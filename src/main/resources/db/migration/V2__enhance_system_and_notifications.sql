-- ============================================================
-- V2__final_schema.sql
-- FINAL VERSION - Production Ready
-- Fix normalization + add real-world features
-- ============================================================


-- ============================================================
-- 1. BOOK COPIES (Physical inventory)
-- ============================================================

CREATE TABLE book_copies (
                             id          BIGSERIAL PRIMARY KEY,
                             book_id     BIGINT NOT NULL REFERENCES books(id) ON DELETE CASCADE,

                             barcode     VARCHAR(100) NOT NULL UNIQUE,
                             status      VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',

                             condition   VARCHAR(50),
                             location    VARCHAR(100),

                             created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
                             updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),

                             CONSTRAINT chk_copy_status CHECK (
                                 status IN ('AVAILABLE', 'BORROWED', 'RESERVED', 'LOST', 'DAMAGED')
                                 )
);

CREATE INDEX idx_book_copies_book   ON book_copies(book_id);
CREATE INDEX idx_book_copies_status ON book_copies(status);


-- ============================================================
-- 2. BORROW RECORDS (FIXED NORMALIZATION)
-- ============================================================

-- DROP old book_id
ALTER TABLE borrow_records
DROP COLUMN book_id;

-- add book_copy_id (required)
ALTER TABLE borrow_records
    ADD COLUMN book_copy_id BIGINT NOT NULL;

ALTER TABLE borrow_records
    ADD CONSTRAINT fk_borrow_copy
        FOREIGN KEY (book_copy_id) REFERENCES book_copies(id);

-- 1 copy chỉ được mượn 1 lần tại 1 thời điểm
CREATE UNIQUE INDEX uq_active_borrow_copy
    ON borrow_records(book_copy_id)
    WHERE status = 'BORROWED';


-- ============================================================
-- 3. RESERVATION QUEUE (FIXED)
-- ============================================================

-- Remove old unique constraint
ALTER TABLE reservations
DROP CONSTRAINT uq_active_reservation;

-- Add queue position
ALTER TABLE reservations
    ADD COLUMN queue_position INT;

-- Đảm bảo thứ tự queue
CREATE UNIQUE INDEX uq_reservation_queue
    ON reservations(book_id, queue_position);

-- Index cho xử lý queue
CREATE INDEX idx_reservation_queue_order
    ON reservations(book_id, status, reserved_at);


-- ============================================================
-- 4. PAYMENT SYSTEM
-- ============================================================

CREATE TABLE payments (
                          id            BIGSERIAL PRIMARY KEY,
                          member_id     BIGINT NOT NULL REFERENCES members(id),

                          amount        DECIMAL(10,2) NOT NULL,
                          currency      VARCHAR(3) NOT NULL DEFAULT 'VND',

                          method        VARCHAR(20) NOT NULL,
                          status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',

                          paid_at       TIMESTAMP,
                          created_at    TIMESTAMP NOT NULL DEFAULT NOW(),

                          CONSTRAINT chk_payment_amount CHECK (amount > 0),
                          CONSTRAINT chk_payment_status CHECK (
                              status IN ('PENDING', 'SUCCESS', 'FAILED')
                              )
);

CREATE INDEX idx_payment_member ON payments(member_id);


-- ============================================================
-- 5. AUDIT LOG
-- ============================================================

CREATE TABLE audit_logs (
                            id           BIGSERIAL PRIMARY KEY,
                            user_id      BIGINT REFERENCES members(id),

                            action       VARCHAR(50) NOT NULL,
                            entity_type  VARCHAR(50),
                            entity_id    BIGINT,

                            metadata     JSONB,

                            created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_user   ON audit_logs(user_id);
CREATE INDEX idx_audit_entity ON audit_logs(entity_type, entity_id);


-- ============================================================
-- 6. NOTIFICATION SYSTEM
-- ============================================================

-- In-app notification
CREATE TABLE notifications (
                               id            BIGSERIAL PRIMARY KEY,
                               member_id     BIGINT NOT NULL REFERENCES members(id),

                               title         VARCHAR(255) NOT NULL,
                               content       TEXT NOT NULL,

                               type          VARCHAR(50) NOT NULL,
                               is_read       BOOLEAN NOT NULL DEFAULT FALSE,

                               sent_at       TIMESTAMP NOT NULL DEFAULT NOW(),
                               created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_member ON notifications(member_id);
CREATE INDEX idx_notification_unread ON notifications(member_id, is_read);


-- Async queue (email/push)
CREATE TABLE notification_queue (
                                    id              BIGSERIAL PRIMARY KEY,
                                    member_id       BIGINT REFERENCES members(id),
                                    notification_id BIGINT REFERENCES notifications(id),

                                    channel         VARCHAR(20) NOT NULL,
                                    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',

                                    retry_count     INT DEFAULT 0,
                                    scheduled_at    TIMESTAMP NOT NULL DEFAULT NOW(),
                                    sent_at         TIMESTAMP,

                                    CONSTRAINT chk_queue_status CHECK (
                                        status IN ('PENDING', 'SENT', 'FAILED')
                                        )
);

CREATE INDEX idx_queue_status ON notification_queue(status);


-- ============================================================
-- 7. SYSTEM SETTINGS
-- ============================================================

CREATE TABLE system_settings (
                                 key         VARCHAR(100) PRIMARY KEY,
                                 value       VARCHAR(255) NOT NULL,
                                 description TEXT,
                                 updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO system_settings (key, value, description)
VALUES
    ('BORROW_DAYS_DEFAULT', '14', 'Default borrow days'),
    ('MAX_RESERVATION_DAYS', '3', 'Reservation expiry'),
    ('ENABLE_EMAIL_NOTIFICATION', 'true', 'Enable email');


-- ============================================================
-- 8. PERFORMANCE IMPROVEMENTS
-- ============================================================

-- Full-text search
DROP INDEX IF EXISTS idx_books_title;

CREATE INDEX idx_books_fulltext
    ON books USING gin(to_tsvector('simple', title || ' ' || coalesce(isbn, '')));

-- Overdue query
CREATE INDEX idx_borrow_overdue
    ON borrow_records(due_date, status)
    WHERE status = 'BORROWED';


-- ============================================================
-- DONE
-- ============================================================