# Vector database and indexing plan

File này ghi lại cách áp dụng chương:

- [../advanced-rag-production-guide/06-vector-database-and-indexing.md](../advanced-rag-production-guide/06-vector-database-and-indexing.md)

vào project Library RAG hiện tại.

Mục tiêu của phần này là biến:

```text
clean chunks + embedding vectors
  -> Qdrant points
  -> search được bằng metadata/permission filters
```

Không lưu vectors trong PostgreSQL. PostgreSQL giữ metadata/audit/job status.
Qdrant giữ vectors và payload tối thiểu để retrieval/filter/citation.

---

## 1. Quyết định hiện tại

### Vector DB chính

Project dùng:

```text
Qdrant
```

Không dùng `pgvector` trong default ingestion/retrieval path. Nếu sau này muốn
benchmark `pgvector`, làm nhánh thử nghiệm riêng.

### Collection MVP

```text
collection = rag_chunks
embedding model = gemini-embedding-2
dimension = 3072
distance = Cosine
```

Giai đoạn đầu dùng một shared collection và lọc bằng metadata payload.

Sau này khi đổi embedding model/version lớn, cân nhắc blue/green collection:

```text
rag_chunks_gemini_embedding_2_v1
rag_chunks_next_model_v1
```

---

## 2. ID strategy

Có 2 loại ID khác nhau.

### 2.1. Human-readable vector key

Đây là key dễ đọc/debug, hiện đã nằm trong chunk metadata:

```text
vector_id = doc-{document_id}-chunk-{chunk_index}-{chunk_hash_prefix}
```

Ví dụ:

```text
doc-7-chunk-0-a1b2c3d4e5f6
```

Key này dùng trong PostgreSQL/artifacts/logs.

### 2.2. Qdrant point id

Qdrant point id nên là UUID ổn định, không dùng string tùy ý. Vì vậy code tạo:

```text
qdrant_point_key = {vector_id}:{embedding_version}
qdrant_point_id  = uuid5(QDRANT_POINT_NAMESPACE, qdrant_point_key)
```

Ví dụ:

```text
qdrant_point_key = doc-7-chunk-0-a1b2c3d4e5f6:gemini-embedding-2-3072-v1
qdrant_point_id  = deterministic UUID
```

Tác dụng:

- retry upsert không tạo point trùng;
- cùng chunk + cùng embedding version luôn ghi đè đúng point;
- đổi embedding version không vô tình overwrite vector cũ nếu vẫn dùng cùng collection;
- Qdrant SDK tương thích hơn vì point id là UUID.

---

## 3. Payload contract

Qdrant payload không copy toàn bộ `document_chunks.metadata`.

Nguyên tắc:

```text
Qdrant payload = retrieval/filter/citation subset
PostgreSQL metadata = audit/debug đầy đủ
rag-artifacts = report/file chi tiết
```

Payload hiện tại do `QdrantVectorStore` build theo allowlist.

Ví dụ payload:

```json
{
  "qdrant_point_id": "uuid",
  "qdrant_point_key": "doc-7-chunk-0-a1b2:gemini-embedding-2-3072-v1",
  "vector_id": "doc-7-chunk-0-a1b2",
  "content": "chunk text...",
  "active": true,
  "metadata_schema_version": "rag-vector-payload-v1",

  "document_id": 7,
  "documentId": "doc_ebook_55",
  "source_system": "LIBRARY",
  "sourceType": "LIBRARY_EBOOK",
  "book_id": 101,
  "ebook_id": 55,

  "pageStart": 12,
  "pageEnd": 13,
  "chapter_index": 1,
  "chapter_title": "Chương 1",
  "chunk_index": 0,
  "chunk_hash": "64-char-sha256",

  "chunking_strategy": "library_pdf_narrative",
  "chunking_strategy_version": "v1",
  "token_count": 420,

  "embedding_provider": "gemini",
  "embedding_model": "gemini-embedding-2",
  "embedding_dim": 3072,
  "embedding_version": "gemini-embedding-2-3072-v1",
  "embedding_text_policy": "gemini_search_title_text_v1",
  "embedding_text_hash": "sha256..."
}
```

