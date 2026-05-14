-- ============================================================
-- V6__fix_member_status_constraint.sql
-- Align DB CHECK constraint with Java MemberStatus enum
-- OLD: PENDING, ACTIVE, SUSPENDED
-- NEW: PENDING_VERIFICATION, ACTIVE, INACTIVE, BANNED
-- ============================================================

-- ── 1. Migrate existing data ────────────────────────────────
UPDATE members SET status = 'PENDING_VERIFICATION' WHERE status = 'PENDING';
UPDATE members SET status = 'INACTIVE'              WHERE status = 'SUSPENDED';

-- ── 2. Drop old constraint ──────────────────────────────────
ALTER TABLE members
    DROP CONSTRAINT chk_member_status;

-- ── 3. Add new constraint matching Java enum ────────────────
ALTER TABLE members
    ADD CONSTRAINT chk_member_status
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'INACTIVE', 'BANNED'));
