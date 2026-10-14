-- Additive B3 implementation against executable V4.1. Drain all Assessment writers.
-- Table locks/generated lookups/history are retained; never down-migrate durable evidence.
LOCK TABLE publications,publication_snapshots,attempts,attempt_answers,submitted_answers,
 grading_results,grading_question_results,attempt_operations,attempt_finalization_audit
 IN ACCESS EXCLUSIVE MODE;
CREATE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE
 SET search_path=pg_catalog,pg_temp AS $$ SELECT clock_timestamp() $$;
ALTER TABLE publications DROP CONSTRAINT publications_v3_closure_disabled;
ALTER TABLE publications ADD CONSTRAINT publication_closed_timestamp CHECK (
 ((state='CLOSED' AND closed_at IS NOT NULL AND isfinite(closed_at)) OR (state<>'CLOSED' AND closed_at IS NULL)) IS TRUE);
ALTER TABLE attempts DROP CONSTRAINT attempts_v4_no_notice_cutoff;
ALTER TABLE attempt_finalization_audit DROP CONSTRAINT audit_v4_no_closure;
DO $$ DECLARE target text; matches integer; BEGIN
 SELECT count(*),min(conname::text) INTO matches,target FROM pg_constraint
 WHERE conrelid='attempt_finalization_audit'::regclass AND contype='c'
 AND pg_get_constraintdef(oid) LIKE '%MANUAL_SUBMIT%' AND pg_get_constraintdef(oid) LIKE '%BOUNDED_TIMEOUT%';
 IF matches<>1 THEN RAISE EXCEPTION 'Unexpected audit reason constraint'; END IF;
 EXECUTE format('ALTER TABLE attempt_finalization_audit DROP CONSTRAINT %I',target);
END $$;
ALTER TABLE attempt_finalization_audit ADD CONSTRAINT audit_reason_v5 CHECK
 (reason IN ('MANUAL_SUBMIT','BOUNDED_TIMEOUT','STUDENT_ABANDON','TEACHER_NOTICE_CUTOFF'));
CREATE TABLE closure_requests (
  id uuid PRIMARY KEY,
  publication_id uuid NOT NULL REFERENCES publications(id),
  requesting_teacher_id uuid NOT NULL,
  requested_at timestamptz NOT NULL CHECK ((isfinite(requested_at)) IS TRUE),
  capture_transaction_id xid8 NOT NULL DEFAULT pg_current_xact_id(),
  captured_members bigint NOT NULL DEFAULT 0 CHECK (captured_members>=0),
  capture_digest assessment_digest NOT NULL DEFAULT repeat('0',64),
  state varchar(14) NOT NULL CHECK ((state IN
    ('PENDING','FINALIZING','CANCELLED','COMPLETED','RESOLVED_EARLY')) IS TRUE),
  finalization_started_at timestamptz,
  resolved_at timestamptz,
  UNIQUE (id, publication_id),
  CHECK ((finalization_started_at IS NULL OR
    (isfinite(finalization_started_at) AND finalization_started_at >= requested_at)) IS TRUE),
  CHECK (((state = 'PENDING' AND finalization_started_at IS NULL AND resolved_at IS NULL)
    OR (state = 'FINALIZING' AND finalization_started_at IS NOT NULL AND resolved_at IS NULL)
    OR (state = 'CANCELLED' AND finalization_started_at IS NULL AND resolved_at IS NOT NULL)
    OR (state IN ('COMPLETED','RESOLVED_EARLY') AND resolved_at IS NOT NULL)) IS TRUE),
  CHECK ((resolved_at IS NULL OR (isfinite(resolved_at) AND resolved_at >= requested_at)) IS TRUE)
);
CREATE UNIQUE INDEX one_live_closure_request
  ON closure_requests(publication_id) WHERE state IN ('PENDING','FINALIZING');
CREATE INDEX closure_request_history ON closure_requests(publication_id, requested_at DESC, id DESC);

