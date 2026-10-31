-- PO academic-demo tier: empty by default. Only separately seeded fictional subjects
-- can generate this new stream. No W3 monitoring trigger or academic grade TTL changes.
CREATE TABLE assessment_demo_subjects (
 actor_id uuid PRIMARY KEY,
 fixture text NOT NULL CHECK(fixture='FICTIONAL_ACADEMIC_DEMO_V1')
);
CREATE TABLE assessment_audit_positions (
 attempt_id uuid PRIMARY KEY REFERENCES attempts(id) ON DELETE RESTRICT,
 last_ordinal bigint NOT NULL CHECK(last_ordinal>0), expired_history boolean NOT NULL DEFAULT false
);
CREATE TABLE assessment_audit_pending (
 event_id uuid PRIMARY KEY,
 attempt_id uuid NOT NULL,
 publication_id uuid NOT NULL,
 actor_id uuid NOT NULL,
 ordinal bigint NOT NULL CHECK(ordinal>0),
 event_type varchar(32) NOT NULL,
 question_number integer,
 assessment_revision bigint,
 occurred_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 operation_kind varchar(32), terminal_reason varchar(32), result_id uuid, notice_id uuid,
 UNIQUE(attempt_id,ordinal),
 FOREIGN KEY(attempt_id,publication_id) REFERENCES attempts(id,publication_id) ON DELETE RESTRICT,
 CHECK(expires_at=occurred_at+interval '2160 hours'),
 CHECK(event_type IN ('ATTEMPT_STARTED','ANSWER_PERSISTED','ATTEMPT_SUBMITTED','ATTEMPT_ABANDONED','CLOSURE_NOTICE_CONFIRMED','RESULT_GRADED'))
);
CREATE TABLE assessment_observation_choices (
 attempt_id uuid PRIMARY KEY REFERENCES attempts(id) ON DELETE RESTRICT,
 actor_id uuid NOT NULL, enabled boolean NOT NULL, stream_id uuid,
 notice_version text NOT NULL CHECK(notice_version='ACADEMIC_DEMO_V1'),
 decided_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 CHECK(expires_at=decided_at+interval '720 hours'),
 CHECK((enabled AND stream_id IS NOT NULL) OR (NOT enabled AND stream_id IS NULL))
);
CREATE TABLE assessment_audit_holds (
 id uuid PRIMARY KEY,
 attempt_id uuid NOT NULL REFERENCES attempts(id) ON DELETE RESTRICT,
 requested_by name NOT NULL, reason varchar(32) NOT NULL CHECK(reason IN ('INCIDENT_REVIEW','LEGAL_REQUEST')),
 requested_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 reviewed_by name, reviewed_at timestamptz,
 CHECK(expires_at>requested_at AND expires_at<=requested_at+interval '720 hours'),
 CHECK((reviewed_by IS NULL AND reviewed_at IS NULL) OR
       (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND reviewed_by<>requested_by))
);
ALTER TABLE assessment_answer_audit ADD COLUMN operation_kind varchar(32),
 ADD COLUMN terminal_reason varchar(32), ADD COLUMN result_id uuid, ADD COLUMN notice_id uuid;
CREATE INDEX academic_pending_order ON assessment_audit_pending(publication_id,attempt_id,ordinal);
CREATE INDEX academic_audit_expiry ON assessment_answer_audit(expires_at,publication_id);
CREATE INDEX academic_receipt_expiry ON assessment_sensitive_read_receipts(expires_at,publication_id);

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_next_audit_ordinal(p_attempt uuid) RETURNS bigint
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE result bigint; BEGIN
 INSERT INTO assessment_audit_positions(attempt_id,last_ordinal)
 VALUES(p_attempt,(SELECT coalesce(max(ordinal),0)+1 FROM assessment_answer_audit WHERE attempt_id=p_attempt))
 ON CONFLICT(attempt_id) DO UPDATE SET last_ordinal=greatest(assessment_audit_positions.last_ordinal,
   (SELECT coalesce(max(ordinal),0) FROM assessment_answer_audit WHERE attempt_id=p_attempt))+1
 RETURNING last_ordinal INTO result;
 RETURN result;
