-- Durable coalescing work, not authorization evidence. No Redis network I/O in a transaction.
CREATE TABLE user_authority_work (
    user_id UUID PRIMARY KEY,
    work_id UUID NOT NULL,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_user_authority_work_due ON user_authority_work (queued_at, user_id);

CREATE FUNCTION enqueue_user_authority_work(actor UUID) RETURNS void
LANGUAGE sql AS $$
    INSERT INTO user_authority_work (user_id, work_id)
    VALUES (actor, gen_random_uuid())
    ON CONFLICT (user_id) DO UPDATE SET work_id = EXCLUDED.work_id;
$$;

CREATE FUNCTION capture_user_authority_work() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        IF TG_TABLE_NAME = 'user_account' THEN
            PERFORM enqueue_user_authority_work(OLD.id);
        ELSE
            PERFORM enqueue_user_authority_work(OLD.user_id);
        END IF;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        IF TG_TABLE_NAME = 'user_account' THEN
            PERFORM enqueue_user_authority_work(NEW.id);
        ELSE
            PERFORM enqueue_user_authority_work(NEW.user_id);
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER user_account_authority_insert_delete AFTER INSERT OR DELETE ON user_account
FOR EACH ROW EXECUTE FUNCTION capture_user_authority_work();
CREATE TRIGGER user_account_authority_update AFTER UPDATE OF account_status, email_verified_at ON user_account
FOR EACH ROW EXECUTE FUNCTION capture_user_authority_work();
CREATE TRIGGER user_role_authority_work AFTER INSERT OR UPDATE OR DELETE ON user_role
FOR EACH ROW EXECUTE FUNCTION capture_user_authority_work();
CREATE TRIGGER user_revocation_authority_work AFTER INSERT OR UPDATE OR DELETE ON user_access_revocation
FOR EACH ROW EXECUTE FUNCTION capture_user_authority_work();
-- Existing accounts are recovered by the separately controlled keyset sweep; no migration-time full-table backfill.
