CREATE TABLE user_access_revocation (
    user_id UUID PRIMARY KEY,
    revoked_before TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_user_access_revocation_user
        FOREIGN KEY (user_id) REFERENCES user_account (id)
);
