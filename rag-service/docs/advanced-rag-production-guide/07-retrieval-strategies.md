# 07. Retrieval Strategies

Retrieval là trái tim của RAG. Nếu retrieval lấy sai context, LLM sẽ trả lời sai hoặc bịa dù model rất mạnh.

Chương này đi sâu vào các chiến lược retrieval từ cơ bản đến production-grade: similarity search, metadata filtering, BM25, hybrid search, multi-query, parent retriever, contextual retrieval, graph retrieval, time-aware retrieval và permission-aware retrieval.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `app/retrieval/vector_retriever.py`
- `app/retrieval/keyword_retriever.py`
- `app/retrieval/hybrid_retriever.py`
- `app/retrieval/query_rewriter.py`
- `app/retrieval/reranker.py`
- `app/retrieval/context_expander.py`
- `app/retrieval/retrieval_pipeline.py`
- `app/indexing/qdrant_store.py`
- `app/documents/repository.py`

## 1. Retrieval Là Gì?

Retrieval là bước tìm các đoạn tài liệu liên quan đến câu hỏi của user.

Flow:

```text
User asks question
  ↓
Process query
  ↓
Retrieve candidate chunks
  ↓
Rerank/compress
  ↓
Send context to LLM
```

Ví dụ user hỏi:

```text
Service order xử lý payment như thế nào?
```

Retrieval cần tìm đúng chunks như:

- `technical-docs/order-service/payment-flow`
- `runbook/payment-timeout`
- `architecture/order-service-state-machine`

Không nên retrieve nhầm:

- HR salary policy
- frontend checkout UI notes
- unrelated payment meeting note cũ

## 2. Vì Sao Retrieval Quan Trọng?

RAG thường fail ở retrieval trước khi fail ở generation.

Nếu LLM không nhìn thấy đúng context, nó có thể:

- trả lời chung chung
- dùng kiến thức ngoài
- hallucinate
- cite sai nguồn
- nói không biết dù tài liệu có tồn tại

Retrieval tốt giúp:

- tăng groundedness
- giảm hallucination
- giảm token cost
- tăng citation accuracy
- tăng user trust

## 3. Retrieval Nằm Ở Đâu Trong Pipeline?

```text
Online query pipeline:

Authenticate user
  ↓
Normalize/rewrite query
  ↓
Build metadata + permission filters
  ↓
Retrieval
  ↓
Reranking
  ↓
Context compression
  ↓
Prompt builder
  ↓
LLM
```

Trong project hiện tại:

```text
chat/service.py
  ↓
retrieval/retrieval_pipeline.py
  ↓
query_rewriter.py
  ↓
vector_retriever.py
keyword_retriever.py
hybrid_retriever.py
  ↓
reranker.py
context_expander.py
```

## 4. Naive Similarity Search

### Nó là gì?

Naive similarity search là cách RAG cơ bản nhất:

```text
embed query
  ↓
vector search top-k
  ↓
send top-k chunks to LLM
```

Pseudo-code:

```python
async def retrieve(query: str, top_k: int = 5):
    query_vector = await embedding_provider.embed_one(query)
    results = await vector_store.search(
        query_vector=query_vector,
        top_k=top_k,
    )
    return results
```

### Ưu điểm

- đơn giản
- dễ implement
- tốt cho demo
- semantic search tự nhiên

### Nhược điểm

- miss exact keywords
- không xử lý permission tốt nếu quên filter
- không hiểu time/version nếu không metadata
- dễ retrieve chunk gần nghĩa nhưng sai context
- top-k nhỏ có thể bỏ sót answer

### Khi nào đủ?

- corpus nhỏ
- document sạch
- user hỏi đơn giản
- không có permission phức tạp
- prototype

Production thường cần nhiều hơn naive similarity search.

## 5. Top-k Retrieval

Top-k là số lượng chunks lấy từ retriever.

Ví dụ:

```text
top_k = 5
```

nghĩa là lấy 5 chunks có score cao nhất.

### Top-k chọn bao nhiêu?

