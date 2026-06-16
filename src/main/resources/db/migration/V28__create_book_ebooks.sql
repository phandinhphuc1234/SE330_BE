-- ============================================================
-- V28__create_book_ebooks.sql
-- Store protected ebook PDF metadata separately from books.
-- The actual PDF file is stored on Cloudinary as raw/authenticated.
-- ============================================================

CREATE TABLE book_ebooks (
    id BIGSERIAL PRIMARY KEY,
    book_id BIGINT NOT NULL,

    provider VARCHAR(50) NOT NULL DEFAULT 'CLOUDINARY',
    public_id VARCHAR(500) NOT NULL,
    resource_type VARCHAR(30) NOT NULL DEFAULT 'RAW',
    delivery_type VARCHAR(30) NOT NULL DEFAULT 'AUTHENTICATED',

    format VARCHAR(30),
    mime_type VARCHAR(100),
    original_filename VARCHAR(255),
    version BIGINT,
    size_bytes BIGINT,
    checksum VARCHAR(128),

    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    max_concurrent_loans INT NOT NULL DEFAULT 5,
    loan_duration_days INT NOT NULL DEFAULT 14,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_book_ebooks_book
        FOREIGN KEY (book_id)
        REFERENCES books(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_book_ebooks_provider
        CHECK (provider IN ('CLOUDINARY')),

    CONSTRAINT chk_book_ebooks_resource_type
        CHECK (resource_type IN ('RAW')),

    CONSTRAINT chk_book_ebooks_delivery_type
        CHECK (delivery_type IN ('UPLOAD', 'AUTHENTICATED', 'PRIVATE')),

    CONSTRAINT chk_book_ebooks_status
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'FAILED', 'DELETED')),

    CONSTRAINT chk_book_ebooks_size_bytes_non_negative
        CHECK (size_bytes IS NULL OR size_bytes >= 0),

    CONSTRAINT chk_book_ebooks_max_concurrent_loans_positive
        CHECK (max_concurrent_loans > 0),

    CONSTRAINT chk_book_ebooks_loan_duration_days_positive
        CHECK (loan_duration_days > 0)
);

CREATE INDEX idx_book_ebooks_book_status
    ON book_ebooks(book_id, status);

CREATE INDEX idx_book_ebooks_status
    ON book_ebooks(status);

CREATE UNIQUE INDEX uq_book_ebooks_provider_public_id
    ON book_ebooks(provider, public_id);
