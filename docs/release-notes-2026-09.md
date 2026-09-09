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
- Thêm đổi/quên/đặt lại mật khẩu: reset token SHA-256 một lần, rate limit Redis
  theo hash email, và revoke toàn bộ session sau đổi mật khẩu.
- Thêm `ADMIN` cập nhật status tài khoản với pessimistic lock, audit
  `member_status_audits` và revoke session; bổ sung Flyway V40–V42.
- CI nay chạy `verify`, tạo và lưu JaCoCo report; thêm k6 baseline script có
  cấu hình target/book qua biến môi trường.

## Xác minh local

- `mvnw.cmd verify`: 207 tests, 0 failures, 0 errors, 0 skipped; JaCoCo 52%
  instruction / 50% line coverage.
- Context/security test chạy bằng PostgreSQL 16 và Redis 7 Testcontainers.
- Docker demo backend đã apply V42 và health `UP` trên snapshot local.
- k6 warm baseline (20 VUs, 20s, book 3105): 400 request `200`, 0% lỗi,
  p95 62.11 ms; số liệu local, không phải benchmark production.

## Giới hạn đã biết

- Database trống chưa là đường demo được hỗ trợ vì V22 là data-fix cố ý cho
  các ID sách có sẵn; dùng database local/snapshot đã phê duyệt.
- RAG, S3 ebook và VNPAY sandbox là optional integration; không nằm trong
  demo mặc định.
- Chưa có frontend repository trong phạm vi này, vì vậy không thay đổi shape
  response/timestamp mang tính breaking change.
