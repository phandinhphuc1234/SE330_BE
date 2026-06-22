-- Add SeaweedFS S3 object metadata while retaining legacy Cloudinary columns for existing rows.
ALTER TABLE book_ebooks
    DROP CONSTRAINT chk_book_ebooks_provider;

ALTER TABLE book_ebooks
    ADD CONSTRAINT chk_book_ebooks_provider
        CHECK (provider IN ('CLOUDINARY', 'SEAWEEDFS')),
    ADD COLUMN bucket_name VARCHAR(255),
    ADD COLUMN object_key VARCHAR(1000),
    ADD COLUMN checksum_sha256 VARCHAR(64),
    ALTER COLUMN public_id DROP NOT NULL;

CREATE UNIQUE INDEX uq_book_ebooks_bucket_object_key
    ON book_ebooks(bucket_name, object_key)
    WHERE bucket_name IS NOT NULL AND object_key IS NOT NULL;

ALTER TABLE book_ebooks
    ADD CONSTRAINT chk_book_ebooks_storage_reference
        CHECK (
            (provider = 'CLOUDINARY' AND public_id IS NOT NULL)
            OR
            (provider = 'SEAWEEDFS' AND bucket_name IS NOT NULL AND object_key IS NOT NULL)
        );
