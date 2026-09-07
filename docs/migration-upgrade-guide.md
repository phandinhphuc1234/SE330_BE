# Migration và môi trường demo

## Nguyên tắc an toàn

Không sửa, xóa hoặc `repair` migration trong database đang có dữ liệu trước khi đối chiếu lịch sử Flyway và tạo backup. Mọi migration mới trong source hiện tại đều là migration chỉ tiến (`V35` đến `V39`).

## Chuỗi migration hiện tại

| Version | Nội dung |
| --- | --- |
| V35 | Chuyển metadata ebook sang S3-compatible storage |
| V36 | Tạo `book_reviews`, gồm foreign key, unique review mỗi member/sách, check rating/content và index |
| V37 | Bổ sung metadata theo dõi RAG cho ebook |
| V38-V39 | Bổ sung ảnh tác giả và metadata Cloudinary |

Do lịch sử GitHub từng có một file khác cũng mang version `V35` để tạo review, source hiện tại cố ý chỉ giữ V35 storage và V36 review. Đây là chuỗi đúng cho database mới hoặc database đã chạy local source.

## Kiểm tra database cần giữ

Tạo backup trước, sau đó chạy truy vấn chỉ đọc sau trên **đúng database thư viện**:

```sql
SELECT installed_rank, version, description, script, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

- Nếu database dừng ở V34: có thể migrate theo source hiện tại; Flyway sẽ chạy V35 storage, V36 review, rồi V37-V39.
- Nếu đã có `V35__move_ebook_storage_metadata_to_s3.sql` và `V36__create_book_reviews.sql`: chỉ cần đối chiếu checksum, không đổi các file migration đã chạy.
- Nếu history có `V35__create_book_reviews.sql`: dừng lại. Không chạy `clean`, không sửa checksum và không chạy `repair` để ép qua. Cần đối chiếu schema thật của `book_reviews` và lập kế hoạch migration riêng theo database đó.

## Chạy demo độc lập

Dockerfile và Maven đều cố định Java 21. Để chạy demo mới:

```powershell
Copy-Item .env.example .env
docker compose up --build
```

Mặc định RAG tắt (`RAG_ENABLED=false`) nên demo không cần RAG key, RAG container hay network bên ngoài. Ebook/S3 cũng là tích hợp tùy chọn; chỉ cấu hình endpoint/credentials khi cần demo upload PDF.

Nếu cổng 8080 đang được project khác sử dụng, đổi `LIBRARY_APP_PORT` trong `.env`; cổng trong container vẫn là 8080.

Các công cụ phụ không tự khởi động:

```powershell
docker compose --profile tools up -d
docker compose --profile monitoring up -d
```

Tài khoản demo được seed bằng migration V9/V21; mật khẩu là `Password123`. Chỉ dùng các tài khoản này ở local/demo.
