-- FB-015 PR C: the deletion operation ledger outlives the quiz row it references. The
-- quiz_id reference becomes an opaque tombstone key so PRIMARY_PURGED can hard-delete
-- quizzes/quiz_drafts/quiz_versions while the minimal operation ledger (ids, states,
-- timestamps, counts - no quiz content) remains to reject stale commands.
ALTER TABLE quiz_deletion_operations DROP CONSTRAINT fk_quiz_deletion_operations_quiz;
