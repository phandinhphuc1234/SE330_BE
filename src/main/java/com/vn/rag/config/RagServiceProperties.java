package com.vn.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rag")
public record RagServiceProperties(
        boolean enabled,
        String serviceUrl,
        String internalApiKey,
        Duration connectTimeout,
        Duration readTimeout
) {
}
