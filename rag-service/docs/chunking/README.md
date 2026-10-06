# Chunking Documentation

Folder này gom riêng các tài liệu liên quan đến chunking để tránh bị lẫn với
parsing/cleaning/embedding.

Chunking là bước nằm giữa:

```text
Parse/Clean
  -> Chunking
  -> Embedding
  -> Vector DB / Qdrant
```

Trong LlamaIndex, chunk thường được gọi là `Node`. Vì vậy khi đọc code, bạn sẽ
thấy luồng:

```text
LlamaIndex Document
  -> NodeParser / TextSplitter
  -> LlamaIndex Node
  -> internal Chunk
  -> document_chunks trong PostgreSQL
```

---

## Thứ tự đọc đề xuất

### 0. Checkpoint hiện tại

Checkpoint cập nhật 2026-10-06:
[Baseline v1 và kế hoạch sửa ranh giới trang/chương](chunking-baseline-and-boundary-plan.md).
Bước 1 thêm fixtures/tests/snapshot/CLI; bước 2 thêm
[chapter-aware/source-mapped v2](chapter-aware-v2.md) opt-in.
Mặc định vẫn v1; bước 3 có
[context expansion theo chương/phiên bản](chapter-aware-context-expansion.md).
Bước 4 có [benchmark PDF thật](real-book-chunking-benchmark.md), 20 questions
trên 135 trang. [Heading regression fix](heading-detection-regression-fix.md)
đã sửa terminal-period/Appendix/front-matter của v2 và rerun cùng labels.
HOLD v1 vì retrieval regressions và thiếu Vietnamese/held-out review.
[Ranking diagnostics và Vietnamese query holdout](retrieval-diagnostics-and-vi-query-holdout.md)
giải thích từng term/score và thêm câu hỏi Việt mới trên PDF English, không
nhầm query-level holdout với corpus sách Việt hoặc unseen-book validation.
[Hybrid ranking trace](hybrid-ranking-trace.md) đã theo dõi evidence qua RRF,
reranker và cutoff, tìm thấy hai v2 failures bắt đầu tụt hạng ở RRF. Policy
chưa đổi; trace có/không bật giữ response và baseline metrics bằng nhau.
[Policy comparison](hybrid-policy-comparison.md) đã chạy cùng corpus với 5
policies/controls: có gains và regressions, chưa chọn thay runtime baseline.
[Lexical agreement](lexical-agreement-diagnostics.md) đã chẩn đoán 80 cases đến
từng term, cả score=0 evidence. Counterexamples bác bỏ title-only/coverage-only
như một rule chắc chắn. [Adaptive experiment v2](adaptive-hybrid-experiment-v2.md)
đã chạy 560 cases với formulas/gates khóa trước run; macro gains nhưng Magi
regressions vẫn giữ HOLD.
[Vietnamese-source first evaluation](vietnamese-source-evaluation-v1.md) đã
thêm báo cáo tiếng Việt 32 trang + 20 câu, chạy 40 BM25 cases + exact replay.
Chủ đồ án đã duyệt [phiếu câu hỏi/heading](vietnamese-source-review-v1.md) ngày
2026-10-06, biên bản theo hash lưu riêng sau first scoring.
[Bounded real embedding baseline](vietnamese-source-embedding-baseline-v1.md) đã
giữ 96 vectors từ lượt lỗi, bổ sung đủ 120 missing inputs theo quyền riêng và
token-aware pacing. Cache đủ 216 vectors, chấm 120 BM25/Dense/Hybrid cases,
0 errors; Hybrid Recall@3 v1/v2 là 92,5%/90%. Section/ranking regressions vẫn
giữ HOLD v1; không scoring adaptive formulas hoặc runtime changes.
[Chẩn đoán Vietnamese baseline](vietnamese-baseline-diagnostics-v1.md) đã trace
40 case-version pairs + 120 exact replay cases, 0 provider calls. 23/23 anchors
có full single-chunk source ở mỗi chunker; v2 chỉ nhận 2/7 headings. Phân biệt
section gaps, candidate ranking và top-3/context cutoffs trước khi sửa v3 riêng.
Trạng thái embedding/retrieval/evaluation mới nhất nằm ở
[implementation ledger](../retrieval-evaluation-implementation.md).

Đọc đầu tiên nếu muốn biết đang làm tới đâu:

```text
implementation-plan.md
```

File này ghi:

- việc chunking đã làm xong;
- việc làm ngay tiếp theo;
- việc cố ý để sau;
- link tới code/test/doc liên quan;
- thứ tự tiếp tục nếu bị mất context.

### 1. Lý thuyết tổng quan

Đọc trước:

```text
04-chunking-strategy.md
```

File này giải thích đầy đủ:

- chunk là gì;
- vì sao chunking ảnh hưởng retrieval;
- chunk size và overlap;
- sentence/paragraph/heading-aware chunking;
- semantic chunking;
- parent-child chunking;
- metadata-aware chunking;
- chunk ID/hash/version;
- quality check và evaluation.

### 2. Strategy riêng cho hệ thống thư viện

Đọc sau:

```text
library-rag-chunking-strategy.md
```