Không có số đúng cho mọi hệ thống.

Gợi ý:

| Stage | Top-k thường dùng |
|---|---:|
| Vector candidate retrieval | 30-100 |
| BM25 candidate retrieval | 30-100 |
| After hybrid merge | 20-50 |
| After reranking | 3-10 |
| Final context to LLM | 3-8 |

Basic RAG hay làm:

```text
vector top 5 -> LLM
```

Advanced RAG thường làm:

```text
vector top 50
BM25 top 50
merge
rerank top 20
final top 5
```

### Trade-off top-k

| Top-k | Ưu điểm | Nhược điểm |
|---|---|---|
| Thấp | Latency thấp, ít token | Dễ miss answer |
| Cao | Recall tốt hơn | Nhiều noise, cần rerank |

### Debug top-k

Nếu answer không tìm thấy:

- tăng candidate top-k
- xem expected chunk nằm rank bao nhiêu
- nếu rank 30 nhưng final top 5 không có, cần rerank
- nếu không nằm top 100, lỗi embedding/chunking/query/filter

## 6. Recall Vs Precision

### Recall

Recall trả lời:

> Trong các chunks đúng, hệ thống lấy được bao nhiêu?

Ví dụ expected chunk nằm trong top 10 → recall@10 tốt.

### Precision

Precision trả lời:

> Trong các chunks hệ thống lấy, bao nhiêu chunks thật sự liên quan?

### Trade-off

- Retrieval candidate stage cần recall cao.
- Final context stage cần precision cao.

Vì vậy pipeline production thường:

```text
Retrieve nhiều để tăng recall
  ↓
Rerank/filter/compress để tăng precision
```

## 7. Similarity Threshold

Threshold là ngưỡng score tối thiểu.

Ví dụ:

```text
score >= 0.72
```

### Vì sao cần?

Nếu vector DB luôn trả top-k, nó vẫn trả chunks dù không có chunk nào thật sự liên quan.

Ví dụ user hỏi:

```text
Công ty có chính sách nuôi cá trong văn phòng không?
```

Nếu corpus không có nội dung này, vector DB vẫn trả top 5 gần nhất. LLM có thể bịa nếu prompt không kiểm soát.

### Trade-off

| Threshold | Ưu điểm | Nhược điểm |
|---|---|---|
| Cao | Giảm noise | Dễ no-answer |
| Thấp | Tăng recall | Dễ đưa context sai |

Threshold phải benchmark theo model/corpus, không copy từ blog.

## 8. Metadata Filtering

Metadata filtering là giới hạn search theo metadata.

Ví dụ:

```json
{
  "tenant_id": "company-alpha",
  "workspace_id": "hr",
  "document_type": "hr_policy",
  "year": 2026,
  "active": true
}
```

### Vì sao cần?

- tìm đúng workspace
- tìm đúng document type
- tìm đúng version/year
- tránh tài liệu archived
- hỗ trợ permission

### Filter trước hay sau vector search?

Filter phải nằm trong vector DB query hoặc trước retrieval.

Sai:

```text
search all vectors top 50
  ↓
filter workspace/year in Python
```

Đúng:

```text
search vectors where workspace=hr and year=2026
```

### Lỗi thường gặp

- Metadata không được attach vào chunk.
- Type mismatch.
- Filter quá hẹp.
- Filter từ LLM extraction sai.
- Missing `active=true`.

## 9. Permission-Aware Retrieval

Permission-aware retrieval là retrieval có xét quyền user.

Ví dụ:

- User A thuộc HR: xem HR private docs.
- User B thuộc Engineering: xem engineering docs và public docs.
- Admin: xem tất cả.

### Vì sao rất quan trọng?

RAG có thể leak dữ liệu nếu retrieve tài liệu user không có quyền.

Không được làm:

```text
Retrieve private HR chunks
  ↓
Filter after retrieval
```

Phải làm:

```text
Build permission filter
  ↓
Retrieve only authorized chunks
```

### Permission filter example

