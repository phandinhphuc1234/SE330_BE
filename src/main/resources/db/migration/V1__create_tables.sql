-- ============================================================
-- V1__init_schema.sql
-- Library Management System
-- JWT Strategy: Access Token (memory) + Refresh Token (Redis)
-- Không lưu refresh token trong DB → dùng Redis
-- ============================================================

-- ── Extensions ───────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "pgcrypto";  -- gen_random_uuid()

-- ── Authors ──────────────────────────────────────────────────
CREATE TABLE authors (
                         id         BIGSERIAL    PRIMARY KEY,
                         name       VARCHAR(100) NOT NULL,
                         bio        TEXT,
                         created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
                         updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),

    -- Tránh duplicate author cùng tên
                         CONSTRAINT uq_author_name UNIQUE (name)
);

-- ── Categories ───────────────────────────────────────────────
CREATE TABLE categories (
                            id          BIGSERIAL    PRIMARY KEY,
                            name        VARCHAR(50)  NOT NULL,
                            description VARCHAR(255),
                            created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
                            updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),

                            CONSTRAINT uq_category_name UNIQUE (name)
);

-- ── Books ─────────────────────────────────────────────────────
CREATE TABLE books (
                       id               BIGSERIAL    PRIMARY KEY,
                       title            VARCHAR(255) NOT NULL,
                       isbn             VARCHAR(20)  NOT NULL,
                       total_copies     INT          NOT NULL DEFAULT 1,
                       available_copies INT          NOT NULL DEFAULT 1,
                       published_date   DATE,                          -- DATE thay vì INT year
                       language         VARCHAR(10)  NOT NULL DEFAULT 'vi',
                       edition          VARCHAR(50),
                       category_id      BIGINT       REFERENCES categories(id) ON DELETE SET NULL,

    -- Optimistic locking (Spring @Version)
                       version          BIGINT       NOT NULL DEFAULT 0,

    -- Soft delete: xóa book khỏi catalog nhưng giữ borrow history
                       deleted_at       TIMESTAMP,
                       deleted_by       BIGINT,                        -- FK sau khi có members

                       created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
                       updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),

                       CONSTRAINT uq_book_isbn             UNIQUE (isbn),
                       CONSTRAINT chk_available_copies     CHECK (available_copies >= 0),
                       CONSTRAINT chk_total_copies         CHECK (total_copies > 0),
    -- available không thể vượt total
                       CONSTRAINT chk_available_lte_total  CHECK (available_copies <= total_copies)
);

-- ── Book ↔ Author (many-to-many) ─────────────────────────────
CREATE TABLE book_authors (
                              book_id   BIGINT NOT NULL REFERENCES books(id)   ON DELETE CASCADE,
                              author_id BIGINT NOT NULL REFERENCES authors(id) ON DELETE CASCADE,
                              PRIMARY KEY (book_id, author_id)
);

-- ── Members ──────────────────────────────────────────────────
CREATE TABLE members (
                         id                   BIGSERIAL    PRIMARY KEY,
                         full_name            VARCHAR(100) NOT NULL,
                         email                VARCHAR(100) NOT NULL,
                         password             VARCHAR(255) NOT NULL,      -- BCrypt hash
                         phone                VARCHAR(20),
                         role                 VARCHAR(20)  NOT NULL DEFAULT 'MEMBER',

    -- Trạng thái tài khoản
                         is_active            BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Giới hạn mượn sách đồng thời
                         max_borrow_limit     INT          NOT NULL DEFAULT 5,

    -- Hạn thẻ thư viện (NULL = không hết hạn)
                         membership_expires_at TIMESTAMP,

                         created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
                         updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),

                         CONSTRAINT uq_member_email  UNIQUE (email),
                         CONSTRAINT chk_member_role  CHECK (role IN ('MEMBER', 'LIBRARIAN', 'ADMIN')),
                         CONSTRAINT chk_borrow_limit CHECK (max_borrow_limit > 0)
);

-- Thêm FK deleted_by sau khi members tồn tại
ALTER TABLE books
    ADD CONSTRAINT fk_book_deleted_by
        FOREIGN KEY (deleted_by) REFERENCES members(id) ON DELETE SET NULL;

-- ── Fine Config ───────────────────────────────────────────────
-- Tránh hardcode fine rate trong code
-- Librarian/Admin có thể thay đổi mà không cần redeploy
CREATE TABLE fine_configs (
                              id              BIGSERIAL      PRIMARY KEY,
                              rate_per_day    DECIMAL(10, 2) NOT NULL,
                              currency        VARCHAR(3)     NOT NULL DEFAULT 'VND',
                              effective_from  DATE           NOT NULL,
    -- NULL = đang áp dụng hiện tại
                              effective_until DATE,
                              created_by      BIGINT         REFERENCES members(id) ON DELETE SET NULL,
                              created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),

                              CONSTRAINT chk_fine_rate_positive CHECK (rate_per_day > 0),
                              CONSTRAINT chk_fine_dates         CHECK (
                                  effective_until IS NULL OR effective_until > effective_from
                                  )
);

