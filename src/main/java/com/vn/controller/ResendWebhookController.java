package com.vn.controller;

import com.vn.service.impl.notification.webhook.ResendWebhookService;
import com.vn.service.notification.webhook.ResendWebhookRejectedException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/resend")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.notification.webhook", name = "enabled", havingValue = "true")
public class ResendWebhookController {

    private final ResendWebhookService webhookService;

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody String rawPayload,
            @RequestHeader(value = "svix-id", required = false) String messageId,
            @RequestHeader(value = "svix-timestamp", required = false) String timestamp,
            @RequestHeader(value = "svix-signature", required = false) String signature
    ) {
        try {
            webhookService.accept(rawPayload, messageId, timestamp, signature);
            return ResponseEntity.ok().build();
        } catch (ResendWebhookRejectedException exception) {
            return ResponseEntity.badRequest().build();
        }
    }
}
