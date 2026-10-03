# Deferred Chunking Implementation

File này là nơi ghi riêng các việc **chưa làm ngay** nhưng cần quay lại implement
sau, để không quên khi pipeline RAG đã đi xa hơn.

Nguyên tắc:

```text
Không implement parent-child / chapter-wise / semantic chunking quá sớm.
Chỉ quay lại khi hệ thống đã có embedding + Qdrant + retrieval cơ bản.
```

---

## 1. Trạng thái hiện tại

Đã có:

```text
C1 document_profile + chunking_strategy/version
C2 ChunkingStrategy adapter bằng LlamaIndex
C3 DocumentProfiler v1
C4 ChunkQualityValidator
C5 token_count + token stats
C6 ChapterDetector metadata MVP
```

Flow hiện tại:

```text
ParsedDocument page-wise
  -> LlamaIndex Document page-wise
  -> PdfCleaningTransformation
  -> DocumentProfiler
  -> ChapterDetector
  -> LlamaIndex SentenceSplitter
  -> child chunks
  -> PostgreSQL document_chunks
```

C6 hiện chỉ làm:

```text
Detect chapter heading ở vài dòng đầu page
Carry-forward chapter metadata sang page sau
Chunk vẫn là page-wise SentenceSplitter
```

C6 chưa làm:

```text
Reconstruct full book text
Split/chunk theo chapter boundary thật sự
Parent-child chunks
Neighbor expansion khi retrieval
```

---

## 2. Điều kiện để quay lại làm phần nâng cao

Chỉ quay lại implement các phần bên dưới khi đã có đủ:

```text
1. Embedding provider đã chọn
2. Child chunks đã embed được
3. Qdrant upsert chạy được
4. Retrieval/search API cơ bản chạy được
5. Có vài câu hỏi test cho sách tiểu thuyết
```

Lý do:

```text
Parent-child/chapter-wise không chỉ là ingestion.
Nó ảnh hưởng trực tiếp tới retrieval, context expansion và cách LLM nhận context.
```

Nếu chưa search được trong Qdrant thì làm parent-child sớm sẽ khó biết parent
chunk nên lớn bao nhiêu, expand bao nhiêu neighbor chunk, và có thật sự cải thiện
answer hay không.

---

## 3. Deferred tasks cần làm sau

### D1 — Embedding provider

Decision hiện tại đã được tách sang:

```text
docs/embedding/README.md
```

Mục tiêu:

```text
document_chunks
  -> build embedding_text
  -> call embedding model
  -> lưu embedding status
```

Cần triển khai theo quyết định hiện tại:

```text
Gemini embedding provider
gemini-embedding-2 primary model
gemini-embedding-001 fallback text-only model nếu cần batch chunks độc lập
embedding dimension / Qdrant vector size
rate limit + retry policy
```

Local BGE/E5 chỉ giữ như phương án fallback nếu không muốn gọi API Gemini.

---

### D2 — Qdrant upsert

Mục tiêu:

```text
child chunks
  -> vector points trong Qdrant
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
  "chunking_strategy_version": "v1"
}
```

---

### D3 — Retrieval/search API cơ bản

Mục tiêu:

```text
User query
  -> embed query
  -> search Qdrant
  -> fetch chunks từ PostgreSQL
  -> trả kết quả/citation
```

Chưa cần parent-child ở bước này. Cần search child chunk trước để có baseline.

---

### D4 — NovelBookReconstructor

Chỉ làm sau D1-D3.

Mục tiêu:

```text
CleanedPage[]
  -> ReconstructedBookText
  -> PageCharMapping
```

Ý nghĩa:

```text
Ghép text nhiều page thành mạch sách liền nhau
nhưng vẫn biết char range nào thuộc page nào
```

Đây là nền tảng để chunk vượt qua page boundary mà vẫn giữ citation đúng.

---

### D5 — Chapter-wise chunking

Chỉ làm sau khi có `NovelBookReconstructor`.

Mục tiêu:

```text
ReconstructedBookText
  -> ChapterSegment[]
  -> LlamaIndex Document theo chapter/section
  -> SentenceSplitter / TokenTextSplitter
  -> child chunks
```

Khác với C6 MVP:

