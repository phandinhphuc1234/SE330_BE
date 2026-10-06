package com.vn.ebook.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.ebook.ai")
public record EbookAiProperties(
        boolean rateLimitEnabled,
        @Min(1) int askRequestsPerWindow,
        @Min(1) int searchRequestsPerWindow,
        @NotNull Duration rateLimitWindow,
        boolean rateLimitFailOpen
) {
}
