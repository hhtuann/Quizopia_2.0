CREATE INDEX idx_quizzes_owner_user_id_id
    ON quizzes (owner_user_id, id DESC);

CREATE INDEX idx_quiz_drafts_updated_at_quiz_id
    ON quiz_drafts (updated_at DESC, quiz_id DESC);
