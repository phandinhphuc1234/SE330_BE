package com.vn.service.impl.notification.delivery;

import com.vn.logging.LogEvent;
import com.vn.logging.LogResult;
import com.vn.service.notification.delivery.EmailProviderResult;
import com.vn.service.notification.delivery.NotificationDeliveryBatchSummary;
import com.vn.service.notification.delivery.NotificationDeliveryException;
import com.vn.service.notification.delivery.NotificationDeliveryOutcome;
import com.vn.service.notification.delivery.NotificationDeliveryTask;
import com.vn.service.notification.delivery.NotificationDeliveryTransition;
import com.vn.service.notification.delivery.NotificationEmailClient;
import com.vn.service.notification.delivery.RenderedNotificationEmail;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryCoordinator {

    private final NotificationQueueClaimService claimService;
    private final NotificationTemplateRenderer templateRenderer;
    private final NotificationEmailClient emailClient;
    private final NotificationDeliveryStateService stateService;
    private final NotificationDeliveryMetrics metrics;
    private final MeterRegistry meterRegistry;

    public NotificationDeliveryBatchSummary dispatchBatch() {
        List<NotificationDeliveryTask> tasks = claimService.claimDueBatch();
        if (tasks.isEmpty()) {
            return NotificationDeliveryBatchSummary.empty();
        }

        int sent = 0;
        int retryScheduled = 0;
        int dead = 0;
        int ownershipLost = 0;

        for (NotificationDeliveryTask task : tasks) {
            try {
                NotificationDeliveryTransition transition = deliver(task);
                metrics.recordOutcome(transition.outcome());
                switch (transition.outcome()) {
                    case SENT -> sent++;
                    case RETRY_SCHEDULED -> retryScheduled++;
                    case DEAD -> dead++;
                    case OWNERSHIP_LOST -> ownershipLost++;
                }
            } catch (RuntimeException exception) {
                // A database transition failure must not prevent the remaining
                // claimed rows from being processed. The affected row is later
                // recovered through the stale PROCESSING timeout.
                ownershipLost++;
                log.error(
                        "eventType={} result={} queueId={} reason=STATE_TRANSITION_ERROR",
                        LogEvent.SEND_QUEUED_NOTIFICATION,
                        LogResult.FAILED,
                        task.queueId(),
                        exception
                );
            }
        }

        return new NotificationDeliveryBatchSummary(
                tasks.size(),
                sent,
                retryScheduled,
                dead,
                ownershipLost
        );
    }

    private NotificationDeliveryTransition deliver(NotificationDeliveryTask task) {
        try {
            RenderedNotificationEmail email = templateRenderer.render(task);
            Timer.Sample sample = metrics.startProviderCall(meterRegistry);
            EmailProviderResult providerResult;
            try {
                providerResult = emailClient.send(email);
            } finally {
                metrics.stopProviderCall(sample);
            }
            NotificationDeliveryTransition transition = stateService.markSent(
                    task,
                    providerResult.providerMessageId()
            );
            logTransition(task, transition, null);
            return transition;
        } catch (NotificationDeliveryException failure) {
            NotificationDeliveryTransition transition = stateService.markFailed(task, failure);
            logTransition(task, transition, failure.errorCode());
            return transition;
        } catch (RuntimeException failure) {
            NotificationDeliveryException retryableFailure = NotificationDeliveryException.retryable(
                    "UNEXPECTED_DELIVERY_ERROR",
                    null,
                    failure
            );
            NotificationDeliveryTransition transition = stateService.markFailed(task, retryableFailure);
            logTransition(task, transition, retryableFailure.errorCode());
            return transition;
        }
    }

    private void logTransition(
            NotificationDeliveryTask task,
            NotificationDeliveryTransition transition,
            String errorCode
    ) {
        NotificationDeliveryOutcome outcome = transition.outcome();
        if (outcome == NotificationDeliveryOutcome.SENT) {
            log.info(
                    "eventType={} result={} queueId={} eventKey={} outcome={}",
                    LogEvent.SEND_QUEUED_NOTIFICATION,
                    LogResult.SUCCESS,
                    task.queueId(),
                    task.eventKey(),
                    outcome
            );
            return;
        }
        log.warn(
                "eventType={} result={} queueId={} eventKey={} outcome={} errorCode={} nextAttemptAt={}",
                LogEvent.SEND_QUEUED_NOTIFICATION,
                LogResult.FAILED,
                task.queueId(),
                task.eventKey(),
                outcome,
                errorCode,
                transition.nextAttemptAt()
        );
    }
}
