# Current Database Schema

This document describes the PostgreSQL schema after applying migrations in `src/main/resources/db/migration`.

## Overview

The schema supports a library management system with:

- Catalog: authors, categories, books, physical book copies
- Members and authentication: members, email verification tokens
- Circulation: borrow records, reservations
- Finance: fine configuration, payments
- Operations: audit logs, notifications, notification queue, system settings

Refresh tokens are not stored in PostgreSQL. They are stored in Redis by the application.

## Extensions

### `pgcrypto`

Created by `V1`:

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
```

The current migrations do not directly use `gen_random_uuid()`, but the extension is available.

## Tables

### `authors`

Stores book authors.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `name` | `VARCHAR(100)` | No | | Unique |
| `bio` | `TEXT` | Yes | | |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `PRIMARY KEY (id)`
- `uq_author_name UNIQUE (name)`

### `categories`

Stores book categories.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `name` | `VARCHAR(50)` | No | | Unique |
| `description` | `VARCHAR(255)` | Yes | | |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `PRIMARY KEY (id)`
- `uq_category_name UNIQUE (name)`

### `books`

Stores catalog-level book data. Physical inventory is tracked separately in `book_copies`.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `title` | `VARCHAR(255)` | No | | |
| `isbn` | `VARCHAR(20)` | No | | Unique |
| `total_copies` | `INT` | No | `1` | Must be greater than 0 |
| `available_copies` | `INT` | No | `1` | Must be between 0 and `total_copies` |
| `published_date` | `DATE` | Yes | | |
| `language` | `VARCHAR(10)` | No | `'vi'` | |
| `edition` | `VARCHAR(50)` | Yes | | |
| `category_id` | `BIGINT` | Yes | | FK to `categories(id)`, `ON DELETE SET NULL` |
| `version` | `BIGINT` | No | `0` | Optimistic locking |
| `deleted_at` | `TIMESTAMP` | Yes | | Soft delete marker |
| `deleted_by` | `BIGINT` | Yes | | FK to `members(id)`, `ON DELETE SET NULL` |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `PRIMARY KEY (id)`
- `uq_book_isbn UNIQUE (isbn)`
- `chk_available_copies CHECK (available_copies >= 0)`
- `chk_total_copies CHECK (total_copies > 0)`
- `chk_available_lte_total CHECK (available_copies <= total_copies)`
- `fk_book_deleted_by FOREIGN KEY (deleted_by) REFERENCES members(id) ON DELETE SET NULL`

Indexes:

- `idx_books_category ON books(category_id)`
- `idx_books_active ON books(id) WHERE deleted_at IS NULL`
- `idx_books_fulltext ON books USING gin(to_tsvector('simple', title || ' ' || coalesce(isbn, '')))`

### `book_authors`

Join table for the many-to-many relation between books and authors.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `book_id` | `BIGINT` | No | | FK to `books(id)`, `ON DELETE CASCADE` |
| `author_id` | `BIGINT` | No | | FK to `authors(id)`, `ON DELETE CASCADE` |

Constraints:

- `PRIMARY KEY (book_id, author_id)`

### `members`

Stores application users and library members.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `full_name` | `VARCHAR(100)` | No | | |
| `email` | `VARCHAR(100)` | No | | Unique |
| `password` | `VARCHAR(255)` | No | | BCrypt hash |
| `phone` | `VARCHAR(20)` | Yes | | |
| `role` | `VARCHAR(20)` | No | `'MEMBER'` | `MEMBER`, `LIBRARIAN`, `ADMIN` |
| `max_borrow_limit` | `INT` | No | `5` | Must be greater than 0 |
| `membership_expires_at` | `TIMESTAMP` | Yes | | Null means no expiration |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |
| `status` | `VARCHAR(20)` | No | `'PENDING'` from V4, data migrated by V6 | Allowed values changed by V6 |

Current status values after `V6`:

- `PENDING_VERIFICATION`
- `ACTIVE`
- `INACTIVE`
- `BANNED`

Constraints:

- `PRIMARY KEY (id)`
- `uq_member_email UNIQUE (email)`
- `chk_member_role CHECK (role IN ('MEMBER', 'LIBRARIAN', 'ADMIN'))`
- `chk_borrow_limit CHECK (max_borrow_limit > 0)`
- `chk_member_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'INACTIVE', 'BANNED'))`

Indexes:

- `idx_members_email ON members(email)`
- `idx_members_active ON members(id) WHERE status = 'ACTIVE'`
- `idx_members_status ON members(status)`

Migration notes:

- `is_active` was created in `V1`.
- `status` was added in `V4`.
- `is_active` was dropped in `V5`.
- `V6` aligned `status` values with the Java `MemberStatus` enum.

### `fine_configs`

Stores fine rate configuration over time.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `rate_per_day` | `DECIMAL(10,2)` | No | | Must be greater than 0 |
| `currency` | `VARCHAR(3)` | No | `'VND'` | |
| `effective_from` | `DATE` | No | | |
| `effective_until` | `DATE` | Yes | | Null means currently active |
| `created_by` | `BIGINT` | Yes | | FK to `members(id)`, `ON DELETE SET NULL` |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `PRIMARY KEY (id)`
- `chk_fine_rate_positive CHECK (rate_per_day > 0)`
- `chk_fine_dates CHECK (effective_until IS NULL OR effective_until > effective_from)`

Seed data:

- `5000 VND` per day from `CURRENT_DATE`

### `book_copies`

Stores physical book copies.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `book_id` | `BIGINT` | No | | FK to `books(id)`, `ON DELETE CASCADE` |
| `barcode` | `VARCHAR(100)` | No | | Unique |
| `status` | `VARCHAR(20)` | No | `'AVAILABLE'` | See allowed values |
| `condition` | `VARCHAR(50)` | Yes | | |
| `location` | `VARCHAR(100)` | Yes | | |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |

Allowed status values:

- `AVAILABLE`
- `BORROWED`
- `RESERVED`
- `LOST`
- `DAMAGED`

Constraints:

- `PRIMARY KEY (id)`
- `barcode UNIQUE`
- `chk_copy_status CHECK (status IN ('AVAILABLE', 'BORROWED', 'RESERVED', 'LOST', 'DAMAGED'))`

Indexes:

- `idx_book_copies_book ON book_copies(book_id)`
- `idx_book_copies_status ON book_copies(status)`

### `borrow_records`

Stores book borrowing history. After `V2`, each borrow record points to a physical copy, not directly to a book.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `member_id` | `BIGINT` | No | | FK to `members(id)` |
| `borrowed_at` | `TIMESTAMP` | No | `NOW()` | |
| `due_date` | `TIMESTAMP` | No | | Must be after `borrowed_at` |
| `returned_at` | `TIMESTAMP` | Yes | | Must be null or after `borrowed_at` |
| `fine_amount` | `DECIMAL(10,2)` | No | `0` | Must be non-negative |
| `fine_config_id` | `BIGINT` | Yes | | FK to `fine_configs(id)` |
| `fine_calculated_at` | `TIMESTAMP` | Yes | | |
| `fine_paid_at` | `TIMESTAMP` | Yes | | |
| `fine_waived_by` | `BIGINT` | Yes | | FK to `members(id)` |
| `fine_waived_reason` | `VARCHAR(255)` | Yes | | |
| `status` | `VARCHAR(20)` | No | `'BORROWED'` | See allowed values |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |
| `book_copy_id` | `BIGINT` | No | | FK to `book_copies(id)` |

Allowed status values:

- `BORROWED`
- `RETURNED`
- `OVERDUE`
- `LOST`

Constraints:

- `PRIMARY KEY (id)`
- `chk_borrow_status CHECK (status IN ('BORROWED', 'RETURNED', 'OVERDUE', 'LOST'))`
- `chk_due_after_borrowed CHECK (due_date > borrowed_at)`
- `chk_returned_after_borrowed CHECK (returned_at IS NULL OR returned_at >= borrowed_at)`
- `chk_fine_non_negative CHECK (fine_amount >= 0)`
- `fk_borrow_copy FOREIGN KEY (book_copy_id) REFERENCES book_copies(id)`

Indexes:

- `idx_borrow_member ON borrow_records(member_id)`
- `idx_borrow_due_date ON borrow_records(due_date)`
- `idx_borrow_member_status ON borrow_records(member_id, status)`
- `idx_borrow_fine_unpaid ON borrow_records(fine_paid_at) WHERE fine_amount > 0 AND fine_paid_at IS NULL`
- `uq_active_borrow_copy UNIQUE ON borrow_records(book_copy_id) WHERE status = 'BORROWED'`
- `idx_borrow_overdue ON borrow_records(due_date, status) WHERE status = 'BORROWED'`

Migration notes:

- `book_id` existed in `V1`.
- `V2` dropped `book_id` and added `book_copy_id`.
- Indexes based on dropped `book_id` no longer apply to the current schema.

### `reservations`

Stores reservation queue entries for books.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `member_id` | `BIGINT` | No | | FK to `members(id)` |
| `book_id` | `BIGINT` | No | | FK to `books(id)` |
| `reserved_at` | `TIMESTAMP` | No | `NOW()` | |
| `expires_at` | `TIMESTAMP` | No | `NOW() + INTERVAL '3 days'` | Must be after `reserved_at` |
| `notified_at` | `TIMESTAMP` | Yes | | |
| `status` | `VARCHAR(20)` | No | `'WAITING'` | See allowed values |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |
| `queue_position` | `INT` | Yes | | Added by `V2` |

Allowed status values:

- `WAITING`
- `NOTIFIED`
- `FULFILLED`
- `CANCELLED`
- `EXPIRED`

Constraints:

- `PRIMARY KEY (id)`
- `chk_reservation_status CHECK (status IN ('WAITING', 'NOTIFIED', 'FULFILLED', 'CANCELLED', 'EXPIRED'))`
- `chk_expires_after_reserved CHECK (expires_at > reserved_at)`

Indexes:

- `idx_reservation_member ON reservations(member_id)`
- `idx_reservation_book ON reservations(book_id)`
- `idx_reservation_active ON reservations(book_id, status) WHERE status IN ('WAITING', 'NOTIFIED')`
- `uq_reservation_queue UNIQUE ON reservations(book_id, queue_position)`
- `idx_reservation_queue_order ON reservations(book_id, status, reserved_at)`

Migration notes:

- `V1` had `uq_active_reservation UNIQUE (member_id, book_id)`.
- `V2` dropped that unique constraint and replaced it with queue-based reservation ordering.

### `payments`

Stores payment records for fines or future payable items.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `member_id` | `BIGINT` | No | | FK to `members(id)` |
| `amount` | `DECIMAL(10,2)` | No | | Must be greater than 0 |
| `currency` | `VARCHAR(3)` | No | `'VND'` | |
| `method` | `VARCHAR(20)` | No | | |
| `status` | `VARCHAR(20)` | No | `'PENDING'` | `PENDING`, `SUCCESS`, `FAILED` |
| `paid_at` | `TIMESTAMP` | Yes | | |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `PRIMARY KEY (id)`
- `chk_payment_amount CHECK (amount > 0)`
- `chk_payment_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED'))`

