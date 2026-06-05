# Current Database Schema

This document describes the PostgreSQL schema after applying all Flyway
migrations in `src/main/resources/db/migration` up to `V21`.

Refresh tokens and idempotency records are not stored in PostgreSQL. Refresh
tokens are stored in Redis, and idempotency state was moved from PostgreSQL to
Redis by `V20__drop_idempotency_records.sql`.

## Overview

The current schema supports:

- Catalog: authors, categories, books, book images, physical book copies
- Members and authentication: members, email verification tokens
- Circulation: borrow records, holds/reservations, renewal data
- Finance: fine configuration and payment records
- Operations: audit logs, notifications, notification queue, system settings,
  scheduled job logs
- Imports: asynchronous CSV book import jobs and row-level import errors

## Extensions

### `pgcrypto`

Created by `V1`:

```sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
```

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

Stores catalog-level book data. Physical inventory is tracked in
`book_copies`.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `title` | `VARCHAR(255)` | No | | |
| `isbn` | `VARCHAR(20)` | No | | Unique |
| `total_copies` | `INT` | No | `1` | Can be `0` after `V10` |
| `available_copies` | `INT` | No | `1` | Must be between `0` and `total_copies` |
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
- `chk_total_copies CHECK (total_copies >= 0)`
- `chk_available_copies CHECK (available_copies >= 0)`
- `chk_available_lte_total CHECK (available_copies <= total_copies)`
- `fk_book_deleted_by FOREIGN KEY (deleted_by) REFERENCES members(id) ON DELETE SET NULL`

Indexes:

- `idx_books_category ON books(category_id)`
- `idx_books_active ON books(id) WHERE deleted_at IS NULL`
- `idx_books_fulltext ON books USING gin(to_tsvector('simple', title || ' ' || coalesce(isbn, '')))`

Migration notes:

- `V10` changed book copy counters to allow `0` copies. A catalog book can
  exist before any physical `book_copies` are created.
- `V23` temporarily added optional `image_url` for catalog cover images.
- `V24` moved cover image metadata to `book_images` and dropped `books.image_url`.

### `book_authors`

Join table for the many-to-many relation between books and authors.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `book_id` | `BIGINT` | No | | FK to `books(id)`, `ON DELETE CASCADE` |
| `author_id` | `BIGINT` | No | | FK to `authors(id)`, `ON DELETE CASCADE` |

Constraints:

- `PRIMARY KEY (book_id, author_id)`

### `book_images`

Stores image metadata for catalog books. The image binary is stored by
Cloudinary; this table stores provider metadata and the delivery URL used by
the frontend.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `book_id` | `BIGINT` | No | | FK to `books(id)`, `ON DELETE CASCADE` |
| `provider` | `VARCHAR(50)` | No | `'CLOUDINARY'` | Currently only `CLOUDINARY` |
| `public_id` | `VARCHAR(512)` | No | | Cloudinary public ID/reference |
| `secure_url` | `VARCHAR(2048)` | No | | Stored HTTPS URL/reference; API response builds `coverImage` URLs from `public_id` and `format` |
| `asset_type` | `VARCHAR(50)` | No | `'COVER_FRONT'` | `COVER_FRONT`, `COVER_BACK`, `PREVIEW`, `OTHER` |
| `format` | `VARCHAR(20)` | Yes | | Example: `png`, `jpg`, `webp` |
| `width` | `INT` | Yes | | Must be positive when present |
| `height` | `INT` | Yes | | Must be positive when present |
| `bytes` | `BIGINT` | Yes | | Must be non-negative when present |
| `alt_text` | `VARCHAR(255)` | Yes | | Accessibility text |
| `sort_order` | `INT` | No | `0` | Display order for multiple images |
| `is_primary` | `BOOLEAN` | No | `FALSE` | Primary cover used by book list/detail responses |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `fk_book_images_book FOREIGN KEY (book_id) REFERENCES books(id) ON DELETE CASCADE`
- `chk_book_images_provider CHECK (provider IN ('CLOUDINARY'))`
- `chk_book_images_asset_type CHECK (asset_type IN ('COVER_FRONT', 'COVER_BACK', 'PREVIEW', 'OTHER'))`
- Positive/non-negative checks for dimensions, bytes and sort order

Indexes:

- `idx_book_images_book_id ON book_images(book_id)`
- `idx_book_images_book_primary ON book_images(book_id, is_primary)`
- `uq_book_images_provider_public_id UNIQUE (provider, public_id)`
- `uq_book_images_one_primary_per_book UNIQUE (book_id) WHERE is_primary = TRUE`

Migration notes:

- `V25` seeds primary Cloudinary cover rows from ISBN-named assets using
  `books.isbn` as the Cloudinary `public_id`.
- `V26` changes generated Cloudinary cover URLs to versionless delivery URLs
  because uploaded assets can have different Cloudinary version segments.
- Current book read APIs expose structured `coverImage` URLs. `imageUrl` remains
  only as create/update request input for attaching a primary cover URL.

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
| `max_borrow_limit` | `INT` | No | `5` | Max active borrows at the same time |
| `membership_expires_at` | `TIMESTAMP` | Yes | | Null means no expiration |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |
| `status` | `VARCHAR(20)` | No | DB default is still `'PENDING'` from `V4` | Account state; application sets `PENDING_VERIFICATION` explicitly |

Allowed `status` values:

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

Seed data:

- `V9` seeds `admin@library.local` and `librarian@library.local`
- `V21` seeds `member1@library.local` and `member2@library.local`
- Demo password noted in migrations: `Password123`

Migration notes:

- `is_active` existed in `V1`.
- `status` was added by `V4`.
- `is_active` was dropped by `V5`.
- `V6` aligned `status` with the Java `MemberStatus` enum.
- `V6` did not alter the column default from `PENDING` to
  `PENDING_VERIFICATION`. Inserts should provide `status` explicitly, as the
  application entity does. A future migration should fix the DB default.

### `fine_configs`

Stores fine rate configuration over time.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `rate_per_day` | `DECIMAL(10,2)` | No | | Must be greater than `0` |
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

Stores physical book copies. A `Book` is catalog metadata; a `BookCopy` is the
real item identified by barcode.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `book_id` | `BIGINT` | No | | FK to `books(id)`, `ON DELETE CASCADE` |
| `barcode` | `VARCHAR(100)` | No | | Unique and case-insensitive unique |
| `status` | `VARCHAR(20)` | No | `'AVAILABLE'` | See allowed values |
| `condition` | `VARCHAR(50)` | Yes | | |
| `location` | `VARCHAR(100)` | Yes | | |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |
| `deleted_at` | `TIMESTAMP` | Yes | | Soft delete marker, added by `V8` |
| `deleted_by` | `BIGINT` | Yes | | FK to `members(id)`, `ON DELETE SET NULL` |

Allowed `status` values after `V13`:

- `AVAILABLE`
- `BORROWED`
- `RESERVED`
- `OVERDUE`
- `ON_HOLD_SHELF`
- `LOST`
- `DAMAGED`
- `REMOVED`

Constraints:

- `PRIMARY KEY (id)`
- `barcode UNIQUE`
- `uq_book_copies_barcode_lower UNIQUE ON LOWER(barcode)`
- `chk_copy_status CHECK (status IN (...))`
- `fk_book_copy_deleted_by FOREIGN KEY (deleted_by) REFERENCES members(id) ON DELETE SET NULL`

Indexes:

- `idx_book_copies_book ON book_copies(book_id)`
- `idx_book_copies_status ON book_copies(status)`
- `idx_book_copies_active ON book_copies(book_id) WHERE deleted_at IS NULL`
- `idx_book_copies_active_status ON book_copies(book_id, status) WHERE deleted_at IS NULL`
- `idx_book_copies_available ON book_copies(book_id) WHERE status = 'AVAILABLE' AND deleted_at IS NULL`
- `uq_book_copies_barcode_lower ON book_copies(LOWER(barcode))`

### `borrow_records`

Stores borrow/loan history. Each record points to one physical copy.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `member_id` | `BIGINT` | No | | FK to `members(id)` |
| `book_copy_id` | `BIGINT` | No | | FK to `book_copies(id)` |
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
| `renew_count` | `INT` | No | `0` | Added by `V13` |
| `max_renewals_at_checkout` | `INT` | No | `1` | Added by `V13`; default setting later changed to `2` |

Allowed `status` values:

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
- `chk_renew_count_non_negative CHECK (renew_count >= 0)`
- `fk_borrow_copy FOREIGN KEY (book_copy_id) REFERENCES book_copies(id)`

Indexes:

- `idx_borrow_member ON borrow_records(member_id)`
- `idx_borrow_due_date ON borrow_records(due_date)`
- `idx_borrow_member_status ON borrow_records(member_id, status)`
- `idx_borrow_fine_unpaid ON borrow_records(fine_paid_at) WHERE fine_amount > 0 AND fine_paid_at IS NULL`
- `idx_borrow_overdue ON borrow_records(due_date, status) WHERE status = 'BORROWED'`
- `uq_open_borrow_copy UNIQUE ON borrow_records(book_copy_id) WHERE status IN ('BORROWED', 'OVERDUE', 'LOST')`

Migration notes:

- `book_id` existed in `V1`.
- `V2` dropped `book_id` and added `book_copy_id`.
- `V14` replaced `uq_active_borrow_copy` with `uq_open_borrow_copy`, so one physical copy can have only one open borrow record across `BORROWED`, `OVERDUE`, and `LOST`.

### `reservations`

Stores hold/reservation queue entries for books.

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
| `assigned_copy_id` | `BIGINT` | Yes | | FK to `book_copies(id)`, `ON DELETE SET NULL`, added by `V13` |

Allowed `status` values after `V13`:

- `WAITING`
- `NOTIFIED`
- `READY_FOR_PICKUP`
- `FULFILLED`
- `CANCELLED`
- `EXPIRED`

Constraints:

- `PRIMARY KEY (id)`
- `chk_reservation_status CHECK (status IN (...))`
- `chk_expires_after_reserved CHECK (expires_at > reserved_at)`
- `fk_reservation_assigned_copy FOREIGN KEY (assigned_copy_id) REFERENCES book_copies(id) ON DELETE SET NULL`

Indexes:

- `idx_reservation_member ON reservations(member_id)`
- `idx_reservation_book ON reservations(book_id)`
- `idx_reservation_active ON reservations(book_id, status) WHERE status IN ('WAITING', 'NOTIFIED')`
- `uq_reservation_queue UNIQUE ON reservations(book_id, queue_position)`
- `idx_reservation_queue_order ON reservations(book_id, status, reserved_at)`
- `idx_reservation_assigned_copy ON reservations(assigned_copy_id)`
- `uq_active_hold_member_book UNIQUE ON reservations(member_id, book_id) WHERE status IN ('WAITING', 'NOTIFIED', 'READY_FOR_PICKUP')`
- `uq_active_hold_assigned_copy UNIQUE ON reservations(assigned_copy_id) WHERE assigned_copy_id IS NOT NULL AND status IN ('NOTIFIED', 'READY_FOR_PICKUP')`

Migration notes:

- `V1` had `uq_active_reservation UNIQUE (member_id, book_id)`.
- `V2` dropped that constraint and introduced queue positions.
- `V13` added `READY_FOR_PICKUP` and `assigned_copy_id`.
- `V15` added partial unique indexes for active member/book holds and active assigned copies.

### `payments`

Stores payment records for fines or future payable items.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `member_id` | `BIGINT` | No | | FK to `members(id)` |
| `amount` | `DECIMAL(10,2)` | No | | Must be greater than `0` |
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
| `trace_id` | `VARCHAR(100)` | Yes | | Request trace ID |
| `actor_role` | `VARCHAR(20)` | Yes | | `MEMBER`, `ADMIN`, `LIBRARIAN` |
| `ip_address` | `VARCHAR(45)` | Yes | | IPv4/IPv6 client address |
| `user_agent` | `VARCHAR(255)` | Yes | | Client user agent |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `PRIMARY KEY (id)`
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

Constraints:

- `PRIMARY KEY (id)`

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
| `notification_type` | `VARCHAR(100)` | Yes | | Added by `V17` |
| `target_type` | `VARCHAR(100)` | Yes | | Added by `V17` |
| `target_id` | `BIGINT` | Yes | | Added by `V17` |

Constraints:

- `PRIMARY KEY (id)`
- `chk_queue_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))`

Indexes:

- `idx_queue_status ON notification_queue(status)`
- `idx_notification_queue_target ON notification_queue(notification_type, target_type, target_id)`
- `uq_notification_queue_once_per_target UNIQUE ON notification_queue(notification_type, target_type, target_id, channel) WHERE notification_type IS NOT NULL AND target_type IS NOT NULL AND target_id IS NOT NULL`

### `system_settings`

Stores configurable system values.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `key` | `VARCHAR(100)` | No | | Primary key |
| `value` | `VARCHAR(255)` | No | | |
| `description` | `TEXT` | Yes | | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `PRIMARY KEY (key)`

