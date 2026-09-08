# Library Management System

Backend cho hệ thống quản lý thư viện: quản lý danh mục và bản sách, bạn đọc,
mượn/trả, đặt giữ chỗ, phạt, ebook, thanh toán VNPAY, import CSV và vận hành
cơ bản. API được xây dựng bằng Spring Boot 4 và Java 21.

> Trạng thái demo: RAG mặc định tắt. Ebook/S3 và VNPAY là tích hợp tùy chọn,
> không cần để chạy các luồng thư viện cốt lõi.

## Chức năng đã có

- Xác thực: đăng ký, xác thực email, đăng nhập, refresh/logout; phân quyền
  `MEMBER`, `LIBRARIAN`, `ADMIN`.
- Danh mục: sách, tác giả, thể loại, bản sách vật lý, ảnh bìa/ảnh tác giả và
  tìm kiếm/phân trang.
- Lưu thông: preview checkout, mượn/trả, gia hạn, đặt giữ chỗ, hàng đợi hold,
  quá hạn, nhắc hạn, tự động gia hạn và phạt.
- Ebook: metadata PDF, mượn ebook miễn phí, payment ebook, phiên đọc có token
  ký và giới hạn truy cập.
- Vận hành: import CSV bất đồng bộ kèm SSE, thống kê staff, dashboard, audit
  log, idempotency Redis, health/Prometheus và Grafana profile tùy chọn.
- Chất lượng API: `ApiResponse`, `PageMeta`, error code/trace ID thống nhất,
  OpenAPI/Swagger và kiểm thử unit, MVC, Testcontainers.

## Công nghệ

- Java 21, Spring Boot 4, Spring Security, Spring Data JPA
- PostgreSQL + Flyway, Redis
- Maven Wrapper, JUnit 5, Mockito, Testcontainers
- Docker Compose, Actuator/Prometheus/Grafana
- Cloudinary (ảnh), S3-compatible storage (ebook), VNPAY sandbox (tùy chọn)

## Chạy local/demo

Yêu cầu: Docker Desktop và Java 21 (chỉ cần Java khi chạy Maven ngoài Docker).

```powershell
Copy-Item .env.example .env
docker compose up -d postgres redis
# Khôi phục database local/snapshot đã migrate thành công nếu máy chưa có.
docker compose up --build library-service
```

API mặc định: `http://localhost:8080`.

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`
- Health: `http://localhost:8080/actuator/health`

Xem hướng dẫn quan trọng về database, bao gồm lý do demo dùng snapshot local
đã qua migration V22, tại [migration-upgrade-guide.md](docs/migration-upgrade-guide.md).

### Tài khoản demo local

Mật khẩu của các tài khoản seed là `Password123`; chỉ dùng cho local/demo.

| Vai trò | Email |
| --- | --- |
| Admin | `admin@library.local` |
| Librarian | `librarian@library.local` |
| Member | `member1@library.local` |
| Member | `member2@library.local` |

## Kiểm thử và CI

```powershell
.\mvnw.cmd test
```

Suite bao gồm PostgreSQL và Redis Testcontainers cho context/security test; nó
không dùng database hoặc Redis local. GitHub Actions tự chạy test trên mọi push
và pull request, đồng thời lưu Surefire reports.

## Tài liệu

- [Tổng quan kiến trúc, ERD và luồng chính](docs/architecture-overview.md)
- [Quy ước HTTP và API response](docs/api-contract.md)
- [Kịch bản demo và request mẫu](docs/demo-script.md)
- [Release notes tháng 09/2026](docs/release-notes-2026-09.md)
- [Quy ước error handling](skills/error-handling-guide.md)
- [Schema PostgreSQL hiện tại](docs/schema-current.md)
- [Chiến lược nâng cấp migration](docs/migration-upgrade-guide.md)
- [Các flow circulation chi tiết](docs/implements/circulation-flows/README.md)

## Phạm vi trình bày khi ứng tuyển

Khi chuẩn bị hồ sơ, chỉ giữ những mục bạn trực tiếp phụ trách. Có thể nhấn mạnh
các phần backend có bằng chứng trong source và test như bảo mật JWT, chuẩn hóa
lỗi API, mượn/trả và hold có idempotency/locking, VNPAY callback, import CSV
SSE, hoặc catalog/ebook tùy phần việc thực tế của bạn. RAG là hạng mục để sau,
không đưa vào bản demo mặc định.
