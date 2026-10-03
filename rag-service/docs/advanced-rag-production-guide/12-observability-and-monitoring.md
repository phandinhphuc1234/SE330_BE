# 12. Observability And Monitoring

Observability là khả năng hiểu hệ thống đang làm gì từ bên ngoài thông qua logs, metrics và traces. Với RAG, observability còn cần prompt logs, retrieval logs, token usage, cost tracking, citation tracking và user feedback.

Một RAG system không observable sẽ rất khó debug. User nói "câu trả lời sai", bạn cần biết sai ở query rewrite, retrieval, reranking, prompt, model hay document version.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `app/core/logger.py`
- `app/core/middlewares/request_id.py`
- `app/core/middlewares/timing.py`
- `app/core/middlewares/rate_limiter.py`
- `app/core/exceptions.py`
- `app/feedback/`
- `app/evaluation/`
- `app/jobs/`

## 1. Observability Cho RAG Là Gì?

Observability cho RAG là khả năng trả lời các câu hỏi:

- User hỏi gì?
- Query được rewrite thành gì?
- Hệ thống retrieve chunks nào?
- Scores/ranks là bao nhiêu?
- Reranker chọn chunks nào?
- Prompt gửi LLM có gì?
- Model nào được dùng?
- Token/cost/latency bao nhiêu?
- Answer cite nguồn nào?
- User feedback ra sao?
- Lỗi xảy ra ở bước nào?

Không có observability, bạn chỉ thấy:

```text
Answer sai.
```

Có observability, bạn thấy:

```text
Query rewrite sai năm 2025 thành 2026.
Filter lấy current policy nên không retrieve policy năm ngoái.
```

## 2. Logs, Metrics, Traces

### 2.1. Logs

Logs là events rời rạc:

```json
{
  "event": "retrieval_completed",
  "request_id": "req_123",
  "results_count": 20,
  "latency_ms": 82
}
```

Logs tốt để debug chi tiết.

### 2.2. Metrics

Metrics là số liệu tổng hợp theo thời gian:

```text
retrieval_latency_ms p95 = 120ms
empty_retrieval_rate = 4.2%
cost_per_request_avg = $0.0013
```

Metrics tốt để alert và dashboard.

### 2.3. Traces

Traces cho thấy một request đi qua nhiều spans:

```text
chat_request
  ├── query_processing
  ├── vector_retrieval
  ├── bm25_retrieval
  ├── reranking
  ├── prompt_building
  ├── llm_generation
  └── save_chat_message
```

Traces tốt để tìm bottleneck và debug distributed system.

## 3. Request ID

Mọi request nên có `request_id`.

Project hiện tại có:

- `RequestIdMiddleware`
- header `X-Request-ID`
- structlog contextvars

Flow:

```text
Client request
  ↓
RequestIdMiddleware
  ↓
request.state.request_id
  ↓
Logs/traces/errors include request_id
  ↓
Response header X-Request-ID
```

### Vì sao quan trọng?

Khi user báo lỗi, bạn hỏi:

```text
Bạn gửi mình request_id trong response header hoặc UI debug panel.
```

Rồi tìm tất cả logs theo `request_id`.

## 4. Trace Flow Cho Một RAG Request

Trace fields bắt buộc theo yêu cầu:

```json
{
  "request_id": "req_123",
  "user_id": "u_42",
  "tenant_id": "company-alpha",
  "query": "Service order xử lý payment như thế nào?",
  "rewritten_query": "order service payment processing flow",
  "retrieved_chunk_ids": ["order-doc-c12", "payment-runbook-c03"],
  "reranked_chunk_ids": ["payment-runbook-c03", "order-doc-c12"],
  "llm_model": "gemini-2.5-flash",
  "prompt_tokens": 1800,
  "completion_tokens": 320,
  "latency": {
    "query_processing_ms": 120,
    "retrieval_ms": 90,
    "reranking_ms": 240,
    "llm_ms": 2100,
    "total_ms": 2630
  },
  "cost": {
    "llm_usd": 0.0012,
    "embedding_usd": 0.00001,
    "total_usd": 0.00121
  },
  "answer_quality_feedback": {
    "rating": 1,
    "comment": "helpful"
  }
}
```

## 5. Logging Từng Step

### 5.1. Query processing log

```json
{
  "event": "query_processed",
  "request_id": "req_123",
  "user_id": "u_42",
  "tenant_id": "company-alpha",
  "raw_query": "chính sách nghỉ phép năm ngoái thay đổi gì?",
  "rewritten_query": "annual leave policy changes between 2025 and 2026",
  "intent": "policy_comparison",
  "filters": {
    "document_type": "hr_policy",
    "department": "HR"
  },
  "latency_ms": 138
}
```

### 5.2. Retrieval log

