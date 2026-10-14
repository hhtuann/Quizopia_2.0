# W3-B/W3-C — PostgreSQL persistence and concurrency design

Status: **Leader product policy APPROVED; engineering direction APPROVED WITH CONDITIONS (EC79-01..04); B1/C1/B2 merged and B3 implemented default disabled, pending independent security/runtime review and Leader merge approval; production activation NOT AUTHORIZED.**

Current runtime, exact routes, verification and rollout gates are recorded in the
[W3-B3 implementation evidence](w3b3-closure-notification-implementation.md). Historical baseline inspection and
proposed SQL below describe the contract freeze; executable Flyway V1–V5 and the
implemented service OpenAPI govern the delivered adapters. Accepted product policy
and the indefinite blocked-recipient limitation are unchanged.

Leader-approved policy is [ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md).
W3-A V1/V2 and JdbcPublicationStore already exist; this additive proposal preserves
their snapshot association. See [lifecycle](../specifications/w3-b-attempt-lifecycle-contract.md),
[closure](../specifications/w3-b-closure-notification-contract.md),
[grading](../specifications/w3-c-grading-results-contract.md) and
[HTTP](../specifications/w3b-w3c-http-contract.md). Practice/guest/general inbox,
external email and gradebook events are deferred. External gates: independent
read-only amendment review, implementation review/Leader merge approval, independent
security and real PostgreSQL concurrency/upgrade tests, primary durability,
time synchronization, measured lock/transaction capacity and retention review.

## EC79-03 — Accepted capacity and recovery requirements

The initial 2 MiB request budget and atomic initial closure capture trade-off are
Leader-accepted engineering considerations, not unresolved policy blockers. The
budget is not a permanent Quiz restriction. Verify every accepted delivery
snapshot against complete-answer submission sizes/expansion before accepting
unusable content; explicitly resolve any mismatch, never truncate valid answers.
Oversized rejection must be sanitized and mutation-free; W3-A's documented
handoff limitations remain until separately resolved.

Under the Publication-first protocol, REQUEST captures the complete authoritative
set of then ACTIVE unlimited Attempts atomically. No successful request may omit
an eligible capture member. If capacity, lock/statement or transaction limits
prevent full capture, roll back REQUEST and its membership writes together.
Later bounded notice/finalization batches do not permit partial initial capture.

Future acceptance evidence must include real PostgreSQL concurrency, full-answer
sizing/expansion, bounded finalization batches, multi-instance recovery, retries
after uncertain commit, database failover/acknowledged-write durability, lock and
statement timeouts, and authentication/authorization during recovery. Static
syntax/schema validation does not prove these runtime gates; no benchmark is
claimed. Existing additive migration/legacy/V1/V2 safeguards below remain unchanged.

## Existing constraints and proposed upgrade

V1 has `publications`, `publication_snapshots`, `publication_creations`, positive
duration CHECK, configuration/relational consistency, same-ID snapshot association,
immutable snapshots and a guard that currently **forbids every OPEN state change**.
V2 adds deferred reverse snapshot association, requiring each inserted snapshot's
same-ID parent to be OPEN/CLOSED at commit; it also locks parent FOR UPDATE.
Do not rewrite V1/V2 checksums, remove V2 or weaken either association direction.
A future CLOSE implementation must deliberately replace the V1 guard; adding a
controller alone would fail its current trigger. Snapshot schema/grading version
CHECKs remain 1; no historic regrading/mutation.

Proposed additive sequence (reserve actual version numbers against current branch
at implementation time; these names are documentation only):

| Migration                                | Contents and ordering                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ---------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| V3 — attempt_configuration_compatibility | Drain all Publication writers. Add max_attempts integer NOT NULL DEFAULT 1 CHECK 0..10; closed_at timestamptz nullable. Replace named duration CHECK to allow 0 or 5..240; add duration-0 CLASS-not-ENABLED rule. Replace configuration CHECK with explicit legacy key branch described below, and replace timing/configuration parts of publication guard while preserving old protected fields/V2 and keeping OPEN→CLOSED denied at this stage. No old OPEN configuration/snapshot UPDATE. |
| V4 — attempts_and_atomic_results         | Create Attempt/answer/creation-replay/submitted-answer/result/per-question/audit/operation tables, local FKs/indexes and deferred consistency/immutable guards below. Audit closure columns may be present but their member FK/closure-only CHECK branch is installed in V5 after target tables exist; V4 forbids TEACHER_NOTICE_CUTOFF writes. Initially no business route activation or CLOSE.                                                                                             |
| V5 — closure_notifications               | Create request/member/inbox/publication-operation tables, partial live request index, evidence guards, same-transaction finalization membership/audit rules and closure transition validation. Install deferred audit/member FKs and closure reason checks after both target tables exist; extend Publication guard to permit all-terminal CLOSE and block schedule updates during live workflow. Initially notices/routes disabled.                                                         |

