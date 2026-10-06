# 06. Vector Database And Indexing

Chương này đi sâu vào **vector database** và **indexing**, tức nơi lưu vectors của chunks và phục vụ semantic search ở query-time.

Nếu embedding biến text thành vector, thì vector database là hệ thống giúp tìm vector gần nhất nhanh và có filter metadata/permission.

Use case xuyên suốt:

> Internal Knowledge Base RAG cho công ty, gồm HR policies, technical documentation, product documents, FAQ, meeting notes, support tickets và internal wiki.

Trong project hiện tại, chương này map vào:

- `app/indexing/base.py`
- `app/indexing/qdrant_store.py`
- `app/indexing/indexer.py`
- `compose.yml` với service `qdrant`
- `.env` với `QDRANT_URL`, `QDRANT_API_KEY`, `QDRANT_COLLECTION_NAME`, `EMBEDDING_DIM`

Architecture decision của project hiện tại:

```text
PostgreSQL = metadata database
Qdrant     = primary vector database
pgvector   = comparison topic only, not part of the default foundation
```

## 1. Vector Database Là Gì?

Vector database là database được tối ưu để lưu và tìm kiếm vectors.

Trong RAG:

```text
chunk text
  ↓
embedding model
  ↓
vector
  ↓
vector database
```

Khi user hỏi:

```text
query
  ↓
query vector
  ↓
search nearest vectors
  ↓
return relevant chunks
```

Vector DB thường lưu:

- vector
- vector ID
- payload/metadata
- score
- collection/index config

Ví dụ record trong Qdrant:

```json
{
  "id": "company-alpha:hr-policy-2026:v3:c012",
  "vector": [0.01, -0.02, "..."],
  "payload": {
    "tenant_id": "company-alpha",
    "workspace_id": "hr",
    "document_id": "hr-policy-2026",
    "document_version": "v3",
    "chunk_id": "hr-policy-2026-v3-c012",
    "section": "Annual Leave",
    "page_number": 5,
    "allowed_roles": ["employee", "hr"],
    "active": true
  }
}
```

## 2. Vì Sao Cần Vector Database?

### 2.1. Scan vector bằng Python không scale

Nếu có 10.000 chunks, có thể brute force tạm.

Nếu có 10 triệu chunks, mỗi query phải so sánh với 10 triệu vectors. Không ổn.

Vector DB cung cấp:

- ANN index
- optimized search
- filtering
- persistence
- replication/snapshot
- APIs

### 2.2. Cần metadata filtering

RAG production không chỉ search nearest vectors. Nó phải search trong phạm vi user được phép:

```text
tenant_id = current tenant
workspace_id in allowed workspaces
allowed_roles contains user role
active = true
embedding_version = current version
```

Vector DB tốt phải hỗ trợ filter metadata hiệu quả.

### 2.3. Cần vận hành production

Vector DB production cần:

- backup
- restore
- snapshot
- monitoring
- memory management
- index rebuild
- deletion
- sharding
- replication

## 3. Vector DB Nằm Ở Đâu Trong RAG Pipeline?

Offline write path:

```text
chunks
  ↓
embedding
  ↓
vector_store.upsert()
  ↓
Qdrant
```

Online read path:

```text
query vector
  ↓
vector_store.search(filter, top_k)
  ↓
candidate chunks
```

Trong project hiện tại:

```python
class VectorStore(ABC):
    async def upsert(self, chunks: list[VectorChunk]) -> None: ...
    async def search(self, query_vector: list[float], top_k: int) -> list[SearchResult]: ...
    async def delete(self, chunk_ids: list[str]) -> None: ...
```

Interface này rất quan trọng vì giúp giữ retrieval/indexing không phụ thuộc trực tiếp vào SDK Qdrant ở mọi nơi. Foundation hiện tại vẫn chốt Qdrant; việc đổi sang vector DB khác phải là một quyết định kiến trúc riêng.

## 4. ANN Search Là Gì?

ANN là **Approximate Nearest Neighbor**.

Thay vì tìm chính xác vector gần nhất bằng cách scan toàn bộ vectors, ANN tìm gần đúng nhưng nhanh hơn rất nhiều.

