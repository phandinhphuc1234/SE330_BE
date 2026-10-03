# 09. Reranking And Context Compression

Reranking và context compression là hai bước nằm giữa retrieval và generation.

Retrieval thường lấy nhiều candidates để tăng recall. Nhưng LLM không nên nhận tất cả candidates vì context dài, nhiễu, tốn token và dễ gây hallucination. Reranking giúp chọn candidates tốt nhất. Context compression giúp cắt gọn context còn đúng phần cần thiết.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `app/retrieval/reranker.py`
- `app/retrieval/context_expander.py`
- `app/retrieval/retrieval_pipeline.py`
- `app/retrieval/hybrid_retriever.py`
- `app/generation/prompt_builder.py`

## 1. Reranking Là Gì?

Reranking là bước sắp xếp lại danh sách chunks đã retrieve bằng một model hoặc rule chính xác hơn.

Flow:

```text
Retrieve top 50
  ↓
Rerank
  ↓
Compress
  ↓
Select context within token budget
```

Ví dụ:

Vector search trả:

```text
1. HR FAQ Leave
2. Travel Policy Leave
3. HR Policy 2026 Annual Leave
4. Meeting Notes HR Planning
```

Reranker đọc query và từng chunk, rồi reorder:

```text
1. HR Policy 2026 Annual Leave
2. HR FAQ Leave
3. Meeting Notes HR Planning
4. Travel Policy Leave
```

## 2. Vì Sao Vector Search Chưa Đủ?

Vector search nhanh và tốt về semantic similarity, nhưng có các hạn chế:

### 2.1. Embedding là representation nén

Một vector không giữ đầy đủ mọi chi tiết trong chunk. Nó là bản tóm tắt ngữ nghĩa.

Nếu query hỏi:

```text
Nhân viên thử việc có được ứng trước ngày phép không?
```

Vector search có thể lấy chunk về "annual leave" chung, nhưng chunk có câu trả lời chính xác nằm rank thấp hơn.

### 2.2. Vector search dễ miss exact constraints

Các chi tiết như:

- "probation"
- "ERR_PAYMENT_TIMEOUT"
- "after 30 seconds"
- "Điều 12"
- "2025"

có thể không được ưu tiên đúng nếu semantic similarity chung cao.

### 2.3. Top-k candidates có noise

Để tăng recall, ta thường retrieve top 50. Nhưng top 50 chắc chắn có noise. LLM nhận hết sẽ bị:

- mất tập trung
- tăng token cost
- lost in the middle
- có thể dùng context sai

Reranking là bước tăng precision.

## 3. Reranking Nằm Ở Đâu Trong Pipeline?

```text
Query processing
  ↓
Dense retrieval + BM25 retrieval
  ↓
Hybrid merge
  ↓
Reranking
  ↓
Context compression / expansion
  ↓
Prompt builder
```

Trong project hiện tại:

```python
class Reranker:
    async def rerank(self, query: str, results: list, top_k: int):
        return results[:top_k]
```

Hiện là placeholder. Sau này sẽ thay bằng cross-encoder, hosted reranker hoặc LLM reranker.

## 4. Cross-Encoder Reranker

### Nó là gì?

Cross-encoder reranker nhận cả query và document chunk cùng lúc, rồi chấm relevance.

Input:

```text
Query: "Nhân viên chính thức được nghỉ phép bao nhiêu ngày?"
Chunk: "Full-time employees receive 14 days of annual leave..."
```

Output:

```text
relevance_score = 0.94
```

### Vì sao chính xác hơn vector search?

Embedding bi-encoder làm:

```text
embed query riêng
embed chunk riêng
compare vectors
```

Cross-encoder làm:

```text
model reads query + chunk together
```

Nó hiểu interaction giữa query và chunk tốt hơn.

### Ưu điểm

- relevance tốt
- cải thiện precision
- rất hữu ích sau hybrid retrieval

### Nhược điểm

- chậm hơn vector search
- scale theo số candidates
- có max input length

### Khi nào dùng?

- corpus lớn
- retrieval candidates nhiều
- domain yêu cầu chính xác
- citation quan trọng
- answer quality chưa ổn với vector top-k