Current seeded keys:

| Key | Value | Description |
|---|---:|---|
| `BORROW_DAYS_DEFAULT` | `14` | Default borrow days |
| `MAX_RESERVATION_DAYS` | `3` | Reservation expiry |
| `ENABLE_EMAIL_NOTIFICATION` | `true` | Enable email |
| `MAX_RENEWALS_DEFAULT` | `2` | Default max renewal count per borrow |
| `RENEWAL_DAYS_DEFAULT` | `7` | Default extra days for each renewal |
| `ALLOW_RENEW_OVERDUE` | `false` | Allow renewing overdue borrow |
| `HOLD_PICKUP_DAYS_DEFAULT` | `3` | Default days a ready hold stays on hold shelf |
| `AUTO_RENEW_ENABLED` | `false` | Enable daily automatic renewal job |
| `AUTO_RENEW_DAYS_BEFORE_DUE` | `1` | Run auto-renewal for borrows due in this many days |
| `AUTO_RENEW_NOTIFY_SUCCESS` | `true` | Send email when auto-renewal succeeds |
| `AUTO_RENEW_NOTIFY_FAILURE` | `true` | Send email when auto-renewal is blocked |
| `AUTO_RENEW_MAX_ITEMS_PER_RUN` | `500` | Maximum borrow records processed per auto-renewal job run |
| `DUE_SOON_REMINDER_ENABLED` | `true` | Enable daily due-soon reminder email job |
| `DUE_SOON_REMINDER_DAYS_BEFORE_DUE` | `2` | Send reminder this many days before due date |
| `DUE_SOON_REMINDER_MAX_ITEMS_PER_RUN` | `500` | Maximum borrow records processed per due-soon reminder job run |

Migration notes:

- `V16` changed `MAX_RENEWALS_DEFAULT` from `1` to `2`.
- `V18` enabled due-soon reminder by setting `DUE_SOON_REMINDER_ENABLED=true`.

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
| `used_at` | `TIMESTAMP` | Yes | | Added by `V7` |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | Added by `V7` |
| `last_sent_at` | `TIMESTAMP` | No | `NOW()` | Added by `V7`; backfilled from `created_at` |

Constraints:

- `PRIMARY KEY (id)`
- `FOREIGN KEY (job_id) REFERENCES book_import_jobs(id) ON DELETE CASCADE`
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

### `job_execution_logs`

Stores scheduled/background job execution history.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `job_name` | `VARCHAR(100)` | No | | |
| `started_at` | `TIMESTAMP` | No | | |
| `finished_at` | `TIMESTAMP` | Yes | | |
| `status` | `VARCHAR(30)` | No | | `RUNNING`, `COMPLETED`, `FAILED` |
| `total_processed` | `INT` | No | `0` | |
| `success_count` | `INT` | No | `0` | |
| `failed_count` | `INT` | No | `0` | |
| `error_message` | `TEXT` | Yes | | |

Constraints:

- `PRIMARY KEY (id)`
- `chk_job_execution_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))`

### `auto_renewal_attempts`

Stores per-borrow auto-renewal attempt history.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `borrow_record_id` | `BIGINT` | No | | FK to `borrow_records(id)` |
| `member_id` | `BIGINT` | No | | FK to `members(id)` |
| `book_copy_id` | `BIGINT` | No | | FK to `book_copies(id)` |
| `job_execution_log_id` | `BIGINT` | Yes | | FK to `job_execution_logs(id)` |
| `attempted_at` | `TIMESTAMP` | No | | |
| `result` | `VARCHAR(30)` | No | | `SUCCESS`, `FAILED` |
| `reason_code` | `VARCHAR(100)` | Yes | | |
| `reason_message` | `TEXT` | Yes | | |
| `old_due_date` | `TIMESTAMP` | Yes | | |
| `new_due_date` | `TIMESTAMP` | Yes | | |
| `renew_count_before` | `INT` | Yes | | |
| `renew_count_after` | `INT` | Yes | | |

Constraints:

- `PRIMARY KEY (id)`
- `chk_auto_renewal_attempt_result CHECK (result IN ('SUCCESS', 'FAILED'))`

Indexes:

- `idx_auto_renewal_attempt_borrow ON auto_renewal_attempts(borrow_record_id, attempted_at DESC)`
- `idx_auto_renewal_attempt_member ON auto_renewal_attempts(member_id, attempted_at DESC)`
- `idx_auto_renewal_attempt_job ON auto_renewal_attempts(job_execution_log_id)`

