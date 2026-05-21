package com.vn.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.enums.IdempotencyStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.service.IdempotencyService;
import com.vn.service.impl.idempotency.IdempotencyRecordPayload;
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
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final int IDEMPOTENCY_TTL_HOURS = 1;
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(IDEMPOTENCY_TTL_HOURS);
    private static final String KEY_PREFIX = "idempotency:";
    private static final int PATH_HASH_LENGTH = 16;

    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate businessTransactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    public IdempotencyServiceImpl(StringRedisTemplate redisTemplate,
                                  PlatformTransactionManager transactionManager) {
        this.redisTemplate = redisTemplate;
        this.businessTransactionTemplate = new TransactionTemplate(transactionManager);
        this.businessTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T execute(Long actorId,
                         String httpMethod,
                         String normalizedPath,
                         String idempotencyKey,
                         Object requestBody,
                         Class<T> responseType,
                         Supplier<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AppException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        String safeKey = idempotencyKey.trim();
        String requestHash = buildRequestHash(httpMethod, normalizedPath, requestBody);
        String redisKey = buildRedisKey(actorId, httpMethod, normalizedPath, safeKey);
        IdempotencyRecordPayload processingPayload = buildProcessingPayload(
                actorId, httpMethod, normalizedPath, safeKey, requestHash);

        if (!registerProcessing(redisKey, processingPayload)) {
            IdempotencyRecordPayload existing = readExisting(redisKey);
            validateRequestHash(existing, requestHash);
            return replayOrReject(existing, responseType);
        }

        T response;
        try {
            response = runBusinessAction(action);
        } catch (RuntimeException e) {
            markFailed(redisKey, processingPayload, e);
            throw e;
        }

        markCompleted(redisKey, processingPayload, response);
        return response;
    }

    // Chức năng: tạo key Redis theo scope actor + method + endpoint + idempotency key.
    private String buildRedisKey(Long actorId, String httpMethod, String normalizedPath, String safeKey) {
        String pathHash = sha256(normalizedPath).substring(0, PATH_HASH_LENGTH);
        return KEY_PREFIX + actorId + ":" + httpMethod + ":" + pathHash + ":" + safeKey;
    }

    // Chức năng: tạo payload PROCESSING ban đầu để ghi bằng SETNX.
    private IdempotencyRecordPayload buildProcessingPayload(Long actorId,
                                                            String httpMethod,
                                                            String normalizedPath,
                                                            String safeKey,
                                                            String requestHash) {
        return new IdempotencyRecordPayload(
                actorId,
                httpMethod,
                normalizedPath,
                safeKey,
                requestHash,
                IdempotencyStatus.PROCESSING,
                null,
                null,
                null,
                null,
                Instant.now(),
                null
        );
    }

    // Chức năng: ghi PROCESSING bằng SETNX để chỉ request đầu tiên với key này được xử lý.
    private boolean registerProcessing(String redisKey, IdempotencyRecordPayload payload) {
        Boolean inserted = redisTemplate.opsForValue().setIfAbsent(redisKey, writePayload(payload), IDEMPOTENCY_TTL);
        if (inserted == null) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return inserted;
    }

    // Chức năng: đọc payload hiện có khi SETNX trả false.
    private IdempotencyRecordPayload readExisting(String redisKey) {
        String json = redisTemplate.opsForValue().get(redisKey);
        if (json == null) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return readPayload(json);
    }

    // Chức năng: đảm bảo client không reuse cùng Idempotency-Key cho payload khác.
    private void validateRequestHash(IdempotencyRecordPayload existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new AppException(ErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST);
        }
    }

    // Chức năng: replay response/lỗi cũ hoặc chặn request khi request đầu tiên còn đang xử lý.
    private <T> T replayOrReject(IdempotencyRecordPayload existing, Class<T> responseType) {
        if (existing.status() == IdempotencyStatus.COMPLETED) {
            return readCachedResponse(existing, responseType);
        }
        if (existing.status() == IdempotencyStatus.FAILED) {
            throw readCachedFailure(existing);
        }
        throw new AppException(ErrorCode.REQUEST_ALREADY_PROCESSING);
    }

    // Chức năng: chạy nghiệp vụ trong transaction SQL riêng, vì Redis chỉ quản lý idempotency state.
    private <T> T runBusinessAction(Supplier<T> action) {
        return businessTransactionTemplate.execute(status -> action.get());
    }

    // Chức năng: lưu response thành công vào Redis để retry nhận lại đúng kết quả cũ.
    private void markCompleted(String redisKey, IdempotencyRecordPayload processingPayload, Object response) {
        IdempotencyRecordPayload completedPayload = new IdempotencyRecordPayload(
                processingPayload.actorId(),
                processingPayload.httpMethod(),
                processingPayload.normalizedPath(),
                processingPayload.idempotencyKey(),
                processingPayload.requestHash(),
                IdempotencyStatus.COMPLETED,
                200,
                writeResponse(response),
                null,
                null,
                processingPayload.createdAt(),
                Instant.now()
        );
        redisTemplate.opsForValue().set(redisKey, writePayload(completedPayload), IDEMPOTENCY_TTL);
    }

    // Chức năng: lưu lỗi nghiệp vụ vào Redis để retry không chạy lại side effect.
    private void markFailed(String redisKey, IdempotencyRecordPayload processingPayload, RuntimeException exception) {
        IdempotencyRecordPayload failedPayload = new IdempotencyRecordPayload(
                processingPayload.actorId(),
                processingPayload.httpMethod(),
                processingPayload.normalizedPath(),
                processingPayload.idempotencyKey(),
                processingPayload.requestHash(),
                IdempotencyStatus.FAILED,
                resolveResponseCode(exception),
                null,
                resolveErrorCode(exception),
                exception.getMessage(),
                processingPayload.createdAt(),
                Instant.now()
        );
        redisTemplate.opsForValue().set(redisKey, writePayload(failedPayload), IDEMPOTENCY_TTL);
    }

    /*
     * Chức năng: tạo hash đại diện cho request.
     *
     * Hash dựa trên method + normalizedPath + canonical JSON body.
     * Không hash Authorization header vì token có thể đổi giữa các lần retry.
     */
    private String buildRequestHash(String method, String normalizedPath, Object body) {
        String canonicalBody = "";
        if (body != null) {
            try {
                JsonNode jsonNode = objectMapper.valueToTree(body);
                canonicalBody = objectMapper.writeValueAsString(jsonNode);
            } catch (JsonProcessingException e) {
                throw new AppException(ErrorCode.BAD_REQUEST);
            }
        }
        return sha256(method + "|" + normalizedPath + "|" + canonicalBody);
    }

    // Chức năng: hash chuỗi bằng SHA-256 để tạo requestHash và path scope hash.
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // Chức năng: serialize payload idempotency thành JSON để lưu trong Redis.
    private String writePayload(IdempotencyRecordPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // Chức năng: deserialize JSON Redis thành payload idempotency.
    private IdempotencyRecordPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, IdempotencyRecordPayload.class);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // Chức năng: serialize response object thành JSON string để cache replay.
    private String writeResponse(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // Chức năng: đọc response đã cache từ Redis và ép về đúng response DTO của caller.
    private <T> T readCachedResponse(IdempotencyRecordPayload payload, Class<T> responseType) {
        try {
            return objectMapper.readValue(payload.responseBody(), responseType);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    // Chức năng: replay lại lỗi đã cache để retry không chạy lại nghiệp vụ thất bại trước đó.
    private AppException readCachedFailure(IdempotencyRecordPayload payload) {
        return Arrays.stream(ErrorCode.values())
                .filter(errorCode -> errorCode.getCode().equals(payload.errorCode()))
                .findFirst()
                .map(AppException::new)
                .orElseGet(() -> new AppException(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    // Chức năng: map exception thành HTTP status code lưu trong payload Redis.
    private int resolveResponseCode(RuntimeException exception) {
        if (exception instanceof AppException appException) {
            return appException.getStatus().value();
        }
        return ErrorCode.INTERNAL_SERVER_ERROR.getStatus().value();
    }

    // Chức năng: map exception thành error code lưu trong payload Redis.
    private String resolveErrorCode(RuntimeException exception) {
        if (exception instanceof AppException appException) {
            return appException.getCode();
        }
        return ErrorCode.INTERNAL_SERVER_ERROR.getCode();
    }
}
