# Library Ebook PDF Ingestion

## Scope

Spring Boot uploads ebook PDFs. RAG does not expose a browser/direct-upload API for library ebooks.

```text
Spring Boot PUTs PDF to SeaweedFS
  -> Spring commits metadata
  -> Spring POSTs bucket/key/checksum to RAG
  -> RAG enqueues Celery ingestion
```

## Request

```http
POST /internal/ingestions
X-RAG-API-Key: <service-secret>
Content-Type: application/json
```

```json
{
  "sourceType": "LIBRARY_EBOOK",
  "bookId": 101,
  "ebookId": 55,
  "bucket": "library-private",
  "objectKey": "ebooks/101/55/original.pdf",
  "checksumSha256": "<64 hexadecimal characters>"
}
```

RAG validates:

- Service API key.
- `sourceType == LIBRARY_EBOOK`.
- Bucket equals configured `LIBRARY_EBOOK_BUCKET`.
- Key equals `ebooks/{bookId}/{ebookId}/original.pdf`.
- SHA-256 has exactly 64 hexadecimal characters.
- IDs are positive integers.

## Job creation

RAG maps the upstream source to:

```text
external_document_id = doc_ebook_{ebookId}
source_type          = LIBRARY_EBOOK
source_id            = ebook:{ebookId}
```

It creates/updates a `RAW_ORIGINAL` artifact containing the supplied bucket, object key and checksum, then creates an ingestion job and commits before enqueueing Celery.

Repeated requests with the same ebook/checksum reuse the latest non-failed job. A failed job may be recreated. Changed checksums create a new job for the same document; immutable versioning remains a production follow-up.

## Worker pipeline

```text
Redis delivers task
  -> worker loads document/job/artifact from PostgreSQL
  -> worker HEAD checks artifact.bucket/artifact.object_key in SeaweedFS
  -> worker downloads artifact.bucket/artifact.object_key into a task-scoped temp file
  -> worker verifies local temp file SHA-256 against artifact.checksum_sha256
  -> validate local temporary file extension and PDF magic bytes
  -> validate PDF structure, encryption status, and page count with pypdf
  -> sample PDF text layer; fail with PDF_OCR_REQUIRED when OCR is needed
  -> PyMuPDF4LLM extracts Markdown text per page
  -> clean text
  -> LlamaIndex sentence-aware chunking
  -> persist document_chunks
  -> persist rag-artifacts
  -> embed chunks
  -> upsert Qdrant
  -> status INDEXED
```

Source metadata is propagated into chunks:

```json
{
  "documentId": "doc_ebook_55",
  "sourceType": "LIBRARY_EBOOK",
  "bookId": 101,
  "ebookId": 55,
  "chunkIndex": 1,
  "pageStart": 10,
  "pageEnd": 10
}
```

## Current state machine

```text
QUEUED
  -> PROCESSING (loading_document)
  -> PROCESSING (parsing_pdf)
  -> PROCESSING (cleaning_text)
  -> PROCESSING (chunking)
  -> CHUNKED
  -> EMBEDDING
  -> EMBEDDED
  -> INDEXING
  -> INDEXED
```

On error:

```text
any state -> FAILED
```

Current terminal success is `INDEXED`. `CHUNKED` remains an intermediate
commit point after chunks/artifacts are persisted, so retry/debug is still safe
if embedding or Qdrant fails later.

## Status polling

```http
GET /internal/ingestions/{jobId}
X-RAG-API-Key: <service-secret>
```

Spring Boot should store the returned job ID and poll when it needs current indexing status. A callback/event can replace polling later, but is not needed for the MVP.

## Failure semantics

Upload status and ingestion status are separate:

```text
upload COMPLETED + ingestion FAILED
```

is a valid state. It means the PDF remains safely stored and ingestion can be retried without another upload.

The worker temp file is not durable storage. It exists only for the current Celery task so validation and parsing can use a stable local file, then it is removed in cleanup.

## Code map

```text
app/api/internal/routes_ingestions.py  request validation/job creation/status
app/core/internal_auth.py              service API-key authentication
app/documents/models.py                source-oriented document/artifact models
app/documents/object_storage.py        bucket-aware S3 download
app/ingestion/pipeline.py               parsing/cleaning/chunking/embedding/indexing orchestration
app/jobs/ingestion_jobs.py              Celery task wrapper/retry handling
app/ingestion/repository.py             ingestion job persistence
alembic/versions/004_internal_service_schema.py
```

## Remaining work

- Implement retrieval/search baseline.
- Implement Qdrant delete/soft-delete path.
- Add immutable document versions.
- Add transactional outbox/reconciliation where needed.
- Upgrade static API key to mTLS or short-lived service identity for production.
