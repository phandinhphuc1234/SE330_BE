package com.vn.service.impl;

import com.vn.dto.ebook.request.UpdateBookEbookRequest;
import com.vn.dto.ebook.response.BookEbookManagementResponse;
import com.vn.dto.ebook.response.BookEbookPublicResponse;
import com.vn.dto.ebook.response.BookEbookUploadResponse;
import com.vn.entity.Book;
import com.vn.entity.BookEbook;
import com.vn.enums.BookEbookStatus;
import com.vn.enums.EbookIngestionStatus;
import com.vn.enums.EbookAccessType;
import com.vn.enums.MediaProvider;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookEbookRepository;
import com.vn.repository.BookRepository;
import com.vn.service.BookEbookService;
import com.vn.service.ebook.EbookPdfValidator;
import com.vn.service.impl.ebook.EbookRagIngestionAsyncProcessor;
import com.vn.service.storage.MediaDeliveryType;
import com.vn.service.storage.MediaResourceType;
import com.vn.service.storage.ebook.EbookObjectStorageService;
import com.vn.service.storage.ebook.EbookObjectStorageService.EbookObjectMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookEbookServiceImpl implements BookEbookService {

    private static final String PDF_FORMAT = "pdf";

    private final BookRepository bookRepository;
    private final BookEbookRepository bookEbookRepository;
    private final EbookObjectStorageService ebookObjectStorageService;
    private final EbookPdfValidator ebookPdfValidator;
    private final EbookRagIngestionAsyncProcessor ragIngestionAsyncProcessor;
    private final TransactionTemplate transactionTemplate;

    @Override
    public BookEbookUploadResponse uploadMainPdf(Long bookId, MultipartFile file) {
        Book book = getActiveBook(bookId);
        ebookPdfValidator.validate(file);

        PreparedEbook prepared = transactionTemplate.execute(status -> prepareEbookStorageRow(book));
        if (prepared == null) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        String objectKey = "ebooks/%d/%d/original.pdf".formatted(bookId, prepared.ebookId());
        EbookObjectMetadata metadata = ebookObjectStorageService.upload(objectKey, file);
        BookEbook savedEbook;
        try {
            savedEbook = transactionTemplate.execute(status ->
                    saveUploadedEbook(prepared.ebookId(), metadata)
            );
        } catch (RuntimeException e) {
            cleanupOnlyWhenThisWasANewObject(metadata, prepared.wasNew());
            throw e;
        }

        BookEbook ebookWithScheduledIngestion = scheduleRagIngestion(savedEbook);
        return toResponse(ebookWithScheduledIngestion);
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

    private PreparedEbook prepareEbookStorageRow(Book book) {
        BookEbook ebook = bookEbookRepository.findFirstByBookIdOrderByIdDesc(book.getId()).orElse(null);
        boolean wasNew = ebook == null;
        if (wasNew) {
            ebook = new BookEbook();
            ebook.setBook(book);
            ebook.setProvider(MediaProvider.SEAWEEDFS);
            ebook.setBucketName("pending");
            ebook.setObjectKey("pending/" + UUID.randomUUID());
            ebook.setResourceType(MediaResourceType.RAW);
            ebook.setDeliveryType(MediaDeliveryType.PRIVATE);
            ebook.setFormat(PDF_FORMAT);
            ebook.setStatus(BookEbookStatus.FAILED);
        }
        BookEbook saved = bookEbookRepository.saveAndFlush(ebook);
        return new PreparedEbook(saved.getId(), wasNew);
    }

    private BookEbook saveUploadedEbook(Long ebookId, EbookObjectMetadata metadata) {
        BookEbook ebook = bookEbookRepository.findById(ebookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        ebook.setProvider(MediaProvider.SEAWEEDFS);
        ebook.setPublicId(null);
        ebook.setBucketName(metadata.bucket());
        ebook.setObjectKey(metadata.objectKey());
        ebook.setResourceType(MediaResourceType.RAW);
        ebook.setDeliveryType(MediaDeliveryType.PRIVATE);
        ebook.setFormat(PDF_FORMAT);
        ebook.setMimeType(metadata.contentType());
        ebook.setOriginalFilename(truncate(metadata.originalFilename(), 255));
        ebook.setVersion(null);
        ebook.setSizeBytes(metadata.fileSizeBytes());
        ebook.setChecksum(null);
        ebook.setChecksumSha256(metadata.checksumSha256());
        ebook.setStatus(BookEbookStatus.ACTIVE);
        ebook.setIngestionStatus(EbookIngestionStatus.QUEUED);
        ebook.setRagDocumentId(null);
        ebook.setRagJobId(null);
        ebook.setIngestionLastError(null);
        ebook.setIndexingRequestedAt(Instant.now());

        if (ebook.getMaxConcurrentLoans() == null) {
            ebook.setMaxConcurrentLoans(5);
        }
        if (ebook.getLoanDurationDays() == null) {
            ebook.setLoanDurationDays(14);
        }

        return bookEbookRepository.save(ebook);
    }

    private BookEbook scheduleRagIngestion(BookEbook ebook) {
        try {
            ragIngestionAsyncProcessor.requestIngestionAsync(ebook.getId());
            return ebook;
        } catch (RuntimeException exception) {
            log.warn("Could not schedule RAG ingestion for ebookId={} objectKey={}",
                    ebook.getId(), ebook.getObjectKey(), exception);
            BookEbook failed = transactionTemplate.execute(status ->
                    markIngestionFailed(ebook.getId(), ErrorCode.INTERNAL_SERVER_ERROR.getCode())
            );
            return failed != null ? failed : ebook;
        }
    }

    private BookEbook markIngestionFailed(Long ebookId, String errorCode) {
        BookEbook ebook = bookEbookRepository.findById(ebookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        ebook.setIngestionStatus(EbookIngestionStatus.FAILED);
        ebook.setIngestionLastError(truncate(errorCode, 1000));
        ebook.setIndexingRequestedAt(Instant.now());
        return bookEbookRepository.save(ebook);
    }

    private void cleanupOnlyWhenThisWasANewObject(EbookObjectMetadata metadata, boolean wasNew) {
        if (!wasNew) {
            log.warn("Could not persist overwritten ebook metadata. Keeping S3 object key={} to avoid deleting an existing ebook.", metadata.objectKey());
            return;
        }

        try {
            ebookObjectStorageService.delete(metadata.bucket(), metadata.objectKey());
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not cleanup newly uploaded ebook objectKey={}", metadata.objectKey(), cleanupFailure);
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
                storageIdentifier(ebook),
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
                ebook.getAccessDurationDays(),
                ebook.getIngestionStatus().name(),
                ebook.getRagDocumentId(),
                ebook.getRagJobId()
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
                storageIdentifier(ebook),
                ebook.getResourceType().name(),
                ebook.getDeliveryType().name(),
                ebook.getFormat(),
                ebook.getMimeType(),
                ebook.getOriginalFilename(),
                ebook.getVersion(),
                ebook.getSizeBytes(),
                ebook.getChecksumSha256() != null ? ebook.getChecksumSha256() : ebook.getChecksum(),
                ebook.getStatus().name(),
                ebook.getMaxConcurrentLoans(),
                ebook.getLoanDurationDays(),
                ebook.getAccessType().name(),
                ebook.getAccessFee(),
                ebook.getCurrency(),
                ebook.getAccessDurationDays(),
                ebook.getIngestionStatus().name(),
                ebook.getRagDocumentId(),
                ebook.getRagJobId(),
                ebook.getIngestionLastError(),
                ebook.getIndexingRequestedAt(),
                ebook.getCreatedAt(),
                ebook.getUpdatedAt()
        );
    }

    private String storageIdentifier(BookEbook ebook) {
        return StringUtils.hasText(ebook.getObjectKey()) ? ebook.getObjectKey() : ebook.getPublicId();
    }

    private record PreparedEbook(Long ebookId, boolean wasNew) {
    }
}
