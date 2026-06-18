CREATE TABLE book_reviews (
    id          BIGSERIAL PRIMARY KEY,
    book_id     BIGINT NOT NULL REFERENCES books(id),
    member_id   BIGINT NOT NULL REFERENCES members(id),
    rating      SMALLINT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    content     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (book_id, member_id)
);
CREATE INDEX idx_book_reviews_book_id ON book_reviews(book_id);
CREATE INDEX idx_book_reviews_member_id ON book_reviews(member_id);