END $body$;

CREATE FUNCTION %1$I.assessment_queue_audit(p_attempt uuid,p_type text,p_question integer,p_revision bigint,
 p_operation text,p_reason text,p_result uuid,p_notice uuid) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE a record; t timestamptz; BEGIN
 IF current_setting('quizopia.academic_demo',true) IS DISTINCT FROM 'true' THEN RETURN; END IF;
 SELECT x.publication_id,x.student_id INTO a FROM attempts x JOIN publications p ON p.id=x.publication_id
 WHERE x.id=p_attempt AND EXISTS(SELECT FROM assessment_demo_subjects WHERE actor_id=x.student_id)
 AND EXISTS(SELECT FROM assessment_demo_subjects WHERE actor_id=p.owner_id);
 IF NOT FOUND THEN RETURN; END IF;
 t:=assessment_time();
 INSERT INTO assessment_audit_pending VALUES(gen_random_uuid(),p_attempt,a.publication_id,a.student_id,
 assessment_next_audit_ordinal(p_attempt),p_type,p_question,p_revision,t,t+interval '2160 hours',p_operation,p_reason,p_result,p_notice);
END $body$;

CREATE FUNCTION %1$I.assessment_capture_audit() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
BEGIN
 IF TG_TABLE_NAME='attempts' THEN
   IF TG_OP='INSERT' THEN
     PERFORM assessment_queue_audit(NEW.id,'ATTEMPT_STARTED',NULL,NULL,'START',NULL,NULL,NULL);
   ELSIF OLD.state='ACTIVE' AND NEW.state='SUBMITTED' THEN
     PERFORM assessment_queue_audit(NEW.id,'ATTEMPT_SUBMITTED',NULL,NEW.revision,'FINALIZATION',NEW.terminal_reason,NEW.result_id,NULL);
     PERFORM assessment_queue_audit(NEW.id,'RESULT_GRADED',NULL,NEW.revision,'FINALIZATION',NEW.terminal_reason,NEW.result_id,NULL);
   ELSIF OLD.state='ACTIVE' AND NEW.state='ABANDONED' THEN
     PERFORM assessment_queue_audit(NEW.id,'ATTEMPT_ABANDONED',NULL,NEW.revision,'ABANDON',NEW.terminal_reason,NULL,NULL);
   END IF;
 ELSIF TG_TABLE_NAME='attempt_operations' THEN
   IF NEW.action='SAVE' THEN
     PERFORM assessment_queue_audit(NEW.attempt_id,'ANSWER_PERSISTED',NEW.question_number,NEW.accepted_revision,'SAVE',NULL,NULL,NULL);
   END IF;
 ELSIF TG_TABLE_NAME='closure_request_members' THEN
   IF NEW.state='NOTICE_CONFIRMED' AND OLD.state IS DISTINCT FROM NEW.state THEN
     PERFORM assessment_queue_audit(NEW.attempt_id,'CLOSURE_NOTICE_CONFIRMED',NULL,NULL,'NOTICE_CONFIRMATION',NULL,NULL,NEW.notification_id);
   END IF;
 END IF;
 RETURN NEW;
END $body$;