Indexes:

- `idx_payment_member ON payments(member_id)`

### `audit_logs`

Stores operational audit events.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `user_id` | `BIGINT` | Yes | | FK to `members(id)` |
| `action` | `VARCHAR(50)` | No | | |
| `entity_type` | `VARCHAR(50)` | Yes | | |
| `entity_id` | `BIGINT` | Yes | | |
| `metadata` | `JSONB` | Yes | `'{}'::jsonb` | Extra audit context |
| `trace_id` | `VARCHAR(100)` | Yes | | Request trace ID, usually from `X-Trace-Id` |
| `actor_role` | `VARCHAR(20)` | Yes | | Role at the time of the action |
| `ip_address` | `VARCHAR(45)` | Yes | | IPv4/IPv6 client address |
| `user_agent` | `VARCHAR(255)` | Yes | | Client user agent |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `chk_audit_actor_role CHECK (actor_role IS NULL OR actor_role IN ('MEMBER', 'ADMIN', 'LIBRARIAN'))`

Indexes:

- `idx_audit_user ON audit_logs(user_id)`
- `idx_audit_entity ON audit_logs(entity_type, entity_id)`
- `idx_audit_created_at ON audit_logs(created_at DESC)`
- `idx_audit_action ON audit_logs(action)`
- `idx_audit_trace_id ON audit_logs(trace_id)`

### `notifications`

Stores in-app notifications.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `member_id` | `BIGINT` | No | | FK to `members(id)` |
| `title` | `VARCHAR(255)` | No | | |
| `content` | `TEXT` | No | | |
| `type` | `VARCHAR(50)` | No | | |
| `is_read` | `BOOLEAN` | No | `FALSE` | |
| `sent_at` | `TIMESTAMP` | No | `NOW()` | |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |

Indexes:

- `idx_notification_member ON notifications(member_id)`
- `idx_notification_unread ON notifications(member_id, is_read)`

### `notification_queue`

Stores async notification delivery jobs.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `member_id` | `BIGINT` | Yes | | FK to `members(id)` |
| `notification_id` | `BIGINT` | Yes | | FK to `notifications(id)` |
| `channel` | `VARCHAR(20)` | No | | |
| `status` | `VARCHAR(20)` | No | `'PENDING'` | `PENDING`, `SENT`, `FAILED` |
| `retry_count` | `INT` | Yes | `0` | |
| `scheduled_at` | `TIMESTAMP` | No | `NOW()` | |
| `sent_at` | `TIMESTAMP` | Yes | | |

Constraints:

- `PRIMARY KEY (id)`
- `chk_queue_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))`

Indexes:

- `idx_queue_status ON notification_queue(status)`

