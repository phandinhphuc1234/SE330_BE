# Spec 07: Test Plan

## Mục tiêu

Test đủ các điểm rủi ro nhất của payment, loan concurrency và reader session.

## Unit tests

Payment provider abstraction:

```text
Factory trả đúng provider client.
Provider chưa hỗ trợ trả UNSUPPORTED_PAYMENT_PROVIDER.
PaymentService dùng mock PaymentProviderClient.
VNPAY-specific params không xuất hiện trong PaymentService test.
```

VNPAY:

```text
Build URL có đủ params bắt buộc.
vnp_Amount = amount * 100.
vnp_CreateDate và vnp_ExpireDate đúng format yyyyMMddHHmmss GMT+7.
Params sort đúng trước khi ký.
Blank bankCode không được đưa vào hashData.
bankCode VNPAYQR/VNBANK/INTCARD được đưa vào hashData.
locale chỉ nhận vn/en.
Verify hash đúng với same params.
Verify hash fail khi sửa amount.
ResponseCode 00 + TransactionStatus 00 => success.
Thiếu một trong hai => failed.
ResponseCode 00 + TransactionStatus 04 => failed, không cấp loan.
ResponseCode 07 + TransactionStatus 00 => failed, không cấp loan.
Return URL valid checksum chỉ parse ProviderReturnResult.
```

Idempotency:

```text
Same key + same body trả cached response.
Same key + different body trả 409.
PROCESSING key trả 409.
COMPLETED key hết TTL thì request mới tạo payment mới nếu business cho phép.
```

EbookPaymentApplier:

```text
Không tạo loan trùng payment_id.
Không tạo loan nếu user đã có active loan.
Không cấp quá max_concurrent_loans.
expired_at = borrowed_at + loan_duration_days.
```

Reader session:

```text
Raw token được hash bằng HMAC-SHA256.
Raw token không lưu DB.
Refresh không vượt loan.expired_at.
Closed session không cấp signed URL.
Redis miss fallback DB.
```

## Integration tests

Payment create:

```text
POST /api/payments tạo payment PENDING.
Frontend không gửi amount, backend tự tính amount.
Ebook không ACTIVE trả lỗi.
User có active loan trả lỗi.
Hết license trả lỗi.
Duplicate Idempotency-Key trả response cũ.
Idempotency-Key same key khác body trả 409.
```

IPN:

```text
Invalid checksum trả provider error và không update payment.
Payment không tồn tại trả order not found.
Amount mismatch không mark SUCCESS.
Success IPN mark payment SUCCESS.
Success IPN tạo ebook_loan ACTIVE.
Duplicate IPN không tạo loan lần 2.
Failed IPN mark payment FAILED.
IPN duplicate sau terminal trả RspCode 02.
IPN invalid checksum trả RspCode 97.
IPN amount mismatch trả RspCode 04.
IPN unknown exception trả RspCode 99.
IPN lưu payment_events raw_payload cho từng lần retry.
```

Return URL:

```text
Return URL valid checksum chỉ redirect.
Return URL không update payment_transactions.
Frontend sau Return URL gọi payment detail để lấy status thật.
Return URL invalid checksum không mark payment FAILED/SUCCESS.
```

Reader:

```text
User chưa có loan ACTIVE không tạo được reading session.
User có loan ACTIVE tạo được reading session.
Loan hết hạn không tạo được reading session.
Signed URL chỉ cấp khi session ACTIVE và loan ACTIVE.
reader/content không nhận token qua query string.
reader/content nhận token qua X-Reading-Session.
Session CLOSED / EXPIRED / REVOKED không được nạp lại Redis.
```

Jobs:

```text
PaymentExpireJob chuyển PENDING hết hạn sang EXPIRED.
EbookLoanExpirationJob chuyển loan hết hạn sang EXPIRED.
Loan expired revoke active reading sessions.
ReadingSessionExpirationWorker expire session hết hạn.
RetentionCleanupJob không xóa ACTIVE.
```

## Concurrency tests

```text
Hai request tạo payment cùng user/ebook chỉ một PENDING hợp lệ.
Hai IPN cùng lúc cho cùng payment chỉ tạo một loan.
Hai payment success cùng lúc cho license cuối cùng chỉ một loan được tạo.
active loan count không vượt max_concurrent_loans.
VNPAY retry IPN 10 lần giả lập vẫn không tạo loan trùng.
```

## Future VNPAY maintenance tests

QueryDr:

```text
Build querydr checksum đúng pipe-delimited order.
QueryDr dùng vnp_TransactionDate từ provider_metadata.vnpCreateDate.
QueryDr response verify checksum trước khi parse status.
ResponseCode 00 nhưng TransactionStatus khác 00 không coi là paid.
ResponseCode 91 map thành transaction not found.
ResponseCode 94 map thành duplicate query request.
```

Refund:

```text
Build refund checksum đúng pipe-delimited order.
Refund full dùng TransactionType 02.
Refund partial dùng TransactionType 03.
Refund amount gửi sang VNPAY nhân 100.
Refund amount không vượt số tiền còn được hoàn.
Refund success không đổi payment SUCCESS thành FAILED.
Refund raw request/response được lưu audit.
```

## Manual sandbox checklist

```text
Tạo payment VNPAY sandbox thành công.
Redirect sang VNPAY sandbox.
Thanh toán sandbox.
VNPAY gọi IPN về public APP_BASE_URL.
Payment detail trả SUCCESS.
Payment detail có ebookLoanId.
User bấm đọc ebook tạo reading session.
Reader content trả Cloudinary signed URL.
Refresh session hoạt động.
Close session xong không lấy được signed URL nữa.
```
