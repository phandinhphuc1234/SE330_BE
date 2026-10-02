-- Idempotent audit ledger for signed delivery-status callbacks from Resend.
-- The raw webhook body is deliberately not stored because it contains recipient
-- addresses and message metadata that are unnecessary for reconciliation.
CREATE TABLE notification_provider_events (
    id BIGSERIAL PRIMARY KEY,
    webhook_message_id VARCHAR(255) NOT NULL, -- NOSONAR: PostgreSQL VARCHAR, not Oracle PL/SQL.
    provider VARCHAR(30) NOT NULL, -- NOSONAR: PostgreSQL VARCHAR, not Oracle PL/SQL.
    provider_message_id VARCHAR(255) NOT NULL, -- NOSONAR: PostgreSQL VARCHAR, not Oracle PL/SQL.
    hinted_queue_id BIGINT,
    notification_queue_id BIGINT,
    event_type VARCHAR(100) NOT NULL, -- NOSONAR: PostgreSQL VARCHAR, not Oracle PL/SQL.
    occurred_at TIMESTAMP NOT NULL,
    provider_detail VARCHAR(1000), -- NOSONAR: PostgreSQL VARCHAR, not Oracle PL/SQL.
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- NOSONAR: PostgreSQL VARCHAR, not Oracle PL/SQL.
    processing_error VARCHAR(500), -- NOSONAR: PostgreSQL VARCHAR, not Oracle PL/SQL.
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP,
    CONSTRAINT uq_notification_provider_event_message UNIQUE (webhook_message_id),
    CONSTRAINT fk_notification_provider_event_queue
        FOREIGN KEY (notification_queue_id) REFERENCES notification_queue(id) ON DELETE SET NULL,
    CONSTRAINT chk_notification_provider_event_status
        CHECK (processing_status IN ('PENDING', 'PROCESSED', 'IGNORED', 'UNMATCHED'))
);

CREATE INDEX idx_notification_provider_event_provider_message
    ON notification_provider_events(provider_message_id);

CREATE INDEX idx_notification_provider_event_unmatched
    ON notification_provider_events(processing_status, created_at)
    WHERE processing_status = 'UNMATCHED';
