# Tài liệu quy trình mượn sách và API cho hệ thống quản lý thư viện

## 0. Mục tiêu tài liệu

Tài liệu này mô tả quy trình mượn/trả sách của một hệ thống thư viện theo hướng gần với các hệ thống ILS thực tế như Koha, Evergreen, OCLC WorldShare và SirsiDynix/Symphony, nhưng được điều chỉnh vừa sức cho đồ án/backend project Spring Boot.

Trọng tâm của hệ thống không chỉ là CRUD sách, mà là **vòng đời lưu thông của từng bản sách vật lý**:

```text
Search → Availability → Checkout → Due Date → Renewal → Return → Fine → Hold Queue → Overdue/Lost → Report/Audit
```

Các mục tiêu chính:

```text
1. Mô tả flow ngoài đời của thư viện.
2. Mô tả flow trong hệ thống/backend.
3. Xác định API cần có.
4. Xác định các trạng thái nghiệp vụ quan trọng.
5. Làm rõ chính sách đặt giữ chỗ: chỉ cho hold khi tất cả bản copy đều không khả dụng.
6. Làm rõ notification: email xác nhận mượn, email nhắc sắp đến hạn, email hold ready.
7. Đưa ra thiết kế vừa đủ production-like: idempotency, audit log, scheduled jobs, notification logs.
```

---

# 1. Bức tranh ngoài đời: thư viện lớn vận hành mượn sách ra sao?

Trong thư viện thật, có ba nhóm vai trò chính:

```text
Member / Patron:
- Bạn đọc, người tìm kiếm, mượn, trả, gia hạn, đặt giữ chỗ sách.

Librarian / Staff:
- Thủ thư hoặc nhân viên circulation desk.
- Xử lý checkout, check-in, quản lý bản sách, xử lý tiền phạt, hỗ trợ hold.

Admin:
- Quản lý cấu hình hệ thống, chính sách mượn/trả, tài khoản nhân viên, báo cáo, audit log.
```

Một thư viện lớn không chỉ quản lý theo “đầu sách”, mà quản lý theo **bản sao vật lý**.

Ví dụ:

```text
Book / Bibliographic record:
  Clean Code

BookCopy / Item:
  COPY-001 | Barcode BC-000001 | AVAILABLE | Shelf A3
  COPY-002 | Barcode BC-000002 | BORROWED  | Due 2026-05-30
  COPY-003 | Barcode BC-000003 | DAMAGED   | Storage
```

Ngoài đời, khi mượn sách tại quầy, thủ thư thường scan:

```text
1. Thẻ bạn đọc hoặc barcode member.
2. Barcode của bản sách vật lý.
```

Tương ứng trong backend:

```text
Book                 = đầu sách
BookCopy             = bản sách vật lý có barcode, status, location
BorrowRecord         = giao dịch mượn/trả
Hold / Reservation   = đặt giữ chỗ khi sách hết
Fine / Fee           = tiền phạt hoặc khoản phí
CirculationPolicy    = luật mượn/trả/gia hạn/phạt
Notification         = email/thông báo
AuditLog             = lịch sử hành động nghiệp vụ quan trọng
```

---

# 2. Nguyên tắc thiết kế quan trọng của hệ thống

## 2.1. Không cho đặt giữ chỗ khi sách vẫn còn trên kệ

Hệ thống áp dụng chính sách tương tự tùy chọn **“If all unavailable”** trong Koha.

Nghĩa là:

```text
Nếu một đầu sách còn ít nhất một bản copy AVAILABLE:
→ Không cho đặt hold online.
→ Hệ thống chỉ hiển thị vị trí kệ.
→ Bạn đọc tự đến thư viện lấy sách và làm thủ tục checkout tại quầy.

Nếu tất cả bản copy đều không khả dụng:
→ Cho phép đặt hold.
→ Bạn đọc vào hàng chờ.
→ Khi có sách được trả, hệ thống giữ sách cho người đầu tiên trong queue.
```

Lý do chọn chính sách này:

```text
1. Dễ giải thích với giảng viên/nhà tuyển dụng.
2. Gần với thư viện không cung cấp dịch vụ staff đi lấy sách trên kệ để giữ cho user online.
3. Tránh phải xây thêm pull list, staff picking workflow, hold shelf preparation workflow.
4. Vẫn đủ thực tế vì hệ thống có hold queue khi sách thật sự hết.
```

## 2.2. Checkout chủ yếu diễn ra tại quầy hoặc qua staff flow

Với bản thiết kế này, khi sách còn `AVAILABLE`, user không “đặt mượn online” để giữ sách trước. User chỉ biết:

```text
Sách còn bản available.
Vị trí: Kệ A3, tầng 2.
Vui lòng đến thư viện lấy sách và checkout tại quầy.
```

API `POST /api/borrows` vẫn có thể hỗ trợ `MEMBER` hoặc `LIBRARIAN`, nhưng trong nghiệp vụ thực tế của đồ án nên mô tả rõ:

```text
- Member có thể mượn nếu hệ thống hỗ trợ self-checkout.
- Librarian có thể checkout giúp member tại quầy bằng barcode.
- Với flow truyền thống, staff checkout bằng barcode là flow chính.
```

---

# 3. Các trạng thái nghiệp vụ quan trọng

## 3.1. BookCopy status

Một bản sách vật lý nên có lifecycle:

```text
AVAILABLE        = đang ở trên kệ, có thể cho mượn ngay
ON_HOLD_SHELF    = đang được giữ tại quầy cho người đặt hold
BORROWED         = đang được mượn
OVERDUE          = đang được mượn nhưng đã quá hạn
LOST             = bị mất hoặc quá hạn quá lâu
DAMAGED          = bị hư hỏng
IN_REPAIR        = đang sửa chữa
REMOVED          = ngừng lưu hành
REFERENCE_ONLY   = chỉ đọc tại chỗ, không cho mượn
```

