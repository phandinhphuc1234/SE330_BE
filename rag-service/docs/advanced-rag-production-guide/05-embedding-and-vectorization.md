# 05. Embedding And Vectorization

Chương này đi sâu vào **embedding** và **vectorization**, tức cách biến text thành vector số để hệ thống có thể tìm kiếm theo ngữ nghĩa.

Nếu parsing/cleaning/chunking là bước chuẩn bị tri thức, thì embedding là bước biến tri thức đó thành dạng máy có thể so sánh nhanh.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `app/indexing/embedding_provider.py`
- `app/indexing/indexer.py`
- `app/indexing/base.py`
- `app/core/config.py`
- `.env` với `EMBEDDING_PROVIDER`, `EMBEDDING_MODEL`, `EMBEDDING_DIM`
- `app/documents/models.py` với `DocumentChunk`

## 1. Embedding Là Gì?

Embedding là vector số đại diện cho ý nghĩa của một đoạn text.

Ví dụ text:

```text
Nhân viên chính thức được nghỉ phép 14 ngày mỗi năm.
```

có thể được biến thành vector:

```text
[0.012, -0.083, 0.441, ..., 0.027]
```

Vector này có thể có 384, 768, 1536, 3072 hoặc nhiều chiều hơn tùy model.

Điểm quan trọng: embedding không lưu text theo kiểu keyword, mà cố gắng mã hóa **ngữ nghĩa**.

Vì vậy các câu có ý gần nhau sẽ có vector gần nhau:

```text
"Chính sách nghỉ phép năm nay là gì?"
"Annual leave policy for this year"
"Nhân viên được nghỉ bao nhiêu ngày phép?"
```

Nếu dùng embedding multilingual tốt, các câu trên có thể gần nhau trong vector space dù khác ngôn ngữ.

## 2. Vector Representation Là Gì?

Vector representation là cách biểu diễn object thành một điểm trong không gian nhiều chiều.

Trong RAG:

- chunk text → document vector
- user query → query vector
- so sánh query vector với document vectors
- lấy các chunk gần nhất

Flow:

```text
Chunk text
  ↓
Embedding model
  ↓
Vector
  ↓
Vector DB
```

Query-time:

```text
User query
  ↓
Embedding model
  ↓
Query vector
  ↓
Vector search
  ↓
Top-k similar chunks
```

## 3. Vì Sao Cần Embedding?

### 3.1. Tìm kiếm keyword không đủ

BM25/keyword search tốt khi user dùng đúng từ có trong document.

Nhưng user có thể hỏi:

```text
Quy trình nhân viên mới bắt đầu làm việc như thế nào?
```

Trong document có thể viết:

```text
Employee onboarding process includes account provisioning, orientation, and mentor assignment.
```

Keyword search tiếng Việt có thể miss. Embedding multilingual có thể match theo nghĩa.

### 3.2. RAG cần retrieve context liên quan

LLM không nên nhận toàn bộ documents. Embedding giúp chọn ra chunks gần câu hỏi nhất.

### 3.3. Semantic search giúp user hỏi tự nhiên

Người dùng không cần nhớ exact title:

```text
Có lỗi gì hay gặp khi deploy backend không?
```

Hệ thống có thể retrieve runbook:

```text
Common backend deployment failures
```

## 4. Embedding Nằm Ở Đâu Trong RAG Pipeline?

Embedding xuất hiện ở cả offline pipeline và online pipeline.

### Offline ingestion-time embedding

```text
Cleaned chunks
  ↓
Embedding provider
  ↓
Chunk vectors
  ↓
Vector DB upsert
```

### Online query-time embedding

```text
User query
  ↓
Query rewrite/normalization
  ↓
Embedding provider
  ↓
Query vector
  ↓
Vector DB search
```

Điểm cực kỳ quan trọng:

> Query embedding và document embedding phải dùng cùng embedding model và cùng vector space.

Nếu document chunks được embed bằng model A, query lại embed bằng model B, similarity score không còn đáng tin.

## 5. Similarity Metrics

Similarity metric là cách đo vector nào gần vector nào.

### 5.1. Cosine similarity

Cosine similarity đo góc giữa hai vector.

Giá trị thường từ `-1` đến `1`:

- gần `1`: rất giống
- gần `0`: không liên quan
- âm: ngược hướng

Trong retrieval text, cosine rất phổ biến.

Pseudo-code:

```python
def cosine_similarity(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = sum(x * x for x in a) ** 0.5
    norm_b = sum(y * y for y in b) ** 0.5
    return dot / (norm_a * norm_b)
```

### 5.2. Dot product

Dot product đo tích vô hướng.

Nếu vector đã normalize, dot product gần tương đương cosine similarity.

Dot product thường nhanh và được nhiều vector DB tối ưu.

### 5.3. Euclidean distance

Euclidean distance đo khoảng cách hình học.

Giá trị càng thấp càng gần.

Ít phổ biến hơn cosine/dot trong text embedding, nhưng vẫn có use case.

### 5.4. Chọn metric thế nào?

Không tự đoán. Xem recommendation của embedding model và vector DB.

Ví dụ:

- OpenAI embeddings thường dùng cosine hoặc dot product.
- Sentence-transformers thường dùng cosine.
- Một số vector DB yêu cầu khai báo metric khi tạo collection.

Trong Qdrant, collection config phải có:

```json
{
  "vectors": {
    "size": 1536,
    "distance": "Cosine"
  }
}
```

Nếu khai báo sai dimension hoặc distance, retrieval quality có thể giảm hoặc upsert fail.

## 6. Embedding Model Selection

Chọn embedding model là quyết định kiến trúc quan trọng.

### 6.1. Tiêu chí chọn model

| Tiêu chí | Câu hỏi cần trả lời |
|---|---|
| Quality | Model retrieve đúng không trên golden dataset? |
| Language | Có hỗ trợ tiếng Việt và tiếng Anh không? |
| Dimension | Vector dimension bao nhiêu? Storage có tăng quá không? |
| Latency | Embed query realtime có nhanh không? |
| Cost | Embed corpus lớn tốn bao nhiêu? |
| Context length | Chunk dài tối đa bao nhiêu token? |
| Deployment | API hosted hay local model? |
| Privacy | Có được gửi text nội bộ ra ngoài không? |
| Stability | Model có version ổn định không? |

### 6.2. Các lựa chọn phổ biến

| Nhóm | Ví dụ | Ưu điểm | Nhược điểm |
|---|---|---|---|
| Hosted commercial | OpenAI, Gemini, Cohere | Quality tốt, dễ dùng | Cost, privacy, rate limit |
| Open-source local | BGE, E5, sentence-transformers | Control tốt, không gửi data ra ngoài | Cần infra GPU/CPU, tuning |
| Multilingual | BGE-M3, multilingual-e5 | Hợp Việt-Anh | Có thể kém hơn model chuyên biệt ở English-only |
| Domain-specific | Legal/biomedical/code embeddings | Tốt cho domain | Khó vận hành, ít universal |

Project hiện tại đang có config:

```env
EMBEDDING_PROVIDER=openai
EMBEDDING_MODEL=text-embedding-3-small
EMBEDDING_DIM=1536
```

Bạn có thể bắt đầu với OpenAI/Gemini-compatible embedding để học nhanh, sau đó benchmark local multilingual model nếu cần tối ưu cost/privacy.

## 7. Dimension

Dimension là số chiều của vector.

Ví dụ:

- 384 dimensions
- 768 dimensions
- 1024 dimensions
- 1536 dimensions
- 3072 dimensions

### Vì sao dimension quan trọng?

Dimension ảnh hưởng:

- storage size
- memory usage
- index build time
- search latency
- vector DB collection schema
- network payload

Ví dụ tính storage thô:

```text
1 vector 1536 dims float32
= 1536 * 4 bytes
= 6144 bytes
≈ 6 KB/vector

1,000,000 chunks
≈ 6 GB raw vector data
```

Chưa tính index overhead, metadata, replication.

### Lỗi thường gặp

Vector DB collection tạo dimension 1536, nhưng model trả vector 768:

```text
Qdrant error: vector dimension mismatch
```

Production nên validate lúc startup:

```python
assert settings.embedding_dim == embedding_provider.dimension
assert vector_store.collection_dimension == settings.embedding_dim
```

## 8. Latency

Embedding latency có hai loại:

- ingestion latency: embed nhiều chunks
- query latency: embed câu hỏi realtime

### Ingestion embedding latency

Không ảnh hưởng trực tiếp user query, nhưng ảnh hưởng tốc độ index tài liệu.

Tối ưu bằng:

- batch embedding
- worker concurrency
- retry/backoff
- rate limit handling
- embedding cache

### Query embedding latency

