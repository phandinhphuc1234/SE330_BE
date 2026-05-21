CREATE TABLE book_import_jobs (
    id UUID PRIMARY KEY,
    original_filename VARCHAR(255),
    status VARCHAR(30) NOT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    processed_rows INT NOT NULL DEFAULT 0,
    success_rows INT NOT NULL DEFAULT 0,
    failed_rows INT NOT NULL DEFAULT 0,
    created_books INT NOT NULL DEFAULT 0,
    created_copies INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_book_import_job_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE book_import_job_errors (
    id BIGSERIAL PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES book_import_jobs(id) ON DELETE CASCADE,
    row_number INT NOT NULL,
    isbn VARCHAR(20),
    barcode VARCHAR(100),
    code VARCHAR(100) NOT NULL,
    message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_book_import_jobs_status
    ON book_import_jobs(status);

CREATE INDEX idx_book_import_job_errors_job_row
    ON book_import_job_errors(job_id, row_number);
