# 15. Cost Optimization

Cost optimization trong RAG không phải là chọn model rẻ nhất. Mục tiêu là đạt quality đủ tốt với chi phí có thể dự đoán, kiểm soát và scale.

RAG cost đến từ nhiều nơi:

- embedding
- LLM prompt/completion tokens
- reranking
- vector database
- storage
- monitoring
- worker compute

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `.env` với `LLM_MODEL`, `LLM_MAX_TOKENS`, `EMBEDDING_MODEL`, `RETRIEVAL_TOP_K`, `RERANKER_TOP_K`
- `app/indexing/`
- `app/retrieval/`
- `app/generation/`
- `app/evaluation/`
- `app/jobs/`

## 1. Cost Trong RAG Đến Từ Đâu?

Một request chat có thể tốn:

```text
query rewrite LLM call
query embedding
vector search
BM25 search
reranker
LLM prompt tokens
LLM completion tokens
logs/traces
```

Một document ingestion có thể tốn:

```text
parse CPU/OCR
chunking
embedding all chunks
vector DB writes
storage
worker time
```

Nếu không đo theo request/job, chi phí sẽ tăng âm thầm.

## 2. Embedding Cost

Embedding cost thường đến từ ingestion nhiều hơn query.

Ví dụ:

```text
10,000 docs
average 40 chunks/doc
= 400,000 chunks

average 400 tokens/chunk
= 160,000,000 tokens embedded
```

### Cách giảm embedding cost

1. Deduplicate documents.
2. Deduplicate chunks.
3. Cache embeddings.
4. Skip unchanged chunks.
5. Batch embedding.
6. Chọn chunk size/overlap hợp lý.
7. Không re-index toàn bộ nếu chỉ một document đổi.
8. Dùng model nhỏ hơn nếu evaluation chấp nhận.

### Không nên giảm cost bằng cách

- chunk quá lớn làm retrieval kém
- bỏ metadata/heading khỏi embedding text
- dùng model không hỗ trợ tiếng Việt nếu corpus Việt-Anh

Quality giảm sẽ làm LLM hallucinate, cuối cùng tốn nhiều hơn.

## 3. LLM Token Cost

LLM cost phụ thuộc:

- prompt tokens
- completion tokens
- model
- số lần gọi

Prompt tokens gồm:

- system prompt
- conversation history
- retrieved context
- user query
- output instructions

### Cách giảm prompt tokens

- context compression
- rerank tốt để giảm final chunks
- deduplicate chunks
- giới hạn conversation history
- summarize conversation history
- bỏ metadata không cần thiết khỏi prompt
- dùng source IDs ngắn

### Cách giảm completion tokens

- set `LLM_MAX_TOKENS`
- prompt yêu cầu concise answer
- structured output vừa đủ
- không yêu cầu model giải thích quá dài nếu không cần

Project hiện tại:

```env
LLM_MAX_TOKENS=2048
```

Với Internal KB, nhiều câu trả lời có thể chỉ cần 400-800 tokens.

## 4. Reranking Cost

Reranking cost phụ thuộc:

- số candidates
- reranker type
- local vs hosted
- input length

### Chiến lược giảm cost

- chỉ rerank top 20-50 candidates
- dùng rerank cache
- không rerank query đơn giản
- skip rerank nếu vector/BM25 confidence rất cao
- dùng cheaper local reranker nếu phù hợp

### Adaptive reranking

```python
if top_score > 0.88 and score_gap > 0.12:
    skip_rerank = True
else:
    rerank_top_30()
```

## 5. Vector DB Cost

Vector DB cost gồm:

- storage vectors
- memory ANN index
- CPU search
- replication
- backups
- network

### Cách giảm

- giảm duplicate chunks
- overlap hợp lý
- dimension phù hợp
- soft delete + cleanup old vectors
- payload indexes đúng, không quá nhiều
- archive old document versions

Không nên:

- giảm dimension bằng model kém nếu recall tụt mạnh
- hard delete ngay làm mất audit/citation

## 6. Storage Cost

Storage gồm:

- raw files
- raw extracted text
- cleaned text
- chunks
- embeddings metadata
- vector DB snapshots
- logs/traces
- evaluation reports

### Cách giảm

- object storage lifecycle policy
- compress raw/cleaned JSON
- retention policy cho old versions
- delete temporary files
- snapshot retention 7/30/90 days

Nhưng không xóa quá sớm:

- audit cần source
- citation cũ cần document version
- eval/replay cần raw data

## 7. Monitoring Cost

Observability cũng tốn tiền:

- logs volume
- traces volume
- metrics cardinality
- prompt logs

### Cách giảm

- log structured IDs thay vì full content
- sample full traces
- avoid high-cardinality labels in Prometheus
- redact/truncate long fields
- set log retention

Không nên:

- tắt observability hoàn toàn
- log full prompt/context mọi request trong production

## 8. Batch Embedding

Batch embedding giảm overhead API.

```python
BATCH_SIZE = 64

for batch in batched(chunks, BATCH_SIZE):
    vectors = await embedding_provider.embed([c.embedding_text for c in batch])
```

### Trade-off

- batch lớn: throughput tốt, timeout risk
- batch nhỏ: ổn định hơn, nhiều requests

Nên tune bằng metrics:

- embedding latency
- provider errors
- rate limit
- tokens per batch

## 9. Deduplicate Chunks

Duplicate chunks làm tăng:

- embedding cost
- vector storage
- prompt tokens
- retrieval noise

Dedup levels:

- file hash
- cleaned text hash
- chunk hash
- embedding text hash

### Cẩn thận

Cross-document dedup có thể sai permission/citation. Nếu hai docs có cùng text nhưng permission khác nhau, không thể dùng chung vector payload một cách đơn giản.