Không đưa các field debug lớn xuống Qdrant, ví dụ:

```text
chunk_quality_report
document_profile_signals
repeated_header_footer_candidates
removed_header_footer_lines
line_type_counts
```

---

## 4. Metadata filtering strategy

Retrieval không được search toàn collection rồi lọc quyền bằng Python.

Filter phải nằm trong Qdrant query.

Filter tối thiểu cho MVP Library:

```text
active = true
source_system/sourceType = Library ebook
book_id hoặc ebook_id nằm trong scope được Spring Boot cho phép
embedding_version = active embedding version
```

Luồng query sau này nên là:

```text
Spring Boot
  -> kiểm tra user có quyền với book/ebook nào
  -> gọi RAG query endpoint kèm allowed scope
RAG retrieval service
  -> build Qdrant filter bắt buộc
  -> Qdrant search
  -> trả chunks/citation
```

Không để client tự gửi raw Qdrant filter.

Field future cho multi-tenant/workspace:

```text
tenant_id
workspace_id
visibility
allowed_role_keys
```

---

## 5. Payload indexes

Các field được index trước để tránh slow filter:

```text
active
document_id
book_id
ebook_id
embedding_version
sourceType
source_type
tenant_id
workspace_id
```

Hiện `QdrantVectorStore.ensure_collection()` gọi thêm
`ensure_payload_indexes()`.

---

## 6. Common failures mapped vào project

| Failure | Cách áp dụng trong project |
|---|---|
| Dimension mismatch | `ensure_collection()` validate collection dimension; embedding provider và upsert cũng validate vector dimension. |
| Missing tenant/permission filter | Retrieval service sau này phải tự build filter bắt buộc; không nhận raw filter từ frontend. MVP dùng `book_id`/`ebook_id`; future thêm `tenant_id`/`workspace_id`. |
| Vector DB down | Cần health check Qdrant và fallback trả no-answer/retry. Chưa làm. |
| Slow filter | Đã thêm payload indexes cơ bản. |
| Orphan vectors | Cần reconciliation job Postgres ↔ Qdrant. Chưa làm. |
| Wrong collection version | Payload có `embedding_version`; collection validate dimension/distance. Blue/green collection để sau. |
| Hard delete needed data | Giai đoạn tới nên làm soft delete bằng `active=false` trước, hard delete chỉ dành cho cleanup/audit-approved path. |

---

## 7. Trạng thái implementation

### Done

```text
A3.1 Qdrant collection setup/validation
A3.1b payload indexes cơ bản
A3.2 Qdrant vector upsert method
     - deterministic UUID point id
     - dimension validation
     - allowlisted payload
     - active flag
     - idempotent upsert
A3.3 Pipeline wiring
     - chunks persisted first
     - embedding stage runs with ChunkEmbeddingService
     - Qdrant upsert runs with QdrantVectorStore
     - document_chunks metadata updated with qdrant_point_id/qdrant_point_key
     - job/document status becomes INDEXED only after Qdrant upsert succeeds
A4.0 Search interface contract
     - VectorSearchQuery added
     - SearchResult extended with vector_id/retrieval_source
     - VectorStore.search now receives a query object instead of loose args
A4.1 Library vector filter builder
     - LibraryVectorSearchScope added
     - mandatory active/sourceType/embedding_version filters
     - requires book_id, ebook_id, or document_id scope
A4.1b Qdrant filter builder
     - provider-neutral filters converted to Qdrant Filter
     - exact MatchValue conditions
     - list MatchAny support
     - empty filters rejected
A4.2 QdrantVectorStore.search
     - validates query vector dimension
     - converts filters to Qdrant Filter
     - calls Qdrant with_payload=true / with_vectors=false by default
     - maps Qdrant points to SearchResult
A4.3 Internal Library retrieval service/API
     - `/internal/retrieval/search`
     - request gồm query + bookId/ebookId/documentId scope
     - build Gemini query embedding bằng `embed_query`
     - search Qdrant bằng filter bắt buộc
     - response trả chunks + score + citation metadata
```