### `system_settings`

Stores configurable system values.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `key` | `VARCHAR(100)` | No | | Primary key |
| `value` | `VARCHAR(255)` | No | | |
| `description` | `TEXT` | Yes | | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |

Seed data:

| Key | Value | Description |
|---|---:|---|
| `BORROW_DAYS_DEFAULT` | `14` | Default borrow days |
| `MAX_RESERVATION_DAYS` | `3` | Reservation expiry |
| `ENABLE_EMAIL_NOTIFICATION` | `true` | Enable email |

### `email_verifications`

Stores email verification tokens for account activation.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `member_id` | `BIGINT` | No | | FK to `members(id)`, `ON DELETE CASCADE` |
| `token` | `VARCHAR(255)` | No | | Unique |
| `expires_at` | `TIMESTAMP` | No | | |
| `is_used` | `BOOLEAN` | No | `FALSE` | |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `used_at` | `TIMESTAMP` | Yes | | Time when the token was used |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | Last update timestamp |
| `last_sent_at` | `TIMESTAMP` | No | Backfilled from `created_at` | Last time the verification email was sent |

Constraints:

- `PRIMARY KEY (id)`
- `token UNIQUE`
- `chk_email_used_at_after_created CHECK (used_at IS NULL OR used_at >= created_at)`
- `chk_email_updated_at_after_created CHECK (updated_at >= created_at)`
- `chk_email_last_sent_at_after_created CHECK (last_sent_at >= created_at)`

Indexes:

- `idx_email_token ON email_verifications(token)`
- `idx_email_member ON email_verifications(member_id)`
- `uq_email_active_token UNIQUE ON email_verifications(member_id) WHERE is_used = FALSE`
- `idx_email_token_active ON email_verifications(token) WHERE is_used = FALSE`
- `idx_email_expiry ON email_verifications(expires_at)`

## Relationship Summary

- `books.category_id` -> `categories.id`
- `books.deleted_by` -> `members.id`
- `book_authors.book_id` -> `books.id`
- `book_authors.author_id` -> `authors.id`
- `book_copies.book_id` -> `books.id`
- `borrow_records.member_id` -> `members.id`
- `borrow_records.book_copy_id` -> `book_copies.id`
- `borrow_records.fine_config_id` -> `fine_configs.id`
- `borrow_records.fine_waived_by` -> `members.id`
- `reservations.member_id` -> `members.id`
- `reservations.book_id` -> `books.id`
- `fine_configs.created_by` -> `members.id`
- `payments.member_id` -> `members.id`
- `audit_logs.user_id` -> `members.id`
- `notifications.member_id` -> `members.id`
- `notification_queue.member_id` -> `members.id`
- `notification_queue.notification_id` -> `notifications.id`
- `email_verifications.member_id` -> `members.id`

## Important Notes

- Java entity coverage is currently narrower than the database schema. The codebase currently has entities for `members` and `email_verifications`; many catalog/circulation tables exist only at migration level.
- `members.status` is the current account-state field. Do not use `is_active`; it was dropped in `V5`.
- `borrow_records` currently references `book_copies`, not `books`, after the normalization change in `V2`.
- `refreshToken` is stored in Redis by application logic, not in PostgreSQL.
- `V4__add_member_status.sql` contains a comment header that says `V3__auth_email_verify_member_status.sql`, but the applied filename is `V4__add_member_status.sql`.
