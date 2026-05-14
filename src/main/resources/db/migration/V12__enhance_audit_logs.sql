-- ============================================================
-- V12__enhance_audit_logs.sql
-- Add request context fields for business audit history.
-- ============================================================

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS actor_role VARCHAR(20),
    ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45),
    ADD COLUMN IF NOT EXISTS user_agent VARCHAR(255);

ALTER TABLE audit_logs
    ALTER COLUMN metadata SET DEFAULT '{}'::jsonb;

ALTER TABLE audit_logs
DROP CONSTRAINT IF EXISTS chk_audit_actor_role;

ALTER TABLE audit_logs
    ADD CONSTRAINT chk_audit_actor_role
        CHECK (actor_role IS NULL OR actor_role IN ('MEMBER', 'ADMIN', 'LIBRARIAN'));

CREATE INDEX IF NOT EXISTS idx_audit_created_at
    ON audit_logs(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_action
    ON audit_logs(action);

CREATE INDEX IF NOT EXISTS idx_audit_trace_id
    ON audit_logs(trace_id);
