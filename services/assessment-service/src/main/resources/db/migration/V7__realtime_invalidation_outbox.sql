-- W4-B3 realtime invalidation outbox. Independent from the W3 monitoring outbox:
-- separate table, GUC, triggers, publisher and topology. Capture is connection-gated
-- by the 'quizopia.realtime_invalidation' GUC so default-disabled pools add zero rows.
CREATE TABLE realtime_invalidation_outbox (
 event_id uuid PRIMARY KEY,
 event_type varchar(24) NOT NULL CHECK (event_type IN ('PUBLICATION_INVALIDATED','ATTEMPT_INVALIDATED')),
 event_version integer NOT NULL CHECK (event_version=1),
 resource_kind varchar(12) NOT NULL CHECK (resource_kind IN ('PUBLICATION','ATTEMPT')),
 resource_id uuid NOT NULL,
 occurred_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 state varchar(12) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','PUBLISHED','QUARANTINED')),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 last_attempt_at timestamptz,
 lease_owner uuid,
 lease_until timestamptz,
 delivery_attempts bigint NOT NULL DEFAULT 0 CHECK (delivery_attempts>=0),
 published_at timestamptz,
 CHECK ((event_type='PUBLICATION_INVALIDATED')=(resource_kind='PUBLICATION')),
 CHECK ((lease_owner IS NULL)=(lease_until IS NULL)),
 CHECK ((state='PUBLISHED')=(published_at IS NOT NULL)),
 CHECK (state<>'PUBLISHED' OR lease_owner IS NULL),
 CHECK (state<>'QUARANTINED' OR delivery_attempts>=1)
);
CREATE INDEX realtime_invalidation_outbox_pending
 ON realtime_invalidation_outbox(next_attempt_at,created_at,event_id) WHERE state='PENDING';

CREATE FUNCTION realtime_invalidation_outbox_immutable() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Realtime invalidation evidence is immutable'; END IF;
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
END $$;
CREATE TRIGGER realtime_invalidation_outbox_immutable BEFORE UPDATE OR DELETE ON realtime_invalidation_outbox
 FOR EACH ROW EXECUTE FUNCTION realtime_invalidation_outbox_immutable();

CREATE FUNCTION realtime_publication_invalidation() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF pg_catalog.current_setting('quizopia.realtime_invalidation',true) IS DISTINCT FROM 'true' THEN RETURN NEW; END IF;
 IF NOT ((OLD.state='DRAFT' AND NEW.state='OPEN')
   OR (OLD.state='OPEN' AND NEW.state='CLOSED')
   OR (OLD.state='OPEN' AND NEW.state='OPEN'
       AND (NEW.end_at>OLD.end_at OR NEW.duration_minutes>OLD.duration_minutes)))
 THEN RETURN NEW; END IF;
 EXECUTE format('INSERT INTO %I.realtime_invalidation_outbox(event_id,event_type,event_version,resource_kind,resource_id,occurred_at)'
  || ' VALUES (pg_catalog.gen_random_uuid(),''PUBLICATION_INVALIDATED'',1,''PUBLICATION'',%L,pg_catalog.clock_timestamp())',TG_TABLE_SCHEMA,NEW.id);
 RETURN NEW;
END $$;
-- AFTER trigger inside the business transaction: rollback discards the event and an outbox
-- failure aborts the writer instead of silently bypassing transactional guarantees.
CREATE TRIGGER realtime_publication_invalidation AFTER UPDATE ON publications
 FOR EACH ROW EXECUTE FUNCTION realtime_publication_invalidation();

CREATE FUNCTION realtime_attempt_invalidation() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF pg_catalog.current_setting('quizopia.realtime_invalidation',true) IS DISTINCT FROM 'true' THEN RETURN NEW; END IF;
 IF (TG_OP='INSERT' AND NEW.state='ACTIVE')
  OR (TG_OP='UPDATE' AND OLD.state='ACTIVE' AND NEW.state IN ('SUBMITTED','ABANDONED')) THEN
  EXECUTE format('INSERT INTO %I.realtime_invalidation_outbox(event_id,event_type,event_version,resource_kind,resource_id,occurred_at)'
   || ' VALUES (pg_catalog.gen_random_uuid(),''ATTEMPT_INVALIDATED'',1,''ATTEMPT'',%L,pg_catalog.clock_timestamp())',TG_TABLE_SCHEMA,NEW.id);
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER realtime_attempt_invalidation_insert AFTER INSERT ON attempts
 FOR EACH ROW EXECUTE FUNCTION realtime_attempt_invalidation();
CREATE TRIGGER realtime_attempt_invalidation_update AFTER UPDATE ON attempts
 FOR EACH ROW EXECUTE FUNCTION realtime_attempt_invalidation();
