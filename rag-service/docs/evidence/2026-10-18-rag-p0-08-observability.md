# RAG-P0-08 — Observability evidence

Môi trường: local; branch `codex/rag-v1-production-readiness`.

## Đã thêm

- Prometheus client khóa ở `0.26.0` trong Poetry lock.
- Private `/metrics`, HTTP count/latency theo route template và stage histograms.
- Retrieval empty/non-empty, answer outcomes và provider quota/error counters.
- Ingestion status/count/duration snapshot từ PostgreSQL, dùng được giữa các
  Celery process và sau restart; snapshot lỗi không làm mất metric API.
- Spring MDC trace ID được gửi sang RAG qua `X-Request-ID`.
- Safe outcome logs có ebook/mode/count/duration và request context.
- Monorepo monitoring profile scrape Spring + RAG + Qdrant.

## Runtime xác nhận

`GET http://127.0.0.1:8000/metrics`: HTTP 200.

Trước upload fixture bổ sung:

```text
library_rag_ingestion_metrics_up 1.0
library_rag_ingestion_jobs{status="INDEXED"} 3.0
library_rag_ingestion_job_duration_seconds_mean{status="INDEXED"} 55.86476366666667
```

Đây là created-to-completed duration của **cả job**, gồm queue/retries; không
diễn giải nó là thời gian worker xử lý riêng.

Public semantic search qua Spring trả HTTP 200, 3 hits và response
`X-Trace-Id=rag-p0-08-20261018-runtime`. Log RAG của request đó có cùng
`request_id`, ebook 10, mode hybrid và completion outcome.

Sau request, scrape có series thật cho query embedding, dense search, BM25,
RRF và rerank; retrieval outcome hybrid/non-empty tăng.

Prometheus target API xác nhận `health=up`, `lastError` rỗng cho:

- `library-service`
- `rag-api`
- `rag-qdrant`
- `prometheus`

Production Compose không publish port RAG; test source kiểm tra điều này. Đây
là bằng chứng private configuration, chưa phải production monitoring rollout.

## Tests

Tests cover request ID, dynamic route labels, unknown paths, DB-unavailable
snapshot, persisted job aggregate, and Spring trace propagation. Full-suite
results nằm trong P0-09 evidence.

Kết luận: P0-08 local PASS. Runbook chi tiết ở `docs/rag-v1-operations.md`.