Trong đồ án, có thể dùng bộ tối giản:

```text
AVAILABLE
BORROWED
OVERDUE
ON_HOLD_SHELF
LOST
DAMAGED
REMOVED
```

## 3.2. BorrowRecord status

```text
ACTIVE           = đang mượn, chưa quá hạn
OVERDUE          = đã quá hạn
RETURNED         = đã trả
LOST             = quá hạn lâu, bị đánh dấu mất
CLAIMED_RETURNED = bạn đọc nói đã trả nhưng thư viện chưa xác nhận
CANCELLED        = giao dịch bị hủy bởi staff/admin
```

Bản vừa sức đồ án:

```text
ACTIVE
OVERDUE
RETURNED
LOST
```

## 3.3. Hold status

```text
WAITING           = đang chờ trong hàng đợi
READY_FOR_PICKUP  = đã có sách, chờ bạn đọc đến lấy
FULFILLED         = đã chuyển thành checkout/mượn sách
EXPIRED           = quá hạn đến lấy
CANCELLED         = bị hủy
```

## 3.4. Fine status

```text
UNPAID  = chưa thanh toán
PAID    = đã thanh toán
WAIVED  = được miễn bởi librarian/admin
VOIDED  = bị hủy do điều chỉnh nghiệp vụ
```

---

# 4. Flow 1 — Search catalogue và xem availability

## 4.1. Ngoài đời

```text
1. Bạn đọc tìm sách trên catalogue.
2. Hệ thống hiển thị sách có còn bản nào không.
3. Nếu còn bản AVAILABLE, hệ thống hiển thị vị trí kệ.
4. Nếu hết bản AVAILABLE, hệ thống cho phép đặt hold.
5. Bạn đọc đến thư viện lấy sách nếu còn bản trên kệ, hoặc đợi thông báo nếu đã đặt hold.
```

## 4.2. API tìm sách

```http
GET /api/books?keyword=clean%20code&categoryId=&author=&availableOnly=true&page=0&size=10
```

Backend xử lý:

```text
1. Search books bằng Specification/Criteria.
2. Count tổng số BookCopy.
3. Count số BookCopy có status AVAILABLE.
4. Tính canPlaceHold theo rule:
   canPlaceHold = availableCopies == 0
5. Trả về metadata availability.
```

Response khi còn sách:

```json
{
  "items": [
    {
      "bookId": 10,
      "title": "Clean Code",
      "authors": ["Robert C. Martin"],
      "availableCopies": 2,
      "totalCopies": 5,
      "canPlaceHold": false,
      "availabilityMessage": "Sách hiện còn trên kệ. Vui lòng đến thư viện lấy sách và checkout tại quầy."
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1
}
```

Response khi hết sách:

```json
{
  "items": [
    {
      "bookId": 10,
      "title": "Clean Code",
      "authors": ["Robert C. Martin"],
      "availableCopies": 0,
      "totalCopies": 5,
      "canPlaceHold": true,
      "queueLength": 2,
      "availabilityMessage": "Hiện không còn bản nào có sẵn. Bạn có thể đặt giữ chỗ."
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1
}
```

## 4.3. API xem chi tiết sách

```http
GET /api/books/{bookId}
```

Response:

```json
{
  "bookId": 10,
  "title": "Clean Code",
  "isbn": "9780132350884",
  "category": "Programming",
  "availableCopies": 1,
  "canPlaceHold": false,
  "copies": [
    {
      "copyId": 101,
      "barcode": "BC-000101",
      "status": "AVAILABLE",
      "location": "Shelf A3, Floor 2"
    },
    {
      "copyId": 102,
      "barcode": "BC-000102",
      "status": "BORROWED",
      "dueDate": "2026-05-25"
    }
  ]
}
```

---

# 5. Flow 2 — Checkout / Mượn sách

## 5.1. Ngoài đời tại quầy

```text
1. Bạn đọc đem sách đến quầy.
2. Thủ thư scan thẻ bạn đọc.
3. Thủ thư scan barcode bản sách.
4. Hệ thống kiểm tra quyền mượn:
   - tài khoản còn active không?
   - có bị khóa không?
   - có sách quá hạn không?
   - có tiền phạt vượt ngưỡng không?
   - đã mượn quá giới hạn chưa?
5. Hệ thống kiểm tra bản sách:
   - bản sách có tồn tại không?
   - status có phải AVAILABLE không?
   - có được phép lưu hành không?
   - có đang giữ cho người khác không?
6. Nếu hợp lệ, hệ thống tạo BorrowRecord.
7. BookCopy chuyển sang BORROWED.
8. Hệ thống tính dueDate theo policy.
9. Hệ thống gửi/in receipt.
```

## 5.2. API preview trước checkout

API này giúp staff biết có được checkout không trước khi commit.

```http
POST /api/staff/circulation/checkouts/preview
Authorization: Bearer librarian-token
Content-Type: application/json

{
  "memberBarcode": "MB-000015",
  "itemBarcode": "BC-000101"
}
```

Backend kiểm tra:

```text
1. Member tồn tại không?
2. Member ACTIVE không?
3. Member có overdue item không?
4. Member có unpaid fine vượt ngưỡng không?
5. Member đã mượn quá maxBorrowLimit chưa?
6. Copy tồn tại không?
7. Copy status AVAILABLE không?
8. Copy có circulate = true không?
9. Copy có đang ON_HOLD_SHELF cho người khác không?
10. Tính dueDate theo policy.
```

Response:

```json
{
  "allowed": true,
  "memberId": 15,
  "bookCopyId": 101,
  "loanPeriodDays": 14,
  "dueDate": "2026-05-30",
  "warnings": []
}
```

Nếu không được:

```json
{
  "allowed": false,
  "reasons": [
    {
      "code": "MEMBER_HAS_OVERDUE_ITEMS",
      "message": "Member has overdue items"
    }
  ]
}
```

## 5.3. API checkout bằng barcode cho staff

```http
POST /api/staff/circulation/checkouts
Authorization: Bearer librarian-token
Idempotency-Key: checkout-uuid-001
Content-Type: application/json

{
  "memberBarcode": "MB-000015",
  "itemBarcode": "BC-000101"
}
```

Backend xử lý:

```text
1. Lấy actor từ JWT.
2. Kiểm tra role LIBRARIAN hoặc ADMIN.
3. Kiểm tra Idempotency-Key.
4. Mở transaction.
5. Tìm member theo barcode.
6. Tìm book copy theo item barcode.
7. Lock BookCopy bằng PESSIMISTIC_WRITE để chống 2 request mượn cùng bản.
8. Validate member eligibility.
9. Validate item eligibility.
10. Resolve circulation policy.
11. Tạo BorrowRecord.
12. Update BookCopy status = BORROWED.
13. Nếu checkout từ hold READY_FOR_PICKUP, mark hold = FULFILLED.
14. Save audit log BORROW_BOOK.
15. Publish BOOK_BORROWED event để gửi checkout receipt email.
16. Save idempotency response.
17. Commit transaction.
```

Response:

```json
{
  "borrowId": 9001,
  "memberId": 15,
  "bookId": 10,
  "bookCopyId": 101,
  "borrowedAt": "2026-05-16T09:30:00",
  "dueDate": "2026-05-30",
  "status": "ACTIVE"
}
```

## 5.4. API checkout cho member/self-checkout nếu hệ thống hỗ trợ

Endpoint này là optional. Nếu đồ án chưa muốn hỗ trợ self-checkout, có thể bỏ.

```http
POST /api/borrows
Authorization: Bearer member-token
Idempotency-Key: member-checkout-uuid-001
Content-Type: application/json

{
  "bookCopyId": 101
}
```

Rule:

```text
- Member chỉ được checkout cho chính mình.
- Copy phải AVAILABLE.
- Nếu thư viện không hỗ trợ self-checkout, endpoint này không cần mở cho MEMBER.
```

---

# 6. Flow 3 — Return / Check-in / Trả sách

## 6.1. Ngoài đời

```text
1. Bạn đọc đem sách tới quầy hoặc bỏ vào book drop.
2. Thủ thư scan barcode bản sách.
3. Hệ thống tìm BorrowRecord đang mở.
4. Hệ thống kiểm tra có quá hạn không.
5. Nếu quá hạn, hệ thống tạo fine.
6. Nếu sách hư hỏng, staff ghi nhận damaged và có thể tạo damage fee.
7. Nếu có hold queue, sách không về kệ mà chuyển ON_HOLD_SHELF.
8. Nếu không có hold queue, sách chuyển AVAILABLE.
9. Hệ thống gửi return receipt.
```

## 6.2. API check-in bằng barcode cho staff

```http
POST /api/staff/circulation/checkins
Authorization: Bearer librarian-token
Idempotency-Key: checkin-uuid-001
Content-Type: application/json

{
  "itemBarcode": "BC-000101",
  "returnCondition": "GOOD",
  "returnLocationId": 1,
  "forgiveFine": false,
  "note": "Returned at front desk"
}
```

Backend xử lý:

```text
1. Tìm BookCopy theo barcode.
2. Tìm BorrowRecord đang ACTIVE/OVERDUE/LOST chưa đóng.
3. Tính returnedAt.
4. Tính overdueDays = max(0, returnedAt - dueDate).
5. Nếu overdueDays > 0:
   - tạo Fine theo policy.
   - hoặc waive nếu staff có quyền và request cho phép.
6. Nếu returnCondition = DAMAGED:
   - mark copy DAMAGED.
   - tạo damage fee nếu có.
7. Nếu có hold WAITING cho Book:
   - chọn hold đầu tiên.
   - copy status = ON_HOLD_SHELF.
   - hold status = READY_FOR_PICKUP.
   - gán copyId cho hold.
   - set pickupExpiredAt.
   - publish HOLD_READY event.
8. Nếu không có hold:
   - copy status = AVAILABLE.
9. BorrowRecord status = RETURNED.
10. Save audit log RETURN_BOOK.
11. Publish BOOK_RETURNED event.
12. Save idempotency response.
```

Response nếu không có hold:

```json
{
  "borrowId": 9001,
  "status": "RETURNED",
  "returnedAt": "2026-05-16T10:00:00",
  "overdueDays": 3,
  "fine": {
    "fineId": 3001,
    "amount": 15000,
    "status": "UNPAID"
  },
  "bookCopyStatus": "AVAILABLE"
}
```

Response nếu có hold:

```json
{
  "borrowId": 9001,
  "status": "RETURNED",
  "bookCopyStatus": "ON_HOLD_SHELF",
  "nextHoldId": 7001,
  "message": "Returned item is assigned to next hold"
}
```

## 6.3. API member trả sách

Nếu đồ án muốn cho member tự tạo request trả sách, cần phân biệt rõ với check-in thật tại quầy. Trong thư viện truyền thống, trả sách thường cần staff xác nhận.

Có thể thiết kế optional:

```http
PUT /api/borrows/{borrowId}/return-request
Authorization: Bearer member-token
```

Nghiệp vụ:

```text
Member báo muốn trả sách.
Hệ thống tạo RETURN_REQUESTED.
Staff vẫn phải check-in khi nhận sách vật lý.
```

Nếu không muốn phức tạp, bỏ endpoint này và chỉ cho staff check-in.

---

# 7. Flow 4 — Renewal / Gia hạn

## 7.1. Ngoài đời

Bạn đọc có thể tự gia hạn trong tài khoản online nếu policy cho phép, hoặc thủ thư gia hạn giúp tại quầy.

Điều kiện thường gặp:

```text
1. BorrowRecord đang ACTIVE.
2. Chưa RETURNED.
3. Chưa quá hạn, trừ khi policy cho phép renew overdue.
4. Chưa vượt maxRenewals.
5. Không có hold đang chờ cho đầu sách đó.
6. Member không bị block.
7. Item type cho phép renewal.
```

## 7.2. API renew

```http
PUT /api/borrows/{borrowId}/extend
Authorization: Bearer member-or-librarian-token
Idempotency-Key: renew-uuid-001
Content-Type: application/json

{
  "requestedDays": 7
}
```

Backend xử lý:

```text
1. Load BorrowRecord.
2. Check owner/role.
3. Check status ACTIVE.
4. Check not overdue hoặc policy allow.
5. Check renewCount < maxRenewals.
6. Check no active holds waiting for this book/item.
7. Tính dueDate mới.
8. Update dueDate.
9. renewCount += 1.
10. Save audit EXTEND_BORROW.
11. Publish BORROW_RENEWED event nếu cần gửi email.
12. Save idempotency response.
```

Response:

```json
{
  "borrowId": 9001,
  "oldDueDate": "2026-05-30",
  "newDueDate": "2026-06-06",
  "renewCount": 1,
  "maxRenewals": 1
}
```

Lỗi khi có hold:

```json
{
  "code": "RENEWAL_BLOCKED_BY_HOLD",
  "message": "This item cannot be renewed because another member has placed a hold"
}
```

---

# 8. Flow 5 — Hold / Reservation Queue

## 8.1. Chính sách hold của hệ thống

Hệ thống chỉ cho đặt hold khi:

```text
availableCopies == 0
```

Tức là:

```text
Còn ít nhất một bản AVAILABLE:
→ Không cho hold.

Tất cả bản copy đều không khả dụng:
→ Cho hold.
```

Copy được xem là không khả dụng nếu có status:

```text
BORROWED
OVERDUE
ON_HOLD_SHELF
LOST
DAMAGED
IN_REPAIR
REMOVED
REFERENCE_ONLY
```

## 8.2. Ngoài đời

```text
1. Bạn đọc thấy sách đang hết bản available.
2. Bạn đọc đặt hold.
3. Hệ thống đưa bạn đọc vào queue.
4. Khi một bản sách được trả:
   - hệ thống chọn người đầu tiên trong queue.
   - copy chuyển ON_HOLD_SHELF.
   - hold chuyển READY_FOR_PICKUP.
   - hệ thống gửi email thông báo đến lấy.
5. Bạn đọc đến thư viện trong thời hạn pickup.
6. Thủ thư checkout copy đó cho đúng bạn đọc.
7. Nếu bạn đọc không đến lấy, hold EXPIRED và chuyển lượt cho người tiếp theo.
```

## 8.3. API tạo hold

```http
POST /api/holds
Authorization: Bearer member-token
Content-Type: application/json

{
  "bookId": 10,
  "pickupLibraryId": 1
}
```

Backend xử lý:

```text
1. Member ACTIVE không?
2. Book tồn tại không?
3. availableCopies == 0 không?
4. Member có vượt hold limit không?
5. Member đã có active hold cho book này chưa?
6. Tạo Hold status WAITING.
7. Tính queuePosition.
8. Save audit CREATE_HOLD.
```

Nếu sách vẫn còn trên kệ:

```json
{
  "code": "BOOK_AVAILABLE_ON_SHELF",
  "message": "Sách hiện vẫn còn trên kệ. Chỉ được đặt giữ chỗ khi tất cả bản copy đều không khả dụng."
}
```

Response thành công:

```json
{
  "holdId": 7001,
  "bookId": 10,
  "status": "WAITING",
  "queuePosition": 3,
  "pickupLibraryId": 1
}
```

## 8.4. API xem holds của tôi

```http
GET /api/holds/my?status=WAITING&page=0&size=10
```

## 8.5. API hủy hold

```http
DELETE /api/holds/{holdId}
Authorization: Bearer member-token
```

Rule:

```text
Member chỉ hủy hold của chính mình.
Librarian/Admin có thể hủy hold theo nghiệp vụ.
```

## 8.6. Checkout hold tại quầy

```http
POST /api/staff/holds/{holdId}/checkout
Authorization: Bearer librarian-token
Idempotency-Key: hold-checkout-uuid-001
```

Backend xử lý:

```text
1. Hold phải READY_FOR_PICKUP.
2. Hold phải có assignedCopyId.
3. BookCopy phải ON_HOLD_SHELF.
4. Member phải đúng người được hold.
5. Tạo BorrowRecord.
6. Hold status = FULFILLED.
7. BookCopy status = BORROWED.
8. Save audit BORROW_FROM_HOLD.
9. Publish BOOK_BORROWED event.
```

---

# 9. Flow 6 — Overdue / Quá hạn

## 9.1. Ngoài đời

```text
1. Khi qua dueDate, item trở thành overdue.
2. Hệ thống gửi nhắc quá hạn hoặc nhắc sắp đến hạn theo lịch.
3. Nếu quá hạn lâu, item có thể bị đánh dấu LOST.
4. Hệ thống có thể tạo replacement fee/lost processing fee.
5. Khi bạn đọc trả sách, hệ thống tính fine hoặc xử lý lost item returned.
```

