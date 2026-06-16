# Spec 10: Free Ebook Borrow And Ebook Query

## Mục tiêu

Spec này xử lý flow ebook miễn phí.

Với ebook `FREE`, member không đi qua payment/VNPAY. Khi member bấm borrow, backend kiểm tra license và tạo `ebook_loans ACTIVE` ngay nếu hợp lệ.

Với ebook `PAID`, member vẫn đi qua flow payment ở spec 2-4.

```text
FREE ebook  -> borrow API tạo ebook_loan trực tiếp
PAID ebook  -> create payment -> VNPAY IPN -> tạo ebook_loan
```

## Trạng thái hiện tại

Đã có API query metadata ebook cho frontend:

```http
GET /api/books/{bookId}/ebook
```

API này public và trả metadata an toàn để render nút đọc/mượn/thanh toán.

Response hiện có:

```json
{
  "success": true,
  "message": "Lấy thông tin ebook thành công",
  "data": {
    "bookEbookId": 1001,
    "bookId": 501,
    "available": true,
    "status": "ACTIVE",
    "format": "pdf",
    "sizeBytes": 1234567,
    "maxConcurrentLoans": 5,
    "loanDurationDays": 14,
    "accessType": "FREE",
    "requiresPayment": false,
    "accessFee": 0,
    "currency": "VND",
    "accessDurationDays": 14,
    "updatedAt": "2026-06-14T10:00:00Z"
  }
}
```

Frontend rule:

```text
requiresPayment = false -> show Borrow/Read free button
requiresPayment = true  -> show Pay/Rent button
```

Đã có API staff/admin chỉnh policy/giá ebook:

```http
PATCH /api/books/{bookId}/ebooks/{bookEbookId}
```

Ví dụ set free:

```json
{
  "accessType": "FREE",
  "accessFee": 0
}
```

Ví dụ set paid:

```json
{
  "accessType": "PAID",
  "accessFee": 25000,
  "currency": "VND",
  "accessDurationDays": 14
}
```

## API cần thêm

### Borrow free ebook

```http
POST /api/ebooks/{bookId}/loans
Authorization: Bearer <accessToken>
```

Request body:

```json
{}
```

Backend resolve ebook đang ACTIVE của `bookId`.

Response success:

```json
{
  "success": true,
  "message": "Mượn ebook thành công",
  "data": {
    "loanId": 3001,
    "memberId": 21,
    "bookId": 501,
    "bookEbookId": 1001,
    "paymentId": null,
    "status": "ACTIVE",
    "borrowedAt": "2026-06-14T10:00:00Z",
    "expiredAt": "2026-06-28T10:00:00Z"
  }
}
```

## Business rules

```text
Endpoint chỉ xử lý ebook FREE.
Nếu ebook PAID thì không tạo loan, trả lỗi EBOOK_REQUIRES_PAYMENT hoặc EBOOK_DOES_NOT_REQUIRE_PAYMENT tùy tên code thống nhất lại.
Nếu ebook không ACTIVE thì trả EBOOK_NOT_AVAILABLE.
Nếu member đã có active loan còn hạn cho ebook này thì trả loan hiện có hoặc lỗi EBOOK_ALREADY_BORROWED.
MVP khuyến nghị trả loan hiện có để borrow FREE idempotent hơn.
Nếu active loan count >= max_concurrent_loans thì trả EBOOK_LICENSE_NOT_AVAILABLE.
Nếu còn license thì tạo ebook_loans ACTIVE.
expired_at = borrowed_at + access_duration_days.
Nếu access_duration_days null thì fallback loan_duration_days.
payment_id = null vì không có payment.
maxBorrowLimit áp dụng trên tổng mọi media đang mượn:
  active physical borrows + active ebook loans còn hạn < member.maxBorrowLimit.
Member được phép có 2 media khác nhau của cùng một đầu sách:
  ví dụ mượn bản vật lý của book A và ebook của book A cùng lúc vẫn hợp lệ nếu chưa vượt maxBorrowLimit.
```

## Concurrency rule

Khi tạo loan FREE cũng phải dùng cùng rule license như payment success:

```text
1. Resolve active book_ebook.
2. Lock book_ebooks row bằng SELECT FOR UPDATE.
3. Check member active loan.
4. Check tổng hạn mức media của member.
5. Count active loans còn hạn của ebook.
6. Nếu còn slot thì insert ebook_loans.
```

Không được count license trước khi lock.

Luồng tạo loan nên lock member trước rồi lock book_ebooks để các flow physical/ebook dùng cùng thứ tự lock:

```text
lock member
lock book_ebooks
check active loan / borrow limit / license
insert ebook_loans
```

Repository cần có:

```java
Optional<BookEbook> findFirstByBookIdAndStatusOrderByIdDesc(Long bookId, BookEbookStatus status);
Optional<BookEbook> findLockedById(Long id);
Optional<EbookLoan> find active loan by memberId/bookEbookId/status/expiredAtAfter;
long count active loan by bookEbookId/status/expiredAtAfter;
```

## Service đề xuất

```text
EbookLoanService
  borrowFreeEbook(memberId, bookId)
  getCurrentLoan(memberId, bookEbookId)
```

Không nên đặt logic borrow FREE trong `PaymentService`, vì free ebook không phải payment.

Không nên để frontend tự tạo `ebook_loans`; backend là nơi kiểm soát license.

## Controller đề xuất

```text
EbookLoanController
  POST /api/ebooks/{bookId}/loans
```

Security:

```text
POST /api/ebooks/{bookId}/loans -> authenticated member
```

## Error codes cần thêm/xem lại

Hiện đã có:

```text
EBOOK_NOT_FOUND
EBOOK_NOT_AVAILABLE
EBOOK_ALREADY_BORROWED
EBOOK_LICENSE_NOT_AVAILABLE
```

Nên thêm code rõ hơn:

```text
EBOOK_REQUIRES_PAYMENT
```

Dùng khi user gọi borrow free API trên ebook `PAID`.

Không nên dùng `EBOOK_DOES_NOT_REQUIRE_PAYMENT` cho case này vì code đó đang dùng ở chiều ngược lại: user tạo payment cho ebook FREE.

## Frontend flow

Trên trang chi tiết sách:

```text
GET /api/books/{bookId}/ebook
```

Nếu:

```text
requiresPayment = false
```

Frontend bấm:

```http
POST /api/ebooks/{bookId}/loans
```

Nếu success:

```text
Show "Mượn ebook thành công"
Enable "Đọc ebook"
```

Nếu:

```text
requiresPayment = true
```

Frontend gọi:

```http
POST /api/payments
```

Sau đó redirect VNPAY và polling payment như spec 09.

## Acceptance criteria

```text
FREE ebook borrow tạo ebook_loans ACTIVE.
FREE ebook borrow không tạo payment_transactions.
PAID ebook gọi borrow-free API không tạo loan.
Member borrow lại khi đã có ACTIVE loan không tạo loan trùng.
Tổng active physical borrows + active ebook loans không vượt member.maxBorrowLimit.
Member vẫn được mượn physical và ebook của cùng một book nếu tổng media chưa vượt maxBorrowLimit.
Hết license trả EBOOK_LICENSE_NOT_AVAILABLE.
Hai request borrow FREE cạnh tranh license cuối cùng không vượt max_concurrent_loans.
expired_at dùng access_duration_days nếu có.
GET /api/books/{bookId}/ebook đủ dữ liệu để frontend quyết định Borrow hay Pay.
```

## Test cases

```text
GET ebook FREE trả requiresPayment=false.
GET ebook PAID trả requiresPayment=true và accessFee.
POST borrow FREE thành công tạo loan ACTIVE.
POST borrow FREE lần 2 không tạo loan trùng.
POST borrow PAID trả EBOOK_REQUIRES_PAYMENT.
POST borrow khi hết license trả EBOOK_LICENSE_NOT_AVAILABLE.
POST borrow khi tổng media đạt maxBorrowLimit trả BORROW_LIMIT_EXCEEDED.
POST borrow khi ebook INACTIVE trả EBOOK_NOT_AVAILABLE.
Concurrent borrow không vượt max_concurrent_loans.
```
