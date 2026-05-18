# Circulation Policy hiện tại của The Athenaeum

Tài liệu này mô tả các policy circulation đã được triển khai trong backend hiện tại. Mục tiêu là làm rõ hệ thống đang kiểm soát nghiệp vụ mượn, trả, gia hạn và phạt quá hạn như thế nào, rule nào đã chạy trong code, rule nào mới là hướng mở rộng.

## 1. Tổng quan

Hệ thống đã có circulation policy ở mức core cho các flow sau:

```text
1. Staff preview checkout
2. Staff checkout
3. Staff checkin
4. Member self-renewal
5. Staff renewal
6. Member active borrows/history
7. Overdue fine calculation khi checkin
8. Idempotency cho các write API quan trọng
```

Các policy này được implement chủ yếu trong package:

```text
src/main/java/com/vn/service/impl/circulation
```

Trong đó:

```text
CirculationPolicyService   -> rule ai được mượn/gia hạn và khi nào bị chặn
CheckoutUseCase            -> flow preview + checkout
CheckinUseCase             -> flow trả sách
RenewalUseCase             -> flow gia hạn
CirculationFineService     -> tính ngày quá hạn và tiền phạt
CirculationSettingService  -> đọc cấu hình circulation từ system_settings
CirculationLookupService   -> lookup member/copy và lock copy khi cần
```

`CirculationServiceImpl` hiện đóng vai trò facade/orchestrator: nhận request từ controller, bọc idempotency cho các write operation, rồi delegate sang use case tương ứng.

## 2. Cấu hình policy

Các cấu hình circulation hiện nằm trong bảng `system_settings`.

| Key | Giá trị seed/default | Ý nghĩa |
|---|---:|---|
| `BORROW_DAYS_DEFAULT` | `14` | Số ngày mượn mặc định khi checkout |
| `MAX_RENEWALS_DEFAULT` | `1` | Số lần gia hạn tối đa được snapshot vào borrow record khi checkout |
| `RENEWAL_DAYS_DEFAULT` | `7` | Số ngày cộng thêm khi gia hạn nếu request không truyền `requestedDays` |
| `ALLOW_RENEW_OVERDUE` | `false` | Có cho phép gia hạn lượt mượn đã quá hạn hay không |

Điểm quan trọng: `maxRenewalsAtCheckout` được lưu trực tiếp vào `borrow_records` tại thời điểm checkout. Nhờ vậy nếu admin đổi policy `MAX_RENEWALS_DEFAULT` sau này, các lượt mượn cũ vẫn giữ rule tại thời điểm chúng được tạo.

## 3. Role và quyền truy cập

| API | Role | Idempotency-Key | Ý nghĩa |
|---|---|---|---|
| `POST /api/staff/circulation/checkouts/preview` | `LIBRARIAN`, `ADMIN` | Không cần | Kiểm tra trước điều kiện mượn |
| `POST /api/staff/circulation/checkouts` | `LIBRARIAN`, `ADMIN` | Bắt buộc | Tạo lượt mượn |
| `POST /api/staff/circulation/checkins` | `LIBRARIAN`, `ADMIN` | Bắt buộc | Trả sách |
| `GET /api/borrows/my` | `MEMBER` | Không cần | Xem sách đang mượn |
| `GET /api/borrows/my/history` | `MEMBER` | Không cần | Xem lịch sử mượn |
| `PUT /api/borrows/{borrowId}/extend` | `MEMBER` | Bắt buộc | Member tự gia hạn sách của mình |
| `PUT /api/staff/borrows/{borrowId}/extend` | `LIBRARIAN`, `ADMIN` | Bắt buộc | Staff gia hạn hộ member |

Hệ thống hiện không cho staff/admin tự mượn sách theo tư cách borrower trong circulation. Borrower bắt buộc phải là tài khoản có role `MEMBER`.

## 4. Trạng thái nghiệp vụ

### 4.1. BorrowStatus

```text
BORROWED
RETURNED
OVERDUE
LOST
```

Nhóm trạng thái đang được dùng trong policy:

```text
activeStatuses = BORROWED, OVERDUE
openStatuses   = BORROWED, OVERDUE, LOST
renewable      = BORROWED
```

Ý nghĩa:

| Nhóm | Dùng ở đâu | Ý nghĩa |
|---|---|---|
| `activeStatuses()` | kiểm tra quota, danh sách đang mượn | Các lượt mượn còn tính vào trách nhiệm hiện tại của member |
| `openStatuses()` | checkin | Các lượt mượn chưa đóng hoàn toàn, có thể tìm để trả sách |
| `isRenewable()` | renewal | Hiện chỉ `BORROWED` được phép gia hạn |

