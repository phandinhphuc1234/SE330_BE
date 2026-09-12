package com.vn.service.impl.importer.chunk;

import com.vn.entity.Author;
import com.vn.entity.Book;
import com.vn.entity.Category;
import com.vn.enums.BookCopyStatus;
import com.vn.repository.AuthorRepository;
import com.vn.repository.BookRepository;
import com.vn.repository.CategoryRepository;
import com.vn.service.impl.importer.model.BookImportCache;
import com.vn.service.impl.importer.model.BookImportCsvRow;
import com.vn.service.impl.importer.model.PreparedBookCopyImport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookImportRowServiceTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private AuthorRepository authorRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private EntityManager entityManager;

    private BookImportRowService rowService;

    @BeforeEach
    void setUp() {
        rowService = new BookImportRowService(
                bookRepository,
                authorRepository,
                categoryRepository,
                entityManager
        );
    }

    @Test
    void prepareRow_shouldCreateBookAuthorCategoryAndPrepareCopy_whenBookDoesNotExist() {
        BookImportCsvRow row = new BookImportCsvRow(
                2,
                "Clean Code",
                "9780132350884",
                List.of("Robert C. Martin"),
                "Tech",
                "LIB-000001",
                "GOOD",
                "Shelf A1",
                "en",
                LocalDate.of(2008, 8, 1),
                "1st"
        );

        when(bookRepository.findByIsbnIgnoreCaseAndDeletedAtIsNull("9780132350884")).thenReturn(Optional.empty());
        when(bookRepository.findByIsbnIgnoreCase("9780132350884")).thenReturn(Optional.empty());
        when(authorRepository.findByNameIgnoreCase("Robert C. Martin")).thenReturn(Optional.empty());
        when(authorRepository.save(any(Author.class))).thenAnswer(invocation -> {
            Author author = invocation.getArgument(0);
            author.setId(1L);
            return author;
        });
        when(categoryRepository.findByNameIgnoreCase("Tech")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> {
            Category category = invocation.getArgument(0);
            category.setId(1L);
            return category;
        });
        when(bookRepository.save(any(Book.class))).thenAnswer(invocation -> {
            Book book = invocation.getArgument(0);
            if (book.getId() == null) {
                book.setId(1L);
            }
            return book;
        });
        PreparedBookCopyImport result = rowService.prepareRow(row, new BookImportCache());

        assertThat(result.createdBook()).isTrue();
        assertThat(result.bookId()).isEqualTo(1L);

        ArgumentCaptor<Book> bookCaptor = ArgumentCaptor.forClass(Book.class);
        verify(bookRepository).save(bookCaptor.capture());
        Book createdBook = bookCaptor.getValue();
        assertThat(createdBook.getTitle()).isEqualTo("Clean Code");
        assertThat(createdBook.getIsbn()).isEqualTo("9780132350884");
        assertThat(createdBook.getCategory().getName()).isEqualTo("Tech");
        assertThat(createdBook.getAuthors()).extracting(Author::getName).containsExactly("Robert C. Martin");

        assertThat(result.copy().bookId()).isEqualTo(1L);
        assertThat(result.copy().barcode()).isEqualTo("LIB-000001");
        assertThat(result.copy().status()).isEqualTo(BookCopyStatus.AVAILABLE.name());
        assertThat(result.copy().condition()).isEqualTo("GOOD");
        assertThat(result.copy().location()).isEqualTo("Shelf A1");
    }

    @Test
    void prepareRow_shouldUseExistingBookAndPrepareOnlyCopy_whenActiveBookExists() {
        Book existingBook = Book.builder()
                .id(1L)
                .title("Clean Code")
                .isbn("9780132350884")
                .language("en")
                .totalCopies(1)
                .availableCopies(1)
                .build();
        BookImportCsvRow row = new BookImportCsvRow(
                2,
                "Ignored Title",
                "9780132350884",
                List.of("Ignored Author"),
                "Ignored Category",
                "LIB-000002",
                "GOOD",
                "Shelf A1",
                "en",
                null,
                null
        );

        when(bookRepository.findByIsbnIgnoreCaseAndDeletedAtIsNull("9780132350884")).thenReturn(Optional.of(existingBook));
        PreparedBookCopyImport result = rowService.prepareRow(row, new BookImportCache());

        assertThat(result.createdBook()).isFalse();
        assertThat(result.bookId()).isEqualTo(1L);
        assertThat(result.copy().bookId()).isEqualTo(1L);
        assertThat(result.copy().barcode()).isEqualTo("LIB-000002");

        verify(bookRepository, never()).save(any());
        verify(authorRepository, never()).findByNameIgnoreCase(any());
        verify(categoryRepository, never()).findByNameIgnoreCase(any());
        verify(bookRepository, never()).findByIsbnIgnoreCase("9780132350884");
    }

    @Test
    void prepareRow_shouldUseCacheAndSkipRepeatedBookAuthorCategoryQueries_whenValuesAlreadyResolved() {
        Book existingBook = Book.builder()
                .id(1L)
                .title("Clean Code")
                .isbn("9780132350884")
                .language("en")
                .totalCopies(1)
                .availableCopies(1)
                .build();
        BookImportCache cache = new BookImportCache();
        cache.putBookId("9780132350884", 1L);
        cache.putAuthorId("robert c. martin", 2L);
        cache.putCategoryId("tech", 3L);
        BookImportCsvRow row = new BookImportCsvRow(
                2,
                "Clean Code",
                "9780132350884",
                List.of("Robert C. Martin"),
                "Tech",
                "LIB-000001",
                null,
                null,
                null,
                null,
                null
        );

        when(entityManager.getReference(Book.class, 1L)).thenReturn(existingBook);
        PreparedBookCopyImport result = rowService.prepareRow(row, cache);

        assertThat(result.createdBook()).isFalse();
        assertThat(result.bookId()).isEqualTo(1L);
        assertThat(result.copy().bookId()).isEqualTo(1L);
        assertThat(result.copy().barcode()).isEqualTo("LIB-000001");
        verify(bookRepository, never()).findByIsbnIgnoreCaseAndDeletedAtIsNull(any());
        verify(bookRepository, never()).save(any());
        verify(authorRepository, never()).findByNameIgnoreCase(any());
        verify(categoryRepository, never()).findByNameIgnoreCase(any());
    }
}

