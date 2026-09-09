CREATE TABLE book_reviews (
    id BIGSERIAL PRIMARY KEY,
    book_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    rating SMALLINT NOT NULL,
    content TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_book_reviews_book
        FOREIGN KEY (book_id) REFERENCES books(id),
    CONSTRAINT fk_book_reviews_member
        FOREIGN KEY (member_id) REFERENCES members(id),
    CONSTRAINT uq_book_reviews_book_member
        UNIQUE (book_id, member_id),
    CONSTRAINT chk_book_reviews_rating
        CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_book_reviews_content_length
        CHECK (content IS NULL OR char_length(content) <= 2000)
);

CREATE INDEX idx_book_reviews_book_created
    ON book_reviews(book_id, created_at DESC);

CREATE INDEX idx_book_reviews_member
    ON book_reviews(member_id);