### Vì sao approximate?

Với corpus lớn, exact search quá chậm hoặc quá tốn tài nguyên.

ANN trade-off:

```text
Tốc độ nhanh hơn
  đổi lại
Có thể bỏ sót một số nearest neighbors
```

Trong RAG, ANN thường chấp nhận được vì:

- ta retrieve top 20/top 50 candidates
- sau đó rerank
- hybrid search bổ sung keyword path

## 5. HNSW

HNSW là **Hierarchical Navigable Small World graph**.

Nó là index phổ biến trong vector search hiện nay.

### Ý tưởng đơn giản

HNSW xây một graph nhiều tầng. Khi search, hệ thống đi qua graph để đến vùng có vectors gần query.

### Ưu điểm

- search nhanh
- recall tốt
- phổ biến trong Qdrant, Weaviate, Milvus, pgvector mới
- phù hợp RAG interactive query

### Nhược điểm

- tốn memory
- index build có thể chậm
- config ảnh hưởng recall/latency

### Config quan trọng

| Config | Ý nghĩa |
|---|---|
| `m` | số edge mỗi node, cao hơn recall tốt hơn nhưng tốn memory |
| `ef_construct` | chất lượng index khi build |
| `ef_search` | chất lượng search query-time |

Trade-off:

```text
ef_search cao hơn -> recall tốt hơn -> latency cao hơn
```

## 6. IVF

IVF là **Inverted File Index**.

Ý tưởng:

- chia vector space thành clusters
- query chỉ search một số clusters gần nhất

### Ưu điểm

- tốt cho corpus lớn
- giảm search space

### Nhược điểm

- cần training/build clusters
- nếu chọn cluster sai có thể mất recall
- tuning phức tạp hơn

IVF phổ biến trong FAISS/Milvus.

## 7. PQ

PQ là **Product Quantization**.

Ý tưởng:

- nén vector để giảm memory/storage
- search trên vector nén

### Ưu điểm

- tiết kiệm memory
- phù hợp scale rất lớn

### Nhược điểm

- giảm accuracy
- config phức tạp
- không cần sớm ở project học/prototype

Với Internal KB nhỏ/vừa, ưu tiên HNSW trước.

## 8. Indexing Strategy

Indexing strategy là cách tổ chức vectors trong vector DB.

### 8.1. Một collection chung

```text
collection: rag_chunks
payload: tenant_id, workspace_id, document_id, embedding_version, active
```

Ưu điểm:

- đơn giản
- ít collection
- dễ query cross-workspace nếu có quyền

Nhược điểm:

- phải filter tenant/permission cực cẩn thận
- index lớn dần

Phù hợp giai đoạn đầu project hiện tại.

### 8.2. Collection theo embedding version

```text
rag_chunks_v1
rag_chunks_v2
```

Ưu điểm:

- blue/green re-index dễ
- rollback dễ
- tránh trộn vector space

Nhược điểm:

- cần routing collection
- tốn storage khi giữ nhiều version

Khuyến nghị production khi đổi embedding model.

### 8.3. Collection theo tenant

```text
rag_chunks_tenant_alpha
rag_chunks_tenant_beta
```

Ưu điểm:

- tenant isolation tốt hơn
- giảm rủi ro filter miss

Nhược điểm:

- nhiều collection
- vận hành phức tạp nếu nhiều tenant
- khó optimize global config

### 8.4. Collection theo document type

```text
hr_policy_chunks
technical_docs_chunks
support_ticket_chunks
```

Thường không khuyến nghị ban đầu, vì query có thể cần search nhiều loại tài liệu.

Chỉ nên dùng nếu:

- workload rất khác nhau
- embedding model khác nhau
- permission/index config khác nhau

## 9. Metadata Filtering

Metadata filtering là search vector trong phạm vi payload nhất định.

Ví dụ:

```json
{
  "tenant_id": "company-alpha",
  "workspace_id": "hr",
  "active": true,
  "document_type": "hr_policy"
}
```

### Vì sao cần?

