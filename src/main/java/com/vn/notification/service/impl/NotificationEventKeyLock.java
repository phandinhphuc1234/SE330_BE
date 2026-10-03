package com.vn.notification.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationEventKeyLock {

    private final JdbcTemplate jdbcTemplate;

    // Serialize producers of the same logical event inside their current
    // PostgreSQL transaction. The lock is released automatically on commit or rollback.
    public void acquire(String eventKey) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
            )) {
                statement.setString(1, eventKey);
                statement.execute();
                return null;
            }
        });
    }
}
