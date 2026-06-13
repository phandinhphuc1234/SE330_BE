ALTER TABLE books
    ADD COLUMN ebook_url VARCHAR(500) NULL;

CREATE TABLE ebook_loans
(
    id           BIGSERIAL PRIMARY KEY,
    member_id    BIGINT      NOT NULL,
    book_id      BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    borrowed_at  TIMESTAMP   NOT NULL,
    expires_at   TIMESTAMP   NOT NULL,
    renew_count  INT         NOT NULL DEFAULT 0,
    max_renewals INT         NOT NULL DEFAULT 1,
    returned_at  TIMESTAMP   NULL,
    created_at   TIMESTAMP   NOT NULL,
    updated_at   TIMESTAMP   NOT NULL,

    CONSTRAINT fk_ebook_loans_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_ebook_loans_book FOREIGN KEY (book_id) REFERENCES books (id)
);

CREATE INDEX idx_ebook_loans_member ON ebook_loans (member_id);
CREATE INDEX idx_ebook_loans_book ON ebook_loans (book_id);
CREATE INDEX idx_ebook_loans_status ON ebook_loans (status);
CREATE INDEX idx_ebook_loans_expires ON ebook_loans (expires_at);
CREATE INDEX idx_ebook_loans_active_expires ON ebook_loans (status, expires_at);
