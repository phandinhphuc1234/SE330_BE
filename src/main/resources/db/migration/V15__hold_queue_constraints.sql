-- ============================================================
-- V15__hold_queue_constraints.sql
-- Constraints/settings for hold queue lifecycle.
-- ============================================================

-- Một member chỉ được có một hold đang hoạt động cho cùng một đầu sách.
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_hold_member_book
    ON reservations(member_id, book_id)
    WHERE status IN ('WAITING', 'NOTIFIED', 'READY_FOR_PICKUP');

-- Một copy trên hold shelf chỉ được gán cho một hold đang hoạt động.
CREATE UNIQUE INDEX IF NOT EXISTS uq_active_hold_assigned_copy
    ON reservations(assigned_copy_id)
    WHERE assigned_copy_id IS NOT NULL
      AND status IN ('NOTIFIED', 'READY_FOR_PICKUP');

INSERT INTO system_settings (key, value, description)
VALUES
    ('HOLD_PICKUP_DAYS_DEFAULT', '3', 'Default number of days a ready hold stays on hold shelf')
ON CONFLICT (key) DO NOTHING;
