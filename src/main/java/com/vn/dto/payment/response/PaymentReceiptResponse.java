package com.vn.dto.payment.response;

import java.time.Instant;

public record PaymentReceiptResponse(
        Long paymentId,
        String receiptNumber,
        String paymentCode,
        Long memberId,
        String memberName,
        String memberEmail,
        String provider,
        String providerTransactionId,
        String providerResponseCode,
        String providerTransactionStatus,
        String purpose,
        String targetType,
        Long targetId,
        String itemTitle,
        Long amount,
        String currency,
        String status,
        Instant paidAt,
        Instant createdAt
) {
}
