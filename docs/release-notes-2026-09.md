# Release notes — 08/09/2026

## Cải thiện chính

- Đồng bộ source local/GitHub vào nhánh cải thiện, giữ chuỗi Flyway local
  V35–V39 và chiến lược snapshot cho historical data-fix V22.
- Demo Docker tối giản PostgreSQL, Redis, backend; RAG tắt mặc định.
- JWT phân loại access/refresh token; account state được kiểm tra lại khi
  xác thực/refresh; lỗi security trả `ApiResponse` có trace ID.
- Thống kê staff mượn/trả được tách service/DTO, validate range/filter và dùng
  timezone `Asia/Ho_Chi_Minh`.
- Idempotency renewal và checkout hold được scope theo ID resource thật, tránh
  replay sai giữa hai resource khác nhau.
- Thêm Testcontainers PostgreSQL/Redis cho context/security test và GitHub
  Actions lưu Surefire reports.
- Bổ sung README, architecture/ERD/flow, API contract, demo script và Postman
  collection.

## Xác minh local

- `mvnw.cmd test`: 194 tests, 0 failures, 0 errors.
- Context/security test chạy bằng PostgreSQL 16 và Redis 7 Testcontainers.
- Docker demo backend đã được kiểm tra health sau migration snapshot.

## Giới hạn đã biết

- Database trống chưa là đường demo được hỗ trợ vì V22 là data-fix cố ý cho
  các ID sách có sẵn; dùng database local/snapshot đã phê duyệt.
- RAG, S3 ebook và VNPAY sandbox là optional integration; không nằm trong
  demo mặc định.
- Chưa có frontend repository trong phạm vi này, vì vậy không thay đổi shape
  response/timestamp mang tính breaking change.