## 9.2. Scheduled job đánh dấu overdue

```java
@Scheduled(cron = "0 0 1 * * *")
public void markOverdueBorrows() {
    ...
}
```

Flow:

```text
1. Tìm BorrowRecord status ACTIVE và dueDate < today.
2. Update BorrowRecord status = OVERDUE.
3. Update BookCopy status = OVERDUE hoặc giữ BORROWED tùy thiết kế.
4. Publish OVERDUE_MARKED event nếu cần.
5. Save audit/system log.
6. Save job_execution_logs.
```

## 9.3. API xem báo cáo quá hạn

```http
GET /api/reports/overdue
Authorization: Bearer librarian-or-admin-token
```

Response:

```json
{
  "items": [
    {
      "borrowId": 9001,
      "memberId": 15,
      "bookTitle": "Clean Code",
      "dueDate": "2026-05-10",
      "daysLate": 6,
      "estimatedFine": 30000
    }
  ]
}
```

---

# 10. Email Notification Policy

Hệ thống có ba nhóm email chính:

```text
1. Checkout receipt email
2. Due soon reminder email
3. Hold ready for pickup email
```

Ba loại email này không nên bị lẫn với nhau.

---

## 10.1. Checkout Receipt Email

Email xác nhận mượn sách được gửi sau khi checkout thành công.

Flow:

```text
Member/Librarian checkout sách
→ BorrowRecord được tạo
→ BookCopy chuyển BORROWED
→ Transaction commit thành công
→ Publish BOOK_BORROWED event
→ Async listener gửi email xác nhận mượn
```

Nội dung email:

```text
Tên sách
Mã bản copy
Ngày mượn
Ngày đến hạn trả
Thông tin thư viện/quầy mượn
```

Lưu ý:

```text
Nếu gửi email lỗi, nghiệp vụ mượn sách không bị rollback.
Email failure được ghi vào notification_logs hoặc application log.
```

---

## 10.2. Due Soon Reminder Email

Email nhắc sắp đến hạn trả được gửi bởi scheduled job chạy mỗi ngày. Nó không sinh ra ngay khi user mượn sách.

Scheduled job:

```java
@Scheduled(cron = "0 0 7 * * *")
public void sendDueSoonReminderEmails() {
    ...
}
```

Điều kiện gửi reminder:

```text
BorrowRecord.status = ACTIVE
BorrowRecord.dueDate = today + 2 days
Chưa từng gửi DUE_SOON_REMINDER cho borrowId này
Member còn email hợp lệ
```

Không gửi cho:

```text
RETURNED
OVERDUE
LOST
CANCELLED
```

Flow:

```text
Job chạy lúc 7h sáng
→ Tính targetDate = today + 2 days
→ Tìm BorrowRecord ACTIVE có dueDate = targetDate
→ Kiểm tra notification_logs để tránh gửi trùng
→ Gửi email reminder
→ Ghi notification_logs
→ Ghi job_execution_logs
```

Gợi ý bảng `notification_logs`:

```sql
CREATE TABLE notification_logs (
    id BIGSERIAL PRIMARY KEY,
    notification_type VARCHAR(100) NOT NULL,
    target_type VARCHAR(100) NOT NULL,
    target_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    channel VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    sent_at TIMESTAMP,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_notification_once
        UNIQUE (notification_type, target_type, target_id, channel)
);
```

Ví dụ:

```text
notification_type = DUE_SOON_REMINDER
target_type = BORROW_RECORD
target_id = 9001
member_id = 15
channel = EMAIL
status = SENT
```

---

## 10.3. Hold Ready For Pickup Email

Email này được gửi khi một copy được trả và được gán cho người đầu tiên trong hold queue.

Flow:

```text
BookCopy được trả
→ Hệ thống kiểm tra hold queue của Book
→ Nếu có Hold WAITING
→ BookCopy chuyển ON_HOLD_SHELF
→ Hold đầu tiên chuyển READY_FOR_PICKUP
→ Gửi email thông báo đến lấy sách
```

Nội dung email:

```text
Tên sách
Pickup location
Thời gian bắt đầu giữ sách
Hạn cuối đến lấy
```

Nên có field:

```text
readyAt
pickupExpiredAt
```

Nếu bạn đọc không đến lấy đúng hạn, scheduled job sẽ expire hold.

---

# 11. Scheduled Jobs

## 11.1. markOverdueBorrows

```text
Mục tiêu:
- Chuyển BorrowRecord ACTIVE quá dueDate sang OVERDUE.

Chạy:
- Mỗi ngày lúc 1h sáng.

Dữ liệu xử lý:
- BorrowRecord.status = ACTIVE
- dueDate < today
```

## 11.2. sendDueSoonReminderEmails

```text
Mục tiêu:
- Gửi email nhắc sắp đến hạn trả trước 2 ngày.

Chạy:
- Mỗi ngày lúc 7h sáng.

Dữ liệu xử lý:
- BorrowRecord.status = ACTIVE
- dueDate = today + 2 days
- Chưa có notification log DUE_SOON_REMINDER
```

## 11.3. expireReadyHolds

```text
Mục tiêu:
- Expire các hold READY_FOR_PICKUP quá hạn đến lấy.
- Nếu còn người chờ tiếp theo, gán copy cho người đó.
- Nếu không còn ai chờ, copy chuyển AVAILABLE.
```

Flow:

```text
Scheduled job chạy định kỳ
→ Tìm Hold READY_FOR_PICKUP có pickupExpiredAt < now
→ Chuyển Hold sang EXPIRED
→ Nếu vẫn còn người chờ tiếp theo
   → Gán BookCopy cho người tiếp theo
   → Gửi Hold Ready email mới
→ Nếu không còn người chờ
   → BookCopy chuyển AVAILABLE
```