File này map lý thuyết vào đồ án hiện tại:

- hệ thống hiện đang dùng LlamaIndex `SentenceSplitter`;
- strategy đầu tiên là `library_pdf_narrative_v1`;
- ưu tiên làm sách tiểu thuyết/narrative trước;
- taxonomy các loại sách trong thư viện;
- roadmap mở rộng sang textbook, technical book, table-heavy, Q/A, math, OCR;
- plan implement từng bước C1 -> C10.

### 3. Strategy riêng cho tiểu thuyết

Đọc khi bắt đầu implement nhóm sách đầu tiên:

```text
novel-chunking-strategy.md
```

File này đi sâu vào:

- recursive paragraph/chapter-aware chunking;
- parent-child chunking;
- child chunk dùng để embedding/search;
- parent chunk dùng để mở rộng context cho LLM;
- metadata riêng cho tiểu thuyết;
- roadmap N1 -> N6.

### 4. Các việc cố ý để sau

Đọc khi đã làm xong embedding/Qdrant/retrieval cơ bản:

```text
deferred-implementation.md
```

File này ghi riêng các việc không làm ngay để khỏi quên:

- `NovelBookReconstructor`;
- chapter-wise chunking thật sự;
- parent chunk records;
- parent/neighbor retrieval expansion;
- semantic chunking;
- evaluation cho novel retrieval.

---

## Trạng thái hiện tại trong code

Hiện pipeline chính đã có baseline chunking:

```text
ParsedDocument page-wise
  -> LlamaIndex Document
  -> PdfCleaningTransformation
  -> LlamaIndex SentenceSplitter
  -> LlamaIndex Node
  -> internal Chunk
  -> PostgreSQL document_chunks
```

File code liên quan:

```text
app/ingestion/llama_index/node_adapter.py
app/ingestion/pipeline.py
app/documents/models.py
```

Điểm còn thiếu trước khi coi là strategy hoàn chỉnh:

- `document_profile`; đã có C1/C3 v1;
- `chunking_strategy`; đã có C1/C2 v1;
- `chunking_strategy_version`; đã có C1/C2 v1;
- `DocumentProfiler`; đã có C3 v1;
- chunk quality validation; đã có C4 v1;
- token count; đã có C5 v1 bằng `ApproxTokenCounter`;
- chapter metadata cho tiểu thuyết; đã có C6 v1 bằng rule-based `ChapterDetector`;
- artifacts trong `rag-artifacts`; đã có A1 foundation gồm parsed/cleaned/chunks/manifest;
- embedding + Qdrant indexing đã có; xem implementation ledger cho kết quả kiểm chứng.

---

## Roadmap gần nhất

Không làm tất cả cùng lúc. Làm theo thứ tự:

```text
C1: thêm document_profile + chunking_strategy/version cho tiểu thuyết [done]
C2: tách ChunkingStrategy adapter bằng LlamaIndex [done]
C3: thêm DocumentProfiler v1 tối giản [done]
C4: thêm chunk quality validation [done]
C5: thêm token count [done]
C6: thêm chapter metadata cho tiểu thuyết [done]
A1: lưu parsed/cleaned/chunks artifacts vào `rag-artifacts` [done]
A2: embedding provider [done]
A3: Qdrant upsert [done]

2026-10-05:
  Bước 1 baseline v1 fixtures/tests/snapshot/CLI [implemented]
  Bước 2 chapter-aware/source-mapped v2 [implemented, opt-in]
  Bước 3 chapter/revision-aware context expansion [implemented]
  Bước 4 real-book/retrieval benchmark [implemented; HOLD default v1]
  Heading fixes + ranking diagnostics/query holdout [implemented]
  Hybrid rank trace [implemented, scoring unchanged]
  Fixed policy comparison [implemented, no candidate passed all gates]
  Lexical-agreement diagnosis [80 cases, descriptive only, no scoring change]
  Adaptive experiment v2 [560 cases, no candidate passed all gates]
  Vietnamese-source first evaluation [40 BM25 cases, exact replay, source/labels sealed]
  Owner review [approved after first scoring, separate version/hash receipt]
  Bounded embeddings first attempt [112 submitted, 96 cached; stopped rate/quota]
  Token-aware completion [120 missing submitted, 216 cached; 120 cases, 0 errors]
  Vietnamese baseline diagnosis [40 pairs, 120 exact replay cases, provider calls=0]
  Tiếp theo: isolated section-detection v3 experiment; stop known-case tuning
```

Các việc nâng cao đã cố ý để sau được ghi riêng tại:

```text
deferred-implementation.md
```

Với riêng tiểu thuyết, xem chi tiết hơn trong:

```text
novel-chunking-strategy.md
```

Sau khi narrative strategy ổn định, mới mở rộng:

```text
textbook heading-aware
technical/code-aware
Q/A pair
table-aware
math/formula-aware
OCR/scanned PDF
```

Trong đó `C7 MarkdownNodeParser cho textbook/technical` đã được đưa vào backlog
làm sau, vì MVP hiện tại đang ưu tiên hoàn tất artifact -> embedding -> Qdrant
cho novel/narrative trước.