### 4.2. BookCopyStatus

```text
AVAILABLE
BORROWED
RESERVED
OVERDUE
ON_HOLD_SHELF
LOST
DAMAGED
REMOVED
```

Trong flow đã implement:

| Status | Hiện đang dùng trong code |
|---|---|
| `AVAILABLE` | Có thể checkout |
| `BORROWED` | Đang được mượn |
| `DAMAGED` | Khi trả sách với returnCondition = `DAMAGED` |
| `RESERVED`, `ON_HOLD_SHELF` | Đã có trong enum/schema, phục vụ hold flow phase sau |
| `OVERDUE`, `LOST`, `REMOVED` | Đã có trong enum/schema, phục vụ overdue/lost/remove phase sau |

## 5. Checkout preview policy

Endpoint:

```http
POST /api/staff/circulation/checkouts/preview
```

Preview dùng để thủ thư kiểm tra điều kiện mượn trước khi tạo borrow thật. Flow này không ghi dữ liệu.

Rule được dùng:

```text
1. Member phải tồn tại
2. Member phải có role MEMBER
3. Member status phải là ACTIVE
4. Membership chưa hết hạn
5. Member chưa vượt maxBorrowLimit
6. Member không có borrow đang OVERDUE
7. Book copy phải tồn tại theo barcode
8. Book copy phải có status AVAILABLE
9. Book của copy chưa bị soft delete
```

Điểm thiết kế quan trọng: preview không throw ngay lỗi đầu tiên. Nó gom lỗi thành danh sách `CirculationBlockResponse` để UI có thể hiển thị nhiều lý do bị chặn cùng lúc.

Ví dụ member vừa hết hạn thẻ, vừa đang có sách quá hạn:

```text
eligible = false
reasons:
- MEMBERSHIP_EXPIRED
- MEMBER_HAS_OVERDUE_ITEMS
```

Nếu không có lỗi, response preview sẽ trả:

```text
eligible = true
borrowDays = BORROW_DAYS_DEFAULT
dueDate = now + borrowDays
```

## 6. Checkout policy

Endpoint:

```http
POST /api/staff/circulation/checkouts
```

Checkout thật dùng cùng bộ rule với preview thông qua `CirculationPolicyService`. Khác biệt là checkout thật sẽ throw `AppException` khi gặp lỗi đầu tiên.

Rule bắt buộc:

```text
1. Request phải có Idempotency-Key
2. Actor phải là LIBRARIAN hoặc ADMIN
3. Borrower phải tồn tại
4. Borrower phải là MEMBER
5. Borrower phải ACTIVE
6. Borrower membership chưa hết hạn
7. Borrower chưa vượt maxBorrowLimit
8. Borrower không có borrow OVERDUE
9. Copy phải tồn tại theo barcode
10. Copy phải AVAILABLE
11. Book của copy chưa bị soft delete
```

Khi checkout thành công:

```text
1. Tạo BorrowRecord
2. borrowedAt = now
3. dueDate = now + BORROW_DAYS_DEFAULT
4. status = BORROWED
5. renewCount = 0
6. maxRenewalsAtCheckout = MAX_RENEWALS_DEFAULT
7. fineAmount = 0
8. BookCopy.status = BORROWED
9. Book.availableCopies giảm 1
```

Checkout dùng pessimistic lock khi tìm copy theo barcode. Mục tiêu là tránh hai staff request đồng thời checkout cùng một bản sách vật lý.

## 7. Checkin policy

Endpoint:

```http
POST /api/staff/circulation/checkins
```

Rule bắt buộc:

```text
1. Request phải có Idempotency-Key
2. Actor phải là LIBRARIAN hoặc ADMIN
3. Copy phải tồn tại theo barcode
4. Copy được lock khi xử lý checkin
5. Phải tìm được BorrowRecord open của copy
```

Open borrow được xác định bằng:

```text
BORROWED
OVERDUE
LOST
```

Khi checkin:

```text
1. returnedAt = now
2. Tính overdueDays dựa trên dueDate và returnedAt
3. Nếu overdueDays > 0 thì tính fine
4. BorrowRecord.status = RETURNED
5. Nếu returnCondition = DAMAGED thì BookCopy.status = DAMAGED
6. Nếu returnCondition khác DAMAGED hoặc rỗng thì BookCopy.status = AVAILABLE
7. Nếu copy quay lại AVAILABLE thì Book.availableCopies tăng 1
```

Hiện tại nếu sách trả về bị hỏng, hệ thống không tăng `availableCopies`, vì bản sách đó không quay lại kho sẵn sàng.

## 8. Overdue fine policy

Fine hiện được tính tại thời điểm checkin.

