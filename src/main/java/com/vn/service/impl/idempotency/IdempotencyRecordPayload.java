package com.vn.service.impl.idempotency;

import com.vn.enums.IdempotencyStatus;

import java.time.Instant;

/*
 * Payload idempotency được lưu dưới dạng JSON trong Redis.
 *
 * Đây không phải JPA entity. Redis key đóng vai trò primary key, còn TTL của Redis
 * thay cho expires_at trong thiết kế SQL cũ.
 */
public record IdempotencyRecordPayload(
        Long actorId,
        String httpMethod,
        String normalizedPath,
        String idempotencyKey,
        String requestHash,
        IdempotencyStatus status,
        Integer responseCode,
        String responseBody,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant completedAt
) {
}
