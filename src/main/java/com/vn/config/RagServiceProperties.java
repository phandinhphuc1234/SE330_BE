package com.vn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagServiceProperties(
        boolean enabled,
        String serviceUrl,
        String internalApiKey
) {
}
