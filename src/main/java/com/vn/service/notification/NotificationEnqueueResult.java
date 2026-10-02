package com.vn.service.notification;

public record NotificationEnqueueResult(
        Long notificationId,
        Long queueId,
        boolean created
) {
}