Ảnh hưởng trực tiếp p95/p99 latency của chat endpoint.

Query flow:

```text
query rewrite: 100-500ms
query embedding: 50-300ms
vector search: 20-100ms
rerank: 100-800ms
LLM: 1-10s
```

Embedding query cần nhanh và ổn định.

### Metrics cần monitor

- `embedding_latency_ms`
- `query_embedding_latency_ms`
- `batch_embedding_latency_ms`
- `embedding_provider_errors_total`
- `embedding_rate_limit_total`

## 9. Cost

Embedding cost thường rẻ hơn LLM generation, nhưng có thể lớn khi corpus lớn.

Ví dụ:

```text
100,000 documents
average 20 chunks/document
= 2,000,000 chunks

average 350 tokens/chunk
= 700,000,000 tokens embedded
```

Nếu không deduplicate và incremental indexing, cost sẽ tăng rất nhanh.

### Cost optimization

- batch embedding
- skip unchanged chunks
- deduplicate chunks
- cache by text hash
- choose smaller model if quality acceptable
- limit chunk overlap
- avoid re-index full corpus unnecessarily

## 10. Multilingual Embedding

Internal KB ở Việt Nam thường trộn tiếng Việt và tiếng Anh:

- HR policy tiếng Việt
- technical docs tiếng Anh
- meeting notes song ngữ
- user hỏi tiếng Việt nhưng document tiếng Anh

Multilingual embedding giúp cross-lingual retrieval.

Ví dụ:

```text
Query: "service order xử lý payment thế nào?"
Document: "Order service payment processing flow"
```

Model multilingual tốt sẽ đưa hai câu gần nhau.

### Lỗi thường gặp

- Dùng English-only embedding cho corpus Việt-Anh.
- Query rewrite dịch sai thuật ngữ nội bộ.
- Acronym nội bộ không được model hiểu.

### Checklist

- Test retrieval tiếng Việt → doc tiếng Anh.
- Test tiếng Anh → doc tiếng Việt.
- Test thuật ngữ mixed-language.
- Test acronym/service names.

## 11. Domain-Specific Embedding

Domain-specific embedding là model được tối ưu cho domain như:

- legal
- medical
- finance
- code
- support tickets

### Khi nào cần?

- General embedding retrieve kém trên golden dataset.
- Domain có thuật ngữ đặc biệt.
- Keyword và semantic đều khó.
- Có dữ liệu đủ để fine-tune hoặc chọn model domain.

### Trade-off

| Ưu điểm | Nhược điểm |
|---|---|
| Quality domain tốt hơn | Có thể kém ở câu hỏi general |
| Hiểu thuật ngữ chuyên ngành | Cần benchmark kỹ |
| Có thể giảm rerank load | Vận hành phức tạp hơn |

Với project hiện tại, chưa cần bắt đầu bằng domain-specific model. Hãy bắt đầu bằng model multilingual/general tốt, tạo golden dataset rồi đo.

## 12. Batch Embedding

Batch embedding là gửi nhiều chunks trong một request.

### Vì sao cần?

Embed từng chunk một:

```text
2,000 chunks = 2,000 API calls
```

Batch 64 chunks:

```text
2,000 chunks ≈ 32 API calls
```

Giảm overhead, tăng throughput.

### Pseudo-code

```python
async def embed_chunks(chunks: list[Chunk], batch_size: int = 64):
    results = []

    for batch in batched(chunks, batch_size):
        texts = [chunk.embedding_text for chunk in batch]
        vectors = await embedding_provider.embed(texts)

        for chunk, vector in zip(batch, vectors):
            results.append((chunk, vector))

    return results
```

### Production concern

- Batch quá lớn có thể timeout.
- Provider có token limit per request.
- Một item lỗi trong batch xử lý thế nào?
- Rate limit cần backoff.
- Log batch latency và token count.

## 13. Embedding Cache

Embedding cache lưu vector theo hash của text + model version.

### Vì sao cần?

Nếu chunk text không đổi, không cần embed lại.

Cache key:

```text
embedding:{provider}:{model}:{version}:{text_hash}
```

Ví dụ Redis key:

```text
rag:embedding:openai:text-embedding-3-small:v1:sha256_abc123
```

### Cache value

```json
{
  "vector": [0.01, -0.02, "..."],
  "dimension": 1536,
  "created_at": "2026-05-27T10:00:00+07:00"
}
```