Code chính:

```text
app/indexing/qdrant_store.py
app/ingestion/pipeline.py
tests/unit/test_qdrant_store_collection.py
tests/unit/test_ingestion_pipeline_indexing.py
```

### Pipeline status hiện tại

Ingestion success path hiện là:

```text
CHUNKED
  -> EMBEDDING
  -> EMBEDDED
  -> INDEXING
  -> INDEXED
```

`CHUNKED` vẫn được commit trước để retry/debug an toàn nếu embedding hoặc Qdrant
lỗi. Nhưng terminal success mới là:

```text
INDEXED
```

---

## 8. Bước tiếp theo

### A4 — Search baseline

Mục tiêu:

```text
query
  -> build Gemini query embedding
  -> Qdrant search với filter bắt buộc
  -> return chunks + citation metadata
```

Filter bắt buộc ở A4:

```text
active = true
embedding_version = current embedding version
book_id / ebook_id / allowed scope từ Library service
```

Không được search toàn collection rồi lọc quyền bằng Python.

### A5 — Soft delete

Không hard delete ngay. Khi document version mới active:

```text
old document/version points -> active=false
new document/version points -> active=true
```

### A6 — Reconciliation job

Định kỳ kiểm tra:

```text
PostgreSQL document_chunks/vector_id
  <-> Qdrant qdrant_point_id/vector_id
```

Để phát hiện orphan vectors hoặc chunks chưa index.

---

## 9. Failure behavior sau A3.3

Vì không có distributed transaction giữa PostgreSQL, Gemini và Qdrant, flow hiện
tại cố ý commit theo mốc an toàn:

```text
1. parse/clean/chunk xong
2. chunks + artifacts được persist
3. commit CHUNKED/pending_embedding
4. embed
5. upsert Qdrant
6. update chunk metadata + document/job status INDEXED
```

Nếu lỗi ở bước 4 hoặc 5:

```text
job -> FAILED
document vẫn có chunks đã persist
retry có thể dùng lại deterministic vector_id/qdrant_point_id
```

Nếu Qdrant upsert thành công nhưng DB final update lỗi, có thể có orphan/partial
state. Đây là lý do A6 reconciliation job vẫn cần làm sau.

---

## 10. Pre-A4 review từ chương 06

Trước khi làm A4 search baseline, cần chốt lại các điểm từ chương
`06-vector-database-and-indexing.md`.

### 10.1. Indexing strategy hiện tại có đúng không?

Có. Strategy hiện tại phù hợp với project ở giai đoạn này:

```text
shared collection: rag_chunks
ANN/HNSW index mặc định của Qdrant
metadata filtering trong Qdrant
Gemini embedding version filter
```

Không cần tách collection theo:

```text
tenant
document type
book type
```

ở MVP. Với Library RAG hiện tại, một shared collection + filter là đủ.

### 10.2. Thuật toán indexing đang dùng là gì?

Về mặt code, project không tự implement thuật toán ANN. Qdrant chịu trách nhiệm
vector index.

Mặc định Qdrant dùng HNSW-like ANN index cho vector search. Vì vậy project chỉ
cần đảm bảo:

```text
dimension đúng
distance đúng
payload filter đúng
point id ổn định
```

Chưa cần tự tune:

```text
m
ef_construct
ef_search
IVF
PQ
sharding
```

Chỉ quay lại tune HNSW khi đã có:

```text
corpus lớn
p95 search latency cao
recall kém qua evaluation
Qdrant memory tăng mạnh
```

