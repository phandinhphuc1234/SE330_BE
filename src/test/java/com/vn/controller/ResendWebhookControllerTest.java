package com.vn.controller;

import com.vn.service.impl.notification.webhook.ResendWebhookService;
import com.vn.service.notification.webhook.ResendWebhookProcessingResult;
import com.vn.service.notification.webhook.ResendWebhookRejectedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResendWebhookControllerTest {

    @Mock private ResendWebhookService webhookService;

    @Test
    void receiveShouldReturn200ForAProcessedSignedEvent() {
        when(webhookService.accept("{}", "msg-1", "100", "v1,test"))
                .thenReturn(ResendWebhookProcessingResult.PROCESSED);

        var response = new ResendWebhookController(webhookService)
                .receive("{}", "msg-1", "100", "v1,test");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void receiveShouldReturn400ForAnInvalidSignature() {
        when(webhookService.accept("{}", "msg-1", "100", "invalid"))
                .thenThrow(new ResendWebhookRejectedException("invalid"));

        var response = new ResendWebhookController(webhookService)
                .receive("{}", "msg-1", "100", "invalid");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