## 11.4. cleanupIdempotencyKeys

```text
Mục tiêu:
- Xóa idempotency records cũ sau 24–48h.
- Tránh bảng idempotency_records tăng vô hạn.
```

---

# 12. Job Execution Log

Các scheduled job quan trọng cần ghi lịch sử chạy để dễ vận hành và debug.

Áp dụng cho:

```text
sendDueSoonReminderEmails
markOverdueBorrows
expireReadyHolds
cleanupIdempotencyKeys
```

Gợi ý schema:

```sql
CREATE TABLE job_execution_logs (
    id BIGSERIAL PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    status VARCHAR(30) NOT NULL,
    total_processed INT DEFAULT 0,
    success_count INT DEFAULT 0,
    failed_count INT DEFAULT 0,
    error_message TEXT
);
```

Ví dụ:

```text
job_name = SEND_DUE_SOON_REMINDER
status = COMPLETED
total_processed = 120
success_count = 118
failed_count = 2
```

Job log giúp trả lời:

```text
Job có chạy không?
Chạy lúc nào?
Xử lý bao nhiêu record?
Gửi email thành công/thất bại bao nhiêu?
Lỗi gì?
```

---

# 13. Fine / Fee / Tiền phạt

## 13.1. Các loại fine/fee

```text
OVERDUE_FINE        = phạt quá hạn
LOST_ITEM_FEE       = phí mất sách
DAMAGE_FEE          = phí hư hỏng
MANUAL_ADJUSTMENT   = điều chỉnh thủ công
```

## 13.2. API xem tiền phạt của tôi

```http
GET /api/fines/my?status=UNPAID&page=0&size=10
Authorization: Bearer member-token
```

## 13.3. API thanh toán tiền phạt giả lập

```http
POST /api/fines/{fineId}/pay
Authorization: Bearer member-or-librarian-token
Idempotency-Key: fine-pay-uuid-001
Content-Type: application/json

{
  "paymentMethod": "CASH"
}
```

Backend xử lý:

```text
1. Fine tồn tại không?
2. Fine thuộc member hiện tại hoặc staff đang xử lý không?
3. Fine status = UNPAID không?
4. Tạo Payment record.
5. Fine status = PAID.
6. Save audit PAY_FINE.
7. Save idempotency response.
```

## 13.4. API miễn phạt

```http
PUT /api/fines/{fineId}/waive
Authorization: Bearer librarian-or-admin-token
Content-Type: application/json

{
  "reason": "Library closure period"
}
```

Response:

```json
{
  "fineId": 3001,
  "oldStatus": "UNPAID",
  "newStatus": "WAIVED",
  "waivedBy": 2,
  "reason": "Library closure period"
}
```

---

# 14. Lost / Damaged / Claims Returned

Phần này là nâng cao, có thể làm sau.

## 14.1. Lost item

```text
1. Borrow quá hạn quá lâu.
2. Scheduled job mark LOST.
3. Tạo LOST_ITEM_FEE.
4. Nếu item trả lại sau đó:
   - tùy policy, void/waive replacement fee.
   - close circulation record.
```

API mark lost thủ công:

```http
PUT /api/borrows/{borrowId}/mark-lost
Authorization: Bearer librarian-or-admin-token
Content-Type: application/json

{
  "reason": "Overdue more than 30 days"
}
```

## 14.2. Damaged on return

Trong API check-in:

```json
{
  "itemBarcode": "BC-000101",
  "returnCondition": "DAMAGED",
  "damageNote": "Water damage"
}
```

Backend:

```text
1. Return borrow.
2. Copy status = DAMAGED.
3. Fine/Fee type = DAMAGE_FEE.
4. Staff review required.
```

## 14.3. Claims returned

```http
PUT /api/borrows/{borrowId}/claim-returned
Authorization: Bearer member-or-librarian-token
Content-Type: application/json

{
  "claimedReturnDate": "2026-05-10",
  "note": "Returned via book drop"
}
```

Status:

```text
CLAIMED_RETURNED
```

---

# 15. Member Account / My Account

## 15.1. Xem sách đang mượn

```http
GET /api/borrows/my
Authorization: Bearer member-token
```

Response:

```json
{
  "items": [
    {
      "borrowId": 9001,
      "bookTitle": "Clean Code",
      "bookCopyBarcode": "BC-000101",
      "borrowedAt": "2026-05-16",
      "dueDate": "2026-05-30",
      "status": "ACTIVE",
      "renewable": true,
      "renewCount": 0
    }
  ]
}
```

## 15.2. Lịch sử mượn

```http
GET /api/borrows/my/history?page=0&size=10&status=&fromDate=&toDate=
Authorization: Bearer member-token
```

## 15.3. Holds của tôi

```http
GET /api/holds/my
Authorization: Bearer member-token
```

## 15.4. Fines của tôi

```http
GET /api/fines/my
Authorization: Bearer member-token
```

## 15.5. Gia hạn tất cả sách có thể gia hạn

```http
POST /api/borrows/my/renew-all
Authorization: Bearer member-token
Idempotency-Key: renew-all-uuid-001
```

---

# 16. Staff Circulation Desk

## 16.1. Tìm bạn đọc

```http
GET /api/staff/members/search?keyword=phuc
Authorization: Bearer librarian-token
```

## 16.2. Xem circulation summary của bạn đọc

```http
GET /api/staff/members/{memberId}/circulation-summary
Authorization: Bearer librarian-token
```

Response:

```json
{
  "memberId": 15,
  "fullName": "Nguyen Van A",
  "status": "ACTIVE",
  "activeBorrows": 3,
  "overdueCount": 1,
  "unpaidFineAmount": 20000,
  "activeHolds": 2,
  "blocks": [
    {
      "code": "HAS_OVERDUE_ITEMS",
      "message": "Member has overdue items"
    }
  ]
}
```

## 16.3. Staff checkout bằng barcode

```http
POST /api/staff/circulation/checkouts
Authorization: Bearer librarian-token
Idempotency-Key: staff-checkout-uuid

{
  "memberBarcode": "MB-000015",
  "itemBarcode": "BC-000101"
}
```

## 16.4. Staff check-in bằng barcode

```http
POST /api/staff/circulation/checkins
Authorization: Bearer librarian-token
Idempotency-Key: staff-checkin-uuid

{
  "itemBarcode": "BC-000101",
  "returnCondition": "GOOD"
}
```

---

# 17. Admin Circulation Policy

Thư viện lớn không nên hardcode tất cả rule. Nhưng với đồ án, có thể hardcode giai đoạn đầu và nâng cấp sau.

Các policy có thể cấu hình:

```text
patronType
itemType
loanPeriodDays
maxBorrowLimit
maxRenewals
finePerDay
gracePeriodDays
lostAfterDays
allowHolds
allowRenewalIfOverdue
pickupExpireHours
```

## API policy

```http
GET /api/admin/circulation-policies
Authorization: Bearer admin-token
```

```http
POST /api/admin/circulation-policies
Authorization: Bearer admin-token
Content-Type: application/json

{
  "patronType": "MEMBER",
  "itemType": "BOOK",
  "loanPeriodDays": 14,
  "maxBorrowLimit": 3,
  "maxRenewals": 1,
  "finePerDay": 5000,
  "gracePeriodDays": 0,
  "lostAfterDays": 30,
  "allowHolds": true,
  "allowRenewalIfOverdue": false,
  "pickupExpireHours": 48
}
```

---

# 18. Idempotency Key Policy

Idempotency key dùng để chống duplicate request cho các API thay đổi trạng thái.

## 18.1. API nên yêu cầu Idempotency-Key

```text
POST /api/staff/circulation/checkouts
POST /api/staff/circulation/checkins
PUT  /api/borrows/{id}/extend
POST /api/fines/{id}/pay
POST /api/staff/holds/{holdId}/checkout
POST /api/books/import
```

## 18.2. Không cần Idempotency-Key cho GET

```text
GET /api/books
GET /api/borrows/my
GET /api/reports/overdue
```

## 18.3. Schema gợi ý

```sql
CREATE TABLE idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    response_code INT,
    response_body TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,

    CONSTRAINT uk_idempotency_member_key
        UNIQUE (member_id, idempotency_key)
);
```

## 18.4. Flow xử lý

```text
1. Client gửi Idempotency-Key.
2. Backend tính requestHash.
3. Nếu key chưa tồn tại:
   - tạo record PROCESSING.
   - xử lý nghiệp vụ.
   - lưu response.
   - chuyển COMPLETED.
4. Nếu key đã COMPLETED và requestHash giống:
   - trả lại response cũ.
5. Nếu key đã COMPLETED nhưng requestHash khác:
   - trả 409 Conflict.
6. Nếu key đang PROCESSING:
   - trả 409 Conflict hoặc 425 Too Early.
```

---

# 19. Audit Log Policy

Audit log dùng để truy vết hành động nghiệp vụ quan trọng.

## 19.1. Audit log trả lời các câu hỏi

```text
Ai thực hiện hành động?
Role tại thời điểm thao tác là gì?
Hành động là gì?
Tác động lên entity nào?
Entity ID bao nhiêu?
Có metadata bổ sung không?
Request/API nào sinh ra audit log này?
IP và user agent nào?
Xảy ra lúc nào?
```

## 19.2. Các action nên audit

```text
REGISTER
LOGIN
LOGOUT
VERIFY_EMAIL

BORROW_BOOK
RETURN_BOOK
EXTEND_BORROW
BORROW_FROM_HOLD

CREATE_HOLD
CANCEL_HOLD
EXPIRE_HOLD
HOLD_READY_FOR_PICKUP

CREATE_FINE
PAY_FINE
WAIVE_FINE

CREATE_BOOK
UPDATE_BOOK
IMPORT_BOOK_CSV

MARK_BORROW_OVERDUE
MARK_BORROW_LOST
```

## 19.3. Schema gợi ý

