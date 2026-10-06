# SeaweedFS, PostgreSQL And Ingestion Flow

Tài liệu này mô tả cách RAG service và Spring Boot dùng chung SeaweedFS nhưng
giữ ownership rõ ràng:

```text
LIBRARY_EBOOK = Spring Boot upload vào library-private, RAG ingest bằng bucket/object key.
BATCH_IMPORT  = Spring Boot staging CSV/ZIP/PDF trong library-temp rồi dọn theo retention.
RAG_ARTIFACT  = RAG ghi kết quả trung gian vào rag-artifacts.
```

Contract tích hợp đầy đủ với Spring Boot: `docs/integration/springboot-rag-ingestion-contract.md`.

## 1. Vai trò của từng storage

```text
SeaweedFS  = source files và ingestion artifacts
PostgreSQL = document/artifact/job metadata và trạng thái
Redis      = Celery broker/result backend
Qdrant     = vectors, chunk text và retrieval payload
```

Không lưu PDF trong PostgreSQL, Redis hoặc Qdrant. Không lưu URL đầy đủ của SeaweedFS trong database; chỉ lưu bucket và object key.

## 2. Bucket convention

| Bucket | Owner ghi | Nội dung |
| --- | --- | --- |
| `library-private` | Spring Boot | PDF ebook của hệ thống thư viện. |
| `library-temp` | Spring Boot | CSV/ZIP/nhiều PDF, manifest và dữ liệu batch import tạm. |
| `rag-artifacts` | RAG worker | Parsed text, cleaned text, chunks snapshot và parse log. |

Object keys:

```text
library-private/ebooks/{bookId}/{ebookId}/original.pdf
library-temp/imports/{importId}/manifest.csv
library-temp/imports/{importId}/...
rag-artifacts/documents/{documentId}/parsed_text.txt
rag-artifacts/documents/{documentId}/cleaned_text.txt
rag-artifacts/documents/{documentId}/chunks.jsonl
rag-artifacts/documents/{documentId}/failed_parse.log
```

## 3. Endpoint theo vị trí chạy

| Consumer | Endpoint |
| --- | --- |
| RAG API/worker trong Compose | `http://seaweedfs:8333` |
| Spring Boot trên shared network | `http://rag-seaweedfs:8333` |
| Tool chạy trực tiếp trên host | `http://localhost:8333` |

`localhost` bên trong container luôn trỏ về chính container đó, không trỏ tới SeaweedFS.

## 4. Biến môi trường RAG

```dotenv
STORAGE_BACKEND=s3
OBJECT_STORAGE_ENDPOINT=http://seaweedfs:8333
OBJECT_STORAGE_REGION=us-east-1
OBJECT_STORAGE_ACCESS_KEY=admin
OBJECT_STORAGE_SECRET_KEY=secret
LIBRARY_EBOOK_BUCKET=library-private
LIBRARY_TEMP_BUCKET=library-temp
RAG_ARTIFACT_BUCKET=rag-artifacts
# Alias tương thích ngược, không phải bucket thứ tư.
RAG_SOURCE_BUCKET=library-private
```

Credential mặc định chỉ dành cho local development và phải được rotate khi deploy ngoài máy phát triển.

## 5. Luồng library ebook

```text
Spring Boot validates PDF and permission
  -> PUT library-private/ebooks/{bookId}/{ebookId}/original.pdf
  -> commit library metadata
  -> POST RAG /internal/ingestions with bucket + objectKey + checksum
  -> RAG creates/reuses document and ingestion job
  -> RAG enqueues Celery task through Redis
  -> worker downloads object from library-private
  -> parse, clean, chunk, embed
  -> upsert Qdrant
  -> update ingestion status
```

SeaweedFS không gửi webhook trong MVP. Spring Boot là orchestrator vì nó biết transaction nghiệp vụ đã commit và có đầy đủ `bookId`/`ebookId`.

## 6. Luồng batch import tạm

```text
Spring Boot creates an import ID
  -> PUT manifest.csv / ZIP / PDF files into library-temp/imports/{importId}/
  -> validate and promote accepted ebooks into library-private
  -> trigger the normal RAG ingestion flow
  -> delete temporary objects after the configured retention period
```

RAG không sở hữu source bucket riêng. Nếu bổ sung connector sau này, connector
phải đi qua ownership/contract của Library system hoặc có bucket được thiết kế
riêng bằng một quyết định kiến trúc mới.

## 7. Metadata cần lưu

Metadata object tối thiểu:

```text
bucket_name
object_key
original_filename
content_type
file_size_bytes
checksum_sha256
```

`object_key` phải độc lập với endpoint để cùng metadata có thể dùng ở local, staging hoặc production.

## 8. Bucket initialization

`seaweedfs-init` trong `compose.yml` tạo ba bucket theo cách idempotent:

```bash
docker compose up -d seaweedfs
docker compose up seaweedfs-init
```

Kiểm tra bằng AWS CLI:

```bash
aws --endpoint-url http://localhost:8333 s3 ls
```

Kết quả cần có:

```text
library-private
library-temp
rag-artifacts
```

## 9. Consistency và cleanup

Không có distributed transaction giữa PostgreSQL, SeaweedFS, Redis và Qdrant. Vì vậy:

- Chỉ enqueue sau khi metadata cần thiết đã commit.
- Ingestion request và worker phải idempotent.
- Upload thành công nhưng DB fail có thể tạo orphan object; cần reconciliation/cleanup job.
- Object trong `library-temp` cần lifecycle hoặc cleanup job xóa sau vài ngày.
- Qdrant upsert thành công nhưng job update fail phải retry bằng deterministic point IDs.
- Không xóa source object chỉ vì ingestion thất bại; source vẫn cần cho retry/debug.

## 10. Trạng thái implementation

Đã có:

- SeaweedFS S3 service và ba bucket chuẩn.
- S3-compatible storage adapter.
- Source download theo bucket được ghi trên document artifact.
- Celery worker/Redis queue và ingestion job tracking.
- Internal API-key authentication, idempotency cơ bản và status endpoint.

Chưa có:

- Hoàn chỉnh embedding/Qdrant indexing và ghi artifact files vào `rag-artifacts`.
- Batch import implementation và lifecycle cleanup cho `library-temp`.
