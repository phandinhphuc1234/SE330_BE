# Flow 5 - Hold / Reservation Queue

## Mục tiêu nghiệp vụ

Hold dùng khi sách đã hết bản available.

Rule của project:

```text
availableCopies > 0  -> không cho hold
availableCopies == 0 -> MEMBER được đặt hold
```

LIBRARIAN/ADMIN không đặt hold cho chính tài khoản staff/admin. Nếu staff hỗ trợ bạn đọc đặt hold, target member vẫn phải là `MEMBER`.

## Endpoint đề xuất

Member:

```http
POST /api/holds
GET /api/holds/my
DELETE /api/holds/{holdId}
```

Staff:

```http
POST /api/staff/holds/{holdId}/checkout
```

## Status mapping với schema hiện tại

Spec muốn:

```text
WAITING
READY_FOR_PICKUP
FULFILLED
CANCELLED
EXPIRED
```

DB hiện có:

```text
WAITING
NOTIFIED
FULFILLED
CANCELLED
EXPIRED
```

Khuyến nghị:

```text
Nếu muốn ít migration: NOTIFIED = READY_FOR_PICKUP.
Nếu muốn rõ nghiệp vụ hơn: thêm READY_FOR_PICKUP vào reservation status.
```

Với `BookCopyStatus`, nên dùng `ON_HOLD_SHELF` thay vì `RESERVED` nếu đã nâng cấp enum theo flow return/check-in.

## Create hold

Validate:

- Current user role `MEMBER`.
- Member `ACTIVE`.
- Book tồn tại, chưa deleted.
- `availableCopies == 0`.
- Member chưa có active hold cho book này.
- Member chưa vượt hold limit nếu có.

Queue position:

```sql
select coalesce(max(queue_position), 0) + 1
from reservations
where book_id = :bookId
  and status in ('WAITING', 'NOTIFIED')
```

Sau đó insert reservation.

## Race condition khi tạo hold

Hai user đặt hold cùng lúc có thể cùng lấy queue position giống nhau.

Cách vừa sức:

1. Giữ unique index `(book_id, queue_position)`.
2. Trong transaction, lock book row hoặc lock các reservation của book.
3. Nếu vẫn đụng unique constraint, trả lỗi retry.

Tốt hơn:

```java
@Lock(PESSIMISTIC_WRITE)
Optional<Book> findByIdAndDeletedAtIsNull(Long id);
```

Lock book trước khi tính queue position.

## Khi return sách

Return flow sẽ gọi:

```java
reservationService.assignReturnedCopyToNextHold(bookId, copyId)
```

Flow:

```text
1. Tìm hold WAITING đầu tiên theo queue_position/reserved_at.
2. Lock hold đó.
3. Update hold status = NOTIFIED.
4. Set notifiedAt = now.
5. Set expiresAt = now + pickup window.
6. Copy status = ON_HOLD_SHELF.
7. Publish HoldReady event sau commit.
```

Hiện schema reservations chưa có `assigned_copy_id`. Nếu không có field này, checkout hold sau đó khó biết copy nào đang giữ cho hold nào.

Nên thêm:

```sql
ALTER TABLE reservations
ADD COLUMN assigned_copy_id BIGINT REFERENCES book_copies(id);

CREATE INDEX idx_reservation_assigned_copy
    ON reservations(assigned_copy_id);
```

## Checkout hold tại quầy

Endpoint:

```http
POST /api/staff/holds/{holdId}/checkout
```

Validate:

- Actor `LIBRARIAN`/`ADMIN`.
- Hold status `NOTIFIED`.
- Hold có `assigned_copy_id`.
- Assigned copy status `ON_HOLD_SHELF`.
- Borrower của hold có role `MEMBER` và `ACTIVE`.
- Không checkout cho actor staff/admin.

Sau đó tạo borrow record như checkout thường:

```text
Reservation -> FULFILLED
BookCopy -> BORROWED
BorrowRecord -> BORROWED
availableCopies không đổi vì copy trước đó không AVAILABLE
```

## Expire hold

Scheduled job:

```text
Tìm reservations status NOTIFIED và expires_at < now
Set EXPIRED
Nếu còn WAITING tiếp theo: assign copy đó cho người tiếp theo
Nếu không còn WAITING: copy -> AVAILABLE và book.availableCopies +1
```

## Test nên có

- Không cho hold khi book còn available.
- Cho hold khi availableCopies = 0.
- Chỉ MEMBER tạo hold cho mình.
- Staff/admin account không tự hold.
- Hai hold cùng book có queue position khác nhau.
- Return sách assign đúng hold đầu tiên.
- Checkout hold chỉ cho đúng member trong hold.
- Expire hold chuyển lượt cho người kế tiếp.

## Nguồn nghiệp vụ/kỹ thuật

- Koha circulation manual: https://koha-community.org/manual/23.05/en/html/circulation.html
- Spring Data JPA Locking: https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html
- PostgreSQL partial unique indexes: https://www.postgresql.org/docs/current/indexes-partial.html