```json
{
  "tenant_id": "company-alpha",
  "active": true,
  "workspace_id": {
    "$in": ["engineering", "public"]
  },
  "visibility": {
    "$in": ["public", "department"]
  },
  "allowed_roles": {
    "$contains_any": ["backend_engineer", "employee"]
  }
}
```

### Production checklist

- Permission metadata copied to vector payload.
- Retrieval service always injects tenant/user filters.
- Client cannot override permission filter.
- Security tests cover unauthorized retrieval.
- Context expansion re-checks permission.
- Reranker only sees authorized chunks.

## 10. BM25

BM25 là keyword-based ranking algorithm phổ biến trong search.

### Vì sao cần BM25 trong RAG?

Vector search tốt về ngữ nghĩa, nhưng có thể miss exact terms:

- service name: `order-payment-worker`
- error code: `ERR_PAYMENT_TIMEOUT`
- API path: `/api/v1/orders/{id}/pay`
- product SKU
- legal clause: `Điều 12`
- acronym nội bộ

BM25 rất tốt với exact lexical match.

### Ví dụ

User hỏi:

```text
ERR_PAYMENT_TIMEOUT xử lý thế nào?
```

Vector search có thể tìm chung chung về payment. BM25 sẽ bắt chính xác `ERR_PAYMENT_TIMEOUT`.

### Trade-off

| Ưu điểm | Nhược điểm |
|---|---|
| Exact keyword tốt | Không hiểu semantic sâu |
| Tốt cho code/error/service names | Kém khi user paraphrase |
| Dễ debug | Cần text index/tokenizer |

## 11. Dense Retrieval

Dense retrieval là retrieval bằng dense embeddings, tức vector nhiều chiều có hầu hết giá trị khác 0.

Đây là semantic vector retrieval đã nói ở chương embedding.

Ưu điểm:

- hiểu paraphrase
- hỗ trợ multilingual nếu model tốt
- phù hợp câu hỏi tự nhiên

Nhược điểm:

- miss exact keyword
- khó debug hơn BM25
- phụ thuộc embedding model

## 12. Sparse Retrieval

Sparse retrieval dùng representation thưa, thường gắn với keyword/term-weighting hoặc sparse neural retrievers.

BM25 là sparse lexical retrieval truyền thống.

Một số model sparse hiện đại có thể tạo sparse vectors tốt hơn BM25.

Trong production RAG, dense + sparse thường bổ sung nhau.

## 13. Hybrid Search

Hybrid search kết hợp vector search và keyword search.

Flow:

```text
User query
  ↓
Dense vector retrieval top 50
  ↓
BM25 retrieval top 50
  ↓
Merge results
  ↓
Rerank
```

### Vì sao cần?

Internal KB thường có cả:

- natural language policies
- technical identifiers
- error codes
- product names
- acronyms

Vector search bắt nghĩa. BM25 bắt exact words. Hybrid tốt hơn từng cái riêng lẻ.

### Reciprocal Rank Fusion

RRF là cách merge rank đơn giản và mạnh.

Formula:

```text
score = sum(1 / (k + rank_i))
```

Thường `k = 60`.

Project hiện tại đã có:

```python
def reciprocal_rank_fusion(result_sets: list[list[str]], k: int = 60) -> dict[str, float]:
    scores: dict[str, float] = {}
    for results in result_sets:
        for rank, item_id in enumerate(results, start=1):
            scores[item_id] = scores.get(item_id, 0.0) + 1.0 / (k + rank)
    return scores
```

### Hybrid pseudo-code

```python
async def hybrid_retrieve(query, filters):
    dense_results = await vector_retriever.search(
        query=query,
        filters=filters,
        top_k=50,
    )

    bm25_results = await keyword_retriever.search(
        query=query,
        filters=filters,
        top_k=50,
    )

    fused_scores = reciprocal_rank_fusion([
        [r.chunk_id for r in dense_results],
        [r.chunk_id for r in bm25_results],
    ])

    by_id = {r.chunk_id: r for r in dense_results + bm25_results}

    return sorted(
        by_id.values(),
        key=lambda r: fused_scores[r.chunk_id],
        reverse=True,
    )
```

