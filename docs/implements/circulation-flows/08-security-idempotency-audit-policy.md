# Security, Idempotency Và Audit Policy Cho Circulation

File này gom các phần cắt ngang được dùng bởi mọi circulation flow.

## Role policy

Rule gốc:

```text
MEMBER là người được mượn.
LIBRARIAN/ADMIN là người thao tác nghiệp vụ.
LIBRARIAN/ADMIN không tự mượn bằng chính tài khoản staff/admin.
```

Áp dụng:

| API | Actor | Borrower/Target |
|---|---|---|
| `POST /api/staff/circulation/checkouts` | LIBRARIAN/ADMIN | MEMBER |
| `POST /api/staff/circulation/checkins` | LIBRARIAN/ADMIN | Borrow hiện tại |
| `PUT /api/borrows/{id}/extend` | MEMBER | Chính member đó |
| `PUT /api/staff/borrows/{id}/extend` | LIBRARIAN/ADMIN | MEMBER |
| `POST /api/holds` | MEMBER | Chính member đó |
| `POST /api/staff/holds/{id}/checkout` | LIBRARIAN/ADMIN | MEMBER của hold |
| `POST /api/fines/{id}/pay` | MEMBER hoặc staff | Fine của MEMBER |
| `PUT /api/fines/{id}/waive` | LIBRARIAN/ADMIN | Fine của MEMBER |

## Dùng `@PreAuthorize`

Project hiện đã bật:

```java
@EnableMethodSecurity
```

Spring Security docs nói method security cho phép dùng `@PreAuthorize`/`@PostAuthorize` trên Spring bean.

Khuyến nghị:

```java
@PreAuthorize("hasAnyRole('LIBRARIAN', 'ADMIN')")
public CheckoutResponse checkoutForMember(...) {}
```

Owner check nên để trong service:

```java
if (actorRole == MEMBER && !borrow.getMember().getId().equals(actorId)) {
    throw new AppException(ErrorCode.FORBIDDEN);
}
```

Không nên nhồi toàn bộ owner logic vào SpEL quá dài.

## Idempotency policy

Các API cần `Idempotency-Key`:

```text
POST /api/staff/circulation/checkouts
POST /api/borrows                  nếu sau này có self-checkout
POST /api/staff/circulation/checkins
PUT  /api/borrows/{id}/extend
PUT  /api/staff/borrows/{id}/extend
POST /api/staff/holds/{holdId}/checkout
POST /api/fines/{id}/pay
POST /api/books/import-csv
```

Nên có:

```text
POST /api/holds
```

GET không cần.

Các API thường không cần:

```text
GET /api/...
PATCH /api/books/{id}              nếu chỉ update field
PUT /api/fines/{id}/waive          nếu chỉ set status = WAIVED
```

DELETE cancel hold:

```text
DELETE /api/holds/{id}
    không bắt buộc Idempotency-Key nếu chỉ member cancel WAITING và code xử lý idempotent.

PUT /api/staff/holds/{id}/cancel
    nên dùng Idempotency-Key nếu cancel READY_FOR_PICKUP có side effect:
    release assigned copy, assign người tiếp theo, gửi email.
```

Nếu không dùng key cho cancel có side effect thì bắt buộc lock `Reservation` + `BookCopy` và check status thật chặt.

## Idempotency scope

Không nên scope chỉ bằng:

```text
actor_id + idempotency_key
```

Nên scope bằng:

```text
actor_id + http_method + normalized_path + idempotency_key
```

Ví dụ:

```text
actor_id = 2
http_method = POST
normalized_path = /api/staff/circulation/checkouts
idempotency_key = 58b0f7aa-8dc3-4bc5-9a41-6a93a25a2a43
```

`normalized_path` là path template, không phải raw path:

```text
Raw path:        PUT /api/borrows/9001/extend
Normalized path: PUT /api/borrows/{borrowId}/extend
```

Lý do: cùng một key client vô tình dùng ở hai endpoint khác nhau thì không nên đụng nhau.

## Request hash

Hash nên dùng:

```text
SHA-256
```

Hash input:

```text
http_method
normalized_path
canonical_body
important query params nếu có
```

Không hash:

```text
Authorization header
Cookie access/refresh token
Trace id
User agent
```

Lý do: access token có thể refresh khác nhau nhưng retry vẫn là cùng một nghiệp vụ.

Không nên hash raw JSON vì whitespace/thứ tự field có thể khác. Nên canonicalize JSON trước:

```java
JsonNode jsonNode = objectMapper.readTree(rawBody);
String canonicalBody = objectMapper.writeValueAsString(jsonNode);
String hashInput = method + "|" + normalizedPath + "|" + canonicalBody;
String requestHash = sha256(hashInput);
```

Nếu muốn chắc hơn, cấu hình Jackson sort properties/map keys khi serialize canonical body.

## Schema đề xuất

```sql
CREATE TABLE idempotency_records (
    id BIGSERIAL PRIMARY KEY,

    actor_id BIGINT NOT NULL,
    http_method VARCHAR(10) NOT NULL,
    normalized_path VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,

    request_hash VARCHAR(64) NOT NULL,

    status VARCHAR(30) NOT NULL,
    response_code INT,
    response_body TEXT,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,

    error_code VARCHAR(100),
    error_message TEXT,

    CONSTRAINT uk_idempotency_scope_key
        UNIQUE (actor_id, http_method, normalized_path, idempotency_key)
);

CREATE INDEX idx_idempotency_expires_at
ON idempotency_records(expires_at);
```

