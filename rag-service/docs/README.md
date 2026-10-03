# Documentation Map

Đây là bản đồ tổng cho folder `docs/`. Tài liệu đã được chia theo từng vùng của
pipeline để dễ đọc và dễ mở rộng.

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
1. docs/platform/README.md
2. docs/integration/README.md
3. docs/ingestion/README.md
4. docs/parsing-cleaning/README.md
5. docs/chunking/README.md
6. docs/embedding/README.md
7. docs/indexing/README.md
8. docs/reference/README.md
```
