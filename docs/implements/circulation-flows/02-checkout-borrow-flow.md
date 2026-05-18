# Flow 2 - Checkout / Mượn Sách

## Mục tiêu nghiệp vụ

Checkout là lúc một bản sách vật lý được giao cho bạn đọc.

Rule của project:

```text
Borrower phải là MEMBER.
LIBRARIAN/ADMIN không tự mượn bằng tài khoản staff/admin.
LIBRARIAN/ADMIN chỉ được checkout giúp MEMBER tại quầy.
```

Vì vậy API staff checkout có 2 actor:

```text
actor     = người thao tác API, lấy từ JWT, role LIBRARIAN/ADMIN
borrower  = bạn đọc đứng tên mượn, phải role MEMBER
```

## Endpoint đề xuất

Core staff flow:

```http
POST /api/staff/circulation/checkouts/preview
POST /api/staff/circulation/checkouts
```

Member self-checkout:

```text
Không triển khai trong giai đoạn đầu.
```

Lý do: thư viện truyền thống cần staff scan barcode vật lý. Nếu mở self-checkout online, user có thể mượn một copy mà họ chưa cầm trên tay.

## Request staff checkout

```json
{
  "memberId": 15,
  "itemBarcode": "BC-000101"
}
```

Nếu sau này muốn giống thư viện thật hơn, thêm `memberBarcode`. Hiện schema `members` chưa có barcode, nên dùng `memberId` trước là hợp lý.

## Validate nghiệp vụ

Validate borrower:

- Member tồn tại.
- `role = MEMBER`.
- `status = ACTIVE`.
- `membershipExpiresAt` chưa hết hạn nếu có.
- Số sách đang mượn chưa vượt `maxBorrowLimit`.
- Không có borrow overdue nếu policy block.
- Fine chưa trả không vượt ngưỡng nếu policy block.

Validate copy:

- Copy tồn tại và chưa soft delete.
- Copy status `AVAILABLE`.
- Book chưa soft delete.
- Copy không `DAMAGED`, `LOST`, `REMOVED`.
- Nếu đang hold shelf thì chỉ checkout qua hold flow, không checkout thường.

## Transaction và lock

Checkout là flow có rủi ro race condition:

```text
Hai thủ thư scan cùng một barcode gần như cùng lúc.
Nếu không lock, cả hai request có thể cùng thấy AVAILABLE và cùng tạo borrow.
```

Kỹ thuật nên dùng:

```java
@Transactional
@Lock(LockModeType.PESSIMISTIC_WRITE)
```

Repository gợi ý:

```java
public interface BookCopyRepository extends JpaRepository<BookCopy, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BookCopy> findByBarcodeIgnoreCaseAndDeletedAtIsNull(String barcode);
}
```

Spring Data JPA docs cho phép dùng `@Lock` trên query method. PostgreSQL `SELECT ... FOR UPDATE` sẽ lock row được chọn để chống concurrent update.

## DB constraint nên giữ

DB hiện đã có:

```sql
CREATE UNIQUE INDEX uq_active_borrow_copy
    ON borrow_records(book_copy_id)
    WHERE status = 'BORROWED';
```

Nên sửa index này cho đúng status mới nếu Java dùng `ACTIVE` thay vì `BORROWED`. Để ít sửa schema, khuyến nghị dùng enum Java khớp DB:

```text
BORROWED
RETURNED
OVERDUE
LOST
```

Với project này nên đặt tên Java là `BorrowStatus.BORROWED` thay vì `ACTIVE`, vì DB hiện đang dùng `BORROWED`.

## Counter book

Khi checkout thành công:

```text
book_copies.status: AVAILABLE -> BORROWED
books.available_copies: -1
books.total_copies: giữ nguyên
```

Không count lại toàn bộ `book_copies`. Dùng method delta như hiện tại:

```java
bookRepository.adjustCopyCounters(bookId, 0, -1);
```

## Idempotency

Checkout là `POST`, không idempotent tự nhiên. Nếu frontend timeout rồi retry, có thể tạo duplicate nếu không bảo vệ.

Nên yêu cầu:

```http
Idempotency-Key: uuid
```

Theo draft Idempotency-Key, key là giá trị client tạo ra để server nhận biết request retry. Key không được reuse với payload khác.

Với checkout, áp dụng thiết kế idempotency chuẩn hơn:

```text
scope = actor_id + http_method + normalized_path + idempotency_key
request_hash = SHA-256(method + normalized_path + canonical_body)
```

Không hash `Authorization` vì access token có thể refresh khác nhau nhưng hành động vẫn là retry cùng một request.

Schema chi tiết nằm ở `08-security-idempotency-audit-policy.md`. Điểm quan trọng là race condition phải được chặn bằng unique constraint ở DB, không chỉ check bằng code.

## Event/email

Không gửi email trong transaction checkout.

Flow đúng:

```text
Transaction tạo borrow thành công
Commit thành công
@TransactionalEventListener(AFTER_COMMIT)
@Async gửi checkout receipt email
```

Spring transaction-bound events phù hợp vì listener chỉ chạy sau khi transaction commit thành công.

## Audit log

Nên ghi:

```text
action = BORROW_BOOK
user_id = actorId
actor_role = LIBRARIAN/ADMIN
entity_type = BORROW_RECORD
entity_id = borrowId
metadata = { "borrowerId": 15, "bookCopyId": 101, "barcode": "BC-000101" }
```

Không dùng `borrowerId` làm actor vì người thao tác là staff.

## Test nên có

- Staff checkout cho member active thành công.
- Staff checkout cho account role `LIBRARIAN` bị từ chối với code `BORROWER_MUST_BE_MEMBER`.
- Checkout copy không `AVAILABLE` bị từ chối.
- Checkout vượt `maxBorrowLimit` bị từ chối.
- Hai request checkout cùng barcode chỉ một request thành công.
- Checkout thành công giảm `availableCopies` đúng 1.

## Nguồn kỹ thuật

- Spring Data JPA Locking: https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html
- Spring `@Transactional`: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- PostgreSQL row locking: https://www.postgresql.org/docs/current/sql-select.html
- Idempotency-Key draft: https://www.ietf.org/archive/id/draft-ietf-httpapi-idempotency-key-header-03.html
- Stripe Idempotent Requests: https://docs.stripe.com/api/idempotent_requests
- Spring transaction-bound events: https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
