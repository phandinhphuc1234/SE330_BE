package com.vn.notification.service.impl.delivery;

import com.vn.notification.service.delivery.NotificationDeliveryException;
import com.vn.notification.service.delivery.NotificationDeliveryTask;
import com.vn.notification.service.delivery.RenderedNotificationEmail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class NotificationTemplateRenderer {

    private static final Pattern SAFE_TEMPLATE_CODE = Pattern.compile("[a-z0-9][a-z0-9-]{0,99}");

    private final TemplateEngine templateEngine;

    public RenderedNotificationEmail render(NotificationDeliveryTask task) {
        validate(task);
        try {
            Context context = new Context();
            task.payload().forEach(context::setVariable);
            String html = templateEngine.process(task.templateCode(), context);
            if (!StringUtils.hasText(html)) {
                throw NotificationDeliveryException.permanent("TEMPLATE_RENDERED_EMPTY", null);
            }
            return new RenderedNotificationEmail(
                    task.queueId(),
                    task.eventKey(),
                    task.providerRequestKey(),
                    task.deliveryAttempt(),
                    task.recipientEmail().strip(),
                    task.subject().strip(),
                    html
            );
        } catch (NotificationDeliveryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw NotificationDeliveryException.permanent("TEMPLATE_RENDER_FAILED", exception);
        }
    }

    private void validate(NotificationDeliveryTask task) {
        if (!StringUtils.hasText(task.eventKey())) {
            throw NotificationDeliveryException.permanent("EVENT_KEY_MISSING", null);
        }
        if (!StringUtils.hasText(task.providerRequestKey())) {
            throw NotificationDeliveryException.permanent("PROVIDER_REQUEST_KEY_MISSING", null);
        }
        if (task.deliveryAttempt() < 1) {
            throw NotificationDeliveryException.permanent("DELIVERY_ATTEMPT_INVALID", null);
        }
        if (!StringUtils.hasText(task.recipientEmail())) {
            throw NotificationDeliveryException.permanent("RECIPIENT_EMAIL_MISSING", null);
        }
        if (!StringUtils.hasText(task.subject())) {
            throw NotificationDeliveryException.permanent("EMAIL_SUBJECT_MISSING", null);
        }
        if (!StringUtils.hasText(task.templateCode())
                || !SAFE_TEMPLATE_CODE.matcher(task.templateCode()).matches()) {
            throw NotificationDeliveryException.permanent("TEMPLATE_CODE_INVALID", null);
        }
    }
}
