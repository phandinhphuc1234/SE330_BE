package com.vn.dto.payment.response;

import java.time.Instant;

/**
 * Response đọc trạng thái payment hiện tại.
 * Frontend dùng response này sau khi user quay về từ provider để xem IPN đã cập nhật chưa.
 */
public record PaymentResponse(
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
        String providerResponseCode,
        String providerTransactionStatus,
        Instant paidAt,
        Instant cancelledAt,
        Instant expiredAt,
        Instant createdAt,
        Instant updatedAt
) {
}
