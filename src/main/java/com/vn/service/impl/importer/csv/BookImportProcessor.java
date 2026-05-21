package com.vn.service.impl.importer.csv;

import com.vn.service.impl.importer.chunk.BookImportChunkProcessor;
import com.vn.service.impl.importer.model.BookImportCache;
import com.vn.service.impl.importer.model.BookImportChunkResult;
import com.vn.service.impl.importer.model.BookImportCsvRow;
import com.vn.service.impl.importer.model.BookImportProgress;
import com.vn.service.impl.importer.model.BookImportProgressListener;
import com.vn.service.impl.importer.model.BookImportSummary;
import com.vn.service.impl.importer.model.ParsedBookImport;

import com.vn.dto.catalog.response.BookImportRowErrorResponse;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.repository.BookCopyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookImportProcessor {

    private static final List<String> REQUIRED_HEADERS = List.of("title", "isbn", "authors", "category", "barcode");
    private static final int MAX_IMPORT_ROWS = 5_000;
    private static final int CHUNK_SIZE = 500;

    private final BookImportChunkProcessor bookImportChunkProcessor;
    private final BookCopyRepository bookCopyRepository;

    // Chức năng: xử lý nội dung file CSV đã được lưu tạm, báo tiến độ qua listener cho import job.
    public BookImportSummary importFromCsv(Path filePath, BookImportProgressListener progressListener) {
        ParsedBookImport parsedImport = parseCsv(filePath);
        List<BookImportCsvRow> rows = new ArrayList<>(parsedImport.rows());
        List<BookImportRowErrorResponse> allErrors = new ArrayList<>(parsedImport.errors());
        int totalRows = rows.size() + allErrors.size();
        progressListener.onTotalRowsKnown(totalRows);
        publishErrors(progressListener, parsedImport.errors());

        List<BookImportRowErrorResponse> duplicateErrors = new ArrayList<>();
        rows = rejectDuplicateBarcodesInFile(rows, duplicateErrors);
        rows = rejectExistingBarcodesInDatabase(rows, duplicateErrors);
        allErrors.addAll(duplicateErrors);
        publishErrors(progressListener, duplicateErrors);

        BookImportCache cache = new BookImportCache();
        int successRows = 0;
        int createdBooks = 0;
        int createdCopies = 0;

        for (int from = 0; from < rows.size(); from += CHUNK_SIZE) {
            int to = Math.min(from + CHUNK_SIZE, rows.size());
            BookImportChunkResult chunkResult = bookImportChunkProcessor.importChunk(rows.subList(from, to), cache);
            successRows += chunkResult.successRows();
            createdBooks += chunkResult.createdBooks();
            createdCopies += chunkResult.createdCopies();
            allErrors.addAll(chunkResult.errors());
            progressListener.onProgress(BookImportProgress.fromChunk(chunkResult));
        }

        BookImportSummary result = new BookImportSummary(
                totalRows,
                successRows,
                allErrors.size(),
                createdBooks,
                createdCopies,
                allErrors
        );

        log.info("eventType={} result={} totalRows={} successRows={} failedRows={} createdBooks={} createdCopies={}",
                LogEvent.IMPORT_BOOKS, LogResult.SUCCESS, result.totalRows(), result.successRows(),
                result.failedRows(), result.createdBooks(), result.createdCopies());

        return result;
    }

    private void publishErrors(BookImportProgressListener progressListener, List<BookImportRowErrorResponse> errors) {
        if (!errors.isEmpty()) {
            progressListener.onProgress(BookImportProgress.failedRows(errors));
        }
    }

    // Chặn barcode trùng ngay trong cùng file CSV trước khi ghi DB.
    private List<BookImportCsvRow> rejectDuplicateBarcodesInFile(
            List<BookImportCsvRow> rows,
            List<BookImportRowErrorResponse> errors) {
        Map<String, Integer> barcodeCounts = new HashMap<>();

        for (BookImportCsvRow row : rows) {
            String barcode = normalizeBarcode(row.barcode());
            if (barcode != null) {
                barcodeCounts.merge(barcode, 1, Integer::sum);
            }
        }

        Set<String> duplicateBarcodes = new HashSet<>();
        for (Map.Entry<String, Integer> entry : barcodeCounts.entrySet()) {
            if (entry.getValue() > 1) {
                duplicateBarcodes.add(entry.getKey());
            }
        }

        if (duplicateBarcodes.isEmpty()) {
            return rows;
        }

        List<BookImportCsvRow> acceptedRows = new ArrayList<>();
        for (BookImportCsvRow row : rows) {
            String barcode = normalizeBarcode(row.barcode());
            if (barcode != null && duplicateBarcodes.contains(barcode)) {
                errors.add(toRowError(row, ErrorCode.DUPLICATE_RESOURCE.getCode(), "Barcode bị trùng trong file CSV"));
            } else {
                acceptedRows.add(row);
            }
        }
        return acceptedRows;
    }

    // Kiểm tra barcode đã tồn tại trong database theo kiểu batch cho toàn bộ file import.
    private List<BookImportCsvRow> rejectExistingBarcodesInDatabase(
            List<BookImportCsvRow> rows,
            List<BookImportRowErrorResponse> errors) {
        Set<String> barcodes = new HashSet<>();

        for (BookImportCsvRow row : rows) {
            String barcode = normalizeBarcode(row.barcode());
            if (barcode != null) {
                barcodes.add(barcode);
            }
        }

        if (barcodes.isEmpty()) {
            return rows;
        }

        Set<String> existingBarcodes = bookCopyRepository.findExistingLowerBarcodes(barcodes);
        if (existingBarcodes.isEmpty()) {
            return rows;
        }

        List<BookImportCsvRow> acceptedRows = new ArrayList<>();
        for (BookImportCsvRow row : rows) {
            String barcode = normalizeBarcode(row.barcode());
            if (barcode != null && existingBarcodes.contains(barcode)) {
                errors.add(toRowError(row, ErrorCode.DUPLICATE_RESOURCE.getCode(), "Barcode đã tồn tại trong hệ thống"));
            } else {
                acceptedRows.add(row);
            }
        }
        return acceptedRows;
    }

    private ParsedBookImport parseCsv(Path filePath) {
        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
             CSVParser parser = csvFormat().parse(reader)) {
            validateHeaders(parser.getHeaderMap().keySet());

            List<BookImportCsvRow> rows = new ArrayList<>();
            List<BookImportRowErrorResponse> errors = new ArrayList<>();

            for (CSVRecord record : parser) {
                if (rows.size() + errors.size() >= MAX_IMPORT_ROWS) {
                    throw new AppException(ErrorCode.BAD_REQUEST);
                }

                int rowNumber = Math.toIntExact(record.getRecordNumber() + 1);
                try {
                    rows.add(toCsvRow(rowNumber, record));
                } catch (AppException ex) {
                    errors.add(new BookImportRowErrorResponse(
                            rowNumber,
                            safeGetColumn(record, "isbn"),
                            safeGetColumn(record, "barcode"),
                            ex.getCode(),
                            ex.getMessage()
                    ));
                }
            }

            return new ParsedBookImport(rows, errors);
        } catch (IOException | IllegalArgumentException ex) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    private CSVFormat csvFormat() {
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .get();
    }

    private void validateHeaders(Set<String> headers) {
        Set<String> normalizedHeaders = new HashSet<>();

        for (String header : headers) {
            normalizedHeaders.add(header.trim().toLowerCase(Locale.ROOT));
        }

        for (String requiredHeader : REQUIRED_HEADERS) {
            if (!normalizedHeaders.contains(requiredHeader)) {
                throw new AppException(ErrorCode.BAD_REQUEST);
            }
        }
    }

    private BookImportCsvRow toCsvRow(int rowNumber, CSVRecord record) {
        return new BookImportCsvRow(
                rowNumber,
                getColumn(record, "title"),
                getColumn(record, "isbn"),
                parseAuthors(getColumn(record, "authors")),
                getColumn(record, "category"),
                getColumn(record, "barcode"),
                getColumn(record, "condition"),
                getColumn(record, "location"),
                getColumn(record, "language"),
                parseDate(getColumn(record, "published_date")),
                getColumn(record, "edition")
        );
    }

    private String getColumn(CSVRecord record, String name) {
        if (!record.isMapped(name)) {
            return null;
        }

        String value = record.get(name);
        return value.isBlank() ? null : value;
    }

    private String safeGetColumn(CSVRecord record, String name) {
        try {
            return getColumn(record, name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private List<String> parseAuthors(String value) {
        if (value == null || value.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        String[] parts = value.split("[;|]");
        LinkedHashSet<String> authors = new LinkedHashSet<>();

        for (String part : parts) {
            String author = part.trim();
            if (!author.isBlank()) {
                authors.add(author);
            }
        }

        if (authors.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        return List.copyOf(authors);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    private String normalizeBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return null;
        }

        return barcode.trim().toLowerCase(Locale.ROOT);
    }

    private BookImportRowErrorResponse toRowError(BookImportCsvRow row, String code, String message) {
        return new BookImportRowErrorResponse(
                row.rowNumber(),
                row.isbn(),
                row.barcode(),
                code,
                message
        );
    }
}