Số ngày quá hạn:

```text
overdueDays = max(0, returnedDate - dueDate)
```

Hệ thống dùng múi giờ nghiệp vụ:

```text
Asia/Bangkok
```

Lý do: tiền phạt thư viện thường tính theo ngày nghiệp vụ, không tính theo số giờ/phút lẻ giữa hai `Instant`.

Nếu `overdueDays > 0`, hệ thống tìm `FineConfig` đang active tại ngày tính phạt:

```text
effectiveFrom <= today
effectiveUntil is null hoặc effectiveUntil >= today
```

Sau đó:

```text
fineAmount = ratePerDay * overdueDays
fineConfig = config đang áp dụng
fineCalculatedAt = now
```

Điểm quan trọng: borrow record lưu lại `fineConfig` đã dùng. Vì vậy nếu sau này thay đổi rate phạt, lịch sử tiền phạt cũ không bị tính lại sai.

Hiện chưa có API thanh toán/waive fine trong circulation core hiện tại. Các field như `finePaidAt`, `fineWaivedBy`, `fineWaivedReason` đã có trong entity để mở rộng.

## 9. Renewal policy

Endpoints:

```http
PUT /api/borrows/{borrowId}/extend
PUT /api/staff/borrows/{borrowId}/extend
```

Hệ thống đang hỗ trợ hai kiểu:

```text
Member tự gia hạn
Staff gia hạn hộ member
```

Đây là hướng tương tự các hệ thống thư viện lớn như Koha/Evergreen: patron có thể tự renew nếu policy cho phép, staff vẫn có thể renew hộ.

Rule bắt buộc:

```text
1. Request phải có Idempotency-Key
2. BorrowRecord phải tồn tại
3. BorrowRecord được lock khi xử lý renewal
4. Borrower phải là MEMBER
5. Borrower status phải ACTIVE
6. Borrower membership chưa hết hạn
7. Nếu là member self-renewal thì borrow phải thuộc chính actor hiện tại
8. BorrowStatus phải là BORROWED
9. Nếu borrow đã quá hạn thì chỉ được renew khi ALLOW_RENEW_OVERDUE = true
10. renewCount phải nhỏ hơn maxRenewalsAtCheckout
11. Không được có reservation active trên cùng book
```

Reservation active hiện gồm:

```text
WAITING
NOTIFIED
READY_FOR_PICKUP
```

Nếu có active reservation, renewal bị chặn bằng lỗi:

```text
RENEWAL_BLOCKED_BY_HOLD
```

Lý do nghiệp vụ: nếu có người khác đang chờ sách, người đang mượn không nên tiếp tục giữ sách bằng cách gia hạn.

Khi renewal thành công:

```text
oldDueDate = dueDate hiện tại
newDueDate = oldDueDate + requestedDays
renewCount = renewCount + 1
```

Nếu request không truyền `requestedDays`, hệ thống dùng:

```text
RENEWAL_DAYS_DEFAULT = 7
```

## 10. Idempotency policy

Các API write quan trọng yêu cầu header:

```http
Idempotency-Key: <uuid-or-random-key>
```

Áp dụng cho:

```text
POST /api/staff/circulation/checkouts
POST /api/staff/circulation/checkins
PUT /api/borrows/{borrowId}/extend
PUT /api/staff/borrows/{borrowId}/extend
```

Scope của key:

```text
actorId + httpMethod + normalizedPath + idempotencyKey
```

Request hash:

```text
SHA-256(method + normalizedPath + canonical JSON body)
```

Behavior:

```text
Key mới:
  insert PROCESSING -> chạy nghiệp vụ -> lưu response -> COMPLETED

Retry cùng key, cùng body, đã COMPLETED:
  trả lại response cũ, không chạy lại nghiệp vụ

Retry cùng key khi request đầu vẫn PROCESSING:
  trả REQUEST_ALREADY_PROCESSING

Dùng lại key nhưng body khác:
  trả IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST
```

TTL hiện tại:

```text
24 giờ
```

Hiện có bảng `idempotency_records` và unique constraint ở database để chống race condition. Đây là điểm quan trọng vì hai request đồng thời có thể cùng đến backend; database unique constraint mới là lớp bảo vệ cuối cùng.

## 11. Inventory counter policy

Hệ thống không recount toàn bộ `book_copies` sau mỗi checkout/checkin. Thay vào đó dùng delta update:

Checkout thành công:

```text
availableCopies -= 1
totalCopies không đổi
```

Checkin sách bình thường:

```text
availableCopies += 1
totalCopies không đổi
```

Checkin sách hỏng:

```text
availableCopies không đổi
BookCopy.status = DAMAGED
```

