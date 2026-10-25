-- Historical creation times were not recorded. Existing rows receive migration time;
-- UUID is the deterministic tie-breaker. New rows use database creation time.
ALTER TABLE classrooms ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX ix_classrooms_owner_directory
    ON classrooms (owner_teacher_user_id, created_at DESC, id DESC);
