-- FB-015 PR C corrections (Leader review H1+H3+H5): least-privilege purge boundary,
-- genuinely bounded purge rounds with durable progress, and legacy (pre-V4,
-- quiz_id NULL) dependent resolution driven by Assessment-owned id inventories.
--
-- PRODUCTION PROVISIONING CONTRACT (do not silently weaken):
--   * quizopia_proctoring_owner - NOLOGIN owner of every monitoring table and of the
--     SECURITY DEFINER purge procedure. Never used as a connection role.
--   * proctoring - the application runtime role (the DB_USERNAME the service
--     authenticates with). It receives USAGE on the schema, DML without DELETE or
--     TRUNCATE anywhere, and EXECUTE only on monitoring_purge_quiz. The operator-only
--     monitoring_purge_expired retention procedure stays ungranted to the runtime role.
-- Flyway must run as a member of quizopia_proctoring_owner (locally:
-- quizopia_proctoring_flyway from infrastructure/postgres/01-init-databases.sh).
-- Superuser (disposable Testcontainers) execution creates missing roles with
-- local-only credentials so adversarial tests can connect as the runtime role;
-- otherwise a missing role FAILS LOUDLY.

-- ---------------------------------------------------------------------------
-- H3: durable per-round purge progress (counts only, no content, no PII).
-- ---------------------------------------------------------------------------
CREATE TABLE purge_progress (
  operation_id uuid PRIMARY KEY,
  quiz_id uuid NOT NULL,
  next_step integer NOT NULL CHECK (next_step >= 1),
  total_removed bigint NOT NULL CHECK (total_removed >= 0),
  rounds integer NOT NULL CHECK (rounds >= 1),
  updated_at timestamptz NOT NULL CHECK (isfinite(updated_at))
);

-- Restricted immutable attribution survives Assessment deletion and local session purge.
CREATE TABLE purge_inventory (
  operation_id uuid PRIMARY KEY, quiz_id uuid NOT NULL UNIQUE,
  publications uuid[], attempts uuid[], sessions uuid[] NOT NULL,
  resolved_attempts uuid[] NOT NULL, resolved_publications uuid[] NOT NULL,
  captured boolean NOT NULL DEFAULT false
);
CREATE TABLE proctoring_retention_holds (
  hold_id uuid PRIMARY KEY, quiz_id uuid NOT NULL, session_id uuid,
  authority text NOT NULL CHECK (length(trim(authority))>0),
  reason text NOT NULL CHECK (length(trim(reason))>0),
  reviewer text NOT NULL CHECK (length(trim(reviewer))>0),
  expires_at timestamptz NOT NULL CHECK (isfinite(expires_at)),
  review_at timestamptz NOT NULL CHECK (isfinite(review_at) AND review_at<=expires_at)
);
-- Only the independent custodian can attest provider erasure, never the runtime.
CREATE TABLE proctoring_provider_cleanup (
  operation_id uuid PRIMARY KEY, receipt_reference text NOT NULL CHECK(length(trim(receipt_reference))>0),
  acknowledged_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- ---------------------------------------------------------------------------
-- H1: owner/app role separation.
-- ---------------------------------------------------------------------------
DO $migration$ DECLARE owner_missing boolean; app_missing boolean; BEGIN
  owner_missing := NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='quizopia_proctoring_owner');
  app_missing := NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='proctoring');
  IF (owner_missing OR app_missing) AND NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user) THEN
    RAISE EXCEPTION 'quizopia_proctoring_owner/proctoring roles missing: provision owner+runtime roles before migrating (see V5 header and service README)';
  END IF;
  IF owner_missing THEN
    EXECUTE 'CREATE ROLE quizopia_proctoring_owner NOLOGIN NOINHERIT';
  END IF;
  IF app_missing THEN
    EXECUTE format('CREATE ROLE proctoring LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT','proctoring_local_only');
  END IF;
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='quizopia_proctoring_custodian') THEN
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname=current_user) THEN
      RAISE EXCEPTION 'provision NOLOGIN quizopia_proctoring_custodian before migrating';
    END IF;
    CREATE ROLE quizopia_proctoring_custodian NOLOGIN;
  END IF;
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='quizopia_proctoring_owner' AND (rolsuper OR rolcanlogin))
    OR EXISTS(SELECT 1 FROM pg_roles WHERE rolname='proctoring' AND (rolsuper OR rolcreaterole OR rolcreatedb))
    OR pg_has_role('proctoring','quizopia_proctoring_owner','MEMBER')
    OR pg_has_role('proctoring','quizopia_proctoring_custodian','MEMBER')
    OR EXISTS(SELECT 1 FROM pg_database WHERE datname=current_database() AND datdba='proctoring'::regrole) THEN
    RAISE EXCEPTION 'unsafe proctoring runtime/owner/custodian role separation';
  END IF;
END $migration$;

DO $migration$ DECLARE object record; BEGIN
  FOR object IN SELECT c.oid::regclass AS obj FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname=current_schema() AND c.relkind IN ('r','S')
                   AND c.relname<>'flyway_schema_history'
                   AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid=c.oid AND d.deptype IN ('a','i')) LOOP
    EXECUTE format('ALTER TABLE %s OWNER TO quizopia_proctoring_owner',object.obj);
  END LOOP;