Replace generated V1 constraint names only after inspecting pg_constraint by
expression in migration tests; do not guess a production name. Maintain a checked
migration ledger and schema-only backup before deployment. Validate legacy rows
before changes; stop on invalid evidence with sanitized constraint error, never
repair/drop data automatically. Apply on PostgreSQL 17 with publications locked
before dependent snapshot tables. No executable Flyway file is added here.

Legacy configuration rule: absent `maxAttempts` is valid **only** when relational
max_attempts=1; present value must be a JSON integer 0..10 equal to the column.
JSON null/string/fractional values fail. Future DRAFT create/update explicitly
writes the effective value and column together. OPEN legacy reads project default
1 without injecting a JSON key; later OPEN schedule extension preserves absence
while retaining value 1. OPEN guard freezes both column and configuration fields
other than approved endAt/durationMinutes extensions. An OPEN maxAttempts cannot
be changed even if no Attempt exists. During DRAFT edits a legacy missing key may
be normalized by an ordinary revision-controlled replacement.

The final V5 guard allows only DRAFT→DRAFT/OPEN, OPEN→OPEN/CLOSED, CLOSED→no changes.
V3/V4 retain OPEN→CLOSED rejection until all Attempt/closure tables and guards exist;
functions installed earlier must not query not-yet-created V5 tables.
All existing source/audience/Classroom/monitoring/owner/identity/start/snapshot and
OPEN metadata freezes stay. Positive OPEN duration may only increase within mode;
0 stays 0; existing pinned deadlines never update. Pending closure blocks endAt
or duration change even if increasing; no maxAttempts or window reopening.
CLOSED requires `closed_at` finite and non-null, no ACTIVE Attempts, and no live
unresolved request/member. Existing DRAFT/OPEN rows have null closed_at. Preserve
revision+1 on each actual Publication update. Workflow create/cancel/close increments
Publication revision; per-Attempt notice/progress/worker updates do not, so normal
worker progress does not invalidate Teacher revision unexpectedly.

Mixed versions: drain/block every old Publication writer before V3 and never route
an old parser/guard binary to new duration/configuration. Deploy V3..V5 and compatible
readers with flags off. Rollback blocks business traffic first and retains schema;
never down-migrate away authoritative Attempt/results. Hibernate validates only.

## Concrete proposed schema

All tables belong only to assessment_db. UUID Student/Teacher IDs are opaque
Identity references, **no cross-service FK**. All unspecified defaults are absent;
NOT NULL is explicit below. Every FK has ON DELETE RESTRICT/ON UPDATE RESTRICT
(default PostgreSQL behavior). Database role cannot disable triggers/constraints.
The following DDL is a schema design in Markdown, not an executable migration.

