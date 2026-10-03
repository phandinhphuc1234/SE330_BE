package com.vn.notification.service;

import com.vn.notification.service.impl.delivery.NotificationDeliveryCoordinator;
import com.vn.notification.service.impl.delivery.NotificationDeliveryMetrics;
import com.vn.notification.service.impl.delivery.NotificationDeliveryStateService;
import com.vn.notification.service.impl.delivery.NotificationQueueClaimService;
import com.vn.notification.service.impl.delivery.NotificationTemplateRenderer;
import com.vn.notification.service.delivery.EmailProviderResult;
import com.vn.notification.service.delivery.NotificationDeliveryBatchSummary;
import com.vn.notification.service.delivery.NotificationDeliveryException;
import com.vn.notification.service.delivery.NotificationDeliveryTask;
import com.vn.notification.service.delivery.NotificationDeliveryTransition;
import com.vn.notification.service.delivery.NotificationEmailClient;
import com.vn.notification.service.delivery.RenderedNotificationEmail;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryCoordinatorTest {

    @Mock
    private NotificationQueueClaimService claimService;

    @Mock
    private NotificationTemplateRenderer templateRenderer;

    @Mock
    private NotificationEmailClient emailClient;

    @Mock
    private NotificationDeliveryStateService stateService;

    private SimpleMeterRegistry meterRegistry;
    private NotificationDeliveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        coordinator = new NotificationDeliveryCoordinator(
                claimService,
                templateRenderer,
                emailClient,
                stateService,
                new NotificationDeliveryMetrics(meterRegistry),
                meterRegistry
        );
    }

    @Test
    void dispatchBatchShouldRecordSuccessfulDelivery() {
        NotificationDeliveryTask task = task(10L);
        RenderedNotificationEmail email = renderedEmail();
        when(claimService.claimDueBatch()).thenReturn(List.of(task));
        when(templateRenderer.render(task)).thenReturn(email);
        when(emailClient.send(email)).thenReturn(new EmailProviderResult("provider-123"));
        when(stateService.markSent(task, "provider-123")).thenReturn(NotificationDeliveryTransition.sent());

        NotificationDeliveryBatchSummary summary = coordinator.dispatchBatch();

        assertThat(summary).isEqualTo(new NotificationDeliveryBatchSummary(1, 1, 0, 0, 0));
        assertThat(meterRegistry.get("library.notification.delivery.outcomes")
                .tag("outcome", "sent").counter().count()).isEqualTo(1.0);
    }

    @Test
    void dispatchBatchShouldContinueAfterRetryableAndPermanentFailures() {
        NotificationDeliveryTask retryTask = task(10L);
        NotificationDeliveryTask deadTask = task(11L);
        when(claimService.claimDueBatch()).thenReturn(List.of(retryTask, deadTask));
        when(templateRenderer.render(retryTask)).thenReturn(renderedEmail());
        NotificationDeliveryException retryFailure = NotificationDeliveryException.retryable(
                "RESEND_HTTP_503_UNKNOWN",
                null,
                null
        );
        when(emailClient.send(renderedEmail())).thenThrow(retryFailure);
        when(stateService.markFailed(retryTask, retryFailure))
                .thenReturn(NotificationDeliveryTransition.retryAt(Instant.parse("2026-09-29T10:01:00Z")));

        NotificationDeliveryException permanentFailure = NotificationDeliveryException.permanent(
                "TEMPLATE_CODE_INVALID",
                null
        );
        when(templateRenderer.render(deadTask)).thenThrow(permanentFailure);
        when(stateService.markFailed(deadTask, permanentFailure)).thenReturn(NotificationDeliveryTransition.dead());

        NotificationDeliveryBatchSummary summary = coordinator.dispatchBatch();

        assertThat(summary).isEqualTo(new NotificationDeliveryBatchSummary(2, 0, 1, 1, 0));
    }

    private NotificationDeliveryTask task(Long queueId) {
        return new NotificationDeliveryTask(
                queueId,
                "worker-a",
                "ACCOUNT_BANNED:" + queueId + ":EMAIL",
                "ACCOUNT_BANNED:" + queueId + ":EMAIL",
                1,
                "reader@example.com",
                "Tài khoản đã bị khóa",
                "account-banned",
                Map.of("reason", "Vi phạm quy định")
        );
    }

    private RenderedNotificationEmail renderedEmail() {
        return new RenderedNotificationEmail(
                10L,
                "ACCOUNT_BANNED:10:EMAIL",
                "ACCOUNT_BANNED:10:EMAIL",
                1,
                "reader@example.com",
                "Tài khoản đã bị khóa",
                "<p>Đã khóa</p>"
        );
    }
}
