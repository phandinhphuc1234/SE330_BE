package com.vn.service.impl.importer;

import com.vn.dto.catalog.response.BookImportRowErrorResponse;
import com.vn.exception.ErrorCode;
import com.vn.repository.BookCopyRepository;
import com.vn.service.impl.importer.chunk.BookImportChunkProcessor;
import com.vn.service.impl.importer.csv.BookImportProcessor;
import com.vn.service.impl.importer.model.BookImportCache;
import com.vn.service.impl.importer.model.BookImportChunkResult;
import com.vn.service.impl.importer.model.BookImportCsvRow;
import com.vn.service.impl.importer.model.BookImportProgress;
import com.vn.service.impl.importer.model.BookImportProgressListener;
import com.vn.service.impl.importer.model.BookImportSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookImportProcessorTest {

    @TempDir
    private Path tempDir;

    @Mock
    private BookImportChunkProcessor chunkProcessor;

    @Mock
    private BookCopyRepository bookCopyRepository;

    private BookImportProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new BookImportProcessor(chunkProcessor, bookCopyRepository);
    }

    @Test
    void importFromCsv_shouldRejectRowsAndNotImport_whenBarcodeDuplicatedInSameFile() throws Exception {
        Path file = csvFile("""
                title,isbn,authors,category,barcode,condition,location,language,published_date,edition
                Clean Code,9780132350884,Robert C. Martin,Tech,LIB-000001,GOOD,Shelf A1,en,2008-08-01,1st
                Nhà Giả Kim,9786041017528,Paulo Coelho,Fiction,LIB-000001,GOOD,Shelf B2,vi,1988-01-01,
                """);

        BookImportSummary result = processor.importFromCsv(file, noopListener());

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

        verify(chunkProcessor, never()).importChunk(any(), any());
        verify(bookCopyRepository, never()).findExistingLowerBarcodes(any());
    }

    @Test
    void importFromCsv_shouldContinueImporting_whenChunkReturnsRowError() throws Exception {
        Path file = csvFile("""
                title,isbn,authors,category,barcode
                Clean Code,9780132350884,Robert C. Martin,Tech,LIB-000001
                Clean Architecture,9780134494166,Robert C. Martin,Tech,LIB-000002
                """);

        when(bookCopyRepository.findExistingLowerBarcodes(any())).thenReturn(Set.of());
        when(chunkProcessor.importChunk(any(), any()))
                .thenReturn(new BookImportChunkResult(
                        1,
                        1,
                        1,
                        1,
                        List.of(new BookImportRowErrorResponse(
                                3,
                                "9780134494166",
                                "LIB-000002",
                                ErrorCode.DUPLICATE_RESOURCE.getCode(),
                                ErrorCode.DUPLICATE_RESOURCE.getMessage()
                        ))
                ));

        BookImportSummary result = processor.importFromCsv(file, noopListener());

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
    void importFromCsv_shouldRejectRowBeforeImport_whenBarcodeAlreadyExistsInDatabase() throws Exception {
        Path file = csvFile("""
                title,isbn,authors,category,barcode
                Clean Code,9780132350884,Robert C. Martin,Tech,LIB-000001
                """);

        when(bookCopyRepository.findExistingLowerBarcodes(any())).thenReturn(Set.of("lib-000001"));

        BookImportSummary result = processor.importFromCsv(file, noopListener());

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

        verify(chunkProcessor, never()).importChunk(any(), any());
    }

    @Test
    void importFromCsv_shouldReturnParseErrorAndSkipInvalidRow_whenRequiredAuthorMissing() throws Exception {
        Path file = csvFile("""
                title,isbn,authors,category,barcode
                Clean Code,9780132350884,,Tech,LIB-000001
                """);

        BookImportSummary result = processor.importFromCsv(file, noopListener());

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.successRows()).isZero();
        assertThat(result.failedRows()).isEqualTo(1);
        assertThat(result.errors())
                .singleElement()
                .satisfies(error -> assertThat(error.code()).isEqualTo(ErrorCode.BAD_REQUEST.getCode()));

        verify(chunkProcessor, never()).importChunk(any(), any());
    }

    @Test
    void importFromCsv_shouldParseQuotedCommaAndEscapedQuote() throws Exception {
        Path file = csvFile("""
                title,isbn,authors,category,barcode
                "Clean Code, Revised",9780132350884,"Robert ""Uncle Bob"" Martin;Another Author","Tech, Programming",LIB-000001
                """);

        when(bookCopyRepository.findExistingLowerBarcodes(any())).thenReturn(Set.of());
        when(chunkProcessor.importChunk(any(), any()))
                .thenReturn(new BookImportChunkResult(1, 0, 1, 1, List.of()));

        BookImportSummary result = processor.importFromCsv(file, noopListener());

        assertThat(result.successRows()).isEqualTo(1);
        assertThat(result.failedRows()).isZero();

        verify(chunkProcessor).importChunk(argThat(rows -> {
            BookImportCsvRow row = rows.getFirst();
            return "Clean Code, Revised".equals(row.title())
                    && "Tech, Programming".equals(row.category())
                    && row.authors().contains("Robert \"Uncle Bob\" Martin")
                    && row.authors().contains("Another Author");
        }), any(BookImportCache.class));
    }

    private Path csvFile(String content) throws Exception {
        Path file = tempDir.resolve("books.csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private BookImportProgressListener noopListener() {
        return new BookImportProgressListener() {
            @Override
            public void onTotalRowsKnown(int totalRows) {
            }

            @Override
            public void onProgress(BookImportProgress progress) {
            }
        };
    }
}
