package com.vn.notification.service;

import com.vn.notification.config.NotificationDeliveryProperties;
import com.vn.notification.entity.NotificationQueue;
import com.vn.notification.enums.NotificationQueueStatus;
import com.vn.notification.repository.NotificationQueueRepository;
import com.vn.notification.service.impl.delivery.NotificationDeliveryStateService;
import com.vn.notification.service.delivery.NotificationDeliveryException;
import com.vn.notification.service.delivery.NotificationDeliveryOutcome;
import com.vn.notification.service.delivery.NotificationDeliveryTask;
import com.vn.notification.service.delivery.NotificationDeliveryTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    @Mock
    private NotificationQueueRepository queueRepository;

    private NotificationDeliveryStateService service;

    @BeforeEach
    void setUp() {
        service = new NotificationDeliveryStateService(
                queueRepository,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void markSentShouldPersistProviderResultAndReleaseTheClaim() {
        NotificationQueue queue = processingQueue(0, 5, "worker-a");
        when(queueRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(queue));

        NotificationDeliveryTransition transition = service.markSent(task("worker-a"), "provider-123");

        assertThat(transition.outcome()).isEqualTo(NotificationDeliveryOutcome.SENT);
        assertThat(queue.getStatus()).isEqualTo(NotificationQueueStatus.SENT);
        assertThat(queue.getProviderMessageId()).isEqualTo("provider-123");
        assertThat(queue.getSentAt()).isEqualTo(NOW);
        assertThat(queue.getLockedAt()).isNull();
        assertThat(queue.getLockedBy()).isNull();
        verify(queueRepository).save(queue);
    }

    @Test
    void retryableFailureShouldUseExponentialBackoffAndReleaseTheClaim() {
        NotificationQueue queue = processingQueue(1, 5, "worker-a");
        when(queueRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(queue));

        NotificationDeliveryTransition transition = service.markFailed(
                task("worker-a"),
                NotificationDeliveryException.retryable("RESEND_HTTP_503", null, null)
        );

        assertThat(transition.outcome()).isEqualTo(NotificationDeliveryOutcome.RETRY_SCHEDULED);
        assertThat(queue.getStatus()).isEqualTo(NotificationQueueStatus.RETRY);
        assertThat(queue.getRetryCount()).isEqualTo(2);
        assertThat(queue.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(queue.getLastError()).isEqualTo("RESEND_HTTP_503");
        assertThat(queue.getLockedBy()).isNull();
    }

    @Test
    void retryableFailureShouldRespectLongerProviderRetryAfter() {
        NotificationQueue queue = processingQueue(0, 5, "worker-a");
        when(queueRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(queue));

        NotificationDeliveryTransition transition = service.markFailed(
                task("worker-a"),
                NotificationDeliveryException.retryable(
                        "RESEND_HTTP_429_RATE_LIMIT_EXCEEDED",
                        Duration.ofMinutes(3),
                        null
                )
        );

        assertThat(transition.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
        assertThat(queue.getStatus()).isEqualTo(NotificationQueueStatus.RETRY);
    }

    @Test
    void exhaustedOrPermanentFailureShouldMoveTheRowToDead() {
        NotificationQueue exhausted = processingQueue(4, 5, "worker-a");
        when(queueRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(exhausted));

        NotificationDeliveryTransition exhaustedTransition = service.markFailed(
                task("worker-a"),
                NotificationDeliveryException.retryable("RESEND_NETWORK_ERROR", null, null)
        );

        assertThat(exhaustedTransition.outcome()).isEqualTo(NotificationDeliveryOutcome.DEAD);
        assertThat(exhausted.getStatus()).isEqualTo(NotificationQueueStatus.DEAD);
        assertThat(exhausted.getRetryCount()).isEqualTo(5);

        NotificationQueue permanent = processingQueue(0, 5, "worker-a");
        when(queueRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(permanent));

        NotificationDeliveryTransition permanentTransition = service.markFailed(
                task("worker-a"),
                NotificationDeliveryException.permanent("TEMPLATE_CODE_INVALID", null)
        );

        assertThat(permanentTransition.outcome()).isEqualTo(NotificationDeliveryOutcome.DEAD);
        assertThat(permanent.getRetryCount()).isEqualTo(1);
    }

    @Test
    void staleWorkerMustNotOverwriteAQueueReclaimedByAnotherWorker() {
        NotificationQueue queue = processingQueue(0, 5, "worker-b");
        when(queueRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(queue));

        NotificationDeliveryTransition transition = service.markSent(task("worker-a"), "provider-123");

        assertThat(transition.outcome()).isEqualTo(NotificationDeliveryOutcome.OWNERSHIP_LOST);
        assertThat(queue.getStatus()).isEqualTo(NotificationQueueStatus.PROCESSING);
        verify(queueRepository, never()).save(queue);
    }

    private NotificationQueue processingQueue(int retryCount, int maxAttempts, String lockOwner) {
        return NotificationQueue.builder()
                .id(10L)
                .status(NotificationQueueStatus.PROCESSING)
                .retryCount(retryCount)
                .maxAttempts(maxAttempts)
                .lockedAt(NOW.minusSeconds(5))
                .lockedBy(lockOwner)
                .build();
    }

    private NotificationDeliveryTask task(String lockOwner) {
        return new NotificationDeliveryTask(
                10L,
                lockOwner,
                "ACCOUNT_BANNED:501:EMAIL",
                "ACCOUNT_BANNED:501:EMAIL",
                1,
                "reader@example.com",
                "Tài khoản đã bị khóa",
                "account-banned",
                Map.of("reason", "Vi phạm quy định")
        );
    }

    private NotificationDeliveryProperties properties() {
        return new NotificationDeliveryProperties(
                true,
                10,
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofMinutes(30),
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                "https://api.resend.com",
                "re_test",
                "Library <no-reply@example.com>"
        );
    }
}
