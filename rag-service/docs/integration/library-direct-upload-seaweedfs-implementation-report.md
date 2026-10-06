# Báo cáo hướng implement: Library backend upload PDF trực tiếp lên SeaweedFS

## 1. Kết luận nhanh

Hướng nên làm trước là:

```text
Browser
  -> upload multipart PDF vào Library Spring Boot
Library Spring Boot
  -> validate quyền, file type, size
  -> upload PDF vào SeaweedFS bucket library-private
  -> lưu metadata file vào database Library
  -> sau khi commit DB thành công, gọi RAG /internal/ingestions
RAG
  -> đọc PDF từ library-private
  -> parse/clean/chunk
  -> về sau embed + upsert Qdrant
```

Không để browser upload trực tiếp trong phase này. Không dùng Cloudinary cho PDF ebook. Không để Library gọi Qdrant. Không cần bucket `rag-source`.

Điểm quan trọng nhất hiện tại: RAG đang validate object key cố định:

```text
ebooks/{bookId}/{ebookId}/original.pdf
```

Vì vậy bên Library phải upload đúng key này nếu muốn gọi RAG API hiện tại mà không sửa contract.

## 2. Tình trạng SeaweedFS hiện tại trong RAG stack

Đã kiểm tra từ `compose.yml` và runtime container.

### 2.1. Service SeaweedFS

SeaweedFS đang chạy bằng image:

```text
chrislusf/seaweedfs:3.85
```

Command hiện tại bật đủ:

```text
S3 gateway : 8333
Filer      : 8888
Master     : 9333
Volume     : 8080 trong container, map ra host 18080
```

Trong RAG container, endpoint nội bộ là:

```text
http://seaweedfs:8333
```

Từ Library service qua shared Docker network, endpoint nên là:

```text
http://rag-seaweedfs:8333
```

Từ host máy dev, endpoint là:

```text
http://localhost:8333
```

Mở `http://localhost:8333` bằng browser bị `AccessDenied` là bình thường, vì đó là S3 API endpoint, không phải web UI.

### 2.2. Bucket hiện có

Đã kiểm tra bằng boto3 từ container `api`, hiện có đúng 3 bucket:

```text
library-private
library-temp
rag-artifacts
```

Ý nghĩa:

| Bucket | Owner ghi chính | Mục đích |
| --- | --- | --- |
| `library-private` | Library Spring Boot | PDF ebook gốc. |
| `library-temp` | Library Spring Boot | File tạm cho import CSV/ZIP/batch upload. |
| `rag-artifacts` | RAG worker | File trung gian do RAG sinh ra. |

`seaweedfs-init` trong Compose tạo 3 bucket này idempotent. Chạy lại không tạo trùng.

### 2.3. Network hiện tại

Thiết kế đang đúng hướng:

```text
library-platform-net
  - library-service
  - rag-api
  - rag-seaweedfs

rag-internal-net
  - rag-api
  - rag-worker
  - postgres
  - redis
  - qdrant
  - seaweedfs
```

Qdrant đã được tách khỏi `library-platform-net`. Library không cần và không nên truy cập Qdrant trực tiếp.

### 2.4. Trạng thái RAG ingestion hiện tại

RAG đã có:

- Internal API auth bằng header `X-RAG-API-Key`.
- Endpoint `POST /internal/ingestions`.
- Endpoint `GET /internal/ingestions/{jobId}`.
- Validate bucket phải là `library-private`.
- Validate object key phải là `ebooks/{bookId}/{ebookId}/original.pdf`.
- Lưu `Document`, `DocumentArtifact`, `IngestionJob`.
- Worker tải PDF từ SeaweedFS bằng bucket/object key.
- Parse PDF, clean text, chunk text.
- Lưu chunks vào RAG PostgreSQL.
- Embed chunks bằng Gemini Embedding.
- Upsert vectors + payload vào Qdrant.
- Trạng thái thành công hiện tại là `INDEXED`.

Chưa hoàn chỉnh:

- Retrieval/search baseline để hỏi đáp trên các chunks đã index.
- Soft delete/reconciliation khi replace/re-index document.

## 3. Config cần có bên Library service

Trong `.env` của Library:

```dotenv
RAG_SERVICE_URL=http://rag-api:8000
RAG_INTERNAL_API_KEY=<giống-hệt-bên-RAG>

OBJECT_STORAGE_ENDPOINT=http://rag-seaweedfs:8333
OBJECT_STORAGE_PUBLIC_ENDPOINT=http://localhost:8333
OBJECT_STORAGE_ACCESS_KEY=admin
OBJECT_STORAGE_SECRET_KEY=secret
OBJECT_STORAGE_REGION=us-east-1

LIBRARY_EBOOK_BUCKET=library-private
LIBRARY_TEMP_BUCKET=library-temp
RAG_ARTIFACT_BUCKET=rag-artifacts
```

