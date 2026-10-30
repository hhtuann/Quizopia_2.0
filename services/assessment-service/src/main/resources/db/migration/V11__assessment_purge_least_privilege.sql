-- ADR-018 guarded implementation; flags remain OFF. Run with writers drained and a
-- separate migration principal, member of quizopia_assessment_owner. Runtime must
-- be assessment, never the migrator/owner.
--
-- PROVISIONING CONTRACT (security-critical): deployments MUST pre-provision
-- quizopia_assessment_owner and quizopia_assessment_custodian (NOLOGIN group
-- roles) and the runtime LOGIN role 'assessment' with a deployment-managed secret
-- (see the service README and infrastructure/postgres/01-init-databases.sh). This
-- migration NEVER creates, alters or passwords any role - not even under a
-- superuser Flyway connection. Missing roles, or roles with unsafe attributes or
-- memberships, fail the migration closed. Only disposable tests provision these
-- roles, through test-only container init scripts that deployment paths cannot
-- reach (src/test/resources/fb015_assessment_roles.sql).
DO $$ BEGIN
  IF (SELECT count(*) FROM pg_roles WHERE rolname IN ('quizopia_assessment_owner','quizopia_assessment_custodian','assessment'))<>3 THEN
    RAISE EXCEPTION 'provision owner, runtime and approved custodian roles before migration: this migration never creates credentials';
  END IF;
  IF EXISTS(SELECT FROM pg_roles WHERE rolname IN ('quizopia_assessment_owner','quizopia_assessment_custodian')
    AND (rolcanlogin OR rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls)) THEN
    RAISE EXCEPTION 'owner and custodian must be unprivileged NOLOGIN group roles';
  END IF;
  IF EXISTS(SELECT FROM pg_roles WHERE rolname='assessment' AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls))
    OR pg_has_role('assessment',(SELECT datdba FROM pg_database WHERE datname=current_database()),'USAGE')
    OR pg_has_role('assessment',(SELECT nspowner FROM pg_namespace WHERE nspname=current_schema()),'USAGE')
    OR pg_has_role('assessment','quizopia_assessment_owner','MEMBER')
    OR pg_has_role('assessment','quizopia_assessment_custodian','MEMBER') THEN
    RAISE EXCEPTION 'unsafe Assessment runtime principal';
  END IF;
END $$;

CREATE UNIQUE INDEX quiz_revocation_operation_unique ON quiz_revocation_fences(operation_id);
CREATE FUNCTION assessment_fence_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF ROW(NEW.quiz_id,NEW.operation_id,NEW.revoked_at,NEW.source)
   IS DISTINCT FROM ROW(OLD.quiz_id,OLD.operation_id,OLD.revoked_at,OLD.source)
   OR (OLD.ack_at IS NOT NULL AND NEW.ack_at IS DISTINCT FROM OLD.ack_at) THEN
   RAISE EXCEPTION 'revocation evidence is immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fence_identity BEFORE UPDATE ON quiz_revocation_fences FOR EACH ROW EXECUTE FUNCTION assessment_fence_identity();

-- Audited reverse cycle edges only. NO ACTION defers the identical referential
-- predicate to COMMIT; no FK is removed operationally. Other RESTRICT edges remain.
DO $$ DECLARE edge record; names text[]; name text; definition text; BEGIN
  FOR edge IN SELECT * FROM (VALUES
    ('attempts','submitted_answers',ARRAY['submitted_answers_id','id']),
    ('attempts','grading_results',ARRAY['result_id','id']),
    ('attempt_finalization_audit','grading_results',ARRAY['result_id','attempt_id']),
    ('attempt_finalization_audit','closure_request_members',ARRAY['closure_request_id','attempt_id']),
    ('closure_request_members','assessment_notifications',ARRAY['notification_id','closure_request_id','attempt_id','student_id'])
  ) AS e(tbl,target,cols) LOOP
    SELECT array_agg(c.conname::text) INTO names FROM pg_constraint c
    WHERE c.contype='f' AND c.conrelid=to_regclass(format('%I.%I',current_schema(),edge.tbl))
      AND c.confrelid=to_regclass(format('%I.%I',current_schema(),edge.target))
      AND (SELECT array_agg(a.attname::text ORDER BY u.n) FROM unnest(c.conkey) WITH ORDINALITY u(num,n)
        JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=u.num)=edge.cols;
    IF cardinality(names) IS DISTINCT FROM 1 THEN RAISE EXCEPTION 'cycle FK audit drift: %',edge.tbl; END IF;
    name:=names[1];
    SELECT pg_get_constraintdef(c.oid) INTO definition FROM pg_constraint c
      WHERE c.conrelid=to_regclass(format('%I.%I',current_schema(),edge.tbl)) AND c.conname=name;
    IF definition NOT LIKE '%DEFERRABLE INITIALLY DEFERRED%' THEN RAISE EXCEPTION 'cycle FK deferral audit drift'; END IF;
    -- Reuse the catalog definition, changing ONLY RESTRICT actions. Validate now.
    EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I',edge.tbl,name);
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I %s',edge.tbl,name,
      replace(definition,'RESTRICT','NO ACTION'));
  END LOOP;
