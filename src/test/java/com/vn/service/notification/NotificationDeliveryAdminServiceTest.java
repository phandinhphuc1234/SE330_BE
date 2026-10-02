package com.vn.service.notification;

import com.vn.dto.notification.response.NotificationDeliveryResponse;
import com.vn.dto.notification.response.NotificationDeliverySummaryResponse;
import com.vn.entity.NotificationQueue;
import com.vn.enums.NotificationQueueStatus;
import com.vn.enums.NotificationTargetType;
import com.vn.enums.NotificationType;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.NotificationDeliveryAuditRepository;
import com.vn.repository.NotificationQueueRepository;
import com.vn.service.impl.NotificationDeliveryAdminServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryAdminServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");

    @Mock private NotificationQueueRepository queueRepository;
    @Mock private NotificationDeliveryAuditRepository auditRepository;

    private NotificationDeliveryAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationDeliveryAdminServiceImpl(
                queueRepository,
                auditRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void retryDeadDeliveryShouldCreateANewProviderAttemptAndAuditIt() {
        NotificationQueue queue = queue(NotificationQueueStatus.DEAD);
        queue.setDeliveryAttempt(1);
        queue.setProviderMessageId("old-provider-id");
        queue.setRetryCount(5);
        queue.setLastError("RESEND_FAILED: rejected");
        when(queueRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(queue));

        NotificationDeliveryResponse result = service.retryDeadDelivery(99L, 10L);

        assertThat(result.status()).isEqualTo(NotificationQueueStatus.PENDING);
        assertThat(result.deliveryAttempt()).isEqualTo(2);
        assertThat(queue.getProviderRequestKey()).isEqualTo("notification-10-delivery-2");
        assertThat(queue.getProviderMessageId()).isNull();
        assertThat(queue.getRetryCount()).isZero();
        assertThat(queue.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(queue.getLastError()).isNull();
        verify(queueRepository).save(queue);
        verify(auditRepository).recordManualRetry(99L, 10L, 1, 2, "RESEND_FAILED: rejected");
    }

    @Test
    void retryDeadDeliveryShouldRejectANonDeadQueue() {
        when(queueRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(queue(NotificationQueueStatus.RETRY)));

        assertThatThrownBy(() -> service.retryDeadDelivery(99L, 10L))
                .isInstanceOf(AppException.class)
                .satisfies(exception -> assertThat(((AppException) exception).getCode())
                        .isEqualTo(ErrorCode.NOTIFICATION_DELIVERY_NOT_RETRYABLE.getCode()));

        verify(queueRepository, never()).save(any());
        verify(auditRepository, never()).recordManualRetry(any(), any(), any(Integer.class), any(Integer.class), any());
    }

    @Test
    void getSummaryShouldIncludeZeroValueStatusesAndActionableCounts() {
        when(queueRepository.countGroupedByStatus()).thenReturn(List.of(
                new Object[]{NotificationQueueStatus.PENDING, 3L},
                new Object[]{NotificationQueueStatus.DEAD, 2L},
                new Object[]{NotificationQueueStatus.BOUNCED, 1L}
        ));

        NotificationDeliverySummaryResponse summary = service.getSummary();

        assertThat(summary.total()).isEqualTo(6);
        assertThat(summary.awaitingDelivery()).isEqualTo(3);
        assertThat(summary.needsAttention()).isEqualTo(3);
        assertThat(summary.byStatus()).containsEntry("DELIVERED", 0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchShouldMaskRecipientEmail() {
        when(queueRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(queue(NotificationQueueStatus.DEAD))));

        Page<NotificationDeliveryResponse> result = service.search(
                "reader@example.com",
                "dead",
                "account_banned",
                0,
                20
        );

        assertThat(result.getContent()).singleElement()
                .extracting(NotificationDeliveryResponse::recipientEmail)
                .isEqualTo("r***@example.com");
    }

    @Test
    void searchShouldRejectUnknownStatus() {
        assertThatThrownBy(() -> service.search(null, "UNKNOWN", null, 0, 20))
                .isInstanceOf(AppException.class)
                .satisfies(exception -> assertThat(((AppException) exception).getCode())
                        .isEqualTo(ErrorCode.INVALID_NOTIFICATION_DELIVERY_FILTER.getCode()));
    }

    private NotificationQueue queue(NotificationQueueStatus status) {
        return NotificationQueue.builder()
                .id(10L)
                .eventKey("ACCOUNT_BANNED:MEMBER_STATUS_AUDIT:1:EMAIL")
                .providerRequestKey("ACCOUNT_BANNED:MEMBER_STATUS_AUDIT:1:EMAIL")
                .deliveryAttempt(1)
                .notificationType(NotificationType.ACCOUNT_BANNED)
                .targetType(NotificationTargetType.MEMBER_STATUS_AUDIT)
                .targetId(1L)
                .status(status)
                .recipientEmail("reader@example.com")
                .retryCount(0)
                .maxAttempts(5)
                .nextAttemptAt(NOW.minusSeconds(60))
                .createdAt(NOW.minusSeconds(120))
                .updatedAt(NOW.minusSeconds(60))
                .build();
    }
}
