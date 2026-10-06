ALTER TABLE book_ebooks
    ADD COLUMN ingestion_poll_failure_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN ingestion_next_check_at TIMESTAMP WITH TIME ZONE;

UPDATE book_ebooks
SET ingestion_status = 'INDEX_FAILED'
WHERE ingestion_status = 'FAILED';

DROP INDEX IF EXISTS idx_book_ebooks_ingestion_sync;

CREATE INDEX idx_book_ebooks_ingestion_sync
    ON book_ebooks (ingestion_status, ingestion_next_check_at, indexing_requested_at, id)
    WHERE rag_job_id IS NOT NULL
      AND ingestion_status NOT IN ('NOT_REQUESTED', 'INDEXED', 'INDEX_FAILED');