CREATE FUNCTION %1$I.assessment_ack_audit(p_event uuid) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
BEGIN
 PERFORM 1 FROM purge_admission d JOIN publications b ON b.quiz_id=d.quiz_id JOIN assessment_audit_pending p ON p.publication_id=b.id WHERE p.event_id=p_event FOR SHARE OF d NOWAIT;
 -- Only a matching durable encrypted projection, expiry or an erasure fence can drain a pending fact.
 PERFORM set_config('quizopia.audit_cleanup','true',true);
 UPDATE assessment_audit_positions pos SET expired_history=true WHERE NOT expired_history AND EXISTS(
   SELECT FROM assessment_audit_pending p JOIN publications b ON b.id=p.publication_id
   WHERE p.event_id=p_event AND p.attempt_id=pos.attempt_id AND p.expires_at<=assessment_time()
   AND NOT EXISTS(SELECT FROM quiz_revocation_fences WHERE quiz_id=b.quiz_id));
 DELETE FROM assessment_audit_pending p WHERE p.event_id=p_event
 AND NOT EXISTS(SELECT FROM assessment_audit_holds h WHERE h.attempt_id=p.attempt_id AND h.reviewed_by IS NOT NULL AND h.expires_at>assessment_time())
 AND NOT EXISTS(SELECT FROM retention_holds h JOIN publications b ON b.quiz_id=h.quiz_id WHERE b.id=p.publication_id AND (h.expires_at IS NULL OR h.expires_at>assessment_time()))
 AND (p.expires_at<=assessment_time()
 OR EXISTS(SELECT FROM assessment_answer_audit a WHERE a.attempt_id=p.attempt_id AND a.event_id=p.event_id
   AND a.source='SERVER_AUTHORITY' AND a.ordinal=p.ordinal AND a.event_type=p.event_type
   AND a.actor_id=p.actor_id AND a.occurred_at=p.occurred_at AND a.expires_at=p.expires_at
   AND a.question_number IS NOT DISTINCT FROM p.question_number AND a.assessment_revision IS NOT DISTINCT FROM p.assessment_revision
   AND a.operation_kind IS NOT DISTINCT FROM p.operation_kind AND a.terminal_reason IS NOT DISTINCT FROM p.terminal_reason
   AND a.result_id IS NOT DISTINCT FROM p.result_id AND a.notice_id IS NOT DISTINCT FROM p.notice_id));
END $body$;

CREATE OR REPLACE FUNCTION %1$I.assessment_minimal_audit_immutable() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,%1$I,pg_temp AS $body$
BEGIN
 IF TG_OP='DELETE' AND current_user='quizopia_assessment_owner'
   AND current_setting('quizopia.audit_cleanup',true)='true'
   AND OLD.expires_at<=assessment_time()
   AND NOT EXISTS(SELECT FROM assessment_audit_holds h WHERE h.attempt_id=OLD.attempt_id
     AND h.reviewed_by IS NOT NULL AND h.expires_at>assessment_time())
   AND NOT EXISTS(SELECT FROM retention_holds h JOIN publications p ON p.quiz_id=h.quiz_id WHERE p.id=OLD.publication_id AND (h.expires_at IS NULL OR h.expires_at>assessment_time())) THEN RETURN OLD; END IF;
 IF TG_OP='DELETE' AND assessment_purge_authorized()
   AND EXISTS(SELECT FROM publications p WHERE p.id=OLD.publication_id
      AND p.quiz_id=nullif(current_setting('quizopia.assessment_purge_quiz',true),'')::uuid) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'sensitive audit is append only';
END $body$;

CREATE FUNCTION %1$I.assessment_demo_child_guard() RETURNS trigger
 LANGUAGE plpgsql SET search_path=pg_catalog,%1$I,pg_temp AS $body$
BEGIN
 IF current_user='quizopia_assessment_owner' AND TG_OP='DELETE'
   AND (current_setting('quizopia.audit_cleanup',true)='true' OR assessment_purge_authorized()) THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'restricted audit custody';
END $body$;

CREATE FUNCTION %1$I.assessment_request_audit_hold(p_attempt uuid,p_reason text,p_expiry timestamptz) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE result uuid:=gen_random_uuid(); t timestamptz:=assessment_time(); BEGIN
 IF p_expiry IS NULL OR NOT isfinite(p_expiry) OR p_expiry<=t OR p_expiry>t+interval '720 hours'
   OR NOT EXISTS(SELECT FROM attempts a JOIN assessment_demo_subjects s ON s.actor_id=a.student_id WHERE a.id=p_attempt)
 THEN RAISE EXCEPTION 'invalid demo hold'; END IF;
 PERFORM 1 FROM publications p JOIN attempts a ON a.publication_id=p.id WHERE a.id=p_attempt FOR UPDATE OF p;
 IF NOT EXISTS(SELECT FROM assessment_answer_audit WHERE attempt_id=p_attempt UNION ALL SELECT FROM assessment_audit_pending WHERE attempt_id=p_attempt UNION ALL SELECT FROM assessment_sensitive_read_receipts WHERE attempt_id=p_attempt UNION ALL SELECT FROM assessment_observation_choices WHERE attempt_id=p_attempt) THEN RAISE EXCEPTION 'audit content no longer available'; END IF;
 INSERT INTO assessment_audit_holds VALUES(result,p_attempt,session_user,p_reason,t,p_expiry,NULL,NULL);
 RETURN result;
