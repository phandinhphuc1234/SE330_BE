package com.vn.service.impl.notification.delivery;

import com.vn.service.notification.delivery.NotificationDeliveryOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryMetrics {

    private final Counter sent;
    private final Counter retryScheduled;
    private final Counter dead;
    private final Counter ownershipLost;
    private final Timer providerLatency;

    public NotificationDeliveryMetrics(MeterRegistry meterRegistry) {
        this.sent = outcomeCounter(meterRegistry, "sent");
        this.retryScheduled = outcomeCounter(meterRegistry, "retry_scheduled");
        this.dead = outcomeCounter(meterRegistry, "dead");
        this.ownershipLost = outcomeCounter(meterRegistry, "ownership_lost");
        this.providerLatency = Timer.builder("library.notification.delivery.provider")
                .description("Latency of calls to the configured email provider")
                .register(meterRegistry);
    }

    public Timer.Sample startProviderCall(MeterRegistry meterRegistry) {
        return Timer.start(meterRegistry);
    }

    public void stopProviderCall(Timer.Sample sample) {
        sample.stop(providerLatency);
    }

    public void record(NotificationDeliveryOutcome outcome) {
        switch (outcome) {
            case SENT -> sent.increment();
            case RETRY_SCHEDULED -> retryScheduled.increment();
            case DEAD -> dead.increment();
            case OWNERSHIP_LOST -> ownershipLost.increment();
        }
    }

    private Counter outcomeCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("library.notification.delivery.outcomes")
                .description("Notification delivery state transitions")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