CREATE TABLE closure_request_members (
  closure_request_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  publication_id uuid NOT NULL,
  student_id uuid NOT NULL,
  state varchar(20) NOT NULL CHECK ((state IN
    ('NOTICE_PENDING','NOTICE_PUBLISHED','NOTICE_CONFIRMED','FINALIZED',
     'RESOLVED_TERMINAL','RESOLVED_CANCELLED')) IS TRUE),
  notification_id uuid,
  next_work_at timestamptz NOT NULL DEFAULT assessment_time(),
  retry_count bigint NOT NULL DEFAULT 0 CHECK(retry_count>=0),
  confirmed_notice_at timestamptz,
  closure_cutoff_at timestamptz,
  accessibility_version integer,
  recipient_checked_at timestamptz,
  resolved_at timestamptz,
  PRIMARY KEY (closure_request_id, attempt_id),
  UNIQUE (closure_request_id, attempt_id, student_id),
  FOREIGN KEY (closure_request_id, publication_id)
    REFERENCES closure_requests(id, publication_id),
  FOREIGN KEY (attempt_id, publication_id, student_id)
    REFERENCES attempts(id, publication_id, student_id),
  CHECK (((confirmed_notice_at IS NULL AND closure_cutoff_at IS NULL
      AND accessibility_version IS NULL AND recipient_checked_at IS NULL) OR
    (notification_id IS NOT NULL AND confirmed_notice_at IS NOT NULL
      AND isfinite(confirmed_notice_at) AND closure_cutoff_at IS NOT NULL
      AND isfinite(closure_cutoff_at) AND accessibility_version = 1
      AND recipient_checked_at IS NOT NULL AND isfinite(recipient_checked_at)
      AND recipient_checked_at<=confirmed_notice_at AND confirmed_notice_at<=recipient_checked_at+interval '5 seconds'
      AND closure_cutoff_at = confirmed_notice_at + interval '48 hours')) IS TRUE),
  CHECK (((state IN ('NOTICE_PENDING','NOTICE_PUBLISHED','NOTICE_CONFIRMED')
      AND resolved_at IS NULL) OR
    (state IN ('FINALIZED','RESOLVED_TERMINAL','RESOLVED_CANCELLED')
      AND resolved_at IS NOT NULL AND isfinite(resolved_at))) IS TRUE),
  CHECK ((state <> 'NOTICE_PENDING' OR notification_id IS NULL) IS TRUE),
  CHECK ((state <> 'NOTICE_PUBLISHED' OR notification_id IS NOT NULL) IS TRUE),
  CHECK ((state NOT IN ('NOTICE_CONFIRMED','FINALIZED') OR confirmed_notice_at IS NOT NULL) IS TRUE)
);
CREATE INDEX closure_member_due ON closure_request_members
  (closure_cutoff_at, publication_id, closure_request_id, attempt_id)
  WHERE state = 'NOTICE_CONFIRMED';
CREATE INDEX closure_member_pending_notice ON closure_request_members
  (next_work_at, publication_id, closure_request_id, attempt_id)
  WHERE state IN ('NOTICE_PENDING','NOTICE_PUBLISHED');
CREATE INDEX closure_member_attempt ON closure_request_members(attempt_id, closure_request_id);

CREATE TABLE assessment_notifications (
  id uuid PRIMARY KEY,
  closure_request_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  recipient_id uuid NOT NULL,
  content_version integer NOT NULL CHECK ((content_version = 1) IS TRUE),
  message text NOT NULL CHECK (length(message)>0),
  publication_title text NOT NULL CHECK ((length(publication_title) BETWEEN 1 AND 300) IS TRUE),
  state varchar(9) NOT NULL CHECK ((state = 'PUBLISHED') IS TRUE),
  published_at timestamptz NOT NULL CHECK ((isfinite(published_at)) IS TRUE),
  publish_transaction_id xid8 NOT NULL DEFAULT pg_current_xact_id(),
  UNIQUE (closure_request_id, attempt_id),
  UNIQUE (id, closure_request_id, attempt_id, recipient_id),
  FOREIGN KEY (closure_request_id, attempt_id, recipient_id)
    REFERENCES closure_request_members(closure_request_id, attempt_id, student_id)
);
ALTER TABLE closure_request_members ADD FOREIGN KEY
  (notification_id, closure_request_id, attempt_id, student_id)
  REFERENCES assessment_notifications(id, closure_request_id, attempt_id, recipient_id)
  DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX notification_inbox ON assessment_notifications(recipient_id, published_at DESC, id DESC);

