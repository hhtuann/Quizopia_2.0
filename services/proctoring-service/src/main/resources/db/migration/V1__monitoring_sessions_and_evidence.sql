CREATE TABLE monitoring_sessions (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL UNIQUE,
    publication_id uuid NOT NULL,
    classroom_id uuid NOT NULL,
    student_id uuid NOT NULL,
    deadline_at timestamptz NOT NULL,
    status varchar(8) NOT NULL CHECK(status IN ('ACTIVE','ENDED')),
    created_at timestamptz NOT NULL,
    ended_at timestamptz,
    CHECK(deadline_at > created_at),
    CHECK((status='ACTIVE' AND ended_at IS NULL) OR (status='ENDED' AND ended_at IS NOT NULL AND ended_at>=created_at))
);
CREATE TABLE monitoring_evidence (
    ordinal bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id uuid NOT NULL UNIQUE,
    session_id uuid NOT NULL REFERENCES monitoring_sessions(id),
    actor_id uuid NOT NULL,
    stream_id uuid,
    client_sequence bigint CHECK(client_sequence>=0),
    schema_version integer NOT NULL DEFAULT 1 CHECK(schema_version=1),
    event_type varchar(32) NOT NULL CHECK(event_type IN (
        'QUESTION_VIEWED','QUESTION_LEFT','ANSWER_SELECTED','ANSWER_CHANGED','ANSWER_CLEARED',
        'VISIBILITY_HIDDEN','VISIBILITY_VISIBLE','WINDOW_BLUR','WINDOW_FOCUS',
        'FULLSCREEN_ENTERED','FULLSCREEN_EXITED','CAMERA_PERMISSION_GRANTED','CAMERA_PERMISSION_DENIED',
        'DEVICE_MISSING','DEVICE_ERROR','CAMERA_UNAVAILABLE','MEDIA_DISCONNECTED','RECONNECTING',
        'NETWORK_DISCONNECTED','NETWORK_RECONNECTED','DUPLICATE_TAB','SESSION_STARTED','SESSION_ENDED')),
    observed_at timestamptz,
    question_number integer CHECK(question_number>0),
    received_at timestamptz NOT NULL,
    source varchar(20) NOT NULL CHECK(source IN ('CLIENT_OBSERVATION','SERVER_AUTHORITY')),
    integrity_sha256 char(64) NOT NULL CHECK(integrity_sha256 ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz,
    retention_policy_reference varchar(512),
    UNIQUE(session_id,stream_id,client_sequence),
    CHECK((source='CLIENT_OBSERVATION' AND stream_id IS NOT NULL AND client_sequence IS NOT NULL AND event_type NOT IN ('SESSION_STARTED','SESSION_ENDED'))
       OR (source='SERVER_AUTHORITY' AND stream_id IS NULL AND client_sequence IS NULL AND observed_at IS NULL AND question_number IS NULL AND event_type IN ('SESSION_STARTED','SESSION_ENDED'))),
    CHECK((event_type IN ('QUESTION_VIEWED','QUESTION_LEFT','ANSWER_SELECTED','ANSWER_CHANGED','ANSWER_CLEARED')) = (question_number IS NOT NULL)),
    CHECK((expires_at IS NULL AND retention_policy_reference IS NULL) OR (expires_at>received_at AND length(retention_policy_reference)>0))
);
CREATE INDEX monitoring_timeline ON monitoring_evidence(session_id,ordinal);
CREATE INDEX monitoring_expiry ON monitoring_evidence(expires_at) WHERE expires_at IS NOT NULL;
CREATE TABLE monitoring_access_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES monitoring_sessions(id),
    actor_id uuid NOT NULL,
    viewer_role varchar(7) NOT NULL CHECK(viewer_role IN ('STUDENT','TEACHER')),
    action varchar(20) NOT NULL CHECK(action IN ('SESSION_READ','TIMELINE_READ')),
    accessed_at timestamptz NOT NULL,
    expires_at timestamptz,
    retention_policy_reference varchar(512),
    CHECK((expires_at IS NULL AND retention_policy_reference IS NULL) OR (expires_at>accessed_at AND length(retention_policy_reference)>0))
);
CREATE INDEX monitoring_audit_session ON monitoring_access_audit(session_id,id);
CREATE FUNCTION monitoring_session_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.id<>OLD.id OR NEW.attempt_id<>OLD.attempt_id OR NEW.publication_id<>OLD.publication_id
        OR NEW.classroom_id<>OLD.classroom_id OR NEW.student_id<>OLD.student_id OR NEW.deadline_at<>OLD.deadline_at
        OR NEW.created_at<>OLD.created_at OR OLD.status='ENDED' THEN
        RAISE EXCEPTION 'monitoring session is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER monitoring_session_guard BEFORE UPDATE OR DELETE ON monitoring_sessions FOR EACH ROW EXECUTE FUNCTION monitoring_session_guard();
CREATE FUNCTION monitoring_append_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Only the owning retention function may remove already-expired records.
    IF TG_OP='DELETE' AND OLD.expires_at IS NOT NULL AND OLD.expires_at<=clock_timestamp()
        AND current_user=(SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid=TG_RELID) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'monitoring history is append only' USING ERRCODE='23514';
END $$;
CREATE TRIGGER monitoring_evidence_guard BEFORE UPDATE OR DELETE ON monitoring_evidence FOR EACH ROW EXECUTE FUNCTION monitoring_append_guard();
CREATE TRIGGER monitoring_audit_guard BEFORE UPDATE OR DELETE ON monitoring_access_audit FOR EACH ROW EXECUTE FUNCTION monitoring_append_guard();
-- No public execution grant, scheduling or default expiry. A separately approved
-- operator role may receive EXECUTE after policy/control review. Runtime must not own tables/functions.
CREATE FUNCTION monitoring_purge_expired(batch_limit integer) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE removed integer; audit_removed integer;
BEGIN
    IF batch_limit IS NULL OR batch_limit<1 OR batch_limit>1000 THEN RAISE EXCEPTION 'invalid retention batch'; END IF;
    DELETE FROM public.monitoring_evidence WHERE ordinal IN
        (SELECT ordinal FROM public.monitoring_evidence WHERE expires_at<=clock_timestamp() ORDER BY ordinal LIMIT batch_limit FOR UPDATE SKIP LOCKED);
    GET DIAGNOSTICS removed=ROW_COUNT;
    DELETE FROM public.monitoring_access_audit WHERE id IN
        (SELECT id FROM public.monitoring_access_audit WHERE expires_at<=clock_timestamp() ORDER BY id LIMIT batch_limit FOR UPDATE SKIP LOCKED);
    GET DIAGNOSTICS audit_removed=ROW_COUNT;
    RETURN removed+audit_removed;
END $$;
REVOKE ALL ON FUNCTION monitoring_purge_expired(integer) FROM PUBLIC;
