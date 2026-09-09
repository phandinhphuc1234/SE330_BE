# Định hướng README và hoàn thiện portfolio

Ngày rà soát: 09/09/2026. Định hướng: ứng tuyển thực tập Java Backend.
Bạn xác nhận phụ trách toàn bộ đồ án; phạm vi source được rà soát ở đây là
repository backend QuanLyThuVien, không phải bằng chứng về một frontend hay
môi trường production chưa được cung cấp.

## 1. Hướng viết đã chọn

Thông điệp chính: **backend quản lý thư viện có nghiệp vụ, ràng buộc dữ liệu,
xử lý bất đồng bộ và kiểm thử có thể xem lại**.

Không lấy số endpoint, số công nghệ hay RAG chưa hoàn thành làm điểm nhấn.
Với dự án này, câu chuyện đáng kể là: vì sao request được gửi lại, dữ liệu cạnh
tranh được bảo vệ ở đâu, import lớn chia transaction thế nào, và lỗi được truy vết ra sao.

README nên giúp người đọc lần lượt trả lời:

1. Dự án giải quyết việc gì, ai sử dụng và bạn phụ trách gì?
2. Ba đến năm bài toán kỹ thuật nào cho thấy chiều sâu triển khai?
3. Có thể xem demo, tìm source/test minh chứng và chạy lại bằng cách nào?
4. Những phần nào đã có code, đã được kiểm chứng, hoặc còn giới hạn?

