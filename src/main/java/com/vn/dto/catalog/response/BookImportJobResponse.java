package com.vn.dto.catalog.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
