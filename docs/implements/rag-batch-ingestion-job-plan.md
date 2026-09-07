# RAG Batch Ingestion Job Plan

Tài liệu này ghi lại hướng chuyển RAG ingestion từ kiểu Spring Boot gọi RAG ngay
sau upload sang kiểu batch job để làm sau khi pipeline RAG đã được test ổn.

## 1. Mục tiêu

Hiện tại upload ebook PDF đang làm:

```text
Staff upload PDF
 -> Spring Boot upload PDF vào SeaweedFS/S3
 -> Spring Boot lưu metadata vào book_ebooks
 -> Spring Boot set ingestion_status = QUEUED
 -> Spring Boot gọi RAG /internal/ingestions bằng @Async
 -> RAG tạo ingestion job
 -> Spring Boot lưu rag_document_id, rag_job_id, ingestion_status
```

Hướng batch job muốn chuyển thành:

```text
Staff upload PDF
 -> Spring Boot upload PDF vào SeaweedFS/S3
 -> Spring Boot lưu metadata vào book_ebooks
 -> Spring Boot set ingestion_status = QUEUED
 -> Spring Boot trả response cho frontend

RAG batch job chạy định kỳ
 -> lấy ebook đang QUEUED
 -> đọc PDF từ SeaweedFS/S3 bằng bucket_name + object_key
 -> parse/chunk/embed/index
 -> callback về Spring Boot để cập nhật trạng thái
```

Frontend không gọi RAG trực tiếp. Frontend chỉ đọc trạng thái từ Spring Boot.

## 2. Ranh giới trách nhiệm

### Spring Boot

- Xác thực staff/admin khi upload PDF.
- Validate file PDF.
- Upload PDF vào object storage.
- Lưu metadata vào `book_ebooks`.
- Lưu trạng thái ingestion hiện tại.
- Cung cấp internal API cho RAG lấy job hoặc nhận callback.
- Cung cấp API frontend đọc trạng thái ebook.

### RAG service

- Chạy batch job hoặc worker nền.
- Lấy các ebook cần ingest.
- Đọc PDF từ SeaweedFS/S3.
- Parse/chunk/embed/upsert vector store.
- Gọi callback về Spring Boot khi trạng thái thay đổi.

RAG không nên gọi trực tiếp frontend và không nên tự quyết định quyền user. Quyền
đọc/hỏi ebook vẫn do Spring Boot kiểm tra.

## 3. Trạng thái đề xuất

`book_ebooks.ingestion_status` nên dùng như sau:

| Status | Ý nghĩa |
|---|---|
| `NOT_REQUESTED` | Chưa yêu cầu RAG index, hoặc RAG bị tắt |
| `QUEUED` | PDF đã upload, đang chờ RAG batch lấy xử lý |
| `PROCESSING` | RAG đã nhận job và đang xử lý |
| `PARSED` | RAG đã parse PDF xong |
| `CHUNKED` | RAG đã chia chunk xong |
| `INDEXED` | RAG đã embed/upsert xong, có thể query |
| `FAILED` | Ingestion lỗi |

Khi upload PDF mới thành công, Spring Boot nên reset:

```text
ingestion_status = QUEUED
rag_document_id = null
rag_job_id = null
ingestion_last_error = null
indexing_requested_at = now
checksum_sha256 = checksum của file mới
bucket_name = library-private
object_key = ebooks/{bookId}/{ebookId}/original.pdf
```

## 4. API nội bộ đề xuất

### 4.1. RAG lấy job từ Spring Boot

```http
GET /internal/rag/ingestion-requests?status=QUEUED&limit=20
X-RAG-API-Key: ...
```

Response:

```json
{
  "data": [
    {
      "bookId": 3242,
      "ebookId": 7,
      "bucket": "library-private",
      "objectKey": "ebooks/3242/7/original.pdf",
      "originalFilename": "clean-code.pdf",
      "contentType": "application/pdf",
      "fileSizeBytes": 11559678,
      "checksumSha256": "..."
    }
  ]
}
```

Khi trả job cho RAG, Spring Boot có thể lock row và chuyển trạng thái:

```text
QUEUED -> PROCESSING
```

Nếu chưa muốn làm lock phức tạp, giai đoạn đầu có thể cho RAG chỉ chạy một
worker và lấy theo `updated_at`/`indexing_requested_at`.

### 4.2. RAG callback trạng thái về Spring Boot

```http
POST /internal/rag/ingestion-callbacks
X-RAG-API-Key: ...
Content-Type: application/json
```

Payload:

```json
{
  "bookId": 3242,
  "ebookId": 7,
  "documentId": "doc_ebook_7",
  "ragJobId": 123,
  "status": "INDEXED",
  "checksumSha256": "...",
  "error": null
}
```

Spring Boot cập nhật:

```text
rag_document_id = documentId
rag_job_id = ragJobId
ingestion_status = status
ingestion_last_error = error
```

