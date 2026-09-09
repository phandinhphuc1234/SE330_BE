# Migration và môi trường demo

## Nguyên tắc an toàn

Không sửa, xóa hoặc `repair` migration trong database đang có dữ liệu trước khi đối chiếu lịch sử Flyway và tạo backup. Mọi migration mới trong source hiện tại đều là migration chỉ tiến (`V35` đến `V42`).

## Chuỗi migration hiện tại

| Version | Nội dung |
| --- | --- |
| V35 | Chuyển metadata ebook sang S3-compatible storage |
| V36 | Tạo `book_reviews`, gồm foreign key, unique review mỗi member/sách, check rating/content và index |
| V37 | Bổ sung metadata theo dõi RAG cho ebook |
| V38-V39 | Bổ sung ảnh tác giả và metadata Cloudinary |
| V40 | Tạo password-reset token chỉ lưu SHA-256 hash |
| V41 | Tạo audit thay đổi trạng thái member |
| V42 | Đưa `password_reset_tokens.token_hash` về `VARCHAR(64)` để khớp Hibernate mapping |

Do lịch sử GitHub từng có một file khác cũng mang version `V35` để tạo review, source hiện tại cố ý chỉ giữ V35 storage và V36 review. Đây là chuỗi migration chuẩn của local và phải được giữ nguyên byte-for-byte nếu database local đã ghi nhận checksum.

`V22__normalize_book_isbns.sql` là data-fix lịch sử cho các book ID đã tồn tại trong database local. Vì vậy database trống không phải đường chạy demo được hỗ trợ: migration sẽ chủ động dừng nếu các book ID đó chưa tồn tại. Không sửa hay bỏ V22 chỉ để làm database trống chạy được.

## Kiểm tra database cần giữ

Tạo backup trước, sau đó chạy truy vấn chỉ đọc sau trên **đúng database thư viện**:

```sql
SELECT installed_rank, version, description, script, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

- Nếu database dừng ở V34: có thể migrate theo source hiện tại; Flyway sẽ chạy V35 storage, V36 review, rồi V37-V42.
- Nếu đã có `V35__move_ebook_storage_metadata_to_s3.sql` và `V36__create_book_reviews.sql`: chỉ cần đối chiếu checksum, không đổi các file migration đã chạy.
- Nếu history có `V35__create_book_reviews.sql`: dừng lại. Không chạy `clean`, không sửa checksum và không chạy `repair` để ép qua. Cần đối chiếu schema thật của `book_reviews` và lập kế hoạch migration riêng theo database đó.

## Chạy demo với database local/snapshot

Dockerfile và Maven đều cố định Java 21. Dùng database local đã migrate thành công qua V22, hoặc restore một backup/snapshot của database đó trước khi khởi động `library-service`:

```powershell
Copy-Item .env.example .env
docker compose up -d postgres redis
# Restore backup vào đúng POSTGRES_DB/POSTGRES_USER trong .env, nếu cần.
# Sau khi kiểm tra flyway_schema_history, mới khởi động service:
docker compose up --build library-service
```

Không chạy `docker compose down -v` với volume database demo cần giữ. Mặc định RAG tắt (`RAG_ENABLED=false`) nên demo không cần RAG key, RAG container hay network bên ngoài. Ebook/S3 cũng là tích hợp tùy chọn; chỉ cấu hình endpoint/credentials khi cần demo upload PDF.

Nếu cổng 8080 đang được project khác sử dụng, đổi `LIBRARY_APP_PORT` trong `.env`; cổng trong container vẫn là 8080.

Các công cụ phụ không tự khởi động:

```powershell
docker compose --profile tools up -d
docker compose --profile monitoring up -d
```

Tài khoản demo được seed bằng migration V9/V21; mật khẩu là `Password123`. Chỉ dùng các tài khoản này ở local/demo.
