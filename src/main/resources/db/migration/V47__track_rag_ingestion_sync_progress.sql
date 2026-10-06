ALTER TABLE book_ebooks
    ADD COLUMN ingestion_stage VARCHAR(100),
    ADD COLUMN ingestion_last_checked_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN indexing_completed_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_book_ebooks_ingestion_sync
    ON book_ebooks (ingestion_status, indexing_requested_at, id)
    WHERE rag_job_id IS NOT NULL
      AND ingestion_status NOT IN ('NOT_REQUESTED', 'INDEXED', 'FAILED');