- tenant isolation
- permission control
- filter theo workspace/department
- filter theo document version
- filter theo year/time
- filter active chunks

### Filter trước hay sau vector search?

Production nên filter **trong vector DB query**, không retrieve rồi mới lọc.

Sai:

```text
vector search all corpus top 100
  ↓
filter unauthorized chunks in Python
```

Đúng:

```text
vector search with tenant/permission filter
  ↓
return only authorized chunks
```

### Vì sao?

Nếu retrieve rồi mới lọc:

- private chunks đã rời vector DB
- có thể log vào trace
- có thể leak nếu bug
- top-k sau filter có thể rỗng dù có authorized chunks ở rank thấp hơn

## 10. Hybrid Metadata + Vector Search

Hybrid metadata + vector search nghĩa là kết hợp:

- semantic similarity
- metadata filter

Ví dụ query:

```text
chính sách nghỉ phép năm ngoái thay đổi gì?
```

Filter:

```json
{
  "department": "HR",
  "year": 2025,
  "tenant_id": "company-alpha",
  "allowed_roles": {"contains": "employee"}
}
```

Vector search chỉ chạy trong tập này.

### Lỗi thường gặp

- Filter quá hẹp làm empty retrieval.
- Metadata thiếu hoặc inconsistent.
- Type mismatch: year stored string `"2025"` nhưng filter integer `2025`.
- Permission arrays không index tốt.

### Debug

Khi retrieval empty:

1. Search without filter.
2. Search with tenant only.
3. Add workspace.
4. Add permission.
5. Add document_type/year.
6. Xem filter nào làm mất kết quả.

## 11. Vector DB Vs PostgreSQL pgvector

### Vector DB chuyên dụng

Ví dụ:

- Qdrant
- Weaviate
- Milvus
- Pinecone
- Chroma

Ưu điểm:

- vector search optimized
- metadata filtering tốt
- APIs cho vector workload
- snapshot/replication tùy DB
- scale vector tốt hơn

Nhược điểm:

- thêm một hệ thống cần vận hành
- consistency với PostgreSQL cần xử lý

### PostgreSQL + pgvector

Ưu điểm:

- dùng cùng PostgreSQL
- transactional với metadata
- đơn giản vận hành giai đoạn đầu
- good enough cho corpus nhỏ/vừa

Nhược điểm:

- scale vector search lớn kém hơn vector DB chuyên dụng
- tuning index cần hiểu PostgreSQL
- heavy vector workload có thể ảnh hưởng metadata DB

### Khi nào dùng pgvector?

- corpus nhỏ/vừa
- team muốn đơn giản
- cần transaction mạnh với metadata
- không muốn vận hành thêm Qdrant/Milvus

### Khi nào dùng Qdrant/Milvus/Weaviate?

- corpus lớn
- filter/query vector nặng
- cần scale riêng vector workload
- cần snapshot/index/collection management tốt

Project hiện tại có `VectorStore` abstraction, đây là thiết kế tốt. Nhưng lựa chọn mặc định phải rõ: `QdrantVectorStore` là primary vector store. Không có `PgVectorStore` trong foundation mặc định để tránh nhầm lẫn embeddings được lưu/search ở đâu.

## 12. So Sánh Vector DB

| Option | Phù hợp | Ưu điểm | Nhược điểm |
|---|---|---|---|
| pgvector | Start đơn giản, corpus nhỏ/vừa | Một DB, transaction tốt, dễ backup cùng Postgres | Scale vector lớn hạn chế hơn |
| Qdrant | RAG production nhỏ đến lớn | API rõ, filter mạnh, Docker dễ, payload tốt | Thêm service cần vận hành |
| Weaviate | Semantic search + schema rich | Có hybrid, modules, GraphQL/REST | Vận hành/schema phức tạp hơn |
| Milvus | Scale rất lớn | Mạnh cho billion-scale vector | Vận hành nặng hơn |
| Pinecone | Managed vector DB | Ít vận hành, scale managed | Cost/vendor lock-in |
| Chroma | Local/dev/prototype | Dễ học, đơn giản | Không phải lựa chọn production mạnh cho scale lớn |
| Elasticsearch/OpenSearch vector | Đã có search infra | Hybrid keyword + vector tự nhiên | Vector capability/tuning phức tạp, cost cao |

