# 20. Advanced RAG Patterns

Chương này tổng hợp các pattern RAG nâng cao. Mục tiêu không phải dùng tất cả, mà là biết pattern nào giải quyết vấn đề nào, khi nào nên dùng, trade-off ra sao và độ khó triển khai thế nào.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, các pattern này map vào:

- `app/retrieval/`
- `app/generation/`
- `app/indexing/`
- `app/ingestion/`
- `app/evaluation/`

## 1. Hybrid RAG

### Nó là gì?

Hybrid RAG kết hợp nhiều retrieval methods, phổ biến nhất là:

```text
BM25 keyword search + dense vector search
```

Sau đó merge bằng RRF hoặc scoring strategy.

### Dùng khi nào?

- Technical docs có error codes, API paths, service names.
- HR/legal docs có exact clause numbers.
- Corpus có cả tiếng Việt và tiếng Anh.
- Vector-only miss exact keywords.

### Flow

```text
Query
  ↓
Dense vector retrieval top 50
  ↓
BM25 retrieval top 50
  ↓
RRF merge
  ↓
Rerank
  ↓
Generate
```

### Ưu điểm

- tăng recall
- bắt được exact keywords
- mạnh hơn vector-only

### Nhược điểm

- cần thêm text index
- merge/rank phức tạp hơn
- debug nhiều stage hơn

### Độ khó

Trung bình. Project hiện tại đã có `hybrid_retriever.py` với RRF helper, rất hợp để triển khai sớm.

## 2. Corrective RAG

### Nó là gì?

Corrective RAG kiểm tra retrieval có đủ tốt không. Nếu retrieval kém, hệ thống tự sửa: rewrite query, retrieve lại, dùng web/tool khác, hoặc trả no-answer.

### Dùng khi nào?

- Query hay mơ hồ.
- Empty retrieval cao.
- User feedback nhiều câu "không đúng tài liệu".

### Flow

```text
Initial retrieval
  ↓
Evaluate retrieved context quality
  ↓
If low quality:
    rewrite query / relax filters / use HyDE / ask clarification
  ↓
Retrieve again
  ↓
Generate or no-answer
```

### Ưu điểm

- giảm hallucination
- xử lý retrieval fail tốt hơn
- user experience tốt hơn

### Nhược điểm

- tăng latency/cost
- cần quality judge
- logic phức tạp hơn

### Độ khó

Trung bình đến cao.

## 3. Self-RAG

### Nó là gì?

Self-RAG là pattern để model tự đánh giá khi nào cần retrieve, retrieval có đủ tốt không, và answer có grounded không.

### Dùng khi nào?

- Query đa dạng, không phải câu nào cũng cần retrieval.
- Muốn model tự quyết định retrieve/refine.

### Flow

```text
Question
  ↓
Model decides: retrieve or answer?
  ↓
Retrieve if needed
  ↓
Model critiques evidence
  ↓
Generate answer
```

### Ưu điểm

- linh hoạt
- có thể giảm retrieval không cần thiết

### Nhược điểm

- khó kiểm soát
- cần prompt/eval mạnh
- latency/cost cao hơn deterministic RAG

### Độ khó

Cao. Không nên làm trước khi basic/hybrid/evaluation ổn.

## 4. Adaptive RAG

### Nó là gì?

Adaptive RAG chọn strategy khác nhau tùy query.

Ví dụ:

| Query type | Strategy |
|---|---|
| FAQ rõ | vector top 5 |
| error code | BM25 + vector hybrid |
| policy comparison | multi-version retrieval |
| low score | HyDE or multi-query |
| summary | parent document retrieval |

### Flow

```text
Classify query intent
  ↓
Choose retrieval strategy
  ↓
Run selected strategy
  ↓
Generate
```

### Ưu điểm

- cân bằng quality/cost
- tránh dùng heavy pipeline cho mọi query

### Nhược điểm

- cần intent classifier
- nhiều code paths
- eval phức tạp hơn

### Độ khó

Trung bình. Nên làm sau khi có observability và eval.

## 5. Agentic RAG

### Nó là gì?

Agentic RAG dùng agent/planner để quyết định nhiều bước retrieve/tool use động.

Ví dụ agent có thể:

- search docs
- query SQL
- call calculator
- compare versions
- ask user clarification

### Dùng khi nào?

- câu hỏi multi-step
- cần tool ngoài vector DB
- cần query database live
- cần reasoning/planning

### Ưu điểm

- linh hoạt
- xử lý task phức tạp

### Nhược điểm

- khó kiểm soát
- cost cao
- latency cao
- cần guardrails/logging mạnh

### Độ khó

Cao. Chương 21 đi sâu riêng.

## 6. Graph RAG

### Nó là gì?

Graph RAG dùng knowledge graph/entity relationships để hỗ trợ retrieval.

Ví dụ:

```text
Order Service -> calls -> Payment Gateway
Payment Worker -> handles -> ERR_PAYMENT_TIMEOUT
Payment Gateway -> emits -> payment_callback
```

### Dùng khi nào?

- technical architecture nhiều relationships
- multi-hop questions
- organizational knowledge
- product dependency docs

### Flow

```text
Extract entities/relations
  ↓
Build graph
  ↓
Query graph for related entities
  ↓
Retrieve docs/chunks linked to entities
  ↓
Generate answer
```

### Ưu điểm

- tốt cho multi-hop
- explain relationships
- giảm miss entity relations

### Nhược điểm

- entity extraction khó
- graph maintenance phức tạp
- cần schema/ontology

### Độ khó

Cao.

## 7. Multi-Hop RAG

### Nó là gì?

Multi-hop RAG trả lời câu hỏi cần nhiều bước hoặc nhiều nguồn.

Ví dụ:

```text
Order service xử lý payment timeout thế nào và policy retry đó được thay đổi sau incident nào?
```

Cần:

1. Retrieve payment timeout runbook.
2. Find incident/postmortem linked.
3. Retrieve incident notes.
4. Synthesize answer.

### Flow

```text
Question
  ↓
Break into sub-questions
  ↓
Retrieve for each sub-question
  ↓
Combine evidence
  ↓
Generate answer
```

### Ưu điểm

- xử lý câu hỏi phức tạp

### Nhược điểm

- nhiều retrieval calls
- dễ drift
- cần citation theo từng hop

### Độ khó

Trung bình đến cao.

## 8. Multi-Vector Retrieval

### Nó là gì?

Một document/chunk có nhiều vectors đại diện cho nhiều góc nhìn.

Ví dụ một document có:

- vector content
- vector summary
- vector title/headings
- vector questions generated from doc

### Dùng khi nào?

- documents dài
- title/summary match tốt hơn body
- FAQ-like retrieval

### Flow

```text
Document
  ↓
Generate multiple representations
  ↓
Embed each representation
  ↓
Retrieve by multiple vector types
  ↓
Map back to source document/chunk
```

### Ưu điểm

- tăng recall
- match nhiều query styles

### Nhược điểm

- tăng storage
- tăng indexing complexity
- cần dedup/merge

### Độ khó

Trung bình.

## 9. Parent-Child Retrieval

### Nó là gì?

Embed child chunks nhỏ để retrieve chính xác, sau đó trả parent section lớn hơn cho LLM.

### Dùng khi nào?

- policy/legal docs
- technical docs dài
- chunk nhỏ thiếu context

### Flow

```text
Retrieve child chunk
  ↓
Map to parent section
  ↓
Return parent context
```

### Ưu điểm

- retrieval chính xác
- context đủ hơn

### Nhược điểm

- schema phức tạp
- token budget tăng

### Độ khó

Trung bình. Project hiện tại có thể mở rộng `context_expander.py`.

## 10. Small-To-Big Retrieval

### Nó là gì?

Retrieve small chunks, rồi expand sang chunks lân cận hoặc parent.

### Dùng khi nào?

- answer nằm trong đoạn nhỏ nhưng cần đoạn trước/sau
- meeting notes
- runbooks

### Flow

```text
Retrieve small chunk
  ↓
Fetch neighbors in same section
  ↓
Check permission
  ↓
Send expanded context
```

### Cảnh báo

Expansion phải check permission. Không được expand sang private neighbor.

### Độ khó

Trung bình.

## 11. Late Interaction Retrieval

### Nó là gì?

Late interaction giữ representation chi tiết hơn single-vector retrieval, ví dụ ColBERT.

