package com.vn.notification.service.impl.webhook;

import com.vn.notification.entity.NotificationQueue;
import com.vn.notification.enums.NotificationQueueStatus;
import com.vn.notification.repository.NotificationProviderEventRepository;
import com.vn.notification.repository.NotificationQueueRepository;
import com.vn.notification.service.webhook.ResendWebhookEvent;
import com.vn.notification.service.webhook.ResendWebhookProcessingResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ResendWebhookEventProcessor {

    private static final String IGNORED_STATUS = "IGNORED";

    private static final Set<String> SUPPORTED_EVENTS = Set.of(
            "email.sent",
            "email.delivered",
            "email.delivery_delayed",
            "email.bounced",
            "email.complained",
            "email.failed",
            "email.suppressed"
    );

    private final NotificationProviderEventRepository eventRepository;
    private final NotificationQueueRepository queueRepository;
    private final Clock notificationDeliveryClock;

    @Transactional
    public ResendWebhookProcessingResult process(ResendWebhookEvent event) {
        if (!eventRepository.registerIfAbsent(event)) {
            return ResendWebhookProcessingResult.DUPLICATE;
        }
        if (!SUPPORTED_EVENTS.contains(event.eventType())) {
            markEvent(event, null, IGNORED_STATUS, "UNSUPPORTED_EVENT_TYPE");
            return ResendWebhookProcessingResult.IGNORED;
        }

        NotificationQueue queue = findQueue(event);
        if (queue == null) {
            markEvent(event, null, "UNMATCHED", "NOTIFICATION_QUEUE_NOT_FOUND");
            return ResendWebhookProcessingResult.UNMATCHED;
        }
        if (isStaleDeliveryAttempt(queue, event)) {
            markEvent(event, queue.getId(), IGNORED_STATUS, "STALE_DELIVERY_ATTEMPT");
            return ResendWebhookProcessingResult.IGNORED;
        }
        if (queue.getProviderMessageId() != null
                && !queue.getProviderMessageId().equals(event.providerMessageId())) {
            markEvent(event, queue.getId(), IGNORED_STATUS, "PROVIDER_MESSAGE_ID_MISMATCH");
            return ResendWebhookProcessingResult.IGNORED;
        }

        if (queue.getProviderMessageId() == null) {
            queue.setProviderMessageId(event.providerMessageId());
        }
        applyTransition(queue, event);
        queueRepository.save(queue);
        markEvent(event, queue.getId(), "PROCESSED", null);
        return ResendWebhookProcessingResult.PROCESSED;
    }

    private NotificationQueue findQueue(ResendWebhookEvent event) {
        if (event.hintedQueueId() != null) {
            return queueRepository.findByIdForUpdate(event.hintedQueueId()).orElse(null);
        }
        return queueRepository.findByProviderMessageIdForUpdate(event.providerMessageId()).orElse(null);
    }

    private boolean isStaleDeliveryAttempt(NotificationQueue queue, ResendWebhookEvent event) {
        int currentAttempt = queue.getDeliveryAttempt() == null ? 1 : queue.getDeliveryAttempt();
        if (event.hintedDeliveryAttempt() == null) {
            return currentAttempt > 1;
        }
        return event.hintedDeliveryAttempt() != currentAttempt;
    }

    private void applyTransition(NotificationQueue queue, ResendWebhookEvent event) {
        switch (event.eventType()) {
            case "email.sent" -> markSentIfNotFinal(queue, event);
            case "email.delivered" -> markDeliveredIfNotRejected(queue, event);
            case "email.bounced", "email.suppressed" -> markBouncedUnlessComplained(queue, event);
            case "email.complained" -> markComplained(queue, event);
            case "email.failed" -> markProviderFailureIfNotFinal(queue, event);
            case "email.delivery_delayed" -> queue.setLastError(providerError("RESEND_DELIVERY_DELAYED", event));
            default -> {
                // Guarded by SUPPORTED_EVENTS; kept for forward-compatible safety.
            }
        }
    }

    private void markSentIfNotFinal(NotificationQueue queue, ResendWebhookEvent event) {
        if (isFinalOutcome(queue.getStatus())) {
            return;
        }
        if (queue.getStatus() == NotificationQueueStatus.PROCESSING) {
            // The callback can race the synchronous Resend API response. Keep
            // the worker lease so markSent() can finish its normal transition.
            queue.setSentAt(event.occurredAt());
            return;
        }
        queue.setStatus(NotificationQueueStatus.SENT);
        queue.setSentAt(event.occurredAt());
        queue.setLastError(null);
        clearLock(queue);
    }

    private void markDeliveredIfNotRejected(NotificationQueue queue, ResendWebhookEvent event) {
        if (queue.getStatus() == NotificationQueueStatus.BOUNCED
                || queue.getStatus() == NotificationQueueStatus.COMPLAINED) {
            return;
        }
        queue.setStatus(NotificationQueueStatus.DELIVERED);
        queue.setDeliveredAt(event.occurredAt());
        queue.setLastError(null);
        clearLock(queue);
    }

    private void markBouncedUnlessComplained(NotificationQueue queue, ResendWebhookEvent event) {
        if (queue.getStatus() == NotificationQueueStatus.COMPLAINED) {
            return;
        }
        queue.setStatus(NotificationQueueStatus.BOUNCED);
        queue.setLastError(providerError("RESEND_BOUNCED", event));
        clearLock(queue);
    }

    private void markComplained(NotificationQueue queue, ResendWebhookEvent event) {
        queue.setStatus(NotificationQueueStatus.COMPLAINED);
        queue.setLastError(providerError("RESEND_COMPLAINED", event));
        clearLock(queue);
    }

    private void markProviderFailureIfNotFinal(NotificationQueue queue, ResendWebhookEvent event) {
        if (queue.getStatus() == NotificationQueueStatus.DELIVERED || isRejected(queue.getStatus())) {
            return;
        }
        queue.setStatus(NotificationQueueStatus.DEAD);
        queue.setLastError(providerError("RESEND_FAILED", event));
        clearLock(queue);
    }

    private boolean isFinalOutcome(NotificationQueueStatus status) {
        return status == NotificationQueueStatus.DELIVERED || isRejected(status);
    }

    private boolean isRejected(NotificationQueueStatus status) {
        return status == NotificationQueueStatus.BOUNCED || status == NotificationQueueStatus.COMPLAINED;
    }

    private String providerError(String code, ResendWebhookEvent event) {
        return event.providerDetail() == null ? code : code + ": " + event.providerDetail();
    }

    private void clearLock(NotificationQueue queue) {
        queue.setLockedAt(null);
        queue.setLockedBy(null);
    }

    private void markEvent(ResendWebhookEvent event, Long queueId, String status, String error) {
        eventRepository.markProcessed(
                event.webhookMessageId(),
                queueId,
                status,
                error,
                notificationDeliveryClock.instant()
        );
    }
}
