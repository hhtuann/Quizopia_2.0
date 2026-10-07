CREATE TABLE role_grant_audit (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL,
    target_user_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    action VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_role_grant_audit_actor
        FOREIGN KEY (actor_user_id) REFERENCES user_account (id),
    CONSTRAINT fk_role_grant_audit_target
        FOREIGN KEY (target_user_id) REFERENCES user_account (id),
    CONSTRAINT ck_role_grant_audit_role
        CHECK (role = 'TEACHER'),
    CONSTRAINT ck_role_grant_audit_action
        CHECK (action = 'ROLE_GRANTED'),
    CONSTRAINT ck_role_grant_audit_source
        CHECK (source = 'SELF_SERVICE'),
    CONSTRAINT ck_role_grant_audit_self_service_actor
        CHECK (actor_user_id = target_user_id),
    CONSTRAINT uk_role_grant_audit_first_grant
        UNIQUE (target_user_id, role, action)
);

CREATE INDEX idx_role_grant_audit_actor_occurred_at
    ON role_grant_audit (actor_user_id, occurred_at);
