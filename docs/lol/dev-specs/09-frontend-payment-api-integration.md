# Spec 09: Frontend Payment API Integration

## Mục tiêu

Spec này dành cho frontend implement flow thanh toán ebook bằng các API backend đã có từ spec 1-4.

Frontend chỉ tạo payment, redirect user sang VNPAY và polling trạng thái payment.
Frontend không tự xác nhận thanh toán thành công, không tự cấp quyền đọc ebook.

```text
Backend IPN/webhook mới là nguồn cập nhật trạng thái thật.
Frontend polling chỉ đọc trạng thái mới nhất để render UI.
```

## API tổng quan

```http
POST /api/payments
GET  /api/payments/{paymentId}
GET  /api/payments/by-code/{paymentCode}
GET  /api/payments/ipn/vnpay
```

Frontend chỉ gọi 3 API đầu.

`GET /api/payments/ipn/vnpay` là webhook/IPN cho VNPAY server gọi, frontend không gọi API này.

## 1. Create payment

```http
POST /api/payments
Authorization: Bearer <accessToken>
Idempotency-Key: <uuid-or-stable-random-key>
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

Field notes:

```text
targetId = bookEbookId, không phải bookId.
bankCode optional. Có thể bỏ null để user tự chọn phương thức ở VNPAY.
locale = vn hoặc en.
Frontend không gửi amount.
```

Response:

```json
{
  "success": true,
  "message": "Tạo giao dịch thanh toán thành công",
  "data": {
    "paymentId": 9001,
    "paymentCode": "PAY20260613204900123ABCDEF",
    "provider": "VNPAY",
    "purpose": "EBOOK_PAYMENT",
    "targetType": "BOOK_EBOOK",
    "targetId": 1001,
    "status": "PENDING",
    "amount": 25000,
    "currency": "VND",
    "paymentUrl": "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?...",
    "expiredAt": "2026-06-13T14:04:00Z"
  },
  "timestamp": "2026-06-13T20:49:00"
}
```

Frontend action:

```text
1. Save paymentId/paymentCode in route state, local/session storage, or query param.
2. Redirect browser to data.paymentUrl.
```

Recommended idempotency key:

```text
Generate once per user click/payment attempt.
Reuse the same key only when retrying the exact same create-payment request.
Generate a new key when user intentionally starts a new payment attempt.
```

## 2. Get payment by id

```http
GET /api/payments/{paymentId}
Authorization: Bearer <accessToken>
```

Use when frontend already has `paymentId`.

Response:

```json
{
  "success": true,
  "message": "Lấy trạng thái thanh toán thành công",
  "data": {
    "paymentId": 9001,
    "paymentCode": "PAY20260613204900123ABCDEF",
    "provider": "VNPAY",
    "purpose": "EBOOK_PAYMENT",
    "targetType": "BOOK_EBOOK",
    "targetId": 1001,
    "status": "SUCCESS",
    "amount": 25000,
    "currency": "VND",
    "paymentUrl": "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?...",
    "providerResponseCode": "00",
    "providerTransactionStatus": "00",
    "paidAt": "2026-06-13T14:00:30Z",
    "cancelledAt": null,
    "expiredAt": "2026-06-13T14:04:00Z",
    "createdAt": "2026-06-13T13:49:00Z",
    "updatedAt": "2026-06-13T14:00:31Z"
  }
}
```

## 3. Get payment by code

```http
GET /api/payments/by-code/{paymentCode}
Authorization: Bearer <accessToken>
```

Use on VNPAY return page because `paymentCode` is the stable provider order id.

Frontend route example:

```text
/payment/vnpay-return?vnp_TxnRef=PAY20260613204900123ABCDEF
```

Frontend should read:

```text
paymentCode = query.vnp_TxnRef
```

Then poll:

```http
GET /api/payments/by-code/PAY20260613204900123ABCDEF
```

## Payment status handling

Status values:

```text
PENDING   = payment created, waiting for IPN confirmation
SUCCESS   = IPN confirmed success, backend may already have created ebook_loan
FAILED    = provider confirmed failed payment
EXPIRED   = payment expired, not currently automated in frontend flow unless backend returns it
CANCELLED = reserved for future cancel flow
```

UI behavior:

```text
PENDING:
  Show "Đang xác nhận thanh toán..."
  Poll status.

