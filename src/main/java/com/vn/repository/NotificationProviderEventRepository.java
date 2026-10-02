package com.vn.repository;

import com.vn.service.notification.webhook.ResendWebhookEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class NotificationProviderEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public boolean registerIfAbsent(ResendWebhookEvent event) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO notification_provider_events(
                    webhook_message_id,
                    provider,
                    provider_message_id,
                    hinted_queue_id,
                    hinted_delivery_attempt,
                    event_type,
                    occurred_at,
                    provider_detail
                ) VALUES (?, 'RESEND', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (webhook_message_id) DO NOTHING
                """,
                event.webhookMessageId(),
                event.providerMessageId(),
                event.hintedQueueId(),
                event.hintedDeliveryAttempt(),
                event.eventType(),
                Timestamp.from(event.occurredAt()),
                event.providerDetail()
        );
        return inserted == 1;
    }

    public void markProcessed(String webhookMessageId,
                              Long queueId,
                              String status,
                              String processingError,
                              Instant processedAt) {
        jdbcTemplate.update("""
                UPDATE notification_provider_events
                SET notification_queue_id = ?,
                    processing_status = ?,
                    processing_error = ?,
                    processed_at = ?
                WHERE webhook_message_id = ?
                """,
                queueId,
                status,
                processingError,
                Timestamp.from(processedAt),
                webhookMessageId
        );
    }
}
