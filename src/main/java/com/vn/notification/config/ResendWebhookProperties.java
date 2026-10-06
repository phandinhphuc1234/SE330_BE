package com.vn.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.notification.webhook")
public record ResendWebhookProperties(
        boolean enabled,
        String signingSecret,
        int maxPayloadBytes
) {

    public ResendWebhookProperties {
        signingSecret = signingSecret == null ? "" : signingSecret.strip();
        if (maxPayloadBytes < 1 || maxPayloadBytes > 1_048_576) {
            throw new IllegalArgumentException(
                    "app.notification.webhook.max-payload-bytes must be between 1 and 1048576"
            );
        }
        if (enabled && !StringUtils.hasText(signingSecret)) {
            throw new IllegalArgumentException(
                    "RESEND_WEBHOOK_SIGNING_SECRET is required when the Resend webhook is enabled"
            );
        }
        if (enabled && !signingSecret.startsWith("whsec_")) {
            throw new IllegalArgumentException(
                    "RESEND_WEBHOOK_SIGNING_SECRET must start with whsec_"
            );
        }
    }
}
