package com.vn.service.notification.webhook;

public class ResendWebhookRejectedException extends RuntimeException {

    public ResendWebhookRejectedException(String message) {
        super(message);
    }

    public ResendWebhookRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
