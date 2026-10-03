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
- embedding + Qdrant indexing.

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
A2: embedding provider
A3: Qdrant upsert
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
