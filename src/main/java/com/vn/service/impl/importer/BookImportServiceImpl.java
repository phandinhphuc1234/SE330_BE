package com.vn.service.impl.importer;

import com.vn.dto.catalog.response.BookImportResultResponse;
import com.vn.dto.catalog.response.BookImportRowErrorResponse;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.repository.BookCopyRepository;
import com.vn.service.BookImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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
public class BookImportServiceImpl implements BookImportService {

    // Các cột bắt buộc phải có trong file CSV import sách
    private static final List<String> REQUIRED_HEADERS = List.of("title", "isbn", "authors", "category", "barcode");
    //
    private static final long MAX_IMPORT_FILE_SIZE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_IMPORT_ROWS = 5_000;

    private final BookImportRowService bookImportRowService;
    private final BookCopyRepository bookCopyRepository;

    // Điều phối import sách từ CSV: đọc file, parse dữ liệu, import từng dòng và tổng hợp kết quả
    @Override
    public BookImportResultResponse importBooksFromCsv(MultipartFile file) {
        validateFile(file);

        ParsedBookImport parsedImport = parseCsv(file);
        List<BookImportCsvRow> rows = new ArrayList<>(parsedImport.rows());
        List<BookImportRowErrorResponse> errors = new ArrayList<>(parsedImport.errors());
        // Kiểm tra barcode có đang bị trùng khi upload
        rows = rejectDuplicateBarcodesInFile(rows, errors);
        // Kiểm tra barcode đã tồn tại trong database hay chưa
        rows = rejectExistingBarcodesInDatabase(rows, errors);
        BookImportCache cache = new BookImportCache();

        int totalRows = rows.size() + errors.size();
        int successRows = 0;
        int createdBooks = 0;
        int createdCopies = 0;

        // Import từng dòng hợp lệ, dòng nào lỗi thì ghi nhận lỗi và tiếp tục dòng khác
        for (BookImportCsvRow row : rows) {
            try {
                BookImportRowResult result = bookImportRowService.importRow(row, cache);
                successRows++;
                createdCopies++;

                if (result.createdBook()) {
                    createdBooks++;
                }
            } catch (AppException ex) {
                errors.add(toRowError(row, ex.getCode(), ex.getMessage()));
            } catch (DataIntegrityViolationException ex) {
                errors.add(toRowError(row, ErrorCode.DUPLICATE_RESOURCE.getCode(), ErrorCode.DUPLICATE_RESOURCE.getMessage()));
            } catch (Exception ex) {
                errors.add(toRowError(row, ErrorCode.INTERNAL_SERVER_ERROR.getCode(), ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
            }
        }

        // Tổng hợp kết quả import để trả về cho client
        BookImportResultResponse result = new BookImportResultResponse(
                totalRows,
                successRows,
                errors.size(),
                createdBooks,
                createdCopies,
                errors
        );

        // Ghi log kết quả import
        log.info("eventType={} result={} totalRows={} successRows={} failedRows={} createdBooks={} createdCopies={}",
                LogEvent.IMPORT_BOOKS, LogResult.SUCCESS, result.totalRows(), result.successRows(),
                result.failedRows(), result.createdBooks(), result.createdCopies());

        return result;
    }

    // Giới hạn file giúp request import synchronous không bị treo quá lâu.
    // Nếu cần file lớn hơn mức này, nên chuyển sang async import job thay vì tăng limit vô hạn.
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }

        if (file.getSize() > MAX_IMPORT_FILE_SIZE_BYTES) {
            throw new AppException(ErrorCode.BAD_REQUEST);
        }
    }

    // Chặn barcode trùng ngay trong cùng file CSV trước khi ghi DB.
    // Nếu một barcode xuất hiện nhiều lần trong file, toàn bộ các dòng dùng barcode đó sẽ bị reject.
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
                errors.add(toRowError(
                        row,
                        ErrorCode.DUPLICATE_RESOURCE.getCode(),
                        "Barcode bị trùng trong file CSV"
                ));
            } else {
                acceptedRows.add(row);
            }
        }

        return acceptedRows;
    }

    // Kiểm tra barcode đã tồn tại trong database theo kiểu batch cho toàn bộ file import.
    // Mục đích:
    // - Tránh gọi existsByBarcodeIgnoreCase(...) cho từng dòng CSV.
    // - Với file lớn vài nghìn dòng, gọi từng dòng sẽ tạo ra hàng nghìn query rất chậm.
    // - Thay vào đó, gom toàn bộ barcode trong file thành một Set rồi query DB một lần.
    // - Dòng nào có barcode đã tồn tại thì đưa vào danh sách errors.
    // - Chỉ trả về các dòng còn hợp lệ để tiếp tục import.
    private List<BookImportCsvRow> rejectExistingBarcodesInDatabase(
            List<BookImportCsvRow> rows,
            List<BookImportRowErrorResponse> errors) {

        // Dùng Set để gom barcode không trùng trong file trước khi query DB
        Set<String> barcodes = new HashSet<>();

        for (BookImportCsvRow row : rows) {
            // Chuẩn hóa barcode về dạng lowercase/trim để so sánh không phân biệt hoa thường
            String barcode = normalizeBarcode(row.barcode());

            if (barcode != null) {
                barcodes.add(barcode);
            }
        }

        // Nếu file không có barcode hợp lệ nào thì không cần query DB
        if (barcodes.isEmpty()) {
            return rows;
        }

        // Query DB một lần để lấy ra các barcode đã tồn tại trong hệ thống
        Set<String> existingBarcodes = bookCopyRepository.findExistingLowerBarcodes(barcodes);

        // Nếu không có barcode nào bị trùng trong DB thì toàn bộ rows được chấp nhận
        if (existingBarcodes.isEmpty()) {
            return rows;
        }

        // Danh sách các dòng không bị trùng barcode, sẽ được tiếp tục import
        List<BookImportCsvRow> acceptedRows = new ArrayList<>();

        for (BookImportCsvRow row : rows) {
            String barcode = normalizeBarcode(row.barcode());

            // Nếu barcode của dòng hiện tại đã tồn tại trong DB thì reject dòng đó
            if (barcode != null && existingBarcodes.contains(barcode)) {
                errors.add(toRowError(
                        row,
                        ErrorCode.DUPLICATE_RESOURCE.getCode(),
                        "Barcode đã tồn tại trong hệ thống"
                ));
            } else {
                // Nếu barcode chưa tồn tại thì giữ lại để import tiếp
                acceptedRows.add(row);
            }
        }

        return acceptedRows;
    }

    // Parse file CSV thành danh sách dòng hợp lệ và danh sách lỗi format
    private ParsedBookImport parseCsv(MultipartFile file) {
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
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

    // Cấu hình parser CSV tập trung ở đây để không tự split chuỗi thủ công.
    // Apache Commons CSV xử lý đúng các case như: "Book, Volume 1", escape quote "", dòng trống và trim khoảng trắng.
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

    // Đọc header CSV và kiểm tra đủ các cột bắt buộc
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

    // Chuyển một dòng CSV thành DTO trung gian để xử lý import
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

    // Lấy giá trị cột theo tên header, nếu rỗng thì trả về null
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

    // Parse danh sách tác giả, hỗ trợ phân tách bằng dấu ; hoặc |
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

    // Parse ngày xuất bản theo định dạng ISO yyyy-MM-dd
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

    // Chuyển lỗi khi import một dòng thành response lỗi trả về client
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