END $migration$;
DO $migration$ DECLARE routine text; BEGIN
  FOR routine IN SELECT p.oid::regprocedure::text FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                  WHERE n.nspname=current_schema() LOOP
    BEGIN
      EXECUTE format('ALTER FUNCTION %s OWNER TO quizopia_proctoring_owner',routine);
    EXCEPTION WHEN insufficient_privilege OR undefined_function THEN NULL;
    END;
  END LOOP;
END $migration$;

-- Runtime grants: DML without DELETE/TRUNCATE on any monitoring table.
-- deletion_purge_receipts / purge_progress are written only by the privileged
-- procedure; media erasure bookkeeping is also procedure-owned.
DO $migration$ BEGIN
  EXECUTE format('GRANT USAGE ON SCHEMA %I TO proctoring',current_schema());
END $migration$;
DO $migration$ DECLARE target record; BEGIN
  FOR target IN SELECT c.relname AS name, c.relkind AS kind FROM pg_class c
                 JOIN pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname=current_schema() AND c.relkind IN ('r','S')
                   AND c.relname<>'flyway_schema_history' LOOP
    IF target.kind='S' THEN
      EXECUTE format('GRANT USAGE,SELECT ON SEQUENCE %I TO proctoring',target.name);
    ELSE
      -- Direct runtime revocation does not remove grants inherited through PUBLIC
      -- (including ALTER DEFAULT PRIVILEGES on newly created custody tables).
      EXECUTE format('REVOKE ALL ON TABLE %I FROM PUBLIC',target.name);
      EXECUTE format('REVOKE ALL ON TABLE %I FROM proctoring',target.name);
      EXECUTE format('GRANT SELECT,INSERT,UPDATE ON TABLE %I TO proctoring',target.name);
       IF target.name IN ('deletion_purge_receipts','purge_progress','purge_inventory','media_erasure_requests',
           'proctoring_retention_holds','proctoring_provider_cleanup') THEN
         EXECUTE format('REVOKE INSERT,UPDATE ON TABLE %I FROM proctoring',target.name);
      END IF;
    END IF;
  END LOOP;
END $migration$;
REVOKE ALL ON purge_inventory,proctoring_retention_holds,proctoring_provider_cleanup FROM proctoring;
GRANT SELECT ON proctoring_provider_cleanup TO proctoring;
GRANT SELECT ON proctoring_retention_holds,proctoring_provider_cleanup TO quizopia_proctoring_custodian;
GRANT INSERT ON proctoring_provider_cleanup TO quizopia_proctoring_custodian;

-- Advisory locks serialize execution, not MVCC snapshots. PostgreSQL cannot lower
-- isolation once a caller has taken a snapshot: reject that transaction, never
-- pretend a lock refreshed a REPEATABLE READ / SERIALIZABLE view. Every relevant
-- function/trigger calls this itself, including direct SQL admission.
CREATE FUNCTION proctoring_require_read_committed() RETURNS void
 LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  IF current_setting('transaction_isolation')<>'read committed' THEN
    RAISE EXCEPTION 'proctoring requires READ COMMITTED' USING ERRCODE='25001';
  END IF;
 END $$;

CREATE FUNCTION proctoring_serialized_write_guard() RETURNS trigger
 LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  PERFORM proctoring_require_read_committed();
  PERFORM pg_advisory_xact_lock(18015);
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
 END $$;
