package com.vn.notification.service.delivery;

public record RenderedNotificationEmail(
        Long queueId,
        String eventKey,
        String providerRequestKey,
        int deliveryAttempt,
        String recipientEmail,
        String subject,
        String html
) {
}
