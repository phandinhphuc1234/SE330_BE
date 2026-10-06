package com.vn.notification.service;

import com.svix.Webhook;
import com.vn.notification.config.ResendWebhookProperties;
import com.vn.notification.service.impl.webhook.ResendWebhookVerifier;
import com.vn.notification.service.webhook.ResendWebhookRejectedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResendWebhookVerifierTest {

    private static final String SECRET = "whsec_" + Base64.getEncoder()
            .encodeToString("01234567890123456789012345678901".getBytes());

    @Test
    void verifyShouldAcceptAValidSvixSignatureOverTheRawBody() throws Exception {
        String payload = "{\"type\":\"email.delivered\"}";
        String messageId = "msg_test_1";
        long timestamp = Instant.now().getEpochSecond();
        String signature = new Webhook(SECRET).sign(messageId, timestamp, payload);
        ResendWebhookVerifier verifier = verifier();

        assertThatCode(() -> verifier.verify(
                payload, messageId, Long.toString(timestamp), signature))
                .doesNotThrowAnyException();
    }

    @Test
    void verifyShouldRejectWhenTheRawBodyWasModified() throws Exception {
        String payload = "{\"type\":\"email.delivered\"}";
        String messageId = "msg_test_1";
        long timestamp = Instant.now().getEpochSecond();
        String signature = new Webhook(SECRET).sign(messageId, timestamp, payload);
        ResendWebhookVerifier webhookVerifier = verifier();
        String tamperedPayload = payload + " ";
        String timestampHeader = Long.toString(timestamp);

        assertThatThrownBy(() -> webhookVerifier.verify(
                tamperedPayload, messageId, timestampHeader, signature))
                .isInstanceOf(ResendWebhookRejectedException.class);
    }

    private ResendWebhookVerifier verifier() {
        return new ResendWebhookVerifier(new ResendWebhookProperties(true, SECRET, 262_144));
    }
}
