package com.vn.notification.service.webhook;

import java.time.Instant;

public record ResendWebhookEvent(
        String webhookMessageId,
        String eventType,
        String providerMessageId,
        Long hintedQueueId,
        Integer hintedDeliveryAttempt,
        Instant occurredAt,
        String providerDetail
) {
}