SUCCESS:
  Show success.
  Stop polling.
  Enable "Đọc ebook" or redirect to ebook detail/reader entry.

FAILED:
  Show failed payment.
  Stop polling.
  Allow user to create a new payment attempt.

EXPIRED/CANCELLED:
  Stop polling.
  Show expired/cancelled state.
  Allow user to create a new payment attempt.
```

## Polling rule

Recommended behavior on return page:

```text
Poll every 2 seconds.
Stop after 60 seconds or 30 attempts.
Stop immediately when status is SUCCESS, FAILED, EXPIRED, or CANCELLED.
If still PENDING after timeout, show "Thanh toán đang được xác nhận, vui lòng thử tải lại sau".
```

Important:

```text
ReturnUrl query params from VNPAY are display hints only.
Do not trust query vnp_ResponseCode to mark success.
Only trust GET /api/payments/{id} or /by-code/{paymentCode}.
```

## Error handling

Common backend errors:

```text
401 UNAUTHORIZED:
  User session expired. Ask user to login again.

400 MISSING_IDEMPOTENCY_KEY:
  Frontend forgot Idempotency-Key on POST /api/payments.

400 INVALID_PAYMENT_LOCALE:
  locale must be vn or en.

400 INVALID_PAYMENT_BANK_CODE:
  bankCode must be supported by backend, or send null.

404 EBOOK_NOT_FOUND:
  Ebook target does not exist.

409 EBOOK_NOT_AVAILABLE:
  Ebook is inactive/unavailable.

409 EBOOK_DOES_NOT_REQUIRE_PAYMENT:
  Ebook is free, do not create payment.

409 EBOOK_ALREADY_BORROWED:
  User already has active access.

409 PAYMENT_ALREADY_PENDING:
  Existing pending payment for this ebook. Frontend should fetch existing status if it has paymentCode/paymentId, or ask user to continue existing attempt.

409 PAYMENT_ALREADY_SUCCESS:
  Ebook already paid. Frontend should show access/read CTA.

409 EBOOK_LICENSE_NOT_AVAILABLE:
  No license slot currently available.
```

Error response shape:

```json
{
  "success": false,
  "code": "PAYMENT_ALREADY_PENDING",
  "message": "Bạn đã có giao dịch thanh toán đang chờ xử lý cho ebook này",
  "timestamp": "2026-06-13T20:49:00",
  "traceId": "..."
}
```

## Frontend pseudo flow

```ts
async function startEbookPayment(bookEbookId: number) {
  const idempotencyKey = crypto.randomUUID();

  const response = await api.post(
    "/api/payments",
    {
      purpose: "EBOOK_PAYMENT",
      targetType: "BOOK_EBOOK",
      targetId: bookEbookId,
      provider: "VNPAY",
      bankCode: null,
      locale: "vn"
    },
    {
      headers: { "Idempotency-Key": idempotencyKey }
    }
  );

  sessionStorage.setItem("lastPaymentCode", response.data.data.paymentCode);
  window.location.href = response.data.data.paymentUrl;
}
```

```ts
async function pollPaymentByCode(paymentCode: string) {
  for (let attempt = 0; attempt < 30; attempt++) {
    const response = await api.get(`/api/payments/by-code/${paymentCode}`);
    const payment = response.data.data;

    if (["SUCCESS", "FAILED", "EXPIRED", "CANCELLED"].includes(payment.status)) {
      return payment;
    }

    await sleep(2000);
  }

  return null;
}
```

## Local testing note

Local payment page can redirect to VNPAY sandbox, but IPN needs public HTTPS.

For full end-to-end sandbox:

```text
ngrok/cloudflare tunnel -> localhost:8080
VNPAY_IPN_URL=https://your-tunnel-domain/api/payments/ipn/vnpay
```

Without tunnel:

```text
Frontend can create payment and open VNPAY sandbox.
But payment may stay PENDING because VNPAY cannot call localhost IPN.
```

## Acceptance criteria

```text
Frontend creates payment with Idempotency-Key.
Frontend redirects to VNPAY paymentUrl.
Return page reads vnp_TxnRef/paymentCode.
Return page polls /api/payments/by-code/{paymentCode}.
Frontend never trusts ReturnUrl query params as final payment success.
Frontend stops polling on terminal status.
Frontend handles PENDING timeout clearly.
Frontend does not call /api/payments/ipn/vnpay.
```
