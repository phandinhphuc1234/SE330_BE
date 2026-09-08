# Kế hoạch cải thiện repo để ứng tuyển thực tập

Ngày lập: 07/09/2026. Phạm vi chính: backend `SE330_BE` và bản local `QuanLyThuVien`.

Mục tiêu: đồng bộ source, sửa các lỗi đã xác nhận, có bản demo dễ chạy và repo đủ tài liệu để giới thiệu khi phỏng vấn. Tạm dừng phát triển tính năng RAG.

## Hiện trạng làm mốc

- Local `main`: `dd67e7e`; GitHub `main`: `a879d6f`; lệch 1 commit local và 4 commit remote.
- Local có 36 file sửa và 29 file chưa được Git theo dõi.
- Build mới: local chạy thành công 173 test; snapshot GitHub chạy thành công 161 test. Chưa chạy context test với hạ tầng thật.
- Đã tái hiện trên cả hai bản: refresh token được chấp nhận như access token, JWT filter xác thực tài khoản BANNED, lỗi path variable sai kiểu trả 500.
- Chưa có README gốc, workflow CI và kiểm thử tích hợp PostgreSQL/security filter chain đầy đủ.

## 1. Bảo toàn và đồng bộ source

- [x] Kiểm tra lại GitHub/local tại thời điểm bắt đầu triển khai; sao lưu thay đổi chưa commit và dữ liệu cần giữ.
- [x] Tạo nhánh cải thiện riêng; chia thay đổi thành checkpoint, merge, và các commit sửa lỗi dễ review.
- [x] Hợp nhất hai bản review sách, giữ các cải tiến local về validation, truy vấn thống kê, xử lý trùng và test.
- [x] Đưa thống kê mượn/trả từ GitHub về local; giữ phần ảnh tác giả và SSE import CSV đang có ở local.
- [x] Đối chiếu DTO, API docs, cấu hình, timezone, dependency và các thay đổi ebook/storage giữa hai phía.
- [x] Rà soát PR #2 và #3 để xác định phần đã có, còn thiếu hoặc bị thay thế trước khi đề xuất xử lý PR.

Hoàn thành 07/09/2026 trên nhánh `improve/repository-sync`: GitHub `origin/main` đã là tổ tiên của nhánh; checkpoint dự phòng là `backup/pre-repository-sync-20260907`. Giữ migration local V35 cho ebook storage và V36 cho book review để tránh trùng version Flyway. Kiểm thử sau merge: 173 tests, 0 failures, 0 errors.

## 2. Thống nhất migration và môi trường demo

- [x] Kiểm tra lịch sử `flyway_schema_history` của database local cần giữ.
- [x] Giải quyết xung đột V35 và hai script tạo `book_reviews`; thống nhất kiểu dữ liệu, constraint và index.
- [x] Chuẩn bị hướng dẫn nâng cấp database hiện có; không tự ý sửa migration đã áp dụng hoặc xóa dữ liệu.
- [x] Thêm cấu hình bật/tắt RAG, mặc định tắt cho demo; không gọi ingestion khi RAG tắt.
- [x] Bỏ yêu cầu RAG key/network khỏi cách chạy demo cơ bản; nếu giữ ebook/S3 thì cấu hình storage chạy độc lập.
- [x] Hoàn thiện Docker Compose, `.env.example`, Java 21 và dữ liệu mẫu; kiểm tra khởi động với database demo local.

Cập nhật 07/09/2026: Docker demo đã chỉ còn PostgreSQL, Redis và service chính; PgAdmin/monitoring dùng profile tùy chọn. Test hồi quy RAG và full unit/slice suite đều xanh (175 tests). Stack database trống xác nhận migration dừng ở `V22__normalize_book_isbns.sql`: file này là data-fix lịch sử cho các book ID 2999-3311 không được seed bởi migration trước đó. Theo quyết định dự án, giữ nguyên migration local và demo dùng database local/snapshot đã chạy thành công V22; không sửa checksum migration để hỗ trợ database trống. Database local đã xác minh 39 migration thành công; backend validate schema và healthcheck thành công trên 07/09/2026.

## 3. Sửa xác thực và chuẩn hóa lỗi API

- [x] Phân biệt access token và refresh token; kiểm tra đúng loại ở từng endpoint/filter.
- [x] Kiểm tra trạng thái tài khoản khi xác thực JWT và làm mới token; rà soát thu hồi token khi khóa tài khoản/đăng xuất.
- [x] Chuẩn hóa lỗi 401/403 trong security filter bằng cùng contract lỗi của ứng dụng.
- [x] Đưa lỗi thiếu refresh token về xử lý tập trung; thống nhất `ErrorCode`, log và trace ID.
- [x] Bổ sung xử lý sai kiểu dữ liệu, thiếu header/file, content type không hỗ trợ và các lỗi validation còn thiếu.
- [x] Kiểm tra quyền MEMBER/LIBRARIAN/ADMIN và quyền sở hữu tài nguyên tại các API quan trọng.
- [x] Rà soát cookie, CORS, log và cấu hình demo để tránh đưa token/secret vào repo hoặc response.

