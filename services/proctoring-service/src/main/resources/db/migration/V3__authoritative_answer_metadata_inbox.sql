-- Additive after shared V1/V2; drain writers before applying. No cross-service FK/SQL.
ALTER TABLE monitoring_evidence ADD COLUMN assessment_revision bigint CHECK(assessment_revision>0);
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='monitoring_evidence'::regclass AND contype='c'
  AND (pg_get_constraintdef(oid) LIKE '%event_type%' OR pg_get_constraintdef(oid) LIKE '%source%') LOOP
  EXECUTE format('ALTER TABLE monitoring_evidence DROP CONSTRAINT %I',c.conname);
 END LOOP;
END $$;
ALTER TABLE monitoring_evidence ADD CONSTRAINT monitoring_event_type_v3 CHECK(event_type IN (
 'QUESTION_VIEWED','QUESTION_LEFT','ANSWER_SELECTED','ANSWER_CHANGED','ANSWER_CLEARED',
 'VISIBILITY_HIDDEN','VISIBILITY_VISIBLE','WINDOW_BLUR','WINDOW_FOCUS','FULLSCREEN_ENTERED','FULLSCREEN_EXITED',
 'CAMERA_PERMISSION_GRANTED','CAMERA_PERMISSION_DENIED','DEVICE_MISSING','DEVICE_ERROR','CAMERA_UNAVAILABLE',
 'MEDIA_DISCONNECTED','RECONNECTING','NETWORK_DISCONNECTED','NETWORK_RECONNECTED','DUPLICATE_TAB',
 'SESSION_STARTED','SESSION_ENDED','ANSWER_PERSISTED'));
ALTER TABLE monitoring_evidence ADD CONSTRAINT monitoring_envelope_v3 CHECK(
 (source='CLIENT_OBSERVATION' AND stream_id IS NOT NULL AND client_sequence IS NOT NULL AND assessment_revision IS NULL
  AND event_type NOT IN ('SESSION_STARTED','SESSION_ENDED','ANSWER_PERSISTED'))
 OR (source='SERVER_AUTHORITY' AND stream_id IS NULL AND client_sequence IS NULL AND observed_at IS NULL
  AND question_number IS NULL AND assessment_revision IS NULL AND event_type IN ('SESSION_STARTED','SESSION_ENDED'))
 OR (source='ASSESSMENT_AUTHORITY' AND event_type='ANSWER_PERSISTED' AND stream_id IS NULL AND client_sequence IS NULL
  AND observed_at IS NOT NULL AND question_number IS NOT NULL AND assessment_revision IS NOT NULL));
ALTER TABLE monitoring_evidence ADD CONSTRAINT monitoring_question_v3 CHECK(
 (event_type IN ('QUESTION_VIEWED','QUESTION_LEFT','ANSWER_SELECTED','ANSWER_CHANGED','ANSWER_CLEARED','ANSWER_PERSISTED'))=(question_number IS NOT NULL));
CREATE UNIQUE INDEX assessment_evidence_revision ON monitoring_evidence(session_id,question_number,assessment_revision) WHERE source='ASSESSMENT_AUTHORITY';
CREATE TABLE assessment_monitoring_inbox (
 event_id uuid PRIMARY KEY,
 attempt_id uuid NOT NULL,
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
 payload_sha256 char(64) NOT NULL CHECK(payload_sha256 ~ '^[0-9a-f]{64}$'),
 state varchar(11) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPLIED','QUARANTINED')),
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 processed_at timestamptz,
 last_attempt_at timestamptz,
 CHECK((state='PENDING')=(processed_at IS NULL))
);
CREATE INDEX assessment_monitoring_pending ON assessment_monitoring_inbox(attempt_id,last_attempt_at) WHERE state='PENDING';
CREATE TABLE assessment_monitoring_rejections (
 payload_sha256 char(64) PRIMARY KEY CHECK(payload_sha256 ~ '^[0-9a-f]{64}$'),
 reason varchar(20) NOT NULL CHECK(reason IN ('INVALID_EVENT','EVENT_CONFLICT','BINDING_CONFLICT')),
 first_seen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 occurrences bigint NOT NULL DEFAULT 1 CHECK(occurrences>0)
);
CREATE FUNCTION assessment_monitoring_inbox_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.state<>'PENDING' OR NEW.event_id<>OLD.event_id OR NEW.attempt_id<>OLD.attempt_id
  OR NEW.payload<>OLD.payload OR NEW.payload_sha256<>OLD.payload_sha256 OR NEW.received_at<>OLD.received_at THEN
  RAISE EXCEPTION 'authoritative inbox identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER assessment_monitoring_inbox_guard BEFORE UPDATE OR DELETE ON assessment_monitoring_inbox FOR EACH ROW EXECUTE FUNCTION assessment_monitoring_inbox_guard();
