package com.vn.notification.service;

import com.vn.notification.dto.response.NotificationDeliveryResponse;
import com.vn.notification.dto.response.NotificationDeliverySummaryResponse;
import org.springframework.data.domain.Page;

public interface NotificationDeliveryAdminService {

    Page<NotificationDeliveryResponse> search(
            String query,
            String status,
            String notificationType,
            int page,
            int size
    );

    NotificationDeliverySummaryResponse getSummary();

    NotificationDeliveryResponse retryDeadDelivery(Long adminId, Long queueId);
}