## 10. Avoid Re-Embedding Unchanged Documents

Khi document update, chỉ embed chunks thay đổi.

Flow:

```text
parse new version
  ↓
chunk
  ↓
compare chunk_hash with previous version
  ↓
embed changed/new chunks only
  ↓
soft delete removed chunks
```

Nếu đổi embedding model, phải re-embed tất cả chunks cho model mới.

## 11. Smaller Embedding Model

Model nhỏ hơn:

- rẻ hơn
- nhanh hơn
- dimension thấp hơn
- storage ít hơn

Nhưng có thể:

- recall thấp hơn
- multilingual kém hơn
- domain terms kém hơn

Quyết định bằng evaluation:

```text
Model A cost thấp hơn 60%
Recall@5 giảm từ 0.87 xuống 0.85
=> có thể chấp nhận

Model B cost thấp hơn 80%
Recall@5 giảm từ 0.87 xuống 0.68
=> không chấp nhận
```

## 12. Use Rerank Only For Hard Queries

Không phải query nào cũng cần rerank.

Hard query signals:

- top vector scores sát nhau
- query dài/ambiguous
- multi-query used
- document type legal/technical
- query có comparison/time
- user role high-value workflow

Easy query:

- exact FAQ
- top score cao
- BM25 exact hit

Adaptive policy:

```python
if intent in {"faq_lookup"} and top_score > 0.9:
    skip_rerank()
else:
    rerank()
```

## 13. Use Cheaper Model For Query Rewriting

Query rewriting không luôn cần model mạnh nhất.

Options:

- small/cheap LLM
- rule-based rewrite
- glossary expansion
- no rewrite for exact query

Use strong model only for:

- ambiguous multi-turn
- complex comparison
- metadata extraction hard cases

## 14. Use Caching

Cache cost savers:

- embedding cache
- query rewrite cache
- retrieval cache
- rerank cache
- public FAQ answer cache

Cost metric:

```text
cost_saved = cache_hits * estimated_uncached_cost
```

Track:

- cache hit ratio
- cost saved
- stale reports

## 15. Limit Context Size

Large context is expensive and can reduce quality.

Controls:

- final top-k
- max context tokens
- compression
- dedup
- MMR
- source priority

Example config:

```env
RETRIEVAL_TOP_K=50
RERANKER_TOP_K=5
LLM_MAX_TOKENS=1024
```

## 16. Async Processing

Ingestion should be async to avoid expensive API request timeouts.

Cost benefits:

- batch work
- control concurrency
- schedule off-peak
- retry safely
- rate limit provider calls

Project hiện tại có Celery worker scaffold, phù hợp.

## 17. Cost Breakdown Example

Example request:

```text
User asks: "Service order xử lý payment như thế nào?"
```

Cost:

```json
{
  "query_rewrite": {
    "model": "cheap-llm",
    "tokens": 120,
    "cost_usd": 0.00002
  },
  "query_embedding": {
    "model": "embedding-small",
    "tokens": 18,
    "cost_usd": 0.000001
  },
  "reranking": {
    "provider": "local-bge",
    "candidates": 30,
    "cost_usd": 0.00005
  },
  "generation": {
    "model": "gemini-2.5-flash",
    "prompt_tokens": 1800,
    "completion_tokens": 350,
    "cost_usd": 0.0012
  },
  "total_cost_usd": 0.001271
}
```

Even if numbers are placeholders, the structure is what matters.

## 18. Cost Per Tenant/User

Enterprise systems need budget controls:

- daily tenant budget
- per-user rate limit
- per-workspace budget
- high-cost query alerts

Example:

```json
{
  "tenant_id": "company-alpha",
  "date": "2026-05-27",
  "requests": 12000,
  "total_cost_usd": 18.42,
  "avg_cost_per_request": 0.00153
}
```

## 19. Cost Observability

Metrics:

- `cost_per_request`
- `cost_per_tenant_daily`
- `tokens_per_request`
- `embedding_cost_total`
- `llm_cost_total`
- `rerank_cost_total`
- `cost_saved_by_cache`

Alerts:

- daily cost above budget
- cost per request p95 spike
- prompt tokens spike
- cache hit ratio drop

## 20. Common Cost Mistakes

| Mistake | Result |
|---|---|
| Re-embed all docs every sync | huge embedding bill |
| Overlap too high | duplicate embedding/storage cost |
| Rerank 100 candidates every query | latency/cost spike |
| Send 20 chunks to LLM | token cost and worse quality |
| Log full prompts forever | observability cost and privacy risk |
| Use strongest LLM for rewrite | unnecessary cost |
| No cache | repeated costs |
| No budget alerts | surprise bill |

## 21. Production Checklist

- Track token usage per request.
- Track cost per request.
- Track cost per tenant/user.
- Embedding cache enabled.
- Skip unchanged chunks.
- Batch embedding.
- Deduplicate chunks.
- Limit chunk overlap.
- Adaptive reranking.
- Cheaper model for rewrite/classification.
- Context token budget enforced.
- Cache hit ratio monitored.
- Cost alerts configured.
- Evaluation confirms cheaper changes do not hurt quality.
- Document update does not trigger full re-index unless necessary.

## 22. Tóm Tắt Chương

Cost optimization tốt là tối ưu có đo lường. Đừng giảm cost bằng cách làm RAG mù hơn. Hãy giảm:

- duplicate work
- unnecessary tokens
- unnecessary model calls
- unnecessary re-index
- unnecessary logs

Và luôn so sánh bằng evaluation:

```text
quality, latency, cost
```

Chương tiếp theo sẽ đi vào production deployment: Docker Compose, single VM, managed cloud, Kubernetes, health check, scaling và backup/restore.