## 13. Qdrant Cho Project Hiện Tại

`compose.yml` đã có:

```yaml
qdrant:
  image: qdrant/qdrant:latest
  ports:
    - "6333:6333"
    - "6334:6334"
  volumes:
    - qdrant_data:/qdrant/storage
```

Postgres trong compose mặc định nên là metadata DB thường:

```yaml
postgres:
  image: postgres:16
```

Không cần dùng `pgvector/pgvector:pg16` khi Qdrant đã là vector DB chính.

`.env`:

```env
QDRANT_URL=http://qdrant:6333
QDRANT_API_KEY=
QDRANT_COLLECTION_NAME=rag_chunks
EMBEDDING_DIM=1536
```

### Collection creation concept

```python
await qdrant.create_collection(
    collection_name=settings.qdrant_collection_name,
    vectors_config={
        "size": settings.embedding_dim,
        "distance": "Cosine",
    },
)
```

### Payload index

For production filtering, create payload indexes for:

- `tenant_id`
- `workspace_id`
- `document_id`
- `document_version`
- `active`
- `document_type`
- `allowed_roles`
- `year`

Payload index giúp filter nhanh hơn.

## 14. VectorStore Interface Production Design

Project hiện tại:

```python
class VectorStore(ABC):
    async def upsert(self, chunks: list[VectorChunk]) -> None: ...
    async def search(self, query_vector: list[float], top_k: int) -> list[SearchResult]: ...
    async def delete(self, chunk_ids: list[str]) -> None: ...
```

Production nên mở rộng search filter:

```python
@dataclass
class VectorSearchQuery:
    query_vector: list[float]
    top_k: int
    filters: dict
    score_threshold: float | None = None
    include_vectors: bool = False


class VectorStore(ABC):
    async def ensure_collection(self) -> None: ...
    async def upsert(self, chunks: list[VectorChunk]) -> None: ...
    async def search(self, query: VectorSearchQuery) -> list[SearchResult]: ...
    async def delete(self, vector_ids: list[str]) -> None: ...
    async def soft_delete_by_document_version(self, document_version_id: str) -> None: ...
```

### Vì sao cần `ensure_collection`?

Để startup validate:

- collection exists
- dimension đúng
- distance metric đúng
- payload indexes có đủ

## 15. Upsert Strategy

Upsert nghĩa là insert nếu chưa có, update nếu đã có.

### Vì sao cần upsert?

Ingestion worker có thể retry. Nếu dùng insert mù:

- duplicate vector
- lỗi conflict
- partial state khó xử lý

Stable vector ID + upsert giúp idempotent.

Vector ID:

```text
{tenant_id}:{document_id}:{document_version}:{chunk_id}:{embedding_version}
```

Pseudo-code:

```python
async def index_chunks(chunks, embeddings):
    vector_chunks = []

    for chunk, vector in zip(chunks, embeddings):
        vector_chunks.append(
            VectorChunk(
                id=chunk.vector_id,
                text=chunk.content,
                vector=vector,
                metadata={
                    **chunk.metadata,
                    "chunk_id": chunk.id,
                    "document_id": chunk.document_id,
                    "document_version_id": chunk.document_version_id,
                    "active": True,
                },
            )
        )

    await vector_store.upsert(vector_chunks)
```

## 16. Deletion Strategy

Vector deletion phức tạp hơn tưởng.

### Hard delete

Xóa vector khỏi DB.

Ưu điểm:

- tiết kiệm storage
- không retrieve nhầm

Nhược điểm:

- audit/citation cũ mất source
- rollback khó
- nếu delete nhầm khó phục hồi

### Soft delete

Set payload:

```json
{
  "active": false,
  "deleted_at": "2026-05-27T10:00:00+07:00"
}
```

Retrieval filter:

```json
{
  "active": true
}
```

Ưu điểm:

- audit tốt
- rollback dễ
- safer

