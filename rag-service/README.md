# Professional RAG Platform

Internal RAG service for the Spring Boot library backend.

## Service boundary

```text
Spring Boot
  = users, roles, permissions, books, ebooks, loans, payments, upload

RAG service
  = ingestion jobs, parsing, chunking, embedding, vector indexing, retrieval
```

Spring Boot uploads ebook PDFs once to SeaweedFS and calls RAG with bucket/object-key metadata. RAG workers download the object directly; Spring Boot does not send the PDF a second time.

```text
Staff → Spring Boot → SeaweedFS
                    → POST /internal/ingestions
                    → Redis → Celery worker
                            → SeaweedFS GET
                            → parse/chunk/embed
                            → Qdrant
```

The full integration contract is [docs/integration/springboot-rag-ingestion-contract.md](docs/integration/springboot-rag-ingestion-contract.md).

## Internal API

All internal endpoints require:

```http
X-RAG-API-Key: <RAG_INTERNAL_API_KEY>
```

Create ingestion:

```http
POST /internal/ingestions
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

Read status:

```http
GET /internal/ingestions/{jobId}
```

RAG-local auth, users, workspaces, chat sessions, feedback, and direct web upload endpoints have been removed. Spring Boot is the application source of truth.

## Infrastructure

| Service | Role |
| --- | --- |
| FastAPI | Internal ingestion/retrieval API. |
| Celery worker | Background ingestion workload. |
| Celery beat | Optional periodic cleanup/reconciliation runner (tasks are placeholders today). |
| PostgreSQL 16 | RAG document/artifact/job metadata. |
| Redis 7 | Celery broker/result backend and cache. |
| Qdrant | Vector index and chunk payload. |
| SeaweedFS | Shared S3-compatible object storage. |

Networks:

```text
rag-internal-net      = private RAG dependencies
library-platform-net = shared Spring Boot/RAG network
```

Shared aliases:

```text
rag-api
rag-seaweedfs
rag-qdrant
rag-postgres-exporter
rag-redis-exporter
```

Buckets:

```text
library-private/ebooks/{bookId}/{ebookId}/original.pdf
library-temp/imports/{importId}/manifest.csv
library-temp/imports/{importId}/...
rag-artifacts/documents/{documentId}/parsed_text.txt
rag-artifacts/documents/{documentId}/cleaned_text.txt
rag-artifacts/documents/{documentId}/chunks.jsonl
rag-artifacts/documents/{documentId}/failed_parse.log
```

## Configuration

Copy `.env.example` to `.env` and replace secrets. Important values:

```dotenv
RAG_INTERNAL_API_KEY=replace-with-a-long-random-service-secret
POSTGRES_DB=rag_db
POSTGRES_USER=user
POSTGRES_PASSWORD=password
REDIS_URL=redis://redis:6379/0
QDRANT_URL=http://qdrant:6333
OBJECT_STORAGE_ENDPOINT=http://seaweedfs:8333
LIBRARY_EBOOK_BUCKET=library-private
LIBRARY_TEMP_BUCKET=library-temp
RAG_ARTIFACT_BUCKET=rag-artifacts
# Temporary compatibility alias; not a fourth bucket.
RAG_SOURCE_BUCKET=library-private
```

Spring Boot must use the same API key and send it as `X-RAG-API-Key`.

## Run

Create the shared network once:

```bash
docker network create library-platform-net
```

Start infrastructure and initialize buckets:

```bash
docker compose up -d postgres redis qdrant seaweedfs
docker compose up seaweedfs-init
```

Run migrations:

```powershell
docker compose --project-directory . -f infra/compose/standalone/compose.yml -f infra/compose/standalone/compose.override.yml --profile tools run --rm migrate
```

Start application processes:

```powershell
docker compose --project-directory . -f infra/compose/standalone/compose.yml -f infra/compose/standalone/compose.override.yml up -d api worker
```

`beat` is behind the `scheduled-jobs` profile because every scheduled task is
currently a placeholder. Enable it only after implementing real cleanup jobs:

```powershell
docker compose --project-directory . -f infra/compose/standalone/compose.yml -f infra/compose/standalone/compose.override.yml --profile scheduled-jobs up -d beat
```

Optional exporters:

```powershell
docker compose --project-directory . -f infra/compose/standalone/compose.yml -f infra/compose/standalone/compose.override.yml -f infra/compose/standalone/compose.observability.yml up -d
```

The standalone override is passed explicitly because it is archived outside the
default Compose filename location. Prefer the repository-level
`..\scripts\dev-up.ps1` command for integrated library development.

## Verify

```powershell
docker compose --project-directory . -f infra/compose/standalone/compose.yml -f infra/compose/standalone/compose.override.yml ps
docker network inspect library-platform-net
curl http://localhost:8000/api/v1/health
```

Local authenticated request:

```bash
curl -X POST http://localhost:8000/internal/ingestions \
  -H "Content-Type: application/json" \
  -H "X-RAG-API-Key: $RAG_INTERNAL_API_KEY" \
  -d @ingestion-request.json
```

## Current implementation status

Implemented:

- Internal API-key authentication.
- Library ebook ingestion request and status endpoints.
- Bucket/key/checksum validation.
- Basic idempotency for repeated ebook/checksum requests.
- PostgreSQL job tracking and Redis/Celery enqueue.
- Worker download from the bucket recorded on the artifact.
- PDF parsing, cleaning, chunking and chunk persistence.

Not complete yet:

- Real embedding provider execution.
- Qdrant collection creation/upsert in the ingestion pipeline.
- Version table and safe concurrent ebook replacement.
- Persistence of parsed/cleaned/chunk/log files into `rag-artifacts`.
- mTLS or short-lived service JWT authentication.

Do not report ingestion as `COMPLETED/INDEXED` until embedding and Qdrant upsert are implemented. The current successful terminal state is `CHUNKED`.

## Documentation

- [Documentation map](docs/README.md)
- [Internal API](docs/reference/api.md)
- [Spring Boot integration contract](docs/integration/springboot-rag-ingestion-contract.md)
- [SeaweedFS/PostgreSQL flow](docs/ingestion/seaweedfs-postgres-upload-flow.md)
- [PDF ingestion](docs/ingestion/pdf-data-ingestion.md)
- [Chunking strategy](docs/chunking/library-rag-chunking-strategy.md)
- [Deployment](docs/platform/deployment.md)
