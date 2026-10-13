-- Drain all Publication writers before applying; preserve V1/V2 checksums and OPEN JSON.
LOCK TABLE publications IN ACCESS EXCLUSIVE MODE;
ALTER TABLE publications ADD COLUMN max_attempts integer NOT NULL DEFAULT 1
 CONSTRAINT publications_max_attempts_range CHECK (max_attempts BETWEEN 0 AND 10);
ALTER TABLE publications ADD COLUMN closed_at timestamptz
 CONSTRAINT publications_v3_closure_disabled CHECK (closed_at IS NULL);

-- Locate the one existing single-column duration CHECK by catalog identity,
-- rather than depending on PostgreSQL's generated constraint name.
DO $$
DECLARE duration_attribute smallint; target_name text; matches integer;
BEGIN
 SELECT attnum INTO STRICT duration_attribute FROM pg_attribute
 WHERE attrelid='publications'::regclass AND attname='duration_minutes' AND NOT attisdropped;
 SELECT count(*), min(conname::text) INTO matches,target_name FROM pg_constraint
 WHERE conrelid='publications'::regclass AND contype='c'
  AND conkey=ARRAY[duration_attribute]::smallint[];
 IF matches<>1 THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Unexpected duration constraint; operator review required';
 END IF;
 EXECUTE format('ALTER TABLE publications DROP CONSTRAINT %I',target_name);
END $$;
ALTER TABLE publications ADD CONSTRAINT publications_duration_range
 CHECK (duration_minutes=0 OR duration_minutes BETWEEN 5 AND 240);
ALTER TABLE publications ADD CONSTRAINT publications_unlimited_monitoring
 CHECK (duration_minutes<>0 OR monitoring_choice<>'ENABLED');
ALTER TABLE publications ADD CONSTRAINT publications_max_attempts_configuration
 CHECK ((
  (NOT (configuration ? 'maxAttempts') AND max_attempts=1)
  OR (configuration ? 'maxAttempts'
   AND jsonb_typeof(configuration->'maxAttempts')='number'
   AND configuration->>'maxAttempts' ~ '^(0|[1-9][0-9]*)$'
   AND (configuration->>'maxAttempts')::numeric=max_attempts)
 ) IS TRUE);

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
  IF OLD.state='CLOSED' OR NEW.closed_at IS DISTINCT FROM OLD.closed_at THEN
   RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Closure is not enabled';
  END IF;
  IF NEW.id<>OLD.id OR NEW.owner_id<>OLD.owner_id OR NEW.created_at<>OLD.created_at OR NEW.revision<>OLD.revision+1 THEN
   RAISE EXCEPTION 'Invalid publication identity or revision';
  END IF;
  IF OLD.state<>'DRAFT' AND (
   NEW.state<>OLD.state OR NEW.max_attempts<>OLD.max_attempts OR NEW.quiz_id<>OLD.quiz_id OR NEW.quiz_version_id<>OLD.quiz_version_id OR NEW.version_number<>OLD.version_number
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
DROP TRIGGER publication_guard ON publications;
CREATE TRIGGER publication_guard BEFORE INSERT OR UPDATE OR DELETE ON publications
 FOR EACH ROW EXECUTE FUNCTION guard_publication_update();
