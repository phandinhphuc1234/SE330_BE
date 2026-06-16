-- V33__create_ebook_reading_sessions.sql
-- Store the source-of-truth reader sessions used to issue short-lived ebook content URLs.

CREATE TABLE ebook_reading_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_token_hash VARCHAR(255) NOT NULL UNIQUE,
    member_id BIGINT NOT NULL,
    book_id BIGINT NOT NULL,
    book_ebook_id BIGINT NOT NULL,
    loan_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    session_expires_at TIMESTAMP NOT NULL,
    last_heartbeat_at TIMESTAMP,
    closed_at TIMESTAMP,
    expired_at TIMESTAMP,
    revoked_at TIMESTAMP,
    revoke_reason VARCHAR(100),
    ip_address VARCHAR(100),
    user_agent_hash VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_ebook_reading_sessions_member
        FOREIGN KEY (member_id) REFERENCES members(id),
    CONSTRAINT fk_ebook_reading_sessions_book
        FOREIGN KEY (book_id) REFERENCES books(id),
    CONSTRAINT fk_ebook_reading_sessions_book_ebook
        FOREIGN KEY (book_ebook_id) REFERENCES book_ebooks(id),
    CONSTRAINT fk_ebook_reading_sessions_loan
        FOREIGN KEY (loan_id) REFERENCES ebook_loans(id),
    CONSTRAINT chk_ebook_reading_sessions_status
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'CLOSED', 'REVOKED'))
);

CREATE INDEX idx_reading_sessions_status_expires
    ON ebook_reading_sessions(status, session_expires_at);

CREATE INDEX idx_reading_sessions_member_status
    ON ebook_reading_sessions(member_id, status);

CREATE INDEX idx_reading_sessions_loan_status
    ON ebook_reading_sessions(loan_id, status);

CREATE INDEX idx_reading_sessions_active_expires
    ON ebook_reading_sessions(session_expires_at)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_reading_sessions_active_member
    ON ebook_reading_sessions(member_id, session_expires_at)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_reading_sessions_active_loan
    ON ebook_reading_sessions(loan_id, session_expires_at)
    WHERE status = 'ACTIVE';
