-- Idempotency state has been moved from PostgreSQL to Redis.
-- Keep this as a forward migration instead of editing V13, because V13 may
-- already be recorded in flyway_schema_history on existing databases.

DROP INDEX IF EXISTS idx_idempotency_expires_at;

DROP TABLE IF EXISTS idempotency_records;