END $body$;

CREATE FUNCTION %1$I.assessment_review_audit_hold(p_hold uuid) RETURNS void
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
BEGIN
 PERFORM 1 FROM publications p JOIN attempts a ON a.publication_id=p.id JOIN assessment_audit_holds h ON h.attempt_id=a.id
 WHERE h.id=p_hold FOR UPDATE OF p;
 IF NOT EXISTS(SELECT FROM assessment_audit_holds h WHERE h.id=p_hold AND (EXISTS(SELECT FROM assessment_answer_audit a WHERE a.attempt_id=h.attempt_id) OR EXISTS(SELECT FROM assessment_audit_pending a WHERE a.attempt_id=h.attempt_id) OR EXISTS(SELECT FROM assessment_sensitive_read_receipts a WHERE a.attempt_id=h.attempt_id) OR EXISTS(SELECT FROM assessment_observation_choices a WHERE a.attempt_id=h.attempt_id))) THEN RAISE EXCEPTION 'audit content no longer available'; END IF;
 UPDATE assessment_audit_holds SET reviewed_by=session_user,reviewed_at=assessment_time()
 WHERE id=p_hold AND requested_by<>session_user AND reviewed_by IS NULL AND expires_at>assessment_time();
 IF NOT FOUND THEN RAISE EXCEPTION 'separate current reviewer required'; END IF;
END $body$;

CREATE FUNCTION %1$I.assessment_audit_hold_admission() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE started boolean; q uuid; BEGIN
 IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'hold requires READ COMMITTED'; END IF;
 IF TG_OP='UPDATE' AND (ROW(NEW.attempt_id,NEW.requested_by,NEW.reason,NEW.requested_at,NEW.expires_at) IS DISTINCT FROM ROW(OLD.attempt_id,OLD.requested_by,OLD.reason,OLD.requested_at,OLD.expires_at) OR OLD.reviewed_by IS NOT NULL) THEN RAISE EXCEPTION 'hold approval is immutable'; END IF;
  SELECT p.quiz_id INTO q FROM publications p JOIN attempts a ON a.publication_id=p.id WHERE a.id=NEW.attempt_id;
  -- Create-or-share the V11 admission boundary. NOWAIT keeps the caller's
  -- publication lock from ever waiting behind purge's admission-first order.
  INSERT INTO purge_admission(quiz_id) VALUES(q) ON CONFLICT DO NOTHING;
  SELECT begun INTO started FROM purge_admission WHERE quiz_id=q FOR UPDATE NOWAIT;
  IF started THEN RAISE EXCEPTION 'purge already admitted: preservation cannot be promised'; END IF;
  RETURN NEW;
END $body$;