END $$;

CREATE TABLE purge_progress (
  operation_id uuid PRIMARY KEY, quiz_id uuid NOT NULL UNIQUE,
  next_step integer NOT NULL, total_removed bigint NOT NULL DEFAULT 0,
  rounds integer NOT NULL DEFAULT 1, updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE purged_publication_markers(publication_id uuid PRIMARY KEY, operation_id uuid NOT NULL);
CREATE INDEX publications_quiz_purge_scope ON publications(quiz_id,id);
CREATE INDEX purged_publication_operation ON purged_publication_markers(operation_id,publication_id);
CREATE TABLE purged_attempt_markers(attempt_id uuid PRIMARY KEY, operation_id uuid NOT NULL);
CREATE INDEX purged_attempt_operation ON purged_attempt_markers(operation_id,attempt_id);
-- The admission row survives completion. A hold may win before irreversible work;
-- once work begins, a new hold is rejected explicitly, never silently accepted for
-- already destroyed content. Trigger-enforced even for direct custodian INSERT.
CREATE TABLE purge_admission(quiz_id uuid PRIMARY KEY, begun boolean NOT NULL DEFAULT false);
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_fence_admission() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
BEGIN
 IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'fence admission requires READ COMMITTED'; END IF;
 INSERT INTO purge_admission(quiz_id) VALUES(NEW.quiz_id) ON CONFLICT DO NOTHING;
 PERFORM 1 FROM purge_admission WHERE quiz_id=NEW.quiz_id FOR UPDATE;
 RETURN NEW;
END $body$ $ddl$,current_schema()); END $migration$;
CREATE TRIGGER fence_admission BEFORE INSERT ON quiz_revocation_fences FOR EACH ROW EXECUTE FUNCTION assessment_fence_admission();
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_hold_admission() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE started boolean; q uuid; BEGIN
  IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'hold admission requires READ COMMITTED'; END IF;
  IF TG_OP='UPDATE' AND NEW.quiz_id<>OLD.quiz_id THEN RAISE EXCEPTION 'hold scope is immutable'; END IF;
  q:=CASE WHEN TG_OP='DELETE' THEN OLD.quiz_id ELSE NEW.quiz_id END;
  INSERT INTO purge_admission(quiz_id) VALUES(q) ON CONFLICT DO NOTHING;
  SELECT begun INTO started FROM purge_admission WHERE quiz_id=q FOR UPDATE;
  IF TG_OP<>'DELETE' AND started THEN RAISE EXCEPTION 'purge already admitted: preservation cannot be promised'; END IF;
  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $body$ $ddl$,current_schema()); END $migration$;
CREATE TRIGGER hold_admission BEFORE INSERT OR UPDATE OR DELETE ON retention_holds
 FOR EACH ROW EXECUTE FUNCTION assessment_hold_admission();

