CREATE TABLE email_verification_challenge (
    user_id UUID PRIMARY KEY,
    otp_hash TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    resend_not_before TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER NOT NULL,
    max_attempts INTEGER NOT NULL,
    CONSTRAINT fk_email_verification_challenge_user
        FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT ck_email_verification_challenge_hash
        CHECK (length(btrim(otp_hash)) > 0),
    CONSTRAINT ck_email_verification_challenge_attempts
        CHECK (failed_attempts >= 0 AND max_attempts > 0 AND failed_attempts <= max_attempts),
    CONSTRAINT ck_email_verification_challenge_expiry
        CHECK (expires_at > issued_at),
    CONSTRAINT ck_email_verification_challenge_cooldown
        CHECK (resend_not_before >= issued_at)
);