### Debug hybrid search

Log:

- dense rank
- BM25 rank
- RRF score
- final rank

Example:

```json
{
  "chunk_id": "payment-runbook-c03",
  "dense_rank": 12,
  "bm25_rank": 1,
  "rrf_score": 0.032,
  "final_rank": 2
}
```

## 14. Multi-Query Retrieval

Multi-query retrieval tạo nhiều biến thể query rồi retrieve từng query.

Ví dụ user hỏi:

```text
Service order xử lý payment như thế nào?
```

Generated queries:

```text
order service payment processing flow
payment lifecycle in order service
how order service handles payment gateway callback
```

### Vì sao cần?

Một query duy nhất có thể không match cách document viết.

Multi-query tăng recall.

### Trade-off

| Ưu điểm | Nhược điểm |
|---|---|
| Recall cao hơn | Tốn embedding/search hơn |
| Bắt nhiều cách diễn đạt | Có thể thêm noise |
| Tốt cho ambiguous query | Cần merge/rerank |

### Production recommendation

Không dùng multi-query cho mọi request nếu cost/latency nhạy cảm.

Dùng khi:

- query dài/ambiguous
- first retrieval score thấp
- user hỏi comparison/multi-hop
- domain quan trọng cần recall cao

## 15. Self-Query Retrieval

Self-query retrieval dùng LLM hoặc parser để tách:

- semantic query
- metadata filters

Ví dụ:

```text
chính sách nghỉ phép năm ngoái thay đổi gì?
```

Output:

```json
{
  "semantic_query": "annual leave policy changes",
  "filters": {
    "document_type": "hr_policy",
    "year": 2025
  }
}
```

### Vì sao cần?

User thường đưa filter trong ngôn ngữ tự nhiên:

- năm ngoái
- tài liệu HR
- trong product X
- lỗi deploy backend
- từ meeting tháng trước

### Lỗi thường gặp

- LLM extract sai metadata.
- Filter quá hẹp.
- Field không tồn tại.
- Date relative tính sai timezone.

Production cần validate filters trước khi dùng.

## 16. Parent Document Retriever

Parent document retriever retrieve chunk nhỏ nhưng trả context lớn hơn.

Flow:

```text
Embed child chunks
  ↓
Search child chunks
  ↓
Map child -> parent section
  ↓
Return parent section to LLM
```

### Khi nào dùng?

- Chunk nhỏ để match chính xác nhưng LLM cần context rộng.
- Policy/legal docs.
- Technical docs có section dài.

### Example

Child match:

```text
Probation employees cannot use annual leave in advance.
```

Parent returned:

```text
Section: Annual Leave
Full-time employees receive 14 days...
Probation employees cannot use annual leave in advance...
Unused leave carry-over...
```

### Risk

Parent context lớn có thể tăng token và noise. Cần compression/rerank.

## 17. Contextual Retrieval

Contextual retrieval là retrieval có thêm context vào chunk hoặc query để cải thiện match.

### Contextualized chunk

Chunk body:

```text
Retry after 30 seconds.
```

Contextualized:

```text
Document: Order Service Runbook
Section: Payment Timeout Handling
Content: Retry after 30 seconds.
```

Đây đã nói ở embedding text template.

### Contextualized query

User query:

```text
timeout xử lý sao?
```

User đang ở workspace Engineering, page Order Service:

```text
order service payment timeout handling
```

### Trade-off

- Cải thiện relevance.
- Nhưng nếu context sai, retrieval sai theo.

## 18. Graph-Based Retrieval

Graph-based retrieval dùng quan hệ giữa entities/documents/chunks.

Ví dụ graph:

```text
Order Service -> calls -> Payment Gateway
Order Service -> emits -> OrderPaid event
Payment Worker -> handles -> ERR_PAYMENT_TIMEOUT
```

### Dùng khi nào?

- multi-hop question
- technical architecture
- entity relationships
- policy dependencies

User hỏi:

```text
Service order xử lý payment như thế nào và liên quan service nào?
```

Graph retrieval có thể tìm:

- Order Service
- Payment Gateway
- Payment Worker
- Event Bus

rồi retrieve docs liên quan.

### Trade-off

- Mạnh cho relationship.
- Nhưng cần xây graph/entity extraction.
- Khó vận hành hơn.

Giai đoạn đầu project chưa cần Graph RAG, nhưng nên thiết kế metadata entity để sau này mở rộng.

## 19. Time-Aware Retrieval

Time-aware retrieval xét thời gian/version.

Ví dụ:

```text
chính sách nghỉ phép năm ngoái thay đổi gì?
```

Cần hiểu "năm ngoái" là 2025 nếu hiện tại 2026.

Filters:

```json
{
  "year": 2025,
  "document_type": "hr_policy"
}
```

Hoặc comparison:

```text
Retrieve HR policy 2025 and 2026
Compare sections Annual Leave
```

### Vì sao cần?

Internal docs thay đổi. Nếu chỉ retrieve current version, câu hỏi về quá khứ sẽ sai.

### Metadata cần có

- `effective_date`
- `expired_at`
- `year`
- `document_version`
- `created_at`
- `updated_at`

## 20. Query Flow Production Example

Yêu cầu bắt buộc flow:

```text
User asks question
  ↓
Classify intent
  ↓
Rewrite query
  ↓
Retrieve dense top 50
  ↓
Retrieve BM25 top 50
  ↓
Merge results
  ↓
Rerank top 20
  ↓
Select final top 5
```

Áp dụng ví dụ:

```text
User: "Service order xử lý payment như thế nào?"
```

Trace:

```json
{
  "intent": "technical_doc_lookup",
  "rewritten_query": "order service payment processing flow",
  "filters": {
    "tenant_id": "company-alpha",
    "workspace_id": "engineering",
    "document_type": ["technical_doc", "runbook"],
    "active": true
  },
  "dense_top_k": 50,
  "bm25_top_k": 50,
  "rerank_top_k": 20,
  "final_top_k": 5
}
```

## 21. Retrieval Pipeline Pseudo-code

```python
class RetrievalPipeline:
    def __init__(
        self,
        query_processor,
        vector_retriever,
        keyword_retriever,
        hybrid_retriever,
        reranker,
        context_expander,
    ):
        self.query_processor = query_processor
        self.vector_retriever = vector_retriever
        self.keyword_retriever = keyword_retriever
        self.hybrid_retriever = hybrid_retriever
        self.reranker = reranker
        self.context_expander = context_expander

    async def retrieve(self, query: str, user_context: UserContext):
        processed = await self.query_processor.process(query, user_context)

        filters = build_filters(
            tenant_id=user_context.tenant_id,
            allowed_workspaces=user_context.allowed_workspaces,
            roles=user_context.roles,
            extracted_filters=processed.filters,
        )

        dense_results = await self.vector_retriever.search(
            query=processed.rewritten_query,
            filters=filters,
            top_k=50,
        )

        keyword_results = await self.keyword_retriever.search(
            query=processed.keyword_query,
            filters=filters,
            top_k=50,
        )

        candidates = self.hybrid_retriever.fuse(
            dense_results=dense_results,
            keyword_results=keyword_results,
            top_k=20,
        )

        reranked = await self.reranker.rerank(
            query=query,
            results=candidates,
            top_k=10,
        )

        expanded = await self.context_expander.expand(
            results=reranked,
            filters=filters,
            token_budget=4000,
        )

        return expanded[:5]
```

## 22. Metadata Filter Trước Hay Sau Vector Search?

Đã nhắc nhiều lần nhưng đây là production rule quan trọng:

> Security filters phải được áp dụng trước hoặc trong retrieval.

### Với filter business không nhạy cảm

Ví dụ document type/year, có thể experiment filter trước/sau để tăng recall. Nhưng thường vẫn nên filter trong DB nếu metadata tốt.

### Với permission filter

Luôn filter trong retrieval.

