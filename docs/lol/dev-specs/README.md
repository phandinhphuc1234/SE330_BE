# Ebook Payment Development Specs

## Mục tiêu

Bộ spec này tách từ `ebook_vnpay_payment_license_flow.md` để implement từng phần nhỏ, dễ review và dễ test.

Trạng thái hiện tại giả định:

```text
Đã có upload PDF lên cloud.
Đã có API create/get metadata ebook.
Đã có bảng hoặc entity tương đương book_ebooks.
Chưa implement payment, loan, reading session, jobs.
```

Scope MVP:

```text
Backend payment bằng VNPAY.
PostgreSQL là source of truth.
Redis dùng cho idempotency và active reading session cache.
Hết license thì chặn thanh toán.
Chưa implement reservation/hàng chờ trong MVP.
```

## Thứ tự implement

1. `01-payment-provider-abstraction.md`
2. `02-payment-data-and-create-api.md`
3. `03-vnpay-provider.md`
4. `04-payment-callback-and-loan.md`
5. `05-reader-session.md`
6. `06-expiration-and-cleanup-jobs.md`
7. `07-test-plan.md`
8. `08-vnpay-querydr-refund-future.md` - phase sau, không bắt buộc cho MVP
9. `09-frontend-payment-api-integration.md` - contract cho frontend tích hợp payment APIs
10. `10-free-ebook-borrow-and-query.md` - backend flow cho ebook FREE và API query ebook

## Nguyên tắc chung

```text
Frontend không gửi amount.
Backend tự tính amount từ book_ebooks.borrow_price.
IPN/callback server-to-server mới được mark payment SUCCESS.
Return URL chỉ redirect, không cập nhật nghiệp vụ.
Payment success chỉ cấp quyền mượn.
Reader API mới cấp quyền đọc tạm thời.
Raw reading session token không lưu database.
Không truyền reading session token trên query string.
```

## Kiến trúc tổng quát

```text
PaymentController
  -> PaymentService
     -> RedisIdempotencyService
     -> PaymentProviderClientFactory
     -> PaymentTransactionRepository

Provider-specific:
  -> VnpayPaymentProviderClient

Business-specific:
  -> PaymentBusinessApplierFactory
  -> EbookPaymentApplier

Reader:
  -> EbookReadingSessionService
  -> EbookReaderService
  -> Redis active session cache
```

## Definition of Done chung

```text
Migration chạy sạch.
API có validation và error code ổn định.
Payment flow idempotent.
IPN duplicate không tạo loan trùng.
Không cấp quá max_concurrent_loans.
Reader signed URL chỉ cấp khi loan và session ACTIVE.
Job chạy lại nhiều lần không làm sai trạng thái.
Có unit/integration test cho happy path, duplicate, invalid signature, concurrency.
```

## Phase sau

```text
VNPAY QueryDr dùng cho reconciliation khi IPN không về hoặc cần đối soát.
VNPAY Refund dùng cho hoàn tiền, cần bảng refund riêng.
Không đưa QueryDr/Refund vào MVP nếu chưa cần.
```
