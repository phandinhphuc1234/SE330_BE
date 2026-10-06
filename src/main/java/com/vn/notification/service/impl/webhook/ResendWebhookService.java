package com.vn.notification.service.impl.webhook;

import com.vn.shared.logging.LogEvent;
import com.vn.shared.logging.LogResult;
import com.vn.notification.service.webhook.ResendWebhookEvent;
import com.vn.notification.service.webhook.ResendWebhookProcessingResult;
import com.vn.notification.service.webhook.ResendWebhookRejectedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResendWebhookService {

    private final ResendWebhookVerifier verifier;
    private final ResendWebhookPayloadParser payloadParser;
    private final ResendWebhookEventProcessor eventProcessor;
    private final ResendWebhookMetrics metrics;

    public ResendWebhookProcessingResult accept(String rawPayload,
                                                 String messageId,
                                                 String timestamp,
                                                 String signature) {
        try {
            verifier.verify(rawPayload, messageId, timestamp, signature);
            ResendWebhookEvent event = payloadParser.parse(rawPayload, messageId);
            ResendWebhookProcessingResult result = eventProcessor.process(event);
            metrics.recordResult(result);
            log.info(
                    "eventType={} result={} webhookMessageId={} providerMessageId={} providerEventType={} outcome={}",
                    LogEvent.PROCESS_RESEND_WEBHOOK,
                    LogResult.SUCCESS,
                    event.webhookMessageId(),
                    event.providerMessageId(),
                    event.eventType(),
                    result
            );
            return result;
        } catch (ResendWebhookRejectedException exception) {
            metrics.recordRejected();
            log.warn(
                    "eventType={} result={} reason={}",
                    LogEvent.PROCESS_RESEND_WEBHOOK,
                    LogResult.FAILED,
                    exception.getMessage()
            );
            throw exception;
        }
    }
}