-- Every scope-bearing writer shares the admission lock. This is enforced at the
-- database, including direct SQL and forged session settings. No UPDATE bypass.
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_purge_write_fence() RETURNS trigger
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE j jsonb:=to_jsonb(NEW); q uuid; pid uuid; aid uuid; BEGIN
 IF TG_TABLE_NAME='publications' THEN
   q:=(j->>'quiz_id')::uuid;
   IF TG_OP='UPDATE' AND EXISTS(SELECT FROM quiz_revocation_fences WHERE quiz_id=OLD.quiz_id) THEN
     RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='ASSESSMENT_REMOVED';
   END IF;
 ELSE
   pid:=(j->>'publication_id')::uuid; aid:=(j->>'attempt_id')::uuid;
   IF TG_TABLE_NAME='realtime_invalidation_outbox' THEN
     IF j->>'resource_kind'='PUBLICATION' THEN pid:=(j->>'resource_id')::uuid;
     ELSE aid:=(j->>'resource_id')::uuid; END IF;
   ELSIF TG_TABLE_NAME='grading_question_results' THEN
     SELECT attempt_id INTO aid FROM grading_results WHERE id=(j->>'result_id')::uuid;
   END IF;
   IF pid IS NULL THEN SELECT publication_id INTO pid FROM attempts WHERE id=aid; END IF;
   SELECT quiz_id INTO q FROM publications WHERE id=pid;
   IF q IS NULL THEN
     SELECT f.quiz_id INTO q FROM quiz_revocation_fences f WHERE f.operation_id IN (
       SELECT operation_id FROM purged_publication_markers WHERE publication_id=pid
       UNION ALL SELECT operation_id FROM purged_attempt_markers WHERE attempt_id=aid);
   END IF;
 END IF;
 IF q IS NOT NULL THEN
   INSERT INTO purge_admission(quiz_id) VALUES(q) ON CONFLICT DO NOTHING;
   PERFORM 1 FROM purge_admission WHERE quiz_id=q FOR SHARE;
   IF EXISTS(SELECT FROM quiz_revocation_fences WHERE quiz_id=q) THEN
     RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='ASSESSMENT_REMOVED';
   END IF;
 END IF;
 RETURN NEW;
END $body$ $ddl$,current_schema()); END $migration$;
DO $$ DECLARE tbl text; BEGIN
 FOREACH tbl IN ARRAY ARRAY['publications','publication_snapshots','publication_creations','attempts',
  'attempt_answers','submitted_answers','grading_results','grading_question_results','attempt_finalization_audit',
  'attempt_operations','attempt_creation_operations','closure_requests','closure_request_members',
  'assessment_notifications','closure_work_audit','publication_operations','assessment_monitoring_outbox',
  'realtime_invalidation_outbox'] LOOP
  EXECUTE format('CREATE TRIGGER a_purge_write_fence BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION assessment_purge_write_fence()',tbl);
 END LOOP;
END $$;

-- A forgeable session setting is only context, never authority (including UPDATE).
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE OR REPLACE FUNCTION %1$I.assessment_purge_authorized() RETURNS boolean
 LANGUAGE sql STABLE SET search_path=pg_catalog,%1$I,pg_temp AS $body$
 SELECT current_user='quizopia_assessment_owner'
  AND EXISTS(SELECT FROM %1$I.quiz_revocation_fences
    WHERE quiz_id=nullif(current_setting('quizopia.assessment_purge_quiz',true),'')::uuid AND ack_at IS NOT NULL)
$body$ $ddl$,current_schema()); END $migration$;

DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_delete_draft(p_publication uuid) RETURNS integer
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE n integer; BEGIN
 DELETE FROM publications WHERE id=p_publication AND state='DRAFT';
 GET DIAGNOSTICS n=ROW_COUNT; RETURN n;
END $body$ $ddl$,current_schema()); END $migration$;

