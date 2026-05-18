-- ============================================================
-- V16__auto_renewal_support.sql
-- Auto-renewal job settings and per-borrow attempt history.
-- ============================================================

INSERT INTO system_settings (key, value, description)
VALUES
    ('AUTO_RENEW_ENABLED', 'false', 'Enable daily automatic renewal job'),
    ('AUTO_RENEW_DAYS_BEFORE_DUE', '1', 'Run auto-renewal for borrows due in this many days'),
    ('AUTO_RENEW_NOTIFY_SUCCESS', 'true', 'Send email when auto-renewal succeeds'),
    ('AUTO_RENEW_NOTIFY_FAILURE', 'true', 'Send email when auto-renewal is blocked'),
    ('AUTO_RENEW_MAX_ITEMS_PER_RUN', '500', 'Maximum borrow records processed per auto-renewal job run')
ON CONFLICT (key) DO NOTHING;

UPDATE system_settings
SET value = '2',
    description = 'Default max renewal count per borrow'
WHERE key = 'MAX_RENEWALS_DEFAULT';

CREATE TABLE IF NOT EXISTS auto_renewal_attempts (
    id BIGSERIAL PRIMARY KEY,
    borrow_record_id BIGINT NOT NULL REFERENCES borrow_records(id),
    member_id BIGINT NOT NULL REFERENCES members(id),
    book_copy_id BIGINT NOT NULL REFERENCES book_copies(id),
    job_execution_log_id BIGINT REFERENCES job_execution_logs(id),

    attempted_at TIMESTAMP NOT NULL,
    result VARCHAR(30) NOT NULL,
    reason_code VARCHAR(100),
    reason_message TEXT,

    old_due_date TIMESTAMP,
    new_due_date TIMESTAMP,
    renew_count_before INT,
    renew_count_after INT,

    CONSTRAINT chk_auto_renewal_attempt_result
        CHECK (result IN ('SUCCESS', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_auto_renewal_attempt_borrow
    ON auto_renewal_attempts(borrow_record_id, attempted_at DESC);

CREATE INDEX IF NOT EXISTS idx_auto_renewal_attempt_member
    ON auto_renewal_attempts(member_id, attempted_at DESC);

CREATE INDEX IF NOT EXISTS idx_auto_renewal_attempt_job
    ON auto_renewal_attempts(job_execution_log_id);