CREATE FUNCTION %1$I.assessment_expire_audit(p_budget integer) RETURNS integer
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE p record; n integer; used integer:=0; BEGIN
 IF p_budget IS NULL OR p_budget<1 OR p_budget>5000 THEN RAISE EXCEPTION 'invalid expiry budget'; END IF;
 IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'expiry requires READ COMMITTED'; END IF;
 PERFORM set_config('quizopia.audit_cleanup','true',true);
 FOR p IN SELECT b.id,b.quiz_id FROM publications b WHERE EXISTS(
   SELECT FROM assessment_answer_audit a WHERE a.publication_id=b.id AND a.expires_at<=assessment_time()
   UNION ALL SELECT FROM assessment_sensitive_read_receipts r WHERE r.publication_id=b.id AND r.expires_at<=assessment_time()
   UNION ALL SELECT FROM assessment_audit_pending x WHERE x.publication_id=b.id AND x.expires_at<=assessment_time()
   UNION ALL SELECT FROM assessment_observation_choices c JOIN attempts a ON a.id=c.attempt_id WHERE a.publication_id=b.id AND c.expires_at<=assessment_time()
   UNION ALL SELECT FROM assessment_audit_holds h JOIN attempts a ON a.id=h.attempt_id WHERE a.publication_id=b.id AND h.expires_at<=assessment_time())
 ORDER BY b.id FOR UPDATE OF b SKIP LOCKED LIMIT p_budget LOOP
    -- Publication-first, but never wait for admission while retaining its row lock:
    -- purge owns admission first, and may need this Publication. Retry next tick.
    -- Creating-or-sharing the admission row also fences concurrent hold INSERTs
    -- (they take this row FOR UPDATE), so the recheck below cannot miss one.
    BEGIN
      INSERT INTO purge_admission(quiz_id) VALUES(p.quiz_id) ON CONFLICT DO NOTHING;
      PERFORM 1 FROM purge_admission WHERE quiz_id=p.quiz_id FOR SHARE NOWAIT;
    EXCEPTION WHEN lock_not_available THEN CONTINUE;
    END;
   IF EXISTS(SELECT FROM retention_holds WHERE quiz_id=p.quiz_id AND (expires_at IS NULL OR expires_at>assessment_time())) THEN CONTINUE; END IF;
   UPDATE assessment_audit_positions pos SET expired_history=true WHERE NOT expired_history AND NOT EXISTS(SELECT FROM quiz_revocation_fences WHERE quiz_id=p.quiz_id) AND (EXISTS(SELECT FROM assessment_answer_audit a WHERE a.attempt_id=pos.attempt_id AND a.publication_id=p.id AND a.expires_at<=assessment_time()) OR EXISTS(SELECT FROM assessment_audit_pending a WHERE a.attempt_id=pos.attempt_id AND a.publication_id=p.id AND a.expires_at<=assessment_time()));
   DELETE FROM assessment_answer_audit a WHERE a.ctid IN (SELECT x.ctid FROM assessment_answer_audit x
    WHERE x.publication_id=p.id AND x.expires_at<=assessment_time() AND NOT EXISTS(SELECT FROM assessment_audit_holds h WHERE h.attempt_id=x.attempt_id AND h.reviewed_by IS NOT NULL AND h.expires_at>assessment_time()) LIMIT p_budget-used);
   GET DIAGNOSTICS n=ROW_COUNT; used:=used+n; IF used=p_budget THEN RETURN used; END IF;
   DELETE FROM assessment_sensitive_read_receipts a WHERE a.ctid IN (SELECT x.ctid FROM assessment_sensitive_read_receipts x
    WHERE x.publication_id=p.id AND x.expires_at<=assessment_time() AND NOT EXISTS(SELECT FROM assessment_audit_holds h WHERE h.attempt_id=x.attempt_id AND h.reviewed_by IS NOT NULL AND h.expires_at>assessment_time()) LIMIT p_budget-used);
   GET DIAGNOSTICS n=ROW_COUNT; used:=used+n; IF used=p_budget THEN RETURN used; END IF;
   DELETE FROM assessment_audit_pending a WHERE a.ctid IN (SELECT x.ctid FROM assessment_audit_pending x
    WHERE x.publication_id=p.id AND x.expires_at<=assessment_time() AND NOT EXISTS(SELECT FROM assessment_audit_holds h WHERE h.attempt_id=x.attempt_id AND h.reviewed_by IS NOT NULL AND h.expires_at>assessment_time()) LIMIT p_budget-used);
   GET DIAGNOSTICS n=ROW_COUNT; used:=used+n; IF used=p_budget THEN RETURN used; END IF;
   DELETE FROM assessment_observation_choices a WHERE a.ctid IN (SELECT x.ctid FROM assessment_observation_choices x JOIN attempts t ON t.id=x.attempt_id
    WHERE t.publication_id=p.id AND x.expires_at<=assessment_time() AND NOT EXISTS(SELECT FROM assessment_audit_holds h WHERE h.attempt_id=x.attempt_id AND h.reviewed_by IS NOT NULL AND h.expires_at>assessment_time()) LIMIT p_budget-used);
   GET DIAGNOSTICS n=ROW_COUNT; used:=used+n; IF used=p_budget THEN RETURN used; END IF;
   DELETE FROM assessment_audit_holds h WHERE h.ctid IN (SELECT x.ctid FROM assessment_audit_holds x JOIN attempts a ON a.id=x.attempt_id WHERE a.publication_id=p.id AND x.expires_at<=assessment_time() LIMIT p_budget-used);
   GET DIAGNOSTICS n=ROW_COUNT; used:=used+n; IF used=p_budget THEN RETURN used; END IF;
 END LOOP;
 RETURN used;
