package com.vn.notification.service.impl.webhook;

import com.vn.notification.service.webhook.ResendWebhookProcessingResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

@Component
public class ResendWebhookMetrics {

    private final Map<ResendWebhookProcessingResult, Counter> resultCounters =
            new EnumMap<>(ResendWebhookProcessingResult.class);
    private final Counter rejected;

    public ResendWebhookMetrics(MeterRegistry registry) {
        for (ResendWebhookProcessingResult result : ResendWebhookProcessingResult.values()) {
            resultCounters.put(result, Counter.builder("notification.webhook.events")
                    .description("Resend webhook events handled by result")
                    .tag("provider", "resend")
                    .tag("result", result.name().toLowerCase(Locale.ROOT))
                    .register(registry));
        }
        rejected = Counter.builder("notification.webhook.events")
                .description("Resend webhook events handled by result")
                .tag("provider", "resend")
                .tag("result", "rejected")
                .register(registry);
    }

    public void recordResult(ResendWebhookProcessingResult result) {
        resultCounters.get(result).increment();
    }

    public void recordRejected() {
        rejected.increment();
    }
}