## 5. Retry và lỗi

Nếu RAG xử lý lỗi:

```text
PROCESSING -> FAILED
ingestion_last_error = mã lỗi hoặc message ngắn
```

Sau này nên thêm cơ chế retry:

- Retry thủ công từ màn staff: `FAILED -> QUEUED`.
- Retry tự động có giới hạn attempt.
- Lưu `last_error`, `attempt_count`, `next_retry_at` nếu tạo bảng task riêng.

Nếu chỉ dùng `book_ebooks` mà không tạo bảng task riêng, retry thủ công có thể
đơn giản là update:

```text
ingestion_status = QUEUED
ingestion_last_error = null
indexing_requested_at = now
```

## 6. Có cần bảng riêng không?

Giai đoạn test pipeline RAG có thể dùng trực tiếp `book_ebooks`.

Khi cần bền hơn, tạo bảng riêng:

```text
ebook_ingestion_tasks
- id
- book_ebook_id
- book_id
- bucket_name
- object_key
- checksum_sha256
- status
- attempts
- next_retry_at
- locked_at
- locked_by
- last_error
- created_at
- updated_at
```

Ưu điểm của bảng riêng:

- Không làm `book_ebooks` phình logic job.
- Lưu được lịch sử nhiều lần ingest.
- Tránh callback/job cũ ghi đè trạng thái file mới.
- Dễ retry và audit hơn.

## 7. Re-upload và job cũ

Hiện tại mỗi sách dùng một row ebook mới nhất hoặc row đã có sẵn. Object key có
dạng:

```text
ebooks/{bookId}/{ebookId}/original.pdf
```

Nếu staff upload lại PDF cho cùng sách:

- Spring Boot có thể reuse cùng `ebookId`.
- Object S3 ở cùng key có thể bị overwrite.
- `checksum_sha256` đổi sang checksum của file mới.
- `ingestion_status` reset về `QUEUED`.
- `rag_document_id`, `rag_job_id`, `ingestion_last_error` bị clear.

Vấn đề cần chú ý khi chuyển sang callback/batch:

```text
Upload file A -> RAG job A đang chạy
Upload file B -> checksum mới, status QUEUED
RAG job A callback muộn -> không được ghi đè trạng thái của file B
```

Vì vậy callback từ RAG nên gửi `checksumSha256` hoặc `ingestionAttemptId`. Spring
Boot chỉ accept callback nếu callback khớp file hiện tại:

```text
callback.checksumSha256 == book_ebooks.checksum_sha256
```

Nếu không khớp, Spring Boot bỏ qua callback cũ và log warning.

Thiết kế tốt hơn là thêm `ingestion_attempt_id` hoặc bảng
`ebook_ingestion_tasks`, mỗi lần upload tạo một task/attempt mới.

## 8. Frontend nên theo dõi trạng thái thế nào?

Giai đoạn đầu dùng polling:

```text
GET /api/books/{bookId}/ebooks/{bookEbookId}
```

Frontend đọc:

```text
ingestionStatus
ragDocumentId
ragJobId
ingestionLastError
```

Khi status:

- `QUEUED` hoặc `PROCESSING`: hiển thị "Dang lap chi muc AI".
- `INDEXED`: bật tính năng hỏi đáp ebook.
- `FAILED`: hiển thị lỗi và nút retry cho staff.

SSE có thể thêm sau:

```text
GET /api/books/{bookId}/ebooks/{bookEbookId}/ingestion-events
```

Chưa cần WebSocket cho ingestion progress.

## 9. Migration từ code hiện tại

Code hiện tại đang có:

```text
BookEbookServiceImpl.scheduleRagIngestion(...)
EbookRagIngestionAsyncProcessor.requestIngestionAsync(...)
RestRagIngestionClient POST /internal/ingestions
```

Khi chuyển sang batch:

1. Giữ upload S3 và lưu DB như hiện tại.
2. Bỏ hoặc disable `scheduleRagIngestion(...)`.
3. Sau upload chỉ để `ingestion_status = QUEUED`.
4. Thêm internal API cho RAG lấy job.
5. Thêm callback API để RAG cập nhật status.
6. Thêm guard bằng `checksumSha256` hoặc `ingestionAttemptId`.
7. Frontend poll status từ Spring Boot.

## 10. Checklist test pipeline RAG

- Upload PDF thành công -> `book_ebooks.ingestion_status = QUEUED`.
- RAG batch lấy được job từ Spring Boot.
- RAG đọc đúng bucket/key từ SeaweedFS/S3.
- RAG parse/chunk/embed/index xong.
- RAG callback `INDEXED`.
- Spring Boot cập nhật `rag_document_id`, `rag_job_id`, `ingestion_status`.
- Frontend thấy status `INDEXED`.
- Upload file mới sau file cũ failed -> status reset `QUEUED`.
- Callback/job cũ không ghi đè trạng thái file mới.