END $body$;
$ddl$,current_schema()); END $migration$;

CREATE TRIGGER academic_audit_start_terminal AFTER INSERT OR UPDATE ON attempts FOR EACH ROW EXECUTE FUNCTION assessment_capture_audit();
CREATE TRIGGER academic_audit_save AFTER INSERT ON attempt_operations FOR EACH ROW EXECUTE FUNCTION assessment_capture_audit();
CREATE TRIGGER academic_audit_notice AFTER UPDATE ON closure_request_members FOR EACH ROW EXECUTE FUNCTION assessment_capture_audit();
CREATE TRIGGER a_purge_write_fence BEFORE INSERT OR UPDATE ON assessment_audit_pending FOR EACH ROW EXECUTE FUNCTION assessment_purge_write_fence();
CREATE TRIGGER a_purge_write_fence BEFORE INSERT OR UPDATE ON assessment_observation_choices FOR EACH ROW EXECUTE FUNCTION assessment_purge_write_fence();
CREATE TRIGGER a_purge_write_fence BEFORE INSERT OR UPDATE ON assessment_audit_positions FOR EACH ROW EXECUTE FUNCTION assessment_purge_write_fence();
CREATE TRIGGER audit_hold_admission BEFORE INSERT OR UPDATE ON assessment_audit_holds FOR EACH ROW EXECUTE FUNCTION assessment_audit_hold_admission();
CREATE TRIGGER pending_audit_guard BEFORE UPDATE OR DELETE ON assessment_audit_pending FOR EACH ROW EXECUTE FUNCTION assessment_demo_child_guard();

-- Extend FB-015 in front of the V12 child-first wrapper without rewriting old steps.
ALTER FUNCTION assessment_purge_quiz(uuid,uuid,integer) RENAME TO assessment_purge_quiz_v12;
REVOKE ALL ON FUNCTION assessment_purge_quiz_v12(uuid,uuid,integer) FROM PUBLIC,assessment;
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_purge_quiz(p_quiz uuid,p_operation uuid,p_budget integer) RETURNS text
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE n integer; used integer:=0; target text; BEGIN
 IF p_budget IS NULL OR p_budget<1 OR p_budget>50000 THEN RAISE EXCEPTION 'invalid purge budget'; END IF;
 IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'purge requires READ COMMITTED'; END IF;
 INSERT INTO purge_admission(quiz_id) VALUES(p_quiz) ON CONFLICT DO NOTHING;
 PERFORM 1 FROM purge_admission WHERE quiz_id=p_quiz FOR UPDATE;
 IF NOT EXISTS(SELECT FROM quiz_revocation_fences WHERE quiz_id=p_quiz AND operation_id=p_operation AND ack_at IS NOT NULL) THEN RAISE EXCEPTION 'acknowledged matching fence required'; END IF;
 IF EXISTS(SELECT FROM retention_holds WHERE quiz_id=p_quiz AND (expires_at IS NULL OR expires_at>assessment_time()))
 OR EXISTS(SELECT FROM assessment_audit_holds h JOIN attempts a ON a.id=h.attempt_id JOIN publications p ON p.id=a.publication_id
   WHERE p.quiz_id=p_quiz AND h.reviewed_by IS NOT NULL AND h.expires_at>assessment_time()) THEN RETURN 'BLOCKED_BY_RETENTION'; END IF;
 UPDATE purge_admission SET begun=true WHERE quiz_id=p_quiz AND NOT begun;
 PERFORM set_config('quizopia.assessment_purge_quiz',p_quiz::text,true);
 PERFORM set_config('quizopia.audit_cleanup','true',true);
 FOREACH target IN ARRAY ARRAY['assessment_audit_pending','assessment_observation_choices','assessment_audit_positions','assessment_audit_holds'] LOOP
   EXECUTE format('DELETE FROM %%I WHERE ctid IN (SELECT t.ctid FROM %%I t JOIN attempts a ON a.id=t.attempt_id JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=$1 LIMIT $2)',target,target) USING p_quiz,p_budget-used;
   GET DIAGNOSTICS n=ROW_COUNT; used:=used+n;
   INSERT INTO deletion_purge_receipts VALUES(p_operation,p_quiz,target,n,clock_timestamp()) ON CONFLICT(operation_id,step) DO UPDATE SET rows_removed=deletion_purge_receipts.rows_removed+EXCLUDED.rows_removed;
   IF used=p_budget THEN RETURN 'IN_PROGRESS'; END IF;
 END LOOP;
 RETURN assessment_purge_quiz_v12(p_quiz,p_operation,p_budget-used);
