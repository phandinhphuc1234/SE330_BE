package com.vn.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationRateLimitServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final String ATTEMPT_KEY = "email:verify:attempt-count:7";

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @Test
    void recordFailedVerificationAttempt_shouldStartTenMinuteWindow_onFirstFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(ATTEMPT_KEY)).thenReturn(1L);

        long attempts = service().recordFailedVerificationAttempt(MEMBER_ID);

        assertThat(attempts).isEqualTo(1L);
        verify(redisTemplate).expire(ATTEMPT_KEY, 600, TimeUnit.SECONDS);
    }

    @Test
    void hasExceededVerificationAttemptLimit_shouldReturnTrue_atFiveFailures() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(ATTEMPT_KEY)).thenReturn("5");

        assertThat(service().hasExceededVerificationAttemptLimit(MEMBER_ID)).isTrue();
    }

    @Test
    void clearVerificationAttempts_shouldDeleteAttemptCounter() {
        service().clearVerificationAttempts(MEMBER_ID);

        verify(redisTemplate).delete(ATTEMPT_KEY);
    }

    private EmailVerificationRateLimitService service() {
        return new EmailVerificationRateLimitService(redisTemplate);
    }
}
