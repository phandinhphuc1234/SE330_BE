package com.vn.notification.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class NotificationQueueClaimRepository {

    private static final String CLAIM_DUE_EMAILS_SQL = """
            WITH candidates AS (
                SELECT id
                FROM notification_queue
                WHERE channel = 'EMAIL'
                  AND (
                      (
                          status IN ('PENDING', 'RETRY')
                          AND scheduled_at <= ?
                          AND next_attempt_at <= ?
                      )
                      OR (
                          status = 'PROCESSING'
                          AND (locked_at IS NULL OR locked_at <= ?)
                      )
                  )
                ORDER BY
                    CASE WHEN status = 'PROCESSING' THEN 0 ELSE 1 END,
                    next_attempt_at,
                    id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE notification_queue AS queue
            SET status = 'PROCESSING',
                locked_at = ?,
                locked_by = ?,
                last_attempt_at = ?,
                updated_at = ?,
                version = version + 1
            FROM candidates
            WHERE queue.id = candidates.id
            RETURNING queue.id
            """;

    private final JdbcTemplate jdbcTemplate;

    // One SQL statement both selects and marks rows. SKIP LOCKED lets several
    // application instances claim different work without waiting on each other.
    public List<Long> claimDueEmailIds(
            Instant now,
            Instant staleBefore,
            String workerId,
            int batchSize
    ) {
        Timestamp timestampNow = Timestamp.from(now);
        return jdbcTemplate.query(
                CLAIM_DUE_EMAILS_SQL,
                (resultSet, rowNumber) -> resultSet.getLong("id"),
                timestampNow,
                timestampNow,
                Timestamp.from(staleBefore),
                batchSize,
                timestampNow,
                workerId,
                timestampNow,
                timestampNow
        );
    }
}