### Khi nào invalidate?

- đổi embedding model
- đổi embedding provider
- đổi embedding version
- đổi text normalization
- đổi embedding text template

Không cần xóa cache cũ ngay nếu key có version. Có thể expire sau.

## 14. Embedding Versioning

Embedding versioning là lưu rõ vector được tạo bởi model nào, config nào.

Metadata tối thiểu:

```json
{
  "chunk_id": "hr-policy-2026-v3-c012",
  "document_id": "hr-policy-2026",
  "embedding_provider": "openai",
  "embedding_model": "text-embedding-3-small",
  "embedding_version": "v1",
  "dimension": 1536,
  "distance": "Cosine"
}
```

### Vì sao phải lưu?

Nếu không lưu, sau này bạn không biết:

- vector này tạo bằng model nào
- dimension bao nhiêu
- có cần re-index không
- query đang search trên collection nào
- evaluation result thuộc embedding version nào

### Version nên tăng khi nào?

- đổi model
- đổi provider
- đổi dimension
- đổi text normalization trước embedding
- đổi embedding text template
- đổi chunking strategy ảnh hưởng text

## 15. Khi Đổi Embedding Model Có Cần Re-index Không?

Thường là **có**.

Vì mỗi embedding model tạo vector space khác nhau. Vector từ model A không nên search bằng query vector từ model B.

Ví dụ:

```text
Document chunks: text-embedding-3-small
Query: bge-m3
```

Similarity score không còn ý nghĩa.

### Strategy re-index

#### Option 1: In-place re-index

```text
Stop writes
Delete old vectors
Embed all chunks with new model
Upsert new vectors
Resume
```

Ưu điểm:

- đơn giản

Nhược điểm:

- downtime/risk
- nếu fail giữa chừng, index không nhất quán

#### Option 2: Blue/green vector collection

```text
Current collection: rag_chunks_v1
Build new collection: rag_chunks_v2
Run evaluation on v2
Switch read traffic to v2
Keep v1 for rollback
```

Ưu điểm:

- an toàn
- rollback dễ
- evaluation trước switch

Nhược điểm:

- tốn storage tạm thời
- cần routing collection theo version

Production nên dùng blue/green cho corpus lớn.

## 16. Vì Sao Query Embedding Và Document Embedding Phải Cùng Model?

Embedding model định nghĩa vector space.

Hãy tưởng tượng model A và model B là hai bản đồ khác nhau:

- Model A đặt "nghỉ phép" gần "annual leave".
- Model B có cách mã hóa khác.

Nếu document nằm trên bản đồ A, query nằm trên bản đồ B, khoảng cách giữa chúng không còn có nghĩa.

Production rule:

```text
retrieval_embedding_model == indexed_embedding_model
retrieval_embedding_version == indexed_embedding_version
retrieval_dimension == vector_collection_dimension
```

Nếu mismatch, fail fast.

## 17. Embedding Text Template

Không phải lúc nào cũng embed raw chunk content. Nhiều trường hợp nên embed text đã thêm context.

Ví dụ chunk body:

```text
Full-time employees receive 14 days.
```

Embedding text tốt hơn:

```text
Document: HR Policy 2026
Section: Annual Leave
Content: Full-time employees receive 14 days.
```

Vì sao?

- "14 days" có context là annual leave.
- Query về "nghỉ phép" match tốt hơn.

### Template example

```python
def build_embedding_text(chunk):
    section = " > ".join(chunk.metadata.get("section_path", []))
    return f"""Document: {chunk.metadata["document_title"]}
Section: {section}
Content:
{chunk.text}
"""
```

### Trade-off

| Ưu điểm | Nhược điểm |
|---|---|
| Retrieval tốt hơn nhờ context | Tăng token embedding |
| Match title/section tốt hơn | Nếu metadata sai thì nhiễu |
| Hữu ích cho chunk nhỏ | Có thể duplicate title nhiều |

Embedding text template cũng phải version.

## 18. Schema Gợi Ý

### 18.1. `chunks`

