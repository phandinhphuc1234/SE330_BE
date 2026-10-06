package com.vn.ebook.service;

import com.vn.ebook.config.EbookAiProperties;
import com.vn.ebook.enums.EbookAiOperation;
import com.vn.shared.exception.AppException;
import com.vn.shared.exception.ErrorCode;
import com.vn.shared.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class EbookAiRateLimitService {

    private static final String KEY_PREFIX = "ebook-ai:rate-limit:";
    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final EbookAiProperties properties;
    private final EbookAiMetrics metrics;

    public void check(Long memberId, Long bookId, EbookAiOperation operation) {
        if (!properties.rateLimitEnabled()) {
            return;
        }

        String key = buildKey(memberId, bookId, operation);
        try {
            Long requestCount = redisTemplate.execute(
                    INCREMENT_WITH_TTL,
                    List.of(key),
                    String.valueOf(properties.rateLimitWindow().toMillis())
            );
            if (requestCount == null) {
                handleUnavailable(memberId, bookId, operation, null);
                return;
            }

            int limit = requestLimit(operation);
            if (requestCount > limit) {
                long retryAfterSeconds = resolveRetryAfterSeconds(key, properties.rateLimitWindow());
                metrics.recordRateLimit(operation, "rejected");
                log.info("eventType=EBOOK_AI_RATE_LIMIT result=REJECTED operation={} memberId={} bookId={} retryAfterSeconds={}",
                        operation, memberId, bookId, retryAfterSeconds);
                throw new RateLimitExceededException(ErrorCode.EBOOK_AI_RATE_LIMIT_EXCEEDED, retryAfterSeconds);
            }

            metrics.recordRateLimit(operation, "allowed");
        } catch (RateLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            handleUnavailable(memberId, bookId, operation, exception);
        }
    }

    private void handleUnavailable(Long memberId,
                                   Long bookId,
                                   EbookAiOperation operation,
                                   RuntimeException cause) {
        metrics.recordRateLimit(operation, properties.rateLimitFailOpen() ? "fail_open" : "unavailable");
        log.warn("eventType=EBOOK_AI_RATE_LIMIT result={} operation={} memberId={} bookId={} reason={}",
                properties.rateLimitFailOpen() ? "FAIL_OPEN" : "FAILED",
                operation,
                memberId,
                bookId,
                cause != null ? cause.getClass().getSimpleName() : "EMPTY_REDIS_RESULT");
        if (!properties.rateLimitFailOpen()) {
            throw new AppException(ErrorCode.EBOOK_AI_RATE_LIMIT_UNAVAILABLE);
        }
    }

    private int requestLimit(EbookAiOperation operation) {
        return operation == EbookAiOperation.ASK
                ? properties.askRequestsPerWindow()
                : properties.searchRequestsPerWindow();
    }

    private long resolveRetryAfterSeconds(String key, Duration fallback) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl != null && ttl > 0 ? ttl : Math.max(1, fallback.toSeconds());
    }

    private String buildKey(Long memberId, Long bookId, EbookAiOperation operation) {
        return KEY_PREFIX
                + operation.name().toLowerCase(Locale.ROOT)
                + ":" + memberId
                + ":" + bookId;
    }
}
