-- FB-015 PR C (ADR-018 Phase B steps 6-7): FK-safe quiz-scoped hard purge for Assessment.
-- Purge receipts carry counts only. Retention holds are record-scoped blockers: an active
-- hold (no expiry yet, or expiry in the future) forces BLOCKED_BY_RETENTION and keeps every
-- affected record; the purge surface refuses to run until every hold has expired.
CREATE TABLE deletion_purge_receipts (
  operation_id uuid NOT NULL,
  quiz_id uuid NOT NULL,
  step varchar(48) NOT NULL,
  rows_removed bigint NOT NULL CHECK (rows_removed >= 0),
  completed_at timestamptz NOT NULL CHECK (isfinite(completed_at)),
  PRIMARY KEY (operation_id, step)
);

CREATE TABLE retention_holds (
  id uuid PRIMARY KEY,
  quiz_id uuid NOT NULL,
  authority varchar(100) NOT NULL CHECK (length(authority) BETWEEN 1 AND 100),
  reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 2000),
  reviewer varchar(128) NOT NULL CHECK (length(reviewer) BETWEEN 1 AND 128),
  expires_at timestamptz CHECK (expires_at IS NULL OR isfinite(expires_at)),
  review_at timestamptz NOT NULL CHECK (isfinite(review_at)),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp() CHECK (isfinite(created_at)),
  CHECK (review_at >= created_at)
);
CREATE INDEX retention_holds_quiz_idx ON retention_holds(quiz_id, created_at);

-- The purge procedure opens one narrow, transaction-scoped window: only the table owner
-- executing assessment_purge_quiz (SECURITY DEFINER) can set this marker, the fence for the
-- named quiz must already be acknowledged, and every guard below still rejects each DELETE
-- executed outside that procedure's transaction.
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_purge_authorized() RETURNS boolean
 LANGUAGE sql STABLE SET search_path=pg_catalog,pg_temp AS $body$
 SELECT current_setting('quizopia.assessment_purge_quiz',true) IS NOT NULL
  AND EXISTS(SELECT 1 FROM %1$I.quiz_revocation_fences f
   WHERE f.quiz_id=current_setting('quizopia.assessment_purge_quiz',true)::uuid
    AND f.ack_at IS NOT NULL)
$body$
$ddl$,current_schema()); END $migration$;

-- History guards gain exactly one branch: DELETE inside an acknowledged-fence purge
-- transaction. Every UPDATE/TRUNCATE rejection and every identity/freeze rule is unchanged.
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.reject_assessment_mutation() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
BEGIN
 IF TG_OP='DELETE' AND %1$I.assessment_purge_authorized() THEN RETURN OLD; END IF;
 RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Assessment history is immutable';
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.reject_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
 IF TG_OP='DELETE' AND %1$I.assessment_purge_authorized() THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Finalized snapshot is immutable';
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.guard_publication_update() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
 IF TG_OP='DELETE' AND %1$I.assessment_purge_authorized() THEN RETURN OLD; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'DRAFT' OR NEW.revision<>0 OR NEW.closed_at IS NOT NULL THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Publication must begin as a draft';
  END IF;
  RETURN NEW;
 END IF;
 IF TG_OP='DELETE' THEN
  IF OLD.state<>'DRAFT' THEN RAISE EXCEPTION 'Finalized publication is immutable'; END IF;
  RETURN OLD;
 END IF;
 IF OLD.state='CLOSED' THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Closure is not enabled';
 END IF;
 IF NEW.id<>OLD.id OR NEW.owner_id<>OLD.owner_id OR NEW.created_at<>OLD.created_at OR NEW.revision<>OLD.revision+1 THEN
  RAISE EXCEPTION 'Invalid publication identity or revision';
 END IF;
 IF OLD.state<>'DRAFT' AND (
  NEW.state NOT IN ('OPEN','CLOSED') OR NEW.max_attempts<>OLD.max_attempts OR NEW.quiz_id<>OLD.quiz_id OR NEW.quiz_version_id<>OLD.quiz_version_id OR NEW.version_number<>OLD.version_number
  OR NEW.audience<>OLD.audience OR NEW.classroom_id IS DISTINCT FROM OLD.classroom_id
  OR NEW.monitoring_choice<>OLD.monitoring_choice OR NEW.snapshot_id IS DISTINCT FROM OLD.snapshot_id
  OR NEW.opened_at IS DISTINCT FROM OLD.opened_at OR NEW.opened_from_revision IS DISTINCT FROM OLD.opened_from_revision
  OR NEW.private_locator IS DISTINCT FROM OLD.private_locator OR NEW.locator_digest IS DISTINCT FROM OLD.locator_digest
  OR NEW.start_at<>OLD.start_at OR NEW.end_at<OLD.end_at OR NEW.duration_minutes<OLD.duration_minutes
  OR ((NEW.duration_minutes=0) IS DISTINCT FROM (OLD.duration_minutes=0))
  OR (NEW.configuration - 'endAt' - 'durationMinutes') IS DISTINCT FROM (OLD.configuration - 'endAt' - 'durationMinutes')) THEN
  RAISE EXCEPTION 'Finalized publication configuration is frozen';
 END IF;
 IF OLD.state='DRAFT' AND NEW.state NOT IN ('DRAFT','OPEN') THEN RAISE EXCEPTION 'Invalid lifecycle transition'; END IF;
 RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.guard_attempt_write() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE p record; policy integer; existing_count bigint; latest_ordinal bigint;
