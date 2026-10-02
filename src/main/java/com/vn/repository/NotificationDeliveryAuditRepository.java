package com.vn.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class NotificationDeliveryAuditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public void recordManualRetry(
            Long adminId,
            Long queueId,
            int previousAttempt,
            int newAttempt,
            String previousError
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("previousAttempt", previousAttempt);
        metadata.put("newAttempt", newAttempt);
        metadata.put("previousError", previousError);

        jdbcTemplate.update("""
                INSERT INTO audit_logs(
                    user_id,
                    action,
                    entity_type,
                    entity_id,
                    metadata,
                    trace_id,
                    actor_role
                ) VALUES (?, 'RETRY_NOTIFICATION_DELIVERY', 'NOTIFICATION_QUEUE', ?, CAST(? AS jsonb), ?, 'ADMIN')
                """,
                adminId,
                queueId,
                toJson(metadata),
                MDC.get("traceId")
        );
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize notification delivery audit metadata", exception);
        }
    }
}
