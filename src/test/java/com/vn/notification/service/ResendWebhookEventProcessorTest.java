package com.vn.notification.service;

import com.vn.notification.entity.NotificationQueue;
import com.vn.notification.enums.NotificationQueueStatus;
import com.vn.notification.repository.NotificationProviderEventRepository;
import com.vn.notification.repository.NotificationQueueRepository;
import com.vn.notification.service.impl.webhook.ResendWebhookEventProcessor;
import com.vn.notification.service.webhook.ResendWebhookEvent;
import com.vn.notification.service.webhook.ResendWebhookProcessingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResendWebhookEventProcessorTest {

    private static final Instant NOW = Instant.parse("2026-09-30T09:00:00Z");

    @Mock private NotificationProviderEventRepository eventRepository;
    @Mock private NotificationQueueRepository queueRepository;

    private ResendWebhookEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ResendWebhookEventProcessor(
                eventRepository,
                queueRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void processShouldMarkQueueDeliveredUsingTheSignedQueueTag() {
        NotificationQueue queue = queue(NotificationQueueStatus.PROCESSING);
        when(eventRepository.registerIfAbsent(any())).thenReturn(true);
        when(queueRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(queue));
        ResendWebhookEvent event = event("email.delivered", 10L);

        ResendWebhookProcessingResult result = processor.process(event);

        assertThat(result).isEqualTo(ResendWebhookProcessingResult.PROCESSED);
        assertThat(queue.getStatus()).isEqualTo(NotificationQueueStatus.DELIVERED);
        assertThat(queue.getProviderMessageId()).isEqualTo("email-123");
        assertThat(queue.getDeliveredAt()).isEqualTo(event.occurredAt());
        assertThat(queue.getLockedBy()).isNull();
        verify(eventRepository).markProcessed("msg-1", 10L, "PROCESSED", null, NOW);
    }

    @Test
    void processShouldIgnoreAReplayWithTheSameSvixMessageId() {
        when(eventRepository.registerIfAbsent(any())).thenReturn(false);

        ResendWebhookProcessingResult result = processor.process(event("email.delivered", 10L));

        assertThat(result).isEqualTo(ResendWebhookProcessingResult.DUPLICATE);
        verify(queueRepository, never()).findByIdForUpdate(any());
        verify(queueRepository, never()).save(any());
    }

    @Test
    void processShouldNotDowngradeComplaintWhenAnOlderDeliveryArrives() {
        NotificationQueue queue = queue(NotificationQueueStatus.COMPLAINED);
        queue.setProviderMessageId("email-123");
        when(eventRepository.registerIfAbsent(any())).thenReturn(true);
        when(queueRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(queue));

        processor.process(event("email.delivered", 10L));

        assertThat(queue.getStatus()).isEqualTo(NotificationQueueStatus.COMPLAINED);
    }

    @Test
    void processShouldAcknowledgeButTrackAnUnknownProviderMessage() {
        when(eventRepository.registerIfAbsent(any())).thenReturn(true);
        when(queueRepository.findByProviderMessageIdForUpdate("email-123"))
                .thenReturn(Optional.empty());
        ResendWebhookEvent event = event("email.bounced", null);

        ResendWebhookProcessingResult result = processor.process(event);

        assertThat(result).isEqualTo(ResendWebhookProcessingResult.UNMATCHED);
        verify(eventRepository).markProcessed(
                "msg-1", null, "UNMATCHED", "NOTIFICATION_QUEUE_NOT_FOUND", NOW);
    }

    @Test
    void processShouldIgnoreAWebhookFromAnOlderManualDeliveryAttempt() {
        NotificationQueue queue = queue(NotificationQueueStatus.PROCESSING);
        queue.setDeliveryAttempt(2);
        when(eventRepository.registerIfAbsent(any())).thenReturn(true);
        when(queueRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(queue));
        ResendWebhookEvent staleEvent = new ResendWebhookEvent(
                "msg-1",
                "email.bounced",
                "old-email-id",
                10L,
                1,
                Instant.parse("2026-09-30T08:30:00Z"),
                "provider detail"
        );

        ResendWebhookProcessingResult result = processor.process(staleEvent);

        assertThat(result).isEqualTo(ResendWebhookProcessingResult.IGNORED);
        assertThat(queue.getStatus()).isEqualTo(NotificationQueueStatus.PROCESSING);
        verify(queueRepository, never()).save(any());
        verify(eventRepository).markProcessed("msg-1", 10L, "IGNORED", "STALE_DELIVERY_ATTEMPT", NOW);
    }

    private NotificationQueue queue(NotificationQueueStatus status) {
        return NotificationQueue.builder()
                .id(10L)
                .status(status)
                .deliveryAttempt(1)
                .lockedBy("worker-1")
                .lockedAt(Instant.parse("2026-09-30T08:29:00Z"))
                .build();
    }

    private ResendWebhookEvent event(String type, Long hintedQueueId) {
        return new ResendWebhookEvent(
                "msg-1",
                type,
                "email-123",
                hintedQueueId,
                1,
                Instant.parse("2026-09-30T08:30:00Z"),
                "provider detail"
        );
    }
}
