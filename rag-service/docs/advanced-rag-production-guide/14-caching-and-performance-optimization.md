# 14. Caching And Performance Optimization

Caching trong RAG không đơn giản là lưu câu trả lời theo query text. Nếu cache sai, hệ thống có thể trả stale answer hoặc leak dữ liệu giữa users có quyền khác nhau.

Chương này giải thích cache cái gì, thiết kế cache key ra sao, TTL thế nào, invalidation khi document update và cách tối ưu performance từ ingestion đến query-time.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `compose.yml` với Redis
- `app/core/config.py` với `REDIS_URL`
- `app/jobs/` dùng Redis cho Celery
- `app/indexing/embedding_provider.py`
- `app/retrieval/`
- `app/generation/`

## 1. Vì Sao RAG Cần Caching?

RAG request có nhiều bước tốn thời gian và tiền:

```text
query processing
  ↓
query embedding
  ↓
vector search
  ↓
BM25 search
  ↓
reranking
  ↓
LLM generation
```

Cache giúp:

- giảm latency
- giảm API cost
- giảm load vector DB/LLM
- tăng throughput
- giảm queue backlog

Nhưng cache cũng tạo rủi ro:

- trả answer cũ sau khi document update
- trả answer của user có quyền cao cho user có quyền thấp
- cache prompt chứa PII
- khó debug nếu không log cache hit/miss

## 2. Cache Cái Gì?

Các tầng cache phổ biến:

| Cache | Lưu gì | Lợi ích | Rủi ro |
|---|---|---|---|
| Query cache | processed query/rewrite | giảm LLM rewrite | stale với context/history |
| Embedding cache | text -> vector | giảm embedding cost | sai nếu model/version đổi |
| Retrieval cache | query+filter -> chunk IDs | giảm vector/BM25 latency | stale khi docs/permission đổi |
| Rerank cache | query+candidates -> order | giảm rerank cost | stale khi chunks đổi |
| LLM response cache | final answer | giảm LLM cost | nguy cơ stale/leak cao |
| Semantic cache | similar query -> answer | UX/cost tốt | khó kiểm soát correctness |
| Prompt cache | system/context prefix | provider-specific optimization | phụ thuộc model/provider |

Production nên bắt đầu cache ở tầng ít rủi ro:

1. embedding cache
2. retrieval cache có permission-aware key
3. rerank cache
4. response cache rất cẩn thận

## 3. Query Cache

Query cache lưu kết quả query processing:

```json
{
  "rewritten_query": "annual leave policy changes between 2025 and 2026",
  "intent": "policy_comparison",
  "filters": {
    "document_type": "hr_policy",
    "department": "HR"
  }
}
```

### Cache key

```text
rag:query:{tenant_id}:{query_hash}:{history_hash}:{query_processor_version}
```

### Khi nào dùng?

- rewrite bằng LLM tốn cost
- query lặp lại nhiều
- chatbot có nhiều câu hỏi phổ biến

### TTL

Ngắn đến vừa:

```text
5 minutes - 1 hour
```

Vì conversation context và business context có thể thay đổi.

## 4. Embedding Cache

Embedding cache là cache quan trọng và ít rủi ro nhất.

### Cache key

```text
rag:embedding:{provider}:{model}:{embedding_version}:{text_hash}
```

Ví dụ:

```text
rag:embedding:openai:text-embedding-3-small:v1:sha256_abc123
```

### Vì sao key phải có model/version?

Vì cùng text nhưng model khác tạo vector khác.

Sai:

```text
rag:embedding:{text_hash}
```

Đúng:

```text
rag:embedding:{provider}:{model}:{version}:{text_hash}
```

### TTL

Có thể dài:

```text
30 days - no expiry
```

Nếu key có version, cache cũ không gây sai khi đổi model. Có thể cleanup sau.

### Pseudo-code

```python
async def embed_with_cache(texts: list[str]):
    vectors = []
    missing = []

    for text in texts:
        key = embedding_cache_key(text)
        cached = await redis.get(key)
        if cached:
            vectors.append(decode_vector(cached))
        else:
            missing.append(text)

    if missing:
        new_vectors = await embedding_provider.embed(missing)
        for text, vector in zip(missing, new_vectors):
            await redis.set(
                embedding_cache_key(text),
                encode_vector(vector),
                ex=60 * 60 * 24 * 30,
            )

    return vectors
```

## 5. Retrieval Cache

Retrieval cache lưu kết quả retrieval:

```json
{
  "chunk_ids": ["hr-policy-2026-c12", "hr-faq-c04"],
  "scores": [0.84, 0.79],
  "created_at": "..."
}
```

### Cache key