Hoàn thành 08/09/2026: JWT mới có claim loại token; refresh token hoặc token cũ không có claim này không còn dùng được cho Bearer API, vì vậy người dùng cần đăng nhập lại sau khi deploy. Filter xác thực lại trạng thái Member ở mỗi request; refresh của tài khoản không còn ACTIVE bị xóa khỏi Redis và từ chối. Security entry point/access denied trả `ApiResponse` có `traceId`; đồng thời thêm mapping 4xx còn thiếu. Reader ebook, payment và receipt chỉ nhận role MEMBER; ownership tiếp tục lấy từ principal và kiểm tra trong service. CORS/cookie đã chuyển sang biến môi trường với default local rõ ràng. Kiểm thử: 183 tests, 0 failures/errors; Docker service healthy, `GET /api/members/me` không token trả `401 UNAUTHORIZED`, Bearer rác trả `401 INVALID_OR_EXPIRED_TOKEN` theo `ApiResponse`, và preflight từ `http://localhost:3000` trả đủ CORS headers.

## 4. Hoàn thiện nghiệp vụ và API hiện có

- [ ] Refactor thống kê mượn/trả về service + DTO + `ApiResponse`; kiểm tra khoảng ngày, bộ lọc và timezone.
- [ ] Thống nhất HTTP status, `Location` khi phù hợp, quy ước dữ liệu rỗng, phân trang và timestamp; đối chiếu frontend trước thay đổi contract.
- [ ] Giữ format riêng của SSE và callback VNPAY; kiểm tra cả đường lỗi, mất kết nối và gọi lặp.
- [ ] Rà soát transaction, khóa dữ liệu và idempotency của mượn/trả, giữ chỗ và thanh toán.
- [ ] Kiểm tra luồng ảnh tác giả, review sách, import CSV và các tác vụ quá hạn/gia hạn/nhắc hạn.

## 5. Kiểm thử và CI

Viết test hồi quy cùng lúc sửa lỗi; bổ sung kiểm thử tích hợp sau khi thống nhất migration và môi trường.

- [ ] Test hồi quy cho sai loại JWT, tài khoản bị khóa, sai quyền và mapping lỗi 4xx.
- [ ] Thiết lập test profile và PostgreSQL Testcontainers; dùng Redis test riêng hoặc mock theo phạm vi.
- [ ] Kiểm tra migration từ database trống và đường nâng cấp dữ liệu đã thống nhất.
- [ ] Test hai request cùng mượn một bản sách, giữ chỗ/gia hạn cạnh tranh và ràng buộc database.
- [ ] Test lặp `Idempotency-Key`, payload khác cùng key và callback payment trùng không tạo kết quả trùng.
- [ ] Test API qua security filter chain; chạy context test trong môi trường cô lập, không dùng dữ liệu thật hay gửi email/thanh toán thật.
- [ ] Thêm GitHub Actions chạy build/test cho push và PR; lưu kết quả kiểm thử để dễ kiểm tra.

## 6. Tài liệu, demo và bản phát hành

- [ ] Viết README: chức năng đã có, công nghệ, cách chạy, test, Swagger, tài khoản demo và phần trực tiếp phụ trách.
- [ ] Hoàn thiện kiến trúc, ERD và sơ đồ ba luồng chính: mượn/trả, giữ chỗ, thanh toán.
- [ ] Đồng bộ Swagger/OpenAPI với response thành công, lỗi, phân trang và các ngoại lệ SSE/provider.
- [ ] Cập nhật tài liệu cũ; phân biệt chức năng đã hoàn thành với RAG và các ý tưởng để sau.
- [ ] Chuẩn bị request mẫu/Postman collection, kịch bản demo và ảnh/video ngắn.
- [ ] Kiểm tra lại thao tác clone → cấu hình → chạy → demo; review diff và test trước khi merge/push bản hoàn thiện.
- [ ] Rà soát PR cũ theo kết quả đối chiếu, viết release notes và chọn commit ổn định dùng trong hồ sơ.

## 7. Làm thêm nếu còn thời gian

- [ ] Đổi mật khẩu và quên/đặt lại mật khẩu, kèm giới hạn tần suất và vô hiệu hóa token phù hợp.
- [ ] API quản lý trạng thái thành viên theo phân quyền, kèm ghi nhận thao tác.
- [ ] Bổ sung đo hiệu năng/coverage cho luồng quan trọng và cập nhật số liệu đã đo vào README.

## Các điểm cần hỏi trước khi triển khai phần liên quan

1. Database local/VPS nào cần giữ dữ liệu, và V35/V36 đã được áp dụng ở đâu?
2. Bản demo có giữ ebook/S3 không? RAG sẽ được giữ dưới cấu hình tắt hay chuyển sang nhánh phát triển riêng?
3. Cần hoàn thành trước ngày nào để ưu tiên khối lượng phù hợp?
4. Frontend đang dùng repo/nhánh nào; phạm vi cải thiện lần này có bao gồm cập nhật frontend khi API thay đổi không?
5. Khi hai phía có nghiệp vụ mâu thuẫn hoặc cần quyết định giữ/bỏ phần của thành viên khác, xác nhận với người dùng trước khi hợp nhất.

Các câu hỏi được xử lý khi bắt đầu giai đoạn liên quan; vẫn tiếp tục những việc độc lập có đủ thông tin. Kế hoạch này chưa phải quyết định thay đổi database, loại bỏ tính năng hay xử lý PR.

## Tiêu chí hoàn thành

- Có một bản source thống nhất, các thay đổi cần giữ được quản lý bằng Git.
- Các lỗi đã xác nhận được sửa và có test hồi quy; build và CI thành công.
- Database mới và đường nâng cấp đã chọn đều được kiểm tra.
- Demo chạy được với RAG tắt; các luồng trong phạm vi đã chốt hoạt động từ đầu đến cuối.
- README, API docs và nội dung giới thiệu phản ánh đúng khả năng thực tế của bản phát hành.