Ghi chú:

- `OBJECT_STORAGE_ENDPOINT` là endpoint backend dùng để upload/read object.
- `OBJECT_STORAGE_PUBLIC_ENDPOINT` chưa quan trọng ở phase backend direct upload, nhưng nên giữ để sau này dùng download/presigned URL.
- Local dev có thể dùng `admin/secret`, production phải đổi secret mạnh.
- Không hardcode access key/secret trong source code.

## 4. Quy ước object key bắt buộc

Với API RAG hiện tại, PDF ebook phải nằm ở:

```text
bucket:    library-private
objectKey: ebooks/{bookId}/{ebookId}/original.pdf
```

Ví dụ:

```text
bucket:    library-private
objectKey: ebooks/101/55/original.pdf
```

Không lưu URL đầy đủ vào database:

```text
Sai:
http://localhost:8333/library-private/ebooks/101/55/original.pdf

Đúng:
bucket    = library-private
objectKey = ebooks/101/55/original.pdf
```

Lý do: endpoint thay đổi theo môi trường local/staging/production, còn bucket/key thì ổn định.

## 5. Thiết kế database bên Library

Library nên lưu metadata PDF tối thiểu trong bảng ebook hoặc bảng ebook_file riêng.

Các field đề xuất:

```text
id
book_id
ebook_id
bucket_name
object_key
original_filename
content_type
file_size_bytes
checksum_sha256
upload_status
ingestion_status
rag_document_id
rag_job_id
last_error
uploaded_at
indexing_requested_at
created_at
updated_at
```

Status đề xuất:

```text
upload_status:
  PENDING | UPLOADING | COMPLETED | FAILED

ingestion_status:
  NOT_REQUESTED | QUEUED | PROCESSING | CHUNKED | INDEXED | FAILED
```

Hiện tại RAG trả terminal success là `INDEXED`. `CHUNKED` vẫn là trạng thái
trung gian sau khi chunks/artifacts đã được persist; `INDEXED` chỉ xuất hiện sau
khi embedding và Qdrant upsert thành công.

## 6. Spring Boot implementation blueprint

### 6.1. Dependency

Dùng AWS SDK S3 client vì SeaweedFS tương thích S3.

Maven:

```xml
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>s3</artifactId>
</dependency>
```

Gradle:

```groovy
implementation "software.amazon.awssdk:s3"
```

Nếu project chưa có BOM quản lý version, cần set version AWS SDK thống nhất trong dependency management.

### 6.2. Properties

Ví dụ mapping property:

```properties
app.object-storage.endpoint=${OBJECT_STORAGE_ENDPOINT}
app.object-storage.region=${OBJECT_STORAGE_REGION:us-east-1}
app.object-storage.access-key=${OBJECT_STORAGE_ACCESS_KEY}
app.object-storage.secret-key=${OBJECT_STORAGE_SECRET_KEY}
app.object-storage.library-ebook-bucket=${LIBRARY_EBOOK_BUCKET:library-private}
```

### 6.3. S3Client bean

Điểm bắt buộc là bật path-style access.

```java
@Bean
S3Client s3Client(ObjectStorageProperties props) {
    return S3Client.builder()
            .endpointOverride(URI.create(props.endpoint()))
            .region(Region.of(props.region()))
            .credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(props.accessKey(), props.secretKey())
                    )
            )
            .serviceConfiguration(
                    S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build()
            )
            .build();
}
```

Nếu không bật path-style, SDK có thể tạo URL kiểu:

```text
http://library-private.rag-seaweedfs:8333/ebooks/...
```

Kiểu này thường lỗi trong Docker/local S3. Cần path-style:

```text
http://rag-seaweedfs:8333/library-private/ebooks/...
```

### 6.4. Upload service flow

Pseudo flow:

```text
1. Nhận MultipartFile từ controller.
2. Check user có quyền upload ebook cho book đó.
3. Check file không rỗng.
4. Check content type/extension là PDF.
5. Check size <= limit.
6. Đảm bảo đã có bookId và ebookId.
7. Tạo objectKey = ebooks/{bookId}/{ebookId}/original.pdf.
8. Tính SHA-256.
9. Upload object lên SeaweedFS.
10. HEAD object để verify size/checksum metadata nếu cần.
11. Lưu bucket/key/checksum/size vào DB.
12. Sau DB commit, gọi RAG /internal/ingestions.
13. Lưu ragJobId/ragDocumentId/status.
```

