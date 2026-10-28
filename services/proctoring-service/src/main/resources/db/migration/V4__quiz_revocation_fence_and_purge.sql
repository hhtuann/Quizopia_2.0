-- FB-015 PR C (ADR-018 Phase A step 4 + Phase B step 7): durable quiz-ID revocation fence,
-- command inbox, purge receipts and the narrowly-scoped privileged quiz purge for Proctoring.
-- Default OFF via quizopia.deletion.enabled; with empty fence tables every existing route
-- behaves exactly as before (the gate always runs and finds nothing).
ALTER TABLE monitoring_sessions ADD COLUMN quiz_id uuid;
CREATE INDEX monitoring_sessions_quiz_idx ON monitoring_sessions(quiz_id) WHERE quiz_id IS NOT NULL;

CREATE TABLE quiz_revocation_fences (
  quiz_id uuid PRIMARY KEY,
  revoked_at timestamptz NOT NULL CHECK (isfinite(revoked_at)),
  operation_id uuid NOT NULL,
  ack_at timestamptz,
  CHECK (ack_at IS NULL OR (isfinite(ack_at) AND ack_at >= revoked_at))
);
CREATE INDEX quiz_revocation_fences_operation_idx ON quiz_revocation_fences(operation_id);

-- ADR-009 inbox for durable deletion commands: dedup by stable operation id and
-- safe redelivery. Fence application and inbox recording share one transaction.
CREATE TABLE deletion_command_inbox (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL,
  quiz_id uuid NOT NULL,
  received_at timestamptz NOT NULL CHECK (isfinite(received_at)),
  processed_at timestamptz,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error text,
  UNIQUE (operation_id),
  CHECK (processed_at IS NULL OR (isfinite(processed_at) AND processed_at >= received_at))
);
CREATE INDEX deletion_command_inbox_unprocessed_idx
  ON deletion_command_inbox(received_at) WHERE processed_at IS NULL;

-- Counts only: no PII, no media payloads, no evidence content ever lands here.
CREATE TABLE deletion_purge_receipts (
  operation_id uuid NOT NULL,
  quiz_id uuid NOT NULL,
  step varchar(40) NOT NULL,
  rows_removed bigint NOT NULL CHECK (rows_removed >= 0),
  completed_at timestamptz NOT NULL CHECK (isfinite(completed_at)),
  PRIMARY KEY (operation_id, step)
);

-- Honest media-provider erasure bookkeeping: a REQUEST is recorded and stays
-- PENDING_PROVIDER until a real provider erasure API exists. No fabricated receipts.
CREATE TABLE media_erasure_requests (
  operation_id uuid PRIMARY KEY,
  quiz_id uuid NOT NULL,
  provider varchar(32) NOT NULL,
  status varchar(20) NOT NULL CHECK (status IN ('PENDING_PROVIDER')),
  requested_at timestamptz NOT NULL CHECK (isfinite(requested_at))
);

-- The purge procedure opens one narrow, transaction-scoped window. Only the table owner
-- executing monitoring_purge_quiz (SECURITY DEFINER) reaches it: the GUC is transaction
-- local, the fence must be acknowledged, and every guard below still rejects each DELETE
-- whose row does not belong to exactly that quiz.
CREATE FUNCTION proctoring_purge_authorized(p_quiz uuid) RETURNS boolean
 LANGUAGE sql STABLE SET search_path=pg_catalog,pg_temp AS $$
 SELECT current_setting('quizopia.proctoring_purge_quiz',true) IS NOT NULL
  AND current_setting('quizopia.proctoring_purge_quiz',true)::uuid IS NOT DISTINCT FROM p_quiz
  AND EXISTS(SELECT 1 FROM public.quiz_revocation_fences f
   WHERE f.quiz_id=p_quiz AND f.ack_at IS NOT NULL)
 $$;