```sql
CREATE DOMAIN assessment_digest AS varchar(64)
  CHECK ((VALUE ~ '^[0-9a-f]{64}$') IS TRUE);
CREATE DOMAIN assessment_revision AS bigint
  CHECK ((VALUE >= 0) IS TRUE);

CREATE TABLE attempts (
  id uuid PRIMARY KEY,
  publication_id uuid NOT NULL REFERENCES publications(id),
  student_id uuid NOT NULL,
  ordinal bigint NOT NULL CHECK ((ordinal >= 1) IS TRUE),
  snapshot_id uuid NOT NULL REFERENCES publication_snapshots(publication_id),
  grading_policy_version integer NOT NULL CHECK ((grading_policy_version = 1) IS TRUE),
  answer_schema_version integer NOT NULL CHECK ((answer_schema_version = 1) IS TRUE),
  state varchar(10) NOT NULL CHECK ((state IN ('ACTIVE','SUBMITTED','ABANDONED')) IS TRUE),
  duration_minutes integer NOT NULL
    CHECK ((duration_minutes = 0 OR duration_minutes BETWEEN 5 AND 240) IS TRUE),
  started_at timestamptz NOT NULL CHECK ((isfinite(started_at)) IS TRUE),
  deadline_at timestamptz,
  revision assessment_revision NOT NULL DEFAULT 0,
  submitted_answers_id uuid,
  result_id uuid,
  terminal_reason varchar(24),
  finalized_at timestamptz,
  UNIQUE (publication_id, student_id, ordinal),
  UNIQUE (id, publication_id, student_id),
  UNIQUE (id, snapshot_id, grading_policy_version),
  CHECK ((snapshot_id = publication_id) IS TRUE),
  CHECK (((duration_minutes = 0 AND deadline_at IS NULL) OR
    (duration_minutes > 0 AND deadline_at IS NOT NULL AND isfinite(deadline_at)
      AND deadline_at = started_at + duration_minutes * interval '1 minute')) IS TRUE),
  CHECK ((
    (state = 'ACTIVE' AND terminal_reason IS NULL AND finalized_at IS NULL
      AND submitted_answers_id IS NULL AND result_id IS NULL) OR
    (state = 'ABANDONED' AND terminal_reason = 'STUDENT_ABANDON'
      AND finalized_at IS NOT NULL AND isfinite(finalized_at)
      AND finalized_at >= started_at
      AND submitted_answers_id IS NULL AND result_id IS NULL) OR
    (state = 'SUBMITTED' AND terminal_reason IN
      ('MANUAL_SUBMIT','BOUNDED_TIMEOUT','TEACHER_NOTICE_CUTOFF')
      AND finalized_at IS NOT NULL AND isfinite(finalized_at)
      AND finalized_at >= started_at
      AND submitted_answers_id IS NOT NULL AND result_id IS NOT NULL)
  ) IS TRUE)
);
CREATE UNIQUE INDEX one_active_attempt_per_student
  ON attempts(publication_id, student_id) WHERE state = 'ACTIVE';
CREATE INDEX bounded_attempts_due
  ON attempts(deadline_at, publication_id, id)
  WHERE state = 'ACTIVE' AND deadline_at IS NOT NULL;
CREATE INDEX active_publication_attempts
  ON attempts(publication_id, id) WHERE state = 'ACTIVE';
CREATE INDEX publication_attempt_page ON attempts(publication_id, id);
-- Unique publication/student/ordinal index also supports latest ordinal lookup.

CREATE TABLE attempt_answers (
  attempt_id uuid NOT NULL REFERENCES attempts(id),
  question_number integer NOT NULL CHECK ((question_number >= 1) IS TRUE),
  answer jsonb NOT NULL CHECK ((jsonb_typeof(answer) = 'object') IS TRUE),
  saved_revision assessment_revision NOT NULL CHECK ((saved_revision > 0) IS TRUE),
  saved_at timestamptz NOT NULL CHECK ((isfinite(saved_at)) IS TRUE),
  PRIMARY KEY (attempt_id, question_number)
);

CREATE TABLE attempt_creation_operations (
  publication_id uuid NOT NULL,
  student_id uuid NOT NULL,
  key_digest assessment_digest NOT NULL,
  action varchar(6) NOT NULL CHECK ((action IN ('START','RETAKE')) IS TRUE),
  request_digest assessment_digest NOT NULL,
  attempt_id uuid NOT NULL,
  created_at timestamptz NOT NULL CHECK ((isfinite(created_at)) IS TRUE),
  PRIMARY KEY (publication_id, student_id, key_digest),
  FOREIGN KEY (attempt_id, publication_id, student_id)
    REFERENCES attempts(id, publication_id, student_id)
);

CREATE TABLE submitted_answers (
  id uuid PRIMARY KEY,
  attempt_id uuid NOT NULL UNIQUE REFERENCES attempts(id),
  answer_schema_version integer NOT NULL CHECK ((answer_schema_version = 1) IS TRUE),
  canonical_answers jsonb NOT NULL CHECK ((jsonb_typeof(canonical_answers) = 'array') IS TRUE),
  answer_digest assessment_digest NOT NULL,
  created_at timestamptz NOT NULL CHECK ((isfinite(created_at)) IS TRUE),
  UNIQUE (id, attempt_id)
);

CREATE TABLE grading_results (
  id uuid PRIMARY KEY,
  attempt_id uuid NOT NULL UNIQUE,
  snapshot_id uuid NOT NULL,
  grading_policy_version integer NOT NULL CHECK ((grading_policy_version = 1) IS TRUE),
  submitted_answers_id uuid NOT NULL,
  earned_weight numeric NOT NULL,
  possible_weight numeric NOT NULL,
  graded_at timestamptz NOT NULL CHECK ((isfinite(graded_at)) IS TRUE),
  UNIQUE (id, attempt_id),
  FOREIGN KEY (attempt_id, snapshot_id, grading_policy_version)
    REFERENCES attempts(id, snapshot_id, grading_policy_version),
  FOREIGN KEY (submitted_answers_id, attempt_id)
    REFERENCES submitted_answers(id, attempt_id),
  CHECK ((possible_weight > 0 AND possible_weight < 'Infinity'::numeric
    AND possible_weight = trunc(possible_weight)) IS TRUE),
  CHECK ((earned_weight >= 0 AND earned_weight <= possible_weight
    AND earned_weight * 4 = trunc(earned_weight * 4)) IS TRUE)
);
ALTER TABLE attempts ADD FOREIGN KEY (submitted_answers_id, id)
  REFERENCES submitted_answers(id, attempt_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE attempts ADD FOREIGN KEY (result_id, id)
  REFERENCES grading_results(id, attempt_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX grading_result_best
  ON grading_results(earned_weight DESC, attempt_id);

CREATE TABLE grading_question_results (
  result_id uuid NOT NULL REFERENCES grading_results(id),
  question_number integer NOT NULL CHECK ((question_number >= 1) IS TRUE),
  earned_weight numeric NOT NULL
    CHECK ((earned_weight IN (0, 0.25, 0.5, 0.75, 1)) IS TRUE),
  possible_weight numeric NOT NULL CHECK ((possible_weight = 1) IS TRUE),
  PRIMARY KEY (result_id, question_number)
);

CREATE TABLE attempt_operations (
  attempt_id uuid NOT NULL REFERENCES attempts(id),
  key_digest assessment_digest NOT NULL,
  action varchar(7) NOT NULL CHECK ((action IN ('SAVE','SUBMIT','ABANDON')) IS TRUE),
  request_digest assessment_digest NOT NULL,
  question_number integer,
  accepted_revision assessment_revision NOT NULL CHECK ((accepted_revision > 0) IS TRUE),
  acknowledged_at timestamptz NOT NULL CHECK ((isfinite(acknowledged_at)) IS TRUE),
  response_status integer NOT NULL CHECK ((response_status = 200) IS TRUE),
  response_body jsonb NOT NULL CHECK ((jsonb_typeof(response_body) = 'object') IS TRUE),
  PRIMARY KEY (attempt_id, key_digest),
  CHECK (((action = 'SAVE' AND question_number IS NOT NULL AND question_number >= 1)
    OR (action <> 'SAVE' AND question_number IS NULL)) IS TRUE)
);

CREATE TABLE closure_requests (
  id uuid PRIMARY KEY,
  publication_id uuid NOT NULL REFERENCES publications(id),
  requesting_teacher_id uuid NOT NULL,
  requested_at timestamptz NOT NULL CHECK ((isfinite(requested_at)) IS TRUE),
  state varchar(14) NOT NULL CHECK ((state IN
    ('PENDING','FINALIZING','CANCELLED','COMPLETED','RESOLVED_EARLY')) IS TRUE),
  finalization_started_at timestamptz,
  resolved_at timestamptz,
  UNIQUE (id, publication_id),
  CHECK ((finalization_started_at IS NULL OR
    (isfinite(finalization_started_at) AND finalization_started_at >= requested_at)) IS TRUE),
  CHECK (((state = 'PENDING' AND finalization_started_at IS NULL AND resolved_at IS NULL)
    OR (state = 'FINALIZING' AND finalization_started_at IS NOT NULL AND resolved_at IS NULL)
    OR (state = 'CANCELLED' AND finalization_started_at IS NULL AND resolved_at IS NOT NULL)
    OR (state IN ('COMPLETED','RESOLVED_EARLY') AND resolved_at IS NOT NULL)) IS TRUE),
  CHECK ((resolved_at IS NULL OR (isfinite(resolved_at) AND resolved_at >= requested_at)) IS TRUE)
);
CREATE UNIQUE INDEX one_live_closure_request
  ON closure_requests(publication_id) WHERE state IN ('PENDING','FINALIZING');
CREATE INDEX closure_request_history ON closure_requests(publication_id, requested_at DESC, id DESC);

CREATE TABLE closure_request_members (
  closure_request_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  publication_id uuid NOT NULL,
  student_id uuid NOT NULL,
  state varchar(20) NOT NULL CHECK ((state IN
    ('NOTICE_PENDING','NOTICE_PUBLISHED','NOTICE_CONFIRMED','FINALIZED',
     'RESOLVED_TERMINAL','RESOLVED_CANCELLED')) IS TRUE),
  notification_id uuid,
  confirmed_notice_at timestamptz,
  closure_cutoff_at timestamptz,
  accessibility_version integer,
  resolved_at timestamptz,
  PRIMARY KEY (closure_request_id, attempt_id),
  UNIQUE (closure_request_id, attempt_id, student_id),
  FOREIGN KEY (closure_request_id, publication_id)
    REFERENCES closure_requests(id, publication_id),
  FOREIGN KEY (attempt_id, publication_id, student_id)
    REFERENCES attempts(id, publication_id, student_id),
  CHECK (((confirmed_notice_at IS NULL AND closure_cutoff_at IS NULL
      AND accessibility_version IS NULL) OR
    (notification_id IS NOT NULL AND confirmed_notice_at IS NOT NULL
      AND isfinite(confirmed_notice_at) AND closure_cutoff_at IS NOT NULL
      AND isfinite(closure_cutoff_at) AND accessibility_version = 1
      AND closure_cutoff_at = confirmed_notice_at + interval '48 hours')) IS TRUE),
  CHECK (((state IN ('NOTICE_PENDING','NOTICE_PUBLISHED','NOTICE_CONFIRMED')
      AND resolved_at IS NULL) OR
    (state IN ('FINALIZED','RESOLVED_TERMINAL','RESOLVED_CANCELLED')
      AND resolved_at IS NOT NULL AND isfinite(resolved_at))) IS TRUE),
  CHECK ((state <> 'NOTICE_PENDING' OR notification_id IS NULL) IS TRUE),
  CHECK ((state <> 'NOTICE_PUBLISHED' OR notification_id IS NOT NULL) IS TRUE),
  CHECK ((state NOT IN ('NOTICE_CONFIRMED','FINALIZED') OR confirmed_notice_at IS NOT NULL) IS TRUE)
);
CREATE INDEX closure_member_due ON closure_request_members
  (closure_cutoff_at, publication_id, closure_request_id, attempt_id)
  WHERE state = 'NOTICE_CONFIRMED';
CREATE INDEX closure_member_pending_notice ON closure_request_members
  (publication_id, closure_request_id, attempt_id)
  WHERE state IN ('NOTICE_PENDING','NOTICE_PUBLISHED');
CREATE INDEX closure_member_attempt ON closure_request_members(attempt_id, closure_request_id);

CREATE TABLE assessment_notifications (
  id uuid PRIMARY KEY,
  closure_request_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  recipient_id uuid NOT NULL,
  content_version integer NOT NULL CHECK ((content_version = 1) IS TRUE),
  publication_title text NOT NULL CHECK ((length(publication_title) BETWEEN 1 AND 300) IS TRUE),
  state varchar(9) NOT NULL CHECK ((state = 'PUBLISHED') IS TRUE),
  published_at timestamptz NOT NULL CHECK ((isfinite(published_at)) IS TRUE),
  publish_transaction_id xid8 NOT NULL DEFAULT pg_current_xact_id(),
  UNIQUE (closure_request_id, attempt_id),
  UNIQUE (id, closure_request_id, attempt_id, recipient_id),
  FOREIGN KEY (closure_request_id, attempt_id, recipient_id)
    REFERENCES closure_request_members(closure_request_id, attempt_id, student_id)
);
ALTER TABLE closure_request_members ADD FOREIGN KEY
  (notification_id, closure_request_id, attempt_id, student_id)
  REFERENCES assessment_notifications(id, closure_request_id, attempt_id, recipient_id)
  DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX notification_inbox ON assessment_notifications(recipient_id, published_at DESC, id DESC);

CREATE TABLE attempt_finalization_audit (
  attempt_id uuid PRIMARY KEY REFERENCES attempts(id),
  publication_id uuid NOT NULL REFERENCES publications(id),
  reason varchar(24) NOT NULL CHECK ((reason IN
    ('MANUAL_SUBMIT','BOUNDED_TIMEOUT','TEACHER_NOTICE_CUTOFF','STUDENT_ABANDON')) IS TRUE),
  actor_kind varchar(6) NOT NULL CHECK ((actor_kind IN ('USER','SYSTEM')) IS TRUE),
  actor_id varchar(128) NOT NULL CHECK ((length(actor_id) > 0) IS TRUE),
  finalized_at timestamptz NOT NULL CHECK ((isfinite(finalized_at)) IS TRUE),
  grading_policy_version integer NOT NULL CHECK ((grading_policy_version = 1) IS TRUE),
  result_id uuid,
  closure_request_id uuid,
  requesting_teacher_id uuid,
  notification_id uuid,
  confirmed_notice_at timestamptz,
  closure_cutoff_at timestamptz,
  accessibility_version integer,
  FOREIGN KEY (result_id, attempt_id) REFERENCES grading_results(id, attempt_id)
    DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY (closure_request_id, attempt_id)
    REFERENCES closure_request_members(closure_request_id, attempt_id),
  CHECK (((reason = 'STUDENT_ABANDON' AND result_id IS NULL)
    OR (reason <> 'STUDENT_ABANDON' AND result_id IS NOT NULL)) IS TRUE),
  CHECK (((reason = 'TEACHER_NOTICE_CUTOFF' AND closure_request_id IS NOT NULL
      AND requesting_teacher_id IS NOT NULL AND notification_id IS NOT NULL
      AND confirmed_notice_at IS NOT NULL AND closure_cutoff_at IS NOT NULL
      AND accessibility_version = 1 AND isfinite(confirmed_notice_at)
      AND closure_cutoff_at = confirmed_notice_at + interval '48 hours'
      AND finalized_at >= closure_cutoff_at)
    OR (reason <> 'TEACHER_NOTICE_CUTOFF' AND closure_request_id IS NULL
      AND requesting_teacher_id IS NULL AND notification_id IS NULL
      AND confirmed_notice_at IS NULL AND closure_cutoff_at IS NULL
      AND accessibility_version IS NULL)) IS TRUE)
);
CREATE INDEX finalization_audit_publication ON attempt_finalization_audit(publication_id, finalized_at, attempt_id);

CREATE TABLE publication_operations (
  publication_id uuid NOT NULL REFERENCES publications(id),
  actor_id uuid NOT NULL,
  key_digest assessment_digest NOT NULL,
  action varchar(7) NOT NULL CHECK ((action IN ('CLOSE','REQUEST','CANCEL','COMPLETE')) IS TRUE),
  request_digest assessment_digest NOT NULL,
  closure_request_id uuid,
  state varchar(11) NOT NULL CHECK ((state IN ('IN_PROGRESS','ACKNOWLEDGED')) IS TRUE),
  processed_units integer NOT NULL DEFAULT 0 CHECK ((processed_units BETWEEN 0 AND 50) IS TRUE),
  response_status integer,
  response_body jsonb,
  created_at timestamptz NOT NULL CHECK ((isfinite(created_at)) IS TRUE),
  acknowledged_at timestamptz,
  PRIMARY KEY (publication_id, actor_id, key_digest),
  FOREIGN KEY (closure_request_id, publication_id) REFERENCES closure_requests(id, publication_id),
  CHECK (((state = 'IN_PROGRESS' AND action = 'COMPLETE' AND response_status IS NULL
      AND response_body IS NULL AND acknowledged_at IS NULL) OR
    (state = 'ACKNOWLEDGED' AND response_status IN (200,201,202)
      AND response_body IS NOT NULL AND jsonb_typeof(response_body) = 'object'
      AND acknowledged_at IS NOT NULL AND isfinite(acknowledged_at))) IS TRUE),
  CHECK (((action = 'CLOSE' AND closure_request_id IS NULL)
    OR (action <> 'CLOSE' AND closure_request_id IS NOT NULL)) IS TRUE)
);
```

