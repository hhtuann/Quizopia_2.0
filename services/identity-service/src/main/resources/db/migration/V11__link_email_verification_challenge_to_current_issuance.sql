ALTER TABLE email_verification_challenge
    ADD COLUMN current_issuance_id UUID;

ALTER TABLE email_verification_challenge
    ADD CONSTRAINT fk_email_verification_challenge_current_issuance
        FOREIGN KEY (current_issuance_id)
        REFERENCES email_verification_issuance (id)
        ON DELETE RESTRICT;

CREATE INDEX ix_email_verification_challenge_current_issuance
    ON email_verification_challenge (current_issuance_id)
    WHERE current_issuance_id IS NOT NULL;
