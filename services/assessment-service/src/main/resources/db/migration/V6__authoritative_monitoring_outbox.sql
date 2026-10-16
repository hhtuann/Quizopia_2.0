-- Disabled unless the service connection explicitly opts into capture. No broker I/O in answer writes.
CREATE TABLE assessment_monitoring_outbox (
 event_id uuid PRIMARY KEY,
 attempt_id uuid NOT NULL REFERENCES attempts(id) ON DELETE RESTRICT,
 question_number integer NOT NULL CHECK(question_number>=1),
 answer_revision bigint NOT NULL CHECK(answer_revision>=1),
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object' AND octet_length(payload::text)<=4096),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 state varchar(10) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','PUBLISHED')),
 last_attempt_at timestamptz,
 lease_owner uuid,
 lease_until timestamptz,
 delivery_attempts bigint NOT NULL DEFAULT 0 CHECK(delivery_attempts>=0),
 published_at timestamptz,
 UNIQUE(attempt_id,question_number,answer_revision),
 CHECK((lease_owner IS NULL)=(lease_until IS NULL)),
 CHECK((state='PUBLISHED')=(published_at IS NOT NULL)),
 CHECK(state<>'PUBLISHED' OR lease_owner IS NULL)
);
CREATE INDEX assessment_monitoring_outbox_pending
 ON assessment_monitoring_outbox(last_attempt_at NULLS FIRST,created_at,event_id) WHERE state='PENDING';

CREATE FUNCTION assessment_monitoring_outbox_immutable() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Monitoring evidence is immutable'; END IF;
 IF ROW(NEW.event_id,NEW.attempt_id,NEW.question_number,NEW.answer_revision,NEW.payload,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.event_id,OLD.attempt_id,OLD.question_number,OLD.answer_revision,OLD.payload,OLD.created_at)
    OR OLD.state='PUBLISHED' THEN RAISE EXCEPTION 'Monitoring evidence is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER assessment_monitoring_outbox_immutable BEFORE UPDATE OR DELETE ON assessment_monitoring_outbox
 FOR EACH ROW EXECUTE FUNCTION assessment_monitoring_outbox_immutable();

CREATE FUNCTION assessment_capture_answer_metadata() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
DECLARE a record; p record; eid uuid; happened timestamptz; body jsonb;
BEGIN
 -- First branch has no row lookups or event construction for default-disabled connections.
 IF current_setting('quizopia.monitoring_capture',true) IS DISTINCT FROM 'true' THEN RETURN NEW; END IF;
 IF TG_OP='UPDATE' AND OLD.answer IS NOT DISTINCT FROM NEW.answer THEN RETURN NEW; END IF;
 EXECUTE format('SELECT * FROM %I.attempts WHERE id=$1',TG_TABLE_SCHEMA) INTO a USING NEW.attempt_id;
 IF a.state<>'ACTIVE' OR a.duration_minutes NOT BETWEEN 5 AND 240 OR a.deadline_at IS NULL THEN RETURN NEW; END IF;
 EXECUTE format('SELECT * FROM %I.publications WHERE id=$1',TG_TABLE_SCHEMA) INTO p USING a.publication_id;
 IF p.audience<>'CLASS' OR p.monitoring_choice<>'ENABLED' OR p.state='DRAFT' THEN RETURN NEW; END IF;
 eid=gen_random_uuid(); happened=clock_timestamp();
 body=jsonb_build_object('eventId',eid,'eventType','ASSESSMENT_ANSWER_PERSISTED','eventVersion',1,
   'occurredAt',happened,'attemptId',a.id,'publicationId',a.publication_id,'classroomId',p.classroom_id,
   'studentId',a.student_id,'attemptStartedAt',a.started_at,'deadlineAt',a.deadline_at,
   'questionNumber',NEW.question_number,'revision',NEW.saved_revision::text);
 EXECUTE format('INSERT INTO %I.assessment_monitoring_outbox(event_id,attempt_id,question_number,answer_revision,payload,created_at) VALUES($1,$2,$3,$4,$5,$6)',TG_TABLE_SCHEMA)
 USING eid,a.id,NEW.question_number,NEW.saved_revision,body,happened;
 RETURN NEW;
END $$;
CREATE TRIGGER assessment_capture_answer_metadata AFTER INSERT OR UPDATE ON attempt_answers
 FOR EACH ROW EXECUTE FUNCTION assessment_capture_answer_metadata();
