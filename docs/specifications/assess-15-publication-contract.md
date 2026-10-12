# ASSESS-15 — bounded Publication backend contract

Status: **Implemented engineering proposal for Leader review**, under the accelerated
backend assignment. LD-01..05 and ASSESS-01..04 are approved product policy; the
new routes, scopes, DTOs and persistence choices below are engineering choices
submitted through this PR, not a claim of prior Leader approval.

Baseline: `45bf80fa8ad8a4498b800648138a1af4a60f7839` (Identity PR #75 and
Gateway/downstream PR #76 merged). Historical proposed inventories remain context.
This assignment explicitly authorizes the combined backend implementation after
writing this contract. No frontend, Attempt, grading or monitoring runtime is added.

## Internal source authority

All operations are bodyless POST, accept no query/cookies, require exactly one
SERVICE Authorization and one sensitive compact `X-Quizopia-User-Evidence` header.
Each server independently verifies issuer, RS256 signature, timestamps and ADR-014
claims, exact configured Assessment client, scope, initiating USER role, fresh
Identity admission and resource authority. Unknown internal methods/paths deny.
They are hidden from browser Swagger and never publicly Gateway-routed.

| Server    | Exact path                                                                  | Scope                              | USER/resource authority                                                 |
| --------- | --------------------------------------------------------------------------- | ---------------------------------- | ----------------------------------------------------------------------- |
| Quiz      | `/internal/quiz-versions/{quizId}/{quizVersionId}/{versionNumber}/snapshot` | `quiz.version-snapshot.read`       | Current/issued TEACHER; source Quiz owner; exact identity triple        |
| Classroom | `/internal/classrooms/{classroomId}/teacher-authority`                      | `classroom.teacher-authority.read` | Current/issued TEACHER; Classroom owner                                 |
| Classroom | `/internal/classrooms/{classroomId}/student-membership`                     | `classroom.membership.read`        | Current/issued STUDENT; persisted membership for evidence-derived actor |

Resource IDs use canonical lowercase UUIDs, version numbers 1..999999999.
Teacher/member confirmation returns only classroomId and actorId. Missing,
other-owner and nonmember resources use indistinguishable 404. Pending invitations
are not memberships. No caller-supplied actor/owner/student ID grants authority.
Quiz snapshot schemaVersion 1 contains the exact identity triple, title/description,
sourceSnapshot, structuredContent, contentSchemaVersion 1 and source createdAt.
Question order, A-D order, Markdown, correctness, numeric strings (including zeros)
and explanations are preserved. It is answer-bearing private content.

Fixed internal errors: 400 INVALID_INTERNAL_REQUEST, 401
SERVICE_AUTHENTICATION_INVALID or USER_EVIDENCE_INVALID, 403 SERVICE_CALLER_FORBIDDEN
or USER_ROLE_FORBIDDEN, 404 SOURCE_NOT_ACCESSIBLE / CLASSROOM_NOT_ACCESSIBLE, 503
AUTHORITY_UNAVAILABLE. Every response is no-store. Source SERVICE/integration
failures become sanitized dependency 503 at Assessment; only independently invalid
initiating USER evidence becomes public 401. No credentials/bodies enter errors.

## Public Assessment operations

Namespace remains `/api/assessments/publications`. All exact routes below require
independent JWT verification and fresh LD-05 admission at Gateway and Assessment.
Other methods, aliases, nested/encoded variants and future paths remain closed.

| Method | Suffix                      | Required USER role | Contract                                                        |
| ------ | --------------------------- | ------------------ | --------------------------------------------------------------- |
| POST   | base                        | TEACHER            | Create DRAFT; required `Idempotency-Key`                        |
| GET    | `/{publicationId}`          | TEACHER            | Owner-safe metadata, including owner-only PUBLIC locator        |
| PUT    | `/{publicationId}`          | TEACHER            | Full DRAFT configuration replacement with expectedRevision      |
| POST   | `/{publicationId}/open`     | TEACHER            | expectedRevision; atomic snapshot + OPEN                        |
| PATCH  | `/{publicationId}/schedule` | TEACHER            | expectedRevision, endAt, durationMinutes; OPEN extensions only  |
| POST   | `/entry`                    | STUDENT            | PUBLIC locator in JSON body; answer-free OPEN metadata          |
| POST   | `/{publicationId}/entry`    | STUDENT            | CLASS only; authoritative membership; answer-free OPEN metadata |

Owner is always the verified USER subject. Missing and other-owner Publications
use the same 404. No list API is introduced in this increment; no unbounded list
or public directory exists. CLOSE/Attempt/result/review routes stay denied.

Full DRAFT configuration: title (1..300 nonblank characters), optional description
(max 5000), quizId, quizVersionId, versionNumber, audience CLASS/PUBLIC, nullable
classroomId, startAt, endAt, durationMinutes and monitoringChoice. Updates also
require nonnegative expectedRevision. Unknown fields are rejected. No owner field.
Instants use ISO-8601 with an explicit UTC offset and at most PostgreSQL microsecond
precision; startAt < endAt, duration integer 5..240. Draft schedules may be past:
OPEN and scheduled start eligibility remain separate. No automatic close at endAt.

CLASS requires exactly one Classroom, checked against initiating Teacher authority
on create/update/open. Monitoring is explicitly UNCONFIGURED, ENABLED or DISABLED;
UNCONFIGURED is allowed only in DRAFT and cannot OPEN. ENABLED means only the
approved activity/camera bundle, whose runtime/privacy gates remain separate.
PUBLIC requires no Classroom and monitoring DISABLED.

Source access is verified at create/update and again at OPEN through the exact
Quiz internal handoff. Failures commit no DRAFT edit or OPEN. Ordinary Teacher
metadata contains no source, questions, correctness, explanations or grading keys.
Student metadata is a separate projection: publicationId, title, description,
audience, startAt, endAt, durationMinutes, monitoringRequired and windowStatus.
It never includes source identity, owner, locator or snapshot. DRAFT is undiscoverable.
OPEN metadata may report BEFORE_START, AVAILABLE or WINDOW_ENDED using server time;
this is discovery, not an Attempt authorization assertion. W3-B must recheck
OPEN, eligibility and `startAt <= serverNow < endAt` when starting.

## Private PUBLIC locator

Generate 32 SecureRandom bytes, encode unpadded base64url (43 characters, 256 bits).
It is separate from Publication UUID. Persist a unique SHA-256 lookup digest and
retain the random locator in the private Publication row for owner retrieval and
creation replay. This is not an anonymous authorization credential: authenticated
current STUDENT is mandatory. Database access and backups must protect the locator;
plaintext retention is an explicit reviewed storage choice, not encryption-at-rest
proof. DTO string rendering redacts it. No public enumeration/search or locator
metrics/log labels. Lookup uses POST body, never URL/query/trace attributes. Future
frontend sharing should keep the locator out of server URL logs (e.g. URL fragment).
No frontend sharing behavior is implemented here.

## Persistence and concurrency

Assessment alone owns publications, publication_snapshots and publication_creations.
No cross-service FK/SQL. UUID identities, revision starting at zero, UTC timestamps
and indexed owner lookups. Creation replay scope is (owner UUID, opaque key), where
keys are 16..128 ASCII letters/digits/underscore/hyphen. Store only key SHA-256,
canonical typed-request SHA-256 and resulting Publication UUID. Same key/request
returns the current authoritative resource; same key/different request returns 409.
Concurrent inserts serialize through the unique key and commit one Publication.
No arbitrary replay TTL or deletion job is introduced.

Final snapshots are write-once records containing schemaVersion 1, source snapshot
and gradingPolicyVersion 1. Existing Quiz question number and option label are
stable item identities/order; no normalization invents a second answer-key model.
Publication's nullable snapshot FK points one way to its own snapshot identity;
DRAFT requires NULL, OPEN/CLOSED requires a matching immutable snapshot. No circular
FK or cross-table trigger. Local same-table guards protect finalized snapshot writes
and frozen source/audience/Classroom/monitoring fields. Local database constraints
also enforce schedule/duration/audience/state/revision invariants.

OPEN first reads owner/DRAFT/revision and performs bounded remote prefetch without
holding a database transaction. It validates source identity/schema/content and
Classroom authority, then locks the Publication in a short local transaction,
rechecks owner/state/revision, inserts the complete snapshot and changes state/revision
atomically. Concurrent edits invalidate preparation. Concurrent OPEN for the same
expected DRAFT revision returns the one committed OPEN; a later retry with that
same openedFromRevision returns metadata without fetching newer source content.
Other revision/state conflicts return 409. Rollback leaves no snapshot or OPEN.
Identity/source admission and this commit are not a distributed transaction;
ownership/revocation changes after their documented snapshots can race in-flight work.

Content, source identity, audience, Classroom and monitoring freeze upon OPEN.
Scheduling is deliberately separate: the OPEN schedule endpoint permits only
nondecreasing endAt and duration; startAt remains fixed. This is safe regardless
of future Attempts and does not ban all post-OPEN timing changes. More permissive
pre-first-Attempt changes await authoritative Attempt integration. Future W3-B
pins each startedAt + duration deadline; extensions never recalculate or shorten
existing Attempt deadlines.

## Future START/CLOSE protocol and explicit deferrals

W3-B START, CLOSE and finalization must lock the same Publication row in the same
order before inspecting/writing authoritative Attempt state. START checks OPEN,
current audience authority/window and captures duration while locked. CLOSE must
reconcile overdue Attempts according to the still-open terminalization contract,
reject if any remains active and transition only after authoritative checks.
No fake zero count, in-memory registry, unconditional CLOSE, force-close or timer
closure is implemented. The exact terminalization/submission semantics remain
Leader/engineering gates. W3-A remains partial overall because CLOSE and frontend
journeys are deferred; this bounded increment can still complete.

Error codes are fixed/sanitized: INVALID_REQUEST 400, UNAUTHENTICATED 401,
ACCESS_DENIED 403, PUBLICATION_NOT_FOUND / source/class inaccessible 404,
PUBLICATION_CONFLICT / IDEMPOTENCY_CONFLICT 409, AUTHORITY_UNAVAILABLE or
DEPENDENCY_UNAVAILABLE / PERSISTENCE_UNAVAILABLE 503. Unsupported handoff schemas
fail closed as dependency 503. Success and errors are no-store.

## Rollout

Publication feature defaults disabled at Gateway and Assessment; exact selected
routes return unavailable, never JWT-only allow. Source internal endpoints and
all admission clients also default disabled. No Identity registration, admission
or reconciler is enabled automatically. Migrate Assessment before enabling its
binary; provision narrowly scoped clients and exact caller/destination allowlists.
Use authenticated HTTPS with certificate/hostname verification and private network
policy. Local insecure overrides are test-only and do not establish production
transport. Redact Authorization, evidence, cookies, secrets, locators and body data
at proxies/APM/logging. Bound pools/deadlines and measure the multiple admissions
and remote dependencies per command.

Drain/block bypass-capable older Gateway AND downstream instances before enabling
traffic. Rollback requires blocking affected operations first. Preserve Identity's
single fenced writable primary and acknowledged security commits across failover.
Cold/stale Redis remains unavailable until per-actor primary equality succeeds.
Keep the previously reproduced baseline Netty buffer-leak diagnostic as an open
capacity investigation; no false-positive/resolved claim. Production activation,
privacy/camera policies and W3-B/C/M1/M2 remain unapproved by this implementation.
