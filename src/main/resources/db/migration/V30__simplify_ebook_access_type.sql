-- ============================================================
-- V30__simplify_ebook_access_type.sql
-- Keep ebook access policy simple: either FREE or PAID.
-- ============================================================

ALTER TABLE book_ebooks
    DROP CONSTRAINT chk_book_ebooks_access_fee_matches_type,
    DROP CONSTRAINT chk_book_ebooks_access_type;

UPDATE book_ebooks
SET access_type = 'PAID'
WHERE access_type = 'PAID_PER_LOAN';

UPDATE book_ebooks
SET access_type = 'FREE',
    access_fee = 0
WHERE access_type IN ('MEMBERSHIP_INCLUDED', 'STAFF_ONLY');

ALTER TABLE book_ebooks
    ADD CONSTRAINT chk_book_ebooks_access_type
        CHECK (access_type IN ('FREE', 'PAID')),
    ADD CONSTRAINT chk_book_ebooks_access_fee_matches_type
        CHECK (
            (access_type = 'PAID' AND access_fee > 0)
            OR (access_type = 'FREE' AND access_fee = 0)
        );