CREATE TABLE publication_operations (
  publication_id uuid NOT NULL REFERENCES publications(id),
  actor_id uuid NOT NULL,
  key_digest assessment_digest NOT NULL,
  action varchar(8) NOT NULL CHECK ((action IN ('CLOSE','REQUEST','CANCEL','COMPLETE')) IS TRUE),
  request_digest assessment_digest NOT NULL,
  closure_request_id uuid,
  state varchar(12) NOT NULL CHECK ((state IN ('IN_PROGRESS','ACKNOWLEDGED')) IS TRUE),
  unit_budget integer NOT NULL DEFAULT 50 CHECK (unit_budget BETWEEN 1 AND 50),
  processed_units integer NOT NULL DEFAULT 0 CHECK ((processed_units BETWEEN 0 AND unit_budget) IS TRUE),
  response_status integer,
  response_body jsonb,
  created_at timestamptz NOT NULL CHECK ((isfinite(created_at)) IS TRUE),
  acknowledged_at timestamptz,
  PRIMARY KEY (publication_id, actor_id, key_digest),
  FOREIGN KEY (closure_request_id, publication_id) REFERENCES closure_requests(id, publication_id),
  CHECK (((state = 'IN_PROGRESS' AND action = 'COMPLETE' AND response_status IS NULL
      AND response_body IS NULL AND acknowledged_at IS NULL) OR
    (state = 'ACKNOWLEDGED' AND response_status IN (200,201,202)
      AND response_body IS NOT NULL AND jsonb_typeof(response_body) = 'object'
      AND acknowledged_at IS NOT NULL AND isfinite(acknowledged_at))) IS TRUE),
  CHECK (((action = 'CLOSE' AND closure_request_id IS NULL)
    OR (action <> 'CLOSE' AND closure_request_id IS NOT NULL)) IS TRUE)
);
ALTER TABLE attempt_finalization_audit ADD FOREIGN KEY(closure_request_id,attempt_id)
 REFERENCES closure_request_members(closure_request_id,attempt_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE attempt_finalization_audit ADD FOREIGN KEY(notification_id) REFERENCES assessment_notifications(id);
ALTER TABLE attempt_finalization_audit ADD CONSTRAINT audit_closure_evidence CHECK ((
 (reason='TEACHER_NOTICE_CUTOFF' AND closure_request_id IS NOT NULL AND requesting_teacher_id IS NOT NULL
  AND notification_id IS NOT NULL AND confirmed_notice_at IS NOT NULL AND isfinite(confirmed_notice_at)
  AND closure_cutoff_at IS NOT NULL AND accessibility_version=1
  AND closure_cutoff_at=confirmed_notice_at+interval '48 hours' AND finalized_at>=closure_cutoff_at AND actor_kind='SYSTEM')
 OR (reason<>'TEACHER_NOTICE_CUTOFF' AND closure_request_id IS NULL AND requesting_teacher_id IS NULL
  AND notification_id IS NULL AND confirmed_notice_at IS NULL AND closure_cutoff_at IS NULL AND accessibility_version IS NULL)) IS TRUE);

CREATE OR REPLACE FUNCTION guard_publication_update() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN
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
 END $$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.closure_request_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE p record; BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Closure history is immutable'; END IF;
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
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Closure membership is immutable'; END IF;
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
CREATE OR REPLACE FUNCTION %1$I.notification_insert_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE m record; c record; p record; a record; BEGIN
 SELECT * INTO STRICT m FROM %1$I.closure_request_members WHERE closure_request_id=NEW.closure_request_id AND attempt_id=NEW.attempt_id;
 SELECT * INTO STRICT p FROM %1$I.publications WHERE id=m.publication_id FOR UPDATE;
 SELECT * INTO STRICT c FROM %1$I.closure_requests WHERE id=m.closure_request_id FOR UPDATE;
 SELECT * INTO STRICT a FROM %1$I.attempts WHERE id=m.attempt_id FOR UPDATE;
 PERFORM 1 FROM %1$I.closure_request_members WHERE closure_request_id=m.closure_request_id AND attempt_id=m.attempt_id FOR UPDATE;
 IF c.state NOT IN ('PENDING','FINALIZING') OR a.state<>'ACTIVE' OR m.state<>'NOTICE_PENDING'
  OR NEW.recipient_id<>m.student_id OR NEW.publication_title<>p.configuration->>'title'
  OR NEW.published_at<c.requested_at OR NEW.published_at>%1$I.assessment_time()
  OR NEW.published_at<transaction_timestamp() THEN RAISE EXCEPTION 'Invalid published notice'; END IF;
 NEW.publish_transaction_id:=pg_current_xact_id();RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.publication_operation_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE p record; BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Operation evidence is immutable'; END IF;
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
CREATE OR REPLACE FUNCTION %1$I.closure_capture_consistency() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE c record; total bigint; digest text; BEGIN
 SELECT * INTO STRICT c FROM %1$I.closure_requests WHERE id=NEW.id;
 SELECT count(*),encode(sha256(convert_to(coalesce(string_agg(attempt_id::text,',' ORDER BY attempt_id),''),'UTF8')),'hex') INTO total,digest
  FROM %1$I.closure_request_members WHERE closure_request_id=c.id;
 IF total<>c.captured_members OR digest<>c.capture_digest THEN RAISE EXCEPTION 'Partial closure capture forbidden'; END IF;
 RETURN NULL;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.closure_workflow_consistency() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE c record; bad boolean; BEGIN
 SELECT * INTO STRICT c FROM %1$I.closure_requests WHERE id=NEW.id;
 IF c.finalization_started_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM %1$I.attempt_finalization_audit WHERE closure_request_id=c.id AND reason='TEACHER_NOTICE_CUTOFF' AND finalized_at=c.finalization_started_at) THEN RAISE EXCEPTION 'Fence must commit with first finalization'; END IF;
 IF c.state='CANCELLED' AND EXISTS(SELECT 1 FROM %1$I.attempt_finalization_audit WHERE closure_request_id=c.id) THEN RAISE EXCEPTION 'Cannot cancel finalized closure'; END IF;
 IF c.state IN ('COMPLETED','RESOLVED_EARLY') AND NOT EXISTS(SELECT 1 FROM %1$I.publications WHERE id=c.publication_id AND state='CLOSED' AND closed_at=c.resolved_at) THEN RAISE EXCEPTION 'Resolved closure requires matching Teacher CLOSED'; END IF;
 IF c.state IN ('CANCELLED','COMPLETED','RESOLVED_EARLY') AND EXISTS(SELECT 1 FROM %1$I.closure_request_members WHERE closure_request_id=c.id AND state NOT IN ('FINALIZED','RESOLVED_TERMINAL','RESOLVED_CANCELLED')) THEN RAISE EXCEPTION 'Resolved request has unresolved members'; END IF;
 RETURN NULL;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.closure_member_consistency() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE m record; a record; c record; n record; audit record; BEGIN
 SELECT * INTO STRICT m FROM %1$I.closure_request_members WHERE closure_request_id=NEW.closure_request_id AND attempt_id=NEW.attempt_id;
 SELECT * INTO STRICT a FROM %1$I.attempts WHERE id=m.attempt_id;
 SELECT * INTO STRICT c FROM %1$I.closure_requests WHERE id=m.closure_request_id;
 IF m.notification_id IS NOT NULL THEN
  SELECT * INTO STRICT n FROM %1$I.assessment_notifications WHERE id=m.notification_id;
  IF m.state='NOTICE_PENDING' OR n.recipient_id<>m.student_id THEN RAISE EXCEPTION 'Invalid notice/member state'; END IF;
 END IF;
 IF m.state='NOTICE_PUBLISHED' AND m.confirmed_notice_at IS NOT NULL THEN RAISE EXCEPTION 'Published is not confirmed'; END IF;
 IF m.state='FINALIZED' THEN
  SELECT * INTO STRICT audit FROM %1$I.attempt_finalization_audit WHERE attempt_id=a.id;
  IF a.terminal_reason IS DISTINCT FROM 'TEACHER_NOTICE_CUTOFF' OR audit.closure_request_id IS DISTINCT FROM c.id OR m.resolved_at IS DISTINCT FROM a.finalized_at THEN RAISE EXCEPTION 'Finalized member lacks matching result'; END IF;
 ELSIF m.state='RESOLVED_TERMINAL' AND (a.state='ACTIVE' OR m.resolved_at IS DISTINCT FROM a.finalized_at) THEN RAISE EXCEPTION 'Unresolved active member';
 ELSIF m.state='RESOLVED_CANCELLED' AND (c.state<>'CANCELLED' OR m.resolved_at IS DISTINCT FROM c.resolved_at) THEN RAISE EXCEPTION 'Cancellation identity mismatch';
 END IF;
 IF c.state IN ('PENDING','FINALIZING') AND a.state<>'ACTIVE' AND m.state NOT IN ('FINALIZED','RESOLVED_TERMINAL') THEN RAISE EXCEPTION 'Terminal member must resolve'; END IF;
 RETURN NULL;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.attempt_closure_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE pid uuid; cutoff timestamptz; BEGIN
 pid:=NEW.publication_id;PERFORM 1 FROM %1$I.publications WHERE id=pid FOR UPDATE;
 IF TG_OP='INSERT' THEN
  IF EXISTS(SELECT 1 FROM %1$I.closure_requests WHERE publication_id=pid AND state IN ('PENDING','FINALIZING')) THEN RAISE EXCEPTION 'Pending closure blocks creation'; END IF;
 ELSE
  SELECT m.closure_cutoff_at INTO cutoff FROM %1$I.closure_requests c JOIN %1$I.closure_request_members m ON m.closure_request_id=c.id
   WHERE c.publication_id=pid AND c.state IN ('PENDING','FINALIZING') AND m.attempt_id=NEW.id AND m.state='NOTICE_CONFIRMED';
  IF cutoff IS NOT NULL AND (
   (NEW.state='ACTIVE' AND %1$I.assessment_time()>=cutoff) OR
   (NEW.terminal_reason IN ('MANUAL_SUBMIT','STUDENT_ABANDON') AND (NEW.finalized_at>=cutoff OR %1$I.assessment_time()>=cutoff))) THEN
   RAISE EXCEPTION USING ERRCODE='QZ001',MESSAGE='CLOSURE_CUTOFF_REACHED'; END IF;
 END IF;
 RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.answer_closure_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE cutoff timestamptz; equivalent boolean:=false; BEGIN
 SELECT m.closure_cutoff_at INTO cutoff FROM %1$I.closure_request_members m JOIN %1$I.closure_requests c ON c.id=m.closure_request_id
  WHERE m.attempt_id=NEW.attempt_id AND c.state IN ('PENDING','FINALIZING') AND m.state='NOTICE_CONFIRMED';
 IF cutoff IS NOT NULL AND %1$I.assessment_time()>=cutoff AND TG_OP='UPDATE' THEN
  -- Pure set-order normalization preserves the saved answer meaning/time. V4.1
  -- still requires a matching parent write and full terminal consistency at commit.
  IF NEW.saved_at=OLD.saved_at AND NEW.answer->>'type'='MULTIPLE_CHOICE'
   AND (NEW.answer-'optionLabels')=(OLD.answer-'optionLabels') THEN
   SELECT (SELECT jsonb_agg(v ORDER BY v) FROM jsonb_array_elements(NEW.answer->'optionLabels') x(v))
    IS NOT DISTINCT FROM (SELECT jsonb_agg(v ORDER BY v) FROM jsonb_array_elements(OLD.answer->'optionLabels') x(v)) INTO equivalent;
  END IF;
 END IF;
 IF cutoff IS NOT NULL AND (NEW.saved_at>=cutoff OR (%1$I.assessment_time()>=cutoff AND equivalent IS DISTINCT FROM true)) THEN
  RAISE EXCEPTION USING ERRCODE='QZ001',MESSAGE='CLOSURE_CUTOFF_REACHED'; END IF;
 RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.resolve_terminal_closure_member() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
