# Documentation Map

Implementation cập nhật 2026-10-05:
[Retrieval, evaluation và graph theo ebook](retrieval-evaluation-implementation.md).
Các checklist trong guide tổng quát không thay thế trạng thái implementation này.

Giải thích từng phần theo code: [Ghi chú học retrieval — bắt đầu BM25](retrieval-learning-notes.md).
Tài liệu này theo dõi sáu chủ đề, nội dung đã giải thích và kết quả kiểm tra.

Sơ đồ đầy đủ theo code: [Full flow RAG trong Library](rag-full-flow.md), gồm
upload/index, retrieval, answer/citation và các flow graph/evaluation chạy riêng.

Checkpoint chunking: [Baseline v1 và kế hoạch sửa ranh giới trang/chương](chunking/chunking-baseline-and-boundary-plan.md).
Bước 2: [Chapter-aware v2 và source mapping](chunking/chapter-aware-v2.md),
opt-in; default v1 và dữ liệu đã index chưa thay đổi.
Bước 3: [Context expansion theo chương và source revision](chunking/chapter-aware-context-expansion.md),
giữ citation từng chunk và không qua boundary/gap; chưa deploy/reindex.
Bước 4: [Benchmark PDF thật và promotion gates](chunking/real-book-chunking-benchmark.md),
source labels chung cho v1/v2; HOLD default v1, chưa reindex/deploy.
[Follow-up heading fix](chunking/heading-detection-regression-fix.md): sửa
v2, 491 tests pass, rerun cùng labels; chapter gate pass, retrieval/held-out HOLD.
[Ranking diagnostics và query holdout](chunking/retrieval-diagnostics-and-vi-query-holdout.md)
thêm giải thích BM25/cosine và 20 câu tiếng Việt với evidence mới trên PDF
English cũ; không nhầm query-level holdout với kiểm chứng sách tiếng Việt.

Đây là bản đồ tổng cho folder `docs/`. Tài liệu đã được chia theo từng vùng của
pipeline để dễ đọc và dễ mở rộng.

---

## 0. Product direction

Hướng sản phẩm đang được ưu tiên là
[Secure AI Ebook Reader](../../docs/secure-ai-ebook-reader-roadmap.md): hỏi đáp
trong một ebook được cấp quyền, có citation theo trang và abstain khi không đủ
bằng chứng.

Các tài liệu GraphRAG, multi-book research, recommendation và learning assistant
là backlog sau MVP. Endpoint retrieval trả evidence chunks; generation MVP đã
được nối qua `/internal/answers` với citation allow-list và abstention.

---

## 1. Platform

Folder:

```text
docs/platform/
```

Nội dung:

- kiến trúc tổng quan;
- deployment;
- Docker/production notes.

Đọc khi muốn hiểu hệ thống RAG service ở mức tổng thể.

---

## 2. Integration

Folder:

```text
docs/integration/
```

Nội dung:

- contract Spring Boot -> RAG;
- internal API key/security;
- upload PDF vào SeaweedFS/S3-compatible storage.

Đọc khi làm phần kết nối Library service với RAG service.

---

## 3. Ingestion

Folder:

```text
docs/ingestion/
```

Nội dung:

- flow ingest PDF;
- SeaweedFS + PostgreSQL upload flow;
- object key/bucket/status metadata.

Đọc khi muốn hiểu worker RAG nhận job và xử lý file từ S3 như thế nào.

---

## 4. Parsing & Cleaning

Folder:

```text
docs/parsing-cleaning/
```

Nội dung:

- parser architecture;
- PDF processing assets;
- cleaning overview;
- cleaning priority;
- cleaning stepwise implementation plan.

Đọc khi làm phần:

```text
PDF
  -> raw text/markdown pages
  -> cleaned pages
```

---

## 5. Chunking

Folder:

```text
docs/chunking/
```

Nội dung:

- lý thuyết chunking tổng quan;
- strategy riêng cho Library RAG;
- strategy riêng cho tiểu thuyết/narrative book;
- ưu tiên `library_pdf_narrative_v1` cho tiểu thuyết trước;
- roadmap mở rộng sang textbook/table/code/math/OCR.

Đọc khi làm phần:

```text
cleaned pages
  -> LlamaIndex Documents
  -> Nodes
  -> Chunks
```

---

## 6. Embedding

Folder:

```text
docs/embedding/
```

Nội dung:

- quyết định chọn Gemini Embedding làm hướng chính;
- model dự kiến `gemini-embedding-2`;
- fallback text-only `gemini-embedding-001`;
- task/text format cho Gemini retrieval embedding;
- khác biệt giữa embedding model, VectorStore/Qdrant và VectorStoreIndex;
- implementation plan A2 trước khi upsert Qdrant.

Đọc khi làm phần:

```text
chunks
  -> embedding_text
  -> vector
```

---

## 7. Indexing / Vector database

Folder:

```text
docs/indexing/
```

Nội dung:

- Qdrant collection strategy;
- deterministic point ID;
- Qdrant payload contract;
- metadata/permission filtering strategy;
- payload indexes;
- common failures và cách áp dụng vào project.

Đọc khi làm phần:

```text
vectors + chunk payload
  -> Qdrant points
  -> semantic search/filter
```

---

## 8. Reference

Folder:

```text
docs/reference/
```

Nội dung:

- API reference;
- schema;
- metadata structure reference.

Đọc khi cần tra cứu field/contract/schema.

---

## 9. Advanced RAG Production Guide

Folder:

```text
docs/advanced-rag-production-guide/
```

Đây là bộ tài liệu học production-grade RAG dài hạn. Nó vẫn giữ nguyên dạng
guide nhiều chương, còn các tài liệu project-specific đã được tách ra các folder
ở trên.

Lưu ý:

```text
Chương chunking đã được tách sang docs/chunking/04-chunking-strategy.md
```

---

## Thứ tự đọc đề xuất cho đồ án hiện tại

```text
0. ../docs/secure-ai-ebook-reader-roadmap.md
1. docs/platform/README.md
2. docs/integration/README.md
3. docs/ingestion/README.md
4. docs/parsing-cleaning/README.md
5. docs/chunking/README.md
6. docs/embedding/README.md
7. docs/indexing/README.md
8. docs/reference/README.md
```
