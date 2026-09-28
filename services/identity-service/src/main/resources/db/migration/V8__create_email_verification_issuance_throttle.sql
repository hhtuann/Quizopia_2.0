CREATE TABLE email_verification_issuance_guard (
    email VARCHAR(320) PRIMARY KEY,
    CONSTRAINT ck_email_verification_issuance_guard_email
        CHECK (length(email) > 0)
);

CREATE TABLE email_verification_issuance (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_email_verification_issuance_guard
        FOREIGN KEY (email) REFERENCES email_verification_issuance_guard (email) ON DELETE CASCADE
);

CREATE INDEX ix_email_verification_issuance_email_issued_at
    ON email_verification_issuance (email, issued_at);
