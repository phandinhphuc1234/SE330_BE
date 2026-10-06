# Professional RAG Platform

Internal RAG service for the Spring Boot library backend.

Retrieval supports scoped PostgreSQL BM25 + weighted RRF, deterministic rewriting
and reranking, bounded neighbouring context and optional ebook-local graph retrieval.
A versioned 20-case evaluation dataset and report CLI are included. See the
[implementation/evaluation guide](docs/retrieval-evaluation-implementation.md)
for commands, score semantics, graph rebuild and validation limits.

Chunking keeps the reviewed v1 default and adds opt-in chapter-aware v2 with
cross-page source mapping. See the [v2 guide](docs/chunking/chapter-aware-v2.md)
and [four-step plan](docs/chunking/chunking-baseline-and-boundary-plan.md).
No existing chunks are automatically reindexed when selecting a strategy.
Context expansion validates current database anchors, source revisions and
chapter/section boundaries while keeping each neighbour independently citable.
See the [context expansion guide](docs/chunking/chapter-aware-context-expansion.md)
for conservative legacy/page-local fallback and validation limits.
The [real-PDF comparison](docs/chunking/real-book-chunking-benchmark.md) adds
source-anchored BM25 and opt-in real-Gemini dense/hybrid benchmarks in memory.
The development corpus exposed chapter detection gaps; the
[heading regression fix](docs/chunking/heading-detection-regression-fix.md)
repairs v2 punctuation, Appendix and front-matter boundaries without changing
the reviewed v1 baseline. Keep v1 until retrieval regressions are addressed and
held-out/Vietnamese cases are reviewed.

[Ranking diagnostics and query holdout](docs/chunking/retrieval-diagnostics-and-vi-query-holdout.md)
explain per-term BM25 and cached cosine rankings. A separate 20-question
Vietnamese query set uses new evidence on the existing English PDFs, with
baseline/checksum/source-overlap guards. This is not Vietnamese-book or
unseen-document validation; runtime retrieval policies are unchanged.
The [hybrid ranking trace](docs/chunking/hybrid-ranking-trace.md) pinpoints RRF/
reranker/cutoff failures using cached real vectors, checks response invariance
and records numeric component scores without exposing query/source text.
The [fixed policy comparison](docs/chunking/hybrid-policy-comparison.md) compares
fusion/reranker alternatives against the replayed baseline and exposes per-book
tradeoffs. No candidate passed all gates; runtime weights/defaults are unchanged.
The [lexical-agreement diagnosis](docs/chunking/lexical-agreement-diagnostics.md)
explains those tradeoffs across 80 cases using actual BM25 contributions and
zero-score evidence. Counterexamples keep adaptive ranking as a future explicit
experiment, not an automatic title-token filter or production change.
The [adaptive experiment v2](docs/chunking/adaptive-hybrid-experiment-v2.md)
tests four preregistered lexical-strength formulas offline across 560 cases.
Macro gains coexist with Magi ranking regressions; all alternatives fail the
no-regression gates, so production defaults remain unchanged.
The [Vietnamese-source evaluation](docs/chunking/vietnamese-source-evaluation-v1.md)
adds an official translated Vietnamese report with 20 frozen questions and
40 BM25 cases. Section failures are reported honestly.
The [owner review](docs/chunking/vietnamese-source-review-v1.md)
was approved on 2026-10-06 after first scoring and recorded separately without
rewriting historical reports. A separately authorized
[bounded Gemini baseline](docs/chunking/vietnamese-source-embedding-baseline-v1.md)
retained 96 vectors from the failed first attempt, then completed all 120 missing
inputs with separately approved token-aware pacing: 216 real cached vectors,
120 BM25/Dense/Hybrid cases and zero errors. Hybrid Recall@3 is 92.5% for v1
versus 90% for v2. Section/ranking regressions still keep HOLD v1; no automatic
retry, default change, experimental policy scoring or runtime promotion.
The [Vietnamese baseline diagnosis](docs/chunking/vietnamese-baseline-diagnostics-v1.md)
traces 40 case-version pairs and exactly replays all 120 baseline cases without
provider calls. All 23 anchors have a complete single-chunk source; missing
headings (v2 detects 2/7) and observed ranking/cutoff losses remain separate
problems. Next: an isolated section-detection experiment, not weight tuning.

## Service boundary

```text
Spring Boot
  = users, roles, permissions, books, ebooks, loans, payments, upload

RAG service
  = ingestion jobs, parsing, chunking, embedding, vector indexing, retrieval
  = grounded answer generation for the Secure AI Ebook Reader
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

Search indexed evidence inside a trusted library scope:

```http
POST /internal/retrieval/search
```

Generate a bounded evidence-grounded answer for one authorized ebook:

```http
POST /internal/answers
```

`/internal/retrieval/search` remains the lower-level evidence API. The answer
endpoint adds threshold-based abstention, provider-backed structured output and
citation allow-list validation. Spring Boot must authorize the member before
calling either endpoint.

RAG-local auth, users, workspaces, chat sessions, feedback, and direct web upload endpoints have been removed. Spring Boot is the application source of truth.

## Infrastructure

| Service | Role |
| --- | --- |
| FastAPI | Internal ingestion, retrieval and grounded-answer API. |
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
- PDF parsing, cleaning, chunking and artifact persistence.
- Gemini embedding execution and Qdrant collection/upsert.
- Terminal ingestion state `INDEXED` after vector upsert succeeds.
- Scoped internal retrieval through `POST /internal/retrieval/search`.
- Evidence responses with scores, chunk text, citation and page metadata.
- Spring member-facing semantic search protected by JWT, reading session,
  active ebook loan and exact ebook scope.
- Grounded Ask This Book generation with bounded evidence, prompt-injection
  guardrails, citation validation and deterministic abstention.
- Spring member-facing `POST /api/ebooks/{bookId}/reader/ask` with scope
  re-validation before returning `ApiResponse`.

Not complete yet:

- Version table and safe concurrent ebook replacement.
- Reader page-jump integration, evaluation set and live provider end-to-end validation.
- mTLS or short-lived service JWT authentication.

The current successful ingestion terminal state is `INDEXED`. Do not describe
`/internal/retrieval/search` as a chatbot: it remains the lower-level evidence
retrieval API used by `/internal/answers` and semantic search.

## Documentation

- [Secure AI Ebook Reader product roadmap](../docs/secure-ai-ebook-reader-roadmap.md)
- [Documentation map](docs/README.md)
- [Internal API](docs/reference/api.md)
- [Spring Boot integration contract](docs/integration/springboot-rag-ingestion-contract.md)
- [SeaweedFS/PostgreSQL flow](docs/ingestion/seaweedfs-postgres-upload-flow.md)
- [PDF ingestion](docs/ingestion/pdf-data-ingestion.md)
- [Chunking strategy](docs/chunking/library-rag-chunking-strategy.md)
- [Deployment](docs/platform/deployment.md)
