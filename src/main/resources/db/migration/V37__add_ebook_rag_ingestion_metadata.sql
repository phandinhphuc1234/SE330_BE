ALTER TABLE book_ebooks
    ADD COLUMN ingestion_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUESTED',
    ADD COLUMN rag_document_id VARCHAR(100),
    ADD COLUMN rag_job_id BIGINT,
    ADD COLUMN ingestion_last_error VARCHAR(1000),
    ADD COLUMN indexing_requested_at TIMESTAMP WITH TIME ZONE;