Use `CHECK (... IS TRUE)` for composite JSON and cross-null Boolean expressions
when translating design into migrations. SQL CHECK accepts UNKNOWN: null evidence
must never slip through an OR branch. In particular enforce the closure evidence
and terminal-state blocks as IS TRUE, and verify rejecting malformed nulls in V4/V5
tests. Scalar nullable columns are intentionally optional only where above permits.
The explicit finite possible bound and earned<=possible prevent special numeric
values from entering results. There is no floating
score or arbitrary fixed numeric scale.

## Required guards beyond column constraints

The migration implementation must install the following named guards, not leave
these invariants only as prose/application conventions. Use schema-qualified
objects/trusted search_path (as V2 does), ordinary runtime DML permissions and
immutable checks. The app obtains parent locks before issuing child writes.

| Guard                                                                                                | Exact condition at write/commit                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| attempt_insert_guard BEFORE INSERT                                                                   | Lock Publication; state OPEN, no live closure, snapshot_id=parent.snapshot_id=id, pinned grading version equals immutable snapshot; Student/ordinal positive. Under parent lock ordinal=max prior ordinal+1, count below finite max or unlimited, no ACTIVE sibling, startAt<=started_at<endAt, pinned duration equals current configuration. FK prevents foreign snapshot binding.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| attempt_update_guard BEFORE UPDATE/DELETE                                                            | No DELETE. Identity/owner/ordinal/snapshot/policy/duration/start/deadline never change. OLD terminal rejects all UPDATE including no-op. OLD ACTIVE permits revision+1 SAVE (still ACTIVE) or one terminalization. No rewrite of prior terminal reason/result.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| answer_write_guard BEFORE INSERT/UPDATE/DELETE                                                       | Parent Attempt ACTIVE, question exists with matching typed shape in pinned snapshot; saved_revision equals resulting Attempt revision and saved_at is authoritative mutation sample; reject arbitrary answer DELETE (clear through typed unanswered). Terminal parent prevents later answers. Deferred validation permits coherent final answer replacement followed by terminal update in one transaction.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| terminal_consistency DEFERRABLE AFTER affected Attempt/answer/submitted/result/question/audit writes | Final ACTIVE has no result/submission/audit. ABANDONED has exactly one matching ungraded audit, no result/submission. SUBMITTED has exactly one same-Attempt submitted document and result, matching snapshot/policy and audit/reason/time. Complete one answer per snapshot question, exact digest, totals=sum question rows, possible=question count, question numbers/types complete; immutable submitted document equals final answer state including never-saved unanswered. Audit Publication/Teacher/notice/result matches real rows; timeout finalized_at>=deadline, closure time>=cutoff, correct member/cutoff, and request fenced before finalization. Deferred validation allows the same transaction to resolve the request and close after its last finalized member; it must not demand that the request still be live at final commit.                                                    |
| immutable_terminal_artifacts BEFORE UPDATE/DELETE                                                    | submitted_answers, grading_results, grading_question_results, attempt_finalization_audit, creation receipts, accepted attempt receipts and published notification content reject UPDATE/DELETE. Receipt/result data cannot be overwritten on retry.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| closure_request_guard                                                                                | Initial PENDING, Teacher=Publication owner, requested_at>=endAt, complete captured ACTIVE unlimited member set in request transaction. Only PENDING→CANCELLED/FINALIZING/COMPLETED/RESOLVED_EARLY and FINALIZING→COMPLETED/RESOLVED_EARLY. No identity/requested_at rewrite or deletion; finalization_started_at set once with first closure audit and never cleared. Resolution immutable.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| closure_member_guard                                                                                 | Member exactly same Publication/Student and unlimited Attempt active at request capture. No identity/delete. Notification assignment once; published row actually committed before separate confirmation: insert guard forces publish_transaction_id=pg_current_xact_id(); confirmation requires a visible PUBLISHED row whose publish_transaction_id differs from pg_current_xact_id(). With READ COMMITTED, this excludes own uncommitted insertion and a different transaction's uncommitted row. confirmed_notice_at/cutoff/accessibility version set together once and thereafter frozen; confirmed>=published_at and>=requested_at; code additionally verifies fresh Identity recipient eligibility before confirmation. FINALIZED requires same-transaction matching closure audit/result. Resolved cancellation cannot return to notice state. Cancellation preserves already-confirmed evidence. |
| publication_close_guard                                                                              | Parent lock; no ACTIVE Attempt and no unresolved live request/member at deferred commit. closed_at authoritative; request resolution and receipt/audit committed with CLOSED. Pending freeze enforced on schedule writes. V2 association remains intact for CLOSED.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| publication_operation_guard                                                                          | Key/request/action/actor/request identity immutable. COMPLETE processed_units grows by one per committed finalization/reconciliation unit up to chosen budget<=50; acknowledged status/body/time set once. ACKNOWLEDGED cannot change/delete. Other actions insert only ACKNOWLEDGED in their successful transaction.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |

Some guards inspect related rows via deferred validation; immediate guards must
allow the intermediate sequence inside an atomic finalization, not demand terminal
result before insertion or forbid ACTIVE answer writes that become terminal at
commit. SQL does not itself authenticate a Teacher or prove endpoint deployment;
those remain owning-service code/readiness requirements. Migrations must exercise
raw DML bypass attempts as well as supported application transactions. Privileged
operators disabling triggers are outside ordinary DML proof and require separate
access/audit controls. RESTRICT plus guards mean no cascade cleanup; future reviewed
delete/anonymization migration/process must preserve legal evidence and retry rules.

## Queries, entitlement and exact aggregation

Latest Attempt: `(publication_id,student_id)` ORDER BY ordinal DESC LIMIT 1.
ACTIVE lookup uses the partial unique index. Count all Attempts under parent lock;
ordinal=max+1 and insertion commit together, with no Redis counters/sequences.
Empty-set races serialize on existing Publication even before any Student row
exists. Publication lock is intentionally coarse for the first delivery; capacity
must be measured before optimizing to a more complex locking scheme.

Best result retrieval:

```sql
SELECT r.id, a.id AS attempt_id, a.ordinal, r.earned_weight, r.possible_weight
FROM attempts a JOIN grading_results r ON r.attempt_id = a.id
WHERE a.publication_id = :publication_id AND a.student_id = :student_id
  AND a.state = 'SUBMITTED'
ORDER BY r.earned_weight DESC, a.ordinal ASC, r.id ASC
LIMIT 1;
```

