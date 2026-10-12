# W3-B/W3-C — Future HTTP and OpenAPI contract freeze proposal

Status: **PROPOSED engineering contract; every operation below is UNIMPLEMENTED.**

Leader product policy is accepted in [ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md).
Machine-readable DTOs/routes are [OpenAPI 3.1](proposed/w3b-w3c.openapi.yaml).
Semantics supplement JSON Schema in [lifecycle](w3-b-attempt-lifecycle-contract.md),
[closure](w3-b-closure-notification-contract.md),
[grading](w3-c-grading-results-contract.md) and
[persistence](../architecture/w3b-w3c-persistence-concurrency-design.md).
W3-A implemented routes remain in their service OpenAPI unchanged. Practice,
public directory, guest, generic notification and read-ack APIs are deferred.
External gates: independent contract review and Leader sign-off, exact route
adapters/feature provisioning, real topology and capacity/security/rollout controls.

## Source-truth route boundary

At `8777d48`, both Gateway and Assessment PublicationRoutes recognize exactly:
POST `/api/assessments/publications`, GET/PUT `.../{UUID}`, POST `.../{UUID}/open`,
PATCH `.../{UUID}/schedule`, POST `.../entry`, POST `.../{UUID}/entry`.
PublicationController implements these seven method/path pairs. Gateway reserves
the broader `/api/assessments/**` transport prefix, but its closed admission
inventory does not authorize arbitrary routes. W3-B/C have no controller/API today.
The proposed inventory must be added explicitly at **both** boundaries, with
matching downstream authorization/admission, only in the owning implementation PR.
No prefix-wide authenticated fallback, aliases, alternate public service ports or
`/internal/**` Gateway route. These docs do not modify service OpenAPI.

## Common transport, validation and credentials

All operations: HTTPS through Gateway in deployment, exactly one Authorization
Bearer USER JWT, RS256/issuer/expiry/ADR-014 verification independently at both
boundaries, fresh primary Identity admission with complete Redis equality on each
request. Token roles and current roles both required. Business responses and every
error include `Cache-Control: no-store`; do not put result/review/inbox into CDN,
service-worker persistent cache or logs. Request/response bodies are application/json.
Mutations require Content-Type application/json and exactly one `Idempotency-Key`
matching `[A-Za-z0-9_-]{16,128}`. No cookies/CSRF credentials authorize business APIs;
refresh remains separate. Existing CORS allows this header; add no USER evidence
or identity assertion headers to browser CORS.

Reject unknown/duplicate JSON fields, trailing documents, unknown enum/type/label,
nonintegral fields, absent required body, malformed/null required fields and
unknown/duplicate query parameters. Resource UUIDs are canonical lowercase;
questionNumber canonical positive decimal int32. GET has no body. Offset instants
must be finite with at most six fractional digits; serialize UTC Z. Reject
percent-encoded/ambiguous paths, backslashes, doubled slashes, dot segments and
matrix parameters as existing ingress does. Invalid/missing operation key is 400;
never automatically substitute a new key on retry.

Attempt revision, expectedRevision and ordinal are **canonical decimal strings**
within signed bigint range (nonnegative revision, ordinal positive); wire strings
avoid JS precision loss. Publication expectedRevision/response revision retain
existing W3-A JSON integer representation; clients must use lossless parsing or
reject unsafe numbers rather than silently round a concurrency token. New Attempt
revision 0, each mutation +1. Overflow fails closed with 503, no wrap/reused ordinal.
The OpenAPI pattern enforces spelling and server semantic checks enforce bigint
range. The config proposal adds maxAttempts default 1 and duration 0 without
changing implemented service artifacts. Student numeric wire semantic checks
are separate from maxLength64 schema; OpenAPI must not advertise a loosely parsed
string as the complete validator.

Persist SHA-256 operation key and canonical typed request digest; do not log raw
key/payload. Scope creation keys to Student+Publication, Student mutation keys to
Attempt (all mutation actions), Teacher keys to actor+Publication (all Teacher
actions). Digest includes action/path IDs/question/revision/payload; absent vs
explicit complete answer-set omission differ. Parsed objects sorted by field
name, answer entries ordered question number, option set sorted after duplicate
validation, matrix A-D, numeric ASCII trim only. Serialize canonical UTF-8 JSON
without whitespace and with deterministic JSON escaping; store canonicalization
version 1 in implementation constants and preserve it across upgrades. A version
change requires dual-read receipt compatibility, never mass key expiration.
Successful matching replays still require current authority and resource ownership.
Changed payload/action under existing/reserved key: 409 IDEMPOTENCY_CONFLICT.
No retention TTL chosen for replay evidence.

