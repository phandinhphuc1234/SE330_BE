# RAG-P0-03 Ingestion Evidence — 2026-10-18

Branch: `codex/rag-v1-production-readiness`
Baseline commit: `0e974ae317c5383b1183de09025091feb291a3f2`

## Runtime health before ingestion

The local Spring + RAG stack was built from the current checkout and started
with persistent volumes intact.

Verified healthy/running services:

- Spring `library-service` — Docker healthy; actuator status `UP` on the
  configured local port `18090`.
- `rag-api` — Docker healthy; `/api/v1/health` returned `{"status":"ok"}`.
- `rag-worker` — running and connected to Redis.
- `rag-postgres`, `rag-redis`, `rag-qdrant`, `rag-seaweedfs` — healthy.
- `rag-migrate` and `rag-seaweedfs-init` — exited successfully with code `0`.

## Existing source selected for a real reindex

The persistent environment already contained the real six-page PDF used by the
golden dataset:

| Field | Value |
|---|---|
| Filename | `the-gift-of-the-magi.pdf` |
| Book ID | `3342` |
| Ebook ID | `10` |
| External document ID | `doc_ebook_10` |
| Private object key | `ebooks/3342/10/original.pdf` |
| Source size | `86822` bytes |

Before reindex, the source had six PostgreSQL chunks, six Qdrant points and nine
stored ingestion artifacts. The checksum was read from the artifact table and
used in the ingestion request without being copied into this evidence file.

## Force-reindex execution

The source was submitted through the authenticated internal ingestion API with
`forceReindex=true`. No PDF bytes were copied through the API; the worker read
the existing private source object from SeaweedFS.

Accepted response:

```json
{"documentId":"doc_ebook_10","ingestionJobId":3,"status":"QUEUED"}
```

Observed persisted stages:

1. `PROCESSING / parsing_pdf`
2. `PARSED / cleaning_text`
3. `EMBEDDING / embedding_chunks`
4. `INDEXING / upserting_qdrant`
5. `INDEXED / indexed`

Final worker result:

```text
document_id=1
ingestion_job_id=3
page_count=6
chunk_count=6
status=INDEXED
worker_duration_seconds=65.768
```

Persisted timestamps:

- Created: `2026-10-18 03:43:50.866071+00`
- Completed: `2026-10-18 03:45:01.73949+00`
- Error message: empty

## Post-ingestion verification

- PostgreSQL active chunk count: `6`.
- PostgreSQL artifact count: `9`.
- Qdrant collection status: `green`.
- Qdrant point count: `6`.
- Vector dimension: `3072`.
- Distance metric: `Cosine`.

The force reindex replaced/upserted the existing logical data without producing
duplicate chunks or vector points.

## Conclusion

`RAG-P0-03` passed. This is runtime ingestion evidence, not a mocked unit test.

## Additional public upload proof

To verify the backend upload boundary as well as internal reindex, the same
86,822-byte, six-page source was read through its protected reader URL and
uploaded through the admin API to a separate local fixture. Existing sources
were not replaced or deleted.

```text
POST /api/books -> HTTP 201, fixture bookId=3343
POST /api/books/3343/ebooks -> HTTP 201, ebookId=11
RAG documentId=2, jobId=4
```

The public management endpoint observed queued/awaiting_enqueue → queued →
processing → parsing → parsed/cleaning → chunked/persisting → embedding →
indexing → indexed. It ended at `INDEXED/indexed`.

Job timestamps: `2026-10-18 07:15:42.939628+00` to
`2026-10-18 07:16:50.792066+00` (67.852438 seconds including queue/status steps).
PostgreSQL has 6 chunks for document 2 and retains the original 6 for document 1.
An exact Qdrant count with document 2, ebook 11 and active=true also returned 6.
Fixture book/ebook remain local, identifiable by `RAG Readiness Fixture` title;
they were intentionally preserved after the test.
