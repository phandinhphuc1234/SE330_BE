# Ebook License Schema Design Review

Tài liệu này đánh giá schema ebook/license bạn đưa ra và đối chiếu với hệ thống hiện tại của project `QuanLyThuVien`.

Yêu cầu trong tài liệu này:

- Không implement code.
- Chỉ phân tích schema, migration, bảng cần thêm, bảng chưa cần thêm.
- Giữ PostgreSQL là source of truth cho quyền đọc/license.
- Cloudinary chỉ là nơi lưu file.
- Redis chỉ là cache/TTL/rate-limit, không quyết định license.

## 1. Kết luận nhanh

Thiết kế bạn gửi **đúng hướng** nếu mục tiêu là:

```text
Ebook PDF/EPUB lưu Cloudinary
+ license hữu hạn
+ đọc online bảo mật
+ session có heartbeat
+ audit truy cập
+ reservation khi hết license
```

Nhưng với project hiện tại, mình không khuyên gọi cả 6 bảng là MVP bắt buộc làm ngay. Nên chia thành phase:

```text
Phase 1 - Core ebook borrowing:
- book_ebooks
- ebook_loans
- ebook_access_audit tối giản

Phase 2 - Secure online reader:
- ebook_reading_sessions
- ebook_reading_progress

Phase 3 - License waiting queue:
- ebook_reservations

Phase 4 - Payment/commercial:
- payments hoặc payment_id nếu sau này có module thanh toán
```

Nếu bạn muốn làm đúng spec “đọc online bảo mật + session token + heartbeat” ngay từ đầu, thì `ebook_reading_sessions` nên vào Phase 1 luôn.

## 2. Current system hiện tại đã có gì

Project hiện tại đã có một số nền tảng dùng lại được:

```text
books
members
book_images
book_copies
loans/borrow flow vật lý
ApiResponse
PageMeta
ErrorCode/AppException
SecurityConfig
Cloudinary Java SDK
MediaStorageService
MediaUploadCommand
MediaUploadResult
MediaResourceType
MediaCategory
```

Đặc biệt:

- Đã có bảng `book_images`, nên **không cần tạo lại `book_images`** cho ebook.
- Đã có `MediaStorageService`, có thể tái sử dụng để upload PDF/EPUB lên Cloudinary.
- Đã có `MediaResourceType.RAW`, phù hợp với PDF/EPUB trên Cloudinary.
- Đã có `MediaCategory.BOOK_PDF`; nếu hỗ trợ EPUB nên thêm category rộng hơn như `BOOK_EBOOK` hoặc thêm `BOOK_EPUB`.
- Migration hiện tại đã tới `V27`, nên ebook migration mới phải bắt đầu từ `V28` trở đi.

## 3. Điểm đúng trong schema gợi ý

### 3.1. Không nhét `ebook_url` vào `books`

Đúng.

`books` chỉ nên lưu metadata sách. Ebook là một asset riêng:

```text
1 book có thể có nhiều ebook:
- PDF full
- EPUB full
- PDF preview
- bản upload Cloudinary
- bản external/Gutenberg
- version mới/cũ
```

Do đó cần bảng riêng `book_ebooks`.

### 3.2. PostgreSQL giữ source of truth

Đúng.

Không nên dùng Redis expired event để quyết định trả license vì Redis event có thể:

- Miss event.
- Fire trễ.
- Fire nhiều lần.
- Gặp race condition khi user return/expire/renew cùng lúc.

PostgreSQL nên là nơi giữ:

```text
loan status
session status
license usage
reservation status
audit
progress aggregate
```

Redis chỉ nên dùng:

```text
cache session
rate limit
progress buffer
temporary token TTL
```

### 3.3. Có `ebook_reading_sessions` là đúng nếu đọc online bảo mật

Nếu frontend có online reader thật, session table là cần thiết:

- Token đọc chỉ sống ngắn hạn.
- Có heartbeat.
- Có thể revoke session.
- Có thể debug khi user bị văng.
- Không lưu raw token trong DB.
- Worker có thể chuyển `ACTIVE -> EXPIRED`.

## 4. Điểm cần chỉnh trong schema gợi ý

### 4.1. `ebook_loans.expired_at` nên đổi thành `expires_at`

