package com.vn.service.payment.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.enums.IdempotencyStatus;
import com.vn.enums.PaymentProvider;
import com.vn.enums.PaymentPurpose;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Supplier;

@Service
public class PaymentIdempotencyService {

    private static final String HTTP_METHOD = "POST";
    private static final String NORMALIZED_PATH = "/api/payments";
    private static final Duration PROCESSING_TTL = Duration.ofMinutes(10);
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);
    private static final Duration FAILED_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    public PaymentIdempotencyService(StringRedisTemplate redisTemplate,
                                     PlatformTransactionManager transactionManager) {
        this.redisTemplate = redisTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T execute(PaymentProvider provider,
                         PaymentPurpose purpose,
                         Long memberId,
                         String idempotencyKey,
                         Object requestBody,
                         Class<T> responseType,
                         Supplier<T> action) {
        // Payment dùng key scope riêng: idem:payment:{provider}:{purpose}:{memberId}:{idempotencyKey}.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AppException(ErrorCode.MISSING_IDEMPOTENCY_KEY);
        }

        String safeKey = idempotencyKey.trim();
        String requestHash = buildRequestHash(requestBody);
        String redisKey = buildRedisKey(provider, purpose, memberId, safeKey);
        PaymentIdempotencyPayload processingPayload = new PaymentIdempotencyPayload(
                IdempotencyStatus.PROCESSING,
                requestHash,
                null,
                null,
                null,
                Instant.now(),
                null
        );

        if (!registerProcessing(redisKey, processingPayload)) {
            PaymentIdempotencyPayload existing = readExisting(redisKey);
            validateRequestHash(existing, requestHash);
            return replayOrReject(existing, responseType);
        }

        try {
            T response = transactionTemplate.execute(status -> action.get());
            markCompleted(redisKey, processingPayload, response);
            return response;
        } catch (RuntimeException exception) {
            markFailed(redisKey, processingPayload, exception);
            throw exception;
        }
    }

    // Key format đúng spec, tránh va chạm nếu cùng Idempotency-Key nhưng khác provider/purpose.
    private String buildRedisKey(PaymentProvider provider, PaymentPurpose purpose, Long memberId, String safeKey) {
        return "idem:payment:" + provider + ":" + purpose + ":" + memberId + ":" + safeKey;
    }

    // SETNX với TTL 10 phút để chỉ request đầu tiên được xử lý trong lúc đang PROCESSING.
    private boolean registerProcessing(String redisKey, PaymentIdempotencyPayload payload) {
        Boolean inserted = redisTemplate.opsForValue().setIfAbsent(redisKey, writePayload(payload), PROCESSING_TTL);
        if (inserted == null) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return inserted;
    }

    private PaymentIdempotencyPayload readExisting(String redisKey) {
        String json = redisTemplate.opsForValue().get(redisKey);
        if (json == null) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return readPayload(json);
    }

    // Same key nhưng body khác là conflict, tránh client reuse idempotency key sai cách.
    private void validateRequestHash(PaymentIdempotencyPayload existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new AppException(ErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST);
        }
    }

    // Retry cùng request nhận lại response cũ nếu completed, hoặc bị chặn nếu request đầu còn chạy.
    private <T> T replayOrReject(PaymentIdempotencyPayload existing, Class<T> responseType) {
        if (existing.status() == IdempotencyStatus.COMPLETED) {
            return readCachedResponse(existing, responseType);
        }
        if (existing.status() == IdempotencyStatus.FAILED) {
            throw readCachedFailure(existing);
        }
        throw new AppException(ErrorCode.IDEMPOTENCY_REQUEST_PROCESSING);
    }

    // Response thành công cache 24 giờ theo spec để retry không tạo payment mới.
    private void markCompleted(String redisKey, PaymentIdempotencyPayload processingPayload, Object response) {
        PaymentIdempotencyPayload completedPayload = new PaymentIdempotencyPayload(
                IdempotencyStatus.COMPLETED,
                processingPayload.requestHash(),
                writeResponse(response),
                null,
                null,
                processingPayload.createdAt(),
                Instant.now()
        );
        redisTemplate.opsForValue().set(redisKey, writePayload(completedPayload), COMPLETED_TTL);
    }

    // Lỗi cache ngắn 10 phút để retry ngay không chạy lại side effect.
    private void markFailed(String redisKey, PaymentIdempotencyPayload processingPayload, RuntimeException exception) {
        PaymentIdempotencyPayload failedPayload = new PaymentIdempotencyPayload(
                IdempotencyStatus.FAILED,
                processingPayload.requestHash(),
                null,
                resolveErrorCode(exception),
                exception.getMessage(),
                processingPayload.createdAt(),
                Instant.now()
        );
        redisTemplate.opsForValue().set(redisKey, writePayload(failedPayload), FAILED_TTL);
    }

    // Hash method/path/body canonical; không hash Authorization vì token có thể refresh giữa các lần retry.
    private String buildRequestHash(Object body) {
        String canonicalBody = "";
        if (body != null) {
            try {
                JsonNode jsonNode = objectMapper.valueToTree(body);
                canonicalBody = objectMapper.writeValueAsString(jsonNode);
            } catch (JsonProcessingException e) {
                throw new AppException(ErrorCode.BAD_REQUEST);
            }
        }
        return sha256(HTTP_METHOD + "|" + NORMALIZED_PATH + "|" + canonicalBody);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private String writePayload(PaymentIdempotencyPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private PaymentIdempotencyPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, PaymentIdempotencyPayload.class);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private String writeResponse(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private <T> T readCachedResponse(PaymentIdempotencyPayload payload, Class<T> responseType) {
        try {
            return objectMapper.readValue(payload.responseBody(), responseType);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private AppException readCachedFailure(PaymentIdempotencyPayload payload) {
        return Arrays.stream(ErrorCode.values())
                .filter(errorCode -> errorCode.getCode().equals(payload.errorCode()))
                .findFirst()
                .map(AppException::new)
                .orElseGet(() -> new AppException(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    private String resolveErrorCode(RuntimeException exception) {
        if (exception instanceof AppException appException) {
            return appException.getCode();
        }
        return ErrorCode.INTERNAL_SERVER_ERROR.getCode();
    }
}
