-- FB-015 PR B (ADR-018 Phase A steps 2-3): durable quiz-ID revocation fence.
-- Row presence means revoked; no lifecycle enum change and no cross-service FK:
-- quiz_id is an opaque reference to the Quiz Service's own authoritative record.
-- Default OFF via quizopia.deletion.enabled; with an empty table every existing
-- route behaves exactly as before (the gate always runs and finds nothing).
CREATE TABLE quiz_revocation_fences (
  quiz_id uuid PRIMARY KEY,
  revoked_at timestamptz NOT NULL CHECK (isfinite(revoked_at)),
  operation_id uuid NOT NULL,
  source varchar(10) NOT NULL CHECK (source IN ('COMMAND','RECONCILE')),
  ack_at timestamptz,
  CHECK (ack_at IS NULL OR (isfinite(ack_at) AND ack_at >= revoked_at))
);
CREATE INDEX quiz_revocation_fences_operation_idx ON quiz_revocation_fences(operation_id);

-- ADR-009 inbox for durable deletion commands: dedup by stable operation id and
-- safe redelivery. Fence application and inbox recording share one transaction.
CREATE TABLE deletion_command_inbox (
  id uuid PRIMARY KEY,
  operation_id uuid NOT NULL,
  quiz_id uuid NOT NULL,
  received_at timestamptz NOT NULL CHECK (isfinite(received_at)),
  processed_at timestamptz,
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error text,
  UNIQUE (operation_id),
  CHECK (processed_at IS NULL OR (isfinite(processed_at) AND processed_at >= received_at))
);
CREATE INDEX deletion_command_inbox_unprocessed_idx
  ON deletion_command_inbox(received_at) WHERE processed_at IS NULL;
