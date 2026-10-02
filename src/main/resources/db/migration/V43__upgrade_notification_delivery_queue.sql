-- Upgrade the existing notification queue into a durable email-delivery outbox.
-- The delivery worker remains disabled until every legacy direct-email producer
-- has been migrated, so this migration stays backward compatible during rollout.

ALTER TABLE notification_queue
    ADD COLUMN IF NOT EXISTS event_key VARCHAR(255),
    ADD COLUMN IF NOT EXISTS recipient_email VARCHAR(320),
    ADD COLUMN IF NOT EXISTS template_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 5,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS locked_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS locked_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS provider_message_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_error TEXT,
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Backfill transport-independent fields for rows produced before V43. A legacy
-- row without a business target still gets a stable key based on its queue id.
UPDATE notification_queue
SET event_key = COALESCE(
        NULLIF(BTRIM(event_key), ''),
        CASE
            WHEN notification_type IS NOT NULL
                 AND target_type IS NOT NULL
                 AND target_id IS NOT NULL
            THEN notification_type || ':' || target_type || ':' || target_id || ':' || channel
            ELSE 'LEGACY_NOTIFICATION_QUEUE:' || id
        END
    ),
    template_code = COALESCE(
        NULLIF(BTRIM(template_code), ''),
        CASE
            WHEN notification_type IS NOT NULL
            THEN LOWER(REPLACE(notification_type, '_', '-'))
            ELSE 'legacy-notification'
        END
    ),
    next_attempt_at = COALESCE(next_attempt_at, scheduled_at),
    created_at = COALESCE(created_at, scheduled_at),
    updated_at = COALESCE(updated_at, sent_at, scheduled_at),
    retry_count = COALESCE(retry_count, 0),
    max_attempts = GREATEST(max_attempts, COALESCE(retry_count, 0), 1)
WHERE id IS NOT NULL; -- Intentional full-table backfill; id is a non-null primary key.

-- Preserve the address that existed when the business event was created. This
-- is intentionally nullable for legacy rows that did not reference a member;
-- the new outbox service will require it for every new EMAIL delivery.
UPDATE notification_queue queue
SET recipient_email = member.email
FROM members member
WHERE queue.member_id = member.id
  AND (queue.recipient_email IS NULL OR BTRIM(queue.recipient_email) = '');

ALTER TABLE notification_queue
    ALTER COLUMN event_key SET NOT NULL,
    ALTER COLUMN template_code SET NOT NULL,
    ALTER COLUMN retry_count SET DEFAULT 0,
    ALTER COLUMN retry_count SET NOT NULL,
    ALTER COLUMN next_attempt_at SET DEFAULT NOW(),
    ALTER COLUMN next_attempt_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT NOW(),
    ALTER COLUMN updated_at SET NOT NULL;

-- Keep the three legacy states valid while adding the states required for
-- claiming, retrying and tracking provider delivery outcomes.
ALTER TABLE notification_queue
    DROP CONSTRAINT IF EXISTS chk_queue_status;

ALTER TABLE notification_queue
    ADD CONSTRAINT chk_queue_status CHECK (
        status IN (
            'PENDING',
            'PROCESSING',
            'RETRY',
            'SENT',
            'DELIVERED',
            'FAILED',
            'DEAD',
            'BOUNCED',
            'COMPLAINED'
        )
    );

ALTER TABLE notification_queue
    DROP CONSTRAINT IF EXISTS chk_notification_queue_retry_count;

ALTER TABLE notification_queue
    ADD CONSTRAINT chk_notification_queue_retry_count
        CHECK (retry_count >= 0 AND max_attempts > 0);

-- event_key is the application-level idempotency key. Keep the older
-- target-based unique index until all existing producers use event_key in the
-- next implementation step.
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_queue_event_key
    ON notification_queue(event_key);

CREATE INDEX IF NOT EXISTS idx_notification_queue_dispatch
    ON notification_queue(status, next_attempt_at, scheduled_at, id)
    WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX IF NOT EXISTS idx_notification_queue_stale_processing
    ON notification_queue(locked_at, id)
    WHERE status = 'PROCESSING';

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_queue_provider_message_id
    ON notification_queue(provider_message_id)
    WHERE provider_message_id IS NOT NULL;

COMMENT ON COLUMN notification_queue.event_key IS
    'Stable application idempotency key for one logical delivery';
COMMENT ON COLUMN notification_queue.recipient_email IS
    'Recipient snapshot captured when the business event is committed';
COMMENT ON COLUMN notification_queue.payload IS
    'Template variables only; secrets and raw credentials must never be stored here';