```json
{
  "event": "retrieval_completed",
  "request_id": "req_123",
  "retrieval_strategy": "hybrid",
  "dense_top_k": 50,
  "bm25_top_k": 50,
  "dense_results_count": 50,
  "bm25_results_count": 42,
  "final_candidates_count": 20,
  "top_chunk_ids": ["hr-policy-2026-c12", "hr-policy-2025-c10"],
  "latency_ms": 92
}
```

### 5.3. Reranking log

```json
{
  "event": "reranking_completed",
  "request_id": "req_123",
  "reranker": "bge-reranker",
  "reranker_version": "v1",
  "candidates_count": 20,
  "selected_count": 5,
  "reranked_chunk_ids": ["hr-policy-2026-c12", "hr-policy-2025-c10"],
  "latency_ms": 310
}
```

### 5.4. Generation log

```json
{
  "event": "generation_completed",
  "request_id": "req_123",
  "llm_provider": "gemini",
  "llm_model": "gemini-2.5-flash",
  "prompt_version": "internal-kb-rag-v1",
  "context_chunk_ids": ["hr-policy-2026-c12", "hr-policy-2025-c10"],
  "prompt_tokens": 2100,
  "completion_tokens": 420,
  "latency_ms": 2600,
  "citations_count": 2
}
```

## 6. Prompt Logs

Prompt logs rất hữu ích nhưng nhạy cảm.

### Có nên log full prompt không?

Tùy môi trường.

Development:

- có thể log prompt để debug
- nhưng cẩn thận API keys/secrets

Production:

- không nên log full prompt mặc định
- nên log prompt hash, prompt version, context IDs, token count
- nếu cần full prompt, bật sampling và redact PII

### Recommended production prompt log

```json
{
  "event": "prompt_built",
  "request_id": "req_123",
  "prompt_version": "internal-kb-rag-v1",
  "prompt_hash": "sha256:abc123",
  "context_chunk_ids": ["chunk_1", "chunk_2"],
  "prompt_tokens": 2100
}
```

## 7. Retrieval Logs

Retrieval logs nên đủ để debug:

- raw query
- rewritten query
- filters
- dense results
- keyword results
- hybrid rank
- scores
- selected context

Nhưng production có thể log IDs/scores thay vì full content.

Example:

```json
{
  "chunk_id": "payment-runbook-c03",
  "dense_rank": 12,
  "dense_score": 0.71,
  "bm25_rank": 1,
  "bm25_score": 18.4,
  "hybrid_rank": 2,
  "rrf_score": 0.032
}
```

## 8. Generation Logs

Generation logs cần:

- provider
- model
- prompt version
- context IDs
- token usage
- latency
- finish reason
- errors

Không nên log:

- API keys
- secrets trong prompt
- private user data nếu policy không cho phép

## 9. Token Usage

Token usage cần track theo request.

Fields:

```json
{
  "query_embedding_tokens": 24,
  "context_tokens": 1600,
  "prompt_tokens": 2100,
  "completion_tokens": 420,
  "total_tokens": 2520
}
```

### Vì sao cần?

- cost tracking
- detect prompt bloat
- tune chunking/context compression
- budget per tenant/user

Nếu prompt_tokens tăng đột biến, có thể do:

- context compression fail
- top-k quá cao
- conversation history quá dài
- duplicate chunks

## 10. Cost Tracking

Cost tracking cần theo:

- request
- user
- tenant
- model
- feature
- time window

Example:

```json
{
  "request_id": "req_123",
  "tenant_id": "company-alpha",
  "llm_model": "gemini-2.5-flash",
  "embedding_model": "text-embedding-3-small",
  "llm_cost_usd": 0.0012,
  "embedding_cost_usd": 0.00001,
  "rerank_cost_usd": 0.0002,
  "total_cost_usd": 0.00141
}
```

### Budget alerts

Examples:

- tenant cost daily > $20
- user requests per minute too high
- cost per query p95 > threshold
- prompt token average increased 50%

## 11. Latency Tracking

Metrics bắt buộc theo yêu cầu:

- `retrieval_latency_ms`
- `embedding_latency_ms`
- `reranking_latency_ms`
- `llm_latency_ms`
- `total_latency_ms`

Nên track p50/p95/p99.

Example dashboard:

```text
total_latency_p95 = 3.4s
llm_latency_p95 = 2.8s
retrieval_latency_p95 = 180ms
reranking_latency_p95 = 520ms
```

Nếu total latency cao, trace breakdown cho biết bottleneck ở đâu.

## 12. Error Tracking

Common errors:

- embedding provider timeout
- LLM provider timeout
- vector DB unavailable
- reranker timeout
- invalid JSON output
- citation validation failed
- permission denied
- no context found

Error log:

```json
{
  "event": "rag_request_failed",
  "request_id": "req_123",
  "error_type": "VectorStoreUnavailable",
  "step": "retrieval",
  "message": "Qdrant request timed out",
  "retryable": true
}
```

## 13. User Feedback

Feedback là observability từ người dùng.

Project hiện tại có `app/feedback/`.

Feedback nên gắn với:

- answer message_id
- request_id
- query
- retrieved chunk ids
- model
- prompt version
- rating
- comment

Example:

```json
{
  "message_id": "msg_456",
  "request_id": "req_123",
  "rating": -1,
  "comment": "Dùng policy cũ",
  "retrieved_chunk_ids": ["hr-policy-2024-c12"],
  "expected_issue": "stale_document"
}
```

Feedback loop:

```text
User feedback
  ↓
Failure triage
  ↓
Add to golden dataset
  ↓
Improve retrieval/prompt
  ↓
Run regression eval
```

## 14. Metrics Danh Sách Bắt Buộc

Theo yêu cầu, cần có:

```text
retrieval_latency_ms
embedding_latency_ms
reranking_latency_ms
llm_latency_ms
total_latency_ms
tokens_per_request
cost_per_request
cache_hit_ratio
empty_retrieval_rate
hallucination_report_rate
```

Mở rộng thêm:

```text
query_rewrite_latency_ms
vector_results_count
bm25_results_count
reranker_candidates_count
context_tokens
prompt_tokens
completion_tokens
citation_validation_failure_rate
no_answer_rate
feedback_negative_rate
ingestion_job_failure_rate
queue_backlog
```

## 15. Cache Hit Ratio

Cache observability cần cho:

- embedding cache
- query cache
- retrieval cache
- rerank cache
- LLM response cache

Metrics:

```text
embedding_cache_hit_ratio
retrieval_cache_hit_ratio
rerank_cache_hit_ratio
semantic_cache_hit_ratio
```

Nếu cache hit thấp:

- cache key quá chi tiết
- TTL quá ngắn
- workload query đa dạng
- invalidation quá aggressive

Nếu cache hit cao nhưng answer stale:

- invalidation thiếu khi document update

## 16. Empty Retrieval Rate

Empty retrieval rate là tỷ lệ query không lấy được context.

```text
empty_retrieval_rate = empty_retrieval_requests / total_requests
```

Nếu tăng đột biến:

- vector DB lỗi
- metadata filter quá hẹp
- permission filter bug
- embedding model mismatch
- ingestion chưa index docs
- query rewrite sai

Dashboard nên phân loại:

- empty due to permission
- empty due to no documents
- empty after filters
- empty after score threshold

## 17. Hallucination Report Rate

Hallucination report rate có thể đến từ:

- user feedback
- LLM judge
- citation validation

```text
hallucination_report_rate = hallucination_reports / answered_requests
```

Nếu tăng:

- retrieval noisy
- prompt too loose
- model changed
- context compression dropped facts
- outdated docs

## 18. OpenTelemetry

OpenTelemetry là chuẩn để instrument traces, metrics, logs.

### RAG spans

```text
rag.chat_request
  rag.query_processing
  rag.query_embedding
  rag.vector_search
  rag.keyword_search
  rag.hybrid_merge
  rag.reranking
  rag.context_compression
  rag.prompt_build
  rag.llm_stream
  rag.citation_validation
  rag.save_message
```

### Span attributes

```json
{
  "rag.tenant_id": "company-alpha",
  "rag.retrieval.strategy": "hybrid",
  "rag.llm.model": "gemini-2.5-flash",
  "rag.prompt.version": "internal-kb-rag-v1",
  "rag.context.chunks_count": 5,
  "rag.prompt.tokens": 2100
}
```

Do not put full sensitive prompt in span attributes.

## 19. Prometheus + Grafana

Prometheus scrape metrics, Grafana hiển thị dashboard.

### Dashboard panels

- total requests per minute
- total latency p50/p95/p99
- LLM latency
- retrieval latency
- reranking latency
- token usage
- cost per tenant
- error rate
- empty retrieval rate
- feedback negative rate
- ingestion queue backlog

### Alerts

- API error rate > 5%
- vector DB unavailable
- LLM timeout rate > 2%
- empty retrieval rate > baseline + 10%
- cost/day > budget
- queue backlog age > 30 min
- p95 latency > 10s

## 20. Loki / ELK

Loki/ELK dùng để query logs.

Useful queries:

```text
request_id="req_123"
event="retrieval_completed" tenant_id="company-alpha"
error_type="CitationValidationError"
retrieval_strategy="hybrid" empty_results=true
```

Structured JSON logs giúp query dễ hơn plain text.

Project hiện tại dùng `structlog`, phù hợp hướng này.

