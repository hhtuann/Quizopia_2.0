CREATE TABLE quiz_versions (
    id UUID PRIMARY KEY,
    quiz_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    title_snapshot TEXT,
    description_snapshot TEXT,
    source_snapshot TEXT NOT NULL,
    structured_content JSONB NOT NULL,
    content_schema_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_quiz_versions_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(id),
    CONSTRAINT ck_quiz_versions_version_positive CHECK (version_number > 0),
    CONSTRAINT ck_quiz_versions_content_schema CHECK (content_schema_version = 1),
    CONSTRAINT uq_quiz_versions_quiz_version UNIQUE (quiz_id, version_number)
);
