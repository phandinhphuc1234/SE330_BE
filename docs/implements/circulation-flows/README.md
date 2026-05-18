# Circulation Flow Docs Index

Thư mục này triển khai chi tiết từ `../library_circulation_workflow_api_spec_revised.md`.

## Mapping với spec gốc

| Spec section | File triển khai |
|---|---|
| 1-3. Bức tranh, nguyên tắc, trạng thái | `00-circulation-roadmap.md` |
| 4. Flow 1 - Search catalogue và availability | `01-search-availability-flow.md` |
| 5. Flow 2 - Checkout / mượn sách | `02-checkout-borrow-flow.md` |
| 6. Flow 3 - Return / check-in / trả sách | `03-return-checkin-flow.md` |
| 7. Flow 4 - Renewal / gia hạn | `04-renewal-flow.md` |
| 8. Flow 5 - Hold / reservation queue | `05-hold-reservation-flow.md` |
| 9-12. Overdue, email notification, scheduled jobs, job log | `06-overdue-notification-jobs-flow.md` |
| 13-14. Fine, payment, lost, damaged, claims returned | `07-fines-lost-damaged-flow.md` |
| 15-19. Member/staff/admin policy, idempotency, audit | `08-security-idempotency-audit-policy.md` |

## Rule project được áp dụng trong toàn bộ doc

```text
Chỉ MEMBER được đứng tên mượn sách.
LIBRARIAN/ADMIN không tự mượn bằng tài khoản staff/admin.
LIBRARIAN/ADMIN chỉ thao tác nghiệp vụ cho MEMBER.
```

Vì vậy khi implement checkout/renew/hold/fine, cần phân biệt rõ:

```text
actor    = người gọi API, lấy từ JWT
borrower = bạn đọc đứng tên mượn, bắt buộc role MEMBER
```

