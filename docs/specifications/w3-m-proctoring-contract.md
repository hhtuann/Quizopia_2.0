# W3-M — Proctoring engineering contract

Status: **M1 engineering contract for the current default-disabled implementation
increment; independent implementation/security review pending. M2 and real-learner
activation remain gated.** No new monitoring mode, retention period, camera refusal
outcome, sanction or production approval is created.

## Authority and unresolved decisions

[Accepted monitoring scope](w3-a-publication-scheduling-monitoring-policy.md),
[Assessment product policy](assessment-core-policy.md),
[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md),
[ADR-014](../decisions/ADR-014-access-token-principal-and-authority-contract.md) and
[LD-05](../architecture/ld05-enforcement.md) remain authoritative. Inspect actual
[Assessment OpenAPI](../../services/assessment-service/contracts/publications.openapi.yaml)
for implemented Publication/Attempt/closure contracts; historical proposed inventories
are not executable APIs. See the [implementation checkpoint](../architecture/w3m-backend-implementation.md)
for actual delivery/evidence boundaries and resumption.

| Question   | Classification                                                             | Current decision / boundary                                                                                                                                                                                                                                                                                        |
| ---------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| PROCTOR-01 | Product/privacy plus external review                                       | Retention/access/erasure duration and cleanup authorization remain OPEN. Durationless storage mechanics and inactive cleanup can be implemented for synthetic tests; real-learner collection cannot be enabled with an unapproved period or indefinite retention.                                                  |
| PROCTOR-07 | Deployment/privacy                                                         | LiveKit provider/location, private transport, credentials, data handling and actual topology acceptance remain OPEN. Local infrastructure is not deployment approval.                                                                                                                                              |
| PROCTOR-08 | Engineering mechanics; separate authoritative-event adapter dependency     | Freeze the M1 typed browser envelope, durable append/replay/ordering/access boundaries below. Browser answer metadata is an untrusted observation; Assessment alone owns committed answers and lifecycle. An authoritative Assessment answer-change outbox/consumer is not delivered by this initial M1 increment. |
| PROCTOR-09 | Engineering derivable from accepted ownership                              | Session binds exactly one authoritative CLASS Publication, Classroom, Attempt and Student. Verify through private service authority, never cross-service SQL/FKs or client-supplied owner/class IDs.                                                                                                               |
| PROCTOR-10 | Already implemented Assessment representation; UI wording remains frontend | Reuse `UNCONFIGURED`, `ENABLED`, `DISABLED`. CLASS choice is explicit before OPEN and immutable afterwards; PUBLIC is DISABLED. No Activity-only or invented A/B mode.                                                                                                                                             |
| PROCTOR-11 | Split: technical security mechanics vs product outcome                     | Scoped grants, truthful technical states and current authorization/reconciliation can be implemented without academic outcomes. Refusal/loss/device/accommodation policy and dependent M2 behavior remain OPEN; no automatic stop/fail/ABANDON/penalty.                                                            |
| PROCTOR-12 | External privacy/accessibility/security review                             | Notice/viewers, lawful basis/authorization/consent, minors, accommodations, retention/deletion, audits, location, incident/data-subject handling and no-recording approval remain OPEN before real learners. No legal compliance is inferred.                                                                      |

AI detectors/flags/risk scoring/snapshots (PROCTOR-02/03), microphone/recording,
strict screen sharing (PROCTOR-04/05) and AI disciplinary workflow are deferred
post-Wave-3. They are not required M1/M2 implementation dependencies.

## Eligibility, ownership and time

Monitoring is optional per eligible CLASS Publication, but ENABLED means the
accepted Activity Evidence **and** LiveKit camera bundle. Separate implementation
increments do not authorize real-learner Activity-only mode. PUBLIC, unlimited
Attempt duration, unbounded availability and practice monitoring are prohibited.
Eligible runtime requires an ACTIVE bounded Attempt, OPEN eligible Publication,
current authenticated Student and current Classroom membership. Teacher access
requires current issued/admitted TEACHER, owned Publication and current Classroom
authority. Proctoring never creates/resurrects an Attempt or mutates answers,
submissions, grades, deadlines, membership or Publication state.

Assessment owns Attempt/Publication validity, deadline and bindings; Classroom owns
membership/Teacher authority; Identity owns current account/role/revocation and
primary/Redis equality. Proctoring owns sessions, evidence, access audit and later
LiveKit orchestration. No service reads another service's database or adds a
cross-database foreign key. Service-local UUID references are verified through
bounded private APIs, not assumed authoritative merely because they are stored.

