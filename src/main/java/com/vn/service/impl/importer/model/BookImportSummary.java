package com.vn.service.impl.importer.model;

import com.vn.dto.catalog.response.BookImportRowErrorResponse;

import java.util.List;

// Tổng kết cuối cùng của processor sau khi xử lý xong file CSV.
// Hiện dùng nội bộ cho logging/test; API public lấy kết quả qua BookImportJobResponse.
public record BookImportSummary(
        int totalRows,
        int successRows,
        int failedRows,
        int createdBooks,
        int createdCopies,
        List<BookImportRowErrorResponse> errors
) {
}
