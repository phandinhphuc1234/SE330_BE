# Spec 04: Payment Callback And Ebook Loan

## Mục tiêu

Xử lý IPN/callback từ provider, mark payment terminal state và cấp `ebook_loan ACTIVE` sau khi thanh toán thành công.

MVP chỉ xử lý VNPAY IPN, nhưng service callback phải dùng `PaymentProviderClient`.

## API

```http
GET /api/payments/ipn/vnpay
```

VNPAY gọi server-to-server về backend.

Yêu cầu vận hành:

```text
IPN URL phải public và dùng HTTPS trong môi trường tích hợp thật.
Local sandbox cần public tunnel như ngrok/cloudflared.
Endpoint phải trả JSON có RspCode và Message.
Endpoint phải idempotent vì VNPAY có thể gọi lại nhiều lần.
```

Response:

```json
{
  "RspCode": "00",
  "Message": "Confirm Success"
}
```

Mapping response VNPAY:

| Case | RspCode | Message |
|---|---|---|
| Cập nhật thành công | `00` | `Confirm Success` |
| Payment đã xử lý | `02` | `Order already confirmed` |
| Không tìm thấy payment | `01` | `Order not found` |
| Sai amount | `04` | `Invalid amount` |
| Sai checksum | `97` | `Invalid signature` |
| Lỗi không xác định | `99` | `Unknown error` |

Retry behavior từ VNPAY:

```text
RspCode 00 hoặc 02: VNPAY kết thúc luồng, không retry.
RspCode 01, 04, 97, 99 hoặc timeout: VNPAY có thể retry IPN.
Số lần retry tối đa theo tài liệu: 10 lần.
Khoảng cách retry theo tài liệu: khoảng 5 phút.
```

Hệ quả implement:

```text
IPN phải lưu payment_events để audit các lần retry.
IPN duplicate sau khi payment đã terminal phải trả 02.
Không được throw exception ra ngoài nếu đã map được RspCode nghiệp vụ.
Unknown exception mới trả 99.
```

## Callback flow

```text
1. Nhận params và headers.
2. Lưu payment_events RECEIVED với raw payload.
3. Lấy provider client theo endpoint/provider.
4. Gọi client.verifyCallback.
5. Update event.signature_valid.
6. Nếu invalid signature: mark event FAILED, return provider error.
7. Find payment by providerOrderId/paymentCode với FOR UPDATE.
8. Nếu không thấy payment: mark event FAILED, return order not found.
9. Check amount khớp.
10. Nếu amount mismatch: mark event FAILED, return invalid amount.
11. Nếu payment đã terminal: mark event IGNORED, return duplicate.
12. Nếu payment failed từ provider: mark payment FAILED, mark event PROCESSED.
13. Nếu provider success: mark payment SUCCESS, lưu providerTransactionId, paidAt, providerMetadata.
14. Gọi PaymentBusinessApplierFactory.get(payment.purpose).applySuccess(payment).
15. Mark event PROCESSED.
16. Return success response cho provider.
```

Return code khi provider báo giao dịch thất bại:

```text
Nếu checksum đúng, payment tồn tại, amount đúng, và payment còn PENDING:
- Mark payment FAILED.
- Mark event PROCESSED.
- Trả RspCode 00 cho VNPAY.

Lý do:
Merchant đã ghi nhận kết quả thất bại thành công.
Không cần VNPAY retry IPN thất bại này nữa.
```

VNPAY success rule:

```text
Provider callback result chỉ được coi là success khi:
vnp_ResponseCode == "00"
vnp_TransactionStatus == "00"

Nếu chỉ một trong hai field là "00" thì vẫn không cấp ebook_loan.
```

Event type:

```text
VNPAY IPN: VNPAY_IPN
VNPAY Return: VNPAY_RETURN
```

Raw payload:

```text
Lưu toàn bộ vnp_* params vào payment_events.raw_payload.
Không chỉ lưu các field đã parse.
```

