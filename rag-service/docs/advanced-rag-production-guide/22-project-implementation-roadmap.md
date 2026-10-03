# 22. Project Implementation Roadmap

Roadmap này biến toàn bộ guide thành kế hoạch triển khai thực tế cho project `professional-rag-platform`.

Mục tiêu không phải làm tất cả cùng lúc. Mục tiêu là đi từng phase, mỗi phase có output chạy được, test được, học được.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty.

## Phase 1: Basic RAG

### Mục tiêu

Xây được luồng RAG cơ bản:

```text
Upload document
  ↓
Parse text
  ↓
Chunk
  ↓
Embed
  ↓
Store vector
  ↓
Query
  ↓
Retrieve top-k
  ↓
Generate answer with citation
```

### Task cụ thể

- Hoàn thiện file upload endpoint.
- Lưu raw file bằng `documents/storage.py`.
- Parse PDF/DOCX/Markdown.
- Clean text cơ bản.
- Chunk bằng `RecursiveChunker`.
- Implement embedding provider.
- Implement Qdrant vector store.
- Implement vector retriever.
- Implement Gemini/OpenAI LLM client.
- Build prompt có context và citation.
- Chat endpoint trả answer.

### Output cần có

- Upload được HR policy sample.
- Query "chính sách nghỉ phép năm nay là gì?"
- Answer có citation chunk ID.

### Kiến thức cần học

- FastAPI file upload.
- SQLAlchemy async.
- Qdrant basics.
- Embedding model.
- Prompt builder.
- SSE streaming.

### Lỗi thường gặp

- Parse text rỗng.
- Dimension mismatch Qdrant.
- Không có metadata chunk.
- Prompt không ép context-only.

### Checklist hoàn thành

- Health endpoint OK.
- Upload endpoint OK.
- Ingestion manual/basic OK.
- Qdrant upsert/search OK.
- Chat endpoint OK.
- Answer có citation.

## Phase 2: Better Retrieval

### Mục tiêu

Cải thiện retrieval quality.

### Task cụ thể

- Metadata filtering.
- Permission filter cơ bản.
- PostgreSQL full-text/BM25 keyword retriever.
- Hybrid search bằng RRF.
- Query rewriting đơn giản.
- Reranker placeholder hoặc local reranker.
- Context compression/token budget.

### Output cần có

- Query error code exact retrieve đúng runbook.
- Query tiếng Việt retrieve docs tiếng Anh tốt hơn.
- Hybrid retrieval tốt hơn vector-only trên eval set nhỏ.

### Kiến thức cần học

- BM25/full-text search.
- Hybrid retrieval.
- RRF.
- Recall vs precision.
- Reranking.

### Lỗi thường gặp

- Filter quá hẹp gây empty retrieval.
- BM25 index stale.
- Hybrid merge duplicate chunks.
- Reranker latency cao.

### Checklist hoàn thành

- Vector retriever chạy.
- Keyword retriever chạy.
- Hybrid retriever chạy.
- Retrieval logs có scores/ranks.
- Golden dataset nhỏ đo Recall@k.

## Phase 3: Production Ingestion

### Mục tiêu

Tách ingestion thành async job production-oriented.

### Task cụ thể

- Tạo models `DocumentVersion`, `IngestionJob`, `EmbeddingJob`.
- Upload trả `job_id`.
- Celery worker xử lý ingestion.
- Job status endpoint.
- Retry/backoff.
- DLQ concept.
- Document versioning.
- Incremental update.
- Dedup bằng content hash/chunk hash.

### Output cần có

- Upload file không chờ xử lý xong.
- Worker ingest async.
- Xem job status.
- Upload cùng file không tạo duplicate chunks.

### Kiến thức cần học

- Celery.
- Redis queue.
- Idempotency.
- Document versioning.
- Retry/DLQ.

### Lỗi thường gặp

- Worker retry tạo duplicate vectors.
- Job stuck PROCESSING.
- Out-of-order update.
- Partial ingestion inconsistent.

### Checklist hoàn thành

- Job statuses đầy đủ.
- Idempotency key.
- Retry max count.
- DLQ table/log.
- Cleanup job cho orphan chunks/vectors.

## Phase 4: Evaluation

### Mục tiêu

Không còn đo quality bằng cảm giác.

### Task cụ thể