DO $migration$ DECLARE target text; BEGIN
 FOREACH target IN ARRAY ARRAY['proctoring_retention_holds','proctoring_provider_cleanup',
   'purge_inventory','purge_progress','deletion_purge_receipts','media_erasure_requests',
   'quiz_revocation_fences','deletion_command_inbox'] LOOP
  EXECUTE format('CREATE TRIGGER proctoring_serialized_write_guard BEFORE INSERT OR UPDATE OR DELETE ON %I
    FOR EACH ROW EXECUTE FUNCTION proctoring_serialized_write_guard()',target);
 END LOOP;
END $migration$;

CREATE FUNCTION proctoring_set_hold(p_id uuid,p_quiz uuid,p_session uuid,p_authority text,
  p_reason text,p_reviewer text,p_expiry timestamptz,p_review timestamptz)
 RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  PERFORM proctoring_require_read_committed();
  PERFORM pg_advisory_xact_lock(18015);
  IF EXISTS(SELECT 1 FROM deletion_purge_receipts WHERE quiz_id=p_quiz) THEN
    RAISE EXCEPTION 'purge already crossed irreversible boundary';
  END IF;
  IF p_expiry<=clock_timestamp() OR p_review<=clock_timestamp() THEN
    RAISE EXCEPTION 'hold requires future expiry and review';
  END IF;
  INSERT INTO proctoring_retention_holds VALUES
    (p_id,p_quiz,p_session,p_authority,p_reason,p_reviewer,p_expiry,p_review);
 END $$;
REVOKE ALL ON FUNCTION proctoring_set_hold(uuid,uuid,uuid,text,text,text,timestamptz,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION proctoring_set_hold(uuid,uuid,uuid,text,text,text,timestamptz,timestamptz)
 TO quizopia_proctoring_custodian;

CREATE FUNCTION proctoring_attempt_fenced(p_attempt uuid) RETURNS boolean
 LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
 PERFORM proctoring_require_read_committed();
 RETURN EXISTS(SELECT 1 FROM purge_inventory WHERE p_attempt=ANY(attempts) OR p_attempt=ANY(resolved_attempts))
 OR EXISTS(SELECT 1 FROM monitoring_sessions s JOIN quiz_revocation_fences f ON f.quiz_id=s.quiz_id
   WHERE s.attempt_id=p_attempt)
 OR EXISTS(SELECT 1 FROM monitoring_sessions s JOIN purge_inventory i
   ON s.id=ANY(i.sessions) OR s.publication_id=ANY(i.publications)
   WHERE s.attempt_id=p_attempt);
 END
  $$;

CREATE FUNCTION proctoring_session_fenced(p_session uuid) RETURNS boolean
 LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
 PERFORM proctoring_require_read_committed();
 RETURN EXISTS(SELECT 1 FROM purge_inventory WHERE p_session=ANY(sessions))
 OR EXISTS(SELECT 1 FROM monitoring_sessions s WHERE s.id=p_session AND proctoring_attempt_fenced(s.attempt_id))
 OR EXISTS(SELECT 1 FROM proctoring_retention_holds h LEFT JOIN monitoring_sessions s ON s.id=p_session
   WHERE h.expires_at>clock_timestamp() AND (h.session_id=p_session OR h.quiz_id=s.quiz_id));
 END
  $$;

CREATE FUNCTION proctoring_retention_hold_active(p_quiz uuid) RETURNS boolean
 LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  PERFORM proctoring_require_read_committed();
  PERFORM pg_advisory_xact_lock(18015);
  RETURN EXISTS(SELECT 1 FROM proctoring_retention_holds h WHERE h.expires_at>clock_timestamp()
    AND (h.quiz_id=p_quiz
      OR EXISTS(SELECT 1 FROM monitoring_sessions s WHERE s.id=h.session_id AND s.quiz_id=p_quiz)
      OR EXISTS(SELECT 1 FROM purge_inventory i WHERE i.quiz_id=p_quiz AND h.session_id=ANY(i.sessions))));
 END $$;

CREATE FUNCTION proctoring_inbox_fence() RETURNS trigger LANGUAGE plpgsql
 SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  PERFORM proctoring_require_read_committed();
  PERFORM pg_advisory_xact_lock(18015);
  IF proctoring_attempt_fenced(NEW.attempt_id) OR EXISTS(
    SELECT 1 FROM purge_inventory WHERE (NEW.payload->>'publicationId')::uuid=ANY(publications)
      OR (NEW.payload->>'publicationId')::uuid=ANY(resolved_publications)) THEN
    RETURN NULL;
  END IF;
  RETURN NEW;
 END $$;
CREATE TRIGGER proctoring_inbox_fence BEFORE INSERT ON assessment_monitoring_inbox
 FOR EACH ROW EXECUTE FUNCTION proctoring_inbox_fence();

CREATE FUNCTION proctoring_write_fence() RETURNS trigger LANGUAGE plpgsql
 SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  PERFORM proctoring_require_read_committed();
  PERFORM pg_advisory_xact_lock(18015);
  IF TG_TABLE_NAME='monitoring_sessions' THEN
    IF proctoring_attempt_fenced(NEW.attempt_id) OR EXISTS(
      SELECT 1 FROM quiz_revocation_fences WHERE quiz_id=NEW.quiz_id) OR EXISTS(
      SELECT 1 FROM purge_inventory WHERE NEW.publication_id=ANY(publications)
        OR NEW.publication_id=ANY(resolved_publications)) THEN
      RAISE EXCEPTION 'monitoring removed' USING ERRCODE='23514';
    END IF;
  ELSIF proctoring_session_fenced(NEW.session_id) THEN
    RAISE EXCEPTION 'monitoring removed' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END $$;
CREATE TRIGGER proctoring_write_fence BEFORE INSERT ON monitoring_sessions
 FOR EACH ROW EXECUTE FUNCTION proctoring_write_fence();
CREATE TRIGGER proctoring_write_fence BEFORE INSERT ON monitoring_evidence
 FOR EACH ROW EXECUTE FUNCTION proctoring_write_fence();
CREATE TRIGGER proctoring_write_fence BEFORE INSERT ON monitoring_access_audit
 FOR EACH ROW EXECUTE FUNCTION proctoring_write_fence();
CREATE TRIGGER proctoring_write_fence BEFORE INSERT ON monitoring_media_rooms
 FOR EACH ROW EXECUTE FUNCTION proctoring_write_fence();
CREATE TRIGGER proctoring_write_fence BEFORE INSERT ON monitoring_media_grants
 FOR EACH ROW EXECUTE FUNCTION proctoring_write_fence();

CREATE OR REPLACE FUNCTION assessment_monitoring_inbox_guard() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  PERFORM proctoring_require_read_committed();
  IF TG_OP='DELETE' AND current_user='quizopia_proctoring_owner'
    AND EXISTS(SELECT 1 FROM purge_inventory WHERE operation_id::text=
      current_setting('quizopia.proctoring_purge_operation',true)
      AND (OLD.attempt_id=ANY(attempts) OR OLD.attempt_id=ANY(resolved_attempts)
        OR (OLD.payload->>'publicationId')::uuid=ANY(publications)
        OR (OLD.payload->>'publicationId')::uuid=ANY(resolved_publications)
        OR EXISTS(SELECT 1 FROM monitoring_sessions WHERE id=ANY(sessions) AND attempt_id=OLD.attempt_id)))
    THEN RETURN OLD;
  END IF;
  IF TG_OP='DELETE' OR OLD.state<>'PENDING' OR NEW.event_id<>OLD.event_id OR NEW.attempt_id<>OLD.attempt_id
   OR NEW.payload<>OLD.payload OR NEW.payload_sha256<>OLD.payload_sha256 OR NEW.received_at<>OLD.received_at THEN
   RAISE EXCEPTION 'authoritative inbox identity is immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END $$;

-- ---------------------------------------------------------------------------
-- H5: purge-trigger guards accept DELETE only for rows whose session belongs to the
-- marked quiz OR is part of the procedure-captured session scope (legacy rows with
-- quiz_id NULL resolved through the Assessment-provided dependents). The session
-- scope marker is transaction-local and, with direct DELETE revoked from every
-- runtime principal, is never itself a privilege: it only informs row binding.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION proctoring_purge_covers_session(p_session uuid) RETURNS boolean
 LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
 PERFORM proctoring_require_read_committed();
 RETURN EXISTS(SELECT 1 FROM unnest(string_to_array(
   coalesce(current_setting('quizopia.proctoring_purge_sessions',true),''),',')) marked
   WHERE marked::uuid=p_session);
 END
  $$;

CREATE OR REPLACE FUNCTION proctoring_purge_authorized(p_quiz uuid) RETURNS boolean
 LANGUAGE plpgsql VOLATILE SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  PERFORM proctoring_require_read_committed();
  RETURN current_setting('quizopia.proctoring_purge_quiz',true) IS NOT NULL
   AND current_setting('quizopia.proctoring_purge_quiz',true)::uuid IS NOT DISTINCT FROM p_quiz
   AND EXISTS(SELECT 1 FROM quiz_revocation_fences WHERE quiz_id=p_quiz AND ack_at IS NOT NULL);
 END $$;

CREATE OR REPLACE FUNCTION monitoring_session_guard() RETURNS trigger LANGUAGE plpgsql AS $$
  BEGIN
      PERFORM proctoring_require_read_committed();
     IF TG_OP='DELETE' THEN
         IF proctoring_purge_authorized(OLD.quiz_id) OR proctoring_purge_covers_session(OLD.id) THEN
             RETURN OLD;
         END IF;
         RAISE EXCEPTION 'monitoring session is immutable' USING ERRCODE='23514';
     END IF;
     IF NEW.id<>OLD.id OR NEW.attempt_id<>OLD.attempt_id OR NEW.publication_id<>OLD.publication_id
         OR NEW.classroom_id<>OLD.classroom_id OR NEW.student_id<>OLD.student_id OR NEW.deadline_at<>OLD.deadline_at
         OR NEW.created_at<>OLD.created_at OR NEW.quiz_id IS DISTINCT FROM OLD.quiz_id OR OLD.status='ENDED' THEN
         RAISE EXCEPTION 'monitoring session is immutable' USING ERRCODE='23514';
     END IF;
     RETURN NEW;
 END $$;

CREATE OR REPLACE FUNCTION monitoring_append_guard() RETURNS trigger LANGUAGE plpgsql AS $$
 DECLARE quiz uuid;
  BEGIN
      PERFORM proctoring_require_read_committed();
      IF TG_OP='DELETE' THEN PERFORM pg_advisory_xact_lock(18015); END IF;
      IF TG_OP='DELETE' AND EXISTS(SELECT 1 FROM public.proctoring_retention_holds h
        JOIN public.monitoring_sessions s ON s.id=OLD.session_id
        WHERE (h.session_id=s.id OR h.quiz_id=s.quiz_id OR EXISTS(
          SELECT 1 FROM public.purge_inventory i WHERE i.quiz_id=h.quiz_id AND s.id=ANY(i.sessions)))
          AND h.expires_at>clock_timestamp()) THEN
        RAISE EXCEPTION 'record held by custodian';
      END IF;
      -- Only the owning retention function may remove already-expired records.
     IF TG_OP='DELETE' AND OLD.expires_at IS NOT NULL AND OLD.expires_at<=clock_timestamp()
         AND current_user=(SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid=TG_RELID) THEN
         RETURN OLD;
     END IF;
     IF TG_OP='DELETE' THEN
         SELECT quiz_id INTO quiz FROM public.monitoring_sessions WHERE id=OLD.session_id;
         IF proctoring_purge_authorized(quiz) OR proctoring_purge_covers_session(OLD.session_id) THEN
             RETURN OLD;
         END IF;
     END IF;
     RAISE EXCEPTION 'monitoring history is append only' USING ERRCODE='23514';
 END $$;

CREATE OR REPLACE FUNCTION monitoring_media_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
 DECLARE quiz uuid;
  BEGIN
      PERFORM proctoring_require_read_committed();
     IF TG_OP='DELETE' THEN
         SELECT quiz_id INTO quiz FROM public.monitoring_sessions WHERE id=OLD.session_id;
         IF proctoring_purge_authorized(quiz) OR proctoring_purge_covers_session(OLD.session_id) THEN
             RETURN OLD;
         END IF;
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

CREATE OR REPLACE FUNCTION monitoring_purge_expired(batch_limit integer) RETURNS integer
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
 DECLARE removed integer; audit_removed integer;
 BEGIN
  PERFORM proctoring_require_read_committed();
  PERFORM pg_advisory_xact_lock(18015);
  IF batch_limit IS NULL OR batch_limit<1 OR batch_limit>1000 THEN RAISE EXCEPTION 'invalid retention batch'; END IF;
  DELETE FROM monitoring_evidence WHERE ordinal IN (SELECT ordinal FROM monitoring_evidence
    WHERE expires_at<=clock_timestamp() ORDER BY ordinal LIMIT batch_limit FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS removed=ROW_COUNT;
  DELETE FROM monitoring_access_audit WHERE id IN (SELECT id FROM monitoring_access_audit
    WHERE expires_at<=clock_timestamp() ORDER BY id LIMIT batch_limit FOR UPDATE SKIP LOCKED);
  GET DIAGNOSTICS audit_removed=ROW_COUNT;
  RETURN removed+audit_removed;
 END $$;

CREATE FUNCTION proctoring_purge_absent(p_quiz uuid,p_operation uuid) RETURNS boolean
 LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
 DECLARE inventory purge_inventory%ROWTYPE;
 BEGIN
  PERFORM proctoring_require_read_committed();
  PERFORM pg_advisory_xact_lock(18015);
  SELECT * INTO inventory FROM purge_inventory WHERE operation_id=p_operation AND quiz_id=p_quiz;
  IF NOT FOUND OR NOT inventory.captured THEN RETURN false; END IF;
  RETURN NOT (
    EXISTS(SELECT 1 FROM monitoring_sessions WHERE quiz_id=p_quiz OR id=ANY(inventory.sessions)
      OR (quiz_id IS NULL AND (attempt_id=ANY(inventory.attempts) OR publication_id=ANY(inventory.publications))))
    OR EXISTS(SELECT 1 FROM assessment_monitoring_inbox WHERE attempt_id=ANY(inventory.attempts)
      OR attempt_id=ANY(inventory.resolved_attempts) OR (payload->>'publicationId')::uuid=ANY(inventory.publications)
      OR (payload->>'publicationId')::uuid=ANY(inventory.resolved_publications))
    OR EXISTS(SELECT 1 FROM monitoring_evidence WHERE session_id=ANY(inventory.sessions))
    OR EXISTS(SELECT 1 FROM monitoring_access_audit WHERE session_id=ANY(inventory.sessions))
    OR EXISTS(SELECT 1 FROM monitoring_media_rooms WHERE session_id=ANY(inventory.sessions))
    OR EXISTS(SELECT 1 FROM monitoring_media_grants WHERE session_id=ANY(inventory.sessions))
    OR EXISTS(SELECT 1 FROM deletion_command_inbox WHERE quiz_id=p_quiz));
 END $$;

-- ---------------------------------------------------------------------------
-- H1+H3+H5: bounded, idempotent, FK-ordered quiz-scoped purge with legacy dependent
-- resolution. p_publications/p_attempts are the Assessment-owned dependents
-- inventory for this quiz (arrays may be empty; NULL means the mapping is
-- unavailable). Legacy rows (quiz_id NULL) are purged only when their attempt_id or
-- publication_id is covered by that inventory. When the mapping is unavailable and
-- ANY legacy row exists, the purge reports RECONCILIATION_BLOCKED instead of
-- guessing: the coordinator surfaces WAITING_DEPENDENCY/legacy_dependents_unresolved
-- and PURGED is never claimed while an unresolved dependent may remain. Every child
-- DELETE binds rows to the captured session scope - never the marker alone.
-- ---------------------------------------------------------------------------
DROP FUNCTION IF EXISTS monitoring_purge_quiz(uuid,uuid,integer);
CREATE FUNCTION monitoring_purge_quiz(p_quiz uuid, p_operation uuid,
  p_publications uuid[], p_attempts uuid[], p_budget integer)
  RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
 DECLARE
   removed bigint; batch bigint; round_removed bigint; step_name varchar; transaction_removed bigint:=0;
   step_index integer; steps_total integer; drained boolean; session_ids uuid[];
    legacy_exists boolean; mapping_provided boolean; inventory purge_inventory%ROWTYPE;
    resolved_attempt_ids uuid[]; resolved_publication_ids uuid[]; new_sessions uuid[];
    steps text[] := ARRAY['assessment_monitoring_inbox','monitoring_media_grants','monitoring_media_rooms',
     'monitoring_access_audit','monitoring_evidence','monitoring_sessions'];
  BEGIN
       PERFORM proctoring_require_read_committed();
       IF p_budget IS NULL OR p_budget<1 OR p_budget>50000 THEN
         RAISE EXCEPTION 'invalid purge budget bound';
      END IF;
      PERFORM pg_advisory_xact_lock(18015);
      IF p_publications IS NOT NULL THEN
        SELECT ARRAY(SELECT DISTINCT x FROM unnest(p_publications) x ORDER BY x) INTO p_publications;
      END IF;
      IF p_attempts IS NOT NULL THEN
        SELECT ARRAY(SELECT DISTINCT x FROM unnest(p_attempts) x ORDER BY x) INTO p_attempts;
      END IF;
     IF NOT EXISTS(SELECT 1 FROM quiz_revocation_fences
         WHERE quiz_id=p_quiz AND operation_id=p_operation AND ack_at IS NOT NULL) THEN
         RAISE EXCEPTION 'quiz purge requires an acknowledged fence';
     END IF;
     -- Own command bookkeeping only (UNIQUE(operation_id) bounds it to one row):
     -- removed ahead of the budgeted work so a tiny budget is never consumed by the
     -- caller's own inbox row.
      mapping_provided := p_publications IS NOT NULL AND p_attempts IS NOT NULL;
      SELECT * INTO inventory FROM purge_inventory WHERE operation_id=p_operation;
      IF FOUND THEN
        IF inventory.quiz_id<>p_quiz OR inventory.publications IS DISTINCT FROM p_publications
          OR inventory.attempts IS DISTINCT FROM p_attempts THEN
          RETURN 'INVENTORY_MISMATCH';
        END IF;
      END IF;
     SELECT EXISTS(SELECT 1 FROM monitoring_sessions WHERE quiz_id IS NULL) INTO legacy_exists;
      IF NOT mapping_provided AND legacy_exists THEN
         -- Legacy dependents exist but cannot be attributed to any quiz: refuse
         -- truthfully instead of risking an incomplete purge.
         RETURN 'RECONCILIATION_BLOCKED';
      END IF;
      IF inventory.operation_id IS NULL THEN
        IF coalesce(cardinality(p_publications),0)>2000 OR coalesce(cardinality(p_attempts),0)>2000 THEN
          RAISE EXCEPTION 'inventory input bound exceeded';
        END IF;
        INSERT INTO purge_inventory(operation_id,quiz_id,publications,attempts,sessions,resolved_attempts,resolved_publications)
          VALUES(p_operation,p_quiz,p_publications,p_attempts,'{}','{}','{}');
        SELECT * INTO inventory FROM purge_inventory WHERE operation_id=p_operation;
      END IF;
      session_ids:=inventory.sessions;
      resolved_attempt_ids:=inventory.resolved_attempts;
      resolved_publication_ids:=inventory.resolved_publications;
      IF NOT inventory.captured THEN
        SELECT coalesce(array_agg(id),'{}') INTO new_sessions FROM (
          SELECT id FROM monitoring_sessions WHERE NOT(id=ANY(session_ids)) AND
            (quiz_id=p_quiz OR (quiz_id IS NULL AND mapping_provided
              AND (attempt_id=ANY(p_attempts) OR publication_id=ANY(p_publications))))
          ORDER BY id LIMIT p_budget) candidates;
        SELECT resolved_attempt_ids||coalesce(array_agg(DISTINCT attempt_id),'{}'),
          resolved_publication_ids||coalesce(array_agg(DISTINCT publication_id),'{}')
          INTO resolved_attempt_ids,resolved_publication_ids FROM monitoring_sessions WHERE id=ANY(new_sessions);
        session_ids:=session_ids||new_sessions;
        UPDATE purge_inventory SET sessions=session_ids,resolved_attempts=resolved_attempt_ids,
          resolved_publications=resolved_publication_ids,captured=(cardinality(new_sessions)<p_budget)
          WHERE operation_id=p_operation;
        p_budget:=p_budget-cardinality(new_sessions);
        IF p_budget=0 THEN RETURN 'IN_PROGRESS'; END IF;
      END IF;
       IF proctoring_retention_hold_active(p_quiz) THEN RETURN 'BLOCKED_BY_RETENTION'; END IF;
      -- Retain the original durable room/participant identifiers until actual cleanup
      -- acknowledgment. An unavailable provider cannot cause locator loss.
       IF (EXISTS(SELECT 1 FROM monitoring_media_rooms WHERE session_id=ANY(session_ids))
         OR EXISTS(SELECT 1 FROM monitoring_media_grants WHERE session_id=ANY(session_ids))) THEN
         INSERT INTO media_erasure_requests VALUES(p_operation,p_quiz,'livekit','PENDING_PROVIDER',clock_timestamp())
           ON CONFLICT DO NOTHING;
         IF NOT EXISTS(SELECT 1 FROM proctoring_provider_cleanup WHERE operation_id=p_operation) THEN
           RETURN 'PENDING_PROVIDER';
         END IF;
      END IF;
       DELETE FROM deletion_command_inbox WHERE operation_id=p_operation AND quiz_id=p_quiz;
        IF EXISTS(SELECT 1 FROM deletion_purge_receipts WHERE operation_id=p_operation AND step='verified_zero')
          THEN
            IF proctoring_purge_absent(p_quiz,p_operation) THEN RETURN 'PURGED'; END IF;
            DELETE FROM deletion_purge_receipts WHERE operation_id=p_operation AND step='verified_zero';
            RETURN 'RECONCILIATION_BLOCKED';
          END IF;
     SELECT next_step INTO step_index FROM purge_progress WHERE operation_id=p_operation AND quiz_id=p_quiz;
     IF step_index IS NULL THEN step_index:=1; END IF;
     steps_total:=array_length(steps,1);
      PERFORM set_config('quizopia.proctoring_purge_quiz',p_quiz::text,true);
      PERFORM set_config('quizopia.proctoring_purge_operation',p_operation::text,true);
     PERFORM set_config('quizopia.proctoring_purge_sessions',array_to_string(session_ids,','),true);
     WHILE step_index<=steps_total LOOP
         step_name:=steps[step_index];
         round_removed:=0;
         drained:=false;
         LOOP
             EXIT WHEN p_budget<=0;
             batch:=least(p_budget,1000);
              CASE step_name
              WHEN 'assessment_monitoring_inbox' THEN
                  DELETE FROM assessment_monitoring_inbox WHERE event_id IN (
                    SELECT event_id FROM assessment_monitoring_inbox WHERE attempt_id=ANY(p_attempts)
                     OR attempt_id=ANY(resolved_attempt_ids)
                     OR (payload->>'publicationId')::uuid=ANY(p_publications)
                     OR (payload->>'publicationId')::uuid=ANY(resolved_publication_ids)
                     OR attempt_id IN (SELECT attempt_id FROM monitoring_sessions WHERE id=ANY(session_ids))
                    ORDER BY event_id LIMIT batch);
             WHEN 'monitoring_media_grants' THEN
                 DELETE FROM monitoring_media_grants WHERE id IN (
                     SELECT g.id FROM monitoring_media_grants g
                      WHERE g.session_id=ANY(session_ids) ORDER BY g.id LIMIT batch);
             WHEN 'monitoring_media_rooms' THEN
                 DELETE FROM monitoring_media_rooms WHERE (session_id,room_name) IN (
                     SELECT r.session_id,r.room_name FROM monitoring_media_rooms r
                      WHERE r.session_id=ANY(session_ids) ORDER BY r.session_id,r.room_name LIMIT batch);
             WHEN 'monitoring_access_audit' THEN
                 DELETE FROM monitoring_access_audit WHERE id IN (
                     SELECT a.id FROM monitoring_access_audit a
                      WHERE a.session_id=ANY(session_ids) ORDER BY a.id LIMIT batch);
             WHEN 'monitoring_evidence' THEN
                 DELETE FROM monitoring_evidence WHERE ordinal IN (
                     SELECT e.ordinal FROM monitoring_evidence e
                      WHERE e.session_id=ANY(session_ids) ORDER BY e.ordinal LIMIT batch);
             WHEN 'monitoring_sessions' THEN
                 DELETE FROM monitoring_sessions WHERE id IN (
                     SELECT s.id FROM monitoring_sessions s
                      WHERE s.id=ANY(session_ids) ORDER BY s.id LIMIT batch);
             ELSE
                 RAISE EXCEPTION 'purge step audit drift';
             END CASE;
             GET DIAGNOSTICS removed=ROW_COUNT;
              round_removed:=round_removed+removed;
              transaction_removed:=transaction_removed+removed;
             p_budget:=p_budget-removed;
             IF removed<batch THEN drained:=true; EXIT; END IF;
         END LOOP;
         INSERT INTO deletion_purge_receipts(operation_id,quiz_id,step,rows_removed,completed_at)
         VALUES(p_operation,p_quiz,step_name,round_removed,clock_timestamp())
         ON CONFLICT (operation_id,step) DO UPDATE
          SET rows_removed=deletion_purge_receipts.rows_removed+EXCLUDED.rows_removed,
              completed_at=EXCLUDED.completed_at;
         -- Budget exhausted mid-step: persist where the next round resumes. No PURGED
         -- receipt exists while any dependent row may remain.
         IF NOT drained THEN
             INSERT INTO purge_progress(operation_id,quiz_id,next_step,total_removed,rounds,updated_at)
             VALUES(p_operation,p_quiz,step_index,transaction_removed,1,clock_timestamp())
             ON CONFLICT (operation_id) DO UPDATE
              SET next_step=EXCLUDED.next_step,
                  total_removed=purge_progress.total_removed+EXCLUDED.total_removed,
                  rounds=purge_progress.rounds+1,
                  updated_at=EXCLUDED.updated_at;
             RETURN 'IN_PROGRESS';
         END IF;
         step_index:=step_index+1;
     END LOOP;
     -- Final-round verification, re-derived from live rows inside this transaction.
      IF EXISTS(SELECT 1 FROM assessment_monitoring_inbox WHERE attempt_id=ANY(p_attempts)
            OR attempt_id=ANY(resolved_attempt_ids)
            OR (payload->>'publicationId')::uuid=ANY(p_publications)
            OR (payload->>'publicationId')::uuid=ANY(resolved_publication_ids))
          OR EXISTS(SELECT 1 FROM monitoring_sessions WHERE quiz_id=p_quiz OR id=ANY(session_ids))
         OR (mapping_provided AND EXISTS(SELECT 1 FROM monitoring_sessions
              WHERE quiz_id IS NULL
                AND (attempt_id=ANY(p_attempts) OR publication_id=ANY(p_publications))))
         OR EXISTS(SELECT 1 FROM monitoring_evidence e WHERE e.session_id IN
             (SELECT id FROM monitoring_sessions WHERE quiz_id=p_quiz
                OR (quiz_id IS NULL AND mapping_provided
                    AND (attempt_id=ANY(p_attempts) OR publication_id=ANY(p_publications)))))
         OR EXISTS(SELECT 1 FROM monitoring_access_audit a WHERE a.session_id IN
             (SELECT id FROM monitoring_sessions WHERE quiz_id=p_quiz
                OR (quiz_id IS NULL AND mapping_provided
                    AND (attempt_id=ANY(p_attempts) OR publication_id=ANY(p_publications)))))
         OR EXISTS(SELECT 1 FROM monitoring_media_rooms r WHERE r.session_id IN
             (SELECT id FROM monitoring_sessions WHERE quiz_id=p_quiz
                OR (quiz_id IS NULL AND mapping_provided
                    AND (attempt_id=ANY(p_attempts) OR publication_id=ANY(p_publications)))))
         OR EXISTS(SELECT 1 FROM monitoring_media_grants g WHERE g.session_id IN
             (SELECT id FROM monitoring_sessions WHERE quiz_id=p_quiz
                OR (quiz_id IS NULL AND mapping_provided
                    AND (attempt_id=ANY(p_attempts) OR publication_id=ANY(p_publications)))))
         OR EXISTS(SELECT 1 FROM deletion_command_inbox WHERE quiz_id=p_quiz) THEN
         RAISE EXCEPTION 'quiz purge left dependent rows behind';
     END IF;
     INSERT INTO deletion_purge_receipts(operation_id,quiz_id,step,rows_removed,completed_at)
     VALUES(p_operation,p_quiz,'verified_zero',0,clock_timestamp())
     ON CONFLICT (operation_id,step) DO UPDATE SET completed_at=EXCLUDED.completed_at;
     DELETE FROM purge_progress WHERE operation_id=p_operation AND quiz_id=p_quiz;
     RETURN 'PURGED';
 END $$;
REVOKE ALL ON FUNCTION monitoring_purge_quiz(uuid,uuid,uuid[],uuid[],integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION monitoring_purge_quiz(uuid,uuid,uuid[],uuid[],integer) TO proctoring;
REVOKE ALL ON FUNCTION monitoring_purge_expired(integer) FROM PUBLIC,proctoring;
REVOKE ALL ON FUNCTION proctoring_set_hold(uuid,uuid,uuid,text,text,text,timestamptz,timestamptz) FROM proctoring;

-- Must run AFTER creation: otherwise new definers remain migration/superuser-owned.
DO $migration$ DECLARE routine record; BEGIN
 FOR routine IN SELECT p.oid::regprocedure AS signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname=current_schema() LOOP
  EXECUTE format('ALTER FUNCTION %s OWNER TO quizopia_proctoring_owner',routine.signature);
  EXECUTE format('ALTER FUNCTION %s SET search_path=pg_catalog,public,pg_temp',routine.signature);
 END LOOP;
END $migration$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC,proctoring;
-- REVOKE from the named runtime is insufficient: PUBLIC defaults, inherited
-- grants, column grants, and SET ROLE membership can all confer effective rights.
-- Do not rewrite unrelated group ACLs: unsafe provisioning fails the migration.
DO $migration$ DECLARE principal record; target record; routine regprocedure; BEGIN
 FOR principal IN SELECT oid,rolname FROM pg_roles
   WHERE rolname='proctoring' OR pg_has_role('proctoring',oid,'MEMBER') LOOP
  FOR target IN SELECT c.oid,c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname=current_schema() AND c.relkind='r' LOOP
   IF has_table_privilege(principal.oid,target.oid,'DELETE,TRUNCATE,TRIGGER') THEN
    RAISE EXCEPTION 'runtime role % has effective destructive privileges on %',principal.rolname,target.relname;
   END IF;
   IF target.relname IN ('proctoring_retention_holds','proctoring_provider_cleanup','purge_inventory',
      'purge_progress','deletion_purge_receipts','media_erasure_requests')
      AND (has_table_privilege(principal.oid,target.oid,'INSERT,UPDATE,REFERENCES')
        OR has_any_column_privilege(principal.oid,target.oid,'INSERT,UPDATE,REFERENCES')) THEN
    RAISE EXCEPTION 'runtime role % has effective protected writes on %',principal.rolname,target.relname;
   END IF;
  END LOOP;
  FOREACH routine IN ARRAY ARRAY['monitoring_purge_expired(integer)'::regprocedure,
    'proctoring_set_hold(uuid,uuid,uuid,text,text,text,timestamptz,timestamptz)'::regprocedure] LOOP
   IF has_function_privilege(principal.oid,routine,'EXECUTE') THEN
    RAISE EXCEPTION 'runtime role % can execute operator-only function %',principal.rolname,routine;
   END IF;
  END LOOP;
 END LOOP;
END $migration$;
