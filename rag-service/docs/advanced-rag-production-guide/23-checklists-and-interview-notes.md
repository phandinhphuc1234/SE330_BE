# 23. Checklists And Interview Notes

File này là bảng tổng hợp để review nhanh trước khi implement, trước khi deploy, hoặc trước phỏng vấn về RAG.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty.

## 1. RAG Design Checklist

- Use case rõ ràng.
- Corpus types đã biết: PDF, DOCX, wiki, tickets, FAQ.
- Offline ingestion tách khỏi online query.
- Metadata model rõ: tenant, workspace, document, version, chunk.
- Permission model rõ.
- Embedding model chọn dựa trên language/domain.
- Vector DB chọn dựa trên scale/filtering.
- Retrieval strategy có baseline và plan nâng cấp.
- Generation prompt có context-only rule.
- Citation format rõ.
- Evaluation dataset có expected sources.
- Observability theo request_id.
- Deployment/backup/retry plan.

## 2. Ingestion Checklist

- Raw file được lưu.
- Parser theo file type.
- Raw extracted text lưu riêng.
- Cleaned text lưu riêng.
- Header/footer/table/encoding xử lý.
- Chunking strategy theo document type.
- Chunk metadata đầy đủ.
- Content hash và chunk hash.
- Document versioning.
- Async job queue.
- Retry/backoff.
- DLQ.
- Idempotent upsert.
- Cleanup orphan chunks/vectors.

## 3. Chunking Checklist

- Chunk không quá nhỏ.
- Chunk không quá lớn.
- Overlap hợp lý.
- Heading/section được giữ.
- Table/code không bị phá.
- FAQ giữ Q/A cùng chunk.
- Legal/policy không split giữa điều khoản.
- Chunk có token count.
- Chunk có stable ID.
- Chunk có hash.
- Chunking strategy versioned.
- Chunk có permission metadata.
- Evaluation đo Recall@k trước/sau đổi strategy.

## 4. Retrieval Checklist

- Query embedding đúng model/version.
- Tenant filter bắt buộc.
- Permission filter bắt buộc.
- Metadata filters validated.
- Vector search top-k phù hợp.
- BM25/keyword search cho exact terms.
- Hybrid RRF merge.
- Reranking nếu cần.
- Context expansion check permission.
- Empty retrieval handled.
- Retrieval logs có chunk IDs/scores/ranks.
- Golden dataset đo Recall@k/MRR.

## 5. Security Checklist

- Auth/JWT.
- User roles/workspaces.
- Tenant isolation.
- Document-level ACL.
- Chunk-level permission.
- Permission filter trong retrieval.
- Reranker chỉ nhận authorized chunks.
- Prompt injection defense.
- Cache key có permission hash.
- Logs redacted.
- Secrets không trong repo.
- Audit log chunk/document access.
- PII masking policy.
- Encryption at rest/in transit.

## 6. Evaluation Checklist

- Golden dataset có expected source IDs.
- Dataset có tags/difficulty.
- Retrieval metrics: Recall@k, Precision@k, MRR, nDCG.
- Generation metrics: faithfulness, answer relevance.
- Citation accuracy.
- Hallucination rate.
- Latency p50/p95/p99.
- Cost per query.
- Eval report versioned.
- CI regression gate.
- User feedback loop.

## 7. Production Readiness Checklist

- API/worker tách riêng.
- PostgreSQL migrations bằng Alembic.
- Redis queue/cache.
- Qdrant/vector DB persistent.
- Health/readiness checks.
- Structured logs.
- Metrics dashboard.
- Alerts.
- Backup/restore tested.
- Retry/DLQ.
- Circuit breaker/fallback.
- Rate limiting.
- Security tests.
- RAG eval gate.
- Rollback plan.

## 8. Common Mistakes

| Mistake | Hậu quả |
|---|---|
| Chỉ dùng vector top 5 | miss exact keywords, recall thấp |
| Không lưu document version | stale/rollback khó |
| Đổi embedding model không re-index | similarity sai |
| Retrieve rồi mới filter permission | data leakage |
| Không có evaluation | không biết quality tốt hay xấu |
| Chunk quá nhỏ | mất context |
| Chunk quá lớn | prompt noise, token cost cao |
| Không log chunk IDs | không debug được |
| Cache answer theo query text | leak/stale answer |
| Prompt không chống injection | document có thể override instruction |

## 9. Interview Q&A

### RAG là gì?

RAG là Retrieval-Augmented Generation. Thay vì để LLM trả lời chỉ dựa trên kiến thức đã học, hệ thống trước tiên retrieve tài liệu liên quan từ knowledge base, đưa context đó vào prompt, rồi LLM sinh câu trả lời grounded trên nguồn. RAG đặc biệt hữu ích cho dữ liệu private, dữ liệu mới, và các use case cần citation.

### Vì sao RAG hallucinate?

RAG vẫn hallucinate nếu retrieval sai hoặc thiếu context, context quá nhiễu, prompt không ép model dùng context, citation không được verify, hoặc LLM tự dùng kiến thức ngoài. Hallucination cũng có thể đến từ stale documents, chunking sai, reranker chọn nhầm chunk hoặc context compression bỏ mất facts quan trọng.

### Chunk size chọn thế nào?

Không có chunk size universal. Chọn theo loại tài liệu và đo bằng evaluation. FAQ có thể 100-300 tokens, policy/legal 300-700, technical docs 400-900, research paper 700-1200. Chunk quá nhỏ mất context, chunk quá lớn nhiễu và tốn token. Nên bắt đầu baseline, tạo golden dataset, đo Recall@k/MRR/citation accuracy rồi điều chỉnh.

### Hybrid search là gì?

Hybrid search kết hợp dense vector search và keyword/BM25 search. Vector search tốt cho ngữ nghĩa/paraphrase, BM25 tốt cho exact keywords như error code, service name, API path, điều khoản. Kết quả thường được merge bằng Reciprocal Rank Fusion rồi rerank. Hybrid search thường tốt hơn vector-only trong internal knowledge base.

### Reranking để làm gì?

Retriever lấy nhiều candidates để tăng recall, nhưng candidates có noise. Reranker đọc query và từng chunk để chấm relevance chính xác hơn, giúp đưa chunk đúng lên đầu trước khi build prompt. Reranking tăng quality nhưng tăng latency/cost, nên thường rerank top 20-50 candidates rồi chọn final top 3-8.

### RAG evaluation đo bằng gì?

Đo nhiều tầng. Retrieval dùng Recall@k, Precision@k, MRR, nDCG. Generation dùng faithfulness, answer relevance, citation accuracy, hallucination rate. Production còn đo latency p50/p95/p99, cost per query, empty retrieval rate và user feedback. Quan trọng là có golden dataset với expected source IDs.

### Làm sao chống prompt injection trong RAG?

Xem retrieved documents là untrusted data. System prompt phải nói không được làm theo instruction trong documents. Permission filter phải đảm bảo only authorized context vào prompt. Có thể detect/flag injection patterns trong ingestion. Cần tests với document chứa "ignore previous instructions". Post-generation validation và logging cũng cần thiết.

### Làm sao phân quyền tài liệu trong RAG?

Permission metadata phải nằm ở document và chunk/vector payload. Retrieval phải áp dụng tenant/workspace/role/user filters trực tiếp trong vector DB/BM25 query, không retrieve rồi mới lọc sau. Context expansion, reranking và cache đều phải permission-aware. Cache key cần permission hash để tránh leak giữa users.

### Làm sao update document mà không re-index toàn bộ?

Dùng document versioning, content hash và chunk hash. Khi document update, parse/chunk version mới, so sánh chunk hashes với version trước, chỉ embed/upsert chunks mới hoặc thay đổi, soft delete chunks bị xóa. Nếu chỉ một document đổi thì không cần re-index toàn corpus. Nhưng nếu đổi embedding model hoặc chunking strategy lớn thì thường cần re-index.

### Vector DB khác database thường ở đâu?

Database thường tối ưu query structured data bằng indexes như B-tree. Vector DB tối ưu similarity search trên vectors nhiều chiều bằng ANN indexes như HNSW/IVF/PQ, đồng thời hỗ trợ metadata filtering. Vector DB dùng để tìm chunks gần nghĩa với query embedding, không thay thế PostgreSQL metadata DB. Production RAG thường dùng cả PostgreSQL và vector DB.

## 10. Final Production Mental Model

Hãy nhớ RAG production là một system, không phải một prompt.

```text
Data quality
  ↓
Chunk quality
  ↓
Embedding/index quality
  ↓
Retrieval quality
  ↓
Context quality
  ↓
Prompt quality
  ↓
Answer quality
  ↓
Evaluation and feedback
```

Nếu answer sai, debug từ trái sang phải. Đừng đổ lỗi cho LLM đầu tiên.

## 11. Recommended Next Learning Loop

1. Chọn 10 tài liệu internal sample.
2. Tạo 30 câu hỏi golden.
3. Implement basic ingestion.
4. Implement vector retrieval.
5. Đo Recall@5.
6. Thêm hybrid retrieval.
7. Đo lại.
8. Thêm reranking.
9. Đo lại.
10. Thêm prompt/citation validation.
11. Đo faithfulness/citation accuracy.
12. Lặp.

Đó là cách học RAG bài bản: build, measure, improve.