The publication/student/ordinal index bounds candidates; possible weight and policy
are pinned equal. No ABANDON zero enters aggregation. Teacher result pages order
Attempt UUID ascending per Publication, supported by `publication_attempt_page`. Notification pages order published_at DESC,id DESC
with recipient filter; request member probes use their primary key and due/notice
indexes. `closure_request_history` supports restricted history/recovery. No full Student count in ordinary CLOSE conflict.

## Global transaction and lock order

Use READ COMMITTED, short database transactions and explicit row locks. Remote
admission/Classroom calls happen first, never while holding database locks.
All business writers, including schedule extension and workers, use:

1. Publication FOR UPDATE (one Publication per transaction).
2. Live/referenced closure request(s), UUID ascending, if relevant.
3. Attempt(s), UUID ascending; prefer one per transaction for grading.
4. Closure member, then notification, then child answers/submission/result/audit,
   then operation/progress receipts. Unique creation keys are checked under parent.

Never lock a due Attempt/notification/receipt first and then Publication. Candidate
worker scans are unlocked hints; select parent FOR UPDATE SKIP LOCKED, re-read
candidate eligibility before locking child. No long-lived lease/network call
inside a unit; PostgreSQL locks and rechecks are sufficient multi-instance fencing.
For batch operations reacquire order every unit. Ordinary read-only result/list
queries use coherent snapshots and no mutation; Attempt resume may reconcile as
documented and therefore uses writer order.