```text
rag:retrieval:{tenant}:{permission_hash}:{query_hash}:{filter_hash}:{index_version}:{retrieval_version}
```

Ví dụ:

```text
rag:retrieval:company-alpha:perm_92ab:q_13ff:f_88dc:index_v4:hybrid_v2
```

### Vì sao cần `permission_hash`?

Hai users hỏi cùng query nhưng quyền khác nhau có thể thấy context khác nhau.

Nếu cache key thiếu permission:

```text
User HR asks salary policy -> cache answer/chunks
User Engineering asks same query -> gets HR chunks
```

Đây là data leak.

### Invalidation

Invalidate khi:

- document update
- chunking strategy đổi
- embedding model/index version đổi
- permission/ACL đổi
- retrieval pipeline version đổi

## 6. Rerank Cache

Rerank cache lưu thứ tự candidates sau rerank.

### Cache key

```text
rag:rerank:{reranker}:{version}:{query_hash}:{candidate_ids_hash}:{permission_hash}
```

### Khi nào hữu ích?

- reranker hosted tốn tiền
- cross-encoder chậm
- query phổ biến

### TTL

Vừa:

```text
10 minutes - 24 hours
```

Tùy tốc độ update docs.

## 7. LLM Response Cache

Response cache lưu câu trả lời cuối cùng.

### Rủi ro cao nhất

Response cache có thể sai nếu:

- document update
- user permission khác
- prompt/model đổi
- context khác nhưng query giống
- user hỏi trong conversation khác

### Cache key

```text
rag:answer:{tenant}:{permission_hash}:{query_hash}:{context_ids_hash}:{prompt_version}:{model}:{index_version}
```

### Khi nào dùng?

- FAQ public
- câu hỏi lặp lại nhiều
- docs ít thay đổi
- answer không phụ thuộc conversation

### Khi không nên dùng?

- private HR/salary docs
- answer phụ thuộc user
- docs update thường xuyên
- query chứa PII
- câu hỏi multi-turn phức tạp

## 8. Semantic Cache

Semantic cache trả cached answer cho query gần nghĩa, không cần giống text.

Ví dụ:

```text
"Nhân viên chính thức có bao nhiêu ngày phép?"
"Full-time employee annual leave entitlement?"
```

### Cách làm

- embed query
- search query cache vectors
- nếu similarity cao, return cached answer

### Rủi ro

- hai query gần nghĩa nhưng khác constraint
- permission mismatch
- stale answer
- khó explain

Production nên dùng semantic cache chỉ cho:

- public FAQ
- low-risk docs
- high confidence threshold
- permission-aware cache partition

## 9. Prompt Cache

Prompt cache có hai nghĩa:

1. Cache prompt đã render trong app.
2. Provider-level prompt caching nếu LLM provider hỗ trợ.

### App-level prompt cache

Ít dùng hơn vì prompt phụ thuộc context.

### Provider prompt cache

Một số provider tối ưu prefix giống nhau:

- system prompt
- long static instruction
- stable context

### Cẩn thận

Không dựa vào prompt cache để giải quyết correctness. Nó chỉ là optimization.

## 10. CDN Cho Static Docs

Nếu UI cần mở file nguồn hoặc preview docs:

- PDF
- images
- attachments

Có thể dùng CDN/object storage signed URL.

Nhưng:

- private docs cần signed URL ngắn hạn
- không public bucket nhầm
- audit access nếu cần

## 11. Cache TTL

TTL phụ thuộc dữ liệu.

| Cache | TTL gợi ý |
|---|---|
| Embedding cache | dài, 30d hoặc không expiry theo version |
| Query rewrite cache | 5m-1h |
| Retrieval cache | 5m-6h |
| Rerank cache | 10m-24h |
| Public FAQ answer cache | 1h-24h |
| Private answer cache | rất cẩn thận hoặc disable |

TTL không thay thế invalidation. TTL chỉ là safety net.

## 12. Cache Invalidation Khi Document Update

Khi document update:

```text
document version changes
  ↓
chunks change
  ↓
embeddings change
  ↓
vector index version changes
  ↓
retrieval/rerank/answer caches may be stale
```

### Strategy tốt

Thay vì xóa hàng loạt key khó khăn, đưa version vào cache key:

```text
index_version=v5
```

Khi document update, increment:

```text
active_index_version = v6
```

Các cache cũ tự miss vì key khác.

### Document-level invalidation

Nếu cache lưu dependency:

```json
{
  "cache_key": "rag:answer:...",
  "depends_on_documents": ["hr-policy-2026"]
}
```

có thể xóa cache liên quan document.

Phức tạp hơn nhưng tiết kiệm cache.

