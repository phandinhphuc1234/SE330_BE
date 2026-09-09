package com.vn.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetRateLimitServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @Test
    void tryAcquire_shouldStartCooldownAndDailyWindowForFirstRequest() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        boolean acquired = new PasswordResetRateLimitService(redisTemplate).tryAcquire("member@example.com");

        assertThat(acquired).isTrue();
        verify(redisTemplate).expire(anyString(), eq(Duration.ofHours(24)));
    }

    @Test
    void tryAcquire_shouldRejectRequestDuringCooldownWithoutIncrementingCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60)))).thenReturn(false);

        boolean acquired = new PasswordResetRateLimitService(redisTemplate).tryAcquire("member@example.com");

        assertThat(acquired).isFalse();
        verify(valueOperations, never()).increment(anyString());
    }
}