```sql
CREATE TABLE chunks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    document_version_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    embedding_text TEXT NOT NULL,
    chunk_hash TEXT NOT NULL,
    token_count INTEGER NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 18.2. `embeddings`

```sql
CREATE TABLE embeddings (
    id UUID PRIMARY KEY,
    chunk_id UUID NOT NULL REFERENCES chunks(id),
    vector_id TEXT NOT NULL,
    embedding_provider TEXT NOT NULL,
    embedding_model TEXT NOT NULL,
    embedding_version TEXT NOT NULL,
    dimension INTEGER NOT NULL,
    distance_metric TEXT NOT NULL,
    text_hash TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(chunk_id, embedding_model, embedding_version)
);
```

### 18.3. `embedding_model_versions`

```sql
CREATE TABLE embedding_model_versions (
    id UUID PRIMARY KEY,
    provider TEXT NOT NULL,
    model_name TEXT NOT NULL,
    version TEXT NOT NULL,
    dimension INTEGER NOT NULL,
    distance_metric TEXT NOT NULL,
    embedding_text_template_version TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(provider, model_name, version)
);
```

## 19. Metadata Ví Dụ

```json
{
  "chunk_id": "hr-policy-2026-v3-c012",
  "document_id": "hr-policy-2026",
  "document_version_id": "v3",
  "embedding_provider": "openai",
  "embedding_model": "text-embedding-3-small",
  "embedding_version": "v1",
  "embedding_text_template_version": "rag-default-v1",
  "dimension": 1536,
  "distance_metric": "Cosine",
  "chunk_hash": "sha256:abc123",
  "vector_id": "company-alpha:hr-policy-2026:v3:c012:text-embedding-3-small:v1"
}
```

## 20. Embedding Provider Interface

Project hiện tại có:

```python
class EmbeddingProvider(ABC):
    @abstractmethod
    async def embed(self, texts: list[str]) -> list[list[float]]:
        raise NotImplementedError
```

Production nên thêm metadata:

```python
class EmbeddingProvider(ABC):
    provider_name: str
    model_name: str
    dimension: int
    version: str

    @abstractmethod
    async def embed(self, texts: list[str]) -> list[list[float]]:
        raise NotImplementedError

    async def embed_one(self, text: str) -> list[float]:
        return (await self.embed([text]))[0]
```

### Vì sao cần interface?

Để thay provider mà không sửa pipeline:

- OpenAI
- Gemini
- HuggingFace local
- Ollama/local embeddings
- Cohere

`IngestionPipeline` và `RetrievalPipeline` chỉ phụ thuộc interface, không phụ thuộc SDK cụ thể.

## 21. Pseudo-code Embedding Service

```python
class EmbeddingService:
    def __init__(self, provider, cache, settings):
        self.provider = provider
        self.cache = cache
        self.settings = settings

    async def embed_chunks(self, chunks: list[ChunkRecord]) -> list[EmbeddedChunk]:
        embedded = []
        to_embed = []

        for chunk in chunks:
            key = self.cache_key(chunk.embedding_text)
            cached = await self.cache.get(key)
            if cached:
                embedded.append(EmbeddedChunk(chunk=chunk, vector=cached))
            else:
                to_embed.append(chunk)

        for batch in batched(to_embed, self.settings.embedding_batch_size):
            texts = [chunk.embedding_text for chunk in batch]
            vectors = await self.provider.embed(texts)

            for chunk, vector in zip(batch, vectors):
                self.validate_dimension(vector)
                await self.cache.set(self.cache_key(chunk.embedding_text), vector)
                embedded.append(EmbeddedChunk(chunk=chunk, vector=vector))

        return embedded

    def cache_key(self, text: str) -> str:
        text_hash = sha256(text.encode("utf-8")).hexdigest()
        return (
            f"rag:embedding:{self.provider.provider_name}:"
            f"{self.provider.model_name}:{self.provider.version}:{text_hash}"
        )

    def validate_dimension(self, vector: list[float]) -> None:
        if len(vector) != self.provider.dimension:
            raise ValueError(
                f"Embedding dimension mismatch: expected {self.provider.dimension}, "
                f"got {len(vector)}"
            )
```

## 22. Query Embedding

Query embedding cần cẩn thận vì nó nằm trên critical path.

Flow:

```text
Raw query
  ↓
Normalize
  ↓
Rewrite / expand if needed
  ↓
Embed rewritten query
  ↓