```text
C6 hiện tại:
  page-wise chunking + chapter metadata

D5 sau này:
  chapter-wise input boundary thật sự
```

---

### D6 — Parent chunk records

Mục tiêu:

```text
Parent chunk: context lớn / cảnh / cụm paragraph
Child chunk: context nhỏ để embed/search
```

Metadata child:

```json
{
  "chunk_level": "child",
  "parent_chunk_id": "doc-7-parent-12"
}
```

Metadata parent:

```json
{
  "chunk_level": "parent",
  "child_chunk_ids": ["doc-7-child-31", "doc-7-child-32"]
}
```

MVP có thể chỉ embed child, parent lưu PostgreSQL để fetch khi cần.

---

### D7 — Parent/neighbor retrieval expansion

Chỉ làm sau khi search child chunk đã chạy được.

Flow:

```text
Search child chunks trong Qdrant
  -> lấy top_k child chunks
  -> fetch parent_chunk_id nếu có
  -> lấy thêm neighbor chunks trước/sau nếu cần
  -> đưa context mở rộng vào LLM
```

Config cần thử:

```text
retrieval_top_k = 20
rerank_top_k = 5 hoặc 8
neighbor_window = 1
```

Chỉ expand trong cùng:

```text
document_id
chapter_index
permission scope
```

---

### D8 — Evaluation cho novel retrieval

Mục tiêu:

```text
So sánh:
  child-only retrieval
  child + neighbor expansion
  child + parent expansion
  chapter-wise chunks
```

Cần tạo bộ câu hỏi nhỏ:

```text
câu hỏi theo chi tiết page/chapter
câu hỏi theo nhân vật
câu hỏi theo sự kiện
câu hỏi cần bối cảnh trước/sau
```

Chỉ khi evaluation cho thấy cần thiết mới tăng độ phức tạp.

---

### D9 — Semantic chunking

Không làm trước D1-D8.

Lý do:

```text
Semantic chunking cần embedding ngay trong lúc ingestion
chậm hơn
tốn compute hơn
khó debug hơn
phụ thuộc mạnh vào embedding model
```

Chỉ thử khi:

```text
Sentence/chapter/parent-child vẫn retrieval kém
và đã có evaluation để đo improvement
```

---

### D10 — C7 MarkdownNodeParser cho textbook/technical

Đây là C7 đã tạm hoãn.

Mục tiêu:

```text
PDF có Markdown heading tốt
  -> LlamaIndex MarkdownNodeParser
  -> giữ section_path/heading metadata
  -> nếu section quá dài thì split tiếp bằng SentenceSplitter
```

Áp dụng cho:

```text
textbook
technical book
sách lập trình
tài liệu có # / ## / ### rõ sau parse
```

Chưa làm ngay vì MVP hiện tại ưu tiên:

```text
novel/narrative
  -> artifacts
  -> embedding
  -> Qdrant
  -> retrieval baseline
```

Khi quay lại D10, cần implement:

```text
1. MarkdownHeadingChunkingStrategy
2. strategy selector cho profile textbook/technical
3. section_path metadata
4. fallback SentenceSplitter cho node quá dài
5. tests với Markdown heading
```

---

## 4. Thứ tự quay lại implement

Thứ tự đề xuất:

```text
D1 Embedding provider
D2 Qdrant upsert
D3 Retrieval/search API cơ bản
D8 Evaluation nhỏ cho novel
D4 NovelBookReconstructor
D5 Chapter-wise chunking
D6 Parent chunk records
D7 Parent/neighbor retrieval expansion
D9 Semantic chunking nếu thật sự cần
D10 C7 MarkdownNodeParser cho textbook/technical
```

Nếu muốn làm nhanh nhất để có demo:

```text
D1 -> D2 -> D3
```

Nếu muốn nâng chất lượng retrieval cho tiểu thuyết:

```text
D4 -> D5 -> D6 -> D7 -> D8
```

---

## 5. Reminder quan trọng

Khi đã làm xong:

```text
embedding
Qdrant upsert
retrieval/search API
```

thì quay lại file này và bắt đầu từ:

```text
D4 NovelBookReconstructor
```

Đây là điểm bắt đầu cho bản chunking tiểu thuyết nâng cao hơn C6 MVP.
