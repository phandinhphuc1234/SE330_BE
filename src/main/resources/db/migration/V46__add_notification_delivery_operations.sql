-- Support safe operator-triggered retries and distinguish callbacks from older
-- delivery attempts. Automatic retries keep the same provider_request_key;
-- an explicit admin retry increments delivery_attempt and creates a new key.
ALTER TABLE notification_queue
    ADD COLUMN delivery_attempt INTEGER,
    ADD COLUMN provider_request_key VARCHAR(255);

UPDATE notification_queue
SET delivery_attempt = 1,
    provider_request_key = event_key
WHERE delivery_attempt IS NULL
   OR provider_request_key IS NULL;

ALTER TABLE notification_queue
    ALTER COLUMN delivery_attempt SET DEFAULT 1,
    ALTER COLUMN delivery_attempt SET NOT NULL,
    ALTER COLUMN provider_request_key SET NOT NULL,
    ADD CONSTRAINT chk_notification_queue_delivery_attempt
        CHECK (delivery_attempt > 0);

CREATE UNIQUE INDEX uq_notification_queue_provider_request_key
    ON notification_queue(provider_request_key);

ALTER TABLE notification_provider_events
    ADD COLUMN hinted_delivery_attempt INTEGER;

ALTER TABLE notification_provider_events
    ADD CONSTRAINT chk_notification_provider_event_attempt
        CHECK (hinted_delivery_attempt IS NULL OR hinted_delivery_attempt > 0);
