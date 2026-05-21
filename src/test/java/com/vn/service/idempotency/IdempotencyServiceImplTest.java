package com.vn.service.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.enums.IdempotencyStatus;
import com.vn.exception.AppException;
import com.vn.exception.ErrorCode;
import com.vn.service.impl.IdempotencyServiceImpl;
import com.vn.service.impl.idempotency.IdempotencyRecordPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceImplTest {

    private static final Long ACTOR_ID = 10L;
    private static final String METHOD = "POST";
    private static final String PATH = "/api/test";
    private static final String KEY = "request-key";
    private static final String REDIS_KEY = "idempotency:10:POST:" + pathHash(PATH) + ":" + KEY;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private IdempotencyServiceImpl idempotencyService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        idempotencyService = new IdempotencyServiceImpl(redisTemplate, transactionManager);
    }

    @Test
    void execute_requiresIdempotencyKey() {
        assertThatThrownBy(() -> idempotencyService.execute(
                ACTOR_ID,
                METHOD,
                PATH,
                " ",
                null,
                String.class,
                () -> "OK"))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REQUIRED.getMessage());

        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void execute_marksCompletedWhenBusinessActionSucceeds() {
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        String response = idempotencyService.execute(ACTOR_ID, METHOD, PATH, KEY, null, String.class, () -> "OK");

        assertThat(response).isEqualTo("OK");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(REDIS_KEY), payloadCaptor.capture(), any(Duration.class));
        IdempotencyRecordPayload completed = readPayload(payloadCaptor.getValue());

        assertThat(completed.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(completed.responseCode()).isEqualTo(200);
        assertThat(completed.responseBody()).isEqualTo("\"OK\"");
        assertThat(completed.errorCode()).isNull();
        assertThat(completed.completedAt()).isNotNull();
    }

    @Test
    void execute_marksFailedAndRethrowsWhenBusinessActionFails() {
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        assertThatThrownBy(() -> idempotencyService.execute(
                ACTOR_ID,
                METHOD,
                PATH,
                KEY,
                null,
                String.class,
                () -> {
                    throw new AppException(ErrorCode.BOOK_COPY_NOT_AVAILABLE);
                }))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorCode.BOOK_COPY_NOT_AVAILABLE.getMessage());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(REDIS_KEY), payloadCaptor.capture(), any(Duration.class));
        IdempotencyRecordPayload failed = readPayload(payloadCaptor.getValue());

        assertThat(failed.status()).isEqualTo(IdempotencyStatus.FAILED);
        assertThat(failed.responseCode()).isEqualTo(ErrorCode.BOOK_COPY_NOT_AVAILABLE.getStatus().value());
        assertThat(failed.errorCode()).isEqualTo(ErrorCode.BOOK_COPY_NOT_AVAILABLE.getCode());
        assertThat(failed.errorMessage()).isEqualTo(ErrorCode.BOOK_COPY_NOT_AVAILABLE.getMessage());
        assertThat(failed.completedAt()).isNotNull();
    }

    @Test
    void execute_replaysCompletedRequestWithoutRunningBusinessActionAgain() {
        AtomicBoolean actionCalled = new AtomicBoolean(false);
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        when(valueOperations.get(REDIS_KEY))
                .thenReturn(writePayload(payload(IdempotencyStatus.COMPLETED, 200, "\"OK\"", null, null)));

        String response = idempotencyService.execute(
                ACTOR_ID,
                METHOD,
                PATH,
                KEY,
                null,
                String.class,
                () -> {
                    actionCalled.set(true);
                    return "SHOULD_NOT_RUN";
                });

        assertThat(response).isEqualTo("OK");
        assertThat(actionCalled).isFalse();
        verify(valueOperations, never()).set(eq(REDIS_KEY), anyString(), any(Duration.class));
    }

    @Test
    void execute_replaysFailedRequestWithoutRunningBusinessActionAgain() {
        AtomicBoolean actionCalled = new AtomicBoolean(false);
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        when(valueOperations.get(REDIS_KEY))
                .thenReturn(writePayload(payload(
                        IdempotencyStatus.FAILED,
                        ErrorCode.BORROW_NOT_RENEWABLE.getStatus().value(),
                        null,
                        ErrorCode.BORROW_NOT_RENEWABLE.getCode(),
                        ErrorCode.BORROW_NOT_RENEWABLE.getMessage()
                )));

        assertThatThrownBy(() -> idempotencyService.execute(
                ACTOR_ID,
                METHOD,
                PATH,
                KEY,
                null,
                String.class,
                () -> {
                    actionCalled.set(true);
                    return "SHOULD_NOT_RUN";
                }))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorCode.BORROW_NOT_RENEWABLE.getMessage());

        assertThat(actionCalled).isFalse();
        verify(valueOperations, never()).set(eq(REDIS_KEY), anyString(), any(Duration.class));
    }

    @Test
    void execute_rejectsRequestAlreadyProcessing() {
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        when(valueOperations.get(REDIS_KEY))
                .thenReturn(writePayload(payload(IdempotencyStatus.PROCESSING, null, null, null, null)));

        assertThatThrownBy(() -> idempotencyService.execute(
                ACTOR_ID,
                METHOD,
                PATH,
                KEY,
                null,
                String.class,
                () -> "SHOULD_NOT_RUN"))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorCode.REQUEST_ALREADY_PROCESSING.getMessage());
    }

    @Test
    void execute_rejectsSameKeyWithDifferentPayload() {
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        when(valueOperations.get(REDIS_KEY))
                .thenReturn(writePayload(new IdempotencyRecordPayload(
                        ACTOR_ID,
                        METHOD,
                        PATH,
                        KEY,
                        "different-hash",
                        IdempotencyStatus.COMPLETED,
                        200,
                        "\"OK\"",
                        null,
                        null,
                        Instant.now(),
                        Instant.now()
                )));

        assertThatThrownBy(() -> idempotencyService.execute(
                ACTOR_ID,
                METHOD,
                PATH,
                KEY,
                null,
                String.class,
                () -> "SHOULD_NOT_RUN"))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST.getMessage());
    }

    @Test
    void execute_failsClosedWhenRedisSetIfAbsentReturnsNull() {
        when(valueOperations.setIfAbsent(eq(REDIS_KEY), anyString(), any(Duration.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> idempotencyService.execute(
                ACTOR_ID,
                METHOD,
                PATH,
                KEY,
                null,
                String.class,
                () -> "SHOULD_NOT_RUN"))
                .isInstanceOf(AppException.class)
                .hasMessage(ErrorCode.INTERNAL_SERVER_ERROR.getMessage());
    }

    private IdempotencyRecordPayload payload(IdempotencyStatus status,
                                             Integer responseCode,
                                             String responseBody,
                                             String errorCode,
                                             String errorMessage) {
        return new IdempotencyRecordPayload(
                ACTOR_ID,
                METHOD,
                PATH,
                KEY,
                sha256(METHOD + "|" + PATH + "|"),
                status,
                responseCode,
                responseBody,
                errorCode,
                errorMessage,
                Instant.now(),
                status == IdempotencyStatus.PROCESSING ? null : Instant.now()
        );
    }

    private String writePayload(IdempotencyRecordPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private IdempotencyRecordPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, IdempotencyRecordPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pathHash(String path) {
        return sha256(path).substring(0, 16);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
