package com.vn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.object-storage")
public record ObjectStorageProperties(
        String endpoint,
        String publicEndpoint,
        String accessKey,
        String secretKey,
        String region,
        String ebookBucket,
        String tempBucket
) {
}