Search vector DB
```

Không phải lúc nào cũng embed raw query. Ví dụ:

```text
Raw: "nghỉ phép năm ngoái thay đổi gì?"
Rewritten: "annual leave policy changes in 2025"
```

Tùy pipeline, có thể embed:

- raw query
- rewritten query
- multiple rewritten queries
- HyDE generated hypothetical answer

Các strategy này sẽ đi sâu ở chương query rewriting.

## 23. Debug Embedding

Khi retrieval sai, embedding có thể là nguyên nhân.

### Checklist debug

- Query và chunks có cùng embedding model/version không?
- Vector dimension đúng không?
- Query language có được model hỗ trợ không?
- Chunk embedding text có thiếu heading/title không?
- Chunk quá nhỏ/quá lớn không?
- Model có hiểu acronym nội bộ không?
- Similarity scores có thấp bất thường không?
- Có dùng wrong collection không?

### Debug bằng nearest neighbors

Lấy một query test:

```text
Chính sách nghỉ phép năm nay là gì?
```

In top 10 chunks:

```text
rank=1 score=0.84 doc=HR Policy 2026 section=Annual Leave
rank=2 score=0.79 doc=HR FAQ section=Leave
rank=3 score=0.62 doc=Travel Policy section=Business Trip Leave
```

Nếu top chunks không liên quan:

- embedding model không phù hợp
- chunk text thiếu context
- query rewrite sai
- metadata filter thiếu
- corpus parse/chunk sai

## 24. Monitoring Embedding

### Metrics

- `embedding_requests_total`
- `embedding_texts_total`
- `embedding_tokens_total`
- `embedding_latency_ms`
- `embedding_errors_total`
- `embedding_rate_limit_total`
- `embedding_cache_hit_ratio`
- `embedding_cost_total`
- `embedding_dimension_mismatch_total`

### Trace fields

```json
{
  "embedding_provider": "openai",
  "embedding_model": "text-embedding-3-small",
  "embedding_version": "v1",
  "texts_count": 64,
  "tokens_count": 24000,
  "latency_ms": 720,
  "cache_hit_count": 18,
  "cost_usd": 0.0006
}
```

## 25. Failure Handling

| Failure | Impact | Detection | Recovery | Prevention |
|---|---|---|---|---|
| Provider timeout | Job/query chậm | timeout exception | retry/backoff | timeout config, fallback |
| Rate limit | Ingestion backlog | 429 error | retry later | rate limiter, batching |
| Dimension mismatch | Upsert fail | vector length check | fix config/recreate collection | startup validation |
| Empty text | Bad vector or provider error | text validation | skip/fail chunk | chunk validation |
| Model changed silently | Quality drift | evaluation regression | pin model/version | explicit config |
| Cache poisoning | Wrong vectors | mismatched key/version | clear cache | include model/version/text hash |

## 26. Security And Privacy

Embedding provider may receive sensitive internal text.

Questions to answer before production:

- Có được gửi HR policy ra external API không?
- Có PII trong support tickets không?
- Provider có data retention policy thế nào?
- Có cần masking PII trước embedding không?
- Có tenant nào yêu cầu local-only embedding không?

### PII masking

Với support tickets:

```text
Customer email: user@example.com
Phone: 090...
```

Có thể mask trước embedding:

```text
Customer email: [EMAIL]
Phone: [PHONE]
```

Nhưng cần lưu raw data an toàn nếu citation cần truy xuất.

## 27. Production Checklist

- Query và document dùng cùng embedding model/version.
- Có `EMBEDDING_DIM` đúng với model.
- Vector DB collection dimension đúng.
- Có embedding provider abstraction.
- Có batch embedding.
- Có embedding cache theo model/version/text_hash.
- Có embedding model version table/config.
- Có embedding text template version.
- Có chunk hash để skip unchanged chunks.
- Có startup validation dimension/distance.
- Có re-index strategy khi đổi model.
- Có blue/green collection plan cho corpus lớn.
- Có metrics latency/cost/error/cache hit.
- Có rate limit/backoff.
- Có privacy decision cho external provider.
- Có evaluation trước khi đổi embedding model.

## 28. Tóm Tắt Chương

Embedding là cầu nối giữa text và vector search. Một hệ thống RAG production-grade không chỉ "gọi embed API", mà phải quản lý:

- model selection
- dimension
- similarity metric
- batch embedding
- cache
- versioning
- re-indexing
- cost
- latency
- privacy
- monitoring

Quy tắc quan trọng nhất:

> Document chunks và query phải nằm trong cùng embedding space.

Chương tiếp theo sẽ đi vào vector database và indexing: Qdrant, HNSW, metadata filtering, backup, replication, deletion và các trade-off production. `pgvector` chỉ được nhắc như lựa chọn so sánh, không phải foundation của project này.
