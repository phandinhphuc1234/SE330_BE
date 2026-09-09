package com.vn.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisTokenService {

    private final StringRedisTemplate redisTemplate;

    // ── Key prefix ──
    private static final String REFRESH_PREFIX = "refresh:";
    private static final String BLACKLIST_PREFIX = "blacklist:";
    private static final String SESSION_REVOKED_AFTER_PREFIX = "session-revoked-after:";

    // ================= REFRESH TOKEN =================

    // Lưu refresh token vào Redis với TTL
    public void saveRefreshToken(Long userId, String token, long expiryMs) {
        redisTemplate.opsForValue().set(
                REFRESH_PREFIX + userId,
                token,
                expiryMs,
                TimeUnit.MILLISECONDS
        );
    }

    // Lấy refresh token từ Redis theo userId
    public String getRefreshToken(Long userId) {
        return redisTemplate.opsForValue().get(REFRESH_PREFIX + userId);
    }

    // Xóa refresh token (logout)
    public void deleteRefreshToken(Long userId) {
        redisTemplate.delete(REFRESH_PREFIX + userId);
    }

    // ================= BLACKLIST ACCESS TOKEN =================

    // Blacklist access token khi logout (TTL = thời gian còn lại của token)
    public void blacklistAccessToken(String token, long remainingMs) {
        if (remainingMs > 0) {
            redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + token,
                    "1",
                    remainingMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    // Kiểm tra access token có bị blacklist không
    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
    }

    // ── SESSION REVOCATION EPOCH ────────────────────────

    /**
     * Invalidates the stored refresh token and all access tokens issued before
     * this instant. The epoch lives no longer than a refresh-token lifetime.
     */
    public void revokeAllSessions(Long userId, long expiryMs) {
        deleteRefreshToken(userId);
        if (expiryMs > 0) {
            redisTemplate.opsForValue().set(
                    SESSION_REVOKED_AFTER_PREFIX + userId,
                    Long.toString(Instant.now().toEpochMilli()),
                    expiryMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    public boolean isSessionRevokedAfter(Long userId, Instant issuedAt) {
        if (userId == null || issuedAt == null) {
            return true;
        }
        String revokedAfter = redisTemplate.opsForValue().get(SESSION_REVOKED_AFTER_PREFIX + userId);
        if (revokedAfter == null) {
            return false;
        }
        try {
            return issuedAt.toEpochMilli() < Long.parseLong(revokedAfter);
        } catch (NumberFormatException ignored) {
            // A malformed revocation marker must never allow an old credential through.
            return true;
        }
    }
}

