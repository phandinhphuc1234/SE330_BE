# 19. Failure Handling And Reliability

RAG production không chỉ cần trả lời đúng khi mọi thứ hoạt động. Nó phải degrade gracefully khi embedding API fail, LLM timeout, vector DB unavailable, document parsing lỗi, queue backlog hoặc job bị retry nhiều lần.

Reliability nghĩa là hệ thống có thể:

- phát hiện lỗi
- retry đúng lỗi
- không retry vô hạn
- không tạo duplicate
- không leak dữ liệu
- trả fallback hợp lý
- phục hồi sau incident

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `app/core/exceptions.py`
- `app/jobs/ingestion_jobs.py`
- `app/jobs/cleanup_jobs.py`
- `app/jobs/celery_app.py`
- `app/retrieval/`
- `app/generation/`
- `app/indexing/`
- `app/ingestion/`

## 1. Failure Trong RAG Có Gì Khác?

RAG có nhiều external dependency:

- LLM API
- embedding API
- vector DB
- PostgreSQL
- Redis
- object storage
- parser/OCR
- reranker

Một request có thể fail một phần:

```text
Vector search ok
BM25 ok
Reranker timeout
LLM ok
```

Hệ thống không nhất thiết phải fail toàn bộ. Có thể fallback:

```text
use hybrid rank without reranker
```

## 2. Embedding API Fail

### Impact

Ingestion:

- document không index được
- job stuck/retry

Query:

- không embed query được
- không retrieve vector path được

### Detection

- timeout
- 429 rate limit
- 5xx provider
- invalid response

### Recovery

- retry with backoff
- use cached embedding if available
- fallback to keyword retrieval for query
- move ingestion job to DLQ after max retries

### Prevention

- batch size tuning
- rate limiter
- provider timeout
- cache
- circuit breaker

## 3. LLM API Timeout

### Impact

- user không nhận answer
- streaming bị đứt
- cost có thể vẫn phát sinh một phần

### Recovery

- retry once for idempotent non-streaming calls
- for streaming, return graceful error
- fallback to cheaper/backup model
- return retrieved sources without generated answer if needed

Fallback message:

```text
Mình đã tìm được tài liệu liên quan nhưng hiện model trả lời đang timeout. Bạn có thể thử lại sau hoặc mở các nguồn sau.
```

### Prevention

- request timeout
- model health monitoring
- context token limit
- circuit breaker
- provider fallback

## 4. Vector DB Unavailable

### Impact

- semantic retrieval fail
- RAG answer may not be possible

### Recovery

- fallback to BM25 keyword retrieval if available
- return no-answer with retry suggestion
- trip circuit breaker
- alert

### Prevention

- health checks
- replication/managed service
- snapshots
- connection pooling
- timeout

## 5. Document Parsing Fail

### Causes

- corrupted PDF
- unsupported file
- password-protected document
- OCR fail
- parser bug

### Recovery

- mark job failed with clear error
- do not retry fatal format errors many times
- send to DLQ/manual review
- allow user to upload another format

### Prevention

- validate file type/size
- parser timeout
- fixture tests
- OCR fallback

## 6. Queue Backlog

Queue backlog means jobs wait too long.

### Causes

- too many uploads
- workers down
- embedding provider slow/rate limited
- large documents
- retry storm

### Detection

- queue length
- oldest job age
- worker heartbeat
- job duration p95

### Recovery

- scale workers
- pause low-priority jobs
- increase provider quota
- reduce batch size if timeout
- throttle uploads

### Prevention

- rate limit ingestion
- priority queues
- backpressure
- dashboards/alerts

## 7. Partial Ingestion

Partial ingestion occurs when some steps succeed and later step fails.

Example:

```text
chunks saved in PostgreSQL
vectors upserted to Qdrant
job fails before marking INDEXED
```

### Risks

- duplicate vectors on retry
- stale chunks active
- inconsistent DB/vector state

### Recovery

- stable vector IDs
- idempotent upsert
- job resume by step
- cleanup orphan chunks/vectors
- reconciliation job

## 8. Duplicate Jobs

Duplicate jobs happen when:

- user uploads same file twice
- connector sends duplicate event
- Celery retries after worker crash
- API request retried by client

### Prevention

- idempotency key
- unique constraint
- content hash
- stable job ID for source event

### Recovery

- detect duplicate and return existing job
- upsert chunks/vectors
- no duplicate inserts

## 9. Out-Of-Order Document Update

Example:

```text
v3 update starts
v4 update starts
v4 finishes first
v3 finishes later and marks itself current
```

This is bad.

### Prevention

- version number
- optimistic locking
- only latest version can become active
- compare source updated_at

Pseudo-rule:

```python
if version.number < document.current_version_number:
    mark_obsolete(version)
    return
```

## 10. Retry Storm

Retry storm occurs when many failed jobs retry at once.

### Causes

- provider outage
- vector DB down
- bad deploy
- no backoff/jitter

### Impact

- overload dependency
- cost spike
- queue backlog
- cascading failure

### Prevention

- exponential backoff
- jitter
- max retries
- circuit breaker
- pause queue on global outage

## 11. Rate Limit

Providers may return 429.

### Recovery

- respect retry-after header
- backoff
- reduce concurrency
- queue requests
- cache

### Prevention

- per-provider rate limiter
- worker concurrency controls
- budget-aware scheduling

## 12. Fallback Response

Fallback response should be honest.

Bad:

```text
The policy is probably 14 days.
```

Good:

```text
Mình chưa thể tạo câu trả lời đầy đủ vì dịch vụ truy xuất tài liệu đang gặp lỗi. Bạn có thể thử lại sau.
```

If retrieval succeeds but LLM fails:

```text
Mình tìm thấy các nguồn liên quan nhưng chưa tạo được câu trả lời. Các nguồn liên quan là...
```

## 13. Circuit Breaker

Circuit breaker stops calling a failing dependency for a period.

States:

```text
CLOSED -> calls normally
OPEN -> fail fast/fallback
HALF_OPEN -> test recovery
```

Use for:

- LLM provider
- embedding provider
- reranker provider
- vector DB

### Why?

Avoid making outage worse with repeated calls.

## 14. Dead Letter Queue

DLQ stores jobs that failed permanently.

DLQ record:

```json
{
  "job_id": "job_123",
  "document_id": "doc_456",
  "failed_step": "EMBEDDING",
  "error_type": "RateLimitError",
  "retry_count": 5,
  "last_error": "provider rate limit"
}
```

Operations:

- inspect
- retry manually
- mark ignored
- cancel
- export report

## 15. Failure Table

| Failure | Impact | Detection | Recovery strategy | Prevention |
|---|---|---|---|---|
| Embedding API fail | ingestion/query vector path fail | timeout/429/5xx | retry, cache, fallback BM25, DLQ | rate limit, backoff, cache |
| LLM API timeout | no answer/partial stream | timeout metric | fallback model, graceful error | token limit, circuit breaker |
| Vector DB unavailable | retrieval fail | health check/search error | fallback keyword/no-answer | replication, timeout, monitoring |
| Document parsing fail | document not indexed | parser exception | DLQ/manual review | validation, parser tests |
| Queue backlog | ingestion delayed | queue length/oldest job age | scale workers/throttle uploads | backpressure, rate limits |
| Partial ingestion | inconsistent state | job stuck/reconciliation | idempotent retry/cleanup | stable IDs, transactions |
| Duplicate jobs | duplicate chunks/vectors | unique key/hash | return existing/upsert | idempotency key |
| Out-of-order update | old doc becomes active | version audit | mark obsolete/reindex | version locking |
| Retry storm | cascading failure | retry spike | circuit breaker/pause queue | backoff+jitter |
| Rate limit | slow/fail requests | 429 count | backoff/retry-after | concurrency limits |

## 16. Reliability Patterns

### Timeouts

Every external call needs timeout:

- embedding
- LLM
- vector DB
- Redis
- PostgreSQL
- object storage

### Retries

Retry only retryable errors.

Use:

- max retries
- exponential backoff
- jitter

### Bulkheads

Separate resources:

- API workers
- ingestion workers
- OCR workers
- eval jobs

So ingestion spike does not kill chat API.

### Backpressure

If queue too long:

- reject/slow uploads
- pause connectors
- lower worker concurrency
- notify admin

## 17. Graceful Degradation

Examples:

| Failure | Degradation |
|---|---|
| Reranker down | use hybrid rank |
| Vector DB down | use BM25 only if available |
| LLM down | return sources/no-answer |
| Embedding query fail | use keyword retrieval |
| Cache down | proceed uncached |
| Observability down | continue serving but alert |

## 18. Incident Response

When incident occurs:

1. Identify scope.
2. Check dashboards.
3. Check recent deploy.
4. Check provider status.
5. Mitigate: rollback, circuit breaker, scale, pause jobs.
6. Communicate.
7. Postmortem.
8. Add tests/alerts.

RAG-specific questions:

- Which tenants affected?
- Which model/provider?
- Which vector collection?
- Did answers leak data?
- Did stale docs get served?
- Did eval catch it before deploy?

## 19. Reliability Metrics

- API error rate
- LLM timeout rate
- embedding error rate
- vector DB error rate
- queue backlog
- DLQ count
- retry count
- job success rate
- ingestion duration p95
- chat latency p95/p99
- fallback response rate
- circuit breaker open count

## 20. Implementation Guidance Cho Project Hiện Tại

### 20.1. Exceptions

`app/core/exceptions.py` already has:

- `IngestionError`
- `LLMError`
- etc.

Extend with:

- `VectorStoreError`
- `EmbeddingProviderError`
- `RetryableJobError`
- `NonRetryableJobError`

### 20.2. Celery retries

Use Celery retry/backoff for retryable errors.

### 20.3. Cleanup jobs

`app/jobs/cleanup_jobs.py` can grow into:

- cleanup orphan chunks
- cleanup orphan vectors
- expire stuck jobs
- check vector store health

### 20.4. Health endpoint

Add readiness endpoint checking:

- DB
- Redis
- Qdrant

### 20.5. Fallback in retrieval

If vector search fails:

```text
try BM25 only
or no-answer with clear message
```

## 21. Production Checklist

- External calls have timeouts.
- Retry only retryable errors.
- Exponential backoff with jitter.
- Max retries defined.
- DLQ exists for failed ingestion jobs.
- Jobs are idempotent.
- Stable vector IDs.
- Out-of-order versions handled.
- Circuit breaker for providers.
- Queue backlog alerts.
- Fallback responses defined.
- Cleanup/reconciliation jobs exist.
- Health/readiness checks exist.
- Incident runbooks exist.
- Postmortem process exists.
- Reliability metrics dashboard exists.

## 22. Tóm Tắt Chương

RAG reliability là thiết kế cho failure ngay từ đầu. Các lỗi chắc chắn sẽ xảy ra:

- provider timeout
- vector DB down
- parser fail
- rate limit
- worker crash
- stale index

Hệ thống tốt không phải là hệ thống không bao giờ lỗi, mà là hệ thống lỗi có kiểm soát:

```text
detect -> isolate -> retry/fallback -> recover -> learn
```

Part tiếp theo sẽ là phần cuối về advanced RAG patterns, agentic RAG, roadmap triển khai và checklist/interview notes.
