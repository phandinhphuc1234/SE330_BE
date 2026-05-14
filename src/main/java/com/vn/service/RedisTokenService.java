package com.vn.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisTokenService {

    private final StringRedisTemplate redisTemplate;

    // ── Key prefix ──
    private static final String REFRESH_PREFIX = "refresh:";
    private static final String BLACKLIST_PREFIX = "blacklist:";

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
}

