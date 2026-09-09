package com.vn.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class PasswordResetRateLimitService {

    private static final String COOLDOWN_PREFIX = "password-reset:cooldown:";
    private static final String WINDOW_COUNT_PREFIX = "password-reset:count:";
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final long MAX_REQUESTS_PER_WINDOW = 5;

    private final StringRedisTemplate redisTemplate;

    /**
     * Claims one reset-email slot without storing the raw email address in Redis.
     * A false result is deliberately returned to the caller as the same generic
     * HTTP response so the endpoint cannot be used to enumerate accounts.
     */
    public boolean tryAcquire(String normalizedEmail) {
        String subjectHash = sha256(normalizedEmail);
        Boolean cooldownAcquired = redisTemplate.opsForValue().setIfAbsent(
                COOLDOWN_PREFIX + subjectHash,
                "1",
                COOLDOWN
        );
        if (!Boolean.TRUE.equals(cooldownAcquired)) {
            return false;
        }

        String countKey = WINDOW_COUNT_PREFIX + subjectHash;
        Long count = redisTemplate.opsForValue().increment(countKey);
        if (count == null) {
            redisTemplate.delete(COOLDOWN_PREFIX + subjectHash);
            return false;
        }
        if (count == 1L) {
            redisTemplate.expire(countKey, WINDOW);
        }
        if (count > MAX_REQUESTS_PER_WINDOW) {
            redisTemplate.delete(COOLDOWN_PREFIX + subjectHash);
            return false;
        }
        return true;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }
}
