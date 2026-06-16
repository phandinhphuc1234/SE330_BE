package com.vn.service.impl;

import com.vn.dto.ebook.request.UpdateBookEbookRequest;
import com.vn.dto.ebook.response.BookEbookManagementResponse;
import com.vn.dto.ebook.response.BookEbookPublicResponse;
import com.vn.dto.ebook.response.BookEbookUploadResponse;
import com.vn.entity.Book;
import com.vn.entity.BookEbook;
import com.vn.enums.BookEbookStatus;
import com.vn.enums.EbookAccessType;
import com.vn.enums.MediaProvider;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookEbookRepository;
import com.vn.repository.BookRepository;
import com.vn.service.BookEbookService;
import com.vn.service.ebook.EbookCloudinaryPublicIdBuilder;
import com.vn.service.ebook.EbookPdfValidator;
import com.vn.service.storage.MediaCategory;
import com.vn.service.storage.MediaDeleteCommand;
import com.vn.service.storage.MediaDeliveryType;
import com.vn.service.storage.MediaResourceType;
import com.vn.service.storage.MediaStorageService;
import com.vn.service.storage.MediaUploadCommand;
import com.vn.service.storage.MediaUploadResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookEbookServiceImpl implements BookEbookService {

    private static final String PDF_FORMAT = "pdf";

    private final BookRepository bookRepository;
    private final BookEbookRepository bookEbookRepository;
    private final MediaStorageService mediaStorageService;
    private final EbookCloudinaryPublicIdBuilder publicIdBuilder;
    private final EbookPdfValidator ebookPdfValidator;
    private final TransactionTemplate transactionTemplate;

    @Override
    public BookEbookUploadResponse uploadMainPdf(Long bookId, MultipartFile file) {
        Book book = getActiveBook(bookId);
        ebookPdfValidator.validate(file);

        // publicId cố định theo ISBN để Cloudinary lưu đúng folder pdf/{isbn}/main.pdf.
        String publicId = publicIdBuilder.buildMainPdfPublicId(book);
        BookEbook existingEbook = bookEbookRepository
                .findByProviderAndPublicId(MediaProvider.CLOUDINARY, publicId)
                .orElse(null);

        // Upload lên Cloudinary trước, chỉ ghi DB sau khi provider trả metadata hợp lệ.
        MediaUploadResult uploadResult = uploadAuthenticatedPdf(book, file, publicId);
        try {
            BookEbook savedEbook = transactionTemplate.execute(status ->
                    saveUploadedEbook(book, existingEbook, uploadResult)
            );
            return toResponse(savedEbook);
        } catch (RuntimeException e) {
            cleanupOnlyWhenThisWasANewCloudinaryAsset(publicId, existingEbook);
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public BookEbookPublicResponse getPublicEbook(Long bookId) {
        // Vẫn check book còn active để không expose ebook của sách đã bị xóa mềm.
        getActiveBook(bookId);
        BookEbook ebook = bookEbookRepository
                .findFirstByBookIdAndStatusOrderByIdDesc(bookId, BookEbookStatus.ACTIVE)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));

        return toPublicResponse(ebook);
    }

    @Override
    @Transactional(readOnly = true)
    public BookEbookManagementResponse getManagementEbook(Long bookId, Long bookEbookId) {
        // API quản trị trả thêm metadata storage nhưng không sinh URL đọc PDF.
        getActiveBook(bookId);
        BookEbook ebook = getBookEbook(bookId, bookEbookId);
        return toManagementResponse(ebook);
    }

    @Override
    @Transactional
    public BookEbookManagementResponse updateEbook(Long bookId, Long bookEbookId, UpdateBookEbookRequest request) {
        // Patch từng field nếu request truyền lên, giữ nguyên các field không gửi.
        getActiveBook(bookId);
        BookEbook ebook = getBookEbook(bookId, bookEbookId);

        if (request.maxConcurrentLoans() != null) {
            ebook.setMaxConcurrentLoans(request.maxConcurrentLoans());
        }
        if (request.loanDurationDays() != null) {
            ebook.setLoanDurationDays(request.loanDurationDays());
        }
        if (request.accessDurationDays() != null) {
            ebook.setAccessDurationDays(request.accessDurationDays());
        }
        if (request.currency() != null) {
            ebook.setCurrency(normalizeCurrency(request.currency()));
        }
        if (request.status() != null) {
            ebook.setStatus(normalizeEditableStatus(request.status()));
        }

        applyAccessPolicy(ebook, request);

        return toManagementResponse(bookEbookRepository.save(ebook));
    }

    private BookEbook saveUploadedEbook(Book book, BookEbook existingEbook, MediaUploadResult uploadResult) {
        // Nếu publicId đã tồn tại, cập nhật metadata row cũ vì Cloudinary đã overwrite cùng asset.
        BookEbook ebook = existingEbook != null ? existingEbook : new BookEbook();
        ebook.setBook(book);
        ebook.setProvider(MediaProvider.CLOUDINARY);
        ebook.setPublicId(uploadResult.publicId());
        ebook.setResourceType(MediaResourceType.RAW);
        ebook.setDeliveryType(MediaDeliveryType.AUTHENTICATED);
        ebook.setFormat(resolveFormat(uploadResult));
        ebook.setMimeType(uploadResult.mimeType());
        ebook.setOriginalFilename(truncate(uploadResult.originalFilename(), 255));
        ebook.setVersion(uploadResult.version());
        ebook.setSizeBytes(uploadResult.sizeBytes());
        ebook.setStatus(BookEbookStatus.ACTIVE);

        if (ebook.getMaxConcurrentLoans() == null) {
            ebook.setMaxConcurrentLoans(5);
        }
        if (ebook.getLoanDurationDays() == null) {
            ebook.setLoanDurationDays(14);
        }

        return bookEbookRepository.save(ebook);
    }

    private MediaUploadResult uploadAuthenticatedPdf(Book book, MultipartFile file, String publicId) {
        // RAW + AUTHENTICATED là phần quan trọng để PDF không public như ảnh bìa.
        return mediaStorageService.upload(new MediaUploadCommand(
                file,
                MediaResourceType.RAW,
                MediaCategory.BOOK_PDF,
                publicId,
                MediaDeliveryType.AUTHENTICATED,
                true,
                Map.of(
                        "category", MediaCategory.BOOK_PDF.name(),
                        "bookId", String.valueOf(book.getId())
                ),
                Map.of(
                        "category", MediaCategory.BOOK_PDF.name(),
                        "bookId", String.valueOf(book.getId()),
                        "isbn", book.getIsbn() == null ? "" : book.getIsbn()
                )
        ));
    }

    private void cleanupOnlyWhenThisWasANewCloudinaryAsset(String publicId, BookEbook existingEbook) {
        if (existingEbook != null) {
            // Không xóa asset khi overwrite DB fail, vì đó có thể là ebook đang được quản lý trước đó.
            log.warn("Could not persist overwritten ebook metadata. Keeping Cloudinary asset publicId={} to avoid deleting an existing ebook.", publicId);
            return;
        }

        try {
            mediaStorageService.delete(new MediaDeleteCommand(
                    publicId,
                    MediaResourceType.RAW,
                    MediaDeliveryType.AUTHENTICATED,
                    true
            ));
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not cleanup newly uploaded ebook publicId={}", publicId, cleanupFailure);
        }
    }

    private Book getActiveBook(Long bookId) {
        return bookRepository.findByIdAndDeletedAtIsNull(bookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private BookEbook getBookEbook(Long bookId, Long bookEbookId) {
        return bookEbookRepository.findByIdAndBookId(bookEbookId, bookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Giữ invariant ở service để DB, API và thanh toán sau này cùng hiểu một rule giá.
    private void applyAccessPolicy(BookEbook ebook, UpdateBookEbookRequest request) {
        EbookAccessType accessType = request.accessType() != null ? request.accessType() : ebook.getAccessType();
        BigDecimal accessFee = request.accessFee() != null ? request.accessFee() : ebook.getAccessFee();

        if (accessType == EbookAccessType.FREE) {
            if (request.accessFee() != null && request.accessFee().compareTo(BigDecimal.ZERO) != 0) {
                throw new AppException(ErrorCode.BAD_REQUEST);
            }
            accessFee = BigDecimal.ZERO;
        } else if (accessFee == null || accessFee.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        ebook.setAccessType(accessType);
        ebook.setAccessFee(accessFee);
    }

    // FAILED/DELETED là trạng thái kỹ thuật/lifecycle, không cho staff set thủ công qua form edit.
    private BookEbookStatus normalizeEditableStatus(BookEbookStatus status) {
        if (status == BookEbookStatus.ACTIVE || status == BookEbookStatus.INACTIVE) {
            return status;
        }

        throw new AppException(ErrorCode.BAD_REQUEST);
    }

    // Chuẩn hóa tiền tệ để response/payment dùng cùng format, ví dụ vnd -> VND.
    private String normalizeCurrency(String currency) {
        String normalized = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        return normalized;
    }

    private String resolveFormat(MediaUploadResult uploadResult) {
        if (StringUtils.hasText(uploadResult.format())) {
            return uploadResult.format().toLowerCase(Locale.ROOT);
        }

        return PDF_FORMAT;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength);
    }

    private BookEbookUploadResponse toResponse(BookEbook ebook) {
        return new BookEbookUploadResponse(
                ebook.getId(),
                ebook.getBook().getId(),
                ebook.getProvider().name(),
                ebook.getPublicId(),
                ebook.getResourceType().name(),
                ebook.getDeliveryType().name(),
                ebook.getFormat(),
                ebook.getMimeType(),
                ebook.getOriginalFilename(),
                ebook.getVersion(),
                ebook.getSizeBytes(),
                ebook.getStatus().name(),
                ebook.getMaxConcurrentLoans(),
                ebook.getLoanDurationDays(),
                ebook.getAccessType().name(),
                ebook.getAccessFee(),
                ebook.getCurrency(),
                ebook.getAccessDurationDays()
        );
    }

    // Mapper public cố tình bỏ publicId/deliveryType để frontend không biết đường dẫn asset gốc.
    private BookEbookPublicResponse toPublicResponse(BookEbook ebook) {
        return new BookEbookPublicResponse(
                ebook.getId(),
                ebook.getBook().getId(),
                ebook.getStatus() == BookEbookStatus.ACTIVE,
                ebook.getStatus().name(),
                ebook.getFormat(),
                ebook.getSizeBytes(),
                ebook.getMaxConcurrentLoans(),
                ebook.getLoanDurationDays(),
                ebook.getAccessType().name(),
                ebook.getAccessType() == EbookAccessType.PAID,
                ebook.getAccessFee(),
                ebook.getCurrency(),
                ebook.getAccessDurationDays(),
                ebook.getUpdatedAt()
        );
    }

    // Mapper quản trị trả metadata lưu trữ để staff/admin kiểm tra asset và policy hiện tại.
    private BookEbookManagementResponse toManagementResponse(BookEbook ebook) {
        return new BookEbookManagementResponse(
                ebook.getId(),
                ebook.getBook().getId(),
                ebook.getProvider().name(),
                ebook.getPublicId(),
                ebook.getResourceType().name(),
                ebook.getDeliveryType().name(),
                ebook.getFormat(),
                ebook.getMimeType(),
                ebook.getOriginalFilename(),
                ebook.getVersion(),
                ebook.getSizeBytes(),
                ebook.getChecksum(),
                ebook.getStatus().name(),
                ebook.getMaxConcurrentLoans(),
                ebook.getLoanDurationDays(),
                ebook.getAccessType().name(),
                ebook.getAccessFee(),
                ebook.getCurrency(),
                ebook.getAccessDurationDays(),
                ebook.getCreatedAt(),
                ebook.getUpdatedAt()
        );
    }
}
