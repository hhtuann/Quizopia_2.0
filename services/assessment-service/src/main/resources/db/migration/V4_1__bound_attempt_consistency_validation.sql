-- Additive V4.1 correction explicitly authorized within W3-B2 on 2026-10-13.
-- Preserve V1-V4 history and reserve V5 for W3-B3. Drain writers before migration.
-- Full integrity/security and runtime acceptance remain review gates.
LOCK TABLE publications, publication_snapshots, attempts, attempt_answers,
 submitted_answers, grading_results, grading_question_results, attempt_operations,
 attempt_finalization_audit IN ACCESS EXCLUSIVE MODE;

-- Derived, immutable type lookup only: the protected payload remains the grading authority.
-- GENERATED ALWAYS prevents callers from substituting an untrusted question-type projection.
-- Unknown/missing types have no valid code and continue to fail closed during answer/grading validation.
CREATE OR REPLACE FUNCTION assessment_question_type_codes(payload jsonb) RETURNS bytea
 LANGUAGE sql IMMUTABLE SET search_path=pg_catalog,pg_temp AS $$
 SELECT decode(coalesce(string_agg(CASE q->>'type'
  WHEN 'SINGLE_CHOICE' THEN '01' WHEN 'MULTIPLE_CHOICE' THEN '02'
  WHEN 'TRUE_FALSE_MATRIX' THEN '03' WHEN 'NUMERIC_FILL' THEN '04'
  ELSE '00' END,'' ORDER BY n),''),'hex')
 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(payload->'structuredContent'->'questions')='array'
  THEN payload->'structuredContent'->'questions' ELSE '[]'::jsonb END) WITH ORDINALITY x(q,n)
$$;
ALTER TABLE publication_snapshots ADD COLUMN question_type_codes bytea
 GENERATED ALWAYS AS (assessment_question_type_codes(payload)) STORED;

CREATE OR REPLACE FUNCTION guard_answer_write() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
DECLARE a record; kind text; valid boolean;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Use an unanswered value to clear an answer'; END IF;
 IF TG_OP='UPDATE' AND ROW(NEW.attempt_id,NEW.question_number) IS DISTINCT FROM ROW(OLD.attempt_id,OLD.question_number) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Answer identity is immutable';
 END IF;
 EXECUTE format('SELECT a.*,CASE WHEN $2 BETWEEN 1 AND octet_length(s.question_type_codes) THEN CASE get_byte(s.question_type_codes,$2-1) WHEN 1 THEN ''SINGLE_CHOICE'' WHEN 2 THEN ''MULTIPLE_CHOICE'' WHEN 3 THEN ''TRUE_FALSE_MATRIX'' WHEN 4 THEN ''NUMERIC_FILL'' END END AS kind FROM %1$I.attempts a JOIN %1$I.publication_snapshots s ON s.publication_id=a.snapshot_id WHERE a.id=$1',TG_TABLE_SCHEMA)
  INTO a USING NEW.attempt_id,NEW.question_number;
 kind:=a.kind;
 EXECUTE format('SELECT %I.assessment_answer_valid($1,$2)',TG_TABLE_SCHEMA) INTO valid USING NEW.answer,kind;
 IF a.state<>'ACTIVE' OR valid IS DISTINCT FROM true OR NEW.saved_at<a.started_at
  OR (a.deadline_at IS NOT NULL AND NEW.saved_at>=a.deadline_at) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Answer state, reference or shape is invalid';
 END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION validate_attempt_consistency() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
DECLARE aid uuid; a record; s record; r record; audit record; answers record;
 declarations integer; grades integer; audits integer; snapshot jsonb; expected jsonb;
 earned numeric; possible numeric; valid boolean; current_answer record;
BEGIN
 -- Full cross-table validation remains on every Attempt mutation.
 -- A child event may take the bounded path only if its parent was written in this
 -- transaction, which necessarily queues that same full deferred Attempt validator.
 -- No caller-controlled cache/GUC or disabled trigger is used.
 IF TG_TABLE_NAME<>'attempts' THEN
  -- Every child event has already run its immutable BEFORE parent-lock trigger;
  -- those Publication/Attempt locks remain held until transaction end. Do not
  -- reacquire them per deferred child or unpack protected snapshot content here.
  IF TG_TABLE_NAME='grading_question_results' THEN
   EXECUTE format('SELECT a.*,a.xmin AS write_transaction,r.possible_weight AS question_count FROM %1$I.grading_results r JOIN %1$I.attempts a ON a.id=r.attempt_id WHERE r.id=$1',TG_TABLE_SCHEMA)
    INTO a USING NEW.result_id;
  ELSE
   EXECUTE format('SELECT *,xmin AS write_transaction FROM %I.attempts WHERE id=$1',TG_TABLE_SCHEMA)
    INTO a USING NEW.attempt_id;
  END IF;
  IF a.id IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Missing attempt'; END IF;
  aid:=a.id;
  IF a.write_transaction<>pg_current_xact_id()::xid THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Child mutation requires the parent transaction';
  END IF;
  IF TG_TABLE_NAME='attempt_answers' THEN
   EXECUTE format('SELECT * FROM %I.attempt_answers WHERE attempt_id=$1 AND question_number=$2',TG_TABLE_SCHEMA)
    INTO current_answer USING aid,NEW.question_number;
   IF current_answer.saved_revision<>a.revision THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Answer revision does not match mutation';
   END IF;
  ELSIF TG_TABLE_NAME='attempt_operations' THEN
   IF NEW.accepted_revision<>a.revision OR (NEW.action='SAVE' AND a.state<>'ACTIVE')
    OR (NEW.action='SUBMIT' AND a.state<>'SUBMITTED') OR (NEW.action='ABANDON' AND a.state<>'ABANDONED') THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Operation acknowledgment is inconsistent';
   END IF;
  ELSIF TG_TABLE_NAME='attempt_finalization_audit' THEN
   IF a.state NOT IN ('SUBMITTED','ABANDONED') THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Audit requires terminal parent';
   END IF;
  ELSIF TG_TABLE_NAME IN ('submitted_answers','grading_results','grading_question_results') THEN
   IF a.state<>'SUBMITTED' THEN
    RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Result artifact requires submitted parent';
   END IF;
   IF TG_TABLE_NAME='grading_question_results' THEN
    possible:=a.question_count;
    -- Full parent validation proves Q, complete coverage and exact sums. This
    -- per-child bound also rejects a late extra row after SET CONSTRAINTS IMMEDIATE.
    IF NEW.result_id IS DISTINCT FROM a.result_id OR possible IS NULL OR NEW.question_number>possible THEN
     RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Grade question reference is invalid';
    END IF;
   END IF;
  ELSE
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Unknown assessment child';
  END IF;
  RETURN NULL;
 END IF;
 aid:=NEW.id;
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
 RETURN NULL;
END $$;
