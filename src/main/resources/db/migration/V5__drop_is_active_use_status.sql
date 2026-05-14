-- ============================================================
-- V5__drop_is_active_use_status.sql
-- Remove redundant is_active column, status column covers all cases
-- ============================================================

-- ── 1. Drop index phụ thuộc vào is_active ───────────────────
DROP INDEX IF EXISTS idx_members_active;

-- ── 2. Drop column is_active ────────────────────────────────
ALTER TABLE members
DROP COLUMN is_active;

-- ── 3. Index mới dựa trên status ────────────────────────────
CREATE INDEX idx_members_active
    ON members(id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_members_status
    ON members(status);