Trong schema gợi ý:

```sql
expired_at TIMESTAMP NOT NULL
```

Tên này dễ gây hiểu nhầm.

Nên tách rõ:

```text
expires_at = thời điểm dự kiến hết hạn
expired_at = thời điểm hệ thống thật sự chuyển status sang EXPIRED
```

Khuyến nghị:

```text
expires_at TIMESTAMP NOT NULL
expired_at TIMESTAMP NULL
```

API check quyền đọc nên dùng:

```text
status = ACTIVE
expires_at > now()
```

Scheduler khi expire thì set:

```text
status = EXPIRED
expired_at = now()
```

### 4.2. `ebook_loans` không nên bắt buộc lưu cả `book_id` và `book_ebook_id`

Schema gợi ý có:

```text
member_id
book_id
book_ebook_id
```

Về chuẩn hóa, `book_id` lấy được từ `book_ebooks.book_id`, nên trong `ebook_loans` chỉ cần:

```text
member_id
book_ebook_id
```

Nếu lưu cả `book_id`, có nguy cơ dữ liệu lệch:

```text
ebook_loans.book_id = 1
ebook_loans.book_ebook_id lại trỏ tới ebook của book_id = 2
```

Khuyến nghị cho giai đoạn đầu:

```text
ebook_loans chỉ lưu book_ebook_id
```

Nếu sau này cần performance/reporting, có thể denormalize thêm `book_id`, nhưng phải có cách đảm bảo consistency bằng composite constraint hoặc service invariant.

### 4.3. `ebook_reading_sessions` nên trỏ tới `loan_id`, không cần lặp `book_id`

Schema gợi ý có:

```text
member_id
book_id
loan_id
```

Vì `loan_id` đã biết được member và ebook, nên bảng session tối thiểu chỉ cần:

```text
loan_id
session_token_hash
status
session_expires_at
last_heartbeat_at
```

Tuy nhiên, nếu muốn query nhanh theo member/book thì có thể lưu thêm `member_id`. Còn `book_id` nên cân nhắc kỹ vì dễ lệch dữ liệu.

Khuyến nghị:

```text
loan_id là quan hệ chính
member_id có thể denormalize nếu cần query nhanh
book_ebook_id có thể denormalize nếu cần query nhanh
book_id chỉ thêm nếu đã có lý do rõ
```

### 4.4. `ebook_reading_progress` nên key theo `book_ebook_id`, không phải `book_id`

Schema gợi ý:

```sql
PRIMARY KEY (member_id, book_id)
```

Điều này ổn nếu một sách chỉ có một bản ebook và một kiểu page count. Nhưng nếu có PDF và EPUB thì progress khác nhau:

```text
PDF dùng page number
EPUB dùng location/CFI
```

Khuyến nghị:

```text
PRIMARY KEY (member_id, book_ebook_id)
```

Và field nên linh hoạt hơn:

```text
last_page INT NULL
last_location VARCHAR(500) NULL
progress_percent NUMERIC(5,2)
```

`last_location` dùng cho EPUB hoặc reader location không phải page number.

### 4.5. `queue_position` trong reservation có thể bị drift

Schema gợi ý có:

```text
queue_position INT
```

Lưu position cố định dễ bị sai khi:

- User trước hủy reservation.
- Reservation hết hạn.
- Admin revoke.
- Worker promote nhiều record.

Có hai hướng:

Hướng đơn giản:

```text
Không lưu queue_position.
Tính position bằng reserved_at/id khi query.
```

Hướng có lưu:

```text
Lưu queue_position nhưng phải có job/service cập nhật lại position.
```

Với project hiện tại, mình khuyên chưa lưu `queue_position` ở phase đầu. Dùng:

```text
ORDER BY reserved_at ASC, id ASC
```

là đủ.

## 5. Bảng `book_ebooks`

### 5.1. Có nên thêm không?

Có. Đây là bảng quan trọng nhất cho ebook.

Nó thay thế hoàn toàn ý tưởng:

```text
books.ebook_url
```

### 5.2. Thiết kế đề xuất cho project hiện tại