GitHub khuyến nghị repo được dùng trong hồ sơ có chức năng chính, hướng dẫn chạy,
ví dụ/demo và cách kiểm thử; đồng thời bổ sung mô tả, website thật nếu có và topic
trong mục About. Đây là cơ sở trình bày, không phải bảo đảm được tuyển dụng.
[Nguồn: GitHub – Enhance your resume](https://docs.github.com/en/account-and-profile/tutorials/using-your-github-profile-to-enhance-your-resume).

README gốc giữ phần tổng quan và khởi động; nội dung dài dẫn sang `docs/`, dùng
link tương đối để hoạt động trên cả GitHub lẫn bản clone.
[Nguồn: GitHub – About READMEs](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes).

## 2. Những điểm nên đưa ra trước nhà tuyển dụng

| Điểm nhấn | Bằng chứng hiện có | Cách nói chính xác |
| --- | --- | --- |
| Nghiệp vụ mượn/trả/hold | [Use cases](../src/main/java/com/vn/service/impl/circulation/usecase), [policy](../src/main/java/com/vn/service/impl/circulation/policy), [constraints V14](../src/main/resources/db/migration/V14__update_open_borrow_copy_constraint.sql) và [V15](../src/main/resources/db/migration/V15__hold_queue_constraints.sql) | Tách quy tắc nghiệp vụ, dùng transaction, locking ở những điểm cụ thể và ràng buộc database |
| Retry/idempotency | [Service](../src/main/java/com/vn/service/impl/IdempotencyServiceImpl.java), [test](../src/test/java/com/vn/service/idempotency/IdempotencyServiceImplTest.java) | Replay kết quả cùng request, từ chối key dùng cho payload khác; không tuyên bố exactly-once trong mọi sự cố |
| Import CSV | [Processor](../src/main/java/com/vn/service/impl/importer/csv/BookImportProcessor.java), [chunk](../src/main/java/com/vn/service/impl/importer/chunk/BookImportChunkProcessor.java), [batch insert](../src/main/java/com/vn/service/impl/importer/batch/BookCopyBatchInserter.java) | Giới hạn file 5 MiB/5.000 dòng, chunk 500, JPA + JDBC batch, polling/SSE và lỗi theo dòng; chưa phải queue bền vững tự retry |
| API & xác thực | [API contract](api-contract.md), [security](../src/main/java/com/vn/security), [exception tests](../src/test/java/com/vn/exception/GlobalExceptionHandlerTest.java) | Hợp đồng JSON, status, phân trang và lỗi security thống nhất; Bearer access token và HttpOnly refresh cookie |
| Kiểm thử & vận hành | [Tests](../src/test/java/com/vn), [CI](../.github/workflows/backend-ci.yml), [Dockerfile](../Dockerfile), [monitoring](monitoring-prometheus-grafana.md) | Có unit/MVC/Testcontainers và pipeline verify; nêu rõ ngày đo và phạm vi kiểm chứng |
| Payment, nếu cần case study mở rộng | [Callback](../src/main/java/com/vn/service/impl/PaymentCallbackServiceImpl.java), [test](../src/test/java/com/vn/service/payment/PaymentCallbackServiceImplTest.java) | Verify callback, đối chiếu amount, lock payment và xử lý trạng thái terminal; không tự nhận đạt yêu cầu production |

Mỗi điểm chỉ cần một đoạn ngắn trong README, kèm source/test tương ứng. Khi phỏng
vấn, chuẩn bị giải thích cả lựa chọn thiết kế và tình huống thiết kế đó chưa xử lý.

## 3. Thứ tự việc nên làm tiếp

### Ưu tiên 1 — Làm cho phiên bản gửi đi đáng tin cậy

- [ ] Review và đưa các thay đổi local hợp lệ lên một commit/nhánh rõ ràng. Tại
  thời điểm rà soát, repo còn thay đổi staged/unstaged và test mới chưa được Git
  theo dõi; HEAD đơn lẻ chưa đại diện toàn bộ source đang dùng.
- [ ] Chạy lại `mvn verify` trên đúng commit sẽ gửi; kiểm tra test skipped, lưu
  report và xác nhận một GitHub Actions run thành công. Sau đó mới thêm badge CI.
- [ ] Xử lý các ca hồi quy mật khẩu/gia hạn nêu ở mục 4 trước khi công khai demo.
- [ ] Chuẩn bị snapshot demo đã loại dữ liệu cá nhân, token, secret và nội dung
  ebook không có quyền chia sẻ; viết quy trình restore, kiểm tra trên database
  dùng riêng cho demo. Giữ nguyên V22 và checksum migration local đã chốt.
- [ ] Nếu snapshot sạch chưa thể chia sẻ, cung cấp demo ghi hình và nói rõ hạn
  chế chạy local. Không ghi “clone và chạy một lệnh là dùng được”.

Kết quả cần có: người nhận truy cập đúng phiên bản code, thấy CI/report thật và
không bị mắc ở bước dựng database mà README không báo trước.

### Ưu tiên 2 — Cho thấy sản phẩm hoạt động

- [ ] Quay video khoảng 2–3 phút: đăng nhập librarian → tìm sách → preview →
  checkout → retry cùng key → checkin → xem thay đổi trạng thái/lịch sử.
- [ ] Thêm 2–3 ảnh thật: một luồng nghiệp vụ; một lỗi có `code`/`traceId`; một
  report test hoặc dashboard monitoring. Không đưa token/cookie/email thật vào ảnh.
- [ ] Dẫn link frontend và mô tả phần frontend bạn làm khi có repository tương
  ứng; hiện chưa chèn link vì chưa có thông tin được xác nhận.
- [ ] Rà lại Postman collection theo endpoint hiện tại; chuẩn bị dữ liệu/barcode
  demo và request đủ thành công, thất bại, retry. Thử từ đầu trên snapshot sạch.

Repo hiện có Swagger, Postman và kịch bản demo, nhưng chưa thấy bộ ảnh/video demo
được đóng gói. Có thể dùng Postman quay backend nếu frontend chưa sẵn sàng.

### Ưu tiên 3 — Nâng bằng chứng kỹ thuật

- [ ] Integration test checkout cạnh tranh trên cùng bản sách, hold cạnh tranh,
  manual renew so với auto-renew; assert trạng thái, số bản có sẵn và mã lỗi.
- [ ] HTTP integration test qua security thật: login/refresh/logout, `401`/`403`,
  kiểm tra quyền sở hữu tài nguyên và một luồng mượn/trả đầy đủ.
- [ ] Kiểm tra nâng cấp Flyway trên snapshot tương thích trong môi trường riêng;
  suite hiện tắt Flyway và test khóa row dùng schema Hibernate, nên chưa bao phủ
  partial index cùng toàn bộ lịch sử migration.
- [ ] Test import với lỗi database thật ở giữa chunk và lỗi job/process, không
  chỉ mock chunk trả lỗi dòng. Ghi rõ chunk nào rollback, dữ liệu nào đã commit.
- [ ] Lưu output k6 gốc cùng commit, cấu hình máy, dataset, phiên bản công cụ,
  thời gian, warm-up, số VUs, think time và error rate. Đo cả lần đầu và lần warm.
- [ ] Viết 2–3 ghi chú quyết định thiết kế ngắn: vì sao monolith; vì sao JPA +
  JDBC batch; vì sao dùng cả Redis idempotency lẫn constraint/lock PostgreSQL.

Không nhất thiết thêm Kafka, Kubernetes hay microservices. Chỉ mở rộng kiến trúc
khi có một yêu cầu cụ thể và phép kiểm chứng cho lợi ích của nó.

### Ưu tiên 4 — Hoàn thiện cách trưng bày

- [ ] Điền GitHub About, topic phù hợp (`java`, `spring-boot`, `postgresql`,
  `redis`, `library-management`, `testcontainers`) và pin repo trên profile.
- [ ] Nếu ứng tuyển nơi yêu cầu tiếng Anh, bổ sung README tiếng Anh có cùng
  dữ kiện, không tự tạo thêm thành tích hoặc số đo trong bản dịch.
- [ ] Thống nhất đầu mối kiến trúc: [ARCHITECTURE.md](../ARCHITECTURE.md) là cây
  thư mục cũ; [architecture-overview.md](architecture-overview.md) là tổng quan
  mới hơn. Cần đối chiếu cả hai với source và gắn nhãn tài liệu kế hoạch/spec.
- [ ] Chọn license sau khi xác nhận quyền với source/dữ liệu của đồ án; hiện chưa
  thấy LICENSE. Không tự gắn MIT hoặc license khác chỉ để đủ mục README.
- [ ] Đưa repo và demo vào CV, mô tả đúng vai trò phụ trách toàn bộ; chỉ thêm link
  deployment khi môi trường thật tồn tại và được kiểm tra truy cập.

## 4. Các điểm kỹ thuật cần xác minh trước khi dùng làm thành tích

Đây là nhận xét từ source hiện tại, không phải kết luận từ một phiên pentest hay
load test mới. Đợt viết README không thay đổi mã nghiệp vụ.

| Điểm cần rà soát | Căn cứ trong source | Việc tiếp theo |
| --- | --- | --- |
| Reset token được kiểm tra trước khi khóa member | [PasswordManagementServiceImpl](../src/main/java/com/vn/service/impl/PasswordManagementServiceImpl.java): `resetPassword` đọc token chưa dùng rồi mới lấy member lock, không kiểm tra lại token sau lock | Test hai request đồng thời dùng cùng token nhưng mật khẩu mới khác nhau; bảo đảm chỉ một request thành công |
| Đổi mật khẩu chưa vô hiệu reset token đang tồn tại | Cũng trong service trên: `changePassword` đổi hash mật khẩu và revoke session nhưng không gọi `invalidateUnusedTokensForMember` | Chốt chính sách rồi bổ sung kiểm thử và vô hiệu hóa token reset cũ khi đổi mật khẩu nếu đó là chính sách mong muốn |
| Ranh giới thời gian revoke và JWT `iat` | [RedisTokenService](../src/main/java/com/vn/service/RedisTokenService.java) lưu mốc millisecond; JWT issued-at có độ phân giải giây | Test đổi/reset mật khẩu rồi đăng nhập lại trong cùng giây; tránh token mới bị đánh dấu đã revoke |
| Manual renew khác auto-renew về khóa | [RenewalUseCase](../src/main/java/com/vn/service/impl/circulation/usecase/RenewalUseCase.java) dùng `findById`; auto-renew dùng `findLockedForRenewalById` | Test hai key khác nhau gia hạn cùng khoản mượn và manual/auto chạy đồng thời; kiểm tra mất cập nhật/giới hạn gia hạn |
| Khoảng trống giữa commit SQL và ghi kết quả Redis | [IdempotencyServiceImpl](../src/main/java/com/vn/service/impl/IdempotencyServiceImpl.java) commit business action rồi mới `markCompleted` | Mô tả cách xử lý khi Redis lỗi sau commit; không gọi đây là bảo đảm exactly-once |
| Tham số `.env` chưa được Compose truyền đầy đủ | [compose.yaml](../compose.yaml) chưa truyền `VNPAY_ENABLED` và `EBOOK_READING_SESSION_SECRET` dù có trong [.env.example](../.env.example) | Đối chiếu cấu hình chạy Maven và Docker, thêm kiểm tra cấu hình trước khi hướng dẫn bật/tắt tích hợp |

## 5. Những cách diễn đạt cần tránh

| Không nên ghi | Nên ghi |
| --- | --- |
| “Redis cache tăng tốc API sách” | Redis phục vụ token/idempotency/phiên đọc; `BookServiceImpl.getBook` hiện truy vấn repository, chưa có cache catalog |
| “Chịu tải 3.000 người dùng” | Có script stress với mức đó, nhưng không có kết quả xác nhận; baseline ghi nhận chỉ là 20 VUs/20 giây, nghỉ 1 giây mỗi vòng |
| “Đã giải quyết toàn bộ race condition” | Có constraint/locking ở các điểm cụ thể và test khóa row; còn cần kiểm thử cạnh tranh qua toàn bộ nghiệp vụ |
| “207 test chứng minh hệ thống hoàn chỉnh” | Snapshot Surefire ngày 08/09/2026 có 207 test đạt; Flyway và toàn bộ workflow chưa nằm trong suite |
| “Audit toàn bộ hệ thống” | Có log sự kiện và các bản ghi chuyên biệt như payment event, trạng thái member, job; không đồng nhất mọi log với audit bền vững |
| “Có AI/RAG hoàn chỉnh” | Có mã tích hợp ingestion; tính năng hỏi đáp AI chưa hoàn thành và nằm ngoài demo chính |
| “Production-ready” | Có cấu hình vận hành cơ bản; cần xác minh bảo mật, dữ liệu demo, external integration và triển khai trước khi công khai |

Các phần khác trong source cũng có giá trị: catalog dùng Specifications và tải
ảnh/rating theo batch cho một trang; Docker multi-stage/non-root; API tạo Book/
Payment có `Location`; import trả `202 Accepted`. Dùng làm ví dụ bổ trợ khi
phỏng vấn, không cần biến phần đầu README thành danh sách mọi implementation detail.

## 6. Mẫu mô tả ngắn cho CV

> Phụ trách toàn bộ đồ án quản lý thư viện; xây dựng backend Java 21/Spring Boot
> cho catalog, bạn đọc, mượn/trả, gia hạn và hàng đợi giữ sách. Thiết kế transaction,
> ràng buộc PostgreSQL và Redis idempotency cho các thao tác nghiệp vụ; triển khai
> import CSV bất đồng bộ theo chunk, theo dõi tiến độ qua SSE. Xây dựng kiểm thử
> unit/MVC/Testcontainers cùng cấu hình CI và Docker Compose.

Có thể thêm “207 test đạt trong lần kiểm chứng ngày 08/09/2026” khi đính kèm report
tương ứng. Sau lần chạy mới, cập nhật theo đúng report/commit đó. Không cần thêm
số đo hiệu năng vào CV nếu chưa giữ được dữ liệu đo gốc và điều kiện tái lập.
