package com.vn.service.impl.importer.chunk;

import com.vn.service.impl.importer.model.BookCopyInsertRow;
import com.vn.service.impl.importer.model.BookImportCache;
import com.vn.service.impl.importer.model.BookImportCsvRow;
import com.vn.service.impl.importer.model.PreparedBookCopyImport;
import com.vn.service.impl.importer.model.ResolvedImportBook;

import com.vn.entity.Author;
import com.vn.entity.Book;
import com.vn.entity.Category;
import com.vn.enums.BookCopyStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.AuthorRepository;
import com.vn.repository.BookRepository;
import com.vn.repository.CategoryRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
class BookImportRowService {

    private final BookRepository bookRepository;
    private final AuthorRepository authorRepository;
    private final CategoryRepository categoryRepository;
    private final EntityManager entityManager;

    // Chức năng: resolve dữ liệu nghiệp vụ cho một dòng CSV trước khi batch insert BookCopy.
    PreparedBookCopyImport prepareRow(BookImportCsvRow row, BookImportCache cache) {
        String isbn = normalizeRequired(row.isbn());
        String barcode = normalizeRequired(row.barcode());

        // Tìm hoặc tạo đầu sách dựa trên ISBN.
        // Nhiều dòng có cùng ISBN sẽ dùng chung một Book, nhưng mỗi dòng tạo một BookCopy khác nhau.
        ResolvedImportBook resolvedBook = resolveOrCreateBook(row, isbn, cache);
        Book book = resolvedBook.book();
        Instant now = Instant.now();

        BookCopyInsertRow copy = new BookCopyInsertRow(
                book.getId(),
                barcode,
                BookCopyStatus.AVAILABLE.name(),
                normalizeOptional(row.condition(), null),
                normalizeOptional(row.location(), null),
                now,
                now
        );

        return new PreparedBookCopyImport(resolvedBook.created(), book.getId(), copy);
    }

    // Tìm hoặc tạo Book theo ISBN.
    // ISBN là khóa nghiệp vụ để gom nhiều dòng CSV thành cùng một đầu sách.
    private ResolvedImportBook resolveOrCreateBook(BookImportCsvRow row, String isbn, BookImportCache cache) {
        String cacheKey = normalizeCacheKey(isbn);

        // Ưu tiên lấy bookId từ cache để tránh query DB lặp lại với cùng ISBN trong file import
        Long cachedBookId = cache.getBookId(cacheKey);
        if (cachedBookId != null) {
            return new ResolvedImportBook(entityManager.getReference(Book.class, cachedBookId), false);
        }

        // Nếu chưa có trong cache thì tìm Book active trong DB theo ISBN
        return bookRepository.findByIsbnIgnoreCaseAndDeletedAtIsNull(isbn)
                .map(book -> {
                    cache.putBookId(cacheKey, book.getId());
                    return new ResolvedImportBook(book, false);
                })
                .orElseGet(() -> {
                    // Nếu ISBN chưa tồn tại thì tạo đầu sách mới từ dữ liệu CSV
                    Book createdBook = createBook(row, isbn, cache);
                    cache.putBookId(cacheKey, createdBook.getId());
                    return new ResolvedImportBook(createdBook, true);
                });
    }

    // Tạo mới đầu sách từ dữ liệu trong dòng CSV
    private Book createBook(BookImportCsvRow row, String isbn, BookImportCache cache) {
        // Nếu ISBN đã tồn tại nhưng bị soft delete hoặc không nằm trong query active thì không tạo trùng
        if (bookRepository.findByIsbnIgnoreCase(isbn).isPresent()) {
            throw new AppException(ErrorCode.DUPLICATE_RESOURCE);
        }
        // Tạo object book lưu vào trong repository
        Book book = Book.builder()
                .title(normalizeRequired(row.title()))
                .isbn(isbn)
                .publishedDate(row.publishedDate())
                .language(normalizeOptional(row.language(), "vi"))
                .edition(normalizeOptional(row.edition(), null))
                .category(resolveCategory(row.category(), cache))
                .authors(resolveAuthors(row.authors(), cache))
                .totalCopies(0)
                .availableCopies(0)
                .build();

        return bookRepository.save(book);
    }

    // Tìm hoặc tạo danh sách tác giả theo tên.
    // Dùng cache để tránh query/tạo lại cùng một tác giả nhiều lần trong cùng file import.
    private Set<Author> resolveAuthors(java.util.List<String> authorNames, BookImportCache cache) {
        Set<Author> authors = new LinkedHashSet<>();

        for (String authorName : authorNames) {
            String normalizedName = normalizeRequired(authorName);
            String cacheKey = normalizeCacheKey(normalizedName);

            // Nếu author đã được xử lý trước đó trong file thì lấy reference theo ID từ cache
            Long cachedAuthorId = cache.getAuthorId(cacheKey);
            if (cachedAuthorId != null) {
                authors.add(entityManager.getReference(Author.class, cachedAuthorId));
                continue;
            }

            // Nếu chưa có trong cache thì tìm trong DB, chưa có thì tạo mới
            Author author = authorRepository.findByNameIgnoreCase(normalizedName)
                    .orElseGet(() -> authorRepository.save(Author.builder()
                            .name(normalizedName)
                            .build()));

            cache.putAuthorId(cacheKey, author.getId());
            authors.add(author);
        }

        return authors;
    }

    // Tìm hoặc tạo Category theo tên.
    // Nếu category rỗng thì trả về null, còn nếu có tên thì dùng cache/DB để resolve.
    private Category resolveCategory(String categoryName, BookImportCache cache) {
        String normalizedName = normalizeOptional(categoryName, null);
        if (normalizedName == null) {
            return null;
        }

        String cacheKey = normalizeCacheKey(normalizedName);

        // Nếu category đã được xử lý trước đó trong file thì lấy reference theo ID từ cache
        Long cachedCategoryId = cache.getCategoryId(cacheKey);
        if (cachedCategoryId != null) {
            return entityManager.getReference(Category.class, cachedCategoryId);
        }

        // Nếu chưa có trong cache thì tìm trong DB, chưa có thì tạo mới
        Category category = categoryRepository.findByNameIgnoreCase(normalizedName)
                .orElseGet(() -> categoryRepository.save(Category.builder()
                        .name(normalizedName)
                        .build()));

        cache.putCategoryId(cacheKey, category.getId());
        return category;
    }

    // Chuẩn hóa field bắt buộc: trim và không cho phép null/rỗng
    private String normalizeRequired(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
        return normalized;
    }

    // Chuẩn hóa field optional: trim, nếu null/rỗng thì dùng giá trị mặc định
    private String normalizeOptional(String value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        String normalized = value.trim();
        return normalized.isBlank() ? defaultValue : normalized;
    }

    // Chuẩn hóa key dùng cho cache để so sánh không phân biệt hoa thường
    private String normalizeCacheKey(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
