package com.vn.ebook.service;

import com.vn.ebook.config.EbookAiProperties;
import com.vn.ebook.enums.EbookAiOperation;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.shared.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EbookAiRateLimitServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private EbookAiMetrics metrics;

    private EbookAiRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new EbookAiRateLimitService(redisTemplate, properties(false), metrics);
    }

    @Test
    void checkShouldAllowRequestWithinOperationLimit() {
        when(executeRateLimitScript()).thenReturn(5L);

        assertThatCode(() -> service.check(10L, 501L, EbookAiOperation.ASK))
                .doesNotThrowAnyException();

        verify(metrics).recordRateLimit(EbookAiOperation.ASK, "allowed");
    }

    @Test
    void checkShouldReturnRetryAfterWhenLimitIsExceeded() {
        when(executeRateLimitScript()).thenReturn(6L);
        when(redisTemplate.getExpire(
                "ebook-ai:rate-limit:ask:10:501",
                TimeUnit.SECONDS
        )).thenReturn(42L);

        assertThatThrownBy(() -> service.check(10L, 501L, EbookAiOperation.ASK))
                .isInstanceOfSatisfying(RateLimitExceededException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.EBOOK_AI_RATE_LIMIT_EXCEEDED.getCode());
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(42L);
                });

        verify(metrics).recordRateLimit(EbookAiOperation.ASK, "rejected");
    }

    @Test
    void checkShouldFailClosedWhenRedisIsUnavailable() {
        when(executeRateLimitScript()).thenThrow(new RedisConnectionFailureException("offline"));

        assertThatThrownBy(() -> service.check(10L, 501L, EbookAiOperation.SEARCH))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getCode())
                                .isEqualTo(ErrorCode.EBOOK_AI_RATE_LIMIT_UNAVAILABLE.getCode()));

        verify(metrics).recordRateLimit(EbookAiOperation.SEARCH, "unavailable");
    }

    @Test
    void checkShouldFailOpenOnlyWhenExplicitlyConfigured() {
        service = new EbookAiRateLimitService(redisTemplate, properties(true), metrics);
        when(executeRateLimitScript()).thenThrow(new RedisConnectionFailureException("offline"));

        assertThatCode(() -> service.check(10L, 501L, EbookAiOperation.SEARCH))
                .doesNotThrowAnyException();

        verify(metrics).recordRateLimit(EbookAiOperation.SEARCH, "fail_open");
    }

    @SuppressWarnings("unchecked")
    private Long executeRateLimitScript() {
        return redisTemplate.execute(
                (RedisScript<Long>) any(RedisScript.class),
                anyList(),
                any()
        );
    }

    private EbookAiProperties properties(boolean failOpen) {
        return new EbookAiProperties(
                true,
                5,
                20,
                Duration.ofMinutes(1),
                failOpen
        );
    }
}
