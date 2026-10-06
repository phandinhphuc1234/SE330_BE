package com.vn.ebook.service;

import com.vn.ebook.enums.EbookAiOperation;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class EbookAiMetrics {

    private final MeterRegistry meterRegistry;

    public void record(EbookAiOperation operation, String outcome, Duration duration) {
        String operationTag = operation.name().toLowerCase(Locale.ROOT);
        meterRegistry.counter(
                "ebook.ai.requests",
                "operation", operationTag,
                "outcome", outcome
        ).increment();
        meterRegistry.timer(
                "ebook.ai.duration",
                "operation", operationTag,
                "outcome", outcome
        ).record(duration);
    }

    public void recordRateLimit(EbookAiOperation operation, String outcome) {
        meterRegistry.counter(
                "ebook.ai.rate.limit",
                "operation", operation.name().toLowerCase(Locale.ROOT),
                "outcome", outcome
        ).increment();
    }
}