-- Each budget unit performs at most THREE content deletes (the attempt/result/
-- submission cycle), or TWO for member/notice and publication/snapshot cycles.
-- Inventory INSERTs also consume units. No unbounded inventory arrays, no runtime
-- DDL, no cascades. At most budget inventory writes + 3*budget deletes + fixed
-- bookkeeping (at most 3*budget+18 receipt upserts, admission, progress, inbox).
-- SQL time is also
-- bounded by the caller's lock/statement/transaction timeouts.
DROP FUNCTION assessment_purge_quiz(uuid,uuid,integer);
DO $migration$ BEGIN EXECUTE format($ddl$
CREATE FUNCTION %1$I.assessment_purge_quiz(p_quiz uuid,p_operation uuid,p_budget integer)
 RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,%1$I,pg_temp AS $body$
DECLARE
 s integer; n bigint; total bigint:=0; used integer:=0; row_id uuid; row_other uuid;
 target text; predicate text; removed bigint; started boolean;
 steps text[]:=ARRAY['grading_question_results','attempt_finalization_audit','closure_work_audit',
   'assessment_notifications','closure_request_members','publication_operations','closure_requests',
   'attempt_operations','attempt_creation_operations','assessment_monitoring_outbox',
   'realtime_invalidation_outbox','attempt_answers','attempts','publication_creations','publications'];
BEGIN
 IF p_budget IS NULL OR p_budget<1 OR p_budget>50000 THEN RAISE EXCEPTION 'invalid purge budget'; END IF;
 IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'purge requires READ COMMITTED'; END IF;
 INSERT INTO purge_admission(quiz_id) VALUES(p_quiz) ON CONFLICT DO NOTHING;
 SELECT begun INTO started FROM purge_admission WHERE quiz_id=p_quiz FOR UPDATE;
 IF NOT EXISTS(SELECT FROM quiz_revocation_fences WHERE quiz_id=p_quiz AND operation_id=p_operation AND ack_at IS NOT NULL) THEN
   RAISE EXCEPTION 'quiz purge requires an acknowledged matching fence';
 END IF;
 IF EXISTS(SELECT FROM retention_holds WHERE quiz_id=p_quiz AND (expires_at IS NULL OR expires_at>clock_timestamp())) THEN
   RETURN 'BLOCKED_BY_RETENTION';
 END IF;
 UPDATE purge_admission SET begun=true WHERE quiz_id=p_quiz AND NOT begun;
 DELETE FROM deletion_command_inbox WHERE operation_id=p_operation AND quiz_id=p_quiz;
 INSERT INTO purge_progress(operation_id,quiz_id,next_step) VALUES(p_operation,p_quiz,-1) ON CONFLICT DO NOTHING;
 SELECT next_step INTO STRICT s FROM purge_progress WHERE operation_id=p_operation AND quiz_id=p_quiz;
 PERFORM set_config('quizopia.assessment_purge_quiz',p_quiz::text,true);
 WHILE used<p_budget LOOP
   n:=0;
   IF s=-1 THEN
     INSERT INTO purged_publication_markers(publication_id,operation_id)
       SELECT p.id,p_operation FROM publications p WHERE p.quiz_id=p_quiz AND NOT EXISTS
        (SELECT FROM purged_publication_markers m WHERE m.publication_id=p.id)
       ORDER BY p.id LIMIT (p_budget-used);
     GET DIAGNOSTICS n=ROW_COUNT; used:=used+n;
   ELSIF s=0 THEN
     INSERT INTO purged_attempt_markers(attempt_id,operation_id)
       SELECT a.id,p_operation FROM attempts a JOIN purged_publication_markers m ON m.publication_id=a.publication_id
       WHERE m.operation_id=p_operation AND NOT EXISTS(SELECT FROM purged_attempt_markers x WHERE x.attempt_id=a.id)
       ORDER BY a.id LIMIT (p_budget-used);
     GET DIAGNOSTICS n=ROW_COUNT; used:=used+n;
   ELSIF s>cardinality(steps) THEN EXIT;
   ELSE
     target:=steps[s];
     IF target='grading_question_results' THEN
       predicate:='t.result_id IN (SELECT r.id FROM grading_results r JOIN purged_attempt_markers m ON m.attempt_id=r.attempt_id WHERE m.operation_id=$1)';
     ELSIF target IN ('publication_operations','closure_requests','publication_creations') THEN
       predicate:='t.publication_id IN (SELECT publication_id FROM purged_publication_markers WHERE operation_id=$1)';
     ELSIF target='realtime_invalidation_outbox' THEN
       predicate:='(t.resource_kind=''PUBLICATION'' AND t.resource_id IN (SELECT publication_id FROM purged_publication_markers WHERE operation_id=$1)) OR (t.resource_kind=''ATTEMPT'' AND t.resource_id IN (SELECT attempt_id FROM purged_attempt_markers WHERE operation_id=$1))';
     ELSIF target='publications' THEN
       predicate:='t.id IN (SELECT publication_id FROM purged_publication_markers WHERE operation_id=$1)';
     ELSIF target='attempts' THEN
       predicate:='t.id IN (SELECT attempt_id FROM purged_attempt_markers WHERE operation_id=$1)';
     ELSE
       predicate:='t.attempt_id IN (SELECT attempt_id FROM purged_attempt_markers WHERE operation_id=$1)';
     END IF;
     IF target='assessment_notifications' THEN
       -- Drain each fixed two-row cycle atomically. Its reverse FK is deferred;
       -- the forward notification->member FK is still immediate.
       SELECT t.id,t.attempt_id INTO row_id,row_other FROM assessment_notifications t
        WHERE t.attempt_id IN (SELECT attempt_id FROM purged_attempt_markers WHERE operation_id=p_operation) LIMIT 1;
       IF FOUND THEN
         DELETE FROM assessment_notifications WHERE id=row_id; n:=1;
         DELETE FROM closure_request_members WHERE attempt_id=row_other AND notification_id=row_id;
         GET DIAGNOSTICS removed=ROW_COUNT;
         INSERT INTO deletion_purge_receipts VALUES(p_operation,p_quiz,'closure_request_members',removed,clock_timestamp())
          ON CONFLICT(operation_id,step) DO UPDATE SET rows_removed=deletion_purge_receipts.rows_removed+EXCLUDED.rows_removed;
         total:=total+removed;
       END IF;
     ELSIF target='attempts' THEN
       SELECT t.id INTO row_id FROM attempts t WHERE t.id IN
         (SELECT attempt_id FROM purged_attempt_markers WHERE operation_id=p_operation) LIMIT 1;
       IF FOUND THEN
         DELETE FROM grading_results WHERE attempt_id=row_id; GET DIAGNOSTICS removed=ROW_COUNT;
         INSERT INTO deletion_purge_receipts VALUES(p_operation,p_quiz,'grading_results',removed,clock_timestamp())
          ON CONFLICT(operation_id,step) DO UPDATE SET rows_removed=deletion_purge_receipts.rows_removed+EXCLUDED.rows_removed;
         total:=total+removed;
         DELETE FROM submitted_answers WHERE attempt_id=row_id; GET DIAGNOSTICS removed=ROW_COUNT;
         INSERT INTO deletion_purge_receipts VALUES(p_operation,p_quiz,'submitted_answers',removed,clock_timestamp())
          ON CONFLICT(operation_id,step) DO UPDATE SET rows_removed=deletion_purge_receipts.rows_removed+EXCLUDED.rows_removed;
         total:=total+removed;
         DELETE FROM attempts WHERE id=row_id; n:=1;
       END IF;
     ELSIF target='publications' THEN
       SELECT t.id INTO row_id FROM publications t WHERE t.id IN
         (SELECT publication_id FROM purged_publication_markers WHERE operation_id=p_operation) LIMIT 1;
       IF FOUND THEN
         DELETE FROM publications WHERE id=row_id; n:=1;
         DELETE FROM publication_snapshots WHERE publication_id=row_id; GET DIAGNOSTICS removed=ROW_COUNT;
         INSERT INTO deletion_purge_receipts VALUES(p_operation,p_quiz,'publication_snapshots',removed,clock_timestamp())
          ON CONFLICT(operation_id,step) DO UPDATE SET rows_removed=deletion_purge_receipts.rows_removed+EXCLUDED.rows_removed;
         total:=total+removed;
       END IF;
     ELSE
       EXECUTE format('DELETE FROM %%I WHERE ctid IN (SELECT t.ctid FROM %%I t WHERE %%s LIMIT $2)',target,target,predicate)
         USING p_operation,p_budget-used;
       GET DIAGNOSTICS n=ROW_COUNT;
     END IF;
     total:=total+n; used:=used+n;
     INSERT INTO deletion_purge_receipts VALUES(p_operation,p_quiz,target,n,clock_timestamp())
       ON CONFLICT(operation_id,step) DO UPDATE SET rows_removed=deletion_purge_receipts.rows_removed+EXCLUDED.rows_removed;
   END IF;
   IF n=0 THEN s:=s+1; END IF;
 END LOOP;
 UPDATE purge_progress SET next_step=s,total_removed=total_removed+total,rounds=rounds+1,updated_at=clock_timestamp()
   WHERE operation_id=p_operation;
 IF s<=cardinality(steps) THEN RETURN 'IN_PROGRESS'; END IF;
 -- Scope is durable even after parents disappear; snapshots cannot become invisible.
 IF EXISTS(SELECT FROM publications WHERE quiz_id=p_quiz)
   OR EXISTS(SELECT FROM publication_snapshots s JOIN purged_publication_markers m ON m.publication_id=s.publication_id WHERE m.operation_id=p_operation)
   OR EXISTS(SELECT FROM attempts a JOIN purged_attempt_markers m ON m.attempt_id=a.id WHERE m.operation_id=p_operation)
   OR EXISTS(SELECT FROM realtime_invalidation_outbox r WHERE
      (r.resource_kind='PUBLICATION' AND r.resource_id IN (SELECT publication_id FROM purged_publication_markers WHERE operation_id=p_operation))
      OR (r.resource_kind='ATTEMPT' AND r.resource_id IN (SELECT attempt_id FROM purged_attempt_markers WHERE operation_id=p_operation)))
   OR EXISTS(SELECT FROM deletion_command_inbox WHERE quiz_id=p_quiz) THEN
   RAISE EXCEPTION 'purge left owned dependencies';
 END IF;
 INSERT INTO deletion_purge_receipts VALUES(p_operation,p_quiz,'verified_zero',0,clock_timestamp())
  ON CONFLICT(operation_id,step) DO UPDATE SET completed_at=EXCLUDED.completed_at;
 DELETE FROM purge_progress WHERE operation_id=p_operation;
 RETURN 'PURGED';
END $body$ $ddl$,current_schema()); END $migration$;

