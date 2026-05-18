package com.vn.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.entity.IdempotencyRecord;
import com.vn.enums.IdempotencyStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.repository.IdempotencyRecordRepository;
import com.vn.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyServiceImpl implements IdempotencyService {
    // Set TTL cho idempotency key
    private static final int IDEMPOTENCY_TTL_HOURS = 24;
    //
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    //
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

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
        // Normalize idempotency string and build hash to store in the database
        String safeKey = idempotencyKey.trim();
        String requestHash = buildRequestHash(httpMethod, normalizedPath, requestBody);
        Instant now = Instant.now();

        // The database unique constraint is the real race-condition guard here.
        // Two identical retries can reach this code at the same time; only one insert can win.
        int inserted = idempotencyRecordRepository.insertProcessing(
                actorId,
                httpMethod,
                normalizedPath,
                safeKey,
                requestHash,
                now,
                now.plus(IDEMPOTENCY_TTL_HOURS, ChronoUnit.HOURS)
        );
        //  Check the idempotency key before saving them in the database
        if (inserted == 0) {
            IdempotencyRecord existing = findExisting(actorId, httpMethod, normalizedPath, safeKey);
            // Reusing the same key for a different payload is a client bug and must not replay old data.
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new AppException(ErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST);
            }
            if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                return readCachedResponse(existing, responseType);
            }
            throw new AppException(ErrorCode.REQUEST_ALREADY_PROCESSING);
        }
        //
        IdempotencyRecord record = findExisting(actorId, httpMethod, normalizedPath, safeKey);
        T response = action.get();
        // Store the successful response so an HTTP retry can receive the same business result.
        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setResponseCode(200);
        record.setResponseBody(writeResponse(response));
        record.setCompletedAt(Instant.now());
        idempotencyRecordRepository.save(record);
        return response;
    }
    // Method check xem idempotency đã tồn tại hay chưa
    private IdempotencyRecord findExisting(Long actorId, String httpMethod, String normalizedPath, String key) {
        return idempotencyRecordRepository
                .findByActorIdAndHttpMethodAndNormalizedPathAndIdempotencyKey(actorId, httpMethod, normalizedPath, key)
                .orElseThrow(() -> new AppException(ErrorCode.INTERNAL_SERVER_ERROR));
    }
    // /*
    //     * Tạo hash đại diện cho request.
    //     *
    //     * Input gồm:
    //     * - method
    //     * - normalizedPath
    //     * - body đã canonical JSON
    //     *
    //     * Sau đó ghép thành chuỗi:
    //     *
    //     * POST|/api/borrows|{"bookId":10}
    //     *
    //     * rồi hash SHA-256.
    //     *
    //     * Mục đích:
    //     * - Cùng request → cùng hash
    //     * - Khác request → khác hash
    //     */
    private String buildRequestHash(String method, String normalizedPath, Object body) {
        String canonicalBody = "";
        if (body != null) {
            try {
                // Hash the canonical JSON body, not raw whitespace/order from the HTTP request.
                JsonNode jsonNode = objectMapper.valueToTree(body);
                canonicalBody = objectMapper.writeValueAsString(jsonNode);
            } catch (JsonProcessingException e) {
                throw new AppException(ErrorCode.BAD_REQUEST);
            }
        }
        return sha256(method + "|" + normalizedPath + "|" + canonicalBody);
    }
    // Hash bằng thuật toán SHA-256
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
    // Serialize response object thành JSON string để lưu vào DB.
    //     *
    //     * Ví dụ response:
    //     *
    //     * {
    //     *   "borrowId": 101,
    //     *   "status": "PENDING"
    //     * }
    //     *
    //     * sẽ được lưu vào idempotency_records.response_body.
    //     *
    //     * Khi client retry request, hệ thống deserialize JSON này
    //     * và trả lại response cũ mà không chạy lại nghiệp vụ.
    private String writeResponse(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
    // Đọc response đã cache từ idempotency record.
    private <T> T readCachedResponse(IdempotencyRecord record, Class<T> responseType) {
        try {
            return objectMapper.readValue(record.getResponseBody(), responseType);
        } catch (JsonProcessingException e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
