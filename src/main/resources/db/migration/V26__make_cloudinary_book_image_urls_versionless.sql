-- ============================================================
-- V26__make_cloudinary_book_image_urls_versionless.sql
-- Cloudinary assets do not all share the same version number.
--
-- Since book cover files are named by ISBN, use versionless delivery URLs:
--   https://res.cloudinary.com/dwgv3yx7e/image/upload/{transform}/{isbn}.png
--
-- This keeps the database independent from per-asset Cloudinary version values.
-- ============================================================

UPDATE book_images image
SET secure_url = 'https://res.cloudinary.com/dwgv3yx7e/image/upload/c_fit,w_320,h_480,q_auto,f_auto/'
                 || image.public_id
                 || '.png',
    updated_at = NOW()
WHERE image.provider = 'CLOUDINARY'
  AND image.asset_type = 'COVER_FRONT'
  AND image.is_primary = TRUE
  AND image.public_id IS NOT NULL
  AND image.public_id <> ''
  AND image.secure_url LIKE 'https://res.cloudinary.com/dwgv3yx7e/image/upload/%';

SELECT
    COUNT(*) AS versionless_primary_cloudinary_book_images
FROM book_images
WHERE provider = 'CLOUDINARY'
  AND asset_type = 'COVER_FRONT'
  AND is_primary = TRUE
  AND secure_url NOT LIKE '%/v%/%';