- Tạo golden dataset.
- Implement Recall@k, Precision@k, MRR, nDCG.
- Implement citation accuracy.
- Add faithfulness judge.
- Build `run_eval.py` CLI.
- Save eval reports.
- Compare baseline.

### Output cần có

- Chạy eval trên 20-50 questions.
- Biết retrieval/generation score.
- Có report trước/sau khi đổi chunking/retrieval.

### Kiến thức cần học

- RAG evaluation.
- Golden dataset.
- LLM-as-judge.
- Regression testing.

### Lỗi thường gặp

- Dataset quá dễ.
- Expected source không versioned.
- LLM judge bias.
- Không phân loại failure.

### Checklist hoàn thành

- Eval dataset versioned.
- Metrics report.
- Failure categories.
- CI eval subset.

## Phase 5: Security

### Mục tiêu

Ngăn data leakage.

### Task cụ thể

- Tenant/workspace/user model hoàn chỉnh.
- Document-level ACL.
- Chunk-level permission metadata.
- Permission filter trong retrieval.
- Prompt injection defense.
- Audit log.
- PII masking policy.

### Output cần có

- User không có quyền không retrieve private chunk.
- Prompt injection document không override system prompt.
- Audit log ghi chunk access.

### Kiến thức cần học

- RBAC/ABAC.
- Multi-tenant design.
- Vector payload filters.
- Prompt injection.
- Secure caching.

### Lỗi thường gặp

- Retrieve rồi mới filter.
- Context expansion quên permission.
- Cache thiếu permission hash.
- Full prompt logs leak data.

### Checklist hoàn thành

- Security tests pass.
- Permission filters enforced server-side.
- Prompt injection tests.
- Cache permission-aware.
- Audit logs.

## Phase 6: Observability

### Mục tiêu

Debug được từng request RAG.

### Task cụ thể

- Structured logs.
- Request ID.
- Trace query/retrieval/rerank/generation.
- Token usage.
- Cost tracking.
- Feedback loop.
- Dashboards.

### Output cần có

- Từ request_id tìm được raw query, rewritten query, chunks, model, tokens, latency, cost.
- User feedback gắn với trace.

### Kiến thức cần học

- structlog.
- OpenTelemetry.
- Prometheus/Grafana.
- RAG trace schema.

### Lỗi thường gặp

- Log quá ít không debug được.
- Log quá nhiều leak data.
- Không có cost per request.
- Không có trace chunk IDs.

### Checklist hoàn thành

- Logs có request_id.
- Metrics latency per step.
- Token/cost tracking.
- Feedback stored.
- Empty retrieval rate monitored.

## Phase 7: Deployment

### Mục tiêu

Deploy được system ổn định.

### Task cụ thể

- Docker Compose dev ổn.
- Dockerfiles production-ready hơn.
- Alembic migration flow.
- Health/readiness checks.
- Backup/restore docs.
- CI/CD pipeline.
- Staging deploy.
- Smoke tests.
- Monitoring/alerts.

### Output cần có

- `docker compose up` chạy đủ API/worker/Postgres/Redis/Qdrant.
- Deploy staging.
- Smoke test RAG.
- Rollback plan.

### Kiến thức cần học

- Docker.
- Celery deployment.
- Database migrations.
- Backups.
- CI/CD.

### Lỗi thường gặp

- Worker env khác API.
- Migration không backward compatible.
- Qdrant không backup.
- Secrets trong `.env` bị commit.

### Checklist hoàn thành

- API/worker separate.
- Health/readiness.
- Backups.
- CI tests/eval.
- Staging before prod.
- Rollback documented.

## Suggested Timeline

Nếu học và làm nghiêm túc:

```text
Week 1: Phase 1 Basic RAG
Week 2: Phase 2 Better Retrieval
Week 3: Phase 3 Production Ingestion
Week 4: Phase 4 Evaluation
Week 5: Phase 5 Security + Phase 6 Observability
Week 6: Phase 7 Deployment + CI/CD
```

Đây là timeline học sâu, không phải hackathon.

## Tóm Tắt

Thứ tự tốt nhất:

```text
Build simple
  ↓
Measure
  ↓
Improve retrieval
  ↓
Make ingestion reliable
  ↓
Secure
  ↓
Observe
  ↓
Deploy
```

Đừng tối ưu advanced patterns trước khi có basic RAG + evaluation. Evaluation là la bàn.
