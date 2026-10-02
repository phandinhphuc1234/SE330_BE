package com.vn.service;

import com.vn.dto.notification.response.NotificationDeliveryResponse;
import com.vn.dto.notification.response.NotificationDeliverySummaryResponse;
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
