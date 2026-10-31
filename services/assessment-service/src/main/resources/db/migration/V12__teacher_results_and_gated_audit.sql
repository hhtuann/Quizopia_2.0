-- W4F-T2: no capture trigger, retention default, production key or learner-enable flag.
-- Sensitive writes/reads remain unavailable in the application while policy is pending.
CREATE UNIQUE INDEX attempts_teacher_binding ON attempts(id,publication_id);
CREATE INDEX attempts_student_history ON attempts(publication_id,student_id,ordinal DESC,id);

CREATE TABLE assessment_sensitive_read_receipts (
 id uuid PRIMARY KEY,
 publication_id uuid NOT NULL,
 attempt_id uuid NOT NULL,
 actor_id uuid NOT NULL,
 purpose varchar(24) NOT NULL CHECK(purpose IN ('TEACHER_REVIEW','ANSWER_AUDIT')),
 accessed_at timestamptz NOT NULL CHECK(isfinite(accessed_at)),
 expires_at timestamptz NOT NULL CHECK(isfinite(expires_at) AND expires_at>accessed_at),
 FOREIGN KEY(attempt_id,publication_id) REFERENCES attempts(id,publication_id) ON DELETE RESTRICT
);
CREATE INDEX sensitive_read_attempt ON assessment_sensitive_read_receipts(attempt_id,id);
CREATE TABLE assessment_answer_audit (
 attempt_id uuid NOT NULL,
 publication_id uuid NOT NULL,
 event_id uuid NOT NULL,
 ordinal bigint NOT NULL CHECK(ordinal>0),
 actor_id uuid NOT NULL,
 source varchar(24) NOT NULL CHECK(source IN ('CLIENT_OBSERVATION','SERVER_AUTHORITY')),
 event_type varchar(32) NOT NULL,
 stream_id uuid,
 sequence bigint CHECK(sequence>=0),
 question_number integer CHECK(question_number>0),
 assessment_revision bigint CHECK(assessment_revision>0),
 observed_at timestamptz CHECK(observed_at IS NULL OR isfinite(observed_at)),
 occurred_at timestamptz CHECK(occurred_at IS NULL OR isfinite(occurred_at)),
 received_at timestamptz NOT NULL CHECK(isfinite(received_at)),
 expires_at timestamptz NOT NULL CHECK(isfinite(expires_at) AND expires_at>received_at),
 key_id varchar(32) NOT NULL CHECK(key_id ~ '^[A-Za-z0-9_-]{1,32}$'),
 protected_input bytea NOT NULL CHECK(octet_length(protected_input) BETWEEN 29 AND 8192),
 PRIMARY KEY(attempt_id,event_id),
 UNIQUE(attempt_id,ordinal),
 UNIQUE(attempt_id,stream_id,sequence),
 FOREIGN KEY(attempt_id,publication_id) REFERENCES attempts(id,publication_id) ON DELETE RESTRICT,
 CHECK((source='CLIENT_OBSERVATION' AND stream_id IS NOT NULL AND sequence IS NOT NULL
     AND question_number IS NOT NULL AND assessment_revision IS NULL AND occurred_at IS NULL
     AND event_type IN ('QUESTION_VIEWED','QUESTION_LEFT','ANSWER_SELECTED','ANSWER_CHANGED','ANSWER_CLEARED'))
   OR (source='SERVER_AUTHORITY' AND stream_id IS NULL AND sequence IS NULL AND observed_at IS NULL
     AND occurred_at IS NOT NULL AND event_type IN ('ATTEMPT_STARTED','ANSWER_PERSISTED','ATTEMPT_SUBMITTED',
       'ATTEMPT_ABANDONED','CLOSURE_NOTICE_CONFIRMED','RESULT_GRADED')))
);

CREATE INDEX minimal_audit_actor_rate ON assessment_answer_audit(actor_id,received_at);

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_minimal_audit_immutable() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,%1$I,pg_temp AS $body$
BEGIN
 IF TG_OP='DELETE' AND assessment_purge_authorized()
   AND EXISTS(SELECT FROM publications p WHERE p.id=OLD.publication_id
      AND p.quiz_id=nullif(current_setting('quizopia.assessment_purge_quiz',true),'')::uuid) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'sensitive audit is append only';
