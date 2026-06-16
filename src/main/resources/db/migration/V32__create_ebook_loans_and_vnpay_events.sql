-- ============================================================
-- V32__create_ebook_loans_and_vnpay_events.sql
-- Store paid ebook access grants and provider-specific callback events.
-- ============================================================

ALTER TABLE payment_events
    DROP CONSTRAINT chk_payment_events_event_type;

ALTER TABLE payment_events
    ADD CONSTRAINT chk_payment_events_event_type
        CHECK (event_type IN ('IPN', 'RETURN', 'VNPAY_IPN', 'VNPAY_RETURN'));

CREATE TABLE ebook_loans (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL,
    book_id BIGINT NOT NULL,
    book_ebook_id BIGINT NOT NULL,
    payment_id BIGINT,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    borrowed_at TIMESTAMP NOT NULL,
    expired_at TIMESTAMP NOT NULL,
    returned_at TIMESTAMP,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_ebook_loans_member
        FOREIGN KEY (member_id)
        REFERENCES members(id),

    CONSTRAINT fk_ebook_loans_book
        FOREIGN KEY (book_id)
        REFERENCES books(id),

    CONSTRAINT fk_ebook_loans_book_ebook
        FOREIGN KEY (book_ebook_id)
        REFERENCES book_ebooks(id),

    CONSTRAINT fk_ebook_loans_payment
        FOREIGN KEY (payment_id)
        REFERENCES payment_transactions(id),

    CONSTRAINT chk_ebook_loans_status
        CHECK (status IN ('ACTIVE', 'EXPIRED', 'RETURNED', 'REVOKED')),

    CONSTRAINT chk_ebook_loans_expired_after_borrowed
        CHECK (expired_at > borrowed_at)
);

CREATE INDEX idx_ebook_loans_ebook_status_expired
    ON ebook_loans(book_ebook_id, status, expired_at);

CREATE INDEX idx_ebook_loans_member_ebook_status
    ON ebook_loans(member_id, book_ebook_id, status);

CREATE INDEX idx_ebook_loans_payment_id
    ON ebook_loans(payment_id);

CREATE UNIQUE INDEX ux_ebook_loan_payment
    ON ebook_loans(payment_id)
    WHERE payment_id IS NOT NULL;

CREATE UNIQUE INDEX ux_member_active_ebook_loan
    ON ebook_loans(member_id, book_ebook_id)
    WHERE status = 'ACTIVE';
