# SE330 Payment Service (Backend)

Standalone payment backend that powers the **separate "Payment" tab** shown in the
system diagram (App → Renew loan → Payment, App → Read online → Payment).

It is designed to plug straight into the existing `SE330_FE` Next.js frontend:

- Response envelope matches `ApiResponse<T>` (`success`, `message`, `data`, `code`, `timestamp`, ...) used by `src/types/api.type.ts`.
- Auth uses the same Bearer access token issued by the main library API (`/api/auth/login`), verified with a shared `JWT_SECRET`.
- CORS is restricted to the frontend origin (`FRONTEND_URL`).

## Endpoints

All endpoints require `Authorization: Bearer <accessToken>`.

| Method | Path                          | Description                                   |
| ------ | ----------------------------- | ---------------------------------------------- |
| POST   | `/api/payments`                | Create a payment order                        |
| GET    | `/api/payments`                | List current user's payment orders            |
| GET    | `/api/payments/:paymentId`     | Get a single payment order                    |
| POST   | `/api/payments/:paymentId/confirm` | Submit payment method & process payment   |
| POST   | `/api/payments/:paymentId/cancel`  | Cancel a pending payment                   |
| GET    | `/api/health`                  | Health check (no auth)                        |

### Create payment — `POST /api/payments`

```json
{
  "purpose": "EBOOK_RENEWAL",
  "loanId": 123,
  "bookId": 45,
  "bookTitle": "Clean Code"
}
```

`purpose` ∈ `EBOOK_RENEWAL | EBOOK_LOAN_FEE | FINE`. Amount is derived from a server-side
price table unless `amount` is provided (required for `FINE`).

### Confirm payment — `POST /api/payments/:paymentId/confirm`

```json
{
  "method": "CARD",
  "cardNumber": "4242 4242 4242 4242",
  "cardHolder": "NGUYEN VAN A",
  "expiry": "12/27",
  "cvv": "123"
}
```

`method` ∈ `CARD | WALLET | BANK_TRANSFER`. Card details are never stored — this is a mock gateway.

## Running locally

```bash
cd SE330_BE
cp .env.example .env   # adjust JWT_SECRET to match the main API, ports, etc.
npm install
npm run dev             # http://localhost:8081
```

Build for production:

```bash
npm run build
npm start
```

## Frontend wiring

In the FE project, set:

```
NEXT_PUBLIC_PAYMENT_API_URL=http://localhost:8081
```

The "Renew" button in `UserEbookLoansPage` opens `/payment?purpose=EBOOK_RENEWAL&loanId=...`
in a new tab (`src/features/payment/components/PaymentPage.tsx`), which creates a payment
order, lets the user pick a method, and confirms it. When that tab closes, the original
tab attempts the real `renewEbook` call.

## Notes / production TODO

- Replace the in-memory `Map` store in `src/services/payment.service.ts` with a database.
- Replace the mock gateway (`confirmPaymentOrder`) with a real provider (VNPay, Momo, Stripe, ...).
- After a successful payment, call back into the main library API to actually perform
  the renewal / unlock the ebook, instead of relying on the FE re-calling `renewEbook`.
