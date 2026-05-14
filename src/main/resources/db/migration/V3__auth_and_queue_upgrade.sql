-- ============================================================
-- V3__auth_email_verification.sql
-- Add Email Verification System (NO CONFLICT WITH V2)
-- ============================================================


-- ============================================================
-- 1. EMAIL VERIFICATION TABLE
-- ============================================================

CREATE TABLE email_verifications (
                                     id            BIGSERIAL PRIMARY KEY,

                                     member_id     BIGINT NOT NULL
                                         REFERENCES members(id) ON DELETE CASCADE,

                                     token         VARCHAR(255) NOT NULL UNIQUE,

                                     expires_at    TIMESTAMP NOT NULL,

                                     is_used       BOOLEAN NOT NULL DEFAULT FALSE,

                                     created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);


-- ============================================================
-- 2. INDEXES (PERFORMANCE + SECURITY)
-- ============================================================

-- lookup token nhanh
CREATE INDEX idx_email_token
    ON email_verifications(token);

-- query theo user
CREATE INDEX idx_email_member
    ON email_verifications(member_id);

-- chỉ cho phép 1 token active / user (BEST PRACTICE)
CREATE UNIQUE INDEX uq_email_active_token
    ON email_verifications(member_id)
    WHERE is_used = FALSE;

-- verify token nhanh (chỉ lấy token chưa dùng)
CREATE INDEX idx_email_token_active
    ON email_verifications(token)
    WHERE is_used = FALSE;

-- dọn dẹp token hết hạn (cron job sau này)
CREATE INDEX idx_email_expiry
    ON email_verifications(expires_at);


-- ============================================================
-- DONE
-- ============================================================