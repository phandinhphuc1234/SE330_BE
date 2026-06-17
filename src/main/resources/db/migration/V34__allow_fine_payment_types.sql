-- ============================================================
-- V34__allow_fine_payment_types.sql
-- Expand payment_transactions constraints to allow overdue fine payments.
-- ============================================================

ALTER TABLE payment_transactions DROP CONSTRAINT chk_payment_transactions_purpose;
ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_transactions_purpose
    CHECK (purpose IN ('EBOOK_PAYMENT', 'OVERDUE_FINE'));

ALTER TABLE payment_transactions DROP CONSTRAINT chk_payment_transactions_target_type;
ALTER TABLE payment_transactions ADD CONSTRAINT chk_payment_transactions_target_type
    CHECK (target_type IN ('BOOK_EBOOK', 'BORROW_RECORD'));
