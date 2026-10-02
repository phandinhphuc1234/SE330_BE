package com.vn.service.impl.notification.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.service.notification.webhook.ResendWebhookEvent;
import com.vn.service.notification.webhook.ResendWebhookRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.Instant;

@Component
public class ResendWebhookPayloadParser {

    private static final int MAX_DETAIL_LENGTH = 1_000;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public ResendWebhookEvent parse(String rawPayload, String webhookMessageId) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            if (root == null || !root.isObject()) {
                throw new ResendWebhookRejectedException("Malformed Resend webhook payload");
            }
            String eventType = requiredText(root, "type");
            String providerMessageId = requiredText(root.path("data"), "email_id");
            Instant occurredAt = parseInstant(requiredText(root, "created_at"));
            Long hintedQueueId = parseQueueId(root.path("data").path("tags").path("queue_id"));
            Integer hintedDeliveryAttempt = parseDeliveryAttempt(
                    root.path("data").path("tags").path("delivery_attempt")
            );
            String providerDetail = extractProviderDetail(root.path("data"));
            return new ResendWebhookEvent(
                    webhookMessageId.strip(),
                    eventType,
                    providerMessageId,
                    hintedQueueId,
                    hintedDeliveryAttempt,
                    occurredAt,
                    providerDetail
            );
        } catch (JsonProcessingException | DateTimeException exception) {
            throw new ResendWebhookRejectedException("Malformed Resend webhook payload", exception);
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (!StringUtils.hasText(value)) {
            throw new ResendWebhookRejectedException("Missing webhook field: " + field);
        }
        return value.strip();
    }

    private Instant parseInstant(String value) {
        return Instant.parse(value);
    }

    private Long parseQueueId(JsonNode queueIdNode) {
        if (queueIdNode.isMissingNode() || queueIdNode.isNull()) {
            return null;
        }
        try {
            long value = Long.parseLong(queueIdNode.asText());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer parseDeliveryAttempt(JsonNode attemptNode) {
        if (attemptNode.isMissingNode() || attemptNode.isNull()) {
            return null;
        }
        try {
            int value = Integer.parseInt(attemptNode.asText());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String extractProviderDetail(JsonNode data) {
        String detail = data.path("bounce").path("message").asText("");
        if (!StringUtils.hasText(detail)) {
            detail = data.path("reason").asText("");
        }
        if (!StringUtils.hasText(detail)) {
            return null;
        }
        String normalized = detail.strip();
        return normalized.substring(0, Math.min(normalized.length(), MAX_DETAIL_LENGTH));
    }
}
