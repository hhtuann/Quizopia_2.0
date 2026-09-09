CREATE UNIQUE INDEX uk_user_account_username
    ON user_account (username)
    WHERE username IS NOT NULL;
