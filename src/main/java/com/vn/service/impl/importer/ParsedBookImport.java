package com.vn.service.impl.importer;

import com.vn.dto.catalog.response.BookImportRowErrorResponse;

import java.util.List;

// Kết quả parse file CSV trước khi ghi DB: gồm các dòng hợp lệ và lỗi format theo từng dòng.
record ParsedBookImport(
        List<BookImportCsvRow> rows,
        List<BookImportRowErrorResponse> errors
) {
}