Proposed input body ceiling is 2 MiB uncompressed UTF-8 JSON, consistent with the
current snapshot handoff transport budget; no arbitrary question-count product cap.
Oversize is 400 INVALID_REQUEST before mutation. Capacity verification must include
complete answer replacement and redacted delivery/review expansion for all snapshots
accepted by W3-A; adjust this engineering ceiling in review if necessary, never
truncate answers/content. The current handoff's accepted compatibility/capacity gap
remains an external gate, not permission to change Quiz authoring validity.

## Proposed inventory and projections

Abbreviations: P=`/api/assessments/publications/{publicationId}`;
A=`/api/assessments/attempts/{attemptId}`;
C=`P/closure-requests/{closureRequestId}`; N=`/api/assessments/notifications`.
All rows are future PROPOSED operations, including ordinary CLOSE.

| Method / path                            | USER role | Request schema     | Success schema / statuses                                                  |
| ---------------------------------------- | --------- | ------------------ | -------------------------------------------------------------------------- |
| POST P/attempts/start                    | STUDENT   | StartRequest       | AttemptView: 201 first creation; 200 existing/replay                       |
| GET A                                    | STUDENT   | No body            | AttemptView 200; reconcile deadline/cutoff before resume                   |
| POST P/attempts/retake                   | STUDENT   | StartRequest       | AttemptView: 201 retake; 200 original-key replay                           |
| POST A/questions/{questionNumber}/answer | STUDENT   | SaveRequest        | SaveAck 200; original acknowledgment on matching replay                    |
| POST A/submit                            | STUDENT   | SubmitRequest      | Result 200; same immutable result on completed replay                      |
| POST A/abandon                           | STUDENT   | AbandonRequest     | AbandonAck 200; same terminal acknowledgment on replay                     |
| GET A/result                             | STUDENT   | No body            | Result 200, total-only                                                     |
| GET P/aggregate-score                    | STUDENT   | No body            | Aggregate 200; bestResult null if no graded work                           |
| GET A/review                             | STUDENT   | No body            | Review 200 only own SUBMITTED plus CLOSED                                  |
| GET P/results                            | TEACHER   | limit/cursor query | TeacherResults 200, owned Publication only                                 |
| POST P/close                             | TEACHER   | TeacherCommand     | ClosedPublication 200; durable successful-key replay                       |
| POST P/closure-requests                  | TEACHER   | TeacherCommand     | ClosureView 201 created; matching replay 200 same request current view     |
| GET C                                    | TEACHER   | No body            | ClosureView 200                                                            |
| POST C/cancel                            | TEACHER   | TeacherCommand     | ClosureView 200; original cancellation acknowledgment                      |
| POST C/complete                          | TEACHER   | TeacherCommand     | ClosureCompletion 200 closed=true; 202 committed bounded work closed=false |
| GET N                                    | STUDENT   | limit/cursor query | NotificationPage 200                                                       |
| GET N/{notificationId}                   | STUDENT   | No body            | Notification 200; no acknowledgment mutation                               |

No hidden read receipt; Student notification GET does not start/advance notice
period. Required noticeAccessibility is a durable server-side publish/confirm
process under the closure contract. Student Attempt `closure` is null if no live
membership; otherwise request ID and nullable confirmation/cutoff. Notification
history retains cancelled/resolved status, owner-only outcome/result reference.

StartRequest: PUBLIC requires locator and path association; CLASS omits locator
(and rejects it if present). No client Student ID. Owned existing PUBLIC START
still verifies supplied locator. Retake always requires locator for PUBLIC.
AttemptView returns stable safe question projection (`number,type,stemMarkdown,
options[{label,markdown}]`) and complete own answers, state/revision/start/deadline/
duration/serverNow. Numeric options empty, other types exactly A-D. No source,
correct flags, numericAnswer, explanations or per-question grade in this DTO.

SubmitRequest requires expectedRevision; optional answers must cover every question
once using AnswerEntry. No `answers:null`, sparse replacement or implicit false
matrix defaults. Omission freezes saved state. AbandonRequest has only revision.
SaveAck contains Attempt/question/revision/savedAt, no grade. AbandonAck contains
Attempt/terminal state/reason/revision/finalizedAt, no grade. Result is immutable
totals/policy/terminal reason/time only. Aggregate includes only this owner's best
Result, or null. Review explicitly adds correctAnswer, explanation and per-question
weights after CLOSED; the same fields are forbidden in all other Student DTOs.
TeacherResults includes Student ID, Attempt ID/ordinal/state and nullable Result,
never answer content/keys. No Teacher answer-review endpoint is inferred.

