-- ============================================================
-- V14__update_open_borrow_copy_constraint.sql
-- Enforce the business invariant that one physical copy can have
-- only one open borrow record at a time.
-- ============================================================

DROP INDEX IF EXISTS uq_active_borrow_copy;
DROP INDEX IF EXISTS uq_open_borrow_copy;

CREATE UNIQUE INDEX uq_open_borrow_copy
    ON borrow_records(book_copy_id)
    WHERE status IN ('BORROWED', 'OVERDUE', 'LOST');
