CREATE TABLE password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_password_reset_tokens_member
        FOREIGN KEY (member_id) REFERENCES members(id) ON DELETE CASCADE,
    CONSTRAINT uq_password_reset_tokens_token_hash
        UNIQUE (token_hash),
    CONSTRAINT chk_password_reset_tokens_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_password_reset_tokens_member_active
    ON password_reset_tokens(member_id, expires_at DESC)
    WHERE used_at IS NULL;