```text
book_ebooks
- id
- book_id
- provider
- source_type
- resource_type
- delivery_type
- public_id
- secure_url
- external_url
- format
- mime_type
- original_filename
- version
- size_bytes
- checksum_sha256
- access_level
- status
- is_primary
- sort_order
- max_concurrent_loans
- loan_duration_days
- max_renewals
- created_at
- updated_at
- deleted_at
```

Ý nghĩa các field quan trọng:

| Field | Ý nghĩa |
|---|---|
| `provider` | `CLOUDINARY`, `GUTENBERG`, `EXTERNAL` |
| `source_type` | `UPLOADED` hoặc `REMOTE_REFERENCE` |
| `resource_type` | Với Cloudinary PDF/EPUB thường là `raw` |
| `delivery_type` | Kiểu Cloudinary delivery, ví dụ `upload`, `authenticated`, `private` |
| `public_id` | ID asset trên Cloudinary, dùng để xóa/quản trị |
| `secure_url` | URL HTTPS Cloudinary trả về, không expose public nếu là ebook private |
| `external_url` | URL ngoài như Gutenberg nếu không upload lên Cloudinary |
| `format` | `PDF`, `EPUB` |
| `access_level` | `PUBLIC_PREVIEW`, `PRIVATE_FULL` |
| `status` | `ACTIVE`, `DISABLED`, `DELETE_PENDING`, `DELETED`, `FAILED` |
| `max_concurrent_loans` | Số license được mượn đồng thời cho ebook này |
| `loan_duration_days` | Số ngày mượn mặc định |
| `max_renewals` | Số lần gia hạn tối đa |

### 5.3. Constraint nên có

Nên có unique provider/public_id nhưng cho phép external source không có public_id:

```text
UNIQUE(provider, public_id) WHERE public_id IS NOT NULL
```

Nên có index:

```text
(book_id, status)
(format, status)
```

Nếu mỗi book chỉ có một primary ebook cho mỗi format:

```text
UNIQUE(book_id, format) WHERE is_primary = true AND status = 'ACTIVE'
```

### 5.4. License nằm ở đâu?

Với mô hình thư viện số, `max_concurrent_loans` nên nằm ở `book_ebooks`, không nằm ở `books`.

Lý do:

```text
PDF có thể mua 5 license
EPUB có thể mua 10 license
Preview không cần license
```

## 6. Bảng `ebook_loans`

### 6.1. Có nên thêm không?

Có. Đây là bảng source of truth cho quyền mượn/đọc ebook.

### 6.2. Thiết kế đề xuất

```text
ebook_loans
- id
- member_id
- book_ebook_id
- status
- borrowed_at
- expires_at
- expired_at
- returned_at
- revoked_at
- revoke_reason
- renew_count
- max_renewals
- created_at
- updated_at
- version
```

Không khuyến nghị lưu `book_id` ngay từ đầu. Lấy `book_id` qua `book_ebook_id`.

### 6.3. Status đề xuất

```text
ACTIVE
EXPIRED
RETURNED
REVOKED
```

Ý nghĩa:

| Status | Ý nghĩa |
|---|---|
| `ACTIVE` | Member còn quyền đọc |
| `EXPIRED` | Hết hạn tự động |
| `RETURNED` | Member trả sớm |
| `REVOKED` | Admin/system thu hồi quyền |

### 6.4. Unique active loan

Có hai rule có thể chọn:

Rule A - không cho mượn trùng cùng một file ebook:

```text
UNIQUE(member_id, book_ebook_id) WHERE status = 'ACTIVE'
```

Rule B - không cho mượn trùng cùng một book dù khác format:

```text
UNIQUE(member_id, book_id) WHERE status = 'ACTIVE'
```

Với thiết kế không lưu `book_id` trong `ebook_loans`, Rule A dễ làm hơn.

Về nghiệp vụ thư viện, mình nghiêng về Rule B vì user không nên mượn cùng lúc PDF và EPUB của cùng một sách rồi chiếm 2 license. Nhưng Rule B cần thêm logic service hoặc denormalized `book_id`.

Khuyến nghị thực tế:

- Phase 1: dùng Rule A ở DB.
- Service thêm check “member đang có active loan cho bất kỳ ebook nào của book này chưa”.
- Nếu sau này cần guarantee DB tuyệt đối cho Rule B, cân nhắc thêm `book_id` vào `ebook_loans` kèm constraint consistency.

### 6.5. License counting phải lock

