-- ============================================================
-- V13__circulation_core_upgrade.sql
-- Core circulation support: item statuses, renewal fields,
-- idempotency records and job execution logs.
-- ============================================================

ALTER TABLE book_copies
    DROP CONSTRAINT IF EXISTS chk_copy_status;
-- Thêm các enums cho trạng thái của book copy
ALTER TABLE book_copies
    ADD CONSTRAINT chk_copy_status CHECK (
        status IN (
            'AVAILABLE',
            'BORROWED',
            'RESERVED',
            'OVERDUE',
            'ON_HOLD_SHELF',
            'LOST',
            'DAMAGED',
            'REMOVED'
        )
    );
-- Thêm constraint chỉ được gia hạn thêm 1 lần duy nhất
ALTER TABLE borrow_records
    ADD COLUMN IF NOT EXISTS renew_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS max_renewals_at_checkout INT NOT NULL DEFAULT 1;
-- Thêm contraints ko được để renew_count_non_negative
ALTER TABLE borrow_records
    DROP CONSTRAINT IF EXISTS chk_renew_count_non_negative;
--
ALTER TABLE borrow_records
    ADD CONSTRAINT chk_renew_count_non_negative
        CHECK (renew_count >= 0);

ALTER TABLE reservations
    DROP CONSTRAINT IF EXISTS chk_reservation_status;

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservation_status CHECK (
        status IN (
            'WAITING',
            'NOTIFIED',
            'READY_FOR_PICKUP',
            'FULFILLED',
            'CANCELLED',
            'EXPIRED'
        )
    );

ALTER TABLE reservations
    ADD COLUMN IF NOT EXISTS assigned_copy_id BIGINT;

ALTER TABLE reservations
    DROP CONSTRAINT IF EXISTS fk_reservation_assigned_copy;

ALTER TABLE reservations
    ADD CONSTRAINT fk_reservation_assigned_copy
        FOREIGN KEY (assigned_copy_id) REFERENCES book_copies(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_reservation_assigned_copy
    ON reservations(assigned_copy_id);

CREATE INDEX IF NOT EXISTS idx_book_copies_available
    ON book_copies(book_id)
    WHERE status = 'AVAILABLE' AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    normalized_path VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    response_code INT,
    response_body TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    error_code VARCHAR(100),
    error_message TEXT,
    CONSTRAINT uk_idempotency_scope_key
        UNIQUE (actor_id, http_method, normalized_path, idempotency_key),
    CONSTRAINT chk_idempotency_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires_at
    ON idempotency_records(expires_at);
-- Thêm table lưu dùng để ghi lịch sử chạy của các background job / scheduled job trong hệ thống.
CREATE TABLE IF NOT EXISTS job_execution_logs (
    id BIGSERIAL PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    status VARCHAR(30) NOT NULL,
    total_processed INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    CONSTRAINT chk_job_execution_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);
-- Insert 1 số system vào hệ thống
INSERT INTO system_settings (key, value, description)
VALUES
    ('MAX_RENEWALS_DEFAULT', '1', 'Default max renewal count per borrow'),
    ('RENEWAL_DAYS_DEFAULT', '7', 'Default extra days for each renewal'),
    ('ALLOW_RENEW_OVERDUE', 'false', 'Allow renewing overdue borrow')
ON CONFLICT (key) DO NOTHING;
