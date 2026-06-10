-- ============================================================
-- V27__book_cover_upload_lifecycle.sql
-- Add Cloudinary upload metadata and lifecycle status for managed
-- book cover uploads.
-- ============================================================

ALTER TABLE book_images
    ADD COLUMN version BIGINT,
    ADD COLUMN mime_type VARCHAR(100),
    ADD COLUMN size_bytes BIGINT,
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN deleted_at TIMESTAMP;

UPDATE book_images
SET size_bytes = bytes
WHERE size_bytes IS NULL
  AND bytes IS NOT NULL;

ALTER TABLE book_images
    DROP COLUMN bytes;

ALTER TABLE book_images
    ADD CONSTRAINT chk_book_images_status
        CHECK (status IN ('ACTIVE', 'REPLACED', 'DELETE_PENDING', 'DELETED', 'PURGED', 'FAILED')),
    ADD CONSTRAINT chk_book_images_size_bytes_non_negative
        CHECK (size_bytes IS NULL OR size_bytes >= 0);

CREATE INDEX idx_book_images_status
    ON book_images(status);

DROP INDEX IF EXISTS uq_book_images_one_primary_per_book;

CREATE UNIQUE INDEX uq_book_images_one_active_primary_per_book
    ON book_images(book_id)
    WHERE is_primary = TRUE AND status = 'ACTIVE';
