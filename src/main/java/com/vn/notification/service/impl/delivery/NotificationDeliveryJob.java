package com.vn.notification.service.impl.delivery;

import com.vn.shared.logging.LogEvent;
import com.vn.shared.logging.LogResult;
import com.vn.notification.service.delivery.NotificationDeliveryBatchSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "app.notification.delivery.enabled",
        havingValue = "true"
)
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryJob {

    private final NotificationDeliveryCoordinator coordinator;

    @Scheduled(
            fixedDelayString = "${app.notification.delivery.poll-delay-ms:5000}",
            initialDelayString = "${app.notification.delivery.initial-delay-ms:10000}",
            scheduler = "notificationDeliveryScheduler"
    )
    public void dispatchDueNotifications() {
        try {
            NotificationDeliveryBatchSummary summary = coordinator.dispatchBatch();
            if (summary.claimed() > 0) {
                log.info(
                        "eventType={} result={} claimed={} sent={} retryScheduled={} dead={} ownershipLost={}",
                        LogEvent.NOTIFICATION_DELIVERY_BATCH,
                        LogResult.SUCCESS,
                        summary.claimed(),
                        summary.sent(),
                        summary.retryScheduled(),
                        summary.dead(),
                        summary.ownershipLost()
                );
            }
        } catch (RuntimeException exception) {
            log.error(
                    "eventType={} result={} reason={}",
                    LogEvent.NOTIFICATION_DELIVERY_BATCH,
                    LogResult.FAILED,
                    exception.getClass().getSimpleName(),
                    exception
            );
        }
    }
}