## `ebook_loans`

Các field bắt buộc:

```text
id
member_id
book_id
book_ebook_id
payment_id
status
borrowed_at
expired_at
returned_at
revoked_at
created_at
updated_at
version
```

Index:

```sql
CREATE INDEX idx_ebook_loans_ebook_status_expired
ON ebook_loans(book_ebook_id, status, expired_at);

CREATE INDEX idx_ebook_loans_member_ebook_status
ON ebook_loans(member_id, book_ebook_id, status);

CREATE INDEX idx_ebook_loans_payment_id
ON ebook_loans(payment_id);
```

Unique:

```sql
CREATE UNIQUE INDEX ux_ebook_loan_payment
ON ebook_loans(payment_id)
WHERE payment_id IS NOT NULL;

CREATE UNIQUE INDEX ux_member_active_ebook_loan
ON ebook_loans(member_id, book_ebook_id)
WHERE status = 'ACTIVE';
```

## EbookPaymentApplier

Responsibilities:

```text
validatePayableTarget: kiểm tra ebook có thể thanh toán và tính amount.
applySuccess: cấp ebook_loan sau khi payment SUCCESS.
```

`applySuccess` phải idempotent:

```text
Nếu đã có loan theo payment_id thì return.
Lock book_ebooks row bằng SELECT FOR UPDATE.
Nếu user đã có active loan cho ebook thì return hoặc throw theo business rule.
Đếm active loans của book_ebook_id.
Nếu active count >= max_concurrent_loans thì không tạo loan.
Nếu còn slot thì tạo ebook_loan ACTIVE.
expired_at = borrowed_at + loan_duration_days.
```

MVP hết license:

```text
Ở bước create payment: chặn thanh toán nếu hết license.
Ở bước IPN: vẫn check lại license.
Nếu hết license do race condition, không cấp quá license.
Mark payment cần manual handling hoặc trạng thái xử lý phù hợp theo codebase.
Chưa implement ebook_reservations trong MVP.
```

Khuyến nghị MVP khi payment SUCCESS nhưng không cấp được loan vì hết license race condition:

```text
Không chuyển ngược payment SUCCESS về FAILED.
Không tạo loan vượt max_concurrent_loans.
Ghi provider/payment event đã xử lý.
Ghi failure/manual reason nội bộ để admin xử lý refund hoặc cấp sau.
Refund tự động để phase sau.
```

## Concurrency rule

Khi cấp loan phải lock `book_ebooks`.

```sql
SELECT *
FROM book_ebooks
WHERE id = :bookEbookId
FOR UPDATE;
```

Sau khi lock mới đếm:

```sql
SELECT COUNT(*)
FROM ebook_loans
WHERE book_ebook_id = :bookEbookId
  AND status = 'ACTIVE'
  AND expired_at > CURRENT_TIMESTAMP;
```

## Acceptance criteria

```text
IPN duplicate không tạo loan lần 2.
Sai checksum không update payment.
Sai amount không update payment.
Payment FAILED không tạo loan.
Payment SUCCESS tạo loan nếu còn license.
Hai IPN cạnh tranh license cuối cùng chỉ một loan được tạo.
active loan count không vượt max_concurrent_loans.
```

## Test cases

```text
IPN checksum invalid trả RspCode 97.
IPN payment không tồn tại trả RspCode 01.
IPN amount mismatch trả RspCode 04.
IPN success update payment SUCCESS.
IPN success tạo ebook_loan ACTIVE.
IPN duplicate sau SUCCESS trả RspCode 02.
IPN duplicate không tạo loan lần 2.
Hai IPN đồng thời không vượt license.
IPN ResponseCode 00 nhưng TransactionStatus 04 không tạo loan.
IPN timeout/retry nhiều lần vẫn không tạo loan trùng.
payment_events lưu đủ raw vnp_* params cho từng lần IPN.
```