### Dùng khi nào?

- cần search quality cao
- corpus lớn
- có team IR/ML mạnh

### Ưu điểm

- retrieval quality tốt
- match token-level tốt hơn

### Nhược điểm

- index/storage lớn
- triển khai phức tạp

### Độ khó

Cao.

## 12. Contextual Compression

### Nó là gì?

Rút gọn context chỉ còn phần liên quan nhất.

### Dùng khi nào?

- retrieved chunks dài
- token budget hạn chế
- LLM hay bị lost in the middle

### Flow

```text
Retrieve/rerank
  ↓
Extract relevant sentences
  ↓
Preserve citations
  ↓
Prompt
```

### Độ khó

Trung bình.

## 13. RAG + Knowledge Graph

### Nó là gì?

Kết hợp vector retrieval với graph database/knowledge graph.

### Dùng khi nào?

- cần trả lời quan hệ entity
- docs có dependency graph
- hỏi kiểu "liên quan đến service nào"

### Example

Query:

```text
Payment timeout ảnh hưởng service nào?
```

Graph:

```text
ERR_PAYMENT_TIMEOUT -> occurs_in -> Payment Worker
Payment Worker -> part_of -> Order Service
Order Service -> calls -> Payment Gateway
```

Then retrieve docs for these entities.

### Độ khó

Cao.

## 14. RAG + SQL

### Nó là gì?

Kết hợp RAG text docs với SQL database query.

### Dùng khi nào?

- câu hỏi cần dữ liệu structured/live
- số liệu, counts, trạng thái

Ví dụ:

```text
Tháng trước có bao nhiêu support ticket payment timeout?
```

Vector docs không đủ. Cần SQL:

```sql
SELECT count(*) FROM support_tickets
WHERE category = 'payment_timeout'
AND created_at >= ...
```

### Ưu điểm

- trả lời dữ liệu live
- chính xác cho aggregate

### Nhược điểm

- SQL generation risk
- permission phức tạp
- cần guardrails

### Độ khó

Trung bình đến cao.

## 15. RAG + Tools

### Nó là gì?

RAG có thể gọi tool ngoài retrieval:

- calculator
- SQL
- web search
- ticket lookup
- deployment status
- document lookup

### Dùng khi nào?

- answer cần action/data ngoài docs
- multi-step workflow

### Cẩn thận

Tool use cần:

- permissions
- audit
- allowlist
- input validation
- timeout
- cost control

## 16. Pattern Selection Guide

| Problem | Pattern nên thử |
|---|---|
| Miss exact keyword/error code | Hybrid RAG |
| Top-k có answer nhưng final sai | Reranking |
| Query mơ hồ | Query rewrite / multi-query |
| Retrieval empty | Corrective RAG / HyDE |
| Chunk thiếu context | Parent-child / small-to-big |
| Multi-hop relationship | Graph RAG / multi-hop RAG |
| Structured numeric question | RAG + SQL |
| Need dynamic actions | Agentic RAG/tools |
| Cost too high | Adaptive RAG |

## 17. Recommended Progression Cho Project Hiện Tại

Không nhảy thẳng vào Agentic/Graph RAG.

Roadmap khuyến nghị:

```text
1. Basic vector RAG
2. Metadata + permission filtering
3. Hybrid BM25 + vector
4. Reranking
5. Context expansion/compression
6. Query rewriting
7. Evaluation gates
8. Adaptive retrieval
9. Parent-child/small-to-big
10. RAG + SQL/tools
11. Graph/Agentic RAG if needed
```

## 18. Tóm Tắt Chương

Advanced RAG patterns là hộp công cụ. Mỗi pattern giải quyết một loại failure:

- Hybrid giải quyết exact keyword miss.
- Reranking giải quyết noisy candidates.
- Corrective RAG giải quyết low-quality retrieval.
- Parent-child giải quyết thiếu context.
- Graph RAG giải quyết relationships.
- RAG + SQL giải quyết dữ liệu structured.
- Agentic RAG giải quyết workflows động.

Chương tiếp theo đi sâu vào Agentic RAG và tool use: khi nào cần agent, planner, tool calling, guardrails và vì sao nó khó kiểm soát hơn deterministic RAG.
