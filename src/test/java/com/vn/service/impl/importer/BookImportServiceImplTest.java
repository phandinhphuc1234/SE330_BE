package com.vn.service.impl.importer;

import com.vn.dto.catalog.response.BookImportResultResponse;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookCopyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookImportServiceImplTest {

    @Mock
    private BookImportRowService bookImportRowService;

    @Mock
    private BookCopyRepository bookCopyRepository;

    private BookImportServiceImpl bookImportService;

    @BeforeEach
    void setUp() {
        bookImportService = new BookImportServiceImpl(bookImportRowService, bookCopyRepository);
    }

    @Test
    void importBooksFromCsv_shouldRejectRowsAndNotImport_whenBarcodeDuplicatedInSameFile() {
        MockMultipartFile file = csvFile("""
                title,isbn,authors,category,barcode,condition,location,language,published_date,edition
                Clean Code,9780132350884,Robert C. Martin,Tech,LIB-000001,GOOD,Shelf A1,en,2008-08-01,1st
                Nhà Giả Kim,9786041017528,Paulo Coelho,Fiction,LIB-000001,GOOD,Shelf B2,vi,1988-01-01,
                """);

        BookImportResultResponse result = bookImportService.importBooksFromCsv(file);

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.successRows()).isZero();
        assertThat(result.failedRows()).isEqualTo(2);
        assertThat(result.createdBooks()).isZero();
        assertThat(result.createdCopies()).isZero();
        assertThat(result.errors())
                .hasSize(2)
                .allSatisfy(error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE.getCode());
                    assertThat(error.message()).isEqualTo("Barcode bị trùng trong file CSV");
                    assertThat(error.barcode()).isEqualTo("LIB-000001");
                });

        verify(bookImportRowService, never()).importRow(any(), any());
        verify(bookCopyRepository, never()).findExistingLowerBarcodes(any());
    }

    @Test
    void importBooksFromCsv_shouldContinueImporting_whenOneRowFails() {
        MockMultipartFile file = csvFile("""
                title,isbn,authors,category,barcode
                Clean Code,9780132350884,Robert C. Martin,Tech,LIB-000001
                Clean Architecture,9780134494166,Robert C. Martin,Tech,LIB-000002
                """);

        when(bookCopyRepository.findExistingLowerBarcodes(any())).thenReturn(Set.of());
        when(bookImportRowService.importRow(any(BookImportCsvRow.class), any(BookImportCache.class)))
                .thenReturn(new BookImportRowResult(true, 1L, 10L))
                .thenThrow(new AppException(ErrorCode.DUPLICATE_RESOURCE));

        BookImportResultResponse result = bookImportService.importBooksFromCsv(file);

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.successRows()).isEqualTo(1);
        assertThat(result.failedRows()).isEqualTo(1);
        assertThat(result.createdBooks()).isEqualTo(1);
        assertThat(result.createdCopies()).isEqualTo(1);
        assertThat(result.errors())
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE.getCode());
                    assertThat(error.isbn()).isEqualTo("9780134494166");
                    assertThat(error.barcode()).isEqualTo("LIB-000002");
                });
    }

    @Test
    void importBooksFromCsv_shouldRejectRowBeforeImport_whenBarcodeAlreadyExistsInDatabase() {
        MockMultipartFile file = csvFile("""
                title,isbn,authors,category,barcode
                Clean Code,9780132350884,Robert C. Martin,Tech,LIB-000001
                """);

        when(bookCopyRepository.findExistingLowerBarcodes(any())).thenReturn(Set.of("lib-000001"));

        BookImportResultResponse result = bookImportService.importBooksFromCsv(file);

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successRows()).isZero();
        assertThat(result.failedRows()).isEqualTo(1);
        assertThat(result.errors())
                .singleElement()
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE.getCode());
                    assertThat(error.message()).isEqualTo("Barcode đã tồn tại trong hệ thống");
                    assertThat(error.barcode()).isEqualTo("LIB-000001");
                });

        verify(bookImportRowService, never()).importRow(any(), any());
    }

    @Test
    void importBooksFromCsv_shouldReturnParseErrorAndSkipInvalidRow_whenRequiredAuthorMissing() {
        MockMultipartFile file = csvFile("""
                title,isbn,authors,category,barcode
                Clean Code,9780132350884,,Tech,LIB-000001
                """);

        BookImportResultResponse result = bookImportService.importBooksFromCsv(file);

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successRows()).isZero();
        assertThat(result.failedRows()).isEqualTo(1);
        assertThat(result.errors())
                .singleElement()
                .satisfies(error -> assertThat(error.code()).isEqualTo(ErrorCode.BAD_REQUEST.getCode()));

        verify(bookImportRowService, never()).importRow(any(), any());
    }

    @Test
    void importBooksFromCsv_shouldParseQuotedCommaAndEscapedQuote() {
        MockMultipartFile file = csvFile("""
                title,isbn,authors,category,barcode
                "Clean Code, Revised",9780132350884,"Robert ""Uncle Bob"" Martin;Another Author","Tech, Programming",LIB-000001
                """);

        when(bookCopyRepository.findExistingLowerBarcodes(any())).thenReturn(Set.of());
        when(bookImportRowService.importRow(any(BookImportCsvRow.class), any(BookImportCache.class)))
                .thenReturn(new BookImportRowResult(true, 1L, 10L));

        BookImportResultResponse result = bookImportService.importBooksFromCsv(file);

        assertThat(result.successRows()).isEqualTo(1);
        assertThat(result.failedRows()).isZero();

        verify(bookImportRowService).importRow(argThat(row ->
                "Clean Code, Revised".equals(row.title())
                        && "Tech, Programming".equals(row.category())
                        && row.authors().contains("Robert \"Uncle Bob\" Martin")
                        && row.authors().contains("Another Author")
        ), any(BookImportCache.class));
    }

    private MockMultipartFile csvFile(String content) {
        return new MockMultipartFile(
                "file",
                "books.csv",
                "text/csv",
                content.getBytes()
        );
    }
}