## 21. LangSmith / Phoenix / Arize

Các tool này giúp trace LLM/RAG chuyên biệt.

### LangSmith

Mạnh nếu dùng LangChain:

- trace chain
- dataset eval
- prompt versioning

### Phoenix / Arize

Mạnh cho observability ML/RAG:

- embeddings visualization
- retrieval traces
- drift
- eval

### Khi nào cần?

- nhiều experiments
- cần UI trace prompt/context/answer
- team cần review failures
- muốn nhanh hơn tự build dashboard

Có thể bắt đầu custom logs/traces, sau đó tích hợp tool chuyên dụng.

## 22. PII Và Logging Policy

Observability không được gây data leakage.

### Không nên log mặc định

- full private documents
- API keys
- secrets
- salary data
- PII trong support tickets
- full prompt nếu chứa private context

### Nên log

- IDs
- hashes
- counts
- scores
- versions
- latency
- cost

Nếu cần log text:

- sample small percentage
- redact PII
- restrict access
- encrypt logs
- define retention

## 23. Debug Playbooks

### 23.1. Answer wrong

Check:

1. request_id
2. rewritten_query
3. filters
4. retrieved chunks
5. reranked chunks
6. final prompt context IDs
7. answer citations
8. model/prompt version

### 23.2. No answer but docs exist

Check:

- ingestion status
- vector count
- metadata filters
- permission filters
- score threshold
- query rewrite
- embedding version

### 23.3. Latency high

Check spans:

- query rewrite slow?
- vector DB slow?
- reranker slow?
- LLM slow?
- streaming blocked?

### 23.4. Cost high

Check:

- prompt tokens
- context chunks count
- duplicate context
- model choice
- cache hit ratio
- multi-query/HyDE usage

## 24. Observability Schema Gợi Ý

### `rag_request_traces`

```sql
CREATE TABLE rag_request_traces (
    id UUID PRIMARY KEY,
    request_id TEXT NOT NULL,
    user_id UUID,
    tenant_id UUID,
    raw_query TEXT,
    rewritten_query TEXT,
    intent TEXT,
    filters JSONB,
    retrieved_chunk_ids JSONB,
    reranked_chunk_ids JSONB,
    final_context_chunk_ids JSONB,
    llm_provider TEXT,
    llm_model TEXT,
    prompt_version TEXT,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_cost_usd NUMERIC,
    latency_ms JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Nếu query/context nhạy cảm, thay `raw_query` bằng hash hoặc redact.

## 25. Implementation Guidance Cho Project Hiện Tại

### 25.1. `logger.py`

Project đã có `structlog`. Tiếp theo:

- gọi `configure_logging()` trong startup
- bind `request_id`, `user_id`, `tenant_id`
- JSON logs trong production

### 25.2. Middlewares

Đã có:

- `RequestIdMiddleware`
- `TimingMiddleware`

Nên thêm:

- error logging middleware
- metrics middleware
- OpenTelemetry instrumentation

### 25.3. Retrieval/generation trace

Thêm trace object trong request lifecycle:

```python
trace = RagTrace(request_id=request.state.request_id)
trace.raw_query = query
trace.retrieved_chunk_ids = [...]
```

Cuối request save trace.

### 25.4. Feedback link

Khi save chat message, lưu:

- request_id
- trace_id
- model
- context IDs

Feedback sau này join được với trace.

## 26. Production Checklist

- Mọi request có `request_id`.
- Logs là structured JSON trong production.
- Logs có `user_id`, `tenant_id` khi có.
- Query processing logs raw/rewritten/filter.
- Retrieval logs chunk IDs/scores/ranks.
- Reranking logs candidates and scores.
- Generation logs model/prompt/tokens/cost.
- Full prompt logging disabled by default in production.
- PII redaction policy exists.
- Metrics include latency per step.
- Metrics include token/cost per request.
- Metrics include cache hit ratio.
- Metrics include empty retrieval rate.
- Metrics include hallucination/user report rate.
- Traces instrument each RAG step.
- Dashboard exists for API, retrieval, LLM, ingestion.
- Alerts exist for error/latency/cost/backlog.
- Feedback stored and tied to request trace.

## 27. Tóm Tắt Chương

RAG observability cần nhiều hơn API logs. Bạn cần nhìn thấy toàn bộ pipeline:

```text
query -> rewrite -> retrieve -> rerank -> prompt -> LLM -> citation -> feedback
```

Các tín hiệu quan trọng:

- request_id
- chunk IDs
- scores/ranks
- model/prompt versions
- latency per step
- token/cost
- feedback
- error type

Chương tiếp theo sẽ đi vào security và permission control: document-level ACL, chunk-level filtering, tenant isolation, prompt injection defense và data leakage prevention.
