package com.vn.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

@ConfigurationProperties(prefix = "app.time")
public record ApplicationTimeProperties(ZoneId zone) {

    public ApplicationTimeProperties {
        if (zone == null) {
            throw new IllegalArgumentException("app.time.zone is required");
        }
    }
}
