# Spec 02: Payment Data Model And Create Payment API

## Mục tiêu

Implement bảng payment core và API tạo payment generic, nhưng MVP chỉ support `VNPAY`.

API này không cấp quyền đọc ebook. Nó chỉ tạo giao dịch `PENDING` và trả `paymentUrl`.

## Database

### `payment_transactions`

Các field bắt buộc:

```text
id
payment_code
member_id
provider
provider_order_id
provider_transaction_id
purpose
target_type
target_id
amount
currency
status
payment_url
idempotency_key
paid_at
cancelled_at
expired_at
failure_code
failure_message
provider_response_code
provider_transaction_status
provider_metadata JSONB
created_at
updated_at
version
```

Constraint/status:

```text
status IN PENDING, SUCCESS, FAILED, EXPIRED, CANCELLED
purpose MVP = EBOOK_PAYMENT
target_type MVP = BOOK_EBOOK
amount > 0
payment_code unique
```

Index bắt buộc:

```sql
CREATE INDEX idx_payment_member_id
ON payment_transactions(member_id);

CREATE INDEX idx_payment_status
ON payment_transactions(status);

CREATE INDEX idx_payment_target
ON payment_transactions(target_type, target_id);

CREATE INDEX idx_payment_provider_order
ON payment_transactions(provider, provider_order_id);

CREATE INDEX idx_payment_provider_transaction
ON payment_transactions(provider, provider_transaction_id);

CREATE INDEX idx_payment_created_at
ON payment_transactions(created_at);
```

Duplicate protection:

```sql
CREATE UNIQUE INDEX ux_payment_pending_ebook_target
ON payment_transactions(member_id, purpose, target_type, target_id)
WHERE status = 'PENDING';

CREATE UNIQUE INDEX ux_payment_success_ebook_target
ON payment_transactions(member_id, purpose, target_type, target_id)
WHERE status = 'SUCCESS';
```

### `payment_events`

Các field bắt buộc:

```text
id
payment_transaction_id
provider
event_type
provider_order_id
provider_transaction_id
raw_payload JSONB
raw_headers JSONB
signature_valid
processing_status
error_message
received_at
processed_at
```

`payment_events` phải lưu được cả IPN/callback và return payload để debug.

Với VNPAY, `provider_metadata` của `payment_transactions` nên lưu tối thiểu:

```json
{
  "vnpCreateDate": "20260613100300",
  "vnpExpireDate": "20260613101800",
  "bankCode": "VNBANK",
  "locale": "vn",
  "orderInfo": "Thanh toan ebook 1001 ma PAY202606130001"
}
```

Lý do:

```text
VNPAY QueryDr cần vnp_TransactionDate, chính là thời gian ghi nhận giao dịch ở merchant.
Với PAY flow, giá trị này nên lấy từ vnp_CreateDate lúc build URL thanh toán.
```

## Redis idempotency

Key:

```text
idem:payment:{provider}:{purpose}:{memberId}:{idempotencyKey}
```

Value `PROCESSING`:

```json
{
  "status": "PROCESSING",
  "requestHash": "sha256(method|path|canonicalBody)",
  "createdAt": "2026-06-13T10:00:00Z"
}
```

Value `COMPLETED`:

```json
{
  "status": "COMPLETED",
  "requestHash": "sha256(method|path|canonicalBody)",
  "response": {
    "paymentId": 9001,
    "paymentCode": "PAY202606130001",
    "status": "PENDING",
    "provider": "VNPAY",
    "amount": 25000,
    "currency": "VND",
    "paymentUrl": "https://sandbox.vnpayment.vn/..."
  },
  "createdAt": "2026-06-13T10:00:00Z"
}
```

TTL:

```text
PROCESSING: 10 phút
COMPLETED: 24 giờ
FAILED: 10 phút
```

## API

```http
POST /api/payments
Authorization: Bearer <access_token>
Idempotency-Key: <uuid>
Content-Type: application/json
```

Request:

```json
{
  "purpose": "EBOOK_PAYMENT",
  "targetType": "BOOK_EBOOK",
  "targetId": 1001,
  "provider": "VNPAY",
  "bankCode": "VNBANK",
  "locale": "vn"
}
```

Response `201 Created`:

```json
{
  "paymentId": 9001,
  "paymentCode": "PAY202606130001",
  "provider": "VNPAY",
  "purpose": "EBOOK_PAYMENT",
  "targetType": "BOOK_EBOOK",
  "targetId": 1001,
  "status": "PENDING",
  "amount": 25000,
  "currency": "VND",
  "paymentUrl": "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?...",
  "expiredAt": "2026-06-13T10:15:00"
}
```

## Validation

```text
Thiếu Idempotency-Key -> 400 MISSING_IDEMPOTENCY_KEY
Idempotency-Key dùng lại với body khác -> 409 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST
Request cùng key đang xử lý -> 409 IDEMPOTENCY_REQUEST_PROCESSING
Ebook không tồn tại -> 404 EBOOK_NOT_FOUND
Ebook không ACTIVE -> 409 EBOOK_NOT_AVAILABLE
borrow_price <= 0 -> 409 EBOOK_DOES_NOT_REQUIRE_PAYMENT
User đã có loan ACTIVE -> 409 EBOOK_ALREADY_BORROWED
Đã có payment PENDING -> 409 PAYMENT_ALREADY_PENDING
Đã có payment SUCCESS -> 409 PAYMENT_ALREADY_SUCCESS
Hết license -> 409 EBOOK_LICENSE_NOT_AVAILABLE
Provider chưa hỗ trợ -> 400 UNSUPPORTED_PAYMENT_PROVIDER
Provider lỗi -> 502 PAYMENT_PROVIDER_ERROR
locale không thuộc vn/en -> 400 INVALID_PAYMENT_LOCALE
bankCode không hợp lệ -> 400 INVALID_PAYMENT_BANK_CODE
```

VNPAY request validation:

```text
locale optional, default vn, chỉ nhận vn hoặc en.
bankCode optional.
bankCode MVP cho phép: VNPAYQR, VNBANK, INTCARD.
Nếu muốn cho user chọn ngân hàng tại VNPAY thì bỏ bankCode khỏi request hoặc gửi null.
currency internal cho VNPAY luôn là VND.
```

## Flow

```text
1. Check Idempotency-Key.
2. Build canonical request hash.
3. Nếu COMPLETED cùng hash thì trả cached response.
4. Nếu PROCESSING cùng hash thì trả 409.
5. Nếu khác hash thì trả 409.
6. SETNX PROCESSING.
7. Gọi PaymentBusinessApplier.validatePayableTarget.
8. Tạo payment_transactions PENDING.
9. Lấy PaymentProviderClient bằng provider.
10. Gọi client.createPayment.
11. Lưu payment_url, provider_order_id, expired_at, provider_metadata.
12. Save Redis COMPLETED.
13. Trả response.
```

## Acceptance criteria

```text
Frontend không gửi amount.
Backend tự tính amount từ book_ebooks.borrow_price.
Không tạo payment nếu user đã mượn ebook.
Không tạo payment nếu hết license.
Idempotency hoạt động đúng.
PaymentService gọi provider qua interface.
```
