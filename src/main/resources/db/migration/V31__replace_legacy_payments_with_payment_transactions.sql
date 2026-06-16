-- ============================================================
-- V31__replace_legacy_payments_with_payment_transactions.sql
-- Replace the unused legacy payments table with the ebook payment core schema.
-- ============================================================

DROP TABLE IF EXISTS payments;

CREATE TABLE payment_transactions (
    id BIGSERIAL PRIMARY KEY,
    payment_code VARCHAR(64) NOT NULL,
    member_id BIGINT NOT NULL,
    provider VARCHAR(30) NOT NULL,
    provider_order_id VARCHAR(100),
    provider_transaction_id VARCHAR(100),
    purpose VARCHAR(50) NOT NULL,
    target_type VARCHAR(50) NOT NULL,
    target_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'VND',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    payment_url TEXT,
    idempotency_key VARCHAR(255) NOT NULL,
    paid_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    expired_at TIMESTAMP NOT NULL,
    failure_code VARCHAR(100),
    failure_message TEXT,
    provider_response_code VARCHAR(50),
    provider_transaction_status VARCHAR(50),
    provider_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_payment_transactions_member
        FOREIGN KEY (member_id)
        REFERENCES members(id),

    CONSTRAINT uq_payment_transactions_payment_code
        UNIQUE (payment_code),

    CONSTRAINT chk_payment_transactions_provider
        CHECK (provider IN ('VNPAY', 'MOMO', 'ZALOPAY', 'STRIPE')),

    CONSTRAINT chk_payment_transactions_purpose
        CHECK (purpose IN ('EBOOK_PAYMENT')),

    CONSTRAINT chk_payment_transactions_target_type
        CHECK (target_type IN ('BOOK_EBOOK')),

    CONSTRAINT chk_payment_transactions_amount_positive
        CHECK (amount > 0),

    CONSTRAINT chk_payment_transactions_currency_not_blank
        CHECK (length(trim(currency)) > 0),

    CONSTRAINT chk_payment_transactions_status
        CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX idx_payment_member_id
    ON payment_transactions(member_id);

CREATE INDEX idx_payment_status
    ON payment_transactions(status);

CREATE INDEX idx_payment_target
    ON payment_transactions(target_type, target_id);

CREATE INDEX idx_payment_provider_order
    ON payment_transactions(provider, provider_order_id);

CREATE INDEX idx_payment_provider_transaction
    ON payment_transactions(provider, provider_transaction_id);

CREATE INDEX idx_payment_created_at
    ON payment_transactions(created_at);

CREATE UNIQUE INDEX ux_payment_pending_ebook_target
    ON payment_transactions(member_id, purpose, target_type, target_id)
    WHERE status = 'PENDING';

CREATE UNIQUE INDEX ux_payment_success_ebook_target
    ON payment_transactions(member_id, purpose, target_type, target_id)
    WHERE status = 'SUCCESS';

CREATE TABLE payment_events (
    id BIGSERIAL PRIMARY KEY,
    payment_transaction_id BIGINT,
    provider VARCHAR(30) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    provider_order_id VARCHAR(100),
    provider_transaction_id VARCHAR(100),
    raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    raw_headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    signature_valid BOOLEAN,
    processing_status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    error_message TEXT,
    received_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP,

    CONSTRAINT fk_payment_events_transaction
        FOREIGN KEY (payment_transaction_id)
        REFERENCES payment_transactions(id)
        ON DELETE SET NULL,

    CONSTRAINT chk_payment_events_provider
        CHECK (provider IN ('VNPAY', 'MOMO', 'ZALOPAY', 'STRIPE')),

    CONSTRAINT chk_payment_events_event_type
        CHECK (event_type IN ('IPN', 'RETURN')),

    CONSTRAINT chk_payment_events_processing_status
        CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'IGNORED'))
);

CREATE INDEX idx_payment_events_transaction_id
    ON payment_events(payment_transaction_id);

CREATE INDEX idx_payment_events_provider_order
    ON payment_events(provider, provider_order_id);

CREATE INDEX idx_payment_events_received_at
    ON payment_events(received_at);
