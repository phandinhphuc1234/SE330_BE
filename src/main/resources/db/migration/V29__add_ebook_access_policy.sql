-- ============================================================
-- V29__add_ebook_access_policy.sql
-- Add MVP access policy fields for paid/free ebook access.
-- Payments/ebook loans should reference these values when implemented.
-- ============================================================

ALTER TABLE book_ebooks
    ADD COLUMN access_type VARCHAR(30) NOT NULL DEFAULT 'FREE',
    ADD COLUMN access_fee DECIMAL(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN currency VARCHAR(10) NOT NULL DEFAULT 'VND',
    ADD COLUMN access_duration_days INT NOT NULL DEFAULT 14;

UPDATE book_ebooks
SET access_duration_days = loan_duration_days
WHERE access_duration_days = 14
  AND loan_duration_days IS NOT NULL;

ALTER TABLE book_ebooks
    ADD CONSTRAINT chk_book_ebooks_access_type
        CHECK (access_type IN ('FREE', 'PAID_PER_LOAN', 'MEMBERSHIP_INCLUDED', 'STAFF_ONLY')),
    ADD CONSTRAINT chk_book_ebooks_access_fee_non_negative
        CHECK (access_fee >= 0),
    ADD CONSTRAINT chk_book_ebooks_currency_not_blank
        CHECK (length(trim(currency)) > 0),
    ADD CONSTRAINT chk_book_ebooks_access_duration_days_positive
        CHECK (access_duration_days > 0),
    ADD CONSTRAINT chk_book_ebooks_access_fee_matches_type
        CHECK (
            (access_type = 'PAID_PER_LOAN' AND access_fee > 0)
            OR (access_type <> 'PAID_PER_LOAN' AND access_fee = 0)
        );

CREATE INDEX idx_book_ebooks_access_type_status
    ON book_ebooks(access_type, status);
