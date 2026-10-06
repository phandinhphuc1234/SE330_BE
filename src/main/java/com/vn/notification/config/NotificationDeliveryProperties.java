package com.vn.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.notification.delivery")
public record NotificationDeliveryProperties(
        boolean enabled,
        int batchSize,
        Duration lockTimeout,
        Duration initialRetryDelay,
        Duration maxRetryDelay,
        Duration connectTimeout,
        Duration readTimeout,
        String resendBaseUrl,
        String resendApiKey,
        String fromEmail
) {

    public NotificationDeliveryProperties {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("app.notification.delivery.batch-size must be between 1 and 100");
        }
        lockTimeout = requirePositive(lockTimeout, "lock-timeout");
        initialRetryDelay = requirePositive(initialRetryDelay, "initial-retry-delay");
        maxRetryDelay = requirePositive(maxRetryDelay, "max-retry-delay");
        connectTimeout = requirePositive(connectTimeout, "connect-timeout");
        readTimeout = requirePositive(readTimeout, "read-timeout");
        if (maxRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException(
                    "app.notification.delivery.max-retry-delay must not be shorter than initial-retry-delay"
            );
        }
        if (!StringUtils.hasText(resendBaseUrl)) {
            throw new IllegalArgumentException("app.notification.delivery.resend-base-url is required");
        }
        resendBaseUrl = resendBaseUrl.strip();
        resendApiKey = resendApiKey == null ? "" : resendApiKey.strip();
        fromEmail = fromEmail == null ? "" : fromEmail.strip();
        if (enabled && !StringUtils.hasText(resendApiKey)) {
            throw new IllegalArgumentException(
                    "RESEND_API_KEY is required when notification delivery is enabled"
            );
        }
        if (enabled && !StringUtils.hasText(fromEmail)) {
            throw new IllegalArgumentException(
                    "MAIL_FROM is required when notification delivery is enabled"
            );
        }
    }

    private static Duration requirePositive(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "app.notification.delivery." + propertyName + " must be positive"
            );
        }
        return value;
    }
}
