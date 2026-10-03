package com.vn.notification.service.impl.webhook;

import com.svix.Webhook;
import com.svix.exceptions.EmptyWebhookSecretException;
import com.svix.exceptions.WebhookVerificationException;
import com.vn.notification.config.ResendWebhookProperties;
import com.vn.notification.service.webhook.ResendWebhookRejectedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ResendWebhookVerifier {

    private final ResendWebhookProperties properties;

    public void verify(String rawPayload, String messageId, String timestamp, String signature) {
        requireHeader(messageId, "svix-id");
        requireHeader(timestamp, "svix-timestamp");
        requireHeader(signature, "svix-signature");
        if (rawPayload == null || rawPayload.getBytes(StandardCharsets.UTF_8).length > properties.maxPayloadBytes()) {
            throw new ResendWebhookRejectedException("Invalid webhook payload size");
        }

        try {
            Webhook webhook = new Webhook(properties.signingSecret());
            webhook.verify(rawPayload, Map.of(
                    "svix-id", List.of(messageId),
                    "svix-timestamp", List.of(timestamp),
                    "svix-signature", List.of(signature)
            ));
        } catch (EmptyWebhookSecretException exception) {
            throw new IllegalStateException("Resend webhook signing secret is not configured", exception);
        } catch (WebhookVerificationException exception) {
            throw new ResendWebhookRejectedException("Invalid Resend webhook signature", exception);
        }
    }

    private void requireHeader(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new ResendWebhookRejectedException("Missing " + name + " header");
        }
    }
}