After all required locks and input/replay checks, sample database `clock_timestamp()`
immediately before eligibility/answer writes. It is wall-clock current time,
unlike transaction-start CURRENT_TIMESTAMP. Compare `now < deadline/cutoff` for new
Student mutation and `now >= deadline/cutoff` for finalization. Time comparisons
and persisted decision timestamp use the same sample. Grading after a successful
sample may complete after cutoff without changing eligibility. Clock health/UTC
sessions/fenced primary/failover checks are operational release gates; browser
clock/request arrival never substitutes. See PostgreSQL 17
[time functions](https://www.postgresql.org/docs/17/functions-datetime.html) and
[locking/deadlock guidance](https://www.postgresql.org/docs/17/explicit-locking.html).
The committed-notification guard uses PostgreSQL 17
[transaction-ID functions](https://www.postgresql.org/docs/17/functions-info.html#FUNCTIONS-PG-SNAPSHOT),
not optional commit-timestamp tracking or a browser read receipt.

Carry successful matching receipt replays through current authority/ownership
checks but do not re-run time/revision eligibility or grade again. Malformed input
400 is mutation-free. Reconciliation resulting in a 409 must commit before emitting
the conflict, using a typed committed outcome or separate reconciliation transaction;
never throw a rollback-triggering business exception after a successful finalization.
Any persistence/grading failure rolls back its whole unit and returns sanitized 503.

DB deadlock 40P01/serialization 40001: at most one internal retry of the **entire**
unit after rollback, using same operation identity, new lock/time sample and
rechecked authority/token freshness. If dependency evidence expired, refresh it
outside locks. Lock/statement/acquisition timeout: 503 PERSISTENCE_UNAVAILABLE,
no automatic request/semantic retry or new key. Do not retry an uncertain COMMIT
blindly; re-read durable key/result. Existing W3-A 3s lock/statement and 5s
transaction limits are initial proposed ceilings for each short unit, not a
measured throughput SLA. Budget/timeout failures cannot fabricate success.

## Operation conflict matrix

| Race                                      | Serialized outcome and required proof                                                                                                                                                                         |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| START / START                             | Same key selects same Attempt; different keys return one ACTIVE; ordinal/entitlement increment once.                                                                                                          |
| START / RETAKE                            | START returns selected latest state; RETAKE only after terminal prior work; no implicit retake. Ordering determines whether START sees old terminal or new ACTIVE.                                            |
| RETAKE / RETAKE                           | Same key same Attempt; distinct key second sees ACTIVE conflict; count/unique index prevent over-consumption.                                                                                                 |
| START or RETAKE / CLOSE                   | Parent winner determines eligibility. CLOSE sees created ACTIVE and conflicts, or CLOSED prevents creation.                                                                                                   |
| START or RETAKE / pending closure         | REQUEST parent lock commits freeze+complete electorate before any creation; later new creation conflicts. START resume of existing remains allowed.                                                           |
| SAVE / SAVE                               | Same receipt/payload one ack; independent same expected revision one winner/one REVISION_CONFLICT, even different questions.                                                                                  |
| SAVE / SUBMIT                             | First mutation changes revision. Submit with stale revision conflicts; winning submit freezes complete answers and later SAVE conflicts.                                                                      |
| SAVE / ABANDON                            | Save first invalidates stale abandon revision; abandon first prevents later save.                                                                                                                             |
| SUBMIT / matching SUBMIT                  | Unique receipt/result and parent/Attempt lock; second returns stored result without grade. Changed payload 409.                                                                                               |
| SUBMIT / bounded TIMEOUT                  | Time sampled after lock: pre-deadline submit wins, otherwise timeout uses committed answers; waiting HTTP arrival gives no priority.                                                                          |
| SUBMIT / closure cutoff                   | Strict pre-cutoff submit wins; cutoff-or-later reconciliation gives closure result and conflict. Cancelled request has no cutoff authority.                                                                   |
| ABANDON / closure finalization            | Pre-cutoff abandon commits ungraded; at/after cutoff forced grade wins; never both or a zero grade for abandon.                                                                                               |
| REQUEST / schedule extension              | Both parent/revision serialized. Extension first can make request before new endAt invalid; request first freezes extension.                                                                                  |
| CANCEL / finalization worker              | Both parent/request locks; CANCEL first fences stale candidate. First committed cutoff finalization changes FINALIZING and forbids CANCEL.                                                                    |
| COMPLETE / finalization worker            | Each rechecks same parent/request/Attempt. Already terminal skips grade; COMPLETE alone may close after final authoritative checks.                                                                           |
| ordinary early CLOSE / stale workflow     | Reconcile bounded, all terminal, resolve workflow/members and close under parent. Stale worker finds resolved/CLOSED and no-ops.                                                                              |
| COMPLETE / COMPLETE retry                 | Reserve durable operation/digest under parent; per-unit processed budget progress commits with work. Replay acknowledged response; unfinished command resumes without duplicated result or >50 units per key. |
| crash / partial batches                   | Committed units survive; uncommitted unit rolls back; per-key progress/member/result drive restart. No CLOSED without successful final Teacher command.                                                       |
| confirmation / CANCEL                     | Publish/confirm lock parent/request; CANCEL first prohibits confirmation, confirmation first retains evidence but subsequent CANCEL makes it nonactionable.                                                   |
| Student terminalization / notice creation | Both parent/request/Attempt locks; terminal first resolves member without unnecessary notice; notice first does not prevent ordinary pre-cutoff action.                                                       |

COMPLETE reserves publication_operations IN_PROGRESS before batching, including
request digest/expected revision and processed budget. Concurrent retries see the
same operation and coordinate each unit under parent lock; no separate in-memory
owner. Each committed unit increments processed_units atomically. After budget or
eligibility examination, write immutable 200/202 acknowledgment. A 409 NOT_READY
leaves the digest reservation/progress durable but no successful acknowledgment;
a later retry with same payload can continue. A distinct later COMPLETE uses a
new key. Recheck fresh Teacher authority for every HTTP continuation and before
the final CLOSED transaction; background helpers may finalize but cannot borrow
stored Teacher credentials to close. Unknown client COMMIT outcomes recover by
key/current workflow reads. API replay bodies/status are fixed after acknowledgment,
while GET workflow reports current progress independently.