CREATE OR REPLACE FUNCTION monitoring_session_guard() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN
     IF TG_OP='DELETE' AND proctoring_purge_authorized(OLD.quiz_id) THEN RETURN OLD; END IF;
     IF TG_OP='DELETE' OR NEW.id<>OLD.id OR NEW.attempt_id<>OLD.attempt_id OR NEW.publication_id<>OLD.publication_id
         OR NEW.classroom_id<>OLD.classroom_id OR NEW.student_id<>OLD.student_id OR NEW.deadline_at<>OLD.deadline_at
         OR NEW.created_at<>OLD.created_at OR OLD.status='ENDED' THEN
         RAISE EXCEPTION 'monitoring session is immutable' USING ERRCODE='23514';
     END IF;
     RETURN NEW;
 END $$;

CREATE OR REPLACE FUNCTION monitoring_append_guard() RETURNS trigger LANGUAGE plpgsql AS $$
 DECLARE quiz uuid;
 BEGIN
     -- Only the owning retention function may remove already-expired records.
     IF TG_OP='DELETE' AND OLD.expires_at IS NOT NULL AND OLD.expires_at<=clock_timestamp()
         AND current_user=(SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid=TG_RELID) THEN
         RETURN OLD;
     END IF;
     IF TG_OP='DELETE' THEN
         SELECT quiz_id INTO quiz FROM public.monitoring_sessions WHERE id=OLD.session_id;
         IF proctoring_purge_authorized(quiz) THEN RETURN OLD; END IF;
     END IF;
     RAISE EXCEPTION 'monitoring history is append only' USING ERRCODE='23514';
 END $$;

CREATE OR REPLACE FUNCTION monitoring_media_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
 DECLARE quiz uuid;
 BEGIN
     IF TG_OP='DELETE' THEN
         SELECT quiz_id INTO quiz FROM public.monitoring_sessions WHERE id=OLD.session_id;
         IF proctoring_purge_authorized(quiz) THEN RETURN OLD; END IF;
         RAISE EXCEPTION 'media history is immutable' USING ERRCODE='23514';
     END IF;
     IF TG_TABLE_NAME='monitoring_media_rooms' THEN
         IF NEW.session_id<>OLD.session_id OR NEW.room_name<>OLD.room_name OR NEW.created_at<>OLD.created_at OR OLD.deleted_at IS NOT NULL THEN
             RAISE EXCEPTION 'media room binding is immutable' USING ERRCODE='23514';
         END IF;
         RETURN NEW;
     END IF;
     IF NEW.id<>OLD.id OR NEW.session_id<>OLD.session_id
         OR NEW.actor_id<>OLD.actor_id OR NEW.viewer_role<>OLD.viewer_role
         OR NEW.participant_identity<>OLD.participant_identity OR NEW.created_at<>OLD.created_at
         OR OLD.state='REVOKED' OR (OLD.state='REVOKE_PENDING' AND (NEW.state NOT IN ('REVOKE_PENDING','REVOKED') OR NEW.lease_until<>OLD.lease_until))
         OR (OLD.state='ACTIVE' AND NEW.state='PREPARED') THEN
         RAISE EXCEPTION 'media binding or terminal fence is immutable' USING ERRCODE='23514';
     END IF;
     RETURN NEW;
 END $$;

