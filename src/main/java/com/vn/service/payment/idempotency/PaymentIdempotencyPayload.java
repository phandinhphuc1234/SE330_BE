package com.vn.service.payment.idempotency;

import com.vn.enums.IdempotencyStatus;

import java.time.Instant;

/**
 * Payload Redis cho idempotency payment.
 * Giữ requestHash và response/error để retry cùng key không chạy lại side effect.
 */
record PaymentIdempotencyPayload(
        IdempotencyStatus status,
        String requestHash,
        String responseBody,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant completedAt
) {
}