```sql
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT,
    actor_role VARCHAR(50),
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(100),
    target_id VARCHAR(100),
    metadata TEXT,
    trace_id VARCHAR(100),
    method VARCHAR(20),
    path VARCHAR(255),
    ip_address VARCHAR(100),
    user_agent TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Bản này phù hợp nhất nếu chỉ ghi thao tác thành công.

Nếu muốn ghi cả thao tác thất bại, thêm:

```sql
ALTER TABLE audit_logs
ADD COLUMN result VARCHAR(30),
ADD COLUMN error_code VARCHAR(100),
ADD COLUMN error_message TEXT;
```

---

# 20. Bộ API tổng hợp

## 20.1. Public / Member API

```http
GET    /api/books
GET    /api/books/{bookId}
GET    /api/borrows/my
GET    /api/borrows/my/history
PUT    /api/borrows/{id}/extend
POST   /api/holds
GET    /api/holds/my
DELETE /api/holds/{id}
GET    /api/fines/my
POST   /api/fines/{id}/pay
```

Optional nếu hỗ trợ self-checkout:

```http
POST /api/borrows
```

## 20.2. Librarian API

```http
GET    /api/staff/members/search
GET    /api/staff/members/{id}/circulation-summary
POST   /api/staff/circulation/checkouts/preview
POST   /api/staff/circulation/checkouts
POST   /api/staff/circulation/checkins
POST   /api/staff/holds/{holdId}/checkout
GET    /api/borrows?status=&memberId=&bookId=&fromDate=&toDate=
PUT    /api/borrows/{id}/extend
PUT    /api/borrows/{id}/mark-lost
PUT    /api/fines/{id}/waive
POST   /api/books/import
GET    /api/reports/overdue
GET    /api/reports/top-books
GET    /api/admin/notification-logs?type=&status=&memberId=&fromDate=&toDate=
```

## 20.3. Admin API

```http
POST   /api/admin/books
PATCH  /api/admin/books/{id}
POST   /api/admin/book-copies
PATCH  /api/admin/book-copies/{id}
GET    /api/admin/circulation-policies
POST   /api/admin/circulation-policies
PATCH  /api/admin/circulation-policies/{id}
GET    /api/reports/stats?month=&year=
GET    /api/reports/fines?fromDate=&toDate=
GET    /api/audit-logs?action=&actorId=&targetType=&fromDate=&toDate=
GET    /api/admin/job-executions?jobName=&fromDate=&toDate=
```

## 20.4. System/Internal jobs

```text
@Scheduled markOverdueBorrows()
@Scheduled sendDueSoonReminderEmails()
@Scheduled expireReadyHolds()
@Scheduled cleanupIdempotencyKeys()

@Async sendCheckoutReceipt()
@Async sendReturnReceipt()
@Async sendHoldReadyNotification()
```

---

# 21. Database entities tương ứng

```text
members
roles
books
authors
categories
book_authors
book_copies
borrow_records
holds
fines
payments
circulation_policies
notification_logs
audit_logs
idempotency_records
job_execution_logs
```

Quan hệ chính:

```text
Book 1-N BookCopy
Book N-N Author
Member 1-N BorrowRecord
BookCopy 1-N BorrowRecord
Member 1-N Hold
Book 1-N Hold
BorrowRecord 1-N Fine
Fine 1-N Payment
```

---

# 22. Flow tổng thể từ đầu đến cuối

```text
1. Admin/Librarian nhập sách và book copies.
2. Member search catalogue.
3. Nếu sách còn AVAILABLE:
   - hệ thống hiển thị vị trí kệ.
   - không cho hold online.
   - member đến thư viện lấy sách và checkout tại quầy.
4. Nếu sách hết AVAILABLE:
   - member đặt hold.
   - hold vào WAITING queue.
5. Staff checkout sách bằng barcode.
6. System tạo BorrowRecord và tính dueDate.
7. System gửi checkout receipt email sau commit.
8. Trước hạn 2 ngày, scheduled job gửi due soon reminder.
9. Nếu quá dueDate, scheduled job mark OVERDUE.
10. Member renew nếu policy cho phép.
11. Staff check-in khi sách được trả.
12. System tính fine nếu quá hạn.
13. Nếu có hold queue:
    - copy chuyển ON_HOLD_SHELF.
    - hold đầu tiên chuyển READY_FOR_PICKUP.
    - gửi hold ready email.
14. Nếu không có hold queue:
    - copy chuyển AVAILABLE.
15. Nếu hold ready quá hạn:
    - expireReadyHolds job chuyển EXPIRED.
    - gán copy cho người tiếp theo hoặc chuyển AVAILABLE.
16. Nếu quá hạn lâu:
    - system mark LOST.
    - tạo lost fee nếu cần.
17. Member trả tiền phạt hoặc staff waive.
18. Admin xem report, audit log, notification log, job execution log.
```

---

# 23. Mức triển khai khuyến nghị cho đồ án

## Core bắt buộc

```text
Book / BookCopy
Borrow / Return
Renew
My checkouts/history
Librarian search borrows
Overdue report
Fine calculation
```

## Production-like nên có

```text
Idempotency-Key cho checkout/check-in/pay fine
Audit log
Scheduled overdue marking
Due soon reminder email
Notification logs chống gửi trùng
Job execution logs
CSV import sách có validate từng dòng
Structured logging + traceId
Testcontainers test borrow edge cases
```

## Nâng cao làm sau

```text
Hold queue đầy đủ
Circulation policy table
Lost item processing
Claimed returned
Outbox pattern cho notification/audit
```

---

# 24. Kết luận thiết kế

Hệ thống thư viện không nên chỉ xoay quanh CRUD sách. Thiết kế nên xoay quanh **circulation lifecycle**:

```text
Search → Availability → Checkout → Due Date → Renewal → Return → Fine → Hold Queue → Overdue/Lost → Report/Audit
```

API nên phản ánh đúng các vùng nghiệp vụ:

```text
/api/books                         catalogue
/api/book-copies                   physical inventory
/api/staff/circulation/checkouts   checkout tại quầy
/api/staff/circulation/checkins    check-in/trả sách tại quầy
/api/borrows                       circulation transaction
/api/holds                         reservation queue
/api/fines                         fees/billing
/api/reports                       librarian/admin insight
/api/audit-logs                    traceability
/api/admin/circulation-policies    policy configuration
```

Điểm quan trọng nhất của bản thiết kế này:

```text
Sách còn trên kệ → không cho hold online, chỉ hiển thị vị trí.
Sách hết available → cho hold, vào queue.
Khi sách được trả → nếu có queue, copy chuyển ON_HOLD_SHELF và gửi email đến lấy.
Due reminder → scheduled job, không phải async event ngay lúc mượn.
Checkout receipt → async event sau checkout thành công.
Audit log + idempotency + scheduled jobs → giúp hệ thống giống backend production-like hơn.
```

