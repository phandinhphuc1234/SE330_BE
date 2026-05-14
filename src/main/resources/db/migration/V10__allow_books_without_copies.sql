-- ============================================================
-- V10__allow_books_without_copies.sql
-- Align database constraints with the current catalog design:
-- a Book can exist before any physical BookCopy is added.
-- ============================================================

ALTER TABLE books
    DROP CONSTRAINT IF EXISTS chk_total_copies;

ALTER TABLE books
    DROP CONSTRAINT IF EXISTS chk_available_copies;

ALTER TABLE books
    DROP CONSTRAINT IF EXISTS chk_available_lte_total;

ALTER TABLE books
    ADD CONSTRAINT chk_total_copies
        CHECK (total_copies >= 0);

ALTER TABLE books
    ADD CONSTRAINT chk_available_copies
        CHECK (available_copies >= 0);

ALTER TABLE books
    ADD CONSTRAINT chk_available_lte_total
        CHECK (available_copies <= total_copies);
