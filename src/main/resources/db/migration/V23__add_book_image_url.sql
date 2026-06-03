-- ============================================================
-- V23__add_book_image_url.sql
-- Store an optional absolute cover image URL for catalog display.
-- ============================================================

ALTER TABLE books
    ADD COLUMN image_url VARCHAR(2048);