Khi mượn ebook có license hữu hạn, không thể chỉ:

```text
count active loans
if count < max then insert
```

Nếu hai request chạy song song có thể vượt license.

Luồng nên là:

```text
Begin transaction
Lock book_ebooks row by id
Expire overdue loans nếu cần hoặc chỉ count ACTIVE + expires_at > now
Count active loans for book_ebook_id
If count >= max_concurrent_loans -> create reservation or reject
Insert ebook_loan ACTIVE
Commit
```

Có thể lock bằng:

```text
SELECT ... FROM book_ebooks WHERE id = ? FOR UPDATE
```

hoặc advisory lock theo `book_ebook_id`.

## 7. Bảng `ebook_reading_sessions`

### 7.1. Có nên thêm không?

Tùy mục tiêu.

Nếu chỉ muốn:

```text
Member mượn ebook -> nhận URL PDF -> đọc/tải
```

thì có thể chưa cần session table ở Phase 1.

Nếu muốn:

```text
Đọc online bảo mật
Token ngắn hạn
Heartbeat
Revoke session
Chống share link
```

thì cần `ebook_reading_sessions`.

### 7.2. Thiết kế đề xuất

```text
ebook_reading_sessions
- id
- session_token_hash
- loan_id
- member_id
- status
- session_expires_at
- last_heartbeat_at
- closed_at
- expired_at
- revoked_at
- revoke_reason
- ip_address
- user_agent_hash
- created_at
- updated_at
- version
```

Không lưu raw token.

Luồng token:

```text
Backend generate raw token
Backend hash token bằng SHA-256
DB lưu token hash
Frontend nhận raw token một lần
Các request reader gửi raw token
Backend hash lại và so sánh
```

### 7.3. Session có dùng để tính license không?

Cần chốt rõ nghiệp vụ.

Có hai loại giới hạn khác nhau:

```text
Loan license:
5 member được mượn ebook cùng lúc.

Reading session:
Một member có thể mở tối đa N phiên đọc cùng lúc.
```

Schema bạn gửi đang hơi trộn hai khái niệm này.

Nếu `max_concurrent_loans = 5`, license nên tính theo:

```text
ebook_loans status ACTIVE
```

Không nên tính theo active session, vì member mượn xong đóng tab thì loan vẫn còn hiệu lực 14 ngày.

Nếu muốn giới hạn số tab/thiết bị đang đọc, thêm config riêng:

```text
max_active_sessions_per_loan
max_active_sessions_per_member
```

## 8. Bảng `ebook_reservations`

### 8.1. Có nên thêm không?

Nên thêm nếu bạn muốn hàng chờ khi hết license.

Nếu scope đồ án cần gọn, có thể để Phase 3.

### 8.2. Thiết kế đề xuất

```text
ebook_reservations
- id
- member_id
- book_ebook_id
- status
- reserved_at
- ready_at
- fulfilled_at
- expires_at
- cancelled_at
- created_at
- updated_at
```

Không cần `book_id` nếu đã có `book_ebook_id`.

Không cần `queue_position` ở Phase 1/2. Tính bằng:

```text
ORDER BY reserved_at ASC, id ASC
```

### 8.3. Status đề xuất

```text
WAITING
READY
FULFILLED
CANCELLED
EXPIRED
```

### 8.4. Unique reservation

Nên chặn member tạo nhiều reservation active cho cùng ebook:

```text
UNIQUE(member_id, book_ebook_id)
WHERE status IN ('WAITING', 'READY')
```

PostgreSQL partial unique index có thể làm tốt việc này.

## 9. Bảng `ebook_access_audit`

### 9.1. Có nên thêm không?

Có. Đây là bảng nhẹ nhưng hữu ích cho debug và bảo mật.

Nếu cần làm gọn, Phase 1 vẫn nên có audit tối giản.

### 9.2. Thiết kế đề xuất

```text
ebook_access_audit
- id
- member_id
- book_ebook_id
- loan_id
- session_id
- action
- reason
- ip_address
- user_agent_hash
- trace_id
- created_at
```

Không nên log mọi hành vi nhỏ như scroll/zoom/page view liên tục.

Nên log:

```text
BORROW_CREATED
BORROW_DENIED
OPEN_READER
ACCESS_DENIED
ACCESS_GRANTED
CLOSE_READER
SESSION_EXPIRED
SESSION_REVOKED
LOAN_EXPIRED
LOAN_RETURNED
ADMIN_REVOKED
SUSPICIOUS_ACTIVITY
```

### 9.3. FK trong audit

Audit thường là append-only. Nếu sau này xóa mềm member/book/ebook thì FK vẫn ổn.

Nếu có khả năng hard-delete dữ liệu liên quan, nên cân nhắc:

- FK nullable.
- Lưu thêm snapshot field như `book_title_snapshot`, `member_code_snapshot`.

Hiện tại project chủ yếu soft-delete, FK vẫn chấp nhận được.

## 10. Bảng `ebook_reading_progress`

### 10.1. Có nên thêm không?

Nên thêm nếu frontend có reader và muốn nhớ vị trí đọc.

Nếu chỉ cho tải file PDF, chưa cần.

### 10.2. Thiết kế đề xuất

```text
ebook_reading_progress
- member_id
- book_ebook_id
- last_page
- last_location
- progress_percent
- updated_at
```

Primary key:

```text
(member_id, book_ebook_id)
```

Lý do không dùng `(member_id, book_id)`:

- PDF và EPUB có progress khác nhau.
- Version ebook mới có thể khác page count.

### 10.3. Không ghi DB mỗi lần lật trang

Nên dùng:

```text
Frontend gửi progress định kỳ
Backend ghi Redis buffer
Flush DB mỗi 30-60 giây hoặc khi close reader
```

Nếu chưa có Redis flow, có thể ghi DB mỗi 15-30 giây để đơn giản.

## 11. Bảng `book_images`

Không cần thêm.

Project hiện tại đã có:

```text
book_images
```

Bảng này đã lưu metadata ảnh bìa Cloudinary. Không nên tạo thêm bảng `book_images` mới trong ebook migration.

Ebook và cover image nên tách:

```text
book_images = ảnh bìa
book_ebooks = file ebook PDF/EPUB
```

Không nên gộp vội hai bảng này ở giai đoạn hiện tại, vì code cover image đã ổn định theo `book_images`.

## 12. Payments

Hiện tại chưa nên thêm nếu đồ án chưa có payment flow.

Nếu chỉ mock thanh toán, có thể để:

```text
ebook_loans.payment_id nullable
```

nhưng chỉ thêm khi có API/payment use case thật.

Nếu thêm quá sớm, hệ thống sẽ có bảng nhưng không có nghiệp vụ rõ, dễ thành dead schema.

Khuyến nghị:

```text
Phase 1-3: chưa thêm payments.
Phase 4: thêm payments khi có yêu cầu mua/thuê ebook trả phí.
```

## 13. Redis key design

Đồng ý với hướng dùng Redis nhưng không đưa Redis vào migration.

Redis key có thể dùng:

```text
ebook:reading-session:{sessionTokenHash}
ebook:active-sessions:{memberId}
ebook:progress-buffer:{memberId}:{bookEbookId}
ebook:rate-limit:{memberId}:{bookEbookId}
```

Vai trò:

| Key | Vai trò |
|---|---|
| `ebook:reading-session:{sessionTokenHash}` | Cache session ngắn hạn |
| `ebook:active-sessions:{memberId}` | Giới hạn số session đang mở |
| `ebook:progress-buffer:{memberId}:{bookEbookId}` | Buffer progress trước khi flush DB |
| `ebook:rate-limit:{memberId}:{bookEbookId}` | Chống spam heartbeat/progress |

Nhắc lại:

```text
Redis không được là nguồn quyết định loan/license.
```

Nếu Redis mất toàn bộ dữ liệu, hệ thống vẫn phải rebuild được quyền đọc từ PostgreSQL.

## 14. Migration plan theo project hiện tại

Migration hiện tại đã tới:

```text
V27__book_cover_upload_lifecycle.sql
```

Nếu implement theo phase gọn:

```text
V28__create_book_ebooks.sql
V29__create_ebook_loans.sql
V30__create_ebook_access_audit.sql
```

Nếu implement luôn secure reader:

```text
V31__create_ebook_reading_sessions.sql
V32__create_ebook_reading_progress.sql
```

