# Frontend Plan: Payment Receipts and Admin Payment Dashboard

Backend now exposes payment receipt APIs for users and payment management APIs for admin.

## Goals

Implement frontend features for:

```text
1. User receipt list after successful payment.
2. User receipt detail / printable invoice view.
3. Admin payment dashboard summary.
4. Admin payment transaction list with filters.
5. Admin receipt detail for support/accounting.
```

## User APIs

### List My Receipts

```http
GET /api/payments/receipts?page=0&size=20
Authorization: Bearer <member token>
```

Response:

```ts
type PaymentReceiptResponse = {
  paymentId: number;
  receiptNumber: string;
  paymentCode: string;
  memberId: number;
  memberName: string;
  memberEmail: string;
  provider: string;
  providerTransactionId: string | null;
  providerResponseCode: string | null;
  providerTransactionStatus: string | null;
  purpose: string;
  targetType: string;
  targetId: number;
  itemTitle: string;
  amount: number;
  currency: string;
  status: string;
  paidAt: string;
  createdAt: string;
};
```

Only successful payments are returned.

### Receipt Detail

```http
GET /api/payments/receipts/{paymentCode}
Authorization: Bearer <member token>
```

Use this for the printable receipt page.

## Admin APIs

### Payment Summary

```http
GET /api/admin/payments/summary
Authorization: Bearer <admin token>
```

Response:

```ts
type PaymentDashboardSummaryResponse = {
  totalPayments: number;
  successPayments: number;
  pendingPayments: number;
  failedPayments: number;
  totalRevenue: number;
  todayRevenue: number;
  todaySuccessPayments: number;
  currency: string;
  generatedAt: string;
};
```

### Admin Payment List

```http
GET /api/admin/payments?q=&status=&paidFrom=&paidTo=&page=0&size=20
Authorization: Bearer <admin token>
```

Supported filters:

```text
q: payment code, provider transaction id, member name/email, book title, numeric id
status: PENDING | SUCCESS | FAILED | CANCELLED | EXPIRED
paidFrom: YYYY-MM-DD or ISO instant
paidTo: YYYY-MM-DD or ISO instant
```

Response row:

```ts
type AdminPaymentRowResponse = {
  paymentId: number;
  paymentCode: string;
  memberId: number;
  memberName: string;
  memberEmail: string;
  provider: string;
  providerTransactionId: string | null;
  purpose: string;
  targetType: string;
  targetId: number;
  itemTitle: string;
  amount: number;
  currency: string;
  status: string;
  providerResponseCode: string | null;
  providerTransactionStatus: string | null;
  paidAt: string | null;
  expiredAt: string;
  createdAt: string;
  updatedAt: string;
};
```

### Admin Receipt Detail

```http
GET /api/admin/payments/receipts/{paymentCode}
Authorization: Bearer <admin token>
```

Returns the same `PaymentReceiptResponse`, but admin can access any member payment.

## User UI

### Add Receipt List Page

Suggested route:

```text
/me/receipts
```

Columns:

```text
Receipt number
Item
Amount
Provider
Paid at
Action
```

Action:

```text
View receipt
Print / Download PDF from browser print
```

### Add Receipt Detail Page

Suggested route:

```text
/me/receipts/{paymentCode}
```

Show:

```text
Receipt number
Payment code
Provider transaction id
Member name/email
Item title
Purpose
Amount/currency
Payment status
Paid at
Provider response/status
```

Add a print button:

```ts
window.print();
```

Use print CSS to hide navigation/sidebar/buttons.

## Admin UI

### Add Payment Dashboard Cards

Suggested route:

```text
/admin/payments
```

Top cards:

```text
Total revenue
Today revenue
Successful payments
Pending payments
Failed payments
Today successful payments
```

### Add Payment Table

Columns:

```text
Payment code
Member
Item
Amount
Status
Provider
Provider transaction id
Paid at
Created at
Actions
```

Actions:

```text
View receipt
View member
View book
```

### Filters

Add:

```text
Search input q
Status select
Paid from date
Paid to date
Page size
```

Status options:

```text
All
PENDING
SUCCESS
FAILED
CANCELLED
EXPIRED
```

## Formatting Rules

Amount:

```ts
new Intl.NumberFormat('vi-VN', {
  style: 'currency',
  currency: receipt.currency ?? 'VND',
}).format(receipt.amount)
```

Dates:

```ts
new Intl.DateTimeFormat('vi-VN', {
  dateStyle: 'medium',
  timeStyle: 'short',
}).format(new Date(value))
```

Status badge:

```text
SUCCESS -> green
PENDING -> amber
FAILED/CANCELLED/EXPIRED -> red/gray
```

## Important Behavior

Do not generate receipts for pending payments.

User receipt APIs only return `SUCCESS` payments. Admin list returns all statuses.

Receipt detail should handle 404:

```text
Receipt not found
Payment may not be completed yet
```

## Acceptance Criteria

```text
User can see list of paid receipts.
User can open a receipt detail page.
User can print receipt from browser.
Admin can see payment summary cards.
Admin can search/filter payments.
Admin can open any receipt by paymentCode.
Pending payments do not appear in user receipt list.
Amount and dates are formatted correctly.
```

## Manual Test

```text
1. Complete a VNPAY payment successfully.
2. Login as that member.
3. Open /me/receipts.
4. Confirm the successful payment appears.
5. Open receipt detail and print preview.
6. Login as admin.
7. Open /admin/payments.
8. Confirm summary revenue includes the payment.
9. Search by paymentCode and member email.
10. Open admin receipt detail.
```
