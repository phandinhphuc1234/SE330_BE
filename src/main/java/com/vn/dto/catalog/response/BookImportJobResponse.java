package com.vn.dto.catalog.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
// Response trả về cho client khi truy vấn trạng thái tiến trình import CSV.
// Chứa thông tin tổng quan về tiến trình và danh sách lỗi nếu có.
public record BookImportJobResponse(
        UUID jobId,
        String originalFilename,
        String status,
        int totalRows,
        int processedRows,
        int successRows,
        int failedRows,
        int createdBooks,
        int createdCopies,
        String errorMessage,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        List<BookImportRowErrorResponse> errors
) {
}
