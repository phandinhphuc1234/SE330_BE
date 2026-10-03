package com.vn.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisTokenServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @Test
    void revokeAllSessions_shouldDeleteRefreshTokenAndStoreExpiryBoundedEpoch() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        new RedisTokenService(redisTemplate, com.vn.shared.testsupport.TestTime.CLOCK)
                .revokeAllSessions(1L, 60_000L);

        verify(redisTemplate).delete("refresh:1");
        verify(valueOperations).set(
                eq("session-revoked-after:1"),
                eq(Long.toString(com.vn.shared.testsupport.TestTime.NOW.toEpochMilli())),
                eq(60_000L),
                eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void isSessionRevokedAfter_shouldRejectTokensIssuedBeforeStoredEpoch() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("session-revoked-after:1")).thenReturn("1000");

        boolean revoked = new RedisTokenService(redisTemplate, com.vn.shared.testsupport.TestTime.CLOCK)
                .isSessionRevokedAfter(1L, Instant.ofEpochMilli(999));

        assertThat(revoked).isTrue();
    }
}
