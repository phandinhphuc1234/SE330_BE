package com.vn.dto.payment.response;

import java.time.Instant;

/**
 * Response trả cho frontend để redirect user sang provider.
 * Trạng thái lúc tạo luôn là PENDING; success sẽ được IPN xử lý ở spec sau.
 */
public record CreatePaymentResponse(
        Long paymentId,
        String paymentCode,
        String provider,
        String purpose,
        String targetType,
        Long targetId,
        String status,
        Long amount,
        String currency,
        String paymentUrl,
        Instant expiredAt
) {
}
