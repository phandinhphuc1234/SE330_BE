package com.vn.service.impl.importer.model;

import com.vn.dto.catalog.response.BookImportRowErrorResponse;

import java.util.List;

// Kết quả parse file CSV trước khi ghi DB: gồm các dòng hợp lệ và lỗi format theo từng dòng.
// Các lỗi ở đây chưa đụng database, ví dụ thiếu author hoặc published_date sai format.
public record ParsedBookImport(
        List<BookImportCsvRow> rows,
        List<BookImportRowErrorResponse> errors
) {
}

