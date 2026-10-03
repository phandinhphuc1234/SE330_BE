package com.vn.notification.service;

public record NotificationEnqueueResult(
        Long notificationId,
        Long queueId,
        boolean created
) {
}
