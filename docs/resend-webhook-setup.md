# Resend delivery webhook

The backend accepts signed delivery events at:

```text
POST https://api-library.flashsale123.tech/api/webhooks/resend
```

## Resend dashboard

Create one webhook for the URL above and subscribe to:

- `email.sent`
- `email.delivered`
- `email.delivery_delayed`
- `email.bounced`
- `email.complained`
- `email.failed`
- `email.suppressed`

Copy the endpoint signing secret shown by Resend. It starts with `whsec_` and is
different from `RESEND_API_KEY`.

## VPS runtime configuration

Edit the protected runtime file on the VPS without putting the value in Git:

```dotenv
RESEND_WEBHOOK_ENABLED=true
RESEND_WEBHOOK_SIGNING_SECRET=whsec_replace_with_the_real_value
RESEND_WEBHOOK_MAX_PAYLOAD_BYTES=262144
```

Then redeploy/restart the backend. The preflight script rejects an enabled
webhook without a `whsec_` signing secret.

## Processing contract

1. The controller reads the request as an unchanged raw string.
2. The official Svix Java verifier validates `svix-id`, `svix-timestamp`, and
   `svix-signature` before JSON parsing or database writes.
3. `svix-id` is inserted into `notification_provider_events` with a unique
   constraint, making Resend retries and manual replays idempotent.
4. The signed `queue_id` tag links the callback to `notification_queue`, while
   `delivery_attempt` prevents a delayed callback from an older manual resend
   from overwriting the current state. Older first-attempt messages can still
   be matched by Resend `email_id`.
5. Queue state becomes `DELIVERED`, `BOUNCED`, `COMPLAINED`, or `DEAD` as
   appropriate. Unknown valid provider messages are acknowledged and recorded
   as `UNMATCHED` so callbacks for direct OTP/password-reset email do not retry
   forever.

Invalid signatures return HTTP 400. Valid events, including duplicates and
unmatched email IDs, return HTTP 200. Unexpected database errors return HTTP
500 through the normal Spring error path so Resend can retry the webhook.

## Admin operations

After deployment, an authenticated admin can inspect delivery state through:

```text
GET  /api/admin/notification-deliveries
GET  /api/admin/notification-deliveries/summary
POST /api/admin/notification-deliveries/{queueId}/retry
```

The retry endpoint only accepts `DEAD` rows. It resets the worker retry budget,
creates a new Resend idempotency key, increments `delivery_attempt`, and records
the action in `audit_logs`. `BOUNCED` and `COMPLAINED` rows are deliberately not
retryable through this endpoint.
