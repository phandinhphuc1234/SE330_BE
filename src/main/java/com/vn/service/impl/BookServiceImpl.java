package com.vn.service.impl;

import com.vn.dto.catalog.request.CreateBookRequest;
import com.vn.dto.catalog.request.UpdateBookAuthorsRequest;
import com.vn.dto.catalog.request.UpdateBookRequest;
import com.vn.dto.catalog.response.BookDetailResponse;
import com.vn.dto.catalog.response.BookSummaryResponse;
import com.vn.entity.Author;
import com.vn.entity.Book;
import com.vn.entity.Category;
import com.vn.enums.BookCopyStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.mapper.BookMapper;
import com.vn.repository.AuthorRepository;
import com.vn.repository.BookCopyRepository;
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

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
            BookCopyStatus.RESERVED
    );

    private final BookRepository bookRepository;
    private final BookCopyRepository bookCopyRepository;
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

        return bookRepository.findAll(spec, pageable)
                .map(bookMapper::toBookSummaryResponse);
    }

    // Lấy thông tin chi tiết của một sách còn hoạt động
    @Override
    @Transactional(readOnly = true)
    public BookDetailResponse getBook(Long bookId) {
        return bookMapper.toBookDetailResponse(getActiveBook(bookId));
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

        // Ghi log khi tạo sách thành công
        log.info("eventType={} result={} entityType=BOOK entityId={}",
                LogEvent.CREATE_BOOK, LogResult.SUCCESS, savedBook.getId());

        return bookMapper.toBookDetailResponse(savedBook);
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

        // Cập nhật danh mục nếu categoryId hợp lệ
        if (request.categoryId() != null) {
            book.setCategory(getCategory(request.categoryId()));
        }

        Book savedBook = bookRepository.save(book);

        // Ghi log khi cập nhật sách thành công
        log.info("eventType={} result={} entityType=BOOK entityId={}",
                LogEvent.UPDATE_BOOK, LogResult.SUCCESS, savedBook.getId());

        return bookMapper.toBookDetailResponse(savedBook);
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

        return bookMapper.toBookDetailResponse(savedBook);
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
}