BEGIN
 IF NEW.state<>'ACTIVE' AND NEW.terminal_reason<>'TEACHER_NOTICE_CUTOFF' THEN
  UPDATE %1$I.closure_request_members SET state='RESOLVED_TERMINAL',resolved_at=NEW.finalized_at
   WHERE attempt_id=NEW.id AND state IN ('NOTICE_PENDING','NOTICE_PUBLISHED','NOTICE_CONFIRMED')
    AND EXISTS(SELECT 1 FROM %1$I.closure_requests c WHERE c.id=closure_request_id AND c.state IN ('PENDING','FINALIZING'));
 END IF;RETURN NULL;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.attempt_closure_consistency() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE a record; audit record; m record; c record; n record; BEGIN
 SELECT * INTO STRICT a FROM %1$I.attempts WHERE id=NEW.id;
 IF a.terminal_reason='TEACHER_NOTICE_CUTOFF' THEN
  SELECT * INTO STRICT audit FROM %1$I.attempt_finalization_audit WHERE attempt_id=a.id;
  SELECT * INTO STRICT m FROM %1$I.closure_request_members WHERE closure_request_id=audit.closure_request_id AND attempt_id=a.id;
  SELECT * INTO STRICT c FROM %1$I.closure_requests WHERE id=m.closure_request_id;
  SELECT * INTO STRICT n FROM %1$I.assessment_notifications WHERE id=m.notification_id;
  IF a.duration_minutes<>0 OR m.state<>'FINALIZED' OR c.state NOT IN ('FINALIZING','COMPLETED','RESOLVED_EARLY')
   OR c.finalization_started_at IS NULL OR a.finalized_at<m.closure_cutoff_at
   OR ROW(audit.publication_id,audit.requesting_teacher_id,audit.notification_id,audit.confirmed_notice_at,audit.closure_cutoff_at,audit.accessibility_version)
    IS DISTINCT FROM ROW(a.publication_id,c.requesting_teacher_id,n.id,m.confirmed_notice_at,m.closure_cutoff_at,m.accessibility_version)
   OR n.recipient_id<>a.student_id OR audit.actor_kind<>'SYSTEM' THEN RAISE EXCEPTION 'Invalid authoritative closure finalization'; END IF;
 END IF;RETURN NULL;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.publication_closure_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
