package com.vn.notification.service.delivery;

import java.time.Instant;

public record NotificationDeliveryTransition(
        NotificationDeliveryOutcome outcome,
        Instant nextAttemptAt
) {

    public static NotificationDeliveryTransition sent() {
        return new NotificationDeliveryTransition(NotificationDeliveryOutcome.SENT, null);
    }

    public static NotificationDeliveryTransition retryAt(Instant nextAttemptAt) {
        return new NotificationDeliveryTransition(NotificationDeliveryOutcome.RETRY_SCHEDULED, nextAttemptAt);
    }

    public static NotificationDeliveryTransition dead() {
        return new NotificationDeliveryTransition(NotificationDeliveryOutcome.DEAD, null);
    }

    public static NotificationDeliveryTransition ownershipLost() {
        return new NotificationDeliveryTransition(NotificationDeliveryOutcome.OWNERSHIP_LOST, null);
    }
}