Every protected operation independently validates RS256 USER/ADR-014 and fresh
LD-05 admission downstream as well as at Gateway. Proctoring acquires its own narrow
SERVICE client token for
`POST /internal/proctoring/attempts/{attemptId}/authority`, requiring
`assessment.proctoring-authority.read` and the exact configured Proctoring caller,
with the original USER evidence and explicit `purpose: HISTORY`;
Assessment independently verifies SERVICE caller/scope, original USER and current
resource authorization, delegating Classroom facts through existing private contracts.
The private response echoes that purpose; its local Attempt `active` value is not
fresh live-media permission. Live media must use the separate `LIVE_MEDIA` request
purpose and validate its echo, including the new narrowly scoped target-Student
Identity/Classroom checks. See [the correction evidence](../architecture/w3m-correction-evidence.md).
No USER impersonation, generic introspection, Gateway internal proxy or positive
eligibility cache is permitted. Authority checkedAt must be fresh (at most five
seconds); malformed/stale/mismatched/unavailable authority is unavailable, not allowed.

Remote authority and a Proctoring database transaction are not one distributed
transaction. Document the bounded in-flight snapshot race honestly: a role,
membership or Attempt transition can occur after an accepted remote sample. Local
binding/state/expiry checks and terminal fencing prevent local resurrection, but
cannot promise atomicity across services. Server receipt/deadline governs eligibility;
client time, HTTP arrival and browser state never grant extra Attempt time.

## M1 exact operations

Gateway exposes only reviewed exact adapters beneath `/api/proctoring`; the
[implemented service OpenAPI](../../services/proctoring-service/contracts/monitoring.openapi.yaml)
is the authoritative wire inventory. Gateway and downstream use the same full prefix without rewriting. The operations are:

| Method / downstream path                                         | Required USER role and resource authority          | Behavior                                                                                                                          |
| ---------------------------------------------------------------- | -------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| POST `/api/proctoring/monitoring/sessions`                       | STUDENT, own current eligible Attempt              | Create or return the durable session for `{attemptId}`; bindings come from authority, never the request.                          |
| GET `/api/proctoring/monitoring/sessions/{sessionId}`            | STUDENT owner or authorized TEACHER                | Return authorized session status after fresh authority.                                                                           |
| POST `/api/proctoring/monitoring/sessions/{sessionId}/events`    | STUDENT owner; ACTIVE eligibility for new activity | Append one strict typed browser observation; authorized exact durable replay may return its prior acknowledgment after local END. |
| GET `/api/proctoring/monitoring/sessions/{sessionId}/events`     | Authorized TEACHER                                 | Bounded keyset timeline with current resource authorization and durable access audit.                                             |
| POST `/api/proctoring/monitoring/sessions/{sessionId}/reconcile` | STUDENT owner or authorized TEACHER                | Recheck authoritative eligibility and irreversibly end local monitoring when no longer eligible; never terminalize Assessment.    |

Unknown methods/routes, internal aliases and encoded/noncanonical variants stay
closed; no generic authenticated `/api/proctoring/**` fallback. SERVICE/ADMIN alone
cannot satisfy USER routes. Identity authority failure does not invalidate refresh
families, force logout or create a JWT-only bypass. All protected responses/errors
are no-store and sanitized; tokens, upstream bodies and private destinations are
never returned or logged. The [private authority OpenAPI](../../services/assessment-service/contracts/proctoring-authority.openapi.yaml)
distinguishes independently invalid original USER evidence (`USER_EVIDENCE_INVALID`,
public 401) from internal SERVICE credential rejection (public authority-unavailable
503), preventing service failures from causing user logout. The OpenAPI specifies exact status/error DTOs; invalid
shape is rejected without partial event mutation, conflicting replay is
`409 EVENT_CONFLICT`, and untrusted required authority fails closed with 503.

## Session and evidence envelope

Session view fields are `id`, `attemptId`, `publicationId`, `classroomId`, `studentId`,
`deadlineAt`, `status` (`ACTIVE` or `ENDED`), `createdAt`, `endedAt`. Session UUID and
immutable authority-derived binding are Proctoring-owned. One durable session per
Attempt prevents concurrent duplicate creation; END is irreversible. Reconnect cannot
reopen an ended session or extend the Attempt deadline.

An ingestion request requires `schemaVersion: 1`, `eventId` UUID, `streamId` UUID, `sequence` integer
≥0, `type` from the allowlist, optional `observedAt` instant and a required positive
`questionNumber` for question/answer event types; other types allow it absent or null.
Missing/null/non-1 schema versions are rejected.
Integer fields require integral JSON tokens; fractional, exponent, string and
Boolean representations are rejected without coercion. `observedAt` accepts an
approved ISO timestamp string or null, never a numeric epoch value. These Event-only
deserializers preserve unrelated endpoint types and schema version 1.
Stored evidence also binds the
authoritative actor ID and schema version to its canonical integrity hash. Unknown fields, duplicate
JSON keys, arbitrary metadata, free text/URLs and invalid type-specific fields are
rejected. The current explicit browser type set is:

- `QUESTION_VIEWED`, `QUESTION_LEFT`, `ANSWER_SELECTED`, `ANSWER_CHANGED`, `ANSWER_CLEARED`.
- `VISIBILITY_HIDDEN`, `VISIBILITY_VISIBLE`, `WINDOW_BLUR`, `WINDOW_FOCUS`.
- `FULLSCREEN_ENTERED`, `FULLSCREEN_EXITED`.
- `CAMERA_PERMISSION_GRANTED`, `CAMERA_PERMISSION_DENIED`, `DEVICE_MISSING`, `DEVICE_ERROR`, `CAMERA_UNAVAILABLE`, `MEDIA_DISCONNECTED`, `RECONNECTING`.
- `NETWORK_DISCONNECTED`, `NETWORK_RECONNECTED`, `DUPLICATE_TAB`.

`SESSION_STARTED` and `SESSION_ENDED` evidence types are reserved for the server, never accepted
as browser assertions. Browser source/provenance remains distinguishable from
server authority. Camera events describe reported technical conditions; they do
not establish an actual live stream or implement M2. Answer events contain a claimed local
question number only, no selection value/answer/digest or correctness. They do not
prove Assessment SAVE committed. No Quiz content, unrelated tab URLs, browsing
history, clipboard, keystrokes, media, tokens or secrets are stored.

Server `receivedAt` is authoritative receipt time. Optional `observedAt` is normalized to PostgreSQL microsecond precision before
canonical hashing/storage, so replay compares the same persisted representation. Stream
identity and sequence are untrusted client claims. Out-of-order arrival is accepted;
server-assigned `ordinal` determines durable timeline order. Client sequence never
rewrites an earlier event or changes authoritative timestamps.

## Persistence, replay, pagination and audit

Proctoring-owned PostgreSQL/Flyway persists immutable session binding, append-only
bounded evidence, canonical request identity and durable acknowledgment within one
local transaction. Exact same canonical event payload replayed by `eventId` or
`(streamId, sequence)` returns its stored acknowledgment; conflicting reuse gives
409 without replacing evidence. Freshly authorized exact replay can return the
existing acknowledgment after terminalization; it cannot append new activity or
reopen the session. Acknowledgment fields are `id`, `ordinal`, `receivedAt`.
Serialize ingestion versus END so an already ended local session cannot accept new
activity. Malformed/oversized input must be sanitized and mutation-free.

Integrity means the actual constraints, server-computed canonical hash and append-only
history guards are verified, not a promise that a privileged database owner cannot
alter storage. Runtime privileges must not own/disable integrity guards. No Redis
or LiveKit DataChannel is an authoritative evidence/answer store. Migration owner
and runtime roles remain separate; recover with reviewed roll-forward, never edit
merged Assessment migrations or delete evidence to repair a retry conflict.

Teacher timeline uses `after` nonnegative server ordinal (default 0), `limit` 1..100
(default 50), response `{items,nextAfter}`. Indexed keyset access is bounded and
resource-authorized on every read; client-controlled pagination never changes actor
or session authorization. Record authorized timeline access durably without logging
protected event bodies or secrets. A session/Attempt ID is not authorization.

Retention-aware fields/cleanup remain inactive until approved policy exists. The
owner-controlled cleanup SQL function has PUBLIC EXECUTE revoked and requires a
separate reviewed operator grant; it may remove only already-expired dated evidence,
never infer an expiry. Normal runtime cannot UPDATE/DELETE evidence. Preserve session
identity/retry metadata; this does not authorize indefinite real-learner evidence
retention. Current
configuration defaults `PROCTORING_ENABLED=false`, unset
retention days and blank policy/privacy approval references. No default 7/30-day TTL
is adopted. The current M1 implementation unconditionally rejects enabled monitoring in the
production profile until actual M2 integration and policy acceptance are delivered.
There is no cleanup scheduler or `CLEANUP_ENABLED` configuration: only the manually
authorized `monitoring_purge_expired` function exists. An attestation/flag cannot
pretend that M2 exists or privacy is approved. Synthetic isolated implementation
tests do not authorize indefinite real-learner storage.

## M2 separate dependent increment

LiveKit backend room/token orchestration belongs to a separate bounded M2 branch/PR,
explicitly dependent on M1 if unmerged. No media API, token issuance or completed M2
implementation is claimed by these M1 operations. Freeze actual room identity and
SDK/provider mechanisms in that increment against current official contracts.
Required bindings remain server-owned eligible session/Attempt/Publication/Classroom;
client room/role/identity cannot confer grants.

A Student may publish only their authorized camera, cannot subscribe to peers or
obtain Teacher rights; a current authorized Teacher may subscribe only to eligible
corresponding active streams. Credentials must be short-lived/scoped and limited by
server-authoritative eligibility. Token TTL alone does not prove immediate removal:
verify actual participant removal/revocation, expiry/reconnect, restart, authority
loss and LiveKit outage against the actual provider. No recording, microphone
requirement, screen sharing, AI identification or automatic sanction is approved.
Refusal/loss/accommodation outcomes and real-learner activation remain gated.

Proctoring/LiveKit failure must not corrupt saved answers, duplicate grading,
change time/score, auto-submit/ABANDON or bypass revoked access. Privileged media
access fails closed, while academic outcomes remain the separately approved
Assessment policy. Browser permission is not all legal/privacy authorization.