Không có ngoại lệ.

## 23. Làm Sao Tránh Retrieve Nhầm Chunk?

### 23.1. Improve chunking

- heading-aware
- attach title/section
- avoid noisy chunks
- split table đúng

### 23.2. Improve metadata

- document_type
- department
- service name
- year
- source

### 23.3. Use hybrid search

Vector + BM25 giúp giảm miss exact keywords.

### 23.4. Use reranking

Vector top 50 có thể noisy. Reranker giúp chọn final chunks.

### 23.5. Use query rewriting

Rewrite query giúp match document language.

### 23.6. Use evaluation

Không đo thì không biết retrieval tốt hay không.

## 24. Debug Retrieval

Khi user nói câu trả lời sai, debug retrieval trước.

### Step 1: Xem query processing

```json
{
  "raw_query": "nghỉ phép năm ngoái thay đổi gì?",
  "rewritten_query": "annual leave policy changes in 2025",
  "filters": {
    "document_type": "hr_policy",
    "year": 2025
  }
}
```

Query rewrite/filter có đúng không?

### Step 2: Xem dense results

In top 10:

```text
1 score=0.84 hr-policy-2026 Annual Leave
2 score=0.79 hr-faq Leave
3 score=0.65 travel-policy Leave During Business Trip
```

Expected chunk có trong top 50 không?

### Step 3: Xem BM25 results

BM25 có bắt đúng keyword không?

### Step 4: Xem hybrid merge

Chunk đúng có bị merge làm rớt không?

### Step 5: Xem reranker

Reranker có đẩy chunk đúng lên không?

### Step 6: Xem final context

Final context có đủ answer không?

## 25. Retrieval Logs

Một trace retrieval tốt nên có:

```json
{
  "request_id": "req_123",
  "user_id": "u_42",
  "tenant_id": "company-alpha",
  "raw_query": "Service order xử lý payment như thế nào?",
  "rewritten_query": "order service payment processing flow",
  "filters": {
    "workspace_id": "engineering",
    "document_type": ["technical_doc", "runbook"]
  },
  "dense_results": [
    {"chunk_id": "order-doc-c12", "score": 0.82, "rank": 1}
  ],
  "keyword_results": [
    {"chunk_id": "payment-runbook-c03", "score": 14.2, "rank": 1}
  ],
  "hybrid_results": [
    {"chunk_id": "payment-runbook-c03", "rrf_score": 0.032, "rank": 1}
  ],
  "final_context_ids": ["payment-runbook-c03", "order-doc-c12"],
  "latency_ms": {
    "dense": 45,
    "keyword": 30,
    "hybrid": 2,
    "rerank": 180,
    "total": 260
  }
}
```

## 26. Retrieval Metrics

### Offline metrics

- Recall@k
- Precision@k
- MRR
- nDCG
- Hit rate

### Online metrics

- retrieval latency p50/p95/p99
- empty retrieval rate
- low confidence retrieval rate
- average top score
- average final context tokens
- user feedback by retrieved source

### Example golden dataset

```json
[
  {
    "question": "Nhân viên chính thức có bao nhiêu ngày nghỉ phép?",
    "expected_source_ids": ["hr-policy-2026-v3-c012"]
  }
]
```

Evaluation:

```text
Run retrieval
  ↓
Check whether expected_source_ids appear in top-k
  ↓
Compute Recall@k, MRR
```

## 27. Handling No Relevant Documents

Nếu retrieval không có context tốt, đừng ép LLM trả lời.

Signal:

- top score thấp
- reranker scores thấp
- results empty sau permission filter
- query out-of-domain

Response:

```text
Mình chưa tìm thấy tài liệu nội bộ đủ liên quan để trả lời chắc chắn.
Bạn có thể cung cấp thêm tên tài liệu, phòng ban hoặc thời gian áp dụng không?
```

Prompt cũng phải có no-answer policy.

## 28. Retrieval Và Caching

Retrieval cache có thể giảm latency/cost.

Cache key:

```text
rag:retrieval:{tenant_id}:{user_permission_hash}:{query_hash}:{filter_hash}:{retrieval_version}
```

Không cache chỉ theo query text, vì permission khác nhau.

Invalidation khi:

- document update
- permission update
- embedding version change
- retrieval pipeline version change

## 29. Production Failure Cases

| Failure | Impact | Detection | Recovery | Prevention |
|---|---|---|---|---|
| Empty retrieval | LLM không có context | empty result metric | no-answer/fallback | better query rewrite/filter debug |
| Wrong chunks | Wrong answer | user feedback/eval fail | rerank/tune chunking | golden dataset |
| Permission leak | Security incident | security test/audit | block/hotfix | enforce filters |
| Slow retrieval | Bad UX | p95 latency | cache/index tuning | payload indexes/top-k tuning |
| BM25 index stale | Miss keyword | consistency check | rebuild index | update pipeline |
| Vector index stale | Old answers | version mismatch | re-index | document versioning |
| Filter too narrow | No answer despite docs | debug trace | relax filter | validate extracted filters |

## 30. Implementation Guidance Cho Project Hiện Tại

### 30.1. `vector_retriever.py`

Nên làm:

```python
class VectorRetriever:
    async def search(self, query: str, filters: dict, top_k: int):
        query_vector = await embedding_provider.embed_one(query)
        return await vector_store.search(
            VectorSearchQuery(
                query_vector=query_vector,
                top_k=top_k,
                filters=filters,
            )
        )
```

### 30.2. `keyword_retriever.py`

Ban đầu có thể dùng PostgreSQL full-text search.

Sau này có thể dùng:

- OpenSearch/Elasticsearch
- Tantivy
- BM25 library
- Postgres `tsvector`

### 30.3. `hybrid_retriever.py`

Hiện đã có RRF helper. Mở rộng thành class:

```python
class HybridRetriever:
    def fuse(self, dense_results, keyword_results, top_k: int):
        ...
```

### 30.4. `retrieval_pipeline.py`

Orchestrate toàn bộ flow:

```text
query processing -> filters -> dense + keyword -> hybrid -> rerank -> expand
```

### 30.5. `context_expander.py`

Mở rộng để:

- lấy neighboring chunks
- lấy parent chunks
- đảm bảo permission filter
- giữ token budget

## 31. Recommended Retrieval Baseline

Giai đoạn đầu:

```text
Vector retrieval top 10
Metadata + permission filters
No rerank
```

Giai đoạn tiếp:

```text
Vector top 50
PostgreSQL BM25/full-text top 50
RRF merge top 20
Final top 5
```

Giai đoạn production:

```text
Query rewrite
Dense + BM25
RRF
Cross-encoder rerank
Context expansion
No-answer threshold
Evaluation and monitoring
```

## 32. Production Checklist

- Query embedding uses current embedding model/version.
- Tenant filter always applied.
- Permission filter always applied.
- Metadata filters validated.
- Vector retrieval has top-k and optional threshold.
- Keyword/BM25 retrieval available for exact terms.
- Hybrid fusion implemented.
- Reranking stage planned or implemented.
- Context expansion checks permission.
- Retrieval trace logs raw query, rewritten query, filters, results and scores.
- Empty retrieval handled gracefully.
- Golden dataset measures Recall@k/MRR.
- Retrieval latency monitored.
- Cache key includes permission/filter/version.
- Security test ensures private chunks cannot be retrieved.

## 33. Tóm Tắt Chương

Retrieval là nơi quyết định LLM có nhìn thấy đúng tri thức hay không. Basic vector top-k chỉ đủ cho demo. Production RAG cần:

- metadata filtering
- permission-aware retrieval
- hybrid BM25 + vector search
- query rewriting
- candidate top-k đủ lớn
- reranking
- context expansion
- no-answer handling
- logs/metrics/evaluation

Trong project hiện tại, các module retrieval đã được scaffold đúng hướng. Part tiếp theo sẽ đi sâu vào query processing/rewrite, reranking/context compression và generation pipeline.
