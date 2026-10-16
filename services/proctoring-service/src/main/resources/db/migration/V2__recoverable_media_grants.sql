CREATE TABLE monitoring_media_rooms (
    session_id uuid PRIMARY KEY REFERENCES monitoring_sessions(id),
    room_name varchar(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deleted_at timestamptz,
    last_recovery_at timestamptz
);
CREATE TABLE monitoring_media_grants (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES monitoring_media_rooms(session_id),
    actor_id uuid NOT NULL,
    viewer_role varchar(7) NOT NULL CHECK(viewer_role IN ('STUDENT','TEACHER')),
    participant_identity varchar(64) NOT NULL UNIQUE,
    lease_until timestamptz NOT NULL,
    state varchar(16) NOT NULL CHECK(state IN ('PREPARED','ACTIVE','REVOKE_PENDING','REVOKED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    revoked_at timestamptz,
    last_recovery_at timestamptz,
    CHECK(lease_until>created_at),
    CHECK((state='REVOKED')=(revoked_at IS NOT NULL))
);
CREATE INDEX monitoring_media_recovery ON monitoring_media_grants(state,lease_until);
CREATE FUNCTION monitoring_media_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'media history is immutable' USING ERRCODE='23514'; END IF;
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
CREATE TRIGGER monitoring_media_grant_guard BEFORE UPDATE OR DELETE ON monitoring_media_grants FOR EACH ROW EXECUTE FUNCTION monitoring_media_binding_guard();
-- Separate room guard avoids referring to grant-only fields in a room trigger.
CREATE TRIGGER monitoring_media_room_guard BEFORE UPDATE OR DELETE ON monitoring_media_rooms FOR EACH ROW EXECUTE FUNCTION monitoring_media_binding_guard();