-- Seed rate mặc định
INSERT INTO fine_configs (rate_per_day, currency, effective_from)
VALUES (5000, 'VND', CURRENT_DATE);

-- ── Borrow Records ────────────────────────────────────────────
CREATE TABLE borrow_records (
                                id          BIGSERIAL      PRIMARY KEY,
                                member_id   BIGINT         NOT NULL REFERENCES members(id),
                                book_id     BIGINT         NOT NULL REFERENCES books(id),

    -- Thời gian
                                borrowed_at TIMESTAMP      NOT NULL DEFAULT NOW(),
                                due_date    TIMESTAMP      NOT NULL,
                                returned_at TIMESTAMP,

    -- Fine tracking
                                fine_amount       DECIMAL(10, 2) NOT NULL DEFAULT 0,
                                fine_config_id    BIGINT         REFERENCES fine_configs(id),  -- rate nào được áp dụng
                                fine_calculated_at TIMESTAMP,
                                fine_paid_at      TIMESTAMP,
                                fine_waived_by    BIGINT         REFERENCES members(id),       -- librarian waive fine
                                fine_waived_reason VARCHAR(255),

                                status      VARCHAR(20)    NOT NULL DEFAULT 'BORROWED',

                                created_at  TIMESTAMP      NOT NULL DEFAULT NOW(),
                                updated_at  TIMESTAMP      NOT NULL DEFAULT NOW(),

                                CONSTRAINT chk_borrow_status CHECK (
                                    status IN ('BORROWED', 'RETURNED', 'OVERDUE', 'LOST')
                                    ),
                                CONSTRAINT chk_due_after_borrowed CHECK (due_date > borrowed_at),
                                CONSTRAINT chk_returned_after_borrowed CHECK (
                                    returned_at IS NULL OR returned_at >= borrowed_at
                                    ),
                                CONSTRAINT chk_fine_non_negative CHECK (fine_amount >= 0)
);

-- ── Reservations ──────────────────────────────────────────────
-- Đặt trước sách khi tất cả bản đang được mượn
CREATE TABLE reservations (
                              id          BIGSERIAL   PRIMARY KEY,
                              member_id   BIGINT      NOT NULL REFERENCES members(id),
                              book_id     BIGINT      NOT NULL REFERENCES books(id),
                              reserved_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    -- Tự hết hạn sau 3 ngày nếu không đến lấy
                              expires_at  TIMESTAMP   NOT NULL DEFAULT NOW() + INTERVAL '3 days',
                              notified_at TIMESTAMP,  -- thời điểm notify member sách available
                              status      VARCHAR(20) NOT NULL DEFAULT 'WAITING',
                              created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
                              updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),

    -- 1 member chỉ đặt 1 lần cho 1 cuốn sách tại 1 thời điểm
                              CONSTRAINT uq_active_reservation UNIQUE (member_id, book_id),
                              CONSTRAINT chk_reservation_status CHECK (
                                  status IN ('WAITING', 'NOTIFIED', 'FULFILLED', 'CANCELLED', 'EXPIRED')
                                  ),
                              CONSTRAINT chk_expires_after_reserved CHECK (expires_at > reserved_at)
);

-- ── Indexes ───────────────────────────────────────────────────

-- Books
CREATE INDEX idx_books_category   ON books(category_id);
CREATE INDEX idx_books_active      ON books(id) WHERE deleted_at IS NULL;
CREATE INDEX idx_books_title       ON books USING gin(to_tsvector('simple', title)); -- full-text search

-- Members
CREATE INDEX idx_members_email     ON members(email);
CREATE INDEX idx_members_active    ON members(id) WHERE is_active = TRUE;

-- Borrow records — các query phổ biến nhất
CREATE INDEX idx_borrow_member         ON borrow_records(member_id);
CREATE INDEX idx_borrow_book           ON borrow_records(book_id);
CREATE INDEX idx_borrow_due_date       ON borrow_records(due_date);
-- Composite: "sách member X đang mượn" — query phổ biến nhất
CREATE INDEX idx_borrow_member_status  ON borrow_records(member_id, status);
-- Composite: "ai đang mượn sách Y"
CREATE INDEX idx_borrow_book_status    ON borrow_records(book_id, status);
-- Fine management: fine chưa thanh toán
CREATE INDEX idx_borrow_fine_unpaid    ON borrow_records(fine_paid_at)
    WHERE fine_amount > 0 AND fine_paid_at IS NULL;

-- Reservations
CREATE INDEX idx_reservation_member  ON reservations(member_id);
CREATE INDEX idx_reservation_book    ON reservations(book_id);
-- Chỉ index reservation đang active (WAITING/NOTIFIED)
CREATE INDEX idx_reservation_active  ON reservations(book_id, status)
    WHERE status IN ('WAITING', 'NOTIFIED');