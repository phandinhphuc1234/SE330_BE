package com.vn.notification.service.impl.delivery;

import com.vn.notification.config.NotificationDeliveryProperties;
import com.vn.notification.entity.NotificationQueue;
import com.vn.notification.repository.NotificationQueueClaimRepository;
import com.vn.notification.repository.NotificationQueueRepository;
import com.vn.notification.service.delivery.NotificationDeliveryTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationQueueClaimService {

    private final NotificationQueueClaimRepository claimRepository;
    private final NotificationQueueRepository queueRepository;
    private final NotificationDeliveryProperties properties;
    private final NotificationWorkerIdentity workerIdentity;
    private final Clock notificationDeliveryClock;

    @Transactional
    public List<NotificationDeliveryTask> claimDueBatch() {
        Instant now = notificationDeliveryClock.instant();
        String workerId = workerIdentity.value();
        List<Long> claimedIds = claimRepository.claimDueEmailIds(
                now,
                now.minus(properties.lockTimeout()),
                workerId,
                properties.batchSize()
        );
        if (claimedIds.isEmpty()) {
            return List.of();
        }

        Map<Long, NotificationQueue> queuesById = queueRepository.findAllById(claimedIds).stream()
                .collect(Collectors.toMap(NotificationQueue::getId, Function.identity()));

        return claimedIds.stream()
                .map(queuesById::get)
                .filter(queue -> queue != null)
                .map(queue -> toTask(queue, workerId))
                .toList();
    }

    private NotificationDeliveryTask toTask(NotificationQueue queue, String workerId) {
        String subject = queue.getNotification() == null ? null : queue.getNotification().getTitle();
        Map<String, Object> payload = queue.getPayload() == null
                ? Map.of()
                : new LinkedHashMap<>(queue.getPayload());
        return new NotificationDeliveryTask(
                queue.getId(),
                workerId,
                queue.getEventKey(),
                queue.getProviderRequestKey(),
                queue.getDeliveryAttempt(),
                queue.getRecipientEmail(),
                subject,
                queue.getTemplateCode(),
                payload
        );
    }
}