### 10.3. Điều tuyệt đối không được quên ở A4

Search phải filter trong Qdrant, không search toàn collection rồi lọc bằng
Python.

Sai:

```text
Qdrant search all rag_chunks
  -> Python lọc book/ebook/user scope
```

Đúng:

```text
Qdrant search with filter:
  active = true
  embedding_version = current version
  sourceType = LIBRARY_EBOOK
  book_id / ebook_id nằm trong scope được Library cho phép
```

Lý do:

- tránh leak chunk từ ebook khác;
- tránh top-k bị rỗng sau khi lọc hậu kỳ;
- tránh private chunks đi vào logs/traces;
- filter có thể dùng payload indexes.

### 10.4. Điểm cần cải tiến trước khi code A4

`VectorStore.search()` hiện tại còn quá đơn giản:

```python
async def search(self, query_vector: list[float], top_k: int) -> list[SearchResult]:
    ...
```

Như vậy chưa truyền được:

```text
metadata filters
score_threshold
include_vectors
retrieval_source
```

Trước A4 nên thêm query object:

```python
VectorSearchQuery
  query_vector
  top_k
  filters
  score_threshold
  include_vectors
```

Và search result nên có shape rõ hơn:

```python
SearchResult
  id / qdrant_point_id
  vector_id
  score
  text
  metadata
  retrieval_source = "vector"
```

### 10.5. Metadata filter contract cho Library RAG

Chương 06 nói nhiều về `tenant_id`. Project Library hiện chưa có multi-tenant
thật, nên scope bảo mật MVP là:

```text
book_id
ebook_id
sourceType = LIBRARY_EBOOK
active = true
embedding_version = current embedding version
```

Library/Spring Boot vẫn là nơi kiểm tra quyền user. RAG chỉ nhận trusted scope
từ Library service qua internal API.

Không để frontend gọi thẳng RAG search với raw filter.

### 10.6. Payload indexes cần có

Đã có index cơ bản:

```text
active
document_id
book_id
ebook_id
embedding_version
sourceType
source_type
tenant_id
workspace_id
```

Nên thêm sau:

```text
document_version_id
documentId
metadata_schema_version
visibility
allowed_role_keys
```

Không bắt buộc trước A4, nhưng nên đưa vào A5/A6 khi làm soft delete,
permission mở rộng và versioning.

### 10.7. Score threshold

Không nên đặt threshold cứng sớm.

Ở A4 nên:

```text
top_k = 5 hoặc 10
score_threshold = None
log scores trả về
```

Sau khi có evaluation nhỏ cho novel, mới quyết định threshold.

Nếu threshold quá cao:

```text
query hợp lệ nhưng empty results
```

Nếu threshold quá thấp:

```text
nhiễu nhiều
```

### 10.8. Content trong Qdrant payload có ổn không?

Hiện payload có:

```text
content = chunk text
```

Điều này ổn cho MVP vì search response/debug đơn giản hơn. Nhưng production có
thể chuyển sang:

```text
Qdrant payload giữ citation/filter metadata
PostgreSQL giữ full chunk content
```

Khi đó search lấy `vector_id/chunk_id`, rồi fetch content từ PostgreSQL. Chưa cần
làm ngay trước A4.

### 10.9. Startup validation

`ensure_collection()` hiện chạy khi upsert/search dùng vector store. Chương 06
khuyến nghị startup validation.

Nên làm sau A4 hoặc trong A4.1:

```text
API/worker startup
  -> check Qdrant reachable
  -> check collection dimension/distance
  -> ensure payload indexes
```

Mục tiêu: fail fast nếu config sai, thay vì đợi job/search runtime mới lỗi.

### 10.10. Observability cần thêm khi làm A4

Search nên log tối thiểu:

```text
event = vector_search_completed
collection
top_k
filters applied
results_count
latency_ms
embedding_version
score_threshold
```

Không log raw user query dài hoặc content nhạy cảm quá mức.