## 5. LLM-Based Reranker

### Nó là gì?

Dùng LLM để đánh giá chunk nào liên quan đến query.

Prompt example:

```text
Given a user question and a list of candidate passages,
score each passage from 0 to 3 for relevance.
Return JSON only.
```

### Ưu điểm

- linh hoạt
- hiểu instruction phức tạp
- có thể giải thích relevance
- hữu ích với domain ít dữ liệu

### Nhược điểm

- chậm
- tốn cost
- output có thể không ổn định
- cần structured output validation

### Khi nào dùng?

- rerank ít candidates sau stage khác
- query khó
- cần reasoning để chọn context
- không có cross-encoder phù hợp

Không nên dùng LLM rerank cho mọi request nếu latency/cost quan trọng.

## 6. Cohere Rerank

Cohere Rerank là hosted reranker phổ biến.

### Ưu điểm

- API dễ dùng
- quality tốt
- không cần tự host model

### Nhược điểm

- gửi dữ liệu ra external provider
- cost
- rate limit
- privacy/compliance

### Dùng khi nào?

- muốn reranking tốt nhanh
- chưa muốn tự host bge-reranker
- data không bị hạn chế gửi external

## 7. BGE Reranker

BGE reranker là nhóm model reranking open-source phổ biến.

### Ưu điểm

- có thể chạy local
- tốt cho nhiều use case
- kiểm soát privacy

### Nhược điểm

- cần CPU/GPU tùy throughput
- phải optimize serving
- latency có thể cao nếu không batch

### Production tips

- batch rerank candidates
- limit candidate count
- cache rerank results
- monitor p95 latency

## 8. ColBERT

ColBERT là late interaction retrieval/reranking approach.

### Ý tưởng

Thay vì nén toàn chunk thành một vector duy nhất, ColBERT giữ token-level representations và tính interaction giữa query tokens và document tokens.

### Ưu điểm

- quality rất tốt cho retrieval
- tốt hơn dense single-vector trong nhiều trường hợp

### Nhược điểm

- index phức tạp hơn
- storage lớn hơn
- vận hành khó hơn

### Khi nào dùng?

- search quality là ưu tiên lớn
- corpus lớn
- team có năng lực vận hành IR nâng cao

Với project hiện tại, chưa cần bắt đầu bằng ColBERT.

## 9. Reranking Tăng Quality Nhưng Tăng Latency/Cost

Reranking không miễn phí.

Ví dụ latency:

```text
vector retrieval top 50: 50ms
BM25 top 50: 40ms
RRF merge: 2ms
cross-encoder rerank 50 chunks: 300-1200ms
LLM generation: 2-8s
```

### Trade-off

| Candidate count | Quality | Latency |
|---:|---|---|
| 10 | thấp/vừa | nhanh |
| 20 | tốt | vừa |
| 50 | tốt hơn | chậm hơn |
| 100 | recall cao | đắt/chậm |

Production thường:

```text
Retrieve top 50
Rerank top 20 or 30
Final top 5
```

## 10. Khi Nào Cần Rerank?

Cần rerank khi:

- vector top-k có nhiều noise
- query technical/legal cần precision cao
- hybrid retrieval lấy nhiều candidates
- answer có citation sai
- expected chunk nằm trong top 50 nhưng không nằm top 5
- user feedback cho thấy "trả lời gần đúng nhưng thiếu đoạn chính"

Không nhất thiết cần rerank khi:

- corpus nhỏ
- top-k rất chính xác
- FAQ Q/A rõ
- latency cực nhạy
- query exact và BM25 đã đủ tốt

## 11. Rerank Cache

Rerank cache giúp giảm cost/latency với query lặp.

Cache key:

```text
rag:rerank:{model}:{query_hash}:{candidate_ids_hash}:{permission_hash}
```

### Invalidation

Invalidate khi:

- document update
- chunk content changes
- reranker version changes
- permission changes
- retrieval pipeline version changes

## 12. Reranking Pseudo-code

