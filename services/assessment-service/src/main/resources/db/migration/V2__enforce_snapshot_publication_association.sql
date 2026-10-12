-- Preserve V1 checksums for databases that already applied the unmerged foundation.
-- Run with Publication traffic drained. Lock parent before child; never repair data here.
LOCK TABLE publications, publication_snapshots IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
 IF EXISTS (
  SELECT 1 FROM publication_snapshots s LEFT JOIN publications p ON p.id=s.publication_id
  WHERE p.id IS NULL OR p.state NOT IN ('OPEN','CLOSED') OR p.snapshot_id IS DISTINCT FROM s.publication_id
 ) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Invalid finalized snapshot association; operator review required';
 END IF;
END $$;

-- AFTER ROW constraint events run against final transaction state, not the INSERT-time
-- DRAFT. FOR UPDATE also serializes validation with a concurrent parent edit/delete.
-- Existing parent guards forbid identity/association changes and deletion once OPEN;
-- the existing FK proves the opposite direction, and snapshot guards forbid mutation.
CREATE FUNCTION validate_snapshot_publication_association() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
DECLARE parent_state varchar(6); parent_snapshot uuid;
BEGIN
 -- Qualify the parent using trusted trigger metadata, never the caller's search_path.
 -- EXECUTE INTO sets targets NULL on no row; it does not update PL/pgSQL FOUND.
 EXECUTE format('SELECT state,snapshot_id FROM %I.publications WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA)
 INTO parent_state,parent_snapshot USING NEW.publication_id;
 IF parent_state IS NULL OR parent_state NOT IN ('OPEN','CLOSED') OR parent_snapshot IS DISTINCT FROM NEW.publication_id THEN
  RAISE EXCEPTION USING ERRCODE='23514', CONSTRAINT='snapshot_publication_association',
   MESSAGE='Invalid finalized snapshot association';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER snapshot_publication_association
 AFTER INSERT ON publication_snapshots DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION validate_snapshot_publication_association();
