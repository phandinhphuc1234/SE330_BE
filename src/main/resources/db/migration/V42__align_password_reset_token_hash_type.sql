-- V40 is already applied on the preserved local snapshot. Hibernate maps the
-- hash as VARCHAR(64), so normalize the PostgreSQL fixed-width CHAR column in
-- a forward-only migration instead of editing an applied migration.
ALTER TABLE password_reset_tokens
    ALTER COLUMN token_hash TYPE VARCHAR(64);