Với file lớn, không nên đọc toàn bộ PDF vào memory. Nên stream ra temp file trong lúc tính checksum, rồi upload temp file bằng `RequestBody.fromFile`.

Metadata nên ghi cùng object:

```text
sha256
original-filename
ebook-id
book-id
```

Ví dụ `PutObjectRequest`:

```java
PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucket)
        .key(objectKey)
        .contentType("application/pdf")
        .contentLength(fileSize)
        .metadata(Map.of(
                "sha256", checksumSha256,
                "book-id", String.valueOf(bookId),
                "ebook-id", String.valueOf(ebookId),
                "original-filename", originalFilename
        ))
        .build();

s3Client.putObject(request, RequestBody.fromFile(tempPdfPath));
```

## 7. Gọi RAG sau khi upload

RAG endpoint:

```http
POST /internal/ingestions
Content-Type: application/json
X-RAG-API-Key: <RAG_INTERNAL_API_KEY>
```

Payload:

```json
{
  "sourceType": "LIBRARY_EBOOK",
  "bookId": 101,
  "ebookId": 55,
  "bucket": "library-private",
  "objectKey": "ebooks/101/55/original.pdf",
  "originalFilename": "clean-code.pdf",
  "contentType": "application/pdf",
  "fileSizeBytes": 5242880,
  "checksumSha256": "64-char-lowercase-hex"
}
```

Response thành công:

```json
{
  "documentId": "doc_ebook_55",
  "ingestionJobId": 123,
  "status": "QUEUED"
}
```

Library nên lưu:

```text
rag_document_id = documentId
rag_job_id      = ingestionJobId
ingestion_status = status
```

Không gọi RAG trước khi DB Library commit metadata upload thành công.

## 8. Transaction và retry

Không có distributed transaction giữa Library DB, SeaweedFS và RAG. Vì vậy nên thiết kế theo kiểu eventual consistency.

### MVP đơn giản

```text
Upload S3 thành công
  -> commit Library DB
  -> gọi RAG
  -> nếu RAG lỗi, giữ ingestion_status = QUEUED/PENDING_RETRY
```

Sau đó có scheduled job retry những ebook:

```text
upload_status = COMPLETED
ingestion_status in (NOT_REQUESTED, QUEUED, FAILED_RETRYABLE)
rag_job_id is null hoặc cần poll lại
```

### Production tốt hơn

Dùng transactional outbox:

```text
1. Upload PDF lên S3.
2. Trong cùng DB transaction:
   - update ebook file metadata
   - insert outbox event EBOOK_UPLOADED
3. Outbox worker đọc event sau commit.
4. Outbox worker gọi RAG.
5. Thành công thì mark event processed.
6. Lỗi thì retry với backoff.
```

Không nên giữ transaction DB mở trong suốt thời gian upload file lớn.

## 9. Failure handling

| Tình huống | Cách xử lý |
| --- | --- |
| PDF không hợp lệ | Trả lỗi 400, không upload S3, không gọi RAG. |
| S3 upload lỗi | Mark upload failed, không gọi RAG. |
| S3 upload thành công nhưng DB lỗi | Có thể tạo orphan object; cần cleanup job xóa object không có DB row. |
| DB commit thành công nhưng RAG down | Giữ upload completed, ingestion pending/retry; user không cần upload lại. |
| RAG trả 401 | Sai `RAG_INTERNAL_API_KEY`; không retry vô hạn, cần alert/config fix. |
| RAG trả 422 | Payload sai bucket/key/checksum; bug phía Library, cần fix code. |
| RAG timeout/5xx | Retry với backoff, cùng payload/checksum. |
| Worker parse PDF lỗi | Upload vẫn thành công, ingestion failed; cho admin retry hoặc thay file. |
| User upload lại cùng ebook khi job cũ đang chạy | Nên chặn hoặc đợi job cũ kết thúc để tránh race do object key cố định. |

## 10. Vấn đề replace/version ebook

Vì object key hiện tại cố định:

```text
ebooks/{bookId}/{ebookId}/original.pdf
```

nếu upload file mới cho cùng `ebookId`, object cũ sẽ bị overwrite.

MVP nên chọn một trong hai hướng:

1. Không cho replace khi ingestion job cũ đang `QUEUED` hoặc `PROCESSING`.
2. Mỗi lần thay file tạo `ebookId` mới như một version mới.

Nếu muốn object key có UUID/version, cần sửa RAG contract trước. Hiện tại RAG sẽ reject key khác pattern trên.

## 11. Test plan

### 11.1. Check bucket

Từ repo RAG:

```powershell
docker compose exec api python -c "import os,boto3; s3=boto3.client('s3',endpoint_url=os.environ['OBJECT_STORAGE_ENDPOINT'],aws_access_key_id=os.environ['OBJECT_STORAGE_ACCESS_KEY'],aws_secret_access_key=os.environ['OBJECT_STORAGE_SECRET_KEY'],region_name=os.getenv('OBJECT_STORAGE_REGION','us-east-1')); print([b['Name'] for b in s3.list_buckets()['Buckets']])"
```

Expected:

```text
['library-private', 'library-temp', 'rag-artifacts']
```

### 11.2. Check Library gọi được RAG

```powershell
docker exec quanlythuvien-library-service-1 sh -lc "wget -qO- http://rag-api:8000/api/v1/health"
```

Expected:

```json
{"status":"ok"}
```

### 11.3. Check object sau upload

Sau khi upload PDF từ Library:

```powershell
docker compose run --rm seaweedfs-init aws --endpoint-url http://seaweedfs:8333 s3 ls s3://library-private/ebooks/{bookId}/{ebookId}/
```

Expected:

```text
original.pdf
```

### 11.4. Check RAG ingestion

Gọi status:

```http
GET http://rag-api:8000/internal/ingestions/{ingestionJobId}
X-RAG-API-Key: <RAG_INTERNAL_API_KEY>
```

Expected flow:

```text
QUEUED -> PROCESSING -> PARSED -> CHUNKED -> EMBEDDING -> EMBEDDED -> INDEXING -> INDEXED
```

Nếu PDF scanned/image-only, có thể `FAILED` với lỗi parse không có text.

## 12. Checklist implement bên Library

- [ ] Thêm AWS SDK S3 dependency.
- [ ] Thêm `ObjectStorageProperties`.
- [ ] Tạo `S3Client` với endpoint `http://rag-seaweedfs:8333`.
- [ ] Bật `pathStyleAccessEnabled(true)`.
- [ ] Thêm service upload PDF vào `library-private`.
- [ ] Dùng key đúng `ebooks/{bookId}/{ebookId}/original.pdf`.
- [ ] Tính SHA-256 lowercase hex.
- [ ] Lưu bucket/key/contentType/size/checksum vào DB.
- [ ] Tách `upload_status` và `ingestion_status`.
- [ ] Gọi RAG bằng `X-RAG-API-Key` sau DB commit.
- [ ] Lưu `ragJobId` và `ragDocumentId`.
- [ ] Implement retry khi RAG timeout/5xx.
- [ ] Không retry vô hạn khi RAG trả 401/422.
- [ ] Không log object storage secret hoặc `RAG_INTERNAL_API_KEY`.
- [ ] Viết integration test upload PDF -> object exists -> RAG job queued.

## 13. Gợi ý triển khai theo sprint nhỏ

### Sprint 1: Upload object

Mục tiêu: Library upload được PDF vào `library-private`.

Done khi:

```text
s3://library-private/ebooks/{bookId}/{ebookId}/original.pdf tồn tại
Library DB có bucket/key/checksum/size
```

### Sprint 2: Trigger RAG

Mục tiêu: Sau upload, Library gọi RAG tạo ingestion job.

Done khi:

```text
RAG trả 202
Library lưu ragJobId
RAG status từ QUEUED chạy tới INDEXED với PDF text bình thường
```

### Sprint 3: Retry và observerability

Mục tiêu: Không mất job khi RAG tạm down.

Done khi:

```text
RAG down -> upload vẫn completed
Retry job gọi lại RAG sau khi RAG up
Admin nhìn được ingestion status/error
```

### Sprint 4: Vector indexing

Mục tiêu: RAG không dừng ở `CHUNKED` nữa mà embed/upsert Qdrant.

Done khi:

```text
RAG status INDEXED/COMPLETED chỉ xuất hiện sau khi Qdrant upsert thành công
Retrieval trả chunk liên quan đến ebook vừa upload
```

## 14. Production notes

- Không publish SeaweedFS S3 public nếu chỉ backend upload trực tiếp.
- Trên VPS, Library và RAG nên gọi nhau qua Docker network/private network.
- Nếu cần user download bằng presigned URL sau này, mới expose storage qua HTTPS domain riêng.
- Rotate `OBJECT_STORAGE_ACCESS_KEY`, `OBJECT_STORAGE_SECRET_KEY`, `RAG_INTERNAL_API_KEY` theo từng environment.
- Backup volume `seaweedfs_data` và database metadata cùng nhau.
- Không xóa file trong `library-private` chỉ vì RAG ingestion fail; file gốc vẫn cần để retry/debug.