Lý do: delta update nhanh hơn recount, phù hợp khi số lượng copies lớn. Khi dùng delta update, các flow làm thay đổi trạng thái copy phải được kiểm soát chặt để tránh lệch counter.

## 12. Locking policy

Hệ thống dùng pessimistic lock ở các điểm có nguy cơ race condition:

```text
Checkout/checkin:
  lock BookCopy theo barcode

Renewal:
  lock BorrowRecord theo borrowId
```

Mục tiêu:

```text
Không cho hai checkout cùng lấy một copy
Không cho checkin/checkout cập nhật cùng copy đồng thời
Không cho hai request renewal cùng tăng renewCount hai lần
```

Idempotency xử lý retry từ client, còn pessimistic lock xử lý tranh chấp cập nhật cùng entity ở database transaction.

## 13. Error policy

Các lỗi nghiệp vụ circulation đang được map qua `ErrorCode`, không throw raw exception.

Các lỗi quan trọng:

| ErrorCode | Khi nào xảy ra |
|---|---|
| `BORROWER_MUST_BE_MEMBER` | Borrower không phải role MEMBER |
| `MEMBER_NOT_ACTIVE` | Member chưa active hoặc bị khóa |
| `MEMBERSHIP_EXPIRED` | Thẻ thành viên đã hết hạn |
| `BORROW_LIMIT_EXCEEDED` | Member đã đạt giới hạn mượn |
| `MEMBER_HAS_OVERDUE_ITEMS` | Member đang có sách quá hạn |
| `BOOK_COPY_NOT_AVAILABLE` | Copy không available hoặc book đã bị xóa |
| `ACTIVE_BORROW_NOT_FOUND` | Trả sách nhưng không tìm thấy borrow đang mở |
| `BORROW_NOT_RENEWABLE` | Borrow không đủ điều kiện gia hạn |
| `RENEWAL_BLOCKED_BY_HOLD` | Có reservation active trên cùng book |
| `IDEMPOTENCY_KEY_REQUIRED` | API yêu cầu idempotency nhưng thiếu key |
| `REQUEST_ALREADY_PROCESSING` | Request cùng key đang được xử lý |
| `IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST` | Dùng lại key cho request khác |

## 14. Logging policy

Service layer chỉ log các business event thành công quan trọng:

```text
BORROW_BOOK
RETURN_BOOK
RENEW_BORROW
```

Format log theo key-value:

```text
eventType={} result={} memberId={} entityType=BORROW_RECORD entityId={} ...
```

Failure không log trùng trong service. Lỗi được xử lý bởi global exception handler theo error-handling policy chung của project.

## 15. Những policy chưa implement xong

Các phần sau đã có schema/enum/docs định hướng, nhưng chưa phải policy hoàn chỉnh trong code hiện tại:

```text
1. Hold/reservation API đầy đủ
2. Hold handoff khi checkin sách có người chờ
3. Checkout từ hold đang READY_FOR_PICKUP
4. Overdue scheduled job tự chuyển BORROWED -> OVERDUE
5. Email thông báo due soon/overdue/hold ready
6. Fine payment API
7. Waive fine API
8. Lost/damaged replacement fee flow
9. Auto-renewal kiểu Evergreen
10. Admin UI/API để chỉnh system_settings
11. Idempotency cleanup scheduled job
12. Audit log đầy đủ cho circulation
```

Khi trình bày đồ án, nên nói rõ:

```text
Core circulation policy đã implement:
checkout, checkin, renewal, fine calculation khi trả, idempotency, locking.

Extended circulation policy đang ở roadmap:
hold lifecycle, overdue job, notification, payment, lost/damaged fee.
```

## 16. Đánh giá hiện tại

Policy hiện tại đã đủ tốt cho một đồ án/intern backend vì có các điểm production-minded:

```text
1. Preview và checkout dùng chung rule, tránh drift logic.
2. Checkout/checkin/renewal có transaction.
3. Các write API quan trọng có Idempotency-Key.
4. Có pessimistic lock cho copy/borrow record.
5. Renewal có giới hạn số lần và chặn nếu có hold active.
6. maxRenewalsAtCheckout được snapshot để tránh policy cũ bị thay đổi.
7. Fine tính theo config effective date và lưu lại config đã dùng.
8. Service đã tách theo use case/policy/fine/setting để dễ mở rộng.
```

Điểm cần ưu tiên tiếp theo nếu muốn hoàn thiện circulation:

```text
1. Implement hold lifecycle.
2. Implement overdue scheduled job.
3. Implement fine payment/waive.
4. Thêm integration tests riêng cho checkout/checkin/renewal.
5. Thêm admin endpoint quản lý circulation settings.
```
