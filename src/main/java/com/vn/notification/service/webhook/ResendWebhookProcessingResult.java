package com.vn.notification.service.webhook;

public enum ResendWebhookProcessingResult {
    PROCESSED,
    DUPLICATE,
    IGNORED,
    UNMATCHED
}