-- Transfer AFTER all function creation; exclude Flyway's locked history table.
-- Never swallow ownership/grant failures. pg_temp explicitly LAST prevents masking.
DO $$ DECLARE obj record; BEGIN
 EXECUTE format('REVOKE CREATE ON SCHEMA %I FROM PUBLIC,assessment',current_schema());
 IF has_schema_privilege('assessment',current_schema(),'CREATE') THEN RAISE EXCEPTION 'inherited runtime schema creation privilege'; END IF;
 EXECUTE format('GRANT USAGE,CREATE ON SCHEMA %I TO quizopia_assessment_owner',current_schema());
 EXECUTE format('GRANT USAGE ON SCHEMA %I TO assessment,quizopia_assessment_custodian',current_schema());
 FOR obj IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname=current_schema() AND c.relkind='r' AND c.relname<>'flyway_schema_history' LOOP
   EXECUTE format('ALTER TABLE %I OWNER TO quizopia_assessment_owner',obj.relname);
   EXECUTE format('REVOKE ALL ON %I FROM PUBLIC,assessment,quizopia_assessment_custodian',obj.relname);
   EXECUTE format('GRANT SELECT ON %I TO assessment',obj.relname);
    IF obj.relname NOT IN ('retention_holds','deletion_purge_receipts','purge_progress','purge_admission',
      'purged_publication_markers','purged_attempt_markers') THEN
      EXECUTE format('GRANT INSERT,UPDATE ON %I TO assessment',obj.relname);
    ELSIF has_table_privilege('assessment',obj.relname,'INSERT') OR has_table_privilege('assessment',obj.relname,'UPDATE') THEN
      RAISE EXCEPTION 'inherited runtime bookkeeping/custody privilege: %',obj.relname;
    END IF;
   IF has_table_privilege('assessment',obj.relname,'DELETE') OR has_table_privilege('assessment',obj.relname,'TRUNCATE') THEN
     RAISE EXCEPTION 'inherited destructive runtime privilege: %',obj.relname;
   END IF;
 END LOOP;
 FOR obj IN SELECT p.oid::regprocedure AS routine,p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
   WHERE n.nspname=current_schema() LOOP
   EXECUTE format('ALTER FUNCTION %s OWNER TO quizopia_assessment_owner',obj.routine);
   -- Existing trigger routines include schema-qualified helper calls; pin all paths.
   EXECUTE format('ALTER FUNCTION %s SET search_path=pg_catalog,%I,pg_temp',obj.routine,current_schema());
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE,DELETE ON retention_holds TO quizopia_assessment_custodian;
DO $$ BEGIN
 IF has_table_privilege('assessment','retention_holds','INSERT')
   OR has_table_privilege('assessment','retention_holds','UPDATE') THEN
   RAISE EXCEPTION 'inherited runtime retention custody privilege';
 END IF;
END $$;
REVOKE ALL ON FUNCTION assessment_hold_admission() FROM PUBLIC,assessment;
REVOKE ALL ON FUNCTION assessment_purge_write_fence() FROM PUBLIC,assessment;
REVOKE ALL ON FUNCTION assessment_fence_admission() FROM PUBLIC,assessment;
REVOKE ALL ON FUNCTION assessment_delete_draft(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION assessment_purge_quiz(uuid,uuid,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION assessment_delete_draft(uuid),assessment_purge_quiz(uuid,uuid,integer) TO assessment;
