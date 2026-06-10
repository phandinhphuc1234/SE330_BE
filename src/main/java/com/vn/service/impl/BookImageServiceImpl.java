package com.vn.service.impl;

import com.vn.dto.catalog.response.BookCoverManagementResponse;
import com.vn.entity.Book;
import com.vn.entity.BookImage;
import com.vn.enums.BookImageStatus;
import com.vn.enums.BookImageType;
import com.vn.enums.ImageProvider;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.mapper.BookImageMapper;
import com.vn.repository.BookImageRepository;
import com.vn.repository.BookRepository;
import com.vn.service.BookImageService;
import com.vn.service.storage.MediaCategory;
import com.vn.service.storage.MediaDeleteCommand;
import com.vn.service.storage.MediaResourceType;
import com.vn.service.storage.MediaStorageService;
import com.vn.service.storage.MediaUploadCommand;
import com.vn.service.storage.MediaUploadResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookImageServiceImpl implements BookImageService {

    private static final long MAX_COVER_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final BookRepository bookRepository;
    private final BookImageRepository bookImageRepository;
    private final MediaStorageService mediaStorageService;
    private final BookImageMapper bookImageMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    public BookCoverManagementResponse addCover(Long bookId, MultipartFile file) {
        Book book = getActiveBook(bookId);
        ensureNoActiveCover(bookId);
        validateCoverFile(file);

        // Upload lên Cloudinary trước; chỉ lưu DB sau khi provider trả metadata hợp lệ.
        MediaUploadResult uploadResult = uploadBookCover(bookId, file);
        try {
            BookImage savedImage = transactionTemplate.execute(status ->
                    bookImageRepository.save(buildActiveCover(book, uploadResult))
            );
            return bookImageMapper.toCoverManagementResponse(savedImage, null);
        } catch (RuntimeException e) {
            // Nếu DB lỗi sau khi upload thành công, cố gắng xóa asset mới để tránh rác trên Cloudinary.
            cleanupNewUpload(uploadResult.publicId());
            throw e;
        }
    }

    @Override
    public BookCoverManagementResponse updateCover(Long bookId, MultipartFile file) {
        Book book = getActiveBook(bookId);
        validateCoverFile(file);

        // Không đụng ảnh cũ trước khi ảnh mới upload thành công.
        MediaUploadResult uploadResult = uploadBookCover(bookId, file);
        BookImage oldImage;
        BookImage newImage;
        try {
            CoverReplacement replacement = transactionTemplate.execute(status -> replaceActiveCover(book, uploadResult));
            oldImage = replacement.oldImage();
            newImage = replacement.newImage();
        } catch (RuntimeException e) {
            // Transaction DB fail thì xóa ảnh mới vừa upload; ảnh cũ trong DB vẫn là primary.
            cleanupNewUpload(uploadResult.publicId());
            throw e;
        }

        // Sau commit mới purge ảnh cũ; nếu purge fail thì giữ DELETE_PENDING để cleanup job retry.
        String oldImageStatus = purgeOldImageIfPossible(oldImage);
        return bookImageMapper.toCoverManagementResponse(newImage, oldImageStatus);
    }

    private CoverReplacement replaceActiveCover(Book book, MediaUploadResult uploadResult) {
        BookImage oldImage = findActiveCover(book.getId());
        if (oldImage != null) {
            // Ảnh cũ không bị xóa vật lý trong transaction, chỉ đổi trạng thái để tránh mất cover khi provider lỗi.
            oldImage.setPrimaryImage(false);
            oldImage.setStatus(BookImageStatus.DELETE_PENDING);
            bookImageRepository.saveAndFlush(oldImage);
        }

        BookImage newImage = bookImageRepository.save(buildActiveCover(book, uploadResult));
        return new CoverReplacement(oldImage, newImage);
    }

    private BookImage buildActiveCover(Book book, MediaUploadResult uploadResult) {
        String secureUrl = uploadResult.secureUrl();
        if (!StringUtils.hasText(secureUrl)) {
            throw new AppException(ErrorCode.CLOUDINARY_UPLOAD_FAILED);
        }

        return BookImage.builder()
                .book(book)
                .provider(ImageProvider.CLOUDINARY)
                .publicId(uploadResult.publicId())
                .secureUrl(secureUrl)
                .version(uploadResult.version())
                .assetType(BookImageType.COVER_FRONT)
                .format(uploadResult.format())
                .mimeType(uploadResult.mimeType())
                .width(uploadResult.width())
                .height(uploadResult.height())
                .sizeBytes(uploadResult.sizeBytes())
                .altText(buildCoverAltText(book))
                .sortOrder(0)
                .primaryImage(true)
                .status(BookImageStatus.ACTIVE)
                .build();
    }

    private void validateCoverFile(MultipartFile file) {
        // Đây là rule nghiệp vụ riêng của book cover; CloudinaryStorageService không biết JPG/PNG/WEBP là gì.
        if (file == null || file.isEmpty() || file.getSize() <= 0 || file.getSize() > MAX_COVER_BYTES) {
            throw new AppException(ErrorCode.INVALID_IMAGE_FILE);
        }

        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new AppException(ErrorCode.INVALID_IMAGE_FILE);
        }

        String extension = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new AppException(ErrorCode.INVALID_IMAGE_FILE);
        }
    }

    private String extractExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }

        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }

        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private Book getActiveBook(Long bookId) {
        return bookRepository.findByIdAndDeletedAtIsNull(bookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void ensureNoActiveCover(Long bookId) {
        if (findActiveCover(bookId) != null) {
            throw new AppException(ErrorCode.BOOK_COVER_ALREADY_EXISTS);
        }
    }

    private BookImage findActiveCover(Long bookId) {
        return bookImageRepository
                .findFirstByBookIdAndPrimaryImageTrueAndStatusOrderBySortOrderAscIdAsc(
                        bookId,
                        BookImageStatus.ACTIVE
                )
                .orElse(null);
    }

    private String purgeOldImageIfPossible(BookImage oldImage) {
        if (oldImage == null) {
            return null;
        }

        try {
            deleteBookCover(oldImage.getPublicId());
            transactionTemplate.executeWithoutResult(status ->
                    bookImageRepository.updateStatusWithDeletedAt(oldImage.getId(), BookImageStatus.PURGED)
            );            return BookImageStatus.PURGED.name();
        } catch (AppException e) {
            log.warn("Old book cover remains DELETE_PENDING imageId={} publicId={}",
                    oldImage.getId(), oldImage.getPublicId());
            return BookImageStatus.DELETE_PENDING.name();
        }
    }

    private void cleanupNewUpload(String publicId) {
        try {
            deleteBookCover(publicId);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not cleanup newly uploaded cover publicId={}", publicId, cleanupFailure);
        }
    }

    private MediaUploadResult uploadBookCover(Long bookId, MultipartFile file) {
        // publicId được sinh ở business service vì đường dẫn này là convention nghiệp vụ của book cover.
        String publicId = "book-covers/book-" + bookId + "/cover-" + UUID.randomUUID();
        return mediaStorageService.upload(new MediaUploadCommand(
                file,
                MediaResourceType.IMAGE,
                MediaCategory.BOOK_COVER,
                publicId,
                Map.of(
                        "category", MediaCategory.BOOK_COVER.name(),
                        "bookId", String.valueOf(bookId)
                ),
                Map.of(
                        "category", MediaCategory.BOOK_COVER.name(),
                        "bookId", String.valueOf(bookId)
                )
        ));
    }

    private void deleteBookCover(String publicId) {
        mediaStorageService.delete(new MediaDeleteCommand(
                publicId,
                MediaResourceType.IMAGE,
                true
        ));
    }

    private String buildCoverAltText(Book book) {
        String altText = "Book cover for " + book.getTitle();
        return altText.length() <= 255 ? altText : altText.substring(0, 255);
    }

    private record CoverReplacement(BookImage oldImage, BookImage newImage) {
    }
}
