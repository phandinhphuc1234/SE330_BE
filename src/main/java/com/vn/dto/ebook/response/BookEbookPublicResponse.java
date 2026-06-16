package com.vn.dto.ebook.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response an toàn cho trang public/member render ebook.
 * Không trả Cloudinary publicId, signed URL hoặc URL PDF trực tiếp.
 */
public record BookEbookPublicResponse(
        Long bookEbookId,
        Long bookId,
        boolean available,
        String status,
        String format,
        Long sizeBytes,
        Integer maxConcurrentLoans,
        Integer loanDurationDays,
        String accessType,
        boolean requiresPayment,
        BigDecimal accessFee,
        String currency,
        Integer accessDurationDays,
        Instant updatedAt
) {
}