BEGIN
 IF TG_OP='UPDATE' AND OLD.state='OPEN' THEN
  IF EXISTS(SELECT 1 FROM %1$I.closure_requests WHERE publication_id=NEW.id AND state IN ('PENDING','FINALIZING'))
   AND ROW(NEW.end_at,NEW.duration_minutes,NEW.max_attempts) IS DISTINCT FROM ROW(OLD.end_at,OLD.duration_minutes,OLD.max_attempts) THEN RAISE EXCEPTION 'Pending closure freezes schedule'; END IF;
 END IF;RETURN NEW;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.publication_closed_consistency() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE p record; BEGIN
 SELECT * INTO STRICT p FROM %1$I.publications WHERE id=NEW.id;
 IF p.state='CLOSED' THEN
  IF EXISTS(SELECT 1 FROM %1$I.attempts WHERE publication_id=p.id AND state='ACTIVE')
   OR EXISTS(SELECT 1 FROM %1$I.closure_requests WHERE publication_id=p.id AND state IN ('PENDING','FINALIZING'))
   OR NOT EXISTS(SELECT 1 FROM %1$I.publication_operations WHERE publication_id=p.id AND actor_id=p.owner_id AND state='ACKNOWLEDGED'
    AND response_status=200 AND action IN ('CLOSE','COMPLETE') AND xmin=pg_current_xact_id()::xid)
   OR p.closed_at<p.opened_at OR p.closed_at>%1$I.assessment_time()
   OR EXISTS(SELECT 1 FROM %1$I.attempts WHERE publication_id=p.id AND finalized_at>p.closed_at) THEN RAISE EXCEPTION 'CLOSED requires all terminal work and a current Teacher receipt'; END IF;
 END IF;RETURN NULL;
