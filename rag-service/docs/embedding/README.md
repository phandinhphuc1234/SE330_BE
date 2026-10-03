# Embedding Strategy

Folder này ghi riêng phần **embedding/vectorization** của hệ thống Library RAG.

Embedding nằm sau chunking và trước Qdrant:

```text
chunks trong PostgreSQL
  -> build embedding_text
  -> Gemini embedding model
  -> vector
  -> Qdrant upsert
```

---

## 1. Quyết định hiện tại

Chọn Gemini Embedding làm hướng chính.

Provider:

```text
gemini
```

Model chính:

```text
gemini-embedding-2
```

Fallback text-only:

```text
gemini-embedding-001
```

Lý do đổi sang Gemini:

- chất lượng embedding mạnh hơn hướng local nhỏ như `intfloat/multilingual-e5-small`;
- hỗ trợ multilingual tốt, phù hợp thư viện tiếng Việt + tiếng Anh;
- `GEMINI_API_KEY` đã có sẵn trong config project;
- không cần tự vận hành model local/Torch trên VPS;
- dễ scale chất lượng hơn ở giai đoạn MVP.

Nguồn chính thức Google Gemini API nói Gemini API có embedding models cho semantic
search/classification/clustering; `gemini-embedding-2` là model mới, còn
`gemini-embedding-001` vẫn có cho text-only use case.

---

## 2. Không nhầm giữa Embedding, VectorStore và VectorStoreIndex

Ba thứ này khác nhau:

```text
Embedding model
  -> biến text thành vector

VectorStore / Qdrant
  -> nơi lưu và search vector

VectorStoreIndex của LlamaIndex
  -> abstraction cao hơn, có thể tự quản lý embed + upsert + query
```

Trong project này, quyết định hiện tại là:

```text
Dùng Gemini API để tạo vector.
Không để VectorStoreIndex quản lý toàn bộ ingestion/indexing ở MVP.
```

Tức là:

```text
RAG worker vẫn điều phối:
  job status
  PostgreSQL chunks
  S3 artifacts
  manifest
  retry/error handling
  Qdrant payload

Gemini embedding provider chỉ làm:
  text -> vector
```

Sau này có thể cân nhắc dùng `QdrantVectorStore` của LlamaIndex ở bước upsert,
nhưng vẫn không bắt buộc dùng `VectorStoreIndex.from_documents(...)` làm trung
tâm của hệ thống.

---

## 3. Vì sao không dùng full `VectorStoreIndex.from_documents()` ngay?

Flow full-auto của LlamaIndex thường là:

```text
Documents
  -> VectorStoreIndex.from_documents(...)
  -> LlamaIndex tự chunk/embed/upsert/query
```

Flow này rất nhanh cho demo, nhưng hệ thống của mình cần kiểm soát rõ:

```text
Spring Boot contract
Celery job status
PDF validation
PostgreSQL document_chunks
rag-artifacts manifest
chunk_quality_report
book_id / ebook_id / permission metadata
retry/callback
```

Vì vậy MVP chọn flow:

```text
RAG worker owns workflow
LlamaIndex owns document/text transformations
Gemini provider owns text -> vector
Qdrant owns vector search
```

---

## 4. Gemini prompt/task format

Gemini Embedding 2 không dùng prefix E5 kiểu:

```text
query:
passage:
```

Với Gemini Embedding 2, Google khuyến nghị format theo task instruction trong
text, ví dụ retrieval/search:

Query:

```text
task: search result | query: nhân vật chính gặp ai ở chương 1?
```

Document/chunk:

```text
title: <book title hoặc none> | text: <chunk content>
```

Với question answering:

```text
task: question answering | query: <user question>
title: <book title hoặc none> | text: <chunk content>
```

MVP của hệ thống Library RAG nên bắt đầu với:

```text
query task = search result
document format = title: {title_or_none} | text: {embedding_text}
```

Sau này nếu truy vấn là hỏi đáp trực tiếp nhiều hơn search, có thể thử:

```text
task: question answering | query: ...
```

và so sánh bằng evaluation.

---

## 5. Embedding text không nhất thiết giống chunk content

`document_chunks.content` nên lưu text sạch gốc để citation/debug:

```text
Minh bước vào thư viện khi trời vừa tối...
```

Nhưng text đem đi embedding nên thêm context nhẹ:

```text
Book: <book title nếu có>
Chapter: Chương 1
Page: 12

Minh bước vào thư viện khi trời vừa tối...
```

Sau đó Gemini document wrapper sẽ biến thành:

```text
title: <book title hoặc none> | text: Book: ...
```

Không nên đưa metadata kỹ thuật vào embedding text:

```text
document_id
vector_id
chunk_hash
timestamp
internal job id
```

Nên đưa metadata semantic/citation:

```text
book title
chapter title
section path
page range
chunk content
```

---

## 6. Config đề xuất

`.env` dự kiến:

```env
EMBEDDING_PROVIDER=gemini
EMBEDDING_MODEL=gemini-embedding-2
EMBEDDING_DIM=3072
EMBEDDING_FALLBACK_MODEL=gemini-embedding-001
EMBEDDING_TEXT_POLICY=gemini_search_title_text_v1
EMBEDDING_BATCH_SIZE=1
GEMINI_API_KEY=...
```

Ghi chú:

```text
Nếu dùng gemini-embedding-2, MVP nên để batch size = 1.
Lý do: mỗi chunk phải sinh ra một vector riêng.
Nếu cần embed nhiều chunk độc lập theo list đơn giản, gemini-embedding-001 có thể tiện hơn.
```

Khi đổi model embedding, phải kiểm tra lại:

```text
embedding dimension
Qdrant collection vector size
payload metadata
retrieval evaluation
```

---

## 7. Lưu ý quan trọng về batch/chunks

Với RAG, mỗi chunk cần có một vector riêng:

```text
chunk_1 -> vector_1
chunk_2 -> vector_2
chunk_3 -> vector_3
```

Theo docs Gemini API:

```text
gemini-embedding-001 hỗ trợ tạo individual embeddings cho list strings.
gemini-embedding-2 khi truyền nhiều input có thể tạo một aggregated embedding.
```

Vì vậy khi implement cần chọn một trong hai hướng:

### Option A — Dùng `gemini-embedding-2` mạnh hơn

Embed từng chunk riêng hoặc dùng Batch API đúng cách:

```text
for each chunk:
  call embed_content(chunk)
```

Ưu điểm:

```text
model mới/mạnh hơn
```

Nhược điểm:

```text
nhiều API calls nếu không dùng Batch API
cần rate limit/retry cẩn thận
```

### Option B — Dùng `gemini-embedding-001` cho MVP indexing

Dùng list chunks để lấy nhiều vectors độc lập:

```text
chunks[] -> embeddings[]
```

Ưu điểm:

```text
dễ implement batch MVP
có task_type RETRIEVAL_DOCUMENT / RETRIEVAL_QUERY
```

Nhược điểm:

```text
không phải model mới nhất
```

Khuyến nghị implementation:

```text
Provider mặc định config là gemini-embedding-2.
Mỗi chunk được bọc trong một Gemini Content riêng để giữ một vector độc lập.
Provider gửi theo batch cấu hình bằng EMBEDDING_BATCH_SIZE (mặc định 8).
Code provider vẫn có fallback/config để dùng gemini-embedding-001 khi cần.
```

---

## 8. Giới hạn Gemini Embedding cần nhớ

Nguồn chính thức:

- Embeddings: <https://ai.google.dev/gemini-api/docs/embeddings>
- Rate limits: <https://ai.google.dev/gemini-api/docs/rate-limits>

### 8.1. Input token limit

Theo Gemini Embeddings docs:

```text
gemini-embedding-2:
  input token limit = 8192 tokens / request

gemini-embedding-001:
  input token limit = 2048 tokens / request
```

Ý nghĩa với hệ thống Library RAG:

```text
Không nên tạo chunk quá dài.
Chunk cho tiểu thuyết/text narrative nên nằm khoảng 512-1024 tokens ở giai đoạn đầu.
```

Nếu chunk quá dài:

- embedding có thể fail vì vượt token limit;
- search semantic kém chính xác hơn vì một vector phải đại diện quá nhiều ý;
- chi phí/quota tăng nhanh hơn.

### 8.2. Output dimension

Theo Gemini Embeddings docs, `gemini-embedding-2` hỗ trợ output dimension:

```text
128 -> 3072
```

Các kích thước Google khuyến nghị:

```text
768
1536
3072
```

Quyết định hiện tại của project:

```text
EMBEDDING_DIM=3072
```

Vì vậy Qdrant collection phải được tạo với:

```text
vector size = 3072
```

Nếu sau này đổi `EMBEDDING_DIM`, bắt buộc phải:

- tạo Qdrant collection mới; hoặc
- xóa/recreate collection cũ; hoặc
- re-index toàn bộ chunks bằng dimension mới.

