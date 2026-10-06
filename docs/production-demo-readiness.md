# Demo trên website đã deploy

## Release và cấu hình

- Frontend: `https://library.flashsale123.tech` (Vercel).
- Backend: `https://api-library.flashsale123.tech` (Azure, Nginx → Spring Boot).
- RAG chạy private sau Spring, không mở Qdrant/Redis/PostgreSQL/FastAPI ra Internet.
- Giữ `CHUNKING_STRATEGY_VERSION=v1` cho demo; v2/GraphRAG là opt-in, không tự reindex.
- Build frontend mới cần Vercel `NEXT_PUBLIC_API_URL=https://api-library.flashsale123.tech`.

## Kiểm tra và bật RAG

1. Workflow **VPS deployment preflight**, chọn `diagnostic_only=true`, chỉ đọc
   RAM/disk/container, config public, trạng thái có/thiếu key và Spring health.
   Không sync file, restart container hay gọi Gemini.
2. Chủ dự án thêm `GEMINI_API_KEY` vào GitHub **Actions repository secrets**.
   Không gửi key vào chat, commit `.env` hoặc nhập key trong workflow inputs.
3. Workflow **Provision production runtime**: HTTPS, API host
   `api-library.flashsale123.tech`, frontend origin `https://library.flashsale123.tech`,
   giữ lựa chọn Swagger hiện tại, `rag_enabled=true`, confirmation `PROVISION`.
   Workflow truyền key qua SSH stdin, giữ key hợp lệ trên VPS, sinh credential
   nội bộ còn thiếu và đặt LLM `gemini-2.5-flash`, chunking v1.
4. Provision chỉ đổi protected runtime, chưa thay container. Sau preflight đạt,
   deploy qua **Backend CI/CD**. Pipeline backup DB, Alembic/Flyway, deploy
   immutable image, kiểm tra health và rollback image khi cần.

## Gate trước khi nói “demo sẵn sàng”

- CI/test, image security scan và deploy phải thành công ở **commit mới**.
- `/healthz` trả 200/UP; `/api/books` trả 200 và dữ liệu thật.
- CORS cho đúng frontend origin; login và refresh session chạy được.
- Refresh dùng HttpOnly cookie: frontend lấy token ở `GET /api/auth/csrf`,
  rồi gửi header `X-XSRF-TOKEN` khi `POST /api/auth/refresh`. Cookie đơn thuần
  không đủ để gọi refresh; các API Bearer và webhook có chữ ký giữ contract cũ.
- Chọn ebook production có quyền đọc, PDF hiển thị được và ingestion `INDEXED`.
- Kiểm tra `Find passages`, một câu hỏi có evidence, citation nhảy đúng trang,
  và một câu hỏi ngoài sách có thể abstain. Không tự retry câu hỏi tạo LLM.
- Local data/benchmark không tự xuất hiện ở production. Upload một PDF demo
  hợp pháp qua giao diện khi cần; không ghi đè database production bằng local.

Không dùng 777 unit/integration test hoặc số liệu retrieval offline để khẳng
định end-to-end production đã chạy. Không bật RAG nếu VPS thiếu tài nguyên hoặc
provider key; core backend vẫn có thể deploy riêng trong thời gian chuẩn bị.

## Review CSRF chọn lọc trước release

- Không tắt CSRF toàn cục: `POST /api/auth/refresh` luôn đi qua `CsrfFilter`.
  GET bootstrap trả token trong JSON `Cache-Control: no-store`; frontend gửi
  `X-XSRF-TOKEN` cùng cookie, không đọc cookie HttpOnly bằng JavaScript.
- Giữ `XorCsrfTokenRequestAttributeHandler` của Spring để bảo vệ BREACH. Token
  trong JSON là dạng masked; cookie giữ token gốc, Spring tự so khớp sau giải mã.
- HTTPS dùng `__Host-XSRF-TOKEN`, Secure + HttpOnly + Path=/, không Domain;
  dự án khác trên cùng domain cha không được inject cookie parent-domain này.
  HTTP local dùng `XSRF-TOKEN`; tên header/body contract không đổi.
- API được bảo vệ còn lại chỉ xác thực bằng Bearer header trong `JwtAuthFilter`,
  không lấy danh tính từ cookie. Login/register nhận JSON; CORS chỉ cho origin
  frontend được cấu hình. Webhook Resend kiểm tra chữ ký riêng.
- Regression test xác nhận: thiếu token hoặc header sai trả 403; cookie + header
  đúng được đi tiếp; không tạo HTTP session; production cookie host-bound.
- `CookieAuthSecurityIntegrationTest` kiểm tra thêm security chain/CORS/JWT filter,
  AuthController và error handlers thật qua MockMvc (13 ca). Chỉ service/Redis/JWT
  dependencies được mock, không gọi database/email/provider và không dùng key thật.
  Các ca gồm: cookie không xác thực được API Bearer; Bearer hợp lệ vẫn logout được;
  refresh thiếu/sai CSRF bị 403; CSRF hợp lệ vẫn cần refresh cookie/token hợp lệ;
  refresh thành công rotate cookie; origin lạ bị chặn; preflight đúng origin được
  phép gửi header CSRF; login JSON còn hoạt động nhưng form/text/plain bị 415.
  Đây là kiểm thử tích hợp lớp web/security, không thay thế smoke test production.
- Sonar `java:S4502` cảnh báo mọi custom CSRF matcher. Reviewer cần kiểm tra
  các điều kiện trên trước khi xử lý riêng issue; không thay quality gate/profile.
- Nếu sau này thêm endpoint xác thực bằng cookie, phải cập nhật matcher và test
  trong cùng thay đổi. Không suy ra Bearer-only chỉ vì application stateless.
