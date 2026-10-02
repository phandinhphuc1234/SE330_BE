package com.vn.service.impl.notification.delivery;

import com.vn.config.NotificationDeliveryProperties;
import com.vn.entity.NotificationQueue;
import com.vn.enums.NotificationQueueStatus;
import com.vn.repository.NotificationQueueRepository;
import com.vn.service.notification.delivery.NotificationDeliveryException;
import com.vn.service.notification.delivery.NotificationDeliveryTask;
import com.vn.service.notification.delivery.NotificationDeliveryTransition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryStateService {

    private static final int MAX_STORED_ERROR_LENGTH = 1_000;

    private final NotificationQueueRepository queueRepository;
    private final NotificationDeliveryProperties properties;
    private final Clock notificationDeliveryClock;

    @Transactional
    public NotificationDeliveryTransition markSent(
            NotificationDeliveryTask task,
            String providerMessageId
    ) {
        NotificationQueue queue = queueRepository.findByIdForUpdate(task.queueId()).orElse(null);
        if (!isOwnedBy(queue, task.lockOwner())) {
            return NotificationDeliveryTransition.ownershipLost();
        }

        Instant now = notificationDeliveryClock.instant();
        queue.setStatus(NotificationQueueStatus.SENT);
        queue.setProviderMessageId(providerMessageId);
        queue.setSentAt(now);
        queue.setLastError(null);
        clearLock(queue);
        queueRepository.save(queue);
        return NotificationDeliveryTransition.sent();
    }

    @Transactional
    public NotificationDeliveryTransition markFailed(
            NotificationDeliveryTask task,
            NotificationDeliveryException failure
    ) {
        NotificationQueue queue = queueRepository.findByIdForUpdate(task.queueId()).orElse(null);
        if (!isOwnedBy(queue, task.lockOwner())) {
            return NotificationDeliveryTransition.ownershipLost();
        }

        Instant now = notificationDeliveryClock.instant();
        int failedAttempts = safeRetryCount(queue) + 1;
        queue.setRetryCount(failedAttempts);
        queue.setLastError(truncate(failure.errorCode()));
        clearLock(queue);

        if (!failure.retryable() || failedAttempts >= safeMaxAttempts(queue)) {
            queue.setStatus(NotificationQueueStatus.DEAD);
            queueRepository.save(queue);
            return NotificationDeliveryTransition.dead();
        }

        Instant nextAttemptAt = now.plus(resolveRetryDelay(failedAttempts, failure.retryAfter()));
        queue.setStatus(NotificationQueueStatus.RETRY);
        queue.setNextAttemptAt(nextAttemptAt);
        queueRepository.save(queue);
        return NotificationDeliveryTransition.retryAt(nextAttemptAt);
    }

    private boolean isOwnedBy(NotificationQueue queue, String workerId) {
        return queue != null
                && queue.getStatus() == NotificationQueueStatus.PROCESSING
                && Objects.equals(queue.getLockedBy(), workerId);
    }

    private Duration resolveRetryDelay(int failedAttempts, Duration providerRetryAfter) {
        int exponent = Math.clamp(failedAttempts - 1, 0, 30);
        long multiplier = 1L << exponent;
        Duration calculated;
        try {
            calculated = properties.initialRetryDelay().multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            calculated = properties.maxRetryDelay();
        }
        if (calculated.compareTo(properties.maxRetryDelay()) > 0) {
            calculated = properties.maxRetryDelay();
        }
        if (providerRetryAfter != null && providerRetryAfter.compareTo(calculated) > 0) {
            return providerRetryAfter;
        }
        return calculated;
    }

    private int safeRetryCount(NotificationQueue queue) {
        return queue.getRetryCount() == null ? 0 : Math.max(queue.getRetryCount(), 0);
    }

    private int safeMaxAttempts(NotificationQueue queue) {
        return queue.getMaxAttempts() == null ? 1 : Math.max(queue.getMaxAttempts(), 1);
    }

    private void clearLock(NotificationQueue queue) {
        queue.setLockedAt(null);
        queue.setLockedBy(null);
    }

    private String truncate(String value) {
        String safeValue = value == null || value.isBlank() ? "UNKNOWN_DELIVERY_ERROR" : value.strip();
        return safeValue.substring(0, Math.min(safeValue.length(), MAX_STORED_ERROR_LENGTH));
    }
}
