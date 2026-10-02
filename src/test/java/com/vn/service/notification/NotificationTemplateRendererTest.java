package com.vn.service.notification;

import com.vn.service.impl.notification.delivery.NotificationTemplateRenderer;
import com.vn.service.notification.delivery.NotificationDeliveryException;
import com.vn.service.notification.delivery.NotificationDeliveryTask;
import com.vn.service.notification.delivery.RenderedNotificationEmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationTemplateRendererTest {

    @Mock
    private TemplateEngine templateEngine;

    @Test
    void renderShouldExposePayloadAsTemplateVariables() {
        NotificationTemplateRenderer renderer = new NotificationTemplateRenderer(templateEngine);
        NotificationDeliveryTask task = task("account-banned");
        ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("account-banned"), contextCaptor.capture()))
                .thenReturn("<p>Đã khóa</p>");

        RenderedNotificationEmail result = renderer.render(task);

        assertThat(result.html()).isEqualTo("<p>Đã khóa</p>");
        assertThat(result.queueId()).isEqualTo(10L);
        assertThat(result.recipientEmail()).isEqualTo("reader@example.com");
        assertThat(contextCaptor.getValue().getVariable("reason")).isEqualTo("Vi phạm quy định");
    }

    @Test
    void renderShouldRejectTemplatePathTraversal() {
        NotificationTemplateRenderer renderer = new NotificationTemplateRenderer(templateEngine);
        NotificationDeliveryTask invalidTask = task("../password-reset");

        assertThatThrownBy(() -> renderer.render(invalidTask))
                .isInstanceOf(NotificationDeliveryException.class)
                .satisfies(exception -> assertThat(((NotificationDeliveryException) exception).retryable()).isFalse());
    }

    private NotificationDeliveryTask task(String templateCode) {
        return new NotificationDeliveryTask(
                10L,
                "worker-a",
                "ACCOUNT_BANNED:501:EMAIL",
                "ACCOUNT_BANNED:501:EMAIL",
                1,
                " reader@example.com ",
                " Tài khoản đã bị khóa ",
                templateCode,
                Map.of("reason", "Vi phạm quy định")
        );
    }
}
