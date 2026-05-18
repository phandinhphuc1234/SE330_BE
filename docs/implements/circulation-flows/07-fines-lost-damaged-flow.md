# Fine, Payment, Lost Và Damaged Flow

File này gom các flow liên quan đến tiền phạt và trạng thái bất thường. Đây là phần nên làm sau checkout/return/hold, vì nó phụ thuộc vào borrow lifecycle.

## Mục tiêu nghiệp vụ

Các loại phí:

```text
OVERDUE_FINE      = phạt quá hạn
DAMAGE_FEE        = phí hư hỏng
LOST_ITEM_FEE     = phí mất sách
MANUAL_ADJUSTMENT = điều chỉnh thủ công
```

Với đồ án, ưu tiên:

```text
1. OVERDUE_FINE khi trả sách trễ.
2. WAIVE fine bởi LIBRARIAN/ADMIN.
3. Fake payment bằng CASH.
```

Lost/damaged làm sau.

## Schema hiện tại

DB đã có `fine_configs`:

```text
rate_per_day
currency
effective_from
effective_until
```

DB cũng lưu fine trực tiếp trong `borrow_records`:

```text
fine_amount
fine_config_id
fine_calculated_at
fine_paid_at
fine_waived_by
fine_waived_reason
```

Và có bảng `payments`.

Vì vậy MVP không cần tạo bảng `fines` riêng. Có thể coi mỗi `borrow_record` có tối đa một overdue fine.

## Overdue fine khi return

Khi check-in:

```text
overdueDays = max(0, returnedAtDate - dueDateDate)
fineAmount = overdueDays * activeFineConfig.ratePerDay
```

Nếu `overdueDays = 0`:

```text
fine_amount = 0
```

Nếu `overdueDays > 0`:

```text
fine_amount = calculated amount
fine_config_id = active config
fine_calculated_at = now
```

Không nên hardcode `5000` trong service. Dùng `fine_configs` hoặc `system_settings`.

## API xem fine của tôi

Vì chưa có bảng `fines`, endpoint có thể đọc từ `borrow_records`:

```http
GET /api/fines/my?status=UNPAID&page=0&size=10
```

UNPAID nghĩa là:

```sql
fine_amount > 0
AND fine_paid_at IS NULL
AND fine_waived_by IS NULL
```

PAID:

```sql
fine_paid_at IS NOT NULL
```

WAIVED:

```sql
fine_waived_by IS NOT NULL
```

## API pay fine giả lập

```http
POST /api/fines/{borrowId}/pay
Authorization: Bearer member-token
Idempotency-Key: uuid
Content-Type: application/json
```

Body:

```json
{
  "paymentMethod": "CASH"
}
```

Rule:

- MEMBER chỉ pay fine của mình.
- Staff có thể pay cash giúp member.
- Borrow phải có `fine_amount > 0`.
- Fine chưa paid/waived.
- Tạo `payments`.
- Set `borrow_records.fine_paid_at = now`.

Vì payment là flow tiền, bắt buộc nên có idempotency. Draft Idempotency-Key nhấn mạnh các request `POST`/`PATCH` không idempotent cần key để retry an toàn.

## API waive fine

```http
PUT /api/fines/{borrowId}/waive
Authorization: Bearer librarian-or-admin-token
Content-Type: application/json
```

Body:

```json
{
  "reason": "Library closure period"
}
```

Rule:

- Actor phải `LIBRARIAN` hoặc `ADMIN`.
- Fine chưa paid.
- Set `fine_waived_by = actorId`.
- Set `fine_waived_reason`.
- Audit `WAIVE_FINE`.

## Lost item

Lost nên làm sau overdue job.

Rule đề xuất:

```text
Nếu borrow OVERDUE quá LOST_AFTER_DAYS thì mark LOST.
BorrowRecord.status = LOST
BookCopy.status = LOST
```

API thủ công:

```http
PUT /api/staff/borrows/{borrowId}/mark-lost
```

Khi lost:

- Không tăng availableCopies.
- Có thể tạo lost fee sau.
- Nếu sách được trả lại sau lost, staff check-in đặc biệt và có thể waive lost fee.

## Damaged on return

Trong check-in body:

```json
{
  "itemBarcode": "BC-000101",
  "returnCondition": "DAMAGED",
  "damageNote": "Water damage"
}
```

Flow:

```text
BorrowRecord -> RETURNED
BookCopy -> DAMAGED
availableCopies không tăng
Audit RETURN_DAMAGED_BOOK
```

Damage fee có thể làm sau bằng manual adjustment.

## Payment thật hay giả lập?

Với đồ án/intern portfolio:

```text
Fake payment CASH là đủ.
```

Không cần tích hợp VNPay/MoMo/Stripe ngay, vì circulation core quan trọng hơn. Với fine thư viện, thanh toán tại quầy bằng tiền mặt hoặc staff mark paid là rất hợp lý.

MVP nên có:

```text
paymentMethod = CASH
status = SUCCESS
paidAt = now
handledBy = librarian/admin nếu staff thu tiền tại quầy
```

Nếu member tự thanh toán online thì mới cần gateway thật.

### Nếu muốn tích hợp thật sau này

Với thị trường Việt Nam:

```text
MoMo/VNPay phù hợp demo local market nhưng cần sandbox, merchant credentials, signature, callback/IPN.
Stripe dễ học tài liệu và idempotency tốt, nhưng không phải lúc nào cũng là lựa chọn tự nhiên cho đồ án thư viện Việt Nam.
```

MoMo docs có test/production environments, credential riêng theo môi trường, và partner transaction id riêng. Điều đó nghĩa là nếu tích hợp thật, mình phải thiết kế payment flow bất đồng bộ:

```text
1. Backend tạo Payment PENDING.
2. Backend gọi gateway tạo payment URL/QR.
3. User thanh toán.
4. Gateway gọi callback/IPN.
5. Backend verify signature.
6. Payment SUCCESS.
7. Fine mới được mark paid.
```

Không nên set fine paid ngay sau khi tạo payment request.

Nếu interviewer hỏi production:

- Tách `payments` thành trạng thái `PENDING/SUCCESS/FAILED`.
- Dùng idempotency key.
- Dùng webhook nếu gateway hỗ trợ.
- Không update paid trực tiếp trước khi gateway xác nhận.

Kết luận:

```text
Giai đoạn này làm fake CASH payment.
Viết code sao cho sau này thêm gateway không phải sửa FineService quá nhiều.
Tạo PaymentService interface, implementation đầu là CashPaymentService/FakePaymentService.
```

## Test nên có

- Return trễ 3 ngày tạo fine đúng rate.
- Return đúng hạn không tạo fine.
- Member xem được fine của mình.
- Member không xem/pay fine người khác.
- Staff waive fine thành công.
- Pay fine tạo payment và set paidAt.
- Pay fine cùng idempotency key không tạo payment duplicate.
- Damaged return không tăng availableCopies.
- Mark lost không tăng availableCopies.

## Nguồn kỹ thuật

- Spring `@Transactional`: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html
- Idempotency-Key draft: https://www.ietf.org/archive/id/draft-ietf-httpapi-idempotency-key-header-03.html
- PostgreSQL partial indexes: https://www.postgresql.org/docs/current/indexes-partial.html
- Stripe Idempotent Requests: https://docs.stripe.com/api/idempotent_requests
- MoMo Integration Procedures: https://developers.momo.vn/v3/docs/payment/onboarding/integration-process/
