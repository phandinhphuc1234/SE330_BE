package com.vn.service.notification;

import com.vn.service.impl.notification.webhook.ResendWebhookPayloadParser;
import com.vn.service.notification.webhook.ResendWebhookEvent;
import com.vn.service.notification.webhook.ResendWebhookRejectedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResendWebhookPayloadParserTest {

    private final ResendWebhookPayloadParser parser =
            new ResendWebhookPayloadParser();

    @Test
    void parseShouldExtractProviderIdSignedQueueTagAndBounceReason() {
        ResendWebhookEvent event = parser.parse("""
                {
                  "type": "email.bounced",
                  "created_at": "2026-09-30T08:30:00Z",
                  "data": {
                    "email_id": "email-123",
                    "tags": {"queue_id": "77", "delivery_attempt": "2"},
                    "bounce": {"message": "Mailbox does not exist"}
                  }
                }
                """, "msg-1");

        assertThat(event.webhookMessageId()).isEqualTo("msg-1");
        assertThat(event.eventType()).isEqualTo("email.bounced");
        assertThat(event.providerMessageId()).isEqualTo("email-123");
        assertThat(event.hintedQueueId()).isEqualTo(77L);
        assertThat(event.hintedDeliveryAttempt()).isEqualTo(2);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-30T08:30:00Z"));
        assertThat(event.providerDetail()).isEqualTo("Mailbox does not exist");
    }

    @Test
    void parseShouldRejectPayloadWithoutProviderMessageId() {
        assertThatThrownBy(() -> parser.parse("""
                {"type":"email.delivered","created_at":"2026-09-30T08:30:00Z","data":{}}
                """, "msg-1"))
                .isInstanceOf(ResendWebhookRejectedException.class);
    }
}
