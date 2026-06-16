package com.vn.dto.ebook.response;

import java.math.BigDecimal;

public record BookEbookUploadResponse(
        Long bookEbookId,
        Long bookId,
        String provider,
        // Trả publicId cho admin/staff quản trị asset, nhưng không trả URL đọc PDF.
        String publicId,
        String resourceType,
        String deliveryType,
        String format,
        String mimeType,
        String originalFilename,
        Long version,
        Long sizeBytes,
        String status,
        Integer maxConcurrentLoans,
        Integer loanDurationDays,
        String accessType,
        BigDecimal accessFee,
        String currency,
        Integer accessDurationDays
) {
}
