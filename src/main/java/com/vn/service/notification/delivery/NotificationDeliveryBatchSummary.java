package com.vn.service.notification.delivery;

public record NotificationDeliveryBatchSummary(
        int claimed,
        int sent,
        int retryScheduled,
        int dead,
        int ownershipLost
) {

    public static NotificationDeliveryBatchSummary empty() {
        return new NotificationDeliveryBatchSummary(0, 0, 0, 0, 0);
    }
}
