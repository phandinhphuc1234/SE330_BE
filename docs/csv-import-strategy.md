# CSV Import Strategy

Tài liệu này mô tả cách backend hiện tại xử lý import CSV để thêm sách và bản sao sách vào database, lý do chọn cách làm hiện tại, các giới hạn, và hướng nâng cấp production-like trong tương lai.

## Mục tiêu hiện tại

Import CSV được dùng để giúp librarian/admin nhập dữ liệu nhanh thay vì phải gọi nhiều API thủ công:

```text
POST /api/authors
POST /api/categories
POST /api/books
POST /api/books/{bookId}/copies
```

API hiện tại:

```http
POST /api/books/import-csv
Content-Type: multipart/form-data
```

Mỗi dòng CSV đại diện cho **một bản copy vật lý**.

Ví dụ 2 dòng cùng ISBN nhưng barcode khác nhau nghĩa là cùng một đầu sách có 2 bản vật lý:

```csv
title,isbn,authors,category,barcode,condition,location,language,published_date,edition
Clean Code,9780132350884,Robert C. Martin,Technology,LIB-2026-000001,GOOD,Shelf A1,en,2008-08-01,1st
Clean Code,9780132350884,Robert C. Martin,Technology,LIB-2026-000002,GOOD,Shelf A1,en,2008-08-01,1st
```

## Format CSV

Cột bắt buộc:

```text
title
isbn
authors
category
barcode
```

Cột optional:

```text
condition
location
language
published_date
edition
```

Quy tắc:

```text
isbn     = định danh đầu sách
barcode  = định danh từng cuốn sách vật lý
authors  = nhiều tác giả có thể phân tách bằng ; hoặc |
```

## Cách xử lý hiện tại

Backend hiện tại xử lý import theo kiểu synchronous:

```text
1. Client upload CSV.
2. Backend đọc và parse file.
3. Backend validate header và dữ liệu từng dòng.
4. Backend loại các dòng có barcode trùng trong chính file CSV.
5. Backend batch-check các barcode đã tồn tại trong database.
6. Backend import từng dòng hợp lệ.
7. Backend trả về summary và danh sách lỗi theo từng dòng.
```

Response gồm:

```text
totalRows
successRows
failedRows
createdBooks
createdCopies
errors
```

Điểm quan trọng: nếu một số dòng lỗi, các dòng hợp lệ khác vẫn tiếp tục được import.

## Các tối ưu đã áp dụng

### 1. Giới hạn file import

Backend không nhận file quá lớn để tránh request bị treo:

```text
File không được rỗng
File tối đa 5MB
CSV tối đa 5000 rows
```

Giới hạn này phù hợp với MVP/demo. Nếu cần import file lớn hơn, nên chuyển sang async import job.

### 2. Check barcode trùng trong file

Trước khi ghi database, backend kiểm tra barcode bị lặp ngay trong file CSV.

Ví dụ:

```csv
Clean Code,9780132350884,Robert C. Martin,Tech,LIB-001
Nhà Giả Kim,9786041017528,Paulo Coelho,Fiction,LIB-001
```

Cả hai dòng sẽ bị reject với lỗi:

```text
Barcode bị trùng trong file CSV
```

### 3. Batch check barcode đã tồn tại trong DB

Trước đây nếu mỗi dòng gọi:

```java
existsByBarcodeIgnoreCase(barcode)
```

thì 5000 dòng có thể tạo ra 5000 query.

Hiện tại backend gom barcode trong file và query database một lần:

```java
findExistingLowerBarcodes(barcodes)
```

Sau đó reject các dòng có barcode đã tồn tại.

### 4. Cache local trong một lần import

Trong một file CSV, nhiều dòng thường lặp lại cùng ISBN, author, category.

Ví dụ 500 dòng cùng sách `Clean Code` thì không nên query book theo ISBN 500 lần.

Backend hiện dùng cache local trong một lần import:

```text
isbn -> bookId
authorName -> authorId
categoryName -> categoryId
```

Cache này chỉ sống trong request import hiện tại, import xong là bỏ. Không dùng Redis ở đây vì dữ liệu không cần sống giữa nhiều request.

### 5. Không đếm lại toàn bộ book_copies sau mỗi dòng

Trước đây sau khi tạo một `BookCopy`, code đếm lại:

```java
countByBookIdAndDeletedAtIsNull(...)
countByBookIdAndStatusAndDeletedAtIsNull(...)
```

Cách này chậm khi import nhiều copy cho cùng một sách.

Hiện tại khi tạo copy mới, backend chỉ tăng counter:

```java
adjustCopyCounters(bookId, 1, 1)
```

Vì copy mới luôn có status `AVAILABLE`.

## Vì sao chưa dùng Redis cho import hiện tại?

Redis không cần thiết cho cache trong một request.

Cache local bằng `HashMap` phù hợp hơn vì:

```text
Nhanh hơn
Không cần network
Không cần TTL
Không có rủi ro stale cache
Không phải serialize JPA entity
Import xong là bỏ cache
```

Redis sẽ phù hợp hơn cho các case như:

```text
Refresh token
Access token blacklist
Email resend cooldown
Rate limit
Async import job progress
Cache public catalog query
```

## Giới hạn của thiết kế hiện tại

Thiết kế hiện tại vẫn là synchronous import.

Nghĩa là:

```text
Client upload file
Request HTTP giữ kết nối
Backend xử lý xong toàn bộ file
Backend mới trả response
```

Vì vậy nếu file lớn, frontend có thể timeout trước khi backend xử lý xong.

Ví dụ frontend timeout 10 giây thì import 5000 rows có thể bị báo timeout dù backend vẫn đang chạy.

Với MVP, có thể xử lý bằng cách:

```text
Tăng timeout riêng cho endpoint import lên 60-120 giây
Disable nút import khi đang upload
Giữ giới hạn file/row count
```

## Hướng nâng cấp production-like

Nếu muốn xử lý file lớn hơn và UX tốt hơn, nên chuyển sang async import job.

### Flow đề xuất

```http
POST /api/books/import-csv
```

Response ngay:

```json
{
  "jobId": "import-123"
}
```

Frontend poll:

```http
GET /api/import-jobs/{jobId}
```

Response:

```json
{
  "status": "PROCESSING",
  "totalRows": 5000,
  "processedRows": 1200,
  "successRows": 1150,
  "failedRows": 50
}
```

Khi hoàn tất:

```json
{
  "status": "COMPLETED",
  "totalRows": 5000,
  "successRows": 4900,
  "failedRows": 100,
  "errors": []
}
```

### Thành phần cần thêm

```text
import_jobs table
ImportJob entity/repository/service
Background worker với @Async hoặc queue
API lấy progress/result
Frontend progress UI
```

Redis có thể dùng để lưu progress tạm thời:

```text
import:job:{jobId}:status
import:job:{jobId}:processedRows
```

Nhưng nếu cần lưu lịch sử import và error report lâu dài, nên lưu vào database.

## Chốt cho đồ án

Với đồ án xin internship, hướng hiện tại là hợp lý:

```text
Synchronous import
Giới hạn file/row
Batch validate barcode
Cache local trong một request
Partial success theo từng dòng
Error report rõ ràng
```

Khi phỏng vấn, có thể trình bày:

```text
Em không dùng Redis cho cache import CSV vì cache chỉ cần sống trong một request.
Em dùng HashMap local để giảm query lặp cho ISBN, author và category.
Em batch-check barcode trước khi insert để tránh 5000 query exists.
Với file lớn hơn, em sẽ chuyển sang async import job có jobId và progress polling.
```