END
$body$
$ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.notification_member_consistency() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM %1$I.closure_request_members WHERE closure_request_id=NEW.closure_request_id
   AND attempt_id=NEW.attempt_id AND student_id=NEW.recipient_id AND notification_id=NEW.id AND state<>'NOTICE_PENDING') THEN
   RAISE EXCEPTION 'Published notification must commit with member visibility'; END IF;
 RETURN NULL;
END $body$ $ddl$,current_schema()); END $migration$;
CREATE CONSTRAINT TRIGGER notification_member_consistency AFTER INSERT ON assessment_notifications
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION notification_member_consistency();
CREATE TABLE closure_work_audit (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 closure_request_id uuid NOT NULL,
 attempt_id uuid NOT NULL,
 stage varchar(20) NOT NULL CHECK(stage IN ('NOTICE_PENDING','NOTICE_PUBLISHED','NOTICE_CONFIRMED','BOUNDED_TIMEOUT')),
 outcome varchar(24) NOT NULL CHECK(outcome IN ('PUBLISHED','CONFIRMED','FINALIZED','INELIGIBLE','AUTHORITY_UNAVAILABLE','INBOX_UNAVAILABLE','STALE','FAILED')),
 occurred_at timestamptz NOT NULL DEFAULT assessment_time() CHECK(isfinite(occurred_at)),
 FOREIGN KEY(closure_request_id) REFERENCES closure_requests(id),
 FOREIGN KEY(attempt_id) REFERENCES attempts(id)
);
CREATE INDEX closure_work_history ON closure_work_audit(closure_request_id,occurred_at,id);
CREATE TRIGGER closure_work_immutable BEFORE UPDATE OR DELETE ON closure_work_audit FOR EACH ROW EXECUTE FUNCTION reject_assessment_mutation();
CREATE TRIGGER closure_work_no_truncate BEFORE TRUNCATE ON closure_work_audit FOR EACH STATEMENT EXECUTE FUNCTION reject_assessment_mutation();

-- Cache SQL plans, never authority/data. Repeated EXECUTE parsing on every answer
-- and question-grade row can consume the accepted full-answer transaction budget.
-- Relations are bound to the owning migration schema, with no search-path lookup.
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.lock_assessment_parent() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE attempt_key uuid; publication_key uuid;
BEGIN
 IF TG_TABLE_NAME='grading_question_results' THEN
  SELECT attempt_id INTO attempt_key FROM %1$I.grading_results WHERE id=NEW.result_id;
 ELSE attempt_key:=NEW.attempt_id;
 END IF;
 SELECT publication_id INTO publication_key FROM %1$I.attempts WHERE id=attempt_key;
 IF publication_key IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Assessment parent is invalid'; END IF;
 PERFORM id FROM %1$I.publications WHERE id=publication_key FOR UPDATE;
 PERFORM id FROM %1$I.closure_requests WHERE publication_id=publication_key AND state IN ('PENDING','FINALIZING') ORDER BY id FOR UPDATE;
 PERFORM id FROM %1$I.attempts WHERE id=attempt_key FOR UPDATE;
 RETURN NEW;
END $body$ $ddl$,current_schema()); END $migration$;

-- Preserve V4.1's generated type lookup and exact answer/time/identity checks.
-- Static schema-qualified plans remove parsing cost, not per-write validation.
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.guard_answer_write() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE a record; kind text; valid boolean;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Use an unanswered value to clear an answer'; END IF;
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
END $body$ $ddl$,current_schema()); END $migration$;

