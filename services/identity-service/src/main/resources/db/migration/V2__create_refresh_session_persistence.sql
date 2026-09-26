CREATE TABLE refresh_token_family (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT fk_refresh_token_family_user
        FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT ck_refresh_token_family_expiry
        CHECK (expires_at > created_at)
);

CREATE INDEX idx_refresh_token_family_user_id
    ON refresh_token_family (user_id);

CREATE TABLE refresh_token (
    id UUID PRIMARY KEY,
    family_id UUID NOT NULL,
    token_hash BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    replaced_by_token_id UUID,
    CONSTRAINT uk_refresh_token_token_hash
        UNIQUE (token_hash),
    CONSTRAINT uk_refresh_token_id_family
        UNIQUE (id, family_id),
    CONSTRAINT fk_refresh_token_family
        FOREIGN KEY (family_id) REFERENCES refresh_token_family (id),
    CONSTRAINT fk_refresh_token_replacement_same_family
        FOREIGN KEY (replaced_by_token_id, family_id)
        REFERENCES refresh_token (id, family_id),
    CONSTRAINT ck_refresh_token_not_self_replaced
        CHECK (replaced_by_token_id IS NULL OR replaced_by_token_id <> id),
    CONSTRAINT ck_refresh_token_replacement_requires_consumption
        CHECK (replaced_by_token_id IS NULL OR consumed_at IS NOT NULL)
);
