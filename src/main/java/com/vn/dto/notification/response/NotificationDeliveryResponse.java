package com.vn.dto.notification.response;

import com.vn.enums.NotificationQueueStatus;
import com.vn.enums.NotificationTargetType;
import com.vn.enums.NotificationType;

import java.time.Instant;

public record NotificationDeliveryResponse(
        Long id,
        String eventKey,
        NotificationType notificationType,
        NotificationTargetType targetType,
        Long targetId,
        NotificationQueueStatus status,
        String recipientEmail,
        int deliveryAttempt,
        int retryCount,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Instant sentAt,
        Instant deliveredAt,
        String providerMessageId,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
}
