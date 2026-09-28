CREATE UNIQUE INDEX uk_user_account_verified_email
    ON user_account (email)
    WHERE email_verified_at IS NOT NULL;
