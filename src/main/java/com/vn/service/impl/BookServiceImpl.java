package com.vn.service.impl;

import com.vn.dto.catalog.request.CreateBookRequest;
import com.vn.dto.catalog.request.UpdateBookAuthorsRequest;
import com.vn.dto.catalog.request.UpdateBookRequest;
import com.vn.dto.catalog.response.BookDetailResponse;
import com.vn.dto.catalog.response.BookSummaryResponse;
import com.vn.entity.Author;
import com.vn.entity.Book;
import com.vn.entity.BookImage;
import com.vn.entity.Category;
import com.vn.enums.BookImageType;
import com.vn.enums.BookCopyStatus;
import com.vn.enums.ImageProvider;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.BookMapper;
import com.vn.repository.AuthorRepository;
import com.vn.repository.BookCopyRepository;
import com.vn.repository.BookImageRepository;
import com.vn.repository.BookRepository;
import com.vn.repository.CategoryRepository;
import com.vn.service.BookService;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookServiceImpl implements BookService {

    // Giới hạn số lượng bản ghi tối đa mỗi trang
    private static final int MAX_PAGE_SIZE = 100;

    // Các trạng thái không cho phép xóa sách vì sách đang được sử dụng
    private static final List<BookCopyStatus> ACTIVE_COPY_STATUSES = List.of(
            BookCopyStatus.BORROWED,
            BookCopyStatus.RESERVED,
            BookCopyStatus.OVERDUE,
            BookCopyStatus.ON_HOLD_SHELF
    );

    private final BookRepository bookRepository;
    private final BookCopyRepository bookCopyRepository;
    private final BookImageRepository bookImageRepository;
    private final AuthorRepository authorRepository;
    private final CategoryRepository categoryRepository;
    private final BookMapper bookMapper;

    // Tìm kiếm sách theo nhiều tiêu chí, có phân trang và sắp xếp
    @Override
    @Transactional(readOnly = true)
    public Page<BookSummaryResponse> searchBooks(String q, String title, String isbn, Long authorId,
                                                 String author, Long categoryId, Boolean availableOnly,
                                                 String language, int page, int size, String sort) {
        Pageable pageable = buildPageable(page, size, sort);
        Specification<Book> spec = buildBookSearchSpec(q, title, isbn, authorId, author, categoryId, availableOnly, language);
        Page<Book> books = bookRepository.findAll(spec, pageable);

        // Book không còn giữ imageUrl trực tiếp, nên load ảnh primary theo batch cho cả page.
        Map<Long, BookImage> primaryImagesByBookId = loadPrimaryImages(books.getContent());

        return books.map(book -> bookMapper.toBookSummaryResponse(
                book,
                primaryImagesByBookId.get(book.getId())
        ));
    }

    // Lấy thông tin chi tiết của một sách còn hoạt động
    @Override
    @Transactional(readOnly = true)
    public BookDetailResponse getBook(Long bookId) {
        Book book = getActiveBook(bookId);
        return bookMapper.toBookDetailResponse(book, getPrimaryImage(book.getId()));
    }

    // Tạo mới đầu sách. Bản copy vật lý được tạo riêng qua BookCopyService.
    @Override
    @Transactional
    public BookDetailResponse createBook(CreateBookRequest request) {
        String isbn = normalizeRequired(request.isbn());
        if (bookRepository.existsByIsbnIgnoreCase(isbn)) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
        }

        Book book = Book.builder()
                .title(normalizeRequired(request.title()))
                .isbn(isbn)
                .publishedDate(request.publishedDate())
                .language(normalizeOptional(request.language(), "vi"))
                .edition(normalizeOptional(request.edition(), null))
                .category(getOptionalCategory(request.categoryId()))
                .authors(resolveAuthors(request.authorIds()))
                .totalCopies(0)
                .availableCopies(0)
                .build();

        Book savedBook = bookRepository.save(book);

        // API vẫn nhận imageUrl đơn giản, nhưng persistence thật nằm trong book_images.
        BookImage primaryImage = syncPrimaryBookImage(savedBook, request.imageUrl());

        // Ghi log khi tạo sách thành công
        log.info("eventType={} result={} entityType=BOOK entityId={}",
                LogEvent.CREATE_BOOK, LogResult.SUCCESS, savedBook.getId());

        return bookMapper.toBookDetailResponse(savedBook, primaryImage);
    }

    // Cập nhật một phần thông tin đầu sách
    @Override
    @Transactional
    public BookDetailResponse updateBook(Long bookId, UpdateBookRequest request) {
        Book book = getActiveBook(bookId);

        // Cập nhật tên sách nếu request có truyền lên
        if (request.title() != null) {
            book.setTitle(normalizeRequired(request.title()));
        }

        if (request.publishedDate() != null) {
            book.setPublishedDate(request.publishedDate());
        }

        if (request.language() != null) {
            book.setLanguage(normalizeRequired(request.language()));
        }

        if (request.edition() != null) {
            book.setEdition(normalizeOptional(request.edition(), null));
        }

        BookImage primaryImage = getPrimaryImage(book.getId());

        // Cập nhật danh mục nếu categoryId hợp lệ
        if (request.categoryId() != null) {
            book.setCategory(getCategory(request.categoryId()));
        }

        Book savedBook = bookRepository.save(book);
        if (request.imageUrl() != null) {
            // Null nghĩa là không đổi ảnh; blank string nghĩa là xóa ảnh primary.
            primaryImage = syncPrimaryBookImage(savedBook, request.imageUrl());
        }

        // Ghi log khi cập nhật sách thành công
        log.info("eventType={} result={} entityType=BOOK entityId={}",
                LogEvent.UPDATE_BOOK, LogResult.SUCCESS, savedBook.getId());

        return bookMapper.toBookDetailResponse(savedBook, primaryImage);
    }

    // Xóa mềm sách, không cho xóa nếu còn bản copy đang mượn hoặc đang được giữ chỗ
    @Override
    @Transactional
    public void deleteBook(Long bookId, Long deletedBy) {
        Book book = getActiveBook(bookId);
        if (bookCopyRepository.existsByBookIdAndStatusInAndDeletedAtIsNull(bookId, ACTIVE_COPY_STATUSES)) {
            throw new AppException(ErrorCode.BOOK_HAS_ACTIVE_COPIES);
        }

        book.setDeletedAt(Instant.now());
        book.setDeletedBy(deletedBy);
        bookRepository.save(book);
        bookCopyRepository.softDeleteByBookIdExcludingStatuses(bookId, deletedBy, ACTIVE_COPY_STATUSES);

        // Ghi log khi xóa mềm sách thành công
        log.info("eventType={} result={} entityType=BOOK entityId={} deletedBy={}",
                LogEvent.DELETE_BOOK, LogResult.SUCCESS, bookId, deletedBy);
    }

    // Thay thế toàn bộ danh sách tác giả của một sách
    @Override
    @Transactional
    public BookDetailResponse updateBookAuthors(Long bookId, UpdateBookAuthorsRequest request) {
        Book book = getActiveBook(bookId);
        book.setAuthors(resolveAuthors(request.authorIds()));
        Book savedBook = bookRepository.save(book);

        // Ghi log khi cập nhật tác giả của sách thành công
        log.info("eventType={} result={} entityType=BOOK entityId={}",
                LogEvent.UPDATE_BOOK_AUTHORS, LogResult.SUCCESS, savedBook.getId());

        return bookMapper.toBookDetailResponse(savedBook, getPrimaryImage(savedBook.getId()));
    }

    // Gom ảnh primary theo bookId để response list có coverImage mà không phát sinh N+1 query.
    private Map<Long, BookImage> loadPrimaryImages(Collection<Book> books) {
        if (books.isEmpty()) {
            return Map.of();
        }

        List<Long> bookIds = books.stream()
                .map(Book::getId)
                .toList();

        Map<Long, BookImage> imagesByBookId = new HashMap<>();
        for (BookImage image : bookImageRepository.findPrimaryImagesByBookIds(bookIds)) {
            imagesByBookId.put(image.getBook().getId(), image);
        }

        return imagesByBookId;
    }

    // Trả về ảnh bìa chính cho detail; null nếu sách chưa có ảnh.
    private BookImage getPrimaryImage(Long bookId) {
        return bookImageRepository
                .findFirstByBookIdAndPrimaryImageTrueOrderBySortOrderAscIdAsc(bookId)
                .orElse(null);
    }

    // Đồng bộ field imageUrl của API với bảng book_images. Hàm này chỉ quản lý ảnh primary.
    private BookImage syncPrimaryBookImage(Book book, String imageUrl) {
        String normalizedImageUrl = normalizeOptional(imageUrl, null);
        if (normalizedImageUrl == null) {
            bookImageRepository.deletePrimaryImagesByBookId(book.getId());
            return null;
        }

        // publicId là định danh ổn định của asset Cloudinary, dùng để chống gán trùng.
        BookImageMetadata metadata = extractCloudinaryMetadata(normalizedImageUrl);
        ensureImageNotAttachedToAnotherBook(book.getId(), metadata.publicId());

        BookImage image = bookImageRepository
                .findFirstByBookIdAndPrimaryImageTrueOrderBySortOrderAscIdAsc(book.getId())
                .orElseGet(() -> BookImage.builder()
                        .book(book)
                        .provider(ImageProvider.CLOUDINARY)
                        .assetType(BookImageType.COVER_FRONT)
                        .sortOrder(0)
                        .primaryImage(true)
                        .build());

        image.setPublicId(metadata.publicId());
        image.setSecureUrl(metadata.secureUrl());
        image.setFormat(metadata.format());
        image.setAltText(buildCoverAltText(book));
        image.setPrimaryImage(true);

        return bookImageRepository.save(image);
    }

    private void ensureImageNotAttachedToAnotherBook(Long bookId, String publicId) {
        bookImageRepository.findByProviderAndPublicId(ImageProvider.CLOUDINARY, publicId)
                .filter(image -> !image.getBook().getId().equals(bookId))
                .ifPresent(image -> {
                    throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
                });
    }

    // Parse URL Cloudinary để lấy publicId/format, hỗ trợ cả URL có version và transformation.
    private BookImageMetadata extractCloudinaryMetadata(String imageUrl) {
        URI uri;
        try {
            uri = URI.create(imageUrl);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !"res.cloudinary.com".equalsIgnoreCase(uri.getHost())) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        String[] segments = uri.getPath().split("/");
        int uploadIndex = findSegmentIndex(segments, "upload");
        if (uploadIndex < 0 || uploadIndex >= segments.length - 1) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        int publicIdStart = uploadIndex + 1;

        // Cloudinary URL thường có dạng /image/upload/v123/folder/file.png.
        for (int i = publicIdStart; i < segments.length; i++) {
            if (segments[i].matches("v\\d+")) {
                publicIdStart = i + 1;
                break;
            }
        }

        // Nếu URL đã được optimize như /upload/c_fit,w_320,h_480,q_auto,f_auto/..., bỏ qua transformation.
        while (publicIdStart < segments.length - 1 && looksLikeCloudinaryTransformation(segments[publicIdStart])) {
            publicIdStart++;
        }

        if (publicIdStart >= segments.length) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        String publicIdWithExtension = joinPathSegments(segments, publicIdStart);
        String format = extractFormat(publicIdWithExtension);
        String publicId = removeExtension(publicIdWithExtension);
        if (publicId.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        return new BookImageMetadata(publicId, imageUrl, format);
    }

    private int findSegmentIndex(String[] segments, String expectedSegment) {
        for (int i = 0; i < segments.length; i++) {
            if (expectedSegment.equals(segments[i])) {
                return i;
            }
        }
        return -1;
    }

    private boolean looksLikeCloudinaryTransformation(String segment) {
        return segment.contains(",")
                || segment.matches("[a-z]{1,3}_.+")
                || "f_auto".equals(segment)
                || "q_auto".equals(segment);
    }

    // Giữ lại folder trong publicId, ví dụ images/clean-code thay vì chỉ clean-code.
    private String joinPathSegments(String[] segments, int startIndex) {
        String joined = String.join("/", List.of(segments).subList(startIndex, segments.length));
        return URLDecoder.decode(joined, StandardCharsets.UTF_8);
    }

    private String extractFormat(String publicIdWithExtension) {
        int lastSlashIndex = publicIdWithExtension.lastIndexOf('/');
        int dotIndex = publicIdWithExtension.lastIndexOf('.');
        if (dotIndex <= lastSlashIndex || dotIndex == publicIdWithExtension.length() - 1) {
            return null;
        }

        String format = publicIdWithExtension.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        return format.length() <= 20 ? format : null;
    }

    private String removeExtension(String publicIdWithExtension) {
        int lastSlashIndex = publicIdWithExtension.lastIndexOf('/');
        int dotIndex = publicIdWithExtension.lastIndexOf('.');
        if (dotIndex <= lastSlashIndex) {
            return publicIdWithExtension;
        }
        return publicIdWithExtension.substring(0, dotIndex);
    }

    private String buildCoverAltText(Book book) {
        String altText = "Book cover for " + book.getTitle();
        return altText.length() <= 255 ? altText : altText.substring(0, 255);
    }

    // Tạo điều kiện tìm kiếm động cho API danh sách sách
    private Specification<Book> buildBookSearchSpec(String q, String title, String isbn, Long authorId,
                                                    String author, Long categoryId, Boolean availableOnly,
                                                    String language) {
        return (root, query, cb) -> {
            if (query != null) {
                query.distinct(true);
            }

            // Chỉ lấy sách chưa bị xóa mềm
            var predicate = cb.isNull(root.get("deletedAt"));

            // Tìm kiếm nhanh theo tên sách hoặc ISBN
            String normalizedQ = normalizeSearch(q);
            if (normalizedQ != null) {
                String like = "%" + normalizedQ + "%";
                predicate = cb.and(predicate, cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("isbn")), like)
                ));
            }

            // Lọc theo tên sách
            String normalizedTitle = normalizeSearch(title);
            if (normalizedTitle != null) {
                predicate = cb.and(predicate, cb.like(cb.lower(root.get("title")), "%" + normalizedTitle + "%"));
            }

            // Lọc theo ISBN
            String normalizedIsbn = normalizeSearch(isbn);
            if (normalizedIsbn != null) {
                predicate = cb.and(predicate, cb.like(cb.lower(root.get("isbn")), "%" + normalizedIsbn + "%"));
            }

            // Lọc theo danh mục
            if (categoryId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("category").get("id"), categoryId));
            }

            // Lọc theo ngôn ngữ
            String normalizedLanguage = normalizeSearch(language);
            if (normalizedLanguage != null) {
                predicate = cb.and(predicate, cb.equal(cb.lower(root.get("language")), normalizedLanguage));
            }

            // Chỉ lấy sách còn ít nhất một bản có thể mượn
            if (Boolean.TRUE.equals(availableOnly)) {
                predicate = cb.and(predicate, cb.greaterThan(root.get("availableCopies"), 0));
            }

            // Lọc theo tác giả bằng ID hoặc tên tác giả
            String normalizedAuthor = normalizeSearch(author);
            if (authorId != null || normalizedAuthor != null) {
                var authorJoin = root.join("authors", JoinType.INNER);
                if (authorId != null) {
                    predicate = cb.and(predicate, cb.equal(authorJoin.get("id"), authorId));
                }
                if (normalizedAuthor != null) {
                    predicate = cb.and(predicate, cb.like(cb.lower(authorJoin.get("name")), "%" + normalizedAuthor + "%"));
                }
            }

            return predicate;
        };
    }

    // Tạo Pageable an toàn, giới hạn page và size để tránh request quá lớn
    private Pageable buildPageable(int page, int size, String sort) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, parseSort(sort));
    }

    // Phân tích chuỗi sort và chỉ cho phép sắp xếp theo các field hợp lệ
    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }

        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (!List.of("title", "publishedDate", "createdAt", "availableCopies").contains(property)) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (parts.length > 1) {
            String directionValue = parts[1].trim();
            if ("desc".equalsIgnoreCase(directionValue)) {
                direction = Sort.Direction.DESC;
            } else if (!"asc".equalsIgnoreCase(directionValue)) {
                throw new AppException(ErrorCode.BAD_REQUEST);
            }
        }

        return Sort.by(direction, property);
    }

    // Lấy sách chưa bị xóa mềm, nếu không có thì báo lỗi
    private Book getActiveBook(Long bookId) {
        return bookRepository.findByIdAndDeletedAtIsNull(bookId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Lấy category bắt buộc theo ID
    private Category getCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // Lấy category nếu request có truyền categoryId
    private Category getOptionalCategory(Long categoryId) {
        return categoryId == null ? null : getCategory(categoryId);
    }

    // Kiểm tra danh sách authorId và chuyển thành danh sách Author không trùng lặp
    private Set<Author> resolveAuthors(List<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return new LinkedHashSet<>();
        }

        if (authorIds.stream().anyMatch(id -> id == null)) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        List<Long> uniqueIds = authorIds.stream().distinct().toList();
        List<Author> authors = authorRepository.findAllById(uniqueIds);
        if (authors.size() != uniqueIds.size()) {
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        return new LinkedHashSet<>(authors);
    }

    // Chuẩn hóa field bắt buộc và không cho phép rỗng
    private String normalizeRequired(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
        return normalized;
    }

    // Chuẩn hóa field không bắt buộc, nếu rỗng thì dùng giá trị mặc định
    private String normalizeOptional(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? defaultValue : normalized;
    }

    // Chuẩn hóa keyword tìm kiếm: trim, lowercase, rỗng thì bỏ qua
    private String normalizeSearch(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private record BookImageMetadata(String publicId, String secureUrl, String format) {
    }
}

