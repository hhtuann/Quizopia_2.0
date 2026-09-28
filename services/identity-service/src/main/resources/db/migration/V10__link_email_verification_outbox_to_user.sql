DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM email_verification_email_outbox
        WHERE state IN ('PENDING', 'CLAIMED')
    ) THEN
        RAISE EXCEPTION
            'Active legacy verification-email outbox jobs must be drained or resolved before V10 migration';
    END IF;
END
$$;

ALTER TABLE email_verification_email_outbox
    ADD COLUMN user_id UUID;

ALTER TABLE email_verification_email_outbox
    ADD CONSTRAINT fk_email_verification_email_outbox_user
        FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE RESTRICT;

ALTER TABLE email_verification_email_outbox
    ADD CONSTRAINT ck_email_verification_email_outbox_account
        CHECK (
            (state IN ('PENDING', 'CLAIMED') AND user_id IS NOT NULL)
            OR state IN ('SENT', 'FAILED', 'EXPIRED')
        );

CREATE INDEX ix_email_verification_email_outbox_user
    ON email_verification_email_outbox (user_id)
    WHERE user_id IS NOT NULL;
