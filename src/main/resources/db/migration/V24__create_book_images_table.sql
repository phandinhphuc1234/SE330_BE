-- ============================================================
-- V24__create_book_images_table.sql
-- Move catalog cover images out of books into a dedicated image
-- metadata table. The actual image file remains on Cloudinary.
-- ============================================================

CREATE TABLE book_images (
    id BIGSERIAL PRIMARY KEY,
    book_id BIGINT NOT NULL,

    provider VARCHAR(50) NOT NULL DEFAULT 'CLOUDINARY',
    public_id VARCHAR(512) NOT NULL,
    secure_url VARCHAR(2048) NOT NULL,

    asset_type VARCHAR(50) NOT NULL DEFAULT 'COVER_FRONT',
    format VARCHAR(20),
    width INT,
    height INT,
    bytes BIGINT,
    alt_text VARCHAR(255),

    sort_order INT NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_book_images_book
        FOREIGN KEY (book_id)
        REFERENCES books(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_book_images_provider
        CHECK (provider IN ('CLOUDINARY')),

    CONSTRAINT chk_book_images_asset_type
        CHECK (asset_type IN ('COVER_FRONT', 'COVER_BACK', 'PREVIEW', 'OTHER')),

    CONSTRAINT chk_book_images_width_positive
        CHECK (width IS NULL OR width > 0),

    CONSTRAINT chk_book_images_height_positive
        CHECK (height IS NULL OR height > 0),

    CONSTRAINT chk_book_images_bytes_non_negative
        CHECK (bytes IS NULL OR bytes >= 0),

    CONSTRAINT chk_book_images_sort_order_non_negative
        CHECK (sort_order >= 0)
);

CREATE INDEX idx_book_images_book_id
    ON book_images(book_id);

CREATE INDEX idx_book_images_book_primary
    ON book_images(book_id, is_primary);

CREATE UNIQUE INDEX uq_book_images_provider_public_id
    ON book_images(provider, public_id);

-- A book can have many images, but only one primary cover.
CREATE UNIQUE INDEX uq_book_images_one_primary_per_book
    ON book_images(book_id)
    WHERE is_primary = TRUE;

-- Preserve any data written while V23 used books.image_url directly.
-- For simple Cloudinary URLs that contain /image/upload/v123/..., keep the
-- Cloudinary public_id. For anything else, use a stable legacy reference.
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
    book.id,
    'CLOUDINARY',
    CASE
        WHEN book.image_url ~ '^https://res\.cloudinary\.com/[^/]+/image/upload/v[0-9]+/.+\.[A-Za-z0-9]+(\?.*)?$'
            THEN regexp_replace(
                    regexp_replace(
                        book.image_url,
                        '^https://res\.cloudinary\.com/[^/]+/image/upload/v[0-9]+/',
                        ''
                    ),
                    '\.[A-Za-z0-9]+(\?.*)?$',
                    ''
                 )
        ELSE 'legacy/book-' || book.id || '-primary'
    END,
    book.image_url,
    'COVER_FRONT',
    lower(substring(book.image_url from '\.([A-Za-z0-9]+)(?:\?.*)?$')),
    left('Book cover for ' || book.title, 255),
    0,
    TRUE,
    NOW(),
    NOW()
FROM books book
WHERE book.image_url IS NOT NULL
  AND btrim(book.image_url) <> ''
ON CONFLICT DO NOTHING;

ALTER TABLE books
    DROP COLUMN image_url;
