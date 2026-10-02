package com.vn.service.notification.delivery;

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
