-- V4 persistence foundation only. V5 installs closure evidence FKs/checks later.
LOCK TABLE publications, publication_snapshots IN ACCESS EXCLUSIVE MODE;
CREATE DOMAIN assessment_digest AS varchar(64)
  CHECK ((VALUE ~ '^[0-9a-f]{64}$') IS TRUE);
CREATE DOMAIN assessment_revision AS bigint
  CHECK ((VALUE >= 0) IS TRUE);

CREATE TABLE attempts (
  id uuid PRIMARY KEY,
  publication_id uuid NOT NULL REFERENCES publications(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  student_id uuid NOT NULL,
  ordinal bigint NOT NULL CHECK ((ordinal >= 1) IS TRUE),
  snapshot_id uuid NOT NULL REFERENCES publication_snapshots(publication_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  grading_policy_version integer NOT NULL CHECK ((grading_policy_version = 1) IS TRUE),
  answer_schema_version integer NOT NULL CHECK ((answer_schema_version = 1) IS TRUE),
  state varchar(10) NOT NULL CHECK ((state IN ('ACTIVE','SUBMITTED','ABANDONED')) IS TRUE),
  duration_minutes integer NOT NULL
    CHECK ((duration_minutes = 0 OR duration_minutes BETWEEN 5 AND 240) IS TRUE),
  started_at timestamptz NOT NULL CHECK ((isfinite(started_at)) IS TRUE),
  deadline_at timestamptz,
  revision assessment_revision NOT NULL DEFAULT 0,
  submitted_answers_id uuid,
  result_id uuid,
  terminal_reason varchar(24),
  CONSTRAINT attempts_v4_no_notice_cutoff CHECK (terminal_reason IS DISTINCT FROM 'TEACHER_NOTICE_CUTOFF'),
  finalized_at timestamptz,
  UNIQUE (publication_id, student_id, ordinal),
  UNIQUE (id, publication_id, student_id),
  UNIQUE (id, snapshot_id, grading_policy_version),
  CHECK ((snapshot_id = publication_id) IS TRUE),
  CHECK (((duration_minutes = 0 AND deadline_at IS NULL) OR
    (duration_minutes > 0 AND deadline_at IS NOT NULL AND isfinite(deadline_at)
      AND deadline_at = started_at + duration_minutes * interval '1 minute')) IS TRUE),
  CHECK ((
    (state = 'ACTIVE' AND terminal_reason IS NULL AND finalized_at IS NULL
      AND submitted_answers_id IS NULL AND result_id IS NULL) OR
    (state = 'ABANDONED' AND terminal_reason = 'STUDENT_ABANDON'
      AND finalized_at IS NOT NULL AND isfinite(finalized_at)
      AND finalized_at >= started_at
      AND submitted_answers_id IS NULL AND result_id IS NULL) OR
    (state = 'SUBMITTED' AND terminal_reason IN
      ('MANUAL_SUBMIT','BOUNDED_TIMEOUT','TEACHER_NOTICE_CUTOFF')
      AND finalized_at IS NOT NULL AND isfinite(finalized_at)
      AND finalized_at >= started_at
      AND submitted_answers_id IS NOT NULL AND result_id IS NOT NULL)
  ) IS TRUE)
);
CREATE UNIQUE INDEX one_active_attempt_per_student
  ON attempts(publication_id, student_id) WHERE state = 'ACTIVE';
CREATE INDEX bounded_attempts_due
  ON attempts(deadline_at, publication_id, id)
  WHERE state = 'ACTIVE' AND deadline_at IS NOT NULL;
CREATE INDEX active_publication_attempts
  ON attempts(publication_id, id) WHERE state = 'ACTIVE';
CREATE INDEX publication_attempt_page ON attempts(publication_id, id);
-- Unique publication/student/ordinal index also supports latest ordinal lookup.

CREATE TABLE attempt_answers (
  attempt_id uuid NOT NULL REFERENCES attempts(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  question_number integer NOT NULL CHECK ((question_number >= 1) IS TRUE),
  answer jsonb NOT NULL CHECK ((jsonb_typeof(answer) = 'object') IS TRUE),
  saved_revision assessment_revision NOT NULL CHECK ((saved_revision > 0) IS TRUE),
  saved_at timestamptz NOT NULL CHECK ((isfinite(saved_at)) IS TRUE),
  PRIMARY KEY (attempt_id, question_number)
);

CREATE TABLE attempt_creation_operations (
  publication_id uuid NOT NULL,
  student_id uuid NOT NULL,
  key_digest assessment_digest NOT NULL,
  action varchar(6) NOT NULL CHECK ((action IN ('START','RETAKE')) IS TRUE),
  request_digest assessment_digest NOT NULL,
  attempt_id uuid NOT NULL,
  created_at timestamptz NOT NULL CHECK ((isfinite(created_at)) IS TRUE),
  PRIMARY KEY (publication_id, student_id, key_digest),
  FOREIGN KEY (attempt_id, publication_id, student_id)
    REFERENCES attempts(id, publication_id, student_id) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE TABLE submitted_answers (
  id uuid PRIMARY KEY,
  attempt_id uuid NOT NULL UNIQUE REFERENCES attempts(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  answer_schema_version integer NOT NULL CHECK ((answer_schema_version = 1) IS TRUE),
  canonical_answers jsonb NOT NULL CHECK ((jsonb_typeof(canonical_answers) = 'array') IS TRUE),
  answer_digest assessment_digest NOT NULL,
  created_at timestamptz NOT NULL CHECK ((isfinite(created_at)) IS TRUE),
  UNIQUE (id, attempt_id)
);

CREATE TABLE grading_results (
  id uuid PRIMARY KEY,
  attempt_id uuid NOT NULL UNIQUE,
  snapshot_id uuid NOT NULL,
  grading_policy_version integer NOT NULL CHECK ((grading_policy_version = 1) IS TRUE),
  submitted_answers_id uuid NOT NULL,
  earned_weight numeric NOT NULL,
  possible_weight numeric NOT NULL,
  graded_at timestamptz NOT NULL CHECK ((isfinite(graded_at)) IS TRUE),
  UNIQUE (id, attempt_id),
  FOREIGN KEY (attempt_id, snapshot_id, grading_policy_version)
    REFERENCES attempts(id, snapshot_id, grading_policy_version) ON DELETE RESTRICT ON UPDATE RESTRICT,
  FOREIGN KEY (submitted_answers_id, attempt_id)
    REFERENCES submitted_answers(id, attempt_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CHECK ((possible_weight > 0 AND possible_weight < 'Infinity'::numeric
    AND possible_weight = trunc(possible_weight)) IS TRUE),
  CHECK ((earned_weight >= 0 AND earned_weight <= possible_weight
    AND earned_weight * 4 = trunc(earned_weight * 4)) IS TRUE)
);
ALTER TABLE attempts ADD FOREIGN KEY (submitted_answers_id, id)
  REFERENCES submitted_answers(id, attempt_id) ON DELETE RESTRICT ON UPDATE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE attempts ADD FOREIGN KEY (result_id, id)
  REFERENCES grading_results(id, attempt_id) ON DELETE RESTRICT ON UPDATE RESTRICT DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX grading_result_best
  ON grading_results(earned_weight DESC, attempt_id);

CREATE TABLE grading_question_results (
  result_id uuid NOT NULL REFERENCES grading_results(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  question_number integer NOT NULL CHECK ((question_number >= 1) IS TRUE),
  earned_weight numeric NOT NULL
    CHECK ((earned_weight IN (0, 0.25, 0.5, 0.75, 1)) IS TRUE),
  possible_weight numeric NOT NULL CHECK ((possible_weight = 1) IS TRUE),
  PRIMARY KEY (result_id, question_number)
);

CREATE TABLE attempt_operations (
  attempt_id uuid NOT NULL REFERENCES attempts(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  key_digest assessment_digest NOT NULL,
  action varchar(7) NOT NULL CHECK ((action IN ('SAVE','SUBMIT','ABANDON')) IS TRUE),
  request_digest assessment_digest NOT NULL,
  question_number integer,
  accepted_revision assessment_revision NOT NULL CHECK ((accepted_revision > 0) IS TRUE),
  acknowledged_at timestamptz NOT NULL CHECK ((isfinite(acknowledged_at)) IS TRUE),
  response_status integer NOT NULL CHECK ((response_status = 200) IS TRUE),
  response_body jsonb NOT NULL CHECK ((jsonb_typeof(response_body) = 'object') IS TRUE),
  PRIMARY KEY (attempt_id, key_digest),
  CHECK (((action = 'SAVE' AND question_number IS NOT NULL AND question_number >= 1)
    OR (action <> 'SAVE' AND question_number IS NULL)) IS TRUE)
);

CREATE TABLE attempt_finalization_audit (
  attempt_id uuid PRIMARY KEY REFERENCES attempts(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  publication_id uuid NOT NULL REFERENCES publications(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  reason varchar(24) NOT NULL CHECK ((reason IN
    ('MANUAL_SUBMIT','BOUNDED_TIMEOUT','STUDENT_ABANDON')) IS TRUE),
  actor_kind varchar(6) NOT NULL CHECK ((actor_kind IN ('USER','SYSTEM')) IS TRUE),
  actor_id varchar(128) NOT NULL CHECK ((length(actor_id) > 0) IS TRUE),
  finalized_at timestamptz NOT NULL CHECK ((isfinite(finalized_at)) IS TRUE),
  grading_policy_version integer NOT NULL CHECK ((grading_policy_version = 1) IS TRUE),
  result_id uuid,
  closure_request_id uuid,
  requesting_teacher_id uuid,
  notification_id uuid,
  confirmed_notice_at timestamptz,
  closure_cutoff_at timestamptz,
  accessibility_version integer,
  FOREIGN KEY (result_id, attempt_id) REFERENCES grading_results(id, attempt_id) ON DELETE RESTRICT ON UPDATE RESTRICT
    DEFERRABLE INITIALLY DEFERRED,
  CHECK (((reason = 'STUDENT_ABANDON' AND result_id IS NULL)
    OR (reason <> 'STUDENT_ABANDON' AND result_id IS NOT NULL)) IS TRUE),
  CONSTRAINT audit_v4_no_closure CHECK ((closure_request_id IS NULL
      AND requesting_teacher_id IS NULL AND notification_id IS NULL
      AND confirmed_notice_at IS NULL AND closure_cutoff_at IS NULL
      AND accessibility_version IS NULL) IS TRUE)
);
CREATE INDEX finalization_audit_publication ON attempt_finalization_audit(publication_id, finalized_at, attempt_id);


-- Stored answers are canonical typed data, never Java class names or polymorphic objects.
CREATE FUNCTION assessment_answer_valid(answer jsonb, kind text) RETURNS boolean
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
DECLARE keys text[]; labels integer; distinct_labels integer;
BEGIN
 IF jsonb_typeof(answer) IS DISTINCT FROM 'object' OR answer->>'type' IS DISTINCT FROM kind THEN RETURN false; END IF;
 SELECT array_agg(key ORDER BY key) INTO keys FROM jsonb_object_keys(answer) key;
 CASE kind
 WHEN 'SINGLE_CHOICE' THEN
  RETURN (keys=ARRAY['optionLabel','type'] AND
   (answer->'optionLabel'='null'::jsonb OR answer->'optionLabel' IN ('"A"'::jsonb,'"B"'::jsonb,'"C"'::jsonb,'"D"'::jsonb))) IS TRUE;
 WHEN 'MULTIPLE_CHOICE' THEN
  IF keys<>ARRAY['optionLabels','type'] OR jsonb_typeof(answer->'optionLabels') IS DISTINCT FROM 'array' THEN RETURN false; END IF;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(answer->'optionLabels') v WHERE v NOT IN ('"A"'::jsonb,'"B"'::jsonb,'"C"'::jsonb,'"D"'::jsonb)) THEN RETURN false; END IF;
  SELECT count(*),count(DISTINCT v) INTO labels,distinct_labels FROM jsonb_array_elements(answer->'optionLabels') v;
  RETURN labels<=4 AND labels=distinct_labels;
 WHEN 'TRUE_FALSE_MATRIX' THEN
  IF keys<>ARRAY['judgments','type'] OR jsonb_typeof(answer->'judgments') IS DISTINCT FROM 'object' THEN RETURN false; END IF;
  SELECT array_agg(key ORDER BY key) INTO keys FROM jsonb_object_keys(answer->'judgments') key;
  RETURN (keys=ARRAY['A','B','C','D'] AND NOT EXISTS(SELECT 1 FROM jsonb_each(answer->'judgments') v
   WHERE jsonb_typeof(v.value) NOT IN ('boolean','null'))) IS TRUE;
 WHEN 'NUMERIC_FILL' THEN
  RETURN (keys=ARRAY['type','value'] AND (answer->'value'='null'::jsonb OR
   (jsonb_typeof(answer->'value')='string' AND length(answer->>'value')=4
    AND answer->>'value' ~ '^(-?[0-9]+([.][0-9]+)?|-[.][0-9]+)$'))) IS TRUE;
 ELSE RETURN false;
 END CASE;
END $$;
CREATE FUNCTION assessment_unanswered(kind text) RETURNS jsonb
 LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
 SELECT CASE kind
 WHEN 'SINGLE_CHOICE' THEN jsonb_build_object('type',kind,'optionLabel',NULL)
 WHEN 'MULTIPLE_CHOICE' THEN jsonb_build_object('type',kind,'optionLabels','[]'::jsonb)
 WHEN 'TRUE_FALSE_MATRIX' THEN jsonb_build_object('type',kind,'judgments',jsonb_build_object('A',NULL,'B',NULL,'C',NULL,'D',NULL))
 WHEN 'NUMERIC_FILL' THEN jsonb_build_object('type',kind,'value',NULL)
 END
$$;

-- Canonical digest input: compact JSON, object keys in C lexical order, arrays
-- preserved, exact answer strings retained. No pgcrypto extension is required.
DO $migration$
BEGIN
 EXECUTE format($ddl$
 CREATE FUNCTION %1$I.assessment_canonical_json(value jsonb) RETURNS text
 LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $body$
 DECLARE encoded text;
 BEGIN
  CASE jsonb_typeof(value)
  WHEN 'object' THEN
   SELECT '{'||coalesce(string_agg(to_jsonb(key)::text||':'||%1$I.assessment_canonical_json(v),',' ORDER BY key COLLATE "C"),'')||'}'
    INTO encoded FROM jsonb_each(value) x(key,v);
  WHEN 'array' THEN
   SELECT '['||coalesce(string_agg(%1$I.assessment_canonical_json(v),',' ORDER BY n),'')||']'
    INTO encoded FROM jsonb_array_elements(value) WITH ORDINALITY x(v,n);
  ELSE encoded := value::text;
  END CASE;
  RETURN encoded;
 END $body$
 $ddl$,current_schema());
END $migration$;

CREATE FUNCTION reject_assessment_mutation() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Assessment history is immutable'; END $$;

CREATE FUNCTION guard_attempt_write() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
DECLARE p record; policy integer; existing_count bigint; latest_ordinal bigint;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Attempt deletion is forbidden'; END IF;
 EXECUTE format('SELECT * FROM %I.publications WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA) INTO p USING NEW.publication_id;
 IF p.id IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Attempt publication is invalid'; END IF;
 IF TG_OP='INSERT' THEN
  EXECUTE format('SELECT grading_policy_version FROM %I.publication_snapshots WHERE publication_id=$1',TG_TABLE_SCHEMA) INTO policy USING p.snapshot_id;
  EXECUTE format('SELECT count(*),coalesce(max(ordinal),0) FROM %I.attempts WHERE publication_id=$1 AND student_id=$2',TG_TABLE_SCHEMA)
   INTO existing_count,latest_ordinal USING NEW.publication_id,NEW.student_id;
  IF p.state<>'OPEN' OR NEW.snapshot_id IS DISTINCT FROM p.snapshot_id
   OR NEW.grading_policy_version IS DISTINCT FROM policy OR NEW.duration_minutes<>p.duration_minutes
   OR NEW.started_at<p.start_at OR NEW.started_at>=p.end_at
   OR NEW.ordinal<>latest_ordinal+1 OR (p.max_attempts<>0 AND existing_count>=p.max_attempts)
   OR NEW.state<>'ACTIVE' OR NEW.revision<>0 THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Attempt eligibility or pinning is invalid';
  END IF;
 ELSE
  IF OLD.state<>'ACTIVE' OR NEW.revision<>OLD.revision+1 OR
   ROW(NEW.id,NEW.publication_id,NEW.student_id,NEW.ordinal,NEW.snapshot_id,NEW.grading_policy_version,NEW.answer_schema_version,NEW.duration_minutes,NEW.started_at,NEW.deadline_at)
   IS DISTINCT FROM ROW(OLD.id,OLD.publication_id,OLD.student_id,OLD.ordinal,OLD.snapshot_id,OLD.grading_policy_version,OLD.answer_schema_version,OLD.duration_minutes,OLD.started_at,OLD.deadline_at) THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Attempt identity, revision or terminal state is immutable';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER attempt_write_guard BEFORE INSERT OR UPDATE OR DELETE ON attempts
 FOR EACH ROW EXECUTE FUNCTION guard_attempt_write();

CREATE FUNCTION lock_assessment_parent() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
DECLARE attempt_key uuid; publication_key uuid;
BEGIN
 IF TG_TABLE_NAME='grading_question_results' THEN
  EXECUTE format('SELECT attempt_id FROM %I.grading_results WHERE id=$1',TG_TABLE_SCHEMA) INTO attempt_key USING NEW.result_id;
 ELSE attempt_key:=NEW.attempt_id;
 END IF;
 EXECUTE format('SELECT publication_id FROM %I.attempts WHERE id=$1',TG_TABLE_SCHEMA) INTO publication_key USING attempt_key;
 IF publication_key IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Assessment parent is invalid'; END IF;
 EXECUTE format('SELECT id FROM %I.publications WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA) USING publication_key;
 EXECUTE format('SELECT id FROM %I.attempts WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA) USING attempt_key;
 RETURN NEW;
END $$;
CREATE FUNCTION guard_answer_write() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
DECLARE a record; kind text; valid boolean;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Use an unanswered value to clear an answer'; END IF;
 IF TG_OP='UPDATE' AND ROW(NEW.attempt_id,NEW.question_number) IS DISTINCT FROM ROW(OLD.attempt_id,OLD.question_number) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Answer identity is immutable';
 END IF;
 EXECUTE format('SELECT a.*,s.payload->''structuredContent''->''questions''->($2-1)->>''type'' AS kind FROM %1$I.attempts a JOIN %1$I.publication_snapshots s ON s.publication_id=a.snapshot_id WHERE a.id=$1',TG_TABLE_SCHEMA)
  INTO a USING NEW.attempt_id,NEW.question_number;
 kind:=a.kind;
 EXECUTE format('SELECT %I.assessment_answer_valid($1,$2)',TG_TABLE_SCHEMA) INTO valid USING NEW.answer,kind;
 IF a.state<>'ACTIVE' OR valid IS DISTINCT FROM true OR NEW.saved_at<a.started_at
  OR (a.deadline_at IS NOT NULL AND NEW.saved_at>=a.deadline_at) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Answer state, reference or shape is invalid';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER answer_parent_lock BEFORE INSERT OR UPDATE ON attempt_answers
 FOR EACH ROW EXECUTE FUNCTION lock_assessment_parent();
CREATE TRIGGER answer_write_guard BEFORE INSERT OR UPDATE OR DELETE ON attempt_answers
 FOR EACH ROW EXECUTE FUNCTION guard_answer_write();

CREATE FUNCTION validate_attempt_consistency() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
DECLARE aid uuid; a record; s record; r record; audit record; answers record;
 declarations integer; grades integer; audits integer; snapshot jsonb; expected jsonb;
 earned numeric; possible numeric; valid boolean; current_answer record;
BEGIN
 IF TG_TABLE_NAME='attempts' THEN aid:=NEW.id;
 ELSIF TG_TABLE_NAME='grading_question_results' THEN
  EXECUTE format('SELECT attempt_id FROM %I.grading_results WHERE id=$1',TG_TABLE_SCHEMA) INTO aid USING NEW.result_id;
 ELSE aid:=NEW.attempt_id;
 END IF;
 EXECUTE format('SELECT publication_id FROM %I.attempts WHERE id=$1',TG_TABLE_SCHEMA) INTO s USING aid;
 IF s.publication_id IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Missing attempt'; END IF;
 EXECUTE format('SELECT id FROM %I.publications WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA) USING s.publication_id;
 EXECUTE format('SELECT *,xmin AS write_transaction FROM %I.attempts WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA) INTO a USING aid;
 EXECUTE format('SELECT payload FROM %I.publication_snapshots WHERE publication_id=$1',TG_TABLE_SCHEMA) INTO snapshot USING a.snapshot_id;
 EXECUTE format('SELECT count(*) FROM %I.submitted_answers WHERE attempt_id=$1',TG_TABLE_SCHEMA) INTO declarations USING aid;
 EXECUTE format('SELECT count(*) FROM %I.grading_results WHERE attempt_id=$1',TG_TABLE_SCHEMA) INTO grades USING aid;
 EXECUTE format('SELECT count(*) FROM %I.attempt_finalization_audit WHERE attempt_id=$1',TG_TABLE_SCHEMA) INTO audits USING aid;
 IF a.state='ACTIVE' THEN
  IF declarations<>0 OR grades<>0 OR audits<>0 THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Partial finalization is forbidden'; END IF;
 ELSIF a.state='ABANDONED' THEN
  IF declarations<>0 OR grades<>0 OR audits<>1 THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Abandonment must be ungraded and audited'; END IF;
 ELSE
  IF declarations<>1 OR grades<>1 OR audits<>1 THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Submission must have complete result and audit'; END IF;
  EXECUTE format('SELECT * FROM %I.submitted_answers WHERE attempt_id=$1',TG_TABLE_SCHEMA) INTO answers USING aid;
  EXECUTE format('SELECT * FROM %I.grading_results WHERE attempt_id=$1',TG_TABLE_SCHEMA) INTO r USING aid;
  EXECUTE format($q$
   SELECT jsonb_agg(jsonb_build_object('questionNumber',q.value->'number','answer',coalesce(v.answer,%1$I.assessment_unanswered(q.value->>'type'))) ORDER BY q.n)
   FROM jsonb_array_elements($2->'structuredContent'->'questions') WITH ORDINALITY q(value,n)
   LEFT JOIN %1$I.attempt_answers v ON v.attempt_id=$1 AND v.question_number=q.n
  $q$,TG_TABLE_SCHEMA) INTO expected USING aid,snapshot;
  IF expected IS NULL OR answers.canonical_answers IS DISTINCT FROM expected OR answers.id IS DISTINCT FROM a.submitted_answers_id
   OR answers.answer_schema_version<>a.answer_schema_version OR answers.created_at<>a.finalized_at
   OR r.id IS DISTINCT FROM a.result_id OR r.submitted_answers_id<>answers.id OR r.graded_at<>a.finalized_at THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Submitted identity or authoritative answers are inconsistent';
  END IF;
  EXECUTE format('SELECT encode(sha256(convert_to(%I.assessment_canonical_json($1),''UTF8'')),''hex'')=$2',TG_TABLE_SCHEMA)
   INTO valid USING expected,answers.answer_digest;
  IF valid IS DISTINCT FROM true THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Submitted answer digest is invalid'; END IF;
  EXECUTE format('SELECT count(*),coalesce(sum(earned_weight),0),coalesce(sum(possible_weight),0) FROM %I.grading_question_results WHERE result_id=$1',TG_TABLE_SCHEMA)
   INTO declarations,earned,possible USING r.id;
  IF declarations<>jsonb_array_length(expected) OR r.possible_weight<>jsonb_array_length(expected)
   OR earned<>r.earned_weight OR possible<>r.possible_weight THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Exact result totals are inconsistent';
  END IF;
  EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I.grading_question_results WHERE result_id=$1 AND question_number>$2)',TG_TABLE_SCHEMA)
   INTO valid USING r.id,jsonb_array_length(expected);
  IF valid THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Grade question reference is invalid'; END IF;
 END IF;
 IF a.state<>'ACTIVE' THEN
  EXECUTE format('SELECT * FROM %I.attempt_finalization_audit WHERE attempt_id=$1',TG_TABLE_SCHEMA) INTO audit USING aid;
  IF audit.publication_id<>a.publication_id OR audit.reason<>a.terminal_reason OR audit.finalized_at<>a.finalized_at
   OR audit.result_id IS DISTINCT FROM a.result_id OR audit.grading_policy_version<>a.grading_policy_version
   OR (audit.reason IN ('MANUAL_SUBMIT','STUDENT_ABANDON') AND (audit.actor_kind<>'USER' OR audit.actor_id<>a.student_id::text))
   OR (audit.reason='BOUNDED_TIMEOUT' AND (audit.actor_kind<>'SYSTEM' OR a.deadline_at IS NULL OR a.finalized_at<a.deadline_at))
   OR (audit.reason IN ('MANUAL_SUBMIT','STUDENT_ABANDON') AND a.deadline_at IS NOT NULL AND a.finalized_at>=a.deadline_at) THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Finalization audit or deadline is inconsistent';
  END IF;
 END IF;
 IF TG_TABLE_NAME='attempt_answers' THEN
  EXECUTE format('SELECT * FROM %I.attempt_answers WHERE attempt_id=$1 AND question_number=$2',TG_TABLE_SCHEMA)
   INTO current_answer USING aid,NEW.question_number;
  -- A matching old revision alone is insufficient: the Attempt must also have
  -- been inserted/updated in this transaction. Deferred execution accepts either
  -- answer-first or Attempt-first writes, including atomic final answer replacement.
  IF current_answer.saved_revision<>a.revision OR a.write_transaction<>pg_current_xact_id()::xid THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Answer revision does not match mutation'; END IF;
 END IF;
 IF TG_TABLE_NAME='attempt_operations' THEN
  IF NEW.accepted_revision<>a.revision OR a.write_transaction<>pg_current_xact_id()::xid
   OR (NEW.action='SAVE' AND a.state<>'ACTIVE') OR (NEW.action='SUBMIT' AND a.state<>'SUBMITTED')
   OR (NEW.action='ABANDON' AND a.state<>'ABANDONED') THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Operation acknowledgment is inconsistent';
  END IF;
 END IF;
 RETURN NULL;
END $$;

-- Child insertions take the same parent-first lock before any deferred validation.
DO $migration$
DECLARE name text;
BEGIN
 FOREACH name IN ARRAY ARRAY['submitted_answers','grading_results','grading_question_results','attempt_operations','attempt_creation_operations','attempt_finalization_audit'] LOOP
  EXECUTE format('CREATE TRIGGER parent_lock BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION lock_assessment_parent()',name);
  EXECUTE format('CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_assessment_mutation()',name);
 END LOOP;
 FOREACH name IN ARRAY ARRAY['attempts','attempt_answers','submitted_answers','grading_results','grading_question_results','attempt_operations','attempt_finalization_audit'] LOOP
  EXECUTE format('CREATE CONSTRAINT TRIGGER terminal_consistency AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_attempt_consistency()',name);
 END LOOP;
END $migration$;

-- TRUNCATE is DML too; row DELETE guards alone cannot protect durable history.
DO $migration$
DECLARE name text;
BEGIN
 FOREACH name IN ARRAY ARRAY['publications','publication_snapshots','publication_creations','attempts','attempt_answers',
  'submitted_answers','grading_results','grading_question_results','attempt_operations','attempt_creation_operations','attempt_finalization_audit'] LOOP
  EXECUTE format('CREATE TRIGGER no_assessment_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_assessment_mutation()',name);
 END LOOP;
END $migration$;
