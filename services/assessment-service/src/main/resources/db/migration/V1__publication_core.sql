CREATE TABLE publication_snapshots (
 publication_id uuid PRIMARY KEY,
 schema_version integer NOT NULL CHECK (schema_version=1),
 grading_policy_version integer NOT NULL CHECK (grading_policy_version=1),
 payload jsonb NOT NULL CHECK (
 (jsonb_typeof(payload)='object' AND payload ?& ARRAY['schemaVersion','quizId','quizVersionId','versionNumber','titleSnapshot','descriptionSnapshot','sourceSnapshot','structuredContent','contentSchemaVersion','createdAt']
 AND payload->'schemaVersion'='1'::jsonb AND payload->'contentSchemaVersion'='1'::jsonb
 AND jsonb_typeof(payload->'structuredContent')='object' AND jsonb_typeof(payload->'sourceSnapshot')='string') IS TRUE),
 created_at timestamptz NOT NULL
);
CREATE TABLE publications (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL,
 configuration jsonb NOT NULL CHECK(jsonb_typeof(configuration)='object'),
 quiz_id uuid NOT NULL, quiz_version_id uuid NOT NULL, version_number integer NOT NULL CHECK(version_number BETWEEN 1 AND 999999999),
 audience varchar(6) NOT NULL CHECK(audience IN ('CLASS','PUBLIC')), classroom_id uuid,
 start_at timestamptz NOT NULL, end_at timestamptz NOT NULL, duration_minutes integer NOT NULL CHECK(duration_minutes BETWEEN 5 AND 240),
 monitoring_choice varchar(12) NOT NULL CHECK(monitoring_choice IN ('UNCONFIGURED','ENABLED','DISABLED')),
 state varchar(6) NOT NULL CHECK(state IN ('DRAFT','OPEN','CLOSED')),
 revision bigint NOT NULL CHECK(revision>=0), opened_from_revision bigint,
 snapshot_id uuid UNIQUE REFERENCES publication_snapshots(publication_id),
 private_locator varchar(43), locator_digest char(64) UNIQUE,
 created_at timestamptz NOT NULL, opened_at timestamptz,
 CHECK(start_at<end_at),
 CHECK((configuration ?& ARRAY['title','description','quizId','quizVersionId','versionNumber','audience','classroomId','startAt','endAt','durationMinutes','monitoringChoice']
 AND configuration->>'quizId'=quiz_id::text AND configuration->>'quizVersionId'=quiz_version_id::text
 AND (configuration->>'versionNumber')::integer=version_number AND configuration->>'audience'=audience
 AND (configuration->>'classroomId')::uuid IS NOT DISTINCT FROM classroom_id
 AND (configuration->>'startAt')::timestamptz=start_at AND (configuration->>'endAt')::timestamptz=end_at
 AND (configuration->>'durationMinutes')::integer=duration_minutes AND configuration->>'monitoringChoice'=monitoring_choice
 AND jsonb_typeof(configuration->'title')='string' AND length(configuration->>'title') BETWEEN 1 AND 300 AND configuration->>'title' ~ '[^[:space:]]'
 AND (configuration->'description'='null'::jsonb OR (jsonb_typeof(configuration->'description')='string' AND length(configuration->>'description')<=5000))) IS TRUE),
 CHECK((audience='CLASS' AND classroom_id IS NOT NULL AND private_locator IS NULL AND locator_digest IS NULL)
    OR (audience='PUBLIC' AND classroom_id IS NULL AND monitoring_choice='DISABLED' AND private_locator IS NOT NULL AND private_locator ~ '^[A-Za-z0-9_-]{43}$' AND locator_digest IS NOT NULL)),
 CHECK((state='DRAFT' AND snapshot_id IS NULL AND opened_at IS NULL AND opened_from_revision IS NULL)
    OR (state IN ('OPEN','CLOSED') AND snapshot_id IS NOT NULL AND snapshot_id=id AND opened_at IS NOT NULL AND opened_from_revision IS NOT NULL AND opened_from_revision>=0 AND revision>opened_from_revision AND monitoring_choice<>'UNCONFIGURED'))
);
CREATE INDEX publications_owner_idx ON publications(owner_id,id);
CREATE TABLE publication_creations (
 owner_id uuid NOT NULL, key_digest char(64) NOT NULL, request_digest char(64) NOT NULL,
 publication_id uuid NOT NULL REFERENCES publications(id), PRIMARY KEY(owner_id,key_digest)
);
CREATE FUNCTION reject_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN RAISE EXCEPTION 'Finalized snapshot is immutable'; END $$;
CREATE TRIGGER immutable_snapshot BEFORE UPDATE OR DELETE ON publication_snapshots FOR EACH ROW EXECUTE FUNCTION reject_snapshot_mutation();
CREATE FUNCTION guard_publication_update() RETURNS trigger LANGUAGE plpgsql AS $$
 BEGIN
  IF TG_OP='DELETE' THEN
   IF OLD.state<>'DRAFT' THEN RAISE EXCEPTION 'Finalized publication is immutable'; END IF;
   RETURN OLD;
  END IF;
  IF NEW.id<>OLD.id OR NEW.owner_id<>OLD.owner_id OR NEW.created_at<>OLD.created_at OR NEW.revision<>OLD.revision+1 THEN
   RAISE EXCEPTION 'Invalid publication identity or revision';
  END IF;
  IF OLD.state<>'DRAFT' AND (
   NEW.state<>OLD.state OR NEW.quiz_id<>OLD.quiz_id OR NEW.quiz_version_id<>OLD.quiz_version_id OR NEW.version_number<>OLD.version_number
   OR NEW.audience<>OLD.audience OR NEW.classroom_id IS DISTINCT FROM OLD.classroom_id
   OR NEW.monitoring_choice<>OLD.monitoring_choice OR NEW.snapshot_id IS DISTINCT FROM OLD.snapshot_id
   OR NEW.opened_at IS DISTINCT FROM OLD.opened_at OR NEW.opened_from_revision IS DISTINCT FROM OLD.opened_from_revision
   OR NEW.private_locator IS DISTINCT FROM OLD.private_locator OR NEW.locator_digest IS DISTINCT FROM OLD.locator_digest
   OR NEW.start_at<>OLD.start_at OR NEW.end_at<OLD.end_at OR NEW.duration_minutes<OLD.duration_minutes
   OR (NEW.configuration - 'endAt' - 'durationMinutes') IS DISTINCT FROM (OLD.configuration - 'endAt' - 'durationMinutes')) THEN
    RAISE EXCEPTION 'Finalized publication configuration is frozen';
  END IF;
  IF OLD.state='DRAFT' AND NEW.state NOT IN ('DRAFT','OPEN') THEN RAISE EXCEPTION 'Invalid lifecycle transition'; END IF;
  RETURN NEW;
 END $$;
CREATE TRIGGER publication_guard BEFORE UPDATE OR DELETE ON publications FOR EACH ROW EXECUTE FUNCTION guard_publication_update();