-- Preserve every V4.1 child/full-parent validation. Only SQL plan preparation
-- changes; no data/authority cache or skipped deferred event is introduced.
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.validate_attempt_consistency() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $body$
DECLARE aid uuid; a record; s record; r record; audit record; answers record;
 declarations integer; grades integer; audits integer; snapshot jsonb; expected jsonb;
 earned numeric; possible numeric; valid boolean; current_answer record;
BEGIN
 IF TG_TABLE_NAME<>'attempts' THEN
  IF TG_TABLE_NAME='grading_question_results' THEN
   SELECT current_attempt.*,current_attempt.xmin AS write_transaction,result_row.possible_weight AS question_count
    INTO a FROM %1$I.grading_results result_row JOIN %1$I.attempts current_attempt ON current_attempt.id=result_row.attempt_id WHERE result_row.id=NEW.result_id;
  ELSE
   SELECT current_attempt.*,current_attempt.xmin AS write_transaction INTO a FROM %1$I.attempts current_attempt WHERE current_attempt.id=NEW.attempt_id;
  END IF;
  IF a.id IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Missing attempt'; END IF;
  aid:=a.id;
  IF a.write_transaction<>pg_current_xact_id()::xid THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Child mutation requires the parent transaction';
  END IF;
  IF TG_TABLE_NAME='attempt_answers' THEN
   SELECT saved.* INTO current_answer FROM %1$I.attempt_answers saved WHERE saved.attempt_id=aid AND saved.question_number=NEW.question_number;
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
    IF NEW.result_id IS DISTINCT FROM a.result_id OR possible IS NULL OR NEW.question_number>possible THEN
     RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Grade question reference is invalid';
    END IF;
   END IF;
  ELSE RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Unknown assessment child';
  END IF;
  RETURN NULL;
 END IF;
 aid:=NEW.id;
 SELECT publication_id INTO s FROM %1$I.attempts WHERE id=aid;
 IF s.publication_id IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Missing attempt'; END IF;
 PERFORM id FROM %1$I.publications WHERE id=s.publication_id FOR UPDATE;
 PERFORM id FROM %1$I.closure_requests WHERE publication_id=s.publication_id AND state IN ('PENDING','FINALIZING') ORDER BY id FOR UPDATE;
 SELECT current_attempt.*,current_attempt.xmin AS write_transaction INTO a FROM %1$I.attempts current_attempt WHERE current_attempt.id=aid FOR UPDATE;
 SELECT payload INTO snapshot FROM %1$I.publication_snapshots WHERE publication_id=a.snapshot_id;
 SELECT count(*) INTO declarations FROM %1$I.submitted_answers WHERE attempt_id=aid;
 SELECT count(*) INTO grades FROM %1$I.grading_results WHERE attempt_id=aid;
 SELECT count(*) INTO audits FROM %1$I.attempt_finalization_audit WHERE attempt_id=aid;
 IF a.state='ACTIVE' THEN
  IF declarations<>0 OR grades<>0 OR audits<>0 THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Partial finalization is forbidden'; END IF;
 ELSIF a.state='ABANDONED' THEN
  IF declarations<>0 OR grades<>0 OR audits<>1 THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Abandonment must be ungraded and audited'; END IF;
 ELSE
  IF declarations<>1 OR grades<>1 OR audits<>1 THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Submission must have complete result and audit'; END IF;
  SELECT * INTO answers FROM %1$I.submitted_answers WHERE attempt_id=aid;
  SELECT * INTO r FROM %1$I.grading_results WHERE attempt_id=aid;
  SELECT jsonb_agg(jsonb_build_object('questionNumber',q.value->'number','answer',coalesce(v.answer,%1$I.assessment_unanswered(q.value->>'type'))) ORDER BY q.n)
   INTO expected FROM jsonb_array_elements(snapshot->'structuredContent'->'questions') WITH ORDINALITY q(value,n)
   LEFT JOIN %1$I.attempt_answers v ON v.attempt_id=aid AND v.question_number=q.n;
  IF expected IS NULL OR answers.canonical_answers IS DISTINCT FROM expected OR answers.id IS DISTINCT FROM a.submitted_answers_id
   OR answers.answer_schema_version<>a.answer_schema_version OR answers.created_at<>a.finalized_at
   OR r.id IS DISTINCT FROM a.result_id OR r.submitted_answers_id<>answers.id OR r.graded_at<>a.finalized_at THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Submitted identity or authoritative answers are inconsistent';
  END IF;
  valid:=encode(sha256(convert_to(%1$I.assessment_canonical_json(expected),'UTF8')),'hex')=answers.answer_digest;
  IF valid IS DISTINCT FROM true THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Submitted answer digest is invalid'; END IF;
  SELECT count(*),coalesce(sum(earned_weight),0),coalesce(sum(possible_weight),0) INTO declarations,earned,possible FROM %1$I.grading_question_results WHERE result_id=r.id;
  IF declarations<>jsonb_array_length(expected) OR r.possible_weight<>jsonb_array_length(expected)
   OR earned<>r.earned_weight OR possible<>r.possible_weight THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Exact result totals are inconsistent';
  END IF;
  SELECT EXISTS(SELECT 1 FROM %1$I.grading_question_results WHERE result_id=r.id AND question_number>jsonb_array_length(expected)) INTO valid;
  IF valid THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Grade question reference is invalid'; END IF;
 END IF;
 IF a.state<>'ACTIVE' THEN
  SELECT * INTO audit FROM %1$I.attempt_finalization_audit WHERE attempt_id=aid;
  IF audit.publication_id<>a.publication_id OR audit.reason<>a.terminal_reason OR audit.finalized_at<>a.finalized_at
   OR audit.result_id IS DISTINCT FROM a.result_id OR audit.grading_policy_version<>a.grading_policy_version
   OR (audit.reason IN ('MANUAL_SUBMIT','STUDENT_ABANDON') AND (audit.actor_kind<>'USER' OR audit.actor_id<>a.student_id::text))
   OR (audit.reason='BOUNDED_TIMEOUT' AND (audit.actor_kind<>'SYSTEM' OR a.deadline_at IS NULL OR a.finalized_at<a.deadline_at))
   OR (audit.reason IN ('MANUAL_SUBMIT','STUDENT_ABANDON') AND a.deadline_at IS NOT NULL AND a.finalized_at>=a.deadline_at) THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Finalization audit or deadline is inconsistent';
  END IF;
 END IF;
 RETURN NULL;
