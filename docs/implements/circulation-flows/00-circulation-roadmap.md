# Circulation Implementation Roadmap

File này là bản triển khai cụ thể hơn từ `docs/implements/library_circulation_workflow_api_spec_revised.md`, sau khi đối chiếu với codebase hiện tại.

## Kết luận sau khi đọc codebase

Hệ thống hiện đã làm khá tốt phần nền:

- Auth/JWT, role `MEMBER`, `LIBRARIAN`, `ADMIN`.
- Catalog: `Book`, `Author`, `Category`, `BookCopy`.
- Search book bằng Spring Data JPA `Specification`.
- Soft delete book/book copy.
- CSV import book/copy.
- Redis token store.
- Email verification bằng `JavaMailSender` + Thymeleaf.
- DB migration đã có sẵn nhiều bảng circulation: `borrow_records`, `reservations`, `fine_configs`, `payments`, `notifications`, `notification_queue`, `audit_logs`, `system_settings`.

Nhưng Java layer hiện chưa có entity/service/controller cho circulation:

- Chưa có `BorrowRecord` entity/service.
- Chưa có `Reservation` entity/service.
- Chưa có `Payment` hoặc fine service.
- Chưa có scheduled jobs cho overdue/reminder/hold expiry.
- Chưa có idempotency table/service.
- Chưa có audit service thật, mới đang log application bằng `log.info`.

Vì vậy hướng triển khai đúng là không nhảy thẳng vào endpoint quá nhiều, mà xây lõi circulation theo từng flow.

## Rule nghiệp vụ quan trọng của project này

Theo yêu cầu hiện tại:

```text
Chỉ MEMBER được đứng tên mượn sách.
LIBRARIAN/ADMIN không tự mượn bằng tài khoản staff/admin.
LIBRARIAN/ADMIN chỉ là actor thao tác tại quầy cho một MEMBER.
```

Nghĩa là:

- `borrow_records.member_id` luôn phải trỏ tới member có `role = MEMBER`.
- `actorId` trong audit/idempotency/log có thể là `LIBRARIAN` hoặc `ADMIN`.
- API staff checkout nhận `memberId` hoặc `memberBarcode`, nhưng phải validate người đó là `MEMBER`.
- API member self-service nếu có thì chỉ lấy borrower từ JWT hiện tại, và JWT đó phải là `MEMBER`.

Nếu sau này muốn thủ thư cũng có thẻ bạn đọc riêng, nên tạo hai account khác nhau:

```text
librarian.work@example.com -> role LIBRARIAN
librarian.personal@example.com -> role MEMBER
```

Không nên để một account `LIBRARIAN` vừa thao tác staff vừa đứng tên mượn.

## Thứ tự triển khai khuyến nghị

### Phase 1 - Core circulation

1. Tạo entity/repository cho `BorrowRecord`, `Reservation`.
2. Mở rộng `BookCopyStatus`: thêm `ON_HOLD_SHELF`, `OVERDUE`, `REMOVED` hoặc map cẩn thận với status DB hiện tại.
3. Tạo `CirculationPolicyService` đọc `system_settings` trước, chưa cần bảng policy riêng.
4. Implement checkout staff flow.
5. Implement return/check-in staff flow.
6. Implement `GET /api/borrows/my`.

### Phase 2 - Hold + renewal

1. Implement hold queue.
2. Implement checkout từ hold.
3. Implement renew.
4. Implement notification event sau commit.

### Phase 3 - Scheduled jobs + fine

1. `markOverdueBorrows`.
2. `sendDueSoonReminderEmails`.
3. `expireReadyHolds`.
4. Fine calculation khi return.
5. Fake payment/waive fine.

### Phase 4 - Production-like polish

1. Idempotency key cho checkout/check-in/renew/pay.
2. Audit service lưu `audit_logs`.
3. Job execution logs.
4. Integration tests bằng Testcontainers PostgreSQL cho lock/concurrency.

## Kỹ thuật nên dùng trong project này

| Vấn đề | Kỹ thuật nên dùng |
|---|---|
| Chống 2 request mượn cùng một copy | `@Transactional` + Spring Data JPA `@Lock(PESSIMISTIC_WRITE)` hoặc native `FOR UPDATE` |
| Search/filter nhiều điều kiện | Spring Data JPA `Specification` |
| Role check | `@PreAuthorize` tại service/controller |
| Email sau khi transaction commit | `ApplicationEventPublisher` + `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` |
| Job hằng ngày | `@Scheduled` |
| Chống duplicate checkout/check-in do retry/double click | `Idempotency-Key` + bảng `idempotency_records` |
| Query active record nhanh | PostgreSQL partial index |
| Config rule mượn/trả vừa sức đồ án | `system_settings` trước, bảng `circulation_policies` sau |

## Nguồn nghiên cứu chính

- Spring Data JPA Locking: https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html
- Spring Data JPA Specifications: https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html
- Spring `@Transactional`: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- Spring scheduling: https://docs.spring.io/spring-framework/reference/integration/scheduling.html
- Spring transaction-bound events: https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
- Spring Security method security: https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
- PostgreSQL row locking: https://www.postgresql.org/docs/current/sql-select.html
- PostgreSQL partial indexes: https://www.postgresql.org/docs/current/indexes-partial.html
- Idempotency-Key draft: https://www.ietf.org/archive/id/draft-ietf-httpapi-idempotency-key-header-03.html
- Koha circulation manual: https://koha-community.org/manual/23.05/en/html/circulation.html

