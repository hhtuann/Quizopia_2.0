CREATE TABLE email_verification_email_outbox (
    id UUID PRIMARY KEY,
    issuance_id UUID NOT NULL,
    recipient_email VARCHAR(320) NOT NULL,
    template_type VARCHAR(64) NOT NULL,
    otp_expires_at TIMESTAMPTZ NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claim_owner VARCHAR(255),
    claim_expires_at TIMESTAMPTZ,
    key_version VARCHAR(64) NOT NULL,
    payload_format_version INTEGER NOT NULL,
    ciphertext BYTEA,
    nonce BYTEA,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ,
    failure_category VARCHAR(64),
    CONSTRAINT uk_email_verification_email_outbox_issuance UNIQUE (issuance_id),
    CONSTRAINT fk_email_verification_email_outbox_issuance
        FOREIGN KEY (issuance_id) REFERENCES email_verification_issuance (id) ON DELETE RESTRICT,
    CONSTRAINT ck_email_verification_email_outbox_recipient
        CHECK (length(recipient_email) > 0),
    CONSTRAINT ck_email_verification_email_outbox_template
        CHECK (length(template_type) > 0),
    CONSTRAINT ck_email_verification_email_outbox_state
        CHECK (state IN ('PENDING', 'CLAIMED', 'SENT', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_email_verification_email_outbox_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_email_verification_email_outbox_key_version
        CHECK (length(key_version) > 0),
    CONSTRAINT ck_email_verification_email_outbox_payload_format
        CHECK (payload_format_version > 0),
    CONSTRAINT ck_email_verification_email_outbox_payload
        CHECK (
            (state IN ('PENDING', 'CLAIMED')
                AND ciphertext IS NOT NULL
                AND length(ciphertext) > 16
                AND nonce IS NOT NULL
                AND length(nonce) = 12)
            OR
            (state IN ('SENT', 'FAILED', 'EXPIRED')
                AND ciphertext IS NULL
                AND nonce IS NULL)
        ),
    CONSTRAINT ck_email_verification_email_outbox_claim
        CHECK (
            (state = 'CLAIMED' AND claim_owner IS NOT NULL AND claim_expires_at IS NOT NULL)
            OR
            (state <> 'CLAIMED' AND claim_owner IS NULL AND claim_expires_at IS NULL)
        ),
    CONSTRAINT ck_email_verification_email_outbox_timestamps
        CHECK (otp_expires_at > created_at AND next_attempt_at >= created_at),
    CONSTRAINT ck_email_verification_email_outbox_terminal
        CHECK (
            (state = 'SENT' AND sent_at IS NOT NULL AND terminal_at IS NOT NULL)
            OR
            (state IN ('FAILED', 'EXPIRED') AND sent_at IS NULL AND terminal_at IS NOT NULL)
            OR
            (state IN ('PENDING', 'CLAIMED') AND sent_at IS NULL AND terminal_at IS NULL)
        )
);

CREATE INDEX ix_email_verification_email_outbox_due
    ON email_verification_email_outbox (next_attempt_at, id)
    WHERE state = 'PENDING';

CREATE INDEX ix_email_verification_email_outbox_stale_claim
    ON email_verification_email_outbox (claim_expires_at, id)
    WHERE state = 'CLAIMED';
