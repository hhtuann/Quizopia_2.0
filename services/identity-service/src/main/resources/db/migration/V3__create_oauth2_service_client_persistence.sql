CREATE TABLE oauth2_service_client (
    id UUID PRIMARY KEY,
    client_id VARCHAR(255) NOT NULL,
    client_secret_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_oauth2_service_client_client_id
        UNIQUE (client_id),
    CONSTRAINT ck_oauth2_service_client_client_id_non_blank
        CHECK (btrim(client_id) <> '' AND client_id = btrim(client_id))
);

CREATE TABLE oauth2_service_client_scope (
    service_client_id UUID NOT NULL,
    scope VARCHAR(255) NOT NULL,
    CONSTRAINT pk_oauth2_service_client_scope
        PRIMARY KEY (service_client_id, scope),
    CONSTRAINT fk_oauth2_service_client_scope_client
        FOREIGN KEY (service_client_id) REFERENCES oauth2_service_client (id) ON DELETE CASCADE,
    CONSTRAINT ck_oauth2_service_client_scope_non_blank
        CHECK (btrim(scope) <> '' AND scope = btrim(scope))
);
