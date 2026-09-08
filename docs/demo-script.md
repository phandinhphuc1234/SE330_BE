# Kịch bản demo backend

Chuẩn bị service theo [README](../README.md). File Postman import được đặt tại
`docs/postman/QuanLyThuVien.postman_collection.json`.

## 1. Kiểm tra service và Swagger

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Start-Process http://localhost:8080/swagger-ui.html
```

Kết quả health cần có `status: UP`.

## 2. Đăng nhập tài khoản librarian

```powershell
$body = @{ email = 'librarian@library.local'; password = 'Password123' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
  -ContentType 'application/json' -Body $body
$token = $login.data.accessToken
$headers = @{ Authorization = "Bearer $token" }
```

`$login.success` là `true`; access token phải là token loại `ACCESS`.

## 3. Xem thống kê mượn/trả

```powershell
Invoke-RestMethod -Headers $headers `
  -Uri 'http://localhost:8080/api/staff/statistics/borrows?from=2026-09-01&to=2026-09-07'
```

Response chuẩn nằm trong `data.days`, `totalBorrowed`, `totalReturned` và
`peakBorrowDate`. Khoảng ngày tối đa là 21 ngày và dùng timezone
`Asia/Ho_Chi_Minh`.

## 4. Kiểm tra error contract

```powershell
try {
  Invoke-WebRequest http://localhost:8080/api/members/me -UseBasicParsing
} catch {
  $_.ErrorDetails.Message
}
```

Kỳ vọng HTTP 401 với JSON có `success: false`, `code: UNAUTHORIZED` và
`traceId`.

## 5. Demo CSV import (tùy dữ liệu)

Sau khi đăng nhập librarian/admin, gửi `POST /api/books/import-csv` dạng
`multipart/form-data` với part tên `file`. Response trả job ID. Dùng
`GET /api/books/import-csv/{jobId}` để polling hoặc EventSource tới
`GET /api/books/import-csv/{jobId}/events` để nghe SSE. SSE không bọc
`ApiResponse` vì EventSource cần `text/event-stream`.

## 6. Điều không demo mặc định

- Không bật RAG.
- Không upload ebook nếu chưa cấu hình S3-compatible storage.
- Không tạo thanh toán sandbox nếu chưa cấu hình VNPAY credentials/IPN URL.

Các mục này là optional integration, không ảnh hưởng các luồng thư viện cốt lõi.
