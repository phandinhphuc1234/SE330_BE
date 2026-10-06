package com.vn.book.service.impl.importer.model;

import com.vn.book.dto.response.BookImportRowErrorResponse;

import java.util.List;

// Kết quả xử lý một chunk CSV sau khi đã prepare row, batch insert copy và update book counters.
// Import job dùng số liệu này để cộng dồn progress cho frontend polling.
public record BookImportChunkResult(
        int successRows,
        int failedRows,
        int createdBooks,
        int createdCopies,
        List<BookImportRowErrorResponse> errors
) {
}
