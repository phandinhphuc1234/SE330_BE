package com.vn.notification.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class NotificationQueueMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void createRepresentativeV42Schema() throws SQLException {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS notification_queue CASCADE");
            statement.execute("DROP TABLE IF EXISTS notifications CASCADE");
            statement.execute("DROP TABLE IF EXISTS members CASCADE");

            statement.execute("""
                    CREATE TABLE members (
                        id BIGINT PRIMARY KEY,
                        email VARCHAR(100) NOT NULL
                    )
                    """);
            statement.execute("CREATE TABLE notifications (id BIGINT PRIMARY KEY)");
            statement.execute("""
                    CREATE TABLE notification_queue (
                        id BIGSERIAL PRIMARY KEY,
                        member_id BIGINT REFERENCES members(id),
                        notification_id BIGINT REFERENCES notifications(id),
                        channel VARCHAR(20) NOT NULL,
                        status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                        retry_count INT DEFAULT 0,
                        scheduled_at TIMESTAMP NOT NULL DEFAULT NOW(),
                        sent_at TIMESTAMP,
                        notification_type VARCHAR(100),
                        target_type VARCHAR(100),
                        target_id BIGINT,
                        CONSTRAINT chk_queue_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
                    )
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX uq_notification_queue_once_per_target
                    ON notification_queue(notification_type, target_type, target_id, channel)
                    WHERE notification_type IS NOT NULL
                      AND target_type IS NOT NULL
                      AND target_id IS NOT NULL
                    """);

            statement.execute("INSERT INTO members(id, email) VALUES (7, 'legacy-reader@example.com')");
            statement.execute("INSERT INTO notifications(id) VALUES (11)");
            statement.execute("""
                    INSERT INTO notification_queue(
                        member_id,
                        notification_id,
                        channel,
                        status,
                        retry_count,
                        scheduled_at,
                        notification_type,
                        target_type,
                        target_id
                    ) VALUES (
                        7,
                        11,
                        'EMAIL',
                        'PENDING',
                        0,
                        TIMESTAMP '2026-09-29 08:00:00',
                        'DUE_SOON_REMINDER',
                        'BORROW_RECORD',
                        99
                    )
                    """);
            statement.execute("""
                    INSERT INTO notification_queue(
                        channel,
                        status,
                        retry_count,
                        scheduled_at
                    ) VALUES (
                        'EMAIL',
                        'FAILED',
                        NULL,
                        TIMESTAMP '2026-09-29 09:00:00'
                    )
                    """);
        }
    }

    @Test
    void migrationShouldUpgradeLegacyRowsAndEnforceDeliveryContract() throws Exception {
        try (Connection connection = openConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/migration/V43__upgrade_notification_delivery_queue.sql")
            );
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/migration/V44__activate_notification_outbox_producers.sql")
            );
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/migration/V45__add_notification_provider_webhook_events.sql")
            );
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource("db/migration/V46__add_notification_delivery_operations.sql")
            );

            assertReplayableLegacyRowUpgraded(connection);
            assertFallbackLegacyRowUpgraded(connection);

            assertThat(columnType(connection, "payload")).isEqualTo("jsonb");
            assertThat(indexNames(connection)).contains(
                    "uq_notification_queue_event_key",
                    "idx_notification_queue_dispatch",
                    "idx_notification_queue_stale_processing",
                    "uq_notification_queue_provider_message_id",
                    "uq_notification_queue_provider_request_key"
            );
            assertThat(indexNames(connection)).doesNotContain("uq_notification_queue_once_per_target");
            assertThat(tableExists(connection, "notification_provider_events")).isTrue();

            // V44 replaces the coarse target uniqueness rule with event-key
            // idempotency, so distinct events may reference the same target.
            execute(connection, """
                    INSERT INTO notification_queue(
                        channel,
                        status,
                        retry_count,
                        scheduled_at,
                        notification_type,
                        target_type,
                        target_id,
                        event_key,
                        provider_request_key,
                        recipient_email,
                        template_code,
                        payload
                    ) VALUES (
                        'EMAIL',
                        'PENDING',
                        0,
                        NOW(),
                        'DUE_SOON_REMINDER',
                        'BORROW_RECORD',
                        99,
                        'DUE_SOON_REMINDER:BORROW_RECORD:99:EMAIL:REPLAY-2',
                        'DUE_SOON_REMINDER:BORROW_RECORD:99:EMAIL:REPLAY-2',
                        'legacy-reader@example.com',
                        'due-soon-reminder',
                        '{"bookTitle":"Clean Code"}'::jsonb
                    )
                    """);

            execute(connection, """
                    INSERT INTO notification_queue(
                        channel,
                        status,
                        retry_count,
                        scheduled_at,
                        event_key,
                        provider_request_key,
                        recipient_email,
                        template_code
                    ) VALUES (
                        'EMAIL',
                        'PROCESSING',
                        0,
                        NOW(),
                        'ACCOUNT_BANNED:MEMBER_STATUS_AUDIT:1:EMAIL',
                        'ACCOUNT_BANNED:MEMBER_STATUS_AUDIT:1:EMAIL',
                        'reader@example.com',
                        'account-banned'
                    )
                    """);

            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO notification_queue(
                        channel,
                        status,
                        retry_count,
                        scheduled_at,
                        event_key,
                        provider_request_key,
                        template_code
                    ) VALUES (
                        'EMAIL',
                        'PENDING',
                        0,
                        NOW(),
                        'ACCOUNT_BANNED:MEMBER_STATUS_AUDIT:1:EMAIL',
                        'ACCOUNT_BANNED:MEMBER_STATUS_AUDIT:1:EMAIL-DUPLICATE',
                        'account-banned'
                    )
                    """))
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO notification_queue(
                        channel,
                        status,
                        retry_count,
                        scheduled_at,
                        event_key,
                        provider_request_key,
                        template_code
                    ) VALUES (
                        'EMAIL',
                        'UNKNOWN',
                        0,
                        NOW(),
                        'UNKNOWN:1:EMAIL',
                        'UNKNOWN:1:EMAIL',
                        'unknown'
                    )
                    """))
                    .isInstanceOf(SQLException.class);
        }
    }

    private void assertReplayableLegacyRowUpgraded(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT event_key,
                            recipient_email,
                            template_code,
                            payload::text,
                            max_attempts,
                            next_attempt_at,
                            created_at,
                            updated_at,
                            version,
                            status,
                            last_error,
                            delivery_attempt,
                            provider_request_key
                     FROM notification_queue
                     WHERE id = 1
                     """)) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString("event_key"))
                    .isEqualTo("DUE_SOON_REMINDER:BORROW_RECORD:99:EMAIL");
            assertThat(row.getString("recipient_email")).isEqualTo("legacy-reader@example.com");
            assertThat(row.getString("template_code")).isEqualTo("due-soon-reminder");
            assertThat(row.getString("payload")).isEqualTo("{}");
            assertThat(row.getInt("max_attempts")).isEqualTo(5);
            assertThat(row.getObject("next_attempt_at", LocalDateTime.class))
                    .isEqualTo(LocalDateTime.of(2026, 9, 29, 8, 0));
            assertThat(row.getObject("created_at", LocalDateTime.class))
                    .isEqualTo(LocalDateTime.of(2026, 9, 29, 8, 0));
            assertThat(row.getObject("updated_at", LocalDateTime.class))
                    .isAfter(LocalDateTime.of(2026, 9, 29, 8, 0));
            assertThat(row.getLong("version")).isZero();
            assertThat(row.getString("status")).isEqualTo("DEAD");
            assertThat(row.getString("last_error")).isEqualTo("LEGACY_PAYLOAD_NOT_REPLAYABLE");
            assertThat(row.getInt("delivery_attempt")).isEqualTo(1);
            assertThat(row.getString("provider_request_key")).isEqualTo(row.getString("event_key"));
        }
    }

    private void assertFallbackLegacyRowUpgraded(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet legacyRow = statement.executeQuery("""
                     SELECT event_key, template_code, retry_count, recipient_email
                     FROM notification_queue
                     WHERE id = 2
                     """)) {
            assertThat(legacyRow.next()).isTrue();
            assertThat(legacyRow.getString("event_key")).isEqualTo("LEGACY_NOTIFICATION_QUEUE:2");
            assertThat(legacyRow.getString("template_code")).isEqualTo("legacy-notification");
            assertThat(legacyRow.getInt("retry_count")).isZero();
            assertThat(legacyRow.getString("recipient_email")).isNull();
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private String columnType(Connection connection, String columnName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'notification_queue'
                  AND column_name = ?
                """)) {
            statement.setString(1, columnName);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString("data_type");
            }
        }
    }

    private List<String> indexNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT indexname
                     FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND tablename = 'notification_queue'
                     """)) {
            while (result.next()) {
                names.add(result.getString("indexname"));
            }
        }
        return names;
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name = ?
                )
                """)) {
            statement.setString(1, tableName);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
