package com.vn.dto.ebook.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response cho màn staff/admin quản trị ebook.
 * Có storage metadata để debug/quản lý asset, nhưng vẫn không trả URL đọc PDF.
 */
public record BookEbookManagementResponse(
        Long bookEbookId,
        Long bookId,
        String provider,
        String publicId,
        String resourceType,
        String deliveryType,
        String format,
        String mimeType,
        String originalFilename,
        Long version,
        Long sizeBytes,
        String checksum,
        String status,
        Integer maxConcurrentLoans,
        Integer loanDurationDays,
        String accessType,
        BigDecimal accessFee,
        String currency,
        Integer accessDurationDays,
        Instant createdAt,
        Instant updatedAt
) {
}
