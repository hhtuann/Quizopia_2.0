CREATE TABLE quizzes (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE quiz_drafts (
    quiz_id UUID PRIMARY KEY,
    title TEXT,
    description TEXT,
    authoring_source TEXT,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_quiz_drafts_quiz FOREIGN KEY (quiz_id) REFERENCES quizzes(id)
);