END $body$ $ddl$,current_schema()); END $migration$;
CREATE TRIGGER minimal_audit_immutable BEFORE UPDATE OR DELETE ON assessment_answer_audit
 FOR EACH ROW EXECUTE FUNCTION assessment_minimal_audit_immutable();
CREATE TRIGGER sensitive_receipt_immutable BEFORE UPDATE OR DELETE ON assessment_sensitive_read_receipts
 FOR EACH ROW EXECUTE FUNCTION assessment_minimal_audit_immutable();
CREATE TRIGGER a_purge_write_fence BEFORE INSERT OR UPDATE ON assessment_answer_audit
 FOR EACH ROW EXECUTE FUNCTION assessment_purge_write_fence();
CREATE TRIGGER a_purge_write_fence BEFORE INSERT OR UPDATE ON assessment_sensitive_read_receipts
 FOR EACH ROW EXECUTE FUNCTION assessment_purge_write_fence();

-- Drain new dependents BEFORE delegating to the immutable V11 step machine. This
-- preserves in-progress step numbers, bounded budgets, custody and old receipts.
ALTER FUNCTION assessment_purge_quiz(uuid,uuid,integer) RENAME TO assessment_purge_quiz_v11;
REVOKE ALL ON FUNCTION assessment_purge_quiz_v11(uuid,uuid,integer) FROM PUBLIC,assessment;
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_purge_quiz(p_quiz uuid,p_operation uuid,p_budget integer)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE target text; n integer; used integer:=0; BEGIN
 IF p_budget IS NULL OR p_budget<1 OR p_budget>50000 THEN RAISE EXCEPTION 'invalid purge budget'; END IF;
 IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'purge requires READ COMMITTED'; END IF;
 INSERT INTO purge_admission(quiz_id) VALUES(p_quiz) ON CONFLICT DO NOTHING;
 PERFORM 1 FROM purge_admission WHERE quiz_id=p_quiz FOR UPDATE;
 IF NOT EXISTS(SELECT FROM quiz_revocation_fences WHERE quiz_id=p_quiz AND operation_id=p_operation AND ack_at IS NOT NULL)
   THEN RAISE EXCEPTION 'quiz purge requires acknowledged matching fence'; END IF;
 IF EXISTS(SELECT FROM retention_holds WHERE quiz_id=p_quiz AND (expires_at IS NULL OR expires_at>clock_timestamp()))
   THEN RETURN 'BLOCKED_BY_RETENTION'; END IF;
 UPDATE purge_admission SET begun=true WHERE quiz_id=p_quiz AND NOT begun;
 PERFORM set_config('quizopia.assessment_purge_quiz',p_quiz::text,true);
 FOREACH target IN ARRAY ARRAY['assessment_answer_audit','assessment_sensitive_read_receipts'] LOOP
   EXECUTE format('DELETE FROM %%I WHERE ctid IN (SELECT t.ctid FROM %%I t JOIN publications p ON p.id=t.publication_id WHERE p.quiz_id=$1 LIMIT $2)',target,target)
     USING p_quiz,p_budget-used;
   GET DIAGNOSTICS n=ROW_COUNT; used:=used+n;
   INSERT INTO deletion_purge_receipts VALUES(p_operation,p_quiz,target,n,clock_timestamp())
     ON CONFLICT(operation_id,step) DO UPDATE SET rows_removed=deletion_purge_receipts.rows_removed+EXCLUDED.rows_removed;
   IF used=p_budget THEN RETURN 'IN_PROGRESS'; END IF;
 END LOOP;
 RETURN assessment_purge_quiz_v11(p_quiz,p_operation,p_budget-used);
END $body$ $ddl$,current_schema()); END $migration$;

ALTER TABLE assessment_answer_audit OWNER TO quizopia_assessment_owner;
ALTER TABLE assessment_sensitive_read_receipts OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_minimal_audit_immutable() OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_purge_quiz(uuid,uuid,integer) OWNER TO quizopia_assessment_owner;
REVOKE ALL ON assessment_answer_audit,assessment_sensitive_read_receipts FROM PUBLIC,assessment,quizopia_assessment_custodian;
GRANT SELECT,INSERT ON assessment_answer_audit,assessment_sensitive_read_receipts TO assessment;
REVOKE ALL ON FUNCTION assessment_minimal_audit_immutable(),assessment_purge_quiz(uuid,uuid,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION assessment_purge_quiz(uuid,uuid,integer) TO assessment;