```python
class CrossEncoderReranker:
    def __init__(self, model, max_candidates: int = 50):
        self.model = model
        self.max_candidates = max_candidates

    async def rerank(self, query: str, results: list[SearchResult], top_k: int):
        candidates = results[: self.max_candidates]

        pairs = [
            (query, result.content)
            for result in candidates
        ]

        scores = await self.model.predict(pairs)

        reranked = []
        for result, score in zip(candidates, scores):
            result.metadata["rerank_score"] = float(score)
            reranked.append(result)

        reranked.sort(
            key=lambda item: item.metadata["rerank_score"],
            reverse=True,
        )

        return reranked[:top_k]
```

## 13. Context Compression Là Gì?

Context compression là bước giảm context xuống phần cần thiết trước khi đưa vào prompt.

Input:

```text
5 chunks, mỗi chunk 700 tokens = 3500 tokens
```

Output:

```text
compressed context 1200 tokens, giữ đoạn liên quan nhất
```

### Vì sao cần?

- LLM context có giới hạn.
- Token cost tăng theo context.
- Context dài dễ gây lost in the middle.
- Nhiều chunk có phần không liên quan.

## 14. Lost In The Middle Problem

Lost in the middle là hiện tượng LLM bỏ qua hoặc ít chú ý thông tin nằm giữa context dài.

Ví dụ context:

```text
[Chunk A relevant]
[Chunk B irrelevant]
[Chunk C answer critical]
[Chunk D irrelevant]
[Chunk E relevant]
```

Nếu chunk C nằm giữa prompt dài, model có thể bỏ sót.

### Cách giảm

- rerank tốt
- đặt context quan trọng đầu/cuối
- compress context
- giảm duplicate
- group theo source/section
- hỏi model trả lời từng bước nếu cần

## 15. Compression Methods

### 15.1. Rule-based trimming

Cắt bớt:

- header/footer
- repeated title
- irrelevant metadata
- long unrelated sections

Ưu điểm:

- nhanh
- rẻ

Nhược điểm:

- không hiểu semantic sâu

### 15.2. Sentence-level selection

Chọn các câu liên quan nhất trong chunk.

Flow:

```text
Split chunk into sentences
  ↓
Score each sentence against query
  ↓
Keep top sentences with neighbors
```

### 15.3. LLM compression

Dùng LLM tóm tắt hoặc extract relevant parts.

Prompt:

```text
Extract only the sentences from the context that are directly relevant to the question.
Do not add new information.
Keep source IDs.
```

Ưu điểm:

- linh hoạt
- tốt với context dài

Nhược điểm:

- cost/latency
- có thể làm mất chi tiết
- cần preserve citation

### 15.4. Parent-child / small-to-big

Không hẳn compression, nhưng giúp lấy context vừa đủ:

```text
Retrieve small child
  ↓
Expand to parent section
  ↓
Trim around relevant child
```

## 16. Deduplication

Deduplication trong context stage loại bỏ chunks trùng hoặc gần trùng.

### Vì sao cần?

Overlap cao hoặc multi-query retrieval dễ trả nhiều chunks giống nhau.

Nếu prompt có 5 chunks nhưng 3 chunks gần giống nhau:

- token bị phí
- LLM lặp ý
- mất chỗ cho nguồn khác

### Dedup methods

- exact text hash
- chunk ID uniqueness
- similarity between chunks
- same parent/section choose best one

Pseudo-code:

```python
def deduplicate_results(results):
    seen_hashes = set()
    deduped = []

    for result in results:
        text_hash = hash_normalized_text(result.content)
        if text_hash in seen_hashes:
            continue
        seen_hashes.add(text_hash)
        deduped.append(result)

    return deduped
```

## 17. Diversity Selection

Diversity selection tránh final context toàn là chunks cùng một nguồn trong khi câu hỏi cần nhiều góc nhìn.

Ví dụ query:

```text
Có lỗi nào thường gặp khi deploy backend không?
```

Không nên lấy 5 chunks đều từ cùng một support ticket. Nên lấy:

- runbook deploy
- support tickets phổ biến
- postmortem
- technical docs

## 18. MMR

MMR là **Maximal Marginal Relevance**.

Mục tiêu:

```text
Chọn chunks vừa relevant với query vừa không quá trùng nhau.
```

