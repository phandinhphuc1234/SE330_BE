package com.vn.service.notification.webhook;

public enum ResendWebhookProcessingResult {
    PROCESSED,
    DUPLICATE,
    IGNORED,
    UNMATCHED
}
