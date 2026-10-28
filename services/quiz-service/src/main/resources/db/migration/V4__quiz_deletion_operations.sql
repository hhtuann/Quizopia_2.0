ALTER TABLE quizzes
    ADD COLUMN deletion_fenced_at TIMESTAMPTZ NULL;

CREATE TABLE quiz_deletion_operations (
    id UUID PRIMARY KEY,
    quiz_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL,
    status TEXT NOT NULL,
    main_progress INTEGER NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    confirmed_title TEXT NOT NULL,
    version_inventory JSONB NOT NULL,
    last_reason TEXT NULL,
    CONSTRAINT fk_quiz_deletion_operations_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE RESTRICT,
    CONSTRAINT uq_quiz_deletion_operations_quiz_key UNIQUE (quiz_id, idempotency_key),
    CONSTRAINT ck_quiz_deletion_operations_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{16,128}$'),
    CONSTRAINT ck_quiz_deletion_operations_status CHECK (status IN (
        'REQUESTED',
        'QUIZ_FENCED',
        'DEPENDENCIES_FENCING',
        'ACCESS_REVOKED',
        'PURGE_PENDING',
        'PURGING',
        'PRIMARY_PURGED',
        'ALL_COPIES_ERASED',
        'WAITING_DEPENDENCY',
        'BLOCKED_BY_RETENTION',
        'PENDING_POLICY',
        'RETRYABLE_FAILURE'))
);

CREATE INDEX idx_quiz_deletion_operations_owner_created
    ON quiz_deletion_operations (owner_user_id, created_at);

CREATE INDEX idx_quiz_deletion_operations_quiz
    ON quiz_deletion_operations (quiz_id);

CREATE TABLE quiz_deletion_outbox (
    id UUID PRIMARY KEY,
    operation_id UUID NOT NULL,
    kind TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT NULL,
    CONSTRAINT fk_quiz_deletion_outbox_operation FOREIGN KEY (operation_id)
        REFERENCES quiz_deletion_operations(id) ON DELETE RESTRICT,
    CONSTRAINT ck_quiz_deletion_outbox_kind CHECK (kind = 'quiz.deletion.requested.v1')
);

CREATE INDEX idx_quiz_deletion_outbox_due
    ON quiz_deletion_outbox (next_attempt_at)
    WHERE dispatched_at IS NULL AND attempts < 10;