Score:

```text
MMR = lambda * relevance(query, doc) - (1 - lambda) * similarity(doc, selected_docs)
```

### Dùng khi nào?

- retrieval results duplicate nhiều
- query cần coverage đa dạng
- multi-query retrieval

### Trade-off

- tăng diversity
- có thể bỏ chunk score cao nhưng trùng
- cần tuning lambda

## 19. Context Token Budget

Prompt có token budget.

Ví dụ:

```text
model_context_window = 8192
reserved_for_system_prompt = 800
reserved_for_answer = 1200
reserved_for_user_query = 200
available_for_context = 5992
```

Không nên nhồi context vượt budget.

Pseudo-code:

```python
def select_with_token_budget(results, max_context_tokens):
    selected = []
    used = 0

    for result in results:
        tokens = count_tokens(result.content)
        if used + tokens > max_context_tokens:
            continue
        selected.append(result)
        used += tokens

    return selected
```

## 20. Context Ordering

Thứ tự context ảnh hưởng LLM.

Options:

1. Order by rerank score.
2. Group by document/source.
3. Chronological order.
4. Put most relevant first and conflicting/secondary later.

### Policy question

Nên ưu tiên:

- current active policy
- higher authority document
- latest effective date

### Technical troubleshooting

Nên ưu tiên:

- runbook
- exact error code
- recent support tickets
- architecture docs

## 21. Context Expansion

Context expansion lấy thêm chunks lân cận hoặc parent.

Trong project hiện tại:

- `app/retrieval/context_expander.py`

### Flow

```text
Reranked child chunk
  ↓
Fetch previous/next chunks in same section
  ↓
Check permission
  ↓
Add if within token budget
```

### Production warning

Expansion phải check permission lại.

Không được:

```text
child chunk allowed
  ↓
expand to private parent section without check
```

## 22. Context Compression Pseudo-code

```python
class ContextCompressor:
    def __init__(self, token_counter, max_context_tokens):
        self.token_counter = token_counter
        self.max_context_tokens = max_context_tokens

    async def compress(self, query, results):
        results = deduplicate_results(results)
        results = diversify_results(results)

        selected = []
        used_tokens = 0

        for result in results:
            compressed_text = extract_relevant_sentences(
                query=query,
                text=result.content,
                max_sentences=6,
            )

            tokens = self.token_counter.count(compressed_text)

            if used_tokens + tokens > self.max_context_tokens:
                continue

            result.metadata["compressed"] = True
            result.metadata["original_token_count"] = self.token_counter.count(result.content)
            result.content = compressed_text
            selected.append(result)
            used_tokens += tokens

        return selected
```

## 23. Citation Preservation

Compression không được làm mất citation mapping.

Nếu bạn extract 3 câu từ chunk `hr-policy-2026-v3-c012`, output context vẫn phải giữ:

```json
{
  "chunk_id": "hr-policy-2026-v3-c012",
  "document_id": "hr-policy-2026",
  "page_number": 5,
  "section": "Annual Leave"
}
```

Nếu LLM compression tạo summary không trace được câu nào từ nguồn nào, citation verification khó hơn.

Khuyến nghị:

- ưu tiên extractive compression hơn abstractive summary cho facts quan trọng
- giữ source IDs trên từng đoạn compressed

## 24. When Not To Compress

Không phải lúc nào cũng compress.

Không compress khi:

- context đã ngắn
- chunk là legal clause cần nguyên văn
- table nhỏ cần giữ nguyên
- code block cần đầy đủ
- citation cần exact wording

Với policy/legal docs, nên cẩn thận vì compression có thể bỏ mất điều kiện quan trọng.

## 25. Reranking + Compression Flow Example

User:

```text
Nhân viên thử việc có được nghỉ phép không?
```

Flow:

```text
Hybrid retrieve top 50
  ↓
Rerank top 20
  ↓
Top chunks:
    1. HR Policy 2026 > Annual Leave
    2. HR FAQ > Probation
    3. Onboarding Guide > Benefits
  ↓
Compress to relevant sentences
  ↓
Final context:
    - probation employees cannot use annual leave in advance
    - full-time employees receive 14 days after official contract
  ↓
Prompt with citations
```

## 26. Debug Reranking

Nếu expected chunk nằm trong retrieved top 50 nhưng không vào final context:

1. Kiểm tra hybrid rank.
2. Kiểm tra rerank score.
3. Kiểm tra reranker input có bị truncate không.
4. Kiểm tra query rewrite có đúng không.
5. Kiểm tra chunk content có đủ context không.
6. Kiểm tra dedup/MMR có loại nhầm không.
7. Kiểm tra token budget có bỏ chunk đúng không.

Trace nên có:

```json
{
  "chunk_id": "hr-policy-2026-v3-c012",
  "dense_rank": 8,
  "bm25_rank": 3,
  "hybrid_rank": 4,
  "rerank_score": 0.94,
  "final_selected": true,
  "compressed": true,
  "final_tokens": 180
}
```

## 27. Monitoring

### Metrics

- `reranking_latency_ms`
- `reranking_candidates_count`
- `reranking_errors_total`
- `context_compression_latency_ms`
- `context_original_tokens`
- `context_compressed_tokens`
- `context_compression_ratio`
- `final_context_chunks_count`
- `final_context_tokens`
- `deduplicated_chunks_count`

### Quality metrics

- answer faithfulness
- citation accuracy
- user feedback by reranker version
- golden dataset score before/after reranking

## 28. Failure Cases

| Failure | Impact | Detection | Recovery | Prevention |
|---|---|---|---|---|
| Reranker timeout | Query slow/fail | timeout metric | fallback to hybrid rank | timeout + circuit breaker |
| Reranker misranks | Wrong context | eval regression | tune/model swap | golden dataset |
| Compression drops key fact | Wrong answer | faithfulness/citation check | use extractive compression | compression tests |
| Token budget excludes answer | LLM says unknown | trace selected chunks | reserve more context | token budget tuning |
| Dedup removes needed chunk | Missing source | debug dedup logs | source-aware dedup | dedup tests |
| Expansion leaks private chunk | Security issue | security test | block/hotfix | permission check expansion |

## 29. Implementation Guidance Cho Project Hiện Tại

### 29.1. `reranker.py`

Start simple:

```python
class NoopReranker:
    async def rerank(self, query, results, top_k):
        return results[:top_k]
```

Next:

- add local bge-reranker
- or hosted rerank provider
- add timeout/fallback

Interface:

```python
class Reranker(ABC):
    name: str
    version: str

    async def rerank(self, query: str, results: list[SearchResult], top_k: int):
        ...
```

### 29.2. `context_expander.py`

Implement:

- fetch previous/next chunks
- fetch parent chunk
- enforce filters
- token budget

### 29.3. `retrieval_pipeline.py`

Order:

```text
hybrid candidates
  ↓
dedup
  ↓
rerank
  ↓
context expansion
  ↓
compression/token budget
  ↓
final context
```

## 30. Production Checklist

- Reranker has interface and version.
- Reranker has timeout.
- Fallback to hybrid rank if reranker fails.
- Candidate count limited.
- Rerank scores logged.
- Golden dataset measures rerank improvement.
- Context compression preserves citations.
- Compression is extractive for factual/legal content.
- Deduplication avoids duplicate context.
- MMR/diversity used when needed.
- Context expansion checks permission.
- Final context respects token budget.
- Final context logs chunk IDs and token counts.
- Reranker/cache invalidates on document/pipeline version change.

## 31. Tóm Tắt Chương

Reranking giúp tăng precision sau retrieval. Context compression giúp đưa đúng lượng thông tin vào prompt. Hai bước này đặc biệt quan trọng khi bạn dùng hybrid retrieval và retrieve nhiều candidates.

Một pipeline production thường:

```text
Retrieve top 50
  ↓
RRF merge
  ↓
Rerank top 20
  ↓
Dedup/MMR
  ↓
Expand/compress
  ↓
Final top 3-8 chunks within token budget
```

Chương tiếp theo sẽ đi vào generation pipeline: prompt builder, context injection, refusal policy, citation format, structured output và verification sau khi LLM trả lời.