### 10.11. Những việc chưa cần làm trước A4

Chưa cần:

```text
blue/green collection
collection per tenant
sharding
replication
PQ/IVF tuning
hybrid BM25
reranking
parent-child retrieval
backup scripts
full reconciliation job
```

Các phần này nên làm sau khi search baseline chạy được và có dữ liệu đánh giá.

### 10.12. Thứ tự đề xuất sau review

```text
A4.0  Cải tiến VectorStore interface
      - thêm VectorSearchQuery
      - mở rộng SearchResult
      - status: done

A4.1  Implement Qdrant filter builder
      - active=true
      - embedding_version=current
      - sourceType=LIBRARY_EBOOK
      - book_id/ebook_id scope
      - không nhận raw filter từ client
      - status: done as provider-neutral Library filter builder

A4.1b Build Qdrant Filter model
      - convert VectorSearchQuery.filters into Qdrant FieldCondition
      - reject empty filters
      - status: done

A4.2  Implement QdrantVectorStore.search()
      - with_payload=true
      - with_vectors=false
      - score_threshold optional
      - log latency/result count
      - status: done as VectorStore method

A4.3  Implement internal search/retrieval service API
      - Library gọi bằng internal API key
      - request gồm query + allowed book/ebook scope
      - response gồm chunks + score + citation metadata
      - status: done

A4.4  Unit tests/security tests
      - không có scope thì reject
      - filter luôn có active=true
      - filter luôn có embedding_version
      - không truyền raw user filter thẳng xuống Qdrant
      - status: partially done for A4.3 service/API

A4.5  Retrieval integration smoke test
      - chạy API với Qdrant thật khi đã có dữ liệu indexed
      - kiểm tra topK/score/page citation trong response
      - chưa gọi LLM/generation
      - script: `scripts/smoke_internal_retrieval.py`
      - status: smoke harness done; local run returned HTTP 200 with resultCount=0 because no chunks are indexed yet
```

### 10.13. A4.5 smoke test command

Sau khi API, Qdrant và dữ liệu indexed đã sẵn sàng:

```bash
poetry run python scripts/smoke_internal_retrieval.py \
  --query "nhân vật chính tìm thấy gì?" \
  --book-id 101 \
  --ebook-id 55 \
  --top-k 5
```

Script này gọi đúng internal API:

```text
POST /internal/retrieval/search
X-RAG-API-Key: <RAG_INTERNAL_API_KEY>
```

Nếu muốn CI/dev fail khi chưa có chunk nào:

```bash
poetry run python scripts/smoke_internal_retrieval.py \
  --query "nhân vật chính tìm thấy gì?" \
  --book-id 101 \
  --top-k 5 \
  --fail-on-empty
```

Lưu ý: smoke test thành công với `resultCount = 0` nghĩa là API/Gemini/Qdrant
đã gọi được nhưng collection chưa có chunks phù hợp với filter/query đó. Khi
đã ingestion ebook xong và job đạt `INDEXED`, kỳ vọng `resultCount > 0`.

Local smoke result gần nhất:

```text
HTTP 200
embeddingVersion = gemini-embedding-2-3072-v1
queryTextPolicy  = gemini_search_title_text_v1
topK             = 5
resultCount      = 0
filters          = active + LIBRARY_EBOOK + embedding_version + book_id + ebook_id
```

Điều này xác nhận:

- internal API route hoạt động;
- `RAG_INTERNAL_API_KEY` hoạt động;
- Gemini query embedding gọi được;
- Qdrant collection `rag_chunks` được tạo đúng dimension `3072`;
- payload indexes cơ bản được tạo;
- hiện chưa có chunks/vectors để trả về kết quả thật.

Trước khi kỳ vọng `resultCount > 0`, cần có ít nhất một ebook ingestion thành công
tới trạng thái:

```text
INDEXED
```

và Qdrant phải có:

```text
points_count > 0
```
