-- ============================================================
-- V25__seed_cloudinary_book_images_from_isbn.sql
-- Seed primary book cover metadata from Cloudinary assets.
--
-- Current Cloudinary convention:
--   https://res.cloudinary.com/dwgv3yx7e/image/upload/v1780497401/{isbn}.png
--
-- The file name is the book ISBN, so the database can generate the Cloudinary
-- public_id and delivery URL directly from books.isbn.
-- ============================================================

WITH isbn_named_images AS (
    SELECT
        book.id AS book_id,
        book.title,
        regexp_replace(book.isbn, '[^0-9Xx]', '', 'g') AS public_id
    FROM books book
    WHERE book.isbn IS NOT NULL
      AND btrim(book.isbn) <> ''
)
INSERT INTO book_images (
    book_id,
    provider,
    public_id,
    secure_url,
    asset_type,
    format,
    alt_text,
    sort_order,
    is_primary,
    created_at,
    updated_at
)
SELECT
    image.book_id,
    'CLOUDINARY',
    image.public_id,
    'https://res.cloudinary.com/dwgv3yx7e/image/upload/c_fit,w_320,h_480,q_auto,f_auto/v1780497401/'
        || image.public_id
        || '.png',
    'COVER_FRONT',
    'png',
    left('Book cover for ' || image.title, 255),
    0,
    TRUE,
    NOW(),
    NOW()
FROM isbn_named_images image
WHERE image.public_id <> ''
  AND NOT EXISTS (
      SELECT 1
      FROM book_images existing_image
      WHERE existing_image.book_id = image.book_id
        AND existing_image.is_primary = TRUE
  )
ON CONFLICT DO NOTHING;

-- Useful Flyway log line: how many active primary Cloudinary covers exist now.
SELECT
    COUNT(*) AS primary_cloudinary_book_images
FROM book_images
WHERE provider = 'CLOUDINARY'
  AND asset_type = 'COVER_FRONT'
  AND is_primary = TRUE;
