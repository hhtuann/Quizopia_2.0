CREATE TABLE user_account (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    username VARCHAR(255),
    account_status VARCHAR(64),
    email_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_user_account_email ON user_account (email);
CREATE INDEX idx_user_account_username ON user_account (username);

CREATE TABLE user_role (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_user_role_user
        FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_role_value
        CHECK (role IN ('STUDENT', 'TEACHER', 'ADMIN')),
    CONSTRAINT uk_user_role_user_role
        UNIQUE (user_id, role)
);

CREATE TABLE local_credential (
    user_id UUID PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_local_credential_user
        FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE
);

CREATE TABLE external_provider_identity (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider VARCHAR(64) NOT NULL,
    provider_subject VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_external_provider_identity_user
        FOREIGN KEY (user_id) REFERENCES user_account (id) ON DELETE CASCADE,
    CONSTRAINT uk_external_provider_identity_provider_subject
        UNIQUE (provider, provider_subject)
);

CREATE INDEX idx_external_provider_identity_user_id
    ON external_provider_identity (user_id);