-- Bounded, idempotent, FK-ordered quiz-scoped purge. Requires an acknowledged fence for
-- exactly this quiz and operation; deletes only rows whose session belongs to that quiz;
-- verifies zero remaining rows before writing the final receipt. Triggers stay enabled.
CREATE FUNCTION monitoring_purge_quiz(p_quiz uuid, p_operation uuid, p_batch_limit integer)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
 DECLARE removed bigint; total bigint; step_name varchar;
 BEGIN
     IF p_batch_limit IS NULL OR p_batch_limit<1 OR p_batch_limit>1000 THEN
         RAISE EXCEPTION 'invalid purge batch bound';
     END IF;
     IF NOT EXISTS(SELECT 1 FROM quiz_revocation_fences
         WHERE quiz_id=p_quiz AND operation_id=p_operation AND ack_at IS NOT NULL) THEN
         RAISE EXCEPTION 'quiz purge requires an acknowledged fence';
     END IF;
     PERFORM set_config('quizopia.proctoring_purge_quiz',p_quiz::text,true);
     FOR step_name IN SELECT unnest(ARRAY['monitoring_media_grants','monitoring_media_rooms',
             'monitoring_access_audit','monitoring_evidence','monitoring_sessions']) LOOP
         total:=0;
         LOOP
             CASE step_name
             WHEN 'monitoring_media_grants' THEN
                 DELETE FROM monitoring_media_grants g WHERE g.id IN (
                     SELECT g.id FROM monitoring_media_grants g JOIN monitoring_media_rooms r ON r.session_id=g.session_id
                      JOIN monitoring_sessions s ON s.id=r.session_id
                      WHERE s.quiz_id=p_quiz ORDER BY g.id LIMIT p_batch_limit);
             WHEN 'monitoring_media_rooms' THEN
                 DELETE FROM monitoring_media_rooms r WHERE r.session_id IN (
                     SELECT r.session_id FROM monitoring_media_rooms r JOIN monitoring_sessions s ON s.id=r.session_id
                      WHERE s.quiz_id=p_quiz ORDER BY r.session_id LIMIT p_batch_limit);
             WHEN 'monitoring_access_audit' THEN
                 DELETE FROM monitoring_access_audit a WHERE a.id IN (
                     SELECT a.id FROM monitoring_access_audit a JOIN monitoring_sessions s ON s.id=a.session_id
                      WHERE s.quiz_id=p_quiz ORDER BY a.id LIMIT p_batch_limit);
             WHEN 'monitoring_evidence' THEN
                 DELETE FROM monitoring_evidence e WHERE e.ordinal IN (
                     SELECT e.ordinal FROM monitoring_evidence e JOIN monitoring_sessions s ON s.id=e.session_id
                      WHERE s.quiz_id=p_quiz ORDER BY e.ordinal LIMIT p_batch_limit);
             ELSE
                 DELETE FROM monitoring_sessions s WHERE s.id IN (
                     SELECT s.id FROM monitoring_sessions s WHERE s.quiz_id=p_quiz ORDER BY s.id LIMIT p_batch_limit);
             END CASE;
             GET DIAGNOSTICS removed=ROW_COUNT;
             total:=total+removed;
             EXIT WHEN removed<p_batch_limit;
         END LOOP;
         INSERT INTO deletion_purge_receipts(operation_id,quiz_id,step,rows_removed,completed_at)
         VALUES(p_operation,p_quiz,step_name,total,clock_timestamp())
         ON CONFLICT (operation_id,step) DO UPDATE
          SET rows_removed=deletion_purge_receipts.rows_removed+EXCLUDED.rows_removed,
              completed_at=EXCLUDED.completed_at;
     END LOOP;
     -- Actual zero-remaining verification before any PURGED claim.
     IF EXISTS(SELECT 1 FROM monitoring_sessions WHERE quiz_id=p_quiz)
         OR EXISTS(SELECT 1 FROM monitoring_evidence e JOIN monitoring_sessions s ON s.id=e.session_id WHERE s.quiz_id=p_quiz)
         OR EXISTS(SELECT 1 FROM monitoring_access_audit a JOIN monitoring_sessions s ON s.id=a.session_id WHERE s.quiz_id=p_quiz)
         OR EXISTS(SELECT 1 FROM monitoring_media_rooms r JOIN monitoring_sessions s ON s.id=r.session_id WHERE s.quiz_id=p_quiz)
         OR EXISTS(SELECT 1 FROM monitoring_media_grants g JOIN monitoring_media_rooms r ON r.session_id=g.session_id
             JOIN monitoring_sessions s ON s.id=r.session_id WHERE s.quiz_id=p_quiz) THEN
         RAISE EXCEPTION 'quiz purge left dependent rows behind';
     END IF;
     INSERT INTO deletion_purge_receipts(operation_id,quiz_id,step,rows_removed,completed_at)
     VALUES(p_operation,p_quiz,'verified_zero',0,clock_timestamp())
     ON CONFLICT (operation_id,step) DO UPDATE SET completed_at=EXCLUDED.completed_at;
     RETURN true;
 END $$;
REVOKE ALL ON FUNCTION monitoring_purge_quiz(uuid,uuid,integer) FROM PUBLIC;
