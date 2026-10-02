-- All notification producers now use event_key through NotificationQueueService.
-- The older target-based uniqueness rule is too coarse because one target can
-- legitimately produce multiple business events during its lifetime.
DROP INDEX IF EXISTS uq_notification_queue_once_per_target;

-- Pre-V44 queue rows did not capture the Thymeleaf variables required by the
-- delivery worker. Quarantine only undelivered legacy rows so enabling the
-- worker cannot send incomplete or misleading messages.
UPDATE notification_queue
SET status = 'DEAD',
    locked_at = NULL,
    locked_by = NULL,
    last_error = 'LEGACY_PAYLOAD_NOT_REPLAYABLE',
    updated_at = NOW()
WHERE status IN ('PENDING', 'PROCESSING', 'RETRY')
  AND (
      event_key LIKE 'LEGACY_NOTIFICATION_QUEUE:%'
      OR (notification_type = 'DUE_SOON_REMINDER' AND payload = '{}'::jsonb)
  );

COMMENT ON COLUMN notification_queue.event_key IS
    'Unique idempotency key for one logical delivery; producer contract active since V44';