BEGIN
 IF TG_OP='DELETE' THEN
  IF %1$I.assessment_purge_authorized() THEN RETURN OLD; END IF;
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Attempt deletion is forbidden';
 END IF;
 EXECUTE format('SELECT * FROM %%I.publications WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA) INTO p USING NEW.publication_id;
 IF p.id IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Attempt publication is invalid'; END IF;
 IF TG_OP='INSERT' THEN
  EXECUTE format('SELECT grading_policy_version FROM %%I.publication_snapshots WHERE publication_id=$1',TG_TABLE_SCHEMA) INTO policy USING p.snapshot_id;
  EXECUTE format('SELECT count(*),coalesce(max(ordinal),0) FROM %%I.attempts WHERE publication_id=$1 AND student_id=$2',TG_TABLE_SCHEMA)
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
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.guard_answer_write() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE a record; kind text; valid boolean;
BEGIN
 IF TG_OP='DELETE' THEN
  IF %1$I.assessment_purge_authorized() THEN RETURN OLD; END IF;
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Use an unanswered value to clear an answer';
 END IF;
 IF TG_OP='UPDATE' AND ROW(NEW.attempt_id,NEW.question_number) IS DISTINCT FROM ROW(OLD.attempt_id,OLD.question_number) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Answer identity is immutable';
 END IF;
 SELECT current_attempt.*,CASE WHEN NEW.question_number BETWEEN 1 AND octet_length(s.question_type_codes)
  THEN CASE get_byte(s.question_type_codes,NEW.question_number-1)
   WHEN 1 THEN 'SINGLE_CHOICE' WHEN 2 THEN 'MULTIPLE_CHOICE' WHEN 3 THEN 'TRUE_FALSE_MATRIX' WHEN 4 THEN 'NUMERIC_FILL' END END AS kind
  INTO a FROM %1$I.attempts current_attempt JOIN %1$I.publication_snapshots s ON s.publication_id=current_attempt.snapshot_id WHERE current_attempt.id=NEW.attempt_id;
 kind:=a.kind;
 valid:=%1$I.assessment_answer_valid(NEW.answer,kind);
 IF a.state<>'ACTIVE' OR valid IS DISTINCT FROM true OR NEW.saved_at<a.started_at
  OR (a.deadline_at IS NOT NULL AND NEW.saved_at>=a.deadline_at) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Answer state, reference or shape is invalid';
 END IF;
 RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.closure_request_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE p record; BEGIN
 IF TG_OP='DELETE' THEN
  IF %1$I.assessment_purge_authorized() THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'Closure history is immutable';
 END IF;
 SELECT * INTO STRICT p FROM %1$I.publications WHERE id=NEW.publication_id FOR UPDATE;
 IF TG_OP='INSERT' THEN
  IF p.state<>'OPEN' OR NEW.requesting_teacher_id<>p.owner_id OR NEW.state<>'PENDING'
   OR NEW.requested_at<p.end_at OR NEW.requested_at>%1$I.assessment_time()
   OR NEW.finalization_started_at IS NOT NULL OR NEW.resolved_at IS NOT NULL THEN RAISE EXCEPTION 'Invalid closure request'; END IF;
  NEW.capture_transaction_id:=pg_current_xact_id();
  SELECT count(*),encode(sha256(convert_to(coalesce(string_agg(id::text,',' ORDER BY id),''),'UTF8')),'hex')
   INTO NEW.captured_members,NEW.capture_digest FROM %1$I.attempts WHERE publication_id=p.id AND state='ACTIVE' AND duration_minutes=0;
 ELSE
  IF ROW(NEW.id,NEW.publication_id,NEW.requesting_teacher_id,NEW.requested_at,NEW.capture_transaction_id,NEW.captured_members,NEW.capture_digest)
   IS DISTINCT FROM ROW(OLD.id,OLD.publication_id,OLD.requesting_teacher_id,OLD.requested_at,OLD.capture_transaction_id,OLD.captured_members,OLD.capture_digest)
   OR OLD.state NOT IN ('PENDING','FINALIZING') OR NEW.state=OLD.state
   OR (OLD.state='FINALIZING' AND NEW.state NOT IN ('COMPLETED','RESOLVED_EARLY'))
   OR (OLD.state='PENDING' AND NEW.state NOT IN ('FINALIZING','CANCELLED','COMPLETED','RESOLVED_EARLY'))
   OR (OLD.finalization_started_at IS NOT NULL AND NEW.finalization_started_at IS DISTINCT FROM OLD.finalization_started_at)
   OR (NEW.state='CANCELLED' AND OLD.finalization_started_at IS NOT NULL) THEN RAISE EXCEPTION 'Invalid closure transition'; END IF;
 END IF;
 RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.closure_member_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE p record; c record; a record; n record; BEGIN
 IF TG_OP='DELETE' THEN
  IF %1$I.assessment_purge_authorized() THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'Closure membership is immutable';
 END IF;
 SELECT * INTO STRICT p FROM %1$I.publications WHERE id=NEW.publication_id FOR UPDATE;
 SELECT * INTO STRICT c FROM %1$I.closure_requests WHERE id=NEW.closure_request_id AND publication_id=p.id FOR UPDATE;
 SELECT * INTO STRICT a FROM %1$I.attempts WHERE id=NEW.attempt_id FOR UPDATE;
 IF a.publication_id<>p.id OR a.student_id<>NEW.student_id OR a.duration_minutes<>0 THEN RAISE EXCEPTION 'Invalid closure member identity'; END IF;
 IF TG_OP='INSERT' THEN
  IF c.capture_transaction_id<>pg_current_xact_id() OR c.state<>'PENDING' OR a.state<>'ACTIVE'
   OR NEW.state<>'NOTICE_PENDING' OR NEW.notification_id IS NOT NULL OR NEW.confirmed_notice_at IS NOT NULL THEN RAISE EXCEPTION 'Invalid closure capture'; END IF;
 ELSE
  IF ROW(NEW.closure_request_id,NEW.attempt_id,NEW.publication_id,NEW.student_id)
   IS DISTINCT FROM ROW(OLD.closure_request_id,OLD.attempt_id,OLD.publication_id,OLD.student_id)
   OR OLD.state IN ('FINALIZED','RESOLVED_TERMINAL','RESOLVED_CANCELLED')
   OR (OLD.notification_id IS NOT NULL AND NEW.notification_id IS DISTINCT FROM OLD.notification_id)
   OR (OLD.confirmed_notice_at IS NOT NULL AND ROW(NEW.confirmed_notice_at,NEW.closure_cutoff_at,NEW.accessibility_version,NEW.recipient_checked_at)
    IS DISTINCT FROM ROW(OLD.confirmed_notice_at,OLD.closure_cutoff_at,OLD.accessibility_version,OLD.recipient_checked_at)) THEN RAISE EXCEPTION 'Immutable closure evidence'; END IF;
  IF NEW.state IN ('NOTICE_PUBLISHED','NOTICE_CONFIRMED','FINALIZED') AND c.state NOT IN ('PENDING','FINALIZING') THEN RAISE EXCEPTION 'Resolved closure cannot act'; END IF;
  IF NEW.state='NOTICE_PUBLISHED' AND NEW.state IS DISTINCT FROM OLD.state AND OLD.state<>'NOTICE_PENDING' THEN RAISE EXCEPTION 'Invalid notice publication'; END IF;
  IF NEW.confirmed_notice_at IS DISTINCT FROM OLD.confirmed_notice_at THEN
   SELECT * INTO STRICT n FROM %1$I.assessment_notifications WHERE id=NEW.notification_id FOR UPDATE;
   IF OLD.state<>'NOTICE_PUBLISHED' OR NEW.state<>'NOTICE_CONFIRMED' OR a.state<>'ACTIVE'
    OR n.publish_transaction_id=pg_current_xact_id() OR n.state<>'PUBLISHED'
    OR NEW.confirmed_notice_at<n.published_at OR NEW.confirmed_notice_at<c.requested_at
    OR NEW.confirmed_notice_at>%1$I.assessment_time() OR NEW.confirmed_notice_at<transaction_timestamp() THEN RAISE EXCEPTION 'Notice must commit before confirmation'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.publication_operation_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE p record; BEGIN
 IF TG_OP='DELETE' THEN
  IF %1$I.assessment_purge_authorized() THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'Operation evidence is immutable';
 END IF;
 SELECT * INTO STRICT p FROM %1$I.publications WHERE id=NEW.publication_id FOR UPDATE;
 IF NEW.actor_id<>p.owner_id THEN RAISE EXCEPTION 'Operation owner mismatch'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.processed_units<>0 OR (NEW.state='IN_PROGRESS' AND NEW.action<>'COMPLETE') THEN RAISE EXCEPTION 'Invalid initial operation'; END IF;
 ELSE
  IF OLD.state='ACKNOWLEDGED' OR ROW(NEW.publication_id,NEW.actor_id,NEW.key_digest,NEW.action,NEW.request_digest,NEW.closure_request_id,NEW.unit_budget,NEW.created_at)
   IS DISTINCT FROM ROW(OLD.publication_id,OLD.actor_id,OLD.key_digest,OLD.action,OLD.request_digest,OLD.closure_request_id,OLD.unit_budget,OLD.created_at)
   OR NEW.processed_units NOT IN (OLD.processed_units,OLD.processed_units+1)
   OR (NEW.state='IN_PROGRESS' AND NEW.processed_units=OLD.processed_units) THEN RAISE EXCEPTION 'Invalid operation progress/replay'; END IF;
 END IF;
 IF NEW.state='ACKNOWLEDGED' AND (NEW.response_body->>'publicationId' IS DISTINCT FROM p.id::text
  OR (NEW.closure_request_id IS NOT NULL AND NEW.response_body->>'closureRequestId' IS DISTINCT FROM NEW.closure_request_id::text)) THEN RAISE EXCEPTION 'Operation response identity mismatch'; END IF;
 RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.assessment_monitoring_outbox_immutable() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
BEGIN
 IF TG_OP='DELETE' AND %1$I.assessment_purge_authorized() THEN RETURN OLD; END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Monitoring evidence is immutable'; END IF;
 IF ROW(NEW.event_id,NEW.attempt_id,NEW.question_number,NEW.answer_revision,NEW.payload,NEW.created_at)
     IS DISTINCT FROM ROW(OLD.event_id,OLD.attempt_id,OLD.question_number,OLD.answer_revision,OLD.payload,OLD.created_at)
     OR OLD.state='PUBLISHED' THEN RAISE EXCEPTION 'Monitoring evidence is immutable'; END IF;
 RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.realtime_invalidation_outbox_immutable() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
BEGIN
 IF TG_OP='DELETE' AND %1$I.assessment_purge_authorized() THEN RETURN OLD; END IF;
 IF OLD.state='PUBLISHED'
  OR NEW.event_id IS DISTINCT FROM OLD.event_id
  OR NEW.event_type IS DISTINCT FROM OLD.event_type
  OR NEW.event_version IS DISTINCT FROM OLD.event_version
  OR NEW.resource_kind IS DISTINCT FROM OLD.resource_kind
  OR NEW.resource_id IS DISTINCT FROM OLD.resource_id
  OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
  OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Realtime invalidation evidence is immutable';
 END IF;
 RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

-- Bounded, idempotent, FK-ordered quiz-scoped purge. Requires an acknowledged fence for
-- exactly this quiz and operation; captures the affected publication/attempt inventory
-- first (because publications and snapshots restrict each other), deletes children before
-- parents in dependency order, and finishes with actual zero-remaining COUNT checks over
-- the captured inventory. Triggers stay enabled; every guard keeps rejecting ordinary DML.
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_purge_quiz(p_quiz uuid, p_operation uuid, p_batch_limit integer)
 RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I AS $body$
 DECLARE removed bigint; total bigint; step_name varchar;
   publication_ids uuid[]; attempt_ids uuid[];
   constraint_row record;
   steps text[] := ARRAY['grading_question_results','attempt_finalization_audit','assessment_notifications',
     'closure_request_members','closure_work_audit','publication_operations','closure_requests',
     'attempt_operations','attempt_creation_operations','assessment_monitoring_outbox',
     'realtime_invalidation_outbox','grading_results','submitted_answers','attempt_answers','attempts',
     'publication_creations','publications','publication_snapshots','deletion_command_inbox'];
 BEGIN
   IF p_batch_limit IS NULL OR p_batch_limit<1 OR p_batch_limit>1000 THEN
     RAISE EXCEPTION 'invalid purge batch bound';
   END IF;
   IF EXISTS(SELECT 1 FROM retention_holds WHERE quiz_id=p_quiz
     AND (expires_at IS NULL OR expires_at>clock_timestamp())) THEN
     RAISE EXCEPTION 'active retention hold blocks quiz purge';
   END IF;
   IF NOT EXISTS(SELECT 1 FROM quiz_revocation_fences
     WHERE quiz_id=p_quiz AND operation_id=p_operation AND ack_at IS NOT NULL) THEN
     RAISE EXCEPTION 'quiz purge requires an acknowledged fence';
   END IF;
   PERFORM set_config('quizopia.assessment_purge_quiz',p_quiz::text,true);
   -- The five cyclic history FKs carry ON DELETE RESTRICT, which PostgreSQL checks
   -- immediately even when the constraint is declared deferrable; ordered deletion can
   -- never satisfy them. They are dropped and re-added INSIDE this transaction only
   -- (transactional DDL is invisible to every other transaction), while all non-cyclic
   -- RESTRICT constraints keep enforcing child-before-parent order throughout.
   FOR constraint_row IN SELECT conrelid::regclass AS tbl, conname FROM pg_constraint
     WHERE contype='f' AND connamespace='%1$s'::regnamespace
      AND pg_get_constraintdef(oid) LIKE '%%DEFERRABLE INITIALLY DEFERRED%%' LOOP
     EXECUTE format('ALTER TABLE %%s DROP CONSTRAINT %%I',constraint_row.tbl,constraint_row.conname);
   END LOOP;
   SELECT array_agg(id) INTO publication_ids FROM publications WHERE quiz_id=p_quiz;
   SELECT coalesce(array_agg(a.id),'{}') INTO attempt_ids
     FROM attempts a JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=p_quiz;
   publication_ids:=coalesce(publication_ids,'{}');
   FOREACH step_name IN ARRAY steps LOOP
     total:=0;
     LOOP
       CASE step_name
       WHEN 'grading_question_results' THEN
         DELETE FROM grading_question_results WHERE (result_id,question_number) IN (
           SELECT g.result_id,g.question_number FROM grading_question_results g
            WHERE g.result_id IN (SELECT r.id FROM grading_results r WHERE r.attempt_id=ANY(attempt_ids))
            ORDER BY g.result_id,g.question_number LIMIT p_batch_limit);
       WHEN 'attempt_finalization_audit' THEN
         DELETE FROM attempt_finalization_audit WHERE attempt_id IN (
           SELECT f.attempt_id FROM attempt_finalization_audit f WHERE f.attempt_id=ANY(attempt_ids)
            ORDER BY f.attempt_id LIMIT p_batch_limit);
       WHEN 'assessment_notifications' THEN
         DELETE FROM assessment_notifications WHERE (closure_request_id,attempt_id,recipient_id) IN (
           SELECT n.closure_request_id,n.attempt_id,n.recipient_id FROM assessment_notifications n
            WHERE n.attempt_id=ANY(attempt_ids) ORDER BY n.id LIMIT p_batch_limit);
       WHEN 'closure_request_members' THEN
         DELETE FROM closure_request_members WHERE (closure_request_id,attempt_id) IN (
           SELECT m.closure_request_id,m.attempt_id FROM closure_request_members m
            WHERE m.attempt_id=ANY(attempt_ids) ORDER BY m.closure_request_id,m.attempt_id LIMIT p_batch_limit);
       WHEN 'closure_work_audit' THEN
         DELETE FROM closure_work_audit WHERE id IN (
           SELECT w.id FROM closure_work_audit w WHERE w.attempt_id=ANY(attempt_ids)
            ORDER BY w.id LIMIT p_batch_limit);
       WHEN 'publication_operations' THEN
         DELETE FROM publication_operations WHERE (publication_id,actor_id,key_digest) IN (
           SELECT o.publication_id,o.actor_id,o.key_digest FROM publication_operations o
            WHERE o.publication_id=ANY(publication_ids) ORDER BY o.publication_id,o.actor_id,o.key_digest LIMIT p_batch_limit);
       WHEN 'closure_requests' THEN
         DELETE FROM closure_requests WHERE id IN (
           SELECT c.id FROM closure_requests c WHERE c.publication_id=ANY(publication_ids)
            ORDER BY c.id LIMIT p_batch_limit);
       WHEN 'attempt_operations' THEN
         DELETE FROM attempt_operations WHERE (attempt_id,key_digest) IN (
           SELECT o.attempt_id,o.key_digest FROM attempt_operations o
            WHERE o.attempt_id=ANY(attempt_ids) ORDER BY o.attempt_id,o.key_digest LIMIT p_batch_limit);
       WHEN 'attempt_creation_operations' THEN
         DELETE FROM attempt_creation_operations WHERE (publication_id,student_id,key_digest) IN (
           SELECT c.publication_id,c.student_id,c.key_digest FROM attempt_creation_operations c
            WHERE c.attempt_id=ANY(attempt_ids) ORDER BY c.publication_id,c.student_id,c.key_digest LIMIT p_batch_limit);
       WHEN 'assessment_monitoring_outbox' THEN
         DELETE FROM assessment_monitoring_outbox WHERE event_id IN (
           SELECT e.event_id FROM assessment_monitoring_outbox e
            WHERE e.attempt_id=ANY(attempt_ids) ORDER BY e.event_id LIMIT p_batch_limit);
       WHEN 'realtime_invalidation_outbox' THEN
         DELETE FROM realtime_invalidation_outbox WHERE event_id IN (
           SELECT e.event_id FROM realtime_invalidation_outbox e
            WHERE (e.resource_kind='PUBLICATION' AND e.resource_id=ANY(publication_ids))
               OR (e.resource_kind='ATTEMPT' AND e.resource_id=ANY(attempt_ids))
            ORDER BY e.event_id LIMIT p_batch_limit);
       WHEN 'grading_results' THEN
         DELETE FROM grading_results WHERE id IN (
           SELECT g.id FROM grading_results g WHERE g.attempt_id=ANY(attempt_ids)
            ORDER BY g.id LIMIT p_batch_limit);
       WHEN 'submitted_answers' THEN
         DELETE FROM submitted_answers WHERE id IN (
           SELECT s.id FROM submitted_answers s WHERE s.attempt_id=ANY(attempt_ids)
            ORDER BY s.id LIMIT p_batch_limit);
       WHEN 'attempt_answers' THEN
         DELETE FROM attempt_answers WHERE (attempt_id,question_number) IN (
           SELECT a.attempt_id,a.question_number FROM attempt_answers a
            WHERE a.attempt_id=ANY(attempt_ids) ORDER BY a.attempt_id,a.question_number LIMIT p_batch_limit);
       WHEN 'attempts' THEN
         DELETE FROM attempts WHERE id IN (
           SELECT a.id FROM attempts a WHERE a.id=ANY(attempt_ids) ORDER BY a.id LIMIT p_batch_limit);
       WHEN 'publication_creations' THEN
         DELETE FROM publication_creations WHERE (owner_id,key_digest) IN (
           SELECT c.owner_id,c.key_digest FROM publication_creations c
            WHERE c.publication_id=ANY(publication_ids) ORDER BY c.owner_id,c.key_digest LIMIT p_batch_limit);
       WHEN 'publications' THEN
         DELETE FROM publications WHERE id IN (
           SELECT p.id FROM publications p WHERE p.id=ANY(publication_ids) OR p.quiz_id=p_quiz
            ORDER BY p.id LIMIT p_batch_limit);
       WHEN 'publication_snapshots' THEN
         DELETE FROM publication_snapshots WHERE publication_id IN (
           SELECT s.publication_id FROM publication_snapshots s WHERE s.publication_id=ANY(publication_ids)
            ORDER BY s.publication_id LIMIT p_batch_limit);
       ELSE
         DELETE FROM deletion_command_inbox WHERE id IN (
           SELECT i.id FROM deletion_command_inbox i WHERE i.quiz_id=p_quiz
            ORDER BY i.id LIMIT p_batch_limit);
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
   -- Restore the cyclic history constraints exactly as V4/V5 defined them, inside the
   -- same transaction; with the affected rows gone they validate trivially.
   ALTER TABLE attempts ADD FOREIGN KEY (submitted_answers_id, id)
     REFERENCES submitted_answers(id, attempt_id) ON DELETE RESTRICT ON UPDATE RESTRICT
     DEFERRABLE INITIALLY DEFERRED;
   ALTER TABLE attempts ADD FOREIGN KEY (result_id, id)
     REFERENCES grading_results(id, attempt_id) ON DELETE RESTRICT ON UPDATE RESTRICT
     DEFERRABLE INITIALLY DEFERRED;
   ALTER TABLE attempt_finalization_audit ADD FOREIGN KEY (result_id, attempt_id)
     REFERENCES grading_results(id, attempt_id) ON DELETE RESTRICT ON UPDATE RESTRICT
     DEFERRABLE INITIALLY DEFERRED;
   ALTER TABLE attempt_finalization_audit ADD FOREIGN KEY (closure_request_id,attempt_id)
     REFERENCES closure_request_members(closure_request_id,attempt_id) DEFERRABLE INITIALLY DEFERRED;
   ALTER TABLE closure_request_members ADD FOREIGN KEY
     (notification_id, closure_request_id, attempt_id, student_id)
     REFERENCES assessment_notifications(id, closure_request_id, attempt_id, recipient_id)
     DEFERRABLE INITIALLY DEFERRED;
   -- Actual zero-remaining verification over the captured inventory before any PURGED claim.
   IF EXISTS(SELECT 1 FROM publications WHERE quiz_id=p_quiz)
     OR EXISTS(SELECT 1 FROM publication_snapshots WHERE publication_id=ANY(publication_ids))
     OR EXISTS(SELECT 1 FROM attempts WHERE id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM attempt_answers WHERE attempt_id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM submitted_answers WHERE attempt_id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM grading_results WHERE attempt_id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM grading_question_results WHERE result_id IN
        (SELECT r.id FROM grading_results r WHERE r.attempt_id=ANY(attempt_ids)))
     OR EXISTS(SELECT 1 FROM attempt_operations WHERE attempt_id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM attempt_creation_operations WHERE attempt_id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM attempt_finalization_audit WHERE attempt_id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM closure_requests WHERE publication_id=ANY(publication_ids))
     OR EXISTS(SELECT 1 FROM closure_request_members WHERE attempt_id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM assessment_notifications WHERE attempt_id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM publication_operations WHERE publication_id=ANY(publication_ids))
     OR EXISTS(SELECT 1 FROM closure_work_audit WHERE attempt_id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM assessment_monitoring_outbox WHERE attempt_id=ANY(attempt_ids))
     OR EXISTS(SELECT 1 FROM realtime_invalidation_outbox WHERE
        (resource_kind='PUBLICATION' AND resource_id=ANY(publication_ids))
        OR (resource_kind='ATTEMPT' AND resource_id=ANY(attempt_ids)))
     OR EXISTS(SELECT 1 FROM publication_creations WHERE publication_id=ANY(publication_ids))
     OR EXISTS(SELECT 1 FROM deletion_command_inbox WHERE quiz_id=p_quiz) THEN
     RAISE EXCEPTION 'quiz purge left dependent rows behind';
   END IF;
   INSERT INTO deletion_purge_receipts(operation_id,quiz_id,step,rows_removed,completed_at)
   VALUES(p_operation,p_quiz,'verified_zero',0,clock_timestamp())
   ON CONFLICT (operation_id,step) DO UPDATE SET completed_at=EXCLUDED.completed_at;
   RETURN true;
 END
$body$
$ddl$,current_schema()); END $migration$;
REVOKE ALL ON FUNCTION assessment_purge_quiz(uuid,uuid,integer) FROM PUBLIC;
-- Unlike the operator-only expired-evidence retention paths, this FB-015 procedure is the
-- bounded purge invoked by the Assessment service itself through the private flag-gated
-- endpoint; deployment grants EXECUTE to the service role only, never to PUBLIC.