## Errors and precedence

Use only code envelope `{"code":"..."}`, no upstream body, path/locator, SQL,
credentials, keys, Student counts or identities in errors. The exact proposed code
inventory is in OpenAPI. Standard status mapping:

| Status | Codes / rules                                                                                                                                                                                                                                                                                                                                                                                           |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 400    | INVALID_REQUEST: malformed schema/reference/numeric/operation identity/query; no mutation                                                                                                                                                                                                                                                                                                               |
| 401    | UNAUTHENTICATED: missing/invalid/expired USER or independently invalid USER evidence                                                                                                                                                                                                                                                                                                                    |
| 403    | ACCESS_DENIED: wrong principal or missing issued/current STUDENT/TEACHER; SERVICE never impersonates USER                                                                                                                                                                                                                                                                                               |
| 404    | PUBLICATION_NOT_FOUND, ATTEMPT_NOT_FOUND, CLOSURE_REQUEST_NOT_FOUND, NOTIFICATION_NOT_FOUND; inaccessible/missing indistinguishable. CLASS member denial can use existing CLASSROOM_NOT_ACCESSIBLE without confirming Attempt existence.                                                                                                                                                                |
| 409    | PUBLICATION_CONFLICT, IDEMPOTENCY_CONFLICT, REVISION_CONFLICT, WINDOW_NOT_STARTED, WINDOW_ENDED, WINDOW_NOT_ENDED, ATTEMPTS_EXHAUSTED, ACTIVE_ATTEMPT_EXISTS, RETAKE_NOT_AVAILABLE, ATTEMPT_TERMINAL, ATTEMPT_DEADLINE_REACHED, CLOSURE_CUTOFF_REACHED, RESULT_NOT_AVAILABLE, REVIEW_NOT_AVAILABLE, ACTIVE_ATTEMPTS, CLOSURE_PENDING, CLOSURE_NOT_READY, CLOSURE_FINALIZATION_STARTED, CLOSURE_RESOLVED |
| 429    | CAPACITY_EXCEEDED for deployed operational abuse throttles; no entitlement change or invented finite cap for unlimited retakes                                                                                                                                                                                                                                                                          |
| 503    | AUTHORITY_UNAVAILABLE (Identity/Redis readiness), DEPENDENCY_UNAVAILABLE (Classroom/integration), PERSISTENCE_UNAVAILABLE (DB/timeouts), GRADING_UNAVAILABLE (unsupported/corrupt pin); no fake success or fallback                                                                                                                                                                                     |

Validate route/auth/issued roles, perform independent current admission, then
resource masking/current CLASS membership, then semantic input validation/replay,
then locked lifecycle/revision/time. JSON syntactic failures may return 400 earlier,
but no resource-sensitive conflict/body leaks before resource authorization.
Same key/different valid payload conflicts before lifecycle/revision eligibility;
matching completed receipt bypasses new mutation checks, not authority. New SAVE/
SUBMIT/ABANDON checks terminal, then due reconciliation/time, then revision, then
writes. A bounded overdue or notice-cutoff conflict may follow an atomically
committed finalization; this is explicitly distinguished from malformed 400 and
from a successful manual submission. Clients GET own state/result after conflict.

Current Identity SERVICE 401/403, malformed/oversized/unknown response, wrong actor,
timeout/pool exhaustion, missing config, Redis stale/missing or primary unavailable
map to sanitized 503 at the public edge. Only independently invalid USER evidence
maps 401. No automatic `/me` fallback, positive admission cache, JWT-only path,
refresh-family invalidation or automatic logout due to outage. Preserve existing
LD-05 bounded in-flight snapshot semantics; a role/membership change after its
validated snapshot can race a currently executing operation.

Teacher conflict ACTIVE_ATTEMPTS is exactly that code, without count/identities.
Review checks durable CLOSED plus own submitted result, never inferred closure
from endAt/48 hours. Notification ownership is checked before projecting any
Publication/cutoff/outcome; no CLASS lookup blocks inbox notice accessibility.
PUBLIC aggregate requires at least one owned Attempt to prevent enumeration by
known Publication UUID. Student result queries cannot list peers; Teacher list
never grants another Teacher access. SERVICE callers get no public operation.

