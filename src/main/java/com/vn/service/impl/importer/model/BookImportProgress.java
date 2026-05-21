package com.vn.service.impl.importer.model;

import com.vn.dto.catalog.response.BookImportRowErrorResponse;

import java.util.List;

// Delta progress của một bước import, không phải tổng cuối cùng.
// Job tracker nhận object này để cộng dồn processed/success/failed và lưu lỗi từng dòng.
public record BookImportProgress(
        int processedRowsDelta,
        int successRowsDelta,
        int failedRowsDelta,
        int createdBooksDelta,
        int createdCopiesDelta,
        List<BookImportRowErrorResponse> errors
) {

    // Tạo progress cho nhóm row bị reject trước khi vào chunk, ví dụ lỗi parse hoặc duplicate barcode.
    public static BookImportProgress failedRows(List<BookImportRowErrorResponse> errors) {
        return new BookImportProgress(errors.size(), 0, errors.size(), 0, 0, errors);
    }

    // Chuyển kết quả chunk thành progress delta để cập nhật import job.
    public static BookImportProgress fromChunk(BookImportChunkResult result) {
        return new BookImportProgress(
                result.successRows() + result.failedRows(),
                result.successRows(),
                result.failedRows(),
                result.createdBooks(),
                result.createdCopies(),
                result.errors()
        );
    }
}