## 13. Cache Có Gây Stale Answer Không?

Có.

Ví dụ:

User hỏi:

```text
Chính sách nghỉ phép năm nay là gì?
```

Cache trả answer cũ:

```text
12 ngày
```

Trong khi HR policy mới:

```text
14 ngày
```

### Cách giảm

- include document/index version in key
- invalidate on ingestion completed
- short TTL for policy docs
- show source version in answer
- evaluation catches stale source

## 14. Performance Optimization Theo Step

### Query processing

- cache rewrite
- rule-based extraction trước LLM
- chỉ dùng HyDE/multi-query khi cần

### Embedding

- cache embedding
- batch embedding
- use fast model for query embedding
- keep provider connection warm

### Vector search

- payload indexes
- filter fields tối ưu
- tune top-k
- avoid overfetch

### BM25

- prebuild full-text index
- index important fields
- keep text normalized

### Reranking

- limit candidates
- batch rerank
- cache rerank
- fallback if timeout

### Generation

- context compression
- reduce duplicate chunks
- prompt version stable
- stream response
- use cheaper model for simple query

## 15. Redis Key Design

Bắt buộc có ví dụ:

```text
rag:query_hash:{hash}
rag:embedding:{model}:{text_hash}
rag:retrieval:{tenant}:{query_hash}:{filter_hash}
```

Production version:

```text
rag:query:{tenant}:{query_hash}:{history_hash}:{query_processor_version}
rag:embedding:{provider}:{model}:{embedding_version}:{text_hash}
rag:retrieval:{tenant}:{permission_hash}:{query_hash}:{filter_hash}:{index_version}:{retrieval_version}
rag:rerank:{reranker}:{version}:{query_hash}:{candidate_ids_hash}:{permission_hash}
rag:answer:{tenant}:{permission_hash}:{query_hash}:{context_hash}:{prompt_version}:{model}:{index_version}
```

## 16. Cache Observability

Metrics:

- `cache_hit_ratio`
- `embedding_cache_hit_ratio`
- `retrieval_cache_hit_ratio`
- `rerank_cache_hit_ratio`
- `answer_cache_hit_ratio`
- `cache_latency_ms`
- `cache_evictions_total`
- `cache_stale_reports_total`

Log example:

```json
{
  "event": "retrieval_cache_hit",
  "request_id": "req_123",
  "cache_key_hash": "sha256:abc",
  "index_version": "v4",
  "permission_hash": "perm_92ab"
}
```

Không log full cache key nếu chứa sensitive info.

## 17. Performance SLOs

Ví dụ SLO:

```text
Chat answer first token p95 < 3s
Total response p95 < 10s
Retrieval p95 < 300ms
Reranking p95 < 800ms
Empty retrieval rate < 5%
Error rate < 1%
```

SLO giúp quyết định:

- có cần cache không
- có cần giảm top-k không
- reranker có quá chậm không
- model có cần đổi không

## 18. Common Mistakes

| Mistake | Hậu quả |
|---|---|
| Cache answer chỉ theo query text | leak dữ liệu giữa users |
| Không version cache key | stale answer sau document update |
| Cache full prompt trong logs | leak private context |
| TTL quá dài cho policy docs | stale policy answer |
| Không monitor cache hit | không biết cache có hiệu quả không |
| Cache before permission filter | security risk |
| Không invalidate khi ACL đổi | user giữ access cũ |

## 19. Production Checklist

- Embedding cache có model/version/text_hash.
- Retrieval cache có tenant/permission/filter/index version.
- Answer cache disabled hoặc cực kỳ cẩn thận cho private docs.
- Cache key không chứa raw PII.
- Cache value không chứa secrets.
- TTL được định nghĩa theo cache type.
- Document update làm cache miss qua version hoặc invalidation.
- ACL change invalidate permission-sensitive caches.
- Cache hit ratio được monitor.
- Stale answer reports được track.
- Redis memory/eviction được monitor.
- Có fallback khi Redis unavailable.

## 20. Tóm Tắt Chương

Caching giúp RAG nhanh và rẻ hơn, nhưng nếu thiết kế sai sẽ làm hệ thống nguy hiểm hơn. Nguyên tắc cốt lõi:

```text
Cache key phải chứa đủ identity của data, permission, model, prompt và index version.
```

Hãy bắt đầu với embedding cache, sau đó retrieval/rerank cache. Response cache chỉ dùng khi bạn kiểm soát rất rõ permission và invalidation.

Chương tiếp theo sẽ đi vào cost optimization: embedding cost, LLM token cost, reranking cost, vector DB/storage/monitoring cost và cách giảm chi phí mà không phá quality.