## Pagination and retries

Both collections default limit 20, range 1..100, response items and nullable nextCursor;
no total count. Teacher results sort Attempt UUID ascending; notification inbox
sorts publishedAt DESC then notificationId DESC. Cursor is opaque signed base64url
(maximum 1024 characters), includes cursor version, route kind, actor/resource binding and
last ordering tuple; runtime HMAC secret belongs only to Assessment and supports
reviewed rotation. Foreign/malformed cursor 400. Keyset pagination avoids duplicates
when new notices arrive above prior page; changing row state/result can be reflected
on later reads. No snapshot-isolated full multi-page export is promised.

Replay responses: START/RETAKE and REQUEST return same selected resource identity's
current projection (200 replay; creation 201). SAVE/SUBMIT/ABANDON/CLOSE/CANCEL/
COMPLETE return original committed acknowledgment/result/status. COMPLETE 202 is
not CLOSED: inspect GET C and use new key for another batch after acknowledged
progress. IN_PROGRESS reservation survives crash and coordinates same-key continuations;
409 not-ready can be retried with same payload/key without replaying committed work.
No automatic unsafe submit retry with a new key. Recover uncertain commit through
same key and authoritative reads. 429/503 backoff is operational; never extends
server deadlines or introduces grace.

## Proposed config and release impact

Implementation introduces default-off ATTEMPTS_ENABLED, RESULTS_ENABLED and
CLOSURE_ENABLED gates independently at exact Gateway/downstream adapters; no route
is enabled until atomic finalizer is present. CLOSURE_ENABLED additionally requires
ready authenticated inbox routes and notice worker. Assessment worker enablement
is separately default-off; all-disabled cannot loosen already accepted cutoffs
or existing deadlines once real data exists. Rollback/blocking cannot erase
notice evidence or extend deadlines. Notice confirmation additionally needs proposed narrow `identity.notice-recipient.read`;
other private Identity/Classroom calls reuse W3-A scopes. Existing browser CORS
Idempotency-Key suffices. Cursor-signing secret and operator flags are runtime
only; no real environment files in Git.

## Proposed internal Identity recipient accessibility dependency

The existing dual-credential admission endpoint cannot check an offline recipient
using a Teacher's USER evidence, and workers must not persist/impersonate Student
tokens. Add a narrow **PROPOSED, UNIMPLEMENTED** direct internal Identity operation:
`POST /internal/security/notice-recipients/{studentId}/eligibility`, bodyless/no
query/cookies, exactly one RS256 SERVICE Authorization, exact allowlisted Assessment
client and scope `identity.notice-recipient.read`. Never Gateway-routed or browser
accessible. It is a recipient eligibility check, not a USER admission grant.

Response 200 `{"studentId":"<matching UUID>","eligible":true,"checkedAt":"<UTC>"}`
uses the same coherent fenced-primary/current complete Redis equality contract
as LD-05. Match the requested UUID; eligible means present ACTIVE, verified and
current STUDENT. Matching complete ineligible/missing authority returns eligible
false without profile/email/roles/cutoff. Untrustworthy/missing/stale Redis or
primary uncertainty returns 503 AUTHORITY_UNAVAILABLE, not a guessed false/true.
The least-privilege caller may inspect only this Boolean; no scope authorizes
Student business operations or gives workers credentials. No positive eligibility
cache. Identity owns all account reads; no cross-service SQL.

Internal errors: 400 INVALID_INTERNAL_REQUEST,401 SERVICE_AUTHENTICATION_INVALID,
403 SERVICE_CALLER_FORBIDDEN,503 AUTHORITY_UNAVAILABLE, code-only/no-store. Bound
private pools/deadlines/response size like admission (4096 bytes, total 5s, no
redirect/retry, pinned private HTTPS origin). Validate exact response shape/UUID/
finite checkedAt. Call before confirmation transaction; total elapsed age from
call start to confirmation sample must be <=5s and preserve the documented
cross-service in-flight limitation. If stale, release locks and repeat check;
never make a network call while holding Publication locks. This check and the
persisted inbox ACL/route-readiness proof jointly establish accessibility.

Deploy/provision/test this dependency before notice confirmation activation. It
adds one internal proposed operation to the 17 public operations, and needs its
own exact Identity filter/scope/allowlist adapter. Existing Identity admission
routes/credentials/USER authorization remain unchanged in this assignment.