Nhược điểm:

- storage tăng
- cần cleanup định kỳ

Production thường:

- soft delete trước
- hard delete sau retention period

## 17. Re-indexing

Re-index xảy ra khi:

- đổi embedding model
- đổi chunking strategy
- đổi parser/cleaner lớn
- vector index corrupt
- metadata filter schema đổi

### In-place re-index

Đơn giản nhưng rủi ro.

```text
delete old vectors
embed all chunks
upsert new vectors
```

### Blue/green re-index

An toàn hơn.

```text
rag_chunks_v1 serving traffic
  ↓
build rag_chunks_v2 in background
  ↓
run evaluation
  ↓
switch read pointer to v2
  ↓
keep v1 for rollback
```

### Routing config

```json
{
  "active_vector_collection": "rag_chunks_v2",
  "embedding_model": "text-embedding-3-small",
  "embedding_version": "v2"
}
```

## 18. Index Build Time

Index build time phụ thuộc:

- số vectors
- dimension
- HNSW config
- hardware
- payload index count
- batch size

Nếu build index lớn, cần:

- background indexing
- progress monitoring
- backpressure
- rate limit
- blue/green strategy

Metrics:

- vectors indexed per second
- index build duration
- failed upserts
- collection size

## 19. Memory Usage

Vector DB memory usage đến từ:

- raw vectors
- ANN index
- payload indexes
- cache
- replication

Rough estimate:

```text
vector_size = dimension * 4 bytes
1M vectors, 1536 dims ≈ 6 GB raw vectors
HNSW overhead can be significant
payload/index overhead additional
```

Production cần:

- estimate corpus size
- monitor memory
- plan sharding/replication
- avoid unnecessary overlap/duplicate chunks

## 20. Replication

Replication là có nhiều bản sao dữ liệu để tăng availability.

### Vì sao cần?

Nếu vector DB node down, retrieval fail.

### Trade-off

- availability tốt hơn
- cost cao hơn
- write complexity cao hơn

Giai đoạn local/dev không cần. Production cần tùy SLA.

## 21. Backup And Snapshot

Vector DB cần backup giống database thường.

### Backup gì?

- vector collections
- payload metadata
- collection config
- snapshots
- mapping embedding version

### Vì sao không chỉ backup PostgreSQL?

PostgreSQL lưu metadata chunks, nhưng vectors nằm ở Qdrant. Nếu Qdrant mất:

- retrieval không chạy
- phải re-embed/re-index toàn bộ nếu không có snapshot
- tốn thời gian/cost

### Backup strategy

```text
Daily Qdrant snapshot
  ↓
Upload snapshot to object storage
  ↓
Keep retention 7/30/90 days
  ↓
Test restore monthly
```

Production checklist:

- snapshot schedule
- offsite storage
- restore test
- matching PostgreSQL backup timestamp
- document current active collection version

## 22. Sharding

Sharding chia dữ liệu thành nhiều shard để scale.

### Shard theo gì?

Options:

- tenant_id
- collection shard built-in
- document type
- hash vector ID

### Khi nào cần?

- corpus rất lớn
- memory vượt một node
- query latency tăng
- tenant isolation cần mạnh

### Trade-off

- scale tốt hơn
- vận hành phức tạp hơn
- query cross-shard khó hơn

Giai đoạn đầu project hiện tại chưa cần sharding custom. Dùng Qdrant collection + metadata filter là đủ.

## 23. Tenant Isolation

Multi-tenant RAG cần tránh data leakage.

### Option 1: shared collection + tenant filter

```json
{
  "tenant_id": "company-alpha"
}
```

Ưu điểm:

- đơn giản
- ít collection

Nhược điểm:

- nếu quên filter là leak

Phải enforce filter ở code, không để caller tùy ý.

### Option 2: collection per tenant

Ưu điểm:

- isolation tốt hơn

Nhược điểm:

- nhiều collection
- khó quản lý nếu tenant nhiều

### Option 3: cluster per tenant

Enterprise cao cấp, cost cao.

### Khuyến nghị

Giai đoạn đầu:

- shared collection
- bắt buộc tenant filter trong retrieval service
- test security kỹ

Không để route/API truyền filter raw trực tiếp từ client vào vector DB.

## 24. Metadata Filtering Performance

Filter performance phụ thuộc:

- payload index
- cardinality field
- filter complexity
- collection size
- vector DB implementation

### Field nên index

- `tenant_id`
- `workspace_id`
- `document_id`
- `document_version_id`
- `active`
- `document_type`
- `year`
- `source_type`

### Field cẩn thận

- array `allowed_user_ids` rất lớn
- high-cardinality free text
- nested JSON quá phức tạp

Permission filtering nên thiết kế metadata tối ưu:

```json
{
  "tenant_id": "company-alpha",
  "workspace_id": "engineering",
  "visibility": "department",
  "allowed_role_keys": ["employee", "backend_engineer"]
}
```

## 25. Consistency Với PostgreSQL

Metadata chính thường nằm ở PostgreSQL, vector nằm ở Qdrant.

Vấn đề:

- DB save chunk thành công, Qdrant upsert fail.
- Qdrant upsert thành công, DB update vector_id fail.
- Delete document ở DB nhưng vector vẫn active.

### Giải pháp

- stable vector ID
- idempotent upsert
- job status
- reconciliation job
- cleanup orphan vectors
- active flag

Reconciliation job:

```text
List active chunks in PostgreSQL
  ↓
Check vector IDs exist in Qdrant
  ↓
Repair missing vectors or mark inconsistent
```

Cleanup job:

```text
List vectors active=true in Qdrant
  ↓
Check chunk exists/active in PostgreSQL
  ↓
Soft delete orphan vectors
```

## 26. Search Result Shape

Search result nên trả đủ dữ liệu để debug và rerank.

```python
@dataclass
class SearchResult:
    id: str
    score: float
    text: str
    metadata: dict
```

Production có thể mở rộng:

```python
@dataclass
class SearchResult:
    vector_id: str
    chunk_id: str
    document_id: str
    document_version_id: str
    score: float
    content: str
    metadata: dict
    retrieval_source: str = "vector"
```

### Vì sao cần `retrieval_source`?

Hybrid retrieval sẽ merge:

- vector results
- BM25 results
- graph results
- parent retriever results

Debug cần biết result đến từ đâu.

## 27. Score Threshold

Score threshold là ngưỡng similarity tối thiểu.

Ví dụ:

```text
Only accept chunks with score >= 0.72
```

### Trade-off

| Threshold | Ưu điểm | Nhược điểm |
|---|---|---|
| Cao | Precision tốt hơn | Dễ empty retrieval |
| Thấp | Recall tốt hơn | Nhiều noise |

Không có threshold universal. Phải benchmark theo embedding model và corpus.

Production thường:

- retrieve top 50
- apply loose threshold
- rerank
- final top 5

## 28. Qdrant Search Pseudo-code

```python
async def search(self, query: VectorSearchQuery) -> list[SearchResult]:
    qdrant_filter = build_qdrant_filter(query.filters)

    response = await self.client.search(
        collection_name=self.collection_name,
        query_vector=query.query_vector,
        query_filter=qdrant_filter,
        limit=query.top_k,
        score_threshold=query.score_threshold,
        with_payload=True,
        with_vectors=query.include_vectors,
    )

    return [
        SearchResult(
            vector_id=str(point.id),
            chunk_id=point.payload["chunk_id"],
            document_id=point.payload["document_id"],
            document_version_id=point.payload["document_version_id"],
            score=point.score,
            content=point.payload.get("content", ""),
            metadata=point.payload,
            retrieval_source="vector",
        )
        for point in response
    ]
```

## 29. Index Validation At Startup

Khi app start, nên validate vector DB config.

Checks:

- Qdrant reachable.
- Collection exists.
- Dimension matches `EMBEDDING_DIM`.
- Distance metric expected.
- Payload indexes exist.
- Active embedding version matches config.

Pseudo-code:

```python
async def validate_vector_store():
    info = await vector_store.get_collection_info(settings.qdrant_collection_name)

    if info.dimension != settings.embedding_dim:
        raise RuntimeError("Qdrant collection dimension mismatch")

    if info.distance != "Cosine":
        raise RuntimeError("Qdrant distance metric mismatch")
```

Fail fast tốt hơn là để query runtime mới lỗi.

## 30. Observability

### Logs

```json
{
  "event": "vector_search_completed",
  "request_id": "req_123",
  "collection": "rag_chunks",
  "top_k": 50,
  "filters": {
    "tenant_id": "company-alpha",
    "workspace_id": "hr"
  },
  "results_count": 50,
  "latency_ms": 42
}
```

### Metrics

- `vector_search_latency_ms`
- `vector_upsert_latency_ms`
- `vector_delete_latency_ms`
- `vector_search_errors_total`
- `vector_upsert_errors_total`
- `vector_results_count`
- `vector_empty_results_total`
- `vector_collection_size`
- `vector_memory_usage`

### Trace fields

- collection
- embedding_version
- top_k
- filters
- score_threshold
- result ids
- scores
- latency

## 31. Common Failures

| Failure | Impact | Detection | Recovery | Prevention |
|---|---|---|---|---|
| Dimension mismatch | Upsert/search fail | startup/search error | recreate collection/re-embed | config validation |
| Missing tenant filter | Data leakage | security tests/log review | hotfix/block | enforce filter in service |
| Vector DB down | Retrieval unavailable | health check | fallback/no-answer | replication, circuit breaker |
| Slow filter | High latency | p95 vector latency | payload indexes | index important fields |
| Orphan vectors | Stale answers | reconciliation | cleanup job | idempotent writes |
| Wrong collection version | Bad retrieval | trace embedding_version | switch routing | config/version validation |
| Hard delete needed data | Broken citation | audit failure | restore snapshot | soft delete first |

## 32. Production Checklist

- VectorStore abstraction exists.
- Collection dimension matches embedding model.
- Distance metric matches model recommendation.
- Stable vector IDs.
- Upsert is idempotent.
- Metadata payload includes tenant/workspace/document/chunk/version.
- Permission metadata included in payload.
- Search uses metadata/permission filters inside vector DB.
- Payload indexes exist for frequent filters.
- Active flag used for soft delete.
- Re-index strategy documented.
- Blue/green collection plan for embedding model change.
- Backup/snapshot schedule.
- Restore test.
- Reconciliation job for Postgres/Qdrant consistency.
- Vector DB health check.
- Metrics/logs/traces for vector operations.
- Security tests ensure tenant filter cannot be skipped.

## 33. Recommended Path Cho Project Hiện Tại

Giai đoạn 1:

- Hoàn thiện `QdrantVectorStore`.
- Tạo collection `rag_chunks`.
- Dùng shared collection + metadata filters.
- Upsert stable vector IDs.
- Store metadata in payload.

Giai đoạn 2:

- Add payload indexes.
- Add `ensure_collection`.
- Add vector search filter object.
- Add soft delete by document version.
- Add startup validation.

Giai đoạn 3:

- Add blue/green collection for embedding version.
- Add reconciliation/cleanup jobs.
- Add backup/snapshot docs/scripts.
- Nếu muốn benchmark pgvector, làm ở nhánh thử nghiệm riêng, không đưa vào default ingestion/retrieval path.

## 34. Tóm Tắt Chương

Vector DB là layer phục vụ semantic retrieval. Trong production, nó không chỉ là nơi lưu vectors. Nó phải hỗ trợ:

- ANN search
- metadata filtering
- permission filtering
- idempotent upsert
- soft delete
- re-index
- backup/restore
- tenant isolation
- observability
- consistency với PostgreSQL

Project hiện tại đã có Qdrant trong Docker Compose và `VectorStore` abstraction. Hướng tiếp theo là triển khai Qdrant store thật, sau đó xây retrieval pipeline trên nó.

Chương tiếp theo sẽ đi sâu vào retrieval strategies: similarity search, top-k, metadata filter, BM25, hybrid search, multi-query, parent retriever, contextual retrieval và permission-aware retrieval.
