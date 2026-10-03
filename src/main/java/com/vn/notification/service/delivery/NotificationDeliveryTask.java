package com.vn.notification.service.delivery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record NotificationDeliveryTask(
        Long queueId,
        String lockOwner,
        String eventKey,
        String providerRequestKey,
        int deliveryAttempt,
        String recipientEmail,
        String subject,
        String templateCode,
        Map<String, Object> payload
) {

    public NotificationDeliveryTask {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
