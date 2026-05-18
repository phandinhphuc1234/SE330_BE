# Flow 4 - Renewal / Gia Hạn

## Mục tiêu nghiệp vụ

Renewal cho phép bạn đọc kéo dài hạn trả nếu không có vấn đề nghiệp vụ.

Trong project này:

```text
MEMBER được gia hạn sách của chính mình.
LIBRARIAN/ADMIN có thể gia hạn giúp MEMBER.
LIBRARIAN/ADMIN không gia hạn sách đứng tên chính tài khoản staff/admin.
```

Chính sách khuyến nghị cho đồ án:

```text
Member được tự gia hạn online.
Mặc định chỉ được gia hạn 1 lần cho mỗi borrow.
Mỗi lần gia hạn thêm 7 ngày hoặc thêm đúng loan period tùy policy.
Không cho gia hạn nếu đã quá hạn.
Không cho gia hạn nếu có người khác đang hold sách đó.
```

Lý do nên cho member tự gia hạn: đây là tính năng self-service phổ biến trong thư viện. Nhưng giới hạn 1 lần giúp nghiệp vụ dễ kiểm soát, dễ giải thích và tránh việc user giữ sách quá lâu.

## Endpoint đề xuất

Member self-service:

```http
PUT /api/borrows/{borrowId}/extend
Authorization: Bearer member-token
Idempotency-Key: uuid
```

Staff hỗ trợ:

```http
PUT /api/staff/borrows/{borrowId}/extend
Authorization: Bearer librarian-or-admin-token
Idempotency-Key: uuid
```

Có thể dùng chung service method:

```java
renewBorrow(actorId, actorRole, borrowId, requestedDays)
```

## Điều kiện được renew

- Borrow tồn tại.
- Borrow status `BORROWED`.
- Borrower role phải là `MEMBER`.
- Nếu actor là `MEMBER`, `borrow.member.id` phải bằng current member id.
- Chưa quá hạn, trừ khi policy cho phép.
- `renewCount < maxRenewals`.
- Không có hold active cho cùng `bookId`.
- Member không bị block.
- Copy không bị mark lost/damaged.

Policy mặc định nên là:

```text
MAX_RENEWALS_DEFAULT = 1
RENEWAL_DAYS_DEFAULT = 7
ALLOW_RENEW_OVERDUE = false
```

Nghĩa là user không được gia hạn vô hạn. Nếu `renewCount = 1` và `maxRenewals = 1`, request tiếp theo bị từ chối.

## Schema cần bổ sung

`borrow_records` hiện chưa có:

```text
renew_count
max_renewals_at_checkout
```

Nên thêm migration:

```sql
ALTER TABLE borrow_records
ADD COLUMN renew_count INT NOT NULL DEFAULT 0,
ADD COLUMN max_renewals_at_checkout INT NOT NULL DEFAULT 1;

ALTER TABLE borrow_records
ADD CONSTRAINT chk_renew_count_non_negative CHECK (renew_count >= 0);
```

Lý do lưu `max_renewals_at_checkout`: nếu admin đổi policy sau này, borrow cũ vẫn theo rule lúc mượn.

## Policy vừa sức đồ án

Đọc từ `system_settings`:

```text
BORROW_DAYS_DEFAULT = 14
MAX_RENEWALS_DEFAULT = 1
RENEWAL_DAYS_DEFAULT = 7
ALLOW_RENEW_OVERDUE = false
```

Hiện DB đã có `system_settings`, nên chưa cần bảng `circulation_policies` riêng.

Nên seed thêm nếu chưa có:

```sql
INSERT INTO system_settings (key, value, description)
VALUES
    ('MAX_RENEWALS_DEFAULT', '1', 'Default max renewal count per borrow'),
    ('RENEWAL_DAYS_DEFAULT', '7', 'Default extra days for each renewal'),
    ('ALLOW_RENEW_OVERDUE', 'false', 'Allow renewing overdue borrow');
```

## Transaction

Renew cần transaction nhưng không nhất thiết phải lock `BookCopy` mạnh như checkout. Tuy nhiên nên lock `BorrowRecord` bằng pessimistic write để tránh double renew:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<BorrowRecord> findById(Long id);
```

Nếu không lock, hai request renew đồng thời có thể cùng thấy `renewCount = 0` rồi cùng tăng thành 1 nhưng due date bị update sai.

## Hold block

Nếu có active reservation cho book:

```text
WAITING
NOTIFIED/READY_FOR_PICKUP
```

thì không cho renew.

Business reason: có người khác đang chờ sách, người đang mượn không nên giữ thêm.

## Idempotency

Renew là `PUT`, về HTTP thường có thể idempotent nếu client set cùng target state. Nhưng endpoint `extend` là "thêm ngày" nên thực tế không idempotent.

Vì vậy vẫn yêu cầu:

```http
Idempotency-Key: uuid
```

Nếu frontend retry do timeout, request renew không bị cộng ngày 2 lần.

## Response

```json
{
  "borrowId": 9001,
  "oldDueDate": "2026-05-30T23:59:59",
  "newDueDate": "2026-06-06T23:59:59",
  "renewCount": 1,
  "maxRenewals": 1
}
```

## Test nên có

- Member renew borrow của mình thành công.
- Member renew borrow của người khác bị từ chối.
- Staff renew giúp member thành công.
- Borrower role staff/admin bị từ chối.
- Renew quá `maxRenewals` bị từ chối.
- Renew khi có hold active bị từ chối.
- Double request cùng idempotency key chỉ renew một lần.

## Nguồn kỹ thuật

- Spring Data JPA Locking: https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html
- Spring Security Method Security: https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
- Idempotency-Key draft: https://www.ietf.org/archive/id/draft-ietf-httpapi-idempotency-key-header-03.html
