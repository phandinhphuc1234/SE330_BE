package com.vn.service.notification.delivery;

import java.time.Duration;

public class NotificationDeliveryException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;
    private final Duration retryAfter;

    private NotificationDeliveryException(
            String errorCode,
            boolean retryable,
            Duration retryAfter,
            Throwable cause
    ) {
        super(errorCode, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public static NotificationDeliveryException retryable(String errorCode, Duration retryAfter, Throwable cause) {
        return new NotificationDeliveryException(errorCode, true, retryAfter, cause);
    }

    public static NotificationDeliveryException permanent(String errorCode, Throwable cause) {
        return new NotificationDeliveryException(errorCode, false, null, cause);
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
