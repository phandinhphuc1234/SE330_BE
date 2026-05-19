INSERT INTO system_settings (key, value, description)
VALUES
    ('DUE_SOON_REMINDER_ENABLED', 'false', 'Enable daily due-soon reminder email job'),
    ('DUE_SOON_REMINDER_DAYS_BEFORE_DUE', '2', 'Send due-soon reminder this many days before due date'),
    ('DUE_SOON_REMINDER_MAX_ITEMS_PER_RUN', '500', 'Maximum borrow records processed per due-soon reminder job run')
ON CONFLICT (key) DO NOTHING;

ALTER TABLE notification_queue
    ADD COLUMN IF NOT EXISTS notification_type VARCHAR(100),
    ADD COLUMN IF NOT EXISTS target_type VARCHAR(100),
    ADD COLUMN IF NOT EXISTS target_id BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_queue_once_per_target
    ON notification_queue(notification_type, target_type, target_id, channel)
    WHERE notification_type IS NOT NULL
      AND target_type IS NOT NULL
      AND target_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notification_queue_target
    ON notification_queue(notification_type, target_type, target_id);
