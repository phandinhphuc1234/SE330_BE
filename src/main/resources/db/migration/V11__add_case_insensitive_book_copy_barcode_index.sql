-- ============================================================
-- V11__add_case_insensitive_book_copy_barcode_index.sql
-- Speed up existsByBarcodeIgnoreCase(...) and enforce the same
-- case-insensitive barcode uniqueness rule that the service uses.
-- ============================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_book_copies_barcode_lower
    ON book_copies (LOWER(barcode));
