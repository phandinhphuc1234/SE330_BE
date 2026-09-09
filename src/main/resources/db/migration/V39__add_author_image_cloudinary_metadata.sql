ALTER TABLE authors
    ADD COLUMN image_provider VARCHAR(50),
    ADD COLUMN image_public_id VARCHAR(500);

ALTER TABLE authors
    ADD CONSTRAINT chk_authors_image_provider
        CHECK (image_provider IS NULL OR image_provider IN ('CLOUDINARY'));

CREATE UNIQUE INDEX uq_authors_image_public_id
    ON authors(image_public_id)
    WHERE image_public_id IS NOT NULL;