END $body$ $ddl$,current_schema()); END $migration$;

CREATE TRIGGER closure_request_write BEFORE INSERT OR UPDATE OR DELETE ON closure_requests FOR EACH ROW EXECUTE FUNCTION closure_request_guard();
CREATE CONSTRAINT TRIGGER closure_capture AFTER INSERT ON closure_requests DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION closure_capture_consistency();
CREATE CONSTRAINT TRIGGER closure_workflow AFTER INSERT OR UPDATE ON closure_requests DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION closure_workflow_consistency();
CREATE TRIGGER closure_member_write BEFORE INSERT OR UPDATE OR DELETE ON closure_request_members FOR EACH ROW EXECUTE FUNCTION closure_member_guard();
CREATE CONSTRAINT TRIGGER closure_member_consistency AFTER INSERT OR UPDATE ON closure_request_members DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION closure_member_consistency();
CREATE TRIGGER notification_insert BEFORE INSERT ON assessment_notifications FOR EACH ROW EXECUTE FUNCTION notification_insert_guard();
CREATE TRIGGER notification_immutable BEFORE UPDATE OR DELETE ON assessment_notifications FOR EACH ROW EXECUTE FUNCTION reject_assessment_mutation();
CREATE TRIGGER operation_write BEFORE INSERT OR UPDATE OR DELETE ON publication_operations FOR EACH ROW EXECUTE FUNCTION publication_operation_guard();
CREATE TRIGGER attempt_closure_write BEFORE INSERT OR UPDATE ON attempts FOR EACH ROW EXECUTE FUNCTION attempt_closure_guard();
CREATE TRIGGER z_answer_closure_write BEFORE INSERT OR UPDATE ON attempt_answers FOR EACH ROW EXECUTE FUNCTION answer_closure_guard();
CREATE TRIGGER terminal_member_resolution AFTER UPDATE ON attempts FOR EACH ROW EXECUTE FUNCTION resolve_terminal_closure_member();
CREATE CONSTRAINT TRIGGER attempt_closure_consistency AFTER INSERT OR UPDATE ON attempts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION attempt_closure_consistency();
CREATE TRIGGER publication_closure_write BEFORE UPDATE ON publications FOR EACH ROW EXECUTE FUNCTION publication_closure_guard();
CREATE CONSTRAINT TRIGGER publication_closed_consistency AFTER UPDATE ON publications DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION publication_closed_consistency();
DO $$ DECLARE name text; BEGIN
 FOREACH name IN ARRAY ARRAY['closure_requests','closure_request_members','assessment_notifications','publication_operations'] LOOP
  EXECUTE format('CREATE TRIGGER no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION reject_assessment_mutation()',name);
 END LOOP;
END $$;