Không được trộn vector 3072 chiều với collection 1536/768 chiều.

### 8.3. Rate limit / quota

Gemini API rate limit không có một con số cố định cho mọi project.
Nó phụ thuộc Google Cloud project và usage tier.

Google đo rate limit chủ yếu bằng:

```text
RPM = requests per minute
TPM = input tokens per minute
RPD = requests per day
```

Rate limit áp dụng theo:

```text
Google Cloud project
```

không phải chỉ theo từng API key.

Nếu vượt giới hạn, API thường trả:

```text
429 RESOURCE_EXHAUSTED
```

Cách xem giới hạn thật của project:

```text
Google AI Studio -> Rate limits
```

Ý nghĩa với implementation:

- provider phải có retry/backoff cho lỗi 429;
- worker không nên spawn quá nhiều embedding task cùng lúc;
- cần log provider/model/token/dimension để debug;
- sau MVP mới tối ưu batch/concurrency.

### 8.4. Batch limit trong RAG

Với RAG, yêu cầu đúng là:

```text
chunk_1 -> vector_1
chunk_2 -> vector_2
chunk_3 -> vector_3
```

Nhưng Gemini Embedding 2 có lưu ý: khi truyền nhiều input, model có thể tạo
embedding tổng hợp cho nhiều input.

Vì vậy rule MVP:

```text
gemini-embedding-2:
  EMBEDDING_BATCH_SIZE=1
```

Sau khi hệ thống chạy ổn, có thể nghiên cứu tiếp:

- Batch API của Gemini;
- fallback `gemini-embedding-001` cho list strings;
- rate limiter theo RPM/TPM;
- embedding cache để tránh gọi lại với chunk không đổi.

---

## 9. Module đề xuất

```text
app/indexing/
  embedding_provider.py              # interface đã có
  embedding_text_builder.py          # build query/document embedding text
  providers/
    gemini_embedding_provider.py     # provider chính
  qdrant_store.py                    # bước A3
  indexer.py
```

Provider sẽ dùng Google GenAI SDK hoặc LlamaIndex Gemini embedding wrapper nếu
phù hợp ở thời điểm implement.

Interface nội bộ vẫn nên giữ:

```python
class EmbeddingProvider:
    async def embed(self, texts: list[str]) -> list[list[float]]:
        ...
```

Lý do: nếu sau này đổi sang OpenAI/BGE/E5 thì pipeline không phải đổi.

---

## 10. Implementation plan A2

### A2.1 — Cài dependency Gemini embedding

Dự kiến một trong hai hướng:

```text
google-genai
```

hoặc nếu LlamaIndex wrapper ổn:

```text
llama-index-embeddings-google-genai
```

Chọn package ở lúc implement dựa trên API ổn định nhất với Gemini Embedding.

---

### A2.2 — Thêm config embedding

Thêm settings:

```text
embedding_provider
embedding_model
embedding_fallback_model
embedding_text_policy
embedding_batch_size
gemini_api_key
```

---

### A2.3 — Implement `EmbeddingTextBuilder`

Nhiệm vụ:

```text
Chunk + metadata
  -> Gemini document text
```

Output ví dụ:

```text
title: <book title hoặc none> | text: Book: <title>
Chapter: Chương 1
Page: 12-13

<chunk content>
```

Query text:

```text
task: search result | query: <user query>
```

---

### A2.4 — Implement `GeminiEmbeddingProvider`

Nhiệm vụ:

```text
texts[]
  -> Gemini embedding API
  -> vectors[][]
```

Yêu cầu:

- đảm bảo mỗi chunk có một vector riêng;
- batch/rate-limit/retry rõ ràng;
- validate dimension;
- log model/provider rõ ràng;
- test bằng fake/mock để unit test không gọi API thật.
- xử lý lỗi 429 bằng retry/backoff nhẹ.

---

### A2.5 — Gắn embedding metadata vào chunks

Sau khi embed thành công, metadata chunk nên có:

```json
{
  "embedding_status": "embedded",
  "embedding_provider": "gemini",
  "embedding_model": "gemini-embedding-2",
  "embedding_text_policy": "gemini_search_title_text_v1"
}
```

Nếu lỗi:

```json
{
  "embedding_status": "failed",
  "embedding_error": "..."
}
```

---

## 11. Sau A2 là A3 Qdrant

A2 chỉ tạo vector.

A3 mới là:

```text
vectors + chunk payload
  -> Qdrant upsert
```

Payload Qdrant tối thiểu hiện được chuẩn hóa ở:

- [../indexing/README.md](../indexing/README.md)

Ví dụ rút gọn:

```json
{
  "qdrant_point_id": "deterministic-uuid",
  "qdrant_point_key": "doc-7-chunk-0-abc:gemini-embedding-2-3072-v1",
  "vector_id": "doc-7-chunk-0-abc",
  "active": true,
  "document_id": 7,
  "documentId": "doc_ebook_55",
  "book_id": 101,
  "ebook_id": 55,
  "pageStart": 12,
  "pageEnd": 13,
  "chapter_title": "Chương 1",
  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1",
  "embedding_model": "gemini-embedding-2"
}
```

---

## 12. Current status

```text
Done:
  - chunks persisted in PostgreSQL
  - artifacts uploaded to rag-artifacts
  - GEMINI_API_KEY setting already exists
  - Gemini embedding smoke test succeeded with gemini-embedding-2
  - gemini-embedding-2 returned 3072-dimension vectors in local smoke test
  - runtime config defaults/env example synced to Gemini
  - EmbeddingTextBuilder implemented for Gemini document/query text
  - GeminiEmbeddingProvider implemented with dimension validation and retry/backoff
  - ChunkEmbeddingService implemented to attach embedding metadata and return vectors in memory
  - QdrantVectorStore collection setup/validation implemented
  - QdrantVectorStore payload indexes implemented
  - QdrantVectorStore vector upsert implemented
  - Qdrant point id is deterministic UUID from vector_id + embedding_version
  - ingestion pipeline wires ChunkEmbeddingService -> QdrantVectorStore
  - terminal success is INDEXED after Qdrant upsert succeeds
  - google-genai declared as a direct dependency
  - qdrant-client dependency exists
  - llama-index-vector-stores-qdrant dependency exists

Not done:
  - retrieval/search baseline API
  - permission-aware Qdrant search filter
```

Next step:

```text
A4 implement retrieval/search baseline
```

Smoke-test script:

```text
poetry run python scripts/check_gemini_embedding.py
poetry run python scripts/check_gemini_embedding.py --model gemini-embedding-001
```

Script này đọc `GEMINI_API_KEY` từ `.env`, gọi Gemini Embedding API, rồi in
dimension/vector preview/cosine similarity. Script không in API key.

Nếu `.env` còn cấu hình cũ:

```env
EMBEDDING_MODEL=text-embedding-3-small
```

thì script sẽ không dùng model OpenAI đó cho Gemini. Script ưu tiên:

```text
1. GEMINI_EMBEDDING_MODEL
2. EMBEDDING_MODEL nếu nó bắt đầu bằng gemini-embedding
3. gemini-embedding-2
```

Có thể ép model rõ ràng bằng:

```text
poetry run python scripts/check_gemini_embedding.py --model gemini-embedding-2
```

---

## 13. Thứ tự bước tiếp theo

Làm theo thứ tự nhỏ, không làm một lần hết:

```text
A2.2  Sync config embedding
      - app/core/config.py default sang Gemini
      - .env.example thêm EMBEDDING_* rõ ràng
      - status: done

A2.3  Implement EmbeddingTextBuilder
      - chunk + metadata -> document text cho Gemini
      - query -> query text cho Gemini
      - status: done

A2.4  Implement GeminiEmbeddingProvider
      - gọi google-genai
      - mỗi chunk một vector
      - validate dimension = 3072
      - retry/backoff cho 429
      - unit test bằng mock/fake
      - status: done

A2.5  Gắn metadata embedding vào document_chunks
      - embedding_status
      - embedding_provider
      - embedding_model
      - embedding_dim
      - embedding_text_policy
      - status: done as service; pipeline wiring waits for A3

A3.1  Implement Qdrant collection setup
      - collection name
      - vector size 3072
      - cosine distance
      - status: done

A3.2  Upsert vectors vào Qdrant
      - vector_id ổn định
      - payload tối thiểu cho search/filter/citation
      - qdrant_point_id UUID ổn định từ vector_id + embedding_version
      - payload indexes cơ bản cho metadata filters
      - status: done as VectorStore method; pipeline wiring waits for A3.3

A3.3  Đổi job status terminal
      - CHUNKED -> EMBEDDED -> INDEXED
      - chỉ INDEXED khi Qdrant upsert thành công
      - status: done

A4    Search baseline
      - query embedding bằng Gemini
      - Qdrant search với active/book/ebook/embedding_version filter
      - trả chunks + citation metadata
      - status: next
```