### `book_import_jobs`

Stores asynchronous CSV import job state.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `UUID` | No | | Primary key |
| `original_filename` | `VARCHAR(255)` | Yes | | |
| `status` | `VARCHAR(30)` | No | | `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` |
| `total_rows` | `INT` | No | `0` | |
| `processed_rows` | `INT` | No | `0` | |
| `success_rows` | `INT` | No | `0` | |
| `failed_rows` | `INT` | No | `0` | |
| `created_books` | `INT` | No | `0` | |
| `created_copies` | `INT` | No | `0` | |
| `error_message` | `TEXT` | Yes | | |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |
| `started_at` | `TIMESTAMP` | Yes | | |
| `completed_at` | `TIMESTAMP` | Yes | | |
| `updated_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `PRIMARY KEY (id)`
- `chk_book_import_job_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))`

Indexes:

- `idx_book_import_jobs_status ON book_import_jobs(status)`

### `book_import_job_errors`

Stores row-level CSV import errors.

| Column | Type | Null | Default | Notes |
|---|---:|---:|---:|---|
| `id` | `BIGSERIAL` | No | | Primary key |
| `job_id` | `UUID` | No | | FK to `book_import_jobs(id)`, `ON DELETE CASCADE` |
| `row_number` | `INT` | No | | CSV row number |
| `isbn` | `VARCHAR(20)` | Yes | | |
| `barcode` | `VARCHAR(100)` | Yes | | |
| `code` | `VARCHAR(100)` | No | | Error code |
| `message` | `TEXT` | No | | Error message |
| `created_at` | `TIMESTAMP` | No | `NOW()` | |

Constraints:

- `PRIMARY KEY (id)`

Indexes:

- `idx_book_import_job_errors_job_row ON book_import_job_errors(job_id, row_number)`

## Removed Tables

### `idempotency_records`

`V13` created this table, but `V20` dropped it. The current application stores
idempotency state in Redis instead of PostgreSQL.

## Relationship Summary

- `books.category_id` -> `categories.id`
- `books.deleted_by` -> `members.id`
- `book_authors.book_id` -> `books.id`
- `book_authors.author_id` -> `authors.id`
- `book_images.book_id` -> `books.id`
- `book_copies.book_id` -> `books.id`
- `book_copies.deleted_by` -> `members.id`
- `borrow_records.member_id` -> `members.id`
- `borrow_records.book_copy_id` -> `book_copies.id`
- `borrow_records.fine_config_id` -> `fine_configs.id`
- `borrow_records.fine_waived_by` -> `members.id`
- `reservations.member_id` -> `members.id`
- `reservations.book_id` -> `books.id`
- `reservations.assigned_copy_id` -> `book_copies.id`
- `fine_configs.created_by` -> `members.id`
- `payments.member_id` -> `members.id`
- `audit_logs.user_id` -> `members.id`
- `notifications.member_id` -> `members.id`
- `notification_queue.member_id` -> `members.id`
- `notification_queue.notification_id` -> `notifications.id`
- `email_verifications.member_id` -> `members.id`
- `auto_renewal_attempts.borrow_record_id` -> `borrow_records.id`
- `auto_renewal_attempts.member_id` -> `members.id`
- `auto_renewal_attempts.book_copy_id` -> `book_copies.id`
- `auto_renewal_attempts.job_execution_log_id` -> `job_execution_logs.id`
- `book_import_job_errors.job_id` -> `book_import_jobs.id`

## Important Notes

- `members.status` is the current account-state field. Do not use `is_active`;
  it was dropped by `V5`.
- `borrow_records` references `book_copies`, not `books`.
- `books.total_copies` and `books.available_copies` can be `0`.
- `book_copies.barcode` is protected by both the original unique constraint and
  the case-insensitive unique index `uq_book_copies_barcode_lower`.
- A member can have only one active hold for the same book because of
  `uq_active_hold_member_book`.
- A copy can have only one open borrow because of `uq_open_borrow_copy`.
- A copy on the hold shelf can be assigned to only one active hold because of
  `uq_active_hold_assigned_copy`.
- `refreshToken` and idempotency records are Redis-backed application state,
  not PostgreSQL tables.
- `V4__add_member_status.sql` has an internal comment header mentioning `V3`,
  but the applied filename is `V4__add_member_status.sql`.
