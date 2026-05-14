-- ============================================================
-- V8__add_soft_delete_to_book_copies.sql
-- Add soft-delete support for physical book copies.
-- ============================================================

ALTER TABLE book_copies
    ADD COLUMN deleted_at TIMESTAMP,
    ADD COLUMN deleted_by BIGINT;

ALTER TABLE book_copies
    ADD CONSTRAINT fk_book_copy_deleted_by
        FOREIGN KEY (deleted_by) REFERENCES members(id) ON DELETE SET NULL;

CREATE INDEX idx_book_copies_active
    ON book_copies(book_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_book_copies_active_status
    ON book_copies(book_id, status)
    WHERE deleted_at IS NULL;
