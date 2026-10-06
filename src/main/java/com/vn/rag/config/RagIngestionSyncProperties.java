package com.vn.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rag.ingestion-sync")
public record RagIngestionSyncProperties(
        int batchSize,
        int maxPollFailures,
        Duration initialRetryDelay,
        Duration maxRetryDelay
) {

    public RagIngestionSyncProperties {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("app.rag.ingestion-sync.batch-size must be between 1 and 100");
        }
        if (maxPollFailures < 1 || maxPollFailures > 100) {
            throw new IllegalArgumentException("app.rag.ingestion-sync.max-poll-failures must be between 1 and 100");
        }
        initialRetryDelay = requirePositive(initialRetryDelay, "initial-retry-delay");
        maxRetryDelay = requirePositive(maxRetryDelay, "max-retry-delay");
        if (maxRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException(
                    "app.rag.ingestion-sync.max-retry-delay must not be shorter than initial-retry-delay"
            );
        }
    }

    private static Duration requirePositive(Duration value, String propertyName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "app.rag.ingestion-sync." + propertyName + " must be positive"
            );
        }
        return value;
    }
}
