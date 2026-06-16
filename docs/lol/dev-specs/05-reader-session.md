# Spec 05: Ebook Reader Session And Signed URL

## Mục tiêu

Chỉ cho user đọc PDF khi đã có `ebook_loan ACTIVE`.

Reader không nhận file trực tiếp. Backend cấp signed URL ngắn hạn sau khi check loan và reading session.

## Database

### `ebook_reading_sessions`

Các field bắt buộc:

```text
id
session_token_hash
member_id
book_id
book_ebook_id
loan_id
status
session_expires_at
last_heartbeat_at
closed_at
expired_at
revoked_at
revoke_reason
ip_address
user_agent_hash
version
created_at
updated_at
```

Status:

```text
ACTIVE
EXPIRED
CLOSED
REVOKED
```

Index cơ bản:

```sql
CREATE INDEX idx_reading_sessions_status_expires
ON ebook_reading_sessions(status, session_expires_at);

CREATE INDEX idx_reading_sessions_member_status
ON ebook_reading_sessions(member_id, status);

CREATE INDEX idx_reading_sessions_loan_status
ON ebook_reading_sessions(loan_id, status);
```

Partial index production:

```sql
CREATE INDEX idx_reading_sessions_active_expires
ON ebook_reading_sessions(session_expires_at)
WHERE status = 'ACTIVE';

CREATE INDEX idx_reading_sessions_active_member
ON ebook_reading_sessions(member_id, session_expires_at)
WHERE status = 'ACTIVE';

CREATE INDEX idx_reading_sessions_active_loan
ON ebook_reading_sessions(loan_id, session_expires_at)
WHERE status = 'ACTIVE';
```

## Token rule

```text
rawToken = random 256-bit token
session_token_hash = HMAC-SHA256(rawToken, READING_SESSION_SECRET)
```

Raw token chỉ trả về một lần khi tạo session.
Database chỉ lưu hash.

## Redis active cache

Key:

```text
reading_session:{sessionTokenHash}
```

Value tối thiểu:

```json
{
  "sessionId": 501,
  "memberId": 21,
  "bookId": 123,
  "bookEbookId": 1001,
  "loanId": 7001,
  "sessionExpiresAt": "2026-06-13T10:18:00Z",
  "loanExpiresAt": "2026-06-27T10:03:00Z",
  "status": "ACTIVE"
}
```

TTL:

```text
min(session_expires_at - now, loan.expired_at - now)
```

## API tạo session

```http
POST /api/ebooks/{bookId}/reading-sessions
Authorization: Bearer <access_token>
```

Check:

```text
User có ebook_loan ACTIVE không?
Loan chưa hết hạn không?
BookEbook ACTIVE không?
```

Response:

```json
{
  "sessionId": 501,
  "sessionToken": "raw-token-only-return-once",
  "bookId": 123,
  "bookEbookId": 1001,
  "loanId": 7001,
  "loanExpiresAt": "2026-06-27T10:03:00",
  "sessionExpiresAt": "2026-06-13T10:18:00",
  "serverNow": "2026-06-13T10:03:00"
}
```

Flow:

```text
1. Check loan ACTIVE.
2. Generate raw token.
3. Hash raw token.
4. Insert PostgreSQL session ACTIVE.
5. Set Redis key TTL 15 phút hoặc tới loan.expired_at.
6. Return raw token một lần.
```

## API lấy signed URL

```http
GET /api/ebooks/{bookId}/reader/content
Authorization: Bearer <access_token>
X-Reading-Session: <raw-session-token>
```

Không dùng:

```http
GET /api/ebooks/{bookId}/reader/content?sessionToken=...
```

Backend:

```text
Hash raw session token.
Check Redis reading_session:{hash}.
Nếu Redis miss thì fallback PostgreSQL.
Nếu DB còn ACTIVE và chưa hết hạn thì nạp lại Redis.
Check JWT member_id khớp session.
Check loan ACTIVE.
Check loan.expired_at > now.
Generate Cloudinary signed URL sống 3-5 phút.
```

Response:

```json
{
  "signedUrl": "https://res.cloudinary.com/...",
  "expiresAt": "2026-06-13T10:08:00Z",
  "serverNow": "2026-06-13T10:03:00Z"
}
```

## API refresh

```http
POST /api/ebooks/reading-sessions/{sessionId}/refresh
Authorization: Bearer <access_token>
X-Reading-Session: <raw-session-token>
```

Backend:

```text
Hash raw token.
Check JWT + member_id khớp session.
Check loan ACTIVE.
sessionExpiresAt = min(now + 15 phút, loan.expired_at).
Redis EXPIRE reading_session:{hash}.
PostgreSQL chỉ update last_heartbeat_at nếu quá throttle window 5-10 phút.
```

## API close

```http
POST /api/ebooks/reading-sessions/{sessionId}/close
Authorization: Bearer <access_token>
X-Reading-Session: <raw-session-token>
```

Backend:

```text
Hash raw token.
Check JWT + member_id khớp session.
Update PostgreSQL status = CLOSED, closed_at = now.
Delete Redis reading_session:{hash}.
```

## Acceptance criteria

```text
User chưa có loan ACTIVE không tạo được session.
Loan hết hạn không tạo được session.
Raw token không lưu DB.
Signed URL chỉ cấp khi session ACTIVE và loan ACTIVE.
Token trên query string không được hỗ trợ.
Redis hit không cần query DB cho mỗi request đọc.
Redis miss fallback DB đúng.
Session CLOSED / EXPIRED / REVOKED không được nạp lại Redis.
Refresh không vượt quá loan.expired_at.
```
