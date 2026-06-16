# Spec 06: Expiration And Cleanup Jobs

## Mục tiêu

Đảm bảo payment, loan và reading session tự hết hạn đúng, chạy lại nhiều lần vẫn an toàn.

## PaymentExpireJob

Nhiệm vụ:

```text
Tìm payment_transactions status = PENDING và expired_at <= now.
Mark status = EXPIRED.
Không tạo loan.
Không release loan.
```

Query pattern:

```sql
SELECT id
FROM payment_transactions
WHERE status = 'PENDING'
  AND expired_at <= CURRENT_TIMESTAMP
ORDER BY expired_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

Update:

```sql
UPDATE payment_transactions
SET status = 'EXPIRED',
    updated_at = CURRENT_TIMESTAMP
WHERE id = :paymentId
  AND status = 'PENDING';
```

## EbookLoanExpirationJob

Nhiệm vụ:

```text
Tìm ebook_loans ACTIVE và expired_at <= now.
Mark loan EXPIRED.
Revoke ebook_reading_sessions ACTIVE liên quan.
Delete Redis reading session keys liên quan nếu có thể.
```

Query pattern:

```sql
SELECT id
FROM ebook_loans
WHERE status = 'ACTIVE'
  AND expired_at <= CURRENT_TIMESTAMP
ORDER BY expired_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

Update loan:

```sql
UPDATE ebook_loans
SET status = 'EXPIRED',
    updated_at = CURRENT_TIMESTAMP
WHERE id = :loanId
  AND status = 'ACTIVE';
```

Update sessions:

```sql
UPDATE ebook_reading_sessions
SET status = 'REVOKED',
    revoked_at = CURRENT_TIMESTAMP,
    revoke_reason = 'LOAN_EXPIRED',
    updated_at = CURRENT_TIMESTAMP
WHERE loan_id = :loanId
  AND status = 'ACTIVE';
```

## ReadingSessionExpirationWorker

Nhiệm vụ:

```text
Tìm ebook_reading_sessions ACTIVE và session_expires_at <= now.
Mark EXPIRED.
Không ảnh hưởng loan.
```

Query pattern:

```sql
SELECT id
FROM ebook_reading_sessions
WHERE status = 'ACTIVE'
  AND session_expires_at <= CURRENT_TIMESTAMP
ORDER BY session_expires_at
LIMIT 500
FOR UPDATE SKIP LOCKED;
```

Update:

```sql
UPDATE ebook_reading_sessions
SET status = 'EXPIRED',
    expired_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :sessionId
  AND status = 'ACTIVE';
```

## ReadingSessionRetentionCleanupJob

MVP retention:

```text
90 ngày.
```

Nhiệm vụ:

```text
Xóa hoặc archive session CLOSED / EXPIRED / REVOKED quá retention.
Chạy batch nhỏ.
Không xóa ACTIVE.
```

Query pattern:

```sql
DELETE FROM ebook_reading_sessions
WHERE id IN (
    SELECT id
    FROM ebook_reading_sessions
    WHERE status IN ('CLOSED', 'EXPIRED', 'REVOKED')
      AND updated_at < CURRENT_TIMESTAMP - INTERVAL '90 days'
    ORDER BY updated_at
    LIMIT 500
);
```

Nếu traffic lớn:

```text
Partition ebook_reading_sessions theo created_at từng tháng.
Drop/detach partition cũ thay vì bulk delete lớn.
```

## Scheduling đề xuất

```text
PaymentExpireJob: mỗi 5 phút.
EbookLoanExpirationJob: mỗi 5-15 phút.
ReadingSessionExpirationWorker: mỗi 1-3 phút.
ReadingSessionRetentionCleanupJob: mỗi ngày, giờ thấp điểm.
```

## Acceptance criteria

```text
Job idempotent.
Chạy lại nhiều lần không đổi sai trạng thái.
Không lock bảng lớn.
Dùng batch + FOR UPDATE SKIP LOCKED.
Không xóa session ACTIVE.
Loan expired revoke session liên quan.
Payment expired không tạo/release loan.
```