Status:

```text
PROCESSING
COMPLETED
FAILED
```

TTL:

```text
24 giờ là đủ cho project này.
48 giờ nếu muốn an toàn hơn khi client/network retry lâu.
```

Stripe docs cũng dùng hướng lưu kết quả request đầu tiên và cho phép prune key sau ít nhất 24 giờ. Project mình không cần copy y nguyên Stripe, nhưng policy 24-48h là hợp lý.

## Idempotency flow

```text
1. Client gửi Idempotency-Key.
2. Backend validate key không rỗng, không chứa dữ liệu nhạy cảm.
3. Backend tính scope:
   actor_id + method + normalized_path + idempotency_key.
4. Backend tính request_hash:
   SHA-256(method + normalized_path + canonical_body).
5. Backend insert record PROCESSING bằng unique constraint.
6. Nếu insert thành công:
   - request này là owner.
   - xử lý nghiệp vụ trong transaction.
   - lưu response_code + response_body.
   - status = COMPLETED.
   - trả response.
7. Nếu insert conflict:
   - load record theo scope.
   - nếu request_hash khác: trả 409 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST.
   - nếu status = COMPLETED: trả lại response cũ.
   - nếu status = PROCESSING: trả 409 REQUEST_ALREADY_PROCESSING hoặc 425 TOO_EARLY.
   - nếu status = FAILED: tùy policy, trả lỗi cũ hoặc yêu cầu retry bằng key mới.
```

Không được implement kiểu "SELECT trước rồi INSERT sau" mà không có unique constraint, vì hai request đồng thời có thể cùng thấy chưa có record. Race condition phải để DB unique constraint chặn.

Với PostgreSQL có thể dùng:

```sql
INSERT INTO idempotency_records (...)
VALUES (...)
ON CONFLICT DO NOTHING;
```

Nếu affected rows = 0 thì key đã tồn tại.

## Stale PROCESSING

Nếu service chết giữa chừng, record có thể kẹt `PROCESSING`.

Policy:

```text
PROCESSING quá 15 phút -> stale.
Scheduled job chuyển FAILED hoặc FAILED_STALE.
Client dùng key mới để retry.
```

Cleanup:

```java
@Scheduled(cron = "0 0 3 * * *")
public void cleanupExpiredIdempotencyRecords() {
    // delete where expiresAt < now
}
```

## Riêng endpoint renew

`PUT /api/borrows/{id}/extend` vẫn cần idempotency vì logic là:

```text
dueDate = dueDate + 7 days
renewCount = renewCount + 1
```

Gọi hai lần sẽ cộng hai lần nếu không bảo vệ.

Nếu muốn REST idempotent hơn, có thể thiết kế:

```http
PUT /api/borrows/{id}/due-date

{
  "newDueDate": "2026-06-06T23:59:59"
}
```

Nhưng với đồ án, giữ `extend + Idempotency-Key` dễ hiểu và sát nghiệp vụ hơn.

## Audit policy

Nên có `AuditService` thay vì chỉ `log.info`.

Signature:

```java
void record(AuditAction action, Long actorId, MemberRole actorRole,
            String entityType, Long entityId, Map<String, Object> metadata);
```

Action nên có:

```text
BORROW_BOOK
RETURN_BOOK
RENEW_BORROW
CREATE_HOLD
CANCEL_HOLD
HOLD_READY_FOR_PICKUP
BORROW_FROM_HOLD
MARK_BORROW_OVERDUE
MARK_BORROW_LOST
CREATE_FINE
PAY_FINE
WAIVE_FINE
```

`audit_logs.user_id` là actor, không phải borrower.

Ví dụ staff checkout:

```json
{
  "actorId": 2,
  "actorRole": "LIBRARIAN",
  "action": "BORROW_BOOK",
  "entityType": "BORROW_RECORD",
  "entityId": 9001,
  "metadata": {
    "borrowerId": 15,
    "bookCopyId": 101,
    "barcode": "BC-000101"
  }
}
```

## Transaction boundary

Các service ghi DB phải là một transaction:

```java
@Transactional
public CheckoutResponse checkout(...) {
    ...
}
```

Không gọi email/payment gateway bên ngoài transaction. Dùng event sau commit.

## Test policy

Nên có integration test cho:

- Role `LIBRARIAN` checkout cho `MEMBER` được.
- Role `LIBRARIAN` checkout cho chính `LIBRARIAN` bị chặn.
- Duplicate idempotency key không tạo borrow/payment duplicate.
- Audit được ghi đúng actor.
- Transaction rollback thì không gửi event email.

## Nguồn kỹ thuật

- Spring Security Method Security: https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
- Spring `@Transactional`: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- Spring transaction-bound events: https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
- Idempotency-Key draft: https://www.ietf.org/archive/id/draft-ietf-httpapi-idempotency-key-header-03.html
- Stripe Idempotent Requests: https://docs.stripe.com/api/idempotent_requests