Nếu implement reservation:

```text
V33__create_ebook_reservations.sql
```

Không tạo:

```text
V22__add_ebook_feature.sql
V23__seed_data.sql
```

vì version này đã được dùng trong project hiện tại.

## 15. API impact từ schema này

Schema này sẽ dẫn tới API khác với PR của bạn bạn.

### Public/member-safe API

```text
GET /api/books/{bookId}/ebooks
```

Trả metadata an toàn:

```text
id
format
borrowable
loanDurationDays
availableLicenses hoặc available=false
```

Không trả:

```text
secure_url
external_url
public_id
```

### Member loan API

```text
POST /api/user/ebook-loans
GET /api/user/ebook-loans
GET /api/user/ebook-loans/{loanId}
POST /api/user/ebook-loans/{loanId}/renew
POST /api/user/ebook-loans/{loanId}/return
```

### Secure reader API

Nếu có session:

```text
POST /api/user/ebook-loans/{loanId}/sessions
POST /api/user/ebook-reading-sessions/{sessionId}/heartbeat
POST /api/user/ebook-reading-sessions/{sessionId}/close
GET /api/user/ebook-reading-sessions/{sessionId}/access
```

Nếu chưa làm session:

```text
GET /api/user/ebook-loans/{loanId}/access
```

### Admin/staff ebook API

```text
POST /api/books/{bookId}/ebooks
GET /api/books/{bookId}/ebooks/manage
PATCH /api/books/{bookId}/ebooks/{ebookId}
DELETE /api/books/{bookId}/ebooks/{ebookId}
```

## 16. Nên chọn scope nào cho đồ án

### Scope gọn nhưng vẫn đúng

Nếu muốn nhanh, dễ bảo vệ, ít rủi ro:

```text
book_ebooks
ebook_loans
ebook_access_audit
```

Flow:

```text
Admin upload PDF
Member borrow ebook
Backend check license bằng active loans
Member gọi access endpoint để lấy URL
Scheduler expire overdue loans
Audit event quan trọng
```

### Scope production hơn

Nếu muốn thể hiện tốt phần bảo mật đọc online:

```text
book_ebooks
ebook_loans
ebook_reading_sessions
ebook_access_audit
ebook_reading_progress
```

Flow:

```text
Member borrow ebook
Member open reader
Backend tạo reading session token
Frontend heartbeat
Backend kiểm tra session khi cấp read URL
Progress lưu aggregate
Worker expire session/loan
```

### Scope đầy đủ license queue

Nếu muốn giống thư viện số có số license hữu hạn:

```text
book_ebooks
ebook_loans
ebook_reading_sessions
ebook_reservations
ebook_access_audit
ebook_reading_progress
```

Flow thêm:

```text
Nếu active loans >= max_concurrent_loans
-> tạo reservation WAITING
Khi loan returned/expired
-> promote reservation kế tiếp sang READY
```

## 17. Khuyến nghị cuối cùng

Mình khuyên project này dùng hướng sau:

### Làm ngay

```text
book_ebooks
ebook_loans
ebook_access_audit
```

Lý do:

- Thay thế được `books.ebook_url`.
- Mượn ebook đúng theo license.
- Không expose URL public.
- Tái sử dụng được Cloudinary/MediaStorageService hiện có.
- Không làm scope phình quá nhanh.

### Làm sau khi borrow/access ổn

```text
ebook_reading_sessions
ebook_reading_progress
```

Lý do:

- Đây là phần phục vụ online reader.
- Cần frontend phối hợp heartbeat/progress.
- Nếu backend làm trước mà frontend chưa có reader thì dễ dư code.

### Làm cuối

```text
ebook_reservations
payments
```

Lý do:

- Reservation chỉ cần khi thật sự muốn hàng chờ license.
- Payment chỉ cần khi có nghiệp vụ trả phí.

Tóm lại:

```text
book_ebooks là metadata ebook.
ebook_loans là quyền đọc.
ebook_reading_sessions là phiên đọc ngắn hạn.
ebook_reservations là hàng chờ license.
ebook_access_audit là lịch sử bảo mật.
ebook_reading_progress là tiến độ đọc.

Cloudinary lưu file.
Redis cache phụ.
PostgreSQL quyết định quyền đọc và license.
```
