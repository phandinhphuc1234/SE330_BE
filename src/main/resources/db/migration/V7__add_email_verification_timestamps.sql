-- ============================================================
-- V7__add_email_verification_timestamps.sql
-- Add lifecycle timestamps for email verification tokens
-- ============================================================

ALTER TABLE email_verifications
--    Thời điêm được sử dụng
    ADD COLUMN used_at TIMESTAMP,
    -- Thời điểm được cập nhật trong hệ thống
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
--     Thời điểm được gửi lần cuối
    ADD COLUMN last_sent_at TIMESTAMP NOT NULL DEFAULT NOW();

-- Existing tokens were sent when they were created, before this column existed.
UPDATE email_verifications
SET last_sent_at = created_at
WHERE last_sent_at IS NULL;

-- Existing used tokens do not have the exact used time. Use created_at as a safe historical fallback.
UPDATE email_verifications
SET used_at = created_at
WHERE is_used = TRUE
  AND used_at IS NULL;

ALTER TABLE email_verifications
    ADD CONSTRAINT chk_email_used_at_after_created
        CHECK (used_at IS NULL OR used_at >= created_at),
    ADD CONSTRAINT chk_email_updated_at_after_created
        CHECK (updated_at >= created_at),
    ADD CONSTRAINT chk_email_last_sent_at_after_created
        CHECK (last_sent_at >= created_at);
