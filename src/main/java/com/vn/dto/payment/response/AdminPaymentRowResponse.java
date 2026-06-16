package com.vn.dto.payment.response;

import java.time.Instant;

public record AdminPaymentRowResponse(
        Long paymentId,
        String paymentCode,
        Long memberId,
        String memberName,
        String memberEmail,
        String provider,
        String providerTransactionId,
        String purpose,
        String targetType,
        Long targetId,
        String itemTitle,
        Long amount,
        String currency,
        String status,
        String providerResponseCode,
        String providerTransactionStatus,
        Instant paidAt,
        Instant expiredAt,
        Instant createdAt,
        Instant updatedAt
) {
}
