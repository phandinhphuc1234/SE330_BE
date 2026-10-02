package com.vn.dto.notification.response;

import java.util.Map;

public record NotificationDeliverySummaryResponse(
        long total,
        long awaitingDelivery,
        long needsAttention,
        Map<String, Long> byStatus
) {
}
