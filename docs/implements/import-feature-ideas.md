# Ý tưởng nâng cấp Import cho hệ thống quản lý thư viện

Tài liệu này gom các hướng phát triển chức năng import để hệ thống trông thực tế hơn trong mắt nhà tuyển dụng. Mục tiêu không phải làm tất cả, mà là chọn vài điểm có giá trị kỹ thuật rõ ràng, dễ demo, và gắn với kiến trúc hiện tại của project.

## 1. Bức tranh hiện tại

Project đã có nền tảng tốt cho import:

- `POST /api/books/import-csv`: nhận CSV sách và bản sao vật lý.
- `GET /api/books/import-csv/{jobId}`: xem trạng thái import.
- `book_import_jobs`: lưu trạng thái job.
- `book_import_job_errors`: lưu lỗi từng dòng.
- Async processing bằng `@Async("csvImportExecutor")`.
- SSE stream cho tiến độ import CSV:
  - `GET /api/books/import-csv/{jobId}/events`
  - event: `book-import-snapshot`, `book-import-processing`, `book-import-progress`, `book-import-completed`, `book-import-failed`.

Điểm đã giống hệ thống thực tế: import chạy nền, không block request, có job status, có row-level error và có realtime progress.

## 2. Ý tưởng nên ưu tiên

### 2.1. Dry-run import

Thêm chế độ kiểm tra trước khi ghi database.

API gợi ý:

```http
POST /api/books/import-csv/dry-run
```

Hoặc dùng query:

```http
POST /api/books/import-csv?dryRun=true
```

Flow:

```text
Upload CSV
 -> parse + validate
 -> detect duplicate ISBN/barcode/author/category
 -> trả report
 -> không insert DB
```

Giá trị demo:

- Cho thấy tư duy an toàn dữ liệu.
- Tránh import nhầm hàng trăm dòng.
- Staff có thể sửa file trước khi import thật.

Response nên có:

```text
totalRows
validRows
invalidRows
wouldCreateBooks
wouldCreateCopies
wouldCreateAuthors
wouldCreateCategories
errors[]
warnings[]
```

### 2.2. Import preview và confirm

Thay vì upload là ghi DB ngay, tách thành 2 bước:

```text
1. Upload file -> tạo import job ở trạng thái VALIDATED
2. Staff xem preview -> bấm Confirm
3. Backend mới ghi DB
```

API gợi ý:

```http
POST /api/book-imports
GET /api/book-imports/{jobId}/preview
POST /api/book-imports/{jobId}/confirm
POST /api/book-imports/{jobId}/cancel
```

Giá trị demo:

- Gần với workflow admin thật.
- Tách validation khỏi commit database.
- Dễ giải thích với nhà tuyển dụng về data governance.

### 2.3. Mapping header linh hoạt

Hiện CSV thường cần header cố định:

```text
title,isbn,authors,category,barcode
```

Nâng cấp:

```text
Tên sách -> title
ISBN-13 -> isbn
Tác giả -> authors
Mã vạch -> barcode
```

API có thể nhận mapping:

```json
{
  "mapping": {
    "Tên sách": "title",
    "ISBN-13": "isbn",
    "Tác giả": "authors",
    "Mã vạch": "barcode"
  }
}
```

Giá trị demo:

- Thực tế hơn vì file Excel/CSV từ nhiều nguồn hiếm khi chuẩn 100%.
- Cho thấy khả năng thiết kế import engine linh hoạt.

### 2.4. Import template download

Thêm endpoint tải file mẫu:

```http
GET /api/books/import-csv/template
```

Trả về CSV mẫu:

```csv
title,isbn,authors,category,barcode,condition,location,language,published_date,edition
Clean Code,9780132350884,Robert C. Martin,Software,BC0001,GOOD,A1,vi,2008-08-01,1st
```

Giá trị demo:

- Dễ dùng cho staff.
- Nhỏ nhưng rất "product-minded".

### 2.5. Duplicate resolution strategy

Hiện import có thể xử lý duplicate theo rule cố định. Nên cho staff chọn chiến lược:

```text
SKIP_EXISTING
UPDATE_METADATA
CREATE_COPY_ONLY
FAIL_ON_DUPLICATE
```

Ví dụ:

```json
{
  "duplicateStrategy": "CREATE_COPY_ONLY"
}
```

Ý nghĩa:

- Nếu ISBN đã tồn tại, không tạo sách mới.
- Chỉ tạo thêm book copy nếu barcode chưa tồn tại.

Giá trị demo:

- Cho thấy hiểu nghiệp vụ thư viện: một sách có thể có nhiều bản copy.
- Tránh dữ liệu trùng lặp.

## 3. Import PDF/Ebook nâng cao

### 3.1. Batch PDF import bằng manifest CSV

Khi hệ thống đã có ebook PDF + SeaweedFS + RAG, có thể làm import ebook theo manifest.

File ZIP:

```text
ebooks.zip
  manifest.csv
  pdfs/
    clean-code.pdf
    refactoring.pdf
```

`manifest.csv`:

```csv
isbn,title,pdf_file,access_type,access_fee
9780132350884,Clean Code,pdfs/clean-code.pdf,PAID,50000
9780201485677,Refactoring,pdfs/refactoring.pdf,FREE,0
```

Flow:

```text
Upload ZIP
 -> validate manifest
 -> match book by ISBN
 -> upload PDF to SeaweedFS
 -> create/update book_ebooks
 -> call RAG ingestion
 -> callback updates item status
 -> notification when completed
```

Giá trị demo:

- Kết nối import, object storage, async job, RAG pipeline.
- Rất mạnh khi trình bày kiến trúc backend.

### 3.2. Ebook import jobs và import items

Nếu làm batch PDF, nên có 2 bảng:

```text
ebook_import_jobs
ebook_import_items
```

`ebook_import_jobs`:

```text
id
created_by
status
total_items
succeeded_items
failed_items
created_at
started_at
completed_at
last_error
```

`ebook_import_items`:

```text
id
job_id
book_id
book_ebook_id
original_filename
bucket_name
object_key
checksum_sha256
file_size_bytes
status
rag_document_id
rag_job_id
error_message
created_at
uploaded_at
ingestion_requested_at
completed_at
```

Item status:

```text
PENDING
UPLOADED
INGESTION_QUEUED
PROCESSING
CHUNKED
INDEXED
FAILED
```

### 3.3. Callback thay vì polling RAG

Không cần Library polling RAG. RAG xử lý xong gọi callback:

```http
POST /internal/rag/ingestion-callbacks
X-LIBRARY-INTERNAL-KEY: <secret>
```

Payload:

```json
{
  "bookId": 101,
  "ebookId": 55,
  "ragJobId": 123,
  "documentId": "doc_ebook_55",
  "status": "CHUNKED",
  "errorMessage": null
}
```

Library xử lý:

```text
verify internal key
 -> update book_ebooks.ingestion_status
 -> update ebook_import_items
 -> recalculate ebook_import_jobs
 -> create notification if job is terminal
```

## 4. Notification cho import

### 4.1. Notification khi CSV import xong

Thêm `NotificationType`:

```java
BOOK_IMPORT_COMPLETED,
BOOK_IMPORT_PARTIAL_FAILED,
BOOK_IMPORT_FAILED
```

Thêm `NotificationTargetType`:

```java
BOOK_IMPORT_JOB
```

Nội dung:

```text
Import sách hoàn tất: 120 dòng, 115 thành công, 5 lỗi.
```

Hoặc:

```text
Import sách thất bại: file không đúng định dạng CSV.
```

### 4.2. Notification khi PDF/RAG import xong

Thêm:

```java
EBOOK_IMPORT_COMPLETED,
EBOOK_IMPORT_PARTIAL_FAILED,
EBOOK_IMPORT_FAILED,
EBOOK_INGESTION_COMPLETED,
EBOOK_INGESTION_FAILED
```

Nội dung:

```text
Import ebook hoàn tất: 10 file, 9 xử lý thành công, 1 lỗi.
```

Hoặc:

```text
Ebook "Clean Code" đã được xử lý xong và sẵn sàng tìm kiếm.
```

## 5. Audit và data lineage

Mỗi import job nên lưu:

```text
created_by
source_filename
source_checksum_sha256
source_size_bytes
import_mode
duplicate_strategy
dry_run
```

Lý do:

- Biết ai import.
- Biết file nào gây ra dữ liệu hiện tại.
- Có thể debug khi dữ liệu sai.
- Có bằng chứng audit cho thao tác hàng loạt.

Với CSV, có thể lưu checksum của file CSV. Với ZIP/PDF, lưu checksum từng file.

## 6. Idempotency cho import

Để tránh staff bấm upload 2 lần, có thể dùng `Idempotency-Key`:

```http
POST /api/books/import-csv
Idempotency-Key: import-books-2026-06-27-001
```

Hoặc dùng checksum:

```text
Nếu same user + same file checksum + same import mode trong 10 phút
 -> trả lại job cũ
```

Giá trị demo:

- Cho thấy hiểu vấn đề double-submit.
- Giảm rủi ro dữ liệu bị nhân đôi.

## 7. Rollback và undo import

Đây là feature gây ấn tượng mạnh nhưng cần cẩn thận.

Hướng an toàn:

```text
Không xóa ngay dữ liệu đã import.
Lưu imported_by_job_id trên bản ghi mới tạo.
Cho phép rollback chỉ với record chưa phát sinh nghiệp vụ.
```

Ví dụ:

```text
Book copy mới tạo nhưng chưa từng được mượn -> có thể xóa/soft delete
Book đã có borrow history -> không rollback cứng
```

API:

```http
POST /api/book-imports/{jobId}/rollback
```

Giá trị demo:

- Cho thấy hiểu tính toàn vẹn dữ liệu.
- Không dùng `delete all` nguy hiểm.

## 8. Import member

Ngoài sách, thư viện thực tế thường cần import bạn đọc.

CSV:

```csv
full_name,email,phone,role,membership_expires_at
Nguyen Van A,a@example.com,0900000000,MEMBER,2027-01-01
```

Flow:

```text
validate email
detect duplicate email
create member as PENDING_VERIFICATION or ACTIVE
optionally send verification email
```

Giá trị demo:

- Mở rộng import engine sang domain khác.
- Tận dụng lại job/progress/error model.

## 9. Import MARC21 hoặc ISBN enrichment

Nếu muốn rất "library domain", nghiên cứu MARC21.

MARC21 là format metadata thư viện dùng trong nhiều hệ thống thực tế.

MVP đơn giản hơn:

```text
Import CSV chỉ có ISBN
 -> backend gọi external ISBN metadata provider
 -> tự điền title/authors/category/published_date
```

Lưu ý: phần này cần network/external API nên nên làm sau khi import core đã ổn.

Giá trị demo:

- Cho thấy hiểu nghiệp vụ thư viện thật, không chỉ CRUD.
- Nhưng phải tránh phụ thuộc API ngoài trong demo offline.

## 10. Observability cho import

Nên có metrics:

```text
book_import_jobs_total
book_import_rows_total
book_import_rows_failed_total
book_import_duration_seconds
ebook_import_jobs_total
ebook_ingestion_failed_total
```

Log nên có:

```text
jobId
createdBy
traceId
status
processedRows
failedRows
durationMs
```

Giá trị demo:

- Gắn với Prometheus/Grafana hiện có trong project.
- Thể hiện tư duy vận hành production.

## 11. Lộ trình đề xuất

### Phase 1: Hoàn thiện CSV import hiện tại

Nên làm:

- SSE progress đã có.
- Thêm notification khi import xong.
- Thêm `created_by` vào `book_import_jobs`.
- Thêm dry-run.
- Thêm template download.

Đây là phase dễ demo nhất.

### Phase 2: Import preview và duplicate strategy

Nên làm:

- Preview rows.
- Confirm/cancel import.
- Duplicate strategy.
- Header mapping.

Đây là phase thể hiện tư duy product và data quality.

### Phase 3: Batch ebook/PDF import

Nên làm sau khi single ebook upload + RAG callback ổn:

- ZIP + manifest CSV.
- Upload PDF vào SeaweedFS.
- Trigger RAG ingestion.
- Callback cập nhật trạng thái.
- Notification khi job hoàn tất.

Đây là phase gây ấn tượng mạnh nhất về kiến trúc.

### Phase 4: Rollback và audit nâng cao

Nên làm nếu còn thời gian:

- Rollback an toàn.
- Data lineage.
- Import metrics.
- Admin dashboard cho import jobs.

## 12. Feature nên tránh làm quá sớm

Không nên bắt đầu bằng:

- Batch PDF ZIP lớn khi single PDF/RAG chưa ổn.
- Rollback phức tạp khi chưa có audit rõ.
- External ISBN API nếu demo cần chạy offline.
- WebSocket nếu SSE đã đủ.
- Import Excel `.xlsx` nếu CSV chưa có dry-run/preview.

## 13. Combo gây ấn tượng nhất cho demo

Nếu chỉ chọn một combo vừa sức:

```text
CSV import async
 -> SSE progress realtime
 -> dry-run validation
 -> row-level errors
 -> notification when completed
 -> import history screen
```

Nếu muốn nâng cấp mạnh:

```text
Ebook ZIP import
 -> SeaweedFS object storage
 -> RAG ingestion callback
 -> per-file status
 -> notification when searchable
```

Hai combo này thể hiện rõ:

- async job design
- reliability
- data validation
- user experience
- production thinking
- integration between services
