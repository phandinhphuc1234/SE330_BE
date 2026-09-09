CREATE TABLE member_status_audits (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL,
    actor_member_id BIGINT NOT NULL,
    previous_status VARCHAR(30) NOT NULL,
    new_status VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_member_status_audits_member
        FOREIGN KEY (member_id) REFERENCES members(id),
    CONSTRAINT fk_member_status_audits_actor
        FOREIGN KEY (actor_member_id) REFERENCES members(id),
    CONSTRAINT chk_member_status_audits_previous_status
        CHECK (previous_status IN ('PENDING_VERIFICATION', 'ACTIVE', 'INACTIVE', 'BANNED')),
    CONSTRAINT chk_member_status_audits_new_status
        CHECK (new_status IN ('PENDING_VERIFICATION', 'ACTIVE', 'INACTIVE', 'BANNED')),
    CONSTRAINT chk_member_status_audits_changed
        CHECK (previous_status <> new_status)
);

CREATE INDEX idx_member_status_audits_member_created
    ON member_status_audits(member_id, created_at DESC);

CREATE INDEX idx_member_status_audits_actor_created
    ON member_status_audits(actor_member_id, created_at DESC);
