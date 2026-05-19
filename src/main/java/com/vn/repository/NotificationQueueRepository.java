package com.vn.repository;

import com.vn.entity.NotificationQueue;
import com.vn.enums.NotificationChannel;
import com.vn.enums.NotificationTargetType;
import com.vn.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationQueueRepository extends JpaRepository<NotificationQueue, Long> {

    // Kiểm tra một notification theo target nghiệp vụ đã được tạo chưa để job không gửi trùng.
    boolean existsByNotificationTypeAndTargetTypeAndTargetIdAndChannel(
            NotificationType notificationType,
            NotificationTargetType targetType,
            Long targetId,
            NotificationChannel channel
    );
}