END $body$;
$ddl$,current_schema()); END $migration$;

ALTER TABLE assessment_demo_subjects OWNER TO quizopia_assessment_owner;
ALTER TABLE assessment_audit_positions OWNER TO quizopia_assessment_owner;
ALTER TABLE assessment_audit_pending OWNER TO quizopia_assessment_owner;
ALTER TABLE assessment_observation_choices OWNER TO quizopia_assessment_owner;
ALTER TABLE assessment_audit_holds OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_next_audit_ordinal(uuid) OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_queue_audit(uuid,text,integer,bigint,text,text,uuid,uuid) OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_capture_audit() OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_ack_audit(uuid) OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_demo_child_guard() OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_request_audit_hold(uuid,text,timestamptz) OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_review_audit_hold(uuid) OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_audit_hold_admission() OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_expire_audit(integer) OWNER TO quizopia_assessment_owner;
ALTER FUNCTION assessment_purge_quiz(uuid,uuid,integer) OWNER TO quizopia_assessment_owner;
REVOKE ALL ON assessment_demo_subjects,assessment_audit_positions,assessment_audit_pending,assessment_observation_choices,assessment_audit_holds FROM PUBLIC,assessment,quizopia_assessment_custodian;
GRANT SELECT ON assessment_demo_subjects,assessment_audit_pending,assessment_observation_choices,assessment_audit_positions TO assessment;
-- PostgreSQL row-lock privilege only; immutable trigger still rejects ordinary UPDATE.
GRANT UPDATE(event_id) ON assessment_audit_pending TO assessment;
GRANT SELECT,INSERT,UPDATE ON assessment_observation_choices TO assessment;
REVOKE ALL ON FUNCTION assessment_next_audit_ordinal(uuid),assessment_queue_audit(uuid,text,integer,bigint,text,text,uuid,uuid),assessment_capture_audit(),assessment_ack_audit(uuid),assessment_demo_child_guard(),assessment_request_audit_hold(uuid,text,timestamptz),assessment_review_audit_hold(uuid),assessment_expire_audit(integer),assessment_purge_quiz(uuid,uuid,integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION assessment_audit_hold_admission() FROM PUBLIC,assessment,quizopia_assessment_custodian;
GRANT EXECUTE ON FUNCTION assessment_next_audit_ordinal(uuid),assessment_ack_audit(uuid),assessment_purge_quiz(uuid,uuid,integer) TO assessment;
GRANT EXECUTE ON FUNCTION assessment_expire_audit(integer),assessment_request_audit_hold(uuid,text,timestamptz) TO quizopia_assessment_custodian;
-- Reviewer uses a separately authenticated owner-authorized administrative connection;
-- the custodian and runtime cannot approve their own holds or write the fixture registry.
