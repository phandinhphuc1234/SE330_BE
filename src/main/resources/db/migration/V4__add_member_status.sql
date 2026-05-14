-- ============================================================
-- V3__auth_email_verify_member_status.sql
-- ============================================================

-- ── 1. Member status ─────────────────────────────────────────
ALTER TABLE members
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

ALTER TABLE members
    ADD CONSTRAINT chk_member_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED'));