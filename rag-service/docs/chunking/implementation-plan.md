# Chunking Implementation Plan

File này là checkpoint chính cho phần chunking. Nếu bị mất context, đọc file này
trước để biết:

- phần nào đã làm xong;
- phần nào nên làm ngay tiếp theo;
- phần nào cố ý để sau;
- code/test/doc nào liên quan.

Ngày cập nhật gần nhất: `2026-10-06`.

## Checkpoint mới — baseline và ranh giới trang/chương

[Kế hoạch bốn bước và verification ledger](chunking-baseline-and-boundary-plan.md)
là checkpoint cho lượt cải thiện hiện tại: bước 1 đã implement fixtures,
snapshot, tests và CLI; bước 2 có [chapter-aware/source-mapped v2](chapter-aware-v2.md)
opt-in. Bước 3 có [context expansion theo chương/phiên bản](chapter-aware-context-expansion.md).
Bước 4 có [benchmark PDF thật và promotion gates](real-book-chunking-benchmark.md).
Heading regressions đã sửa; [diagnostics/query holdout](retrieval-diagnostics-and-vi-query-holdout.md)
và [hybrid ranking trace](hybrid-ranking-trace.md) đã đo regression tiếp theo.
[Policy comparison](hybrid-policy-comparison.md) đã chạy 560 cases + replay;
không candidate nào qua mọi gates. [Lexical diagnosis](lexical-agreement-diagnostics.md)
đã phân tích 80 cases và counterexamples, không đổi scoring.
[Adaptive experiment v2](adaptive-hybrid-experiment-v2.md) đã chạy 560 cases;
macro gains nhưng không candidate nào qua mọi gates, giữ HOLD.
[Vietnamese-source baseline mới](vietnamese-source-evaluation-v1.md) đã thêm
báo cáo Việt 32 trang, 20 câu/23 anchors đã khóa, 40 BM25 cases + exact replay.
V2 Recall@3 87,5% nhưng còn 30/94 section mismatches. Bạn đã duyệt toàn bộ
[phiếu 20 câu/7 heading](vietnamese-source-review-v1.md) ngày 2026-10-06;
approval lưu riêng theo hash, không sửa reports cũ.
[Bounded Gemini baseline](vietnamese-source-embedding-baseline-v1.md) đã hoàn tất
sau lượt lỗi đầu tiên: giữ 96 vectors, user duyệt riêng 120 missing inputs,
token-aware pacing bổ sung thành công cả 120, cache đủ 216 vectors. Chấm đủ
120 cases, 0 errors; Hybrid Recall@3 v1/v2 là 92,5%/90%. V2 vẫn regression và
còn 30/94 section mismatches: giữ HOLD v1, không tune known cases, thay default
hoặc reindex production.
Follow-up [chẩn đoán đã xong](vietnamese-baseline-diagnostics-v1.md): 40 pairs,
120 exact baseline replay cases, 0 provider calls; 23/23 anchors có full
single-chunk evidence ở cả hai. V2 nhận 2/7 headings; rank/context losses đã
trace đến từng stage. Tiếp theo làm section-detection v3 như experiment riêng;
không sửa v1/v2 hoặc tune weights, inputs embedding mới cần quyền/budget riêng.
Các checklist phía dưới giữ lịch sử.

Embedding, Qdrant, search, hybrid và evaluation runner đã có; xem
[implementation ledger](../retrieval-evaluation-implementation.md).
Các phần roadmap cũ phía dưới giữ làm lịch sử; không dùng chữ "sau này" trong
chúng để kết luận embedding/search hiện chưa tồn tại.

---

## 1. Mục tiêu hiện tại

Project đang ưu tiên sách tiểu thuyết/narrative trước.

Flow chunking MVP hiện tại:

```text
PDF source trong library-private
  -> validate PDF
  -> parse page-wise
  -> clean page-wise
  -> LlamaIndex Document page-wise
  -> LlamaIndex SentenceSplitter
  -> child chunks
  -> PostgreSQL document_chunks
  -> rag-artifacts/chunks/chunks.jsonl
  -> sau này embed child chunks
  -> Qdrant
```

Quyết định quan trọng:

```text
MVP không làm table-aware / textbook heading-aware / semantic chunking ngay.
MVP làm tốt novel/narrative trước.
```

---

## 2. Đọc lại context theo thứ tự này

Khi bị mất context, đọc theo thứ tự:

1. [README.md](README.md)
   - bản đồ thư mục chunking.

2. [implementation-plan.md](implementation-plan.md)
   - file hiện tại, dùng để biết đang làm tới đâu.

3. [library-rag-chunking-strategy.md](library-rag-chunking-strategy.md)
   - strategy tổng thể cho hệ thống thư viện.

4. [novel-chunking-strategy.md](novel-chunking-strategy.md)
   - strategy riêng cho sách tiểu thuyết.

5. [deferred-implementation.md](deferred-implementation.md)
   - backlog cố ý để sau.

6. [../embedding/README.md](../embedding/README.md)
   - embedding roadmap, vì chunking nâng cao phụ thuộc embedding/Qdrant/retrieval.

Nếu cần lý thuyết nền:

- [04-chunking-strategy.md](04-chunking-strategy.md)

---

## 3. Trạng thái hiện tại

### Đã làm xong

| Mã | Việc | Trạng thái | Link |
|---|---|---|---|
| C1 | Thêm metadata `document_profile`, `chunking_strategy`, `chunking_strategy_version` | Done | [library-rag-chunking-strategy.md](library-rag-chunking-strategy.md) |
| C2 | Tách `ChunkingStrategy` adapter dùng LlamaIndex | Done | [strategy.py](../../app/ingestion/chunking/strategy.py), [llama_sentence_strategy.py](../../app/ingestion/chunking/llama_sentence_strategy.py) |
| C3 | `DocumentProfiler` v1 | Done | [profiling](../../app/ingestion/profiling/) |
| C4 | `ChunkQualityValidator` v1 | Done | [quality.py](../../app/ingestion/chunking/quality.py) |
| C5 | `token_count`, `token_counter`, token stats | Done | [token_counter.py](../../app/ingestion/chunking/token_counter.py) |
| C6 | Chapter metadata cho tiểu thuyết | Done | [chapter_detector.py](../../app/ingestion/chunking/chapter_detector.py) |
| A1 | Lưu artifacts parsed/cleaned/chunks/report/manifest vào S3 `rag-artifacts` | Done | [writer.py](../../app/ingestion/artifacts/writer.py) |

### Đang có trong code

Code chunking chính:

- [app/ingestion/pipeline.py](../../app/ingestion/pipeline.py)
- [app/ingestion/chunking/](../../app/ingestion/chunking/)
- [app/ingestion/llama_index/node_adapter.py](../../app/ingestion/llama_index/node_adapter.py)
- [app/ingestion/llama_index/document_adapter.py](../../app/ingestion/llama_index/document_adapter.py)
- [app/ingestion/llama_index/pdf_cleaning_transformation.py](../../app/ingestion/llama_index/pdf_cleaning_transformation.py)

Tests liên quan:

- [test_chunking_strategy.py](../../tests/unit/test_chunking_strategy.py)
- [test_document_profiler.py](../../tests/unit/test_document_profiler.py)
- [test_chunk_quality.py](../../tests/unit/test_chunk_quality.py)
- [test_token_counter.py](../../tests/unit/test_token_counter.py)
- [test_chapter_detector.py](../../tests/unit/test_chapter_detector.py)
- [test_llamaindex_node_adapter.py](../../tests/unit/test_llamaindex_node_adapter.py)
- [test_ingestion_pipeline_llamaindex_integration.py](../../tests/unit/test_ingestion_pipeline_llamaindex_integration.py)

---

## 4. Strategy hiện tại đang chạy

Tên strategy:

```text
library_pdf_narrative
```

Version:

```text
v1
```

Chunker thật sự:

```text
LlamaIndex SentenceSplitter
```

Metadata chính trên chunk:

```json
{
  "document_profile": "novel_narrative",
  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1",
  "chunk_level": "child",
  "token_count": 420,
  "token_counter": "approx_token_counter_v1",
  "chapter_detected": true,
  "chapter_title": "Chương 1",
  "section_path": ["Chương 1"]
}
```

Ý nghĩa:

```text
Hiện tại chỉ có child chunks.
Chưa có parent chunks.
Chưa có semantic chunking.
Chưa có chapter-wise chunking thật sự.
```

C6 hiện chỉ làm:

```text
detect chapter heading ở page
carry-forward chapter metadata sang các chunk sau
```

C6 chưa làm:

```text
ghép toàn bộ sách thành text liền mạch
chunk theo chapter boundary thật sự
parent-child retrieval
neighbor expansion
```

---

## 5. Việc làm ngay tiếp theo

### Nguyên tắc

Không nên tiếp tục mở rộng chunking nâng cao ngay.

Lý do:

```text
Hiện tại chưa có embedding + Qdrant + retrieval baseline.
Nếu làm parent-child/semantic quá sớm thì chưa có cách đo nó có tốt hơn không.
```

Vì vậy thứ tự gần nhất là:

```text
A2 Embedding
A3 Qdrant
A4 Retrieval/search baseline
E1 Evaluation nhỏ cho novel
Sau đó mới quay lại chunking nâng cao
```

### A2 — Embedding

Nguồn plan:

- [../embedding/README.md](../embedding/README.md)

Mục tiêu:

```text
document_chunks
  -> build embedding_text
  -> Gemini embedding
  -> vector
```

Status hiện tại:

```text
EmbeddingTextBuilder đã có.
GeminiEmbeddingProvider đã có.
ChunkEmbeddingService đã có để build embedding_text, embed và update chunk metadata trong memory.
Chưa wire service này vào pipeline chính.
Chưa upsert vector vào Qdrant.
```

Quyết định hiện tại:

```text
provider = gemini
model = gemini-embedding-2
dimension = 3072
batch_size MVP = 1
embedding_text_policy = novel_context_v1 / gemini_search_title_text_v1
```

Chunking liên quan trực tiếp tới A2 vì `embedding_text` không nên chỉ là raw
chunk content. Với novel, nên thêm context nhẹ:

```text
Title: <book title>
Author: <author nếu có>
Chapter: <chapter title nếu có>
Page: <page_start>-<page_end>

Content:
<chunk content>
```

Metadata cần có sau A2:

```json
{
  "embedding_status": "embedded",
  "embedding_provider": "gemini",
  "embedding_model": "gemini-embedding-2",
  "embedding_dim": 3072,
  "embedding_version": "gemini-embedding-2-3072-v1",
  "embedding_text_policy": "novel_context_v1",
  "embedding_text_hash": "sha256:..."
}
```

### A3 — Qdrant upsert

Nguồn plan:

- [../embedding/README.md](../embedding/README.md)
- [../advanced-rag-production-guide/06-vector-database-and-indexing.md](../advanced-rag-production-guide/06-vector-database-and-indexing.md)

Mục tiêu:

```text
vectors + payload
  -> Qdrant collection
```

Qdrant collection MVP:

```text
name = rag_chunks
vector size = 3072
distance = Cosine
```

Status hiện tại:

```text
Qdrant collection setup/validation đã có.
Qdrant vector upsert method đã có.
Pipeline chính đã wire embedding + Qdrant upsert.
Search baseline chưa có.
```

Payload tối thiểu:

```json
{
  "chunk_id": "doc-7-chunk-31",
  "document_id": 7,
  "book_id": 101,
  "ebook_id": 55,
  "pageStart": 45,
  "pageEnd": 46,
  "chapter_index": 3,
  "chapter_title": "Chương 3",
  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1",
  "embedding_model": "gemini-embedding-2",
  "embedding_version": "gemini-embedding-2-3072-v1"
}
```

### A4 — Retrieval/search baseline

Mục tiêu:

```text
User query
  -> query embedding cùng model/version
  -> Qdrant search child chunks
  -> fetch chunks từ PostgreSQL
  -> trả text + citation/page/chapter
```

Ở A4 chưa cần parent-child. Chỉ cần search child chunks để có baseline.

### E1 — Evaluation nhỏ cho novel

Sau khi A4 chạy được, tạo một bộ câu hỏi nhỏ để test chunking:

```text
1. Câu hỏi chi tiết nằm trong một đoạn ngắn
2. Câu hỏi theo chương
3. Câu hỏi theo nhân vật
4. Câu hỏi cần context trước/sau
5. Câu hỏi hỏi sự kiện xuất hiện ở nhiều page
```

Mục tiêu của E1:

```text
Biết strategy hiện tại đủ tốt chưa.
Nếu retrieval child-only kém, mới quay lại D4-D7.
```

---

## 6. Việc cố ý để sau

Các việc dưới đây không làm ngay. Chúng nằm trong:

- [deferred-implementation.md](deferred-implementation.md)

### D4 — NovelBookReconstructor

Làm sau khi đã có:

```text
embedding + Qdrant + retrieval baseline
```

Mục tiêu:

```text
CleanedPage[]
  -> full book text liền mạch
  -> PageCharMapping
```

Tác dụng:

```text
Chunk có thể vượt qua page boundary nhưng vẫn citation đúng page.
```

### D5 — Chapter-wise chunking

Làm sau D4.

Mục tiêu:

```text
full book text
  -> ChapterSegment[]
  -> chunk trong boundary của chapter
```

Khác C6:

```text
C6 chỉ gắn metadata chapter.
D5 thật sự dùng chapter làm boundary chunking.
```

### D6 — Parent chunk records

Mục tiêu:

```text
child chunks dùng để embed/search
parent chunks dùng làm context lớn hơn cho LLM
```

MVP sau này có thể:

```text
embed child only
store parent in PostgreSQL
fetch parent khi answer cần context rộng
```

### D7 — Parent/neighbor retrieval expansion

Làm sau khi child-only search chạy được.

Flow:

```text
Qdrant search child chunks
  -> top_k child
  -> fetch parent_chunk_id hoặc neighbor chunks
  -> build expanded context
  -> LLM answer
```

### D8 — Novel retrieval evaluation

So sánh:

```text
child-only
child + neighbor
child + parent
chapter-wise
```

Chỉ khi evaluation cho thấy cần thiết mới tăng độ phức tạp.

### D9 — Semantic chunking

Không làm sớm.

Lý do:

```text
semantic chunking cần embedding trong ingestion
chậm hơn
tốn API hơn
phụ thuộc model embedding
khó debug nếu chưa có evaluation
```

### D10 — C7 MarkdownNodeParser cho textbook/technical

Để sau vì MVP đang tập trung novel.

Khi mở rộng sang textbook/technical:

```text
MarkdownNodeParser
  -> giữ heading/section_path
  -> fallback SentenceSplitter nếu section quá dài
```

---

## 7. Roadmap tổng hợp

### Phase 0 — Baseline chunking đã xong

```text
C1 document_profile + strategy metadata
C2 ChunkingStrategy adapter
C3 DocumentProfiler
C4 ChunkQualityValidator
C5 token_count
C6 ChapterDetector metadata
A1 S3 artifacts
```

Status:

```text
Done
```

### Phase 1 — Làm ngay tiếp theo để có RAG end-to-end

```text
A2 Embedding
A3 Qdrant upsert
A4 Retrieval/search baseline
E1 Evaluation nhỏ cho novel
```

Status:

```text
Next
```

### Phase 2 — Chunking novel nâng cao

```text
D4 NovelBookReconstructor
D5 Chapter-wise chunking
D6 Parent chunk records
D7 Parent/neighbor retrieval expansion
D8 Evaluation so sánh
```

Status:

```text
Deferred until retrieval baseline exists
```

### Phase 3 — Các loại sách khác

```text
D10 MarkdownNodeParser cho textbook/technical
table-aware chunking
Q/A pair chunking
math/formula-aware chunking
OCR/scanned PDF pipeline
```

Status:

```text
Deferred until novel path is stable
```

### Phase 4 — Semantic/chất lượng cao

```text
semantic chunking
character memory index
chapter summary index
event timeline index
theme index
```

Status:

```text
Research later, only after evaluation proves need
```

---

## 8. Khi nào được coi chunking MVP là xong?

Chunking MVP cho novel được coi là đủ khi:

```text
1. chunks có strategy/version/profile metadata
2. chunks có token_count
3. chunks có pageStart/pageEnd
4. chunks có chapter metadata nếu detect được
5. chunks lưu trong PostgreSQL
6. chunks.jsonl lưu trong rag-artifacts
7. chunk_quality_report có trong artifacts/metadata
8. embedding_text tạo được từ chunk + metadata
9. child chunks embed và upsert Qdrant được
10. search query trả về chunk đúng kèm citation
```

Hiện tại đã đạt 1-9.

Còn thiếu:

```text
10. search baseline
```

---

## 9. Không nên làm gì ngay

Không làm ngay:

```text
semantic chunking
parent-child hoàn chỉnh
table-aware chunking
MarkdownNodeParser cho textbook
OCR pipeline
character memory index
chapter summary index
```

Lý do:

```text
Chưa có retrieval baseline để đo improvement.
Làm sớm sẽ tăng complexity mà chưa biết có giúp gì không.
```

---

## 10. Quy tắc cập nhật file này

Mỗi khi làm xong một bước chunking/embedding ảnh hưởng chunking, cập nhật:

```text
1. Section 3 — Trạng thái hiện tại
2. Section 5 — Việc làm ngay tiếp theo
3. Section 7 — Roadmap tổng hợp
4. Section 8 — Điều kiện MVP
```

Nếu quyết định hoãn một việc:

```text
1. Ghi lý do vào Section 6
2. Nếu là backlog lớn, cập nhật thêm deferred-implementation.md
```

Nếu thêm strategy mới:

```text
1. Thêm code dưới app/ingestion/chunking/
2. Thêm test tương ứng trong tests/unit/
3. Cập nhật library-rag-chunking-strategy.md
4. Cập nhật file plan này
```

---

## 11. One-screen summary

```text
Hiện tại:
  Novel/narrative chunking v1 đã có.
  Chunks đã lưu PostgreSQL + S3 artifacts.
  EmbeddingTextBuilder/GeminiEmbeddingProvider/ChunkEmbeddingService đã có.
  Qdrant collection setup đã có.
  Qdrant vector upsert method đã có.
  Pipeline chính đã wire embedding + Qdrant upsert.
  Search/hybrid/evaluation runner đã có; xem implementation ledger.
  Fixtures/tests/snapshot/CLI cho chunking v1 đã được bổ sung.
  Chapter-aware/source-mapped v2 đã có, opt-in; mặc định giữ v1.
  Context expansion theo chương/section + source revision đã có.

Làm tiếp:
  Bước 4 benchmark PDF thật đã có; xem real-book-chunking-benchmark.md.
  Heading regression fix đã sửa v2 terminal-period/Appendix/front-matter.
  Xem heading-detection-regression-fix.md; v1 baseline không đổi.
  Ranking diagnostics + 20 Vietnamese query holdout đã có; xem
  retrieval-diagnostics-and-vi-query-holdout.md. Runtime ranking chưa đổi.
  HOLD v1: chọn/thử keyword policy trên development, thêm PDF Vietnamese và
  unseen-document/human review trước khi promote.

Sau đó mới quay lại:
  D4 NovelBookReconstructor tổng quát (v2 hiện chỉ ghép phạm vi chương đã nhận diện)
  D5 Mở rộng chapter-wise chunking/hierarchy (v2 boundary cơ bản đã có)
  D6 Parent chunks
  D7 Parent expansion (chapter-safe neighbor expansion đã có ở bước 3)

Để sau nữa:
  MarkdownNodeParser cho textbook
  table-aware
  semantic chunking
  OCR/scanned PDF
```
