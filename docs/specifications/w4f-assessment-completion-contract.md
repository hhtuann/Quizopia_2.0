# W4 Final Completion — Task 1 contract freeze candidate

Recorded: 2026-10-30. Authority: [Issue #133 and PO execution index](https://github.com/hhtuann/Quizopia_2.0/issues/133).
Inspected baseline: `origin/develop` `bdf5d116bb7258ced8471a3e4c24b1a3de21f4a2`.

**Disposition: proposed engineering contracts frozen for independent Leader/security
review. Documentation/design only for sections 3–7; no new API, ingestion, audit
collection, analytics or Teacher Review implementation is delivered by T1.**
“Frozen” means a specific reviewable candidate, not approval of pending policy.
Implemented service OpenAPI continues to describe available runtime capabilities.

## 1. Approval and execution matrix

| Decision / gate | Actual status | Consequence |
| --- | --- | --- |
| W4F-DEC-01 | PO intent **ALLOW** (2026-10-30) | Current owning Teacher may see keys while OPEN through a separately authorized Teacher Review endpoint; independent security contract approval **PENDING** |
| W4F-DEC-02 | PO intent **MINIMAL** (2026-10-30) | Server lifecycle/persistence/finalization facts and five minimal browser question/answer categories; exact proposed reconciliation below needs review |
| W4F-DEC-03 | **PENDING** | Audit retention TTL, expiry, hold authority and copy expiry are not selected; no guessed 7/30/90/365-day default and no real-learner collection |
| W4F-DEC-04 | Notice **REQUIRED**; wording/lawful basis/minors/qualified privacy/legal approval **PENDING** | Collection stays default OFF; permission prompt or PO scope intent is not privacy approval |
| T1 configuration/UI | **AUTHORIZED** by #133 | Existing DRAFT maxAttempts contract, single-column details, masked locator, remove redundant chips/global Refresh |
| T2 #134 | **BLOCKED** on independent T1 review + merge and explicit Leader handoff | Authoritative summaries/history/Teacher Review and separately gated synthetic-only audit mechanics belong there |
| T3 #135 | **BLOCKED** on T2 review + merge | Unified student results/overview frontend belongs there |
| T4 #136 | **BLOCKED** on T3 review + merge | Live Monitoring/LiveKit integration and full acceptance belong there |
| Production / real learners | **NO-GO** | No deployment, production flag change, real audit collection, release or final product acceptance |

ASSESS-04 remains the Student CLOSED gate. DEC-01 is a narrow **future Teacher
endpoint** exception, not a reinterpretation of Student review, existing result
lists or dual-role access. ADR-015 scoring/entitlement, ADR-017 identity lookup,
ADR-018 deletion, LD-05 and BR-01 remain authoritative. No ownership transfer or
new microservice is proposed. PO alone closes final product acceptance.

## 2. Inspected capability inventory

Paths below are repository-relative; Java paths are within the owning service's
`src/main/java/com/quizopia/` tree.

| Area | Existing code/contracts | Missing / dependent work |
| --- | --- | --- |
| Publication configuration | `frontend/features/assessments/components/{publication-detail-screen,publication-summary,publication-configuration-form,publication-confirmation,private-publication-locator}.tsx`; `assessment/domain/PublicationConfiguration.java`, strict `PublicationJsonConfiguration`, `JdbcPublicationStore`; `services/assessment-service/contracts/publications.openapi.yaml` | Backend already accepts maxAttempts 0..10, omission defaults 1; T1 exposes editing, preserving revision/retry and OPEN freeze |
| Student attempts/results | `frontend/features/attempts/`; `StudentAttemptService`, `JdbcStudentAttemptStore`, `AttemptFinalization`, grading policy v1; START/RETAKE/SAVE/SUBMIT/ABANDON/result/aggregate/review in the same OpenAPI | No entitlement/count field in StudentView or AttemptView. T1 must use confirmed `ATTEMPTS_EXHAUSTED`, not guess from a page/ordinal. Existing own review requires CLOSED |
| Teacher results | `frontend/features/teacher-results/components/teacher-results-pages.tsx`; `ClosureService.results`, `JdbcClosureStore.results`, `ClosureViews.TeacherRow`; GET `/api/assessments/publications/{publicationId}/results` | Attempt-row cursor page with nullable score and optional name; no distinct-student directory, whole-publication statistics, histogram, student history or Teacher key-review endpoint |
| FB-012 | ADR-017; `services/identity-service/contracts/user-profiles.openapi.yaml`; `StudentProfiles` / profile client; Identity `/internal/security/user-profiles/batch` | Reuse exactly one post-authorization page-bounded batch (1..100 IDs), exact SERVICE caller/scope, no name cache. Failure yields null name, never missing results; no global name search or roster scrape |
| W4 realtime | ADR-016; four contracts in `shared/api-contracts/` and `shared/event-contracts/realtime-amqp-invalidation-v1.schema.json`; frontend `realtime/`, Assessment V7 outbox, Gateway resource authorization/fanout | Best-effort invalidation only. No score/answer/identity payload. New views must refetch authorized REST, coalesce hints and fence stale responses; no new WS authority/namespace in T1 |
| W3 Activity Evidence | `w3-m-proctoring-contract.md`, `w3m-authoritative-answer-integration.md`; `services/proctoring-service/contracts/monitoring.openapi.yaml`; Assessment V6 outbox / Proctoring inbox | Existing monitored bounded CLASS observations and `ANSWER_PERSISTED` metadata are not a general Assessment audit. Do not enable/repurpose capture or manufacture historical observations |
| LiveKit | `w3-m2-media-contract.md`; `services/proctoring-service/contracts/media.openapi.yaml`; frontend `proctoring/components/{teacher-monitoring-screen,teacher-camera,teacher-timeline}.tsx` | Known-attempt monitoring page, per-session explicit camera authorization and paged timeline already exist. Integrated Publication monitoring/grid belongs to T4, subject to existing grants and privacy/provider gates |
| FB-015 | ADR-018; service `quiz-revocations.openapi.yaml` / `quiz-purgings.openapi.yaml`; Assessment V9–V11, `QuizRevocationGate`; Proctoring fences/purge; Quiz deletion coordinator | Every future derived table/audit/outbox/access receipt must join the same fence and service-owned purge/hold inventory. Current purge list cannot automatically cover future tables |
| Edge/Identity | Gateway exact `PublicationRoutes`/`StudentAttemptRoutes`, service admission inventories, Identity current USER/SERVICE authority | New paths require exact Gateway **and** downstream inventory plus independent JWT/LD-05 checks; no broad authenticated fallback or Identity API expansion |

Existing evidence is historical at `docs/development/w4-acceptance-evidence.md` and
`w4-final-engineering-closure.md`; neither closes reopened PO acceptance. Current
changes add no source snapshots, endpoints or generated DTOs under `shared/`.

## 3. Proposed statistics and distinct-student semantics

Assessment computes each response in a coherent PostgreSQL read snapshot after
current Teacher ownership and deletion-fence checks. Never calculate totals from
the currently loaded UI page or by fetching an unbounded set of pages in the
browser. No foreign database, Redis scoreboard, Identity roster, or Proctoring
presence is authoritative for participation or scores.

For one authorized Publication, group **all its Attempts by studentId**:

- `participatedStudentCount`: at least one committed Attempt of any state,
  including ABANDONED. Not the enrolled roster, visitor count or camera count.
- `activeStudentCount`: at least one persisted ACTIVE Attempt. Expired but not
  yet finalized remains persisted ACTIVE; label as attempt state, not “online”.
- `submittedStudentCount`: at least one SUBMITTED Attempt. A submitted student
  with an ACTIVE retake appears in **both** counters. Counters are not additive.
- `gradedStudentCount`: at least one SUBMITTED Attempt joined to a complete
  immutable result. ABANDONED/ACTIVE never contribute zero grades.
- `latestAttempt`: highest Attempt ordinal for that Student/Publication, then
  attempt UUID as defensive deterministic tie-break. Latest is independent of best.
- `bestResult`: maximum exact earned/possible ratio among graded SUBMITTED
  Attempts, tie by lowest ordinal then result UUID per existing grading contract.
  All use the same immutable snapshot/denominator. An active retake preserves best.
- Row `averageResult`: mean over **all that student's graded SUBMITTED Attempts**;
  encode exact aggregate numerator `sumEarnedWeight` and denominator
  `sumPossibleWeight`, plus `gradedAttemptCount`. Null if none; no intermediate
  division. This is distinct from the row's best score.
- Publication `bestScore`: highest student best ratio; publication
  `averageBestScore`: mean over **one best graded SUBMITTED Attempt per graded
  student**, using summed weights, not an average of row averages. Null with no
  eligible results. Expose sample count so the denominator is explicit.

Current rows are participant rows, not all Classroom roster students. BR-01 owner
self-attempts are ordinary official Student records and are included under the
same rules, with no silent owner exclusion or grade rewriting.

### Exact 20-bin score distribution

Score out of 10 is exactly `10 * earned / possible`. Do **not** persist or compare
a rounded 0–10 value or convert through JavaScript/Java binary floating point.
Keep GRADE-08 exact decimal-string weights and policy `QUIZOPIA_GRADING_V1`.
Presentation alone is two decimal places HALF_UP; a recurring quotient is returned
as its exact weight pair plus display, never mislabeled as an exact finite decimal.

Use precisely one `bestResult` per graded student, over all authorized records.
For bins `i=0..19`, lower bound is `i/2`, upper bound `(i+1)/2`.
Bins are `[lower, upper)`, except bin 19 is `[9.5, 10]`.
Index = `min(19, floor(20 * earned / possible))`, with exact decimal/integer
arithmetic. Reject invalid/out-of-range persisted grades; never clamp corrupt
negative or >10 scores into plausible bins. Exactly twenty ordered bins, including
empty ones; sum(count) = gradedStudentCount. Bound strings are `"0"`, `"0.5"`, …,
`"10"`. Each bin carries `lowerInclusive:true`, `upperInclusive:(i===19)`.

Required examples: 0→bin0; 0.5→bin1; 9.5→bin19; 10→bin19;
earned/possible 1/3→bin6 (3.333…); an exact score below 0.5 that displays 0.50
stays bin0. A student with two submitted scores 4 and 8 plus an ACTIVE retake
contributes once to bin16, once to submitted and once to active. An abandoned-only
student contributes to participated only. Empty results mean 20 zeros and null
score summaries, not a zero average.

## 4. Proposed HTTP/DTO candidate (T2, not implemented)

All paths below are under `/api/assessments/publications/{publicationId}`.
Names are proposed **new** operations; retain existing `/results` unchanged.
Strict allowlisted query/JSON, canonical UUIDs, repository error envelope and
`Cache-Control: no-store` on every protected success/error response.

| Method / suffix | Proposed response and bounds |
| --- | --- |
| GET `/student-overview` | `{publicationId, asOf, activeStudentCount, submittedStudentCount, participatedStudentCount, gradedStudentCount, bestScore, averageBestScore, scoreBins}`; full-publication totals independent of student-list filters and page size |
| GET `/students` | `{publicationId, asOf, items, nextCursor}`; item `{studentId, studentName:null|string, attemptCount, gradedAttemptCount, hasActive, hasSubmitted, latestAttempt, bestResult, averageResult}` |
| GET `/students/{studentId}/attempts` | `{publicationId, studentId, asOf, items, nextCursor}`; existing safe Attempt/result summary shapes, descending ordinal/attemptId; no answers, keys or cross-publication rows |
| GET `/attempts/{attemptId}/teacher-review` | Explicit TeacherReview below, bound to BOTH route Publication and Attempt; no call through the Student review API |
| GET `/attempts/{attemptId}/answer-audit` | Future gated `{items,nextAfter}`; authorized timeline only, `after` nonnegative ordinal default 0, `limit` default 50/range 1..100 |
| POST `/attempts/{attemptId}/answer-observations` | Future gated single strict observation, Student-owned only, authority-derived binding and durable acknowledgment; never accepted in T1 |

Student-list query: `limit` default20/range1..100, opaque `cursor` max2048 bytes,
optional canonical `studentId` equality, optional `status=ACTIVE|SUBMITTED|ABANDONED`
(at least one matching state, independently overlapping), `sort=LATEST|BEST_SCORE`,
`direction=ASC|DESC` default DESC. Default sort LATEST uses latest startedAt then
studentId; BEST_SCORE uses exact best earned weight (same denominator), nulls last
in either direction, then studentId. Filters choose participants but each returned
row still aggregates that student's **entire** history in this Publication.
No arbitrary SQL sort or unbounded name substring search. Display names are
page enrichment, not Identity search keys or authorization evidence.

Cursor is server-authenticated, binds actor, Publication, filters/sort/direction,
last tuple and schema version; malformed/mismatched cursor gives sanitized 400.
Every page re-authorizes; no cursor is a bearer permission. Keyset pages are live
read snapshots, not a frozen export: concurrent score changes can move rows across
pages; frontend deduplicates by studentId and refresh resets pagination. Summary
`asOf` values may differ between requests; do not claim atomic cross-request views.
T2 must test deterministic ordering, page transitions, filtered summaries vs full
totals, null sorts, and concurrent writes. Long-lived export snapshots are outside scope.

Counts/ordinals/revisions serialize as canonical nonnegative decimal strings when
signed-64-bit, validated for overflow; scores use existing Result weight strings.
Bounded bin indexes and array lengths are JSON integers. `asOf` is a UTC instant
with at most microseconds. No email, legal identity, role lists, sourceMarkdown,
private locator, keys or grading internals in directory/statistic/error DTOs.

## 5. Teacher Review security contract candidate

| Caller | Existing Student APIs | Proposed Teacher statistics/history/review | Minimal audit write/read |
| --- | --- | --- | --- |
| Current USER+STUDENT, own eligible Attempt | Total after grading; keys only own SUBMITTED + CLOSED + current CLASS membership where required | DENY without current TEACHER **and** persisted Publication ownership | Own ACTIVE observation write only after all future approval gates; no Teacher timeline read |
| Current owning USER+TEACHER | Student permissions only if independently STUDENT/eligible; Teacher role adds nothing here | Statistics/history after ownership; OPEN/CLOSED Teacher Review after dedicated contract approval, CLASS current Teacher authority required | Owner-scoped timeline read only after policy approval, access audited |
| Dual TEACHER+STUDENT owner, including BR-01 self-attempt | Exactly the same CLOSED gate on Student route | Separate Teacher route may disclose OPEN keys because it independently verifies ownership, not because caller is an Attempt owner | Same endpoint-specific rules; no merged Student/Teacher DTO |
| Other Teacher, Student peer, ADMIN-only, SERVICE token, anonymous | DENY outside existing scoped contracts | DENY; no implicit admin/service/locator override | DENY |
| Revoked/disabled/stale role, untrustworthy LD-05, deleted/fenced Quiz | Fail closed under existing contracts | Fail closed before query/cache/audit response | Fail closed before append, replay, lookup or return |

Gateway and Assessment independently verify RS256 issuer/expiry/ADR-014 USER
role/current LD-05 authority. Resource lookup verifies persisted Publication owner,
Attempt.publicationId, immutable snapshot association and current CLASS Teacher
authority. Wrong owner/mismatched binding/missing resource returns masked 404;
wrong role 403; unavailable required authority 503; DRAFT review 409
REVIEW_NOT_AVAILABLE; fence uses existing ASSESSMENT_REMOVED semantics. No key
disclosure merely from UUID/locator possession, role-aware navigation or workspace.

TeacherReview projects `{publicationId,attemptId,snapshotId,state,revision,
gradingPolicyVersion,result,questions}`. Each question includes existing safe
question content and typed `persistedAnswer`, typed `correctAnswer`, optional
explanation, and nullable persisted per-question grading weights. OPEN ACTIVE
answers are a point-in-time committed SAVE view (not unsaved browser input);
result/grading details are null until actual graded SUBMITTED state. ABANDONED
may show its retained answers to the authorized Teacher but must not fabricate a
grade or Student review entitlement. Never run a finalizer, recompute a grade,
fetch current Quiz content or return raw protected snapshot/source. Existing
Student review stays SUBMITTED-only + CLOSED.

Audited access must durably record successful sensitive Teacher review/timeline
disclosure with actor/resource/time/purpose/outcome, no answer/key/body/token.
If required audit persistence fails, do not return the sensitive response.
Denied security telemetry is sanitized and rate-bounded. Access-receipt TTL/holds
also need privacy approval; no hidden indefinite collection. Owner can already
know authored keys; DEC-01 intentionally accepts that risk even while making an
official BR-01 attempt. It does not entitle Student route key disclosure.

## 6. Minimal append-only Assessment audit candidate

Assessment owns this **assessment interaction** record; Proctoring continues owning
monitored CLASS surveillance/media evidence. PUBLIC answer interaction audit, if
subsequently approved for collection, is not permission for PUBLIC proctoring,
tab visibility, focus, camera or device surveillance. No new capture in T1.

### Category and provenance reconciliation

| Logical category | Proposed canonical type / provenance | Exact meaning |
| --- | --- | --- |
| START | `ATTEMPT_STARTED` / SERVER_AUTHORITY | Newly committed Attempt only, not START selecting/resuming/replaying old work |
| persisted SAVE | `ANSWER_PERSISTED` / SERVER_AUTHORITY | Committed persistence fact, binds question + revision; operationKind distinguishes SAVE, SUBMIT_REPLACEMENT, FINALIZATION_NORMALIZATION; not proof of a browser selection |
| SUBMIT / TIMEOUT | `ATTEMPT_SUBMITTED` / SERVER_AUTHORITY | One committed graded finalization with existing MANUAL_SUBMIT, BOUNDED_TIMEOUT or TEACHER_NOTICE_CUTOFF reason; do not also fabricate a manual submission |
| ABANDON | `ATTEMPT_ABANDONED` / SERVER_AUTHORITY | Existing STUDENT_ABANDON terminal transition, ungraded |
| cutoff / result | `CLOSURE_NOTICE_CONFIRMED`, `RESULT_GRADED` / SERVER_AUTHORITY | Actual durable notice accessibility/cutoff and persisted result identity; no queued-notice delivery claim or historical backdating |
| minimal browser | `QUESTION_VIEWED`, `QUESTION_LEFT`, `ANSWER_SELECTED`, `ANSWER_CHANGED`, `ANSWER_CLEARED` / CLIENT_OBSERVATION | Claimed local question interaction only; incomplete/manipulable, never proof SAVE committed or cheating occurred |

Legacy Proctoring `source=ASSESSMENT_AUTHORITY` maps conceptually to
SERVER_AUTHORITY but its deployed schema/hash stays unchanged. Do not rename
existing events or conflate browser answers with the producer-owned
`ASSESSMENT_ANSWER_PERSISTED` v1 envelope. New Assessment wire representation is
separate; copying facts to Proctoring still requires its existing CLASS eligibility.

Proposed stored envelope: `schemaVersion:1`, UUID `eventId`, authority-derived
publication/attempt/student/actor binding, positive per-Attempt server `ordinal`,
type, provenance, server `receivedAt`, nullable producer `occurredAt` / client
`observedAt`, nullable questionNumber, nullable canonical string revision,
allowlisted operationKind/terminalReason and opaque result/notice/answer-version
reference where appropriate. Never store answer value, selected option, correctness,
answer/key digest, free text, URLs, keystrokes, clipboard, locator or tokens in the
audit. Low-entropy answer hashes are guessable and are not privacy protection.

Answer references are opaque internal identifiers bound to immutable persisted
versions, not public URLs or decryption credentials. No duplicate answer payload
store. Any dereference must use the same Teacher review authorization/FB-015 fence;
missing expired versions remain “unavailable”, never reconstructed from current
answers or Quiz drafts. Do not backfill a historical interaction sequence from
present answers. Pre-feature Attempts explicitly report audit unavailable/partial.

Browser ingress candidate: one event per request, ≤4 KiB body, strict duplicate-key
and unknown-field rejection; UUID eventId/streamId, nonnegative signed-64 sequence,
allowlisted type, known snapshot questionNumber and optional ISO observedAt only.
Binding/provenance/ordinal/revision/result references are never supplied by browser.
One in-flight request, memory-only queue cap100, bounded backoff/three transient
retries respecting 429; report gaps/dropped observations truthfully. No offline
disk queue, localStorage, beacon-on-unload workaround or sending after authority
ends. Proposed server limits: 60/minute/Attempt, 120/minute/actor, bounded global
concurrency8; 429 sanitized no-store. These are reviewable engineering budgets,
not retention policy or production capacity certification.

Store event and canonical identity/acknowledgment in one transaction. Unique
`(attemptId,eventId)` and `(attemptId,streamId,sequence)`; identical freshly
authorized replay returns original `{eventId,ordinal,receivedAt}`, changed reuse
409 EVENT_CONFLICT without mutation. Accept out-of-order client sequences, order
by server ordinal; observedAt is a claim, receivedAt is authoritative receipt,
occurredAt is actual producer capture, not inferred timely learner action.
No cross-source global causal order or exactly-once network guarantee.

Server facts join the authoritative business transaction or transactional outbox;
rollback means no fact. Broker outage cannot block on network I/O inside SAVE or
change grade/deadline. Consumer inbox/retries deduplicate producer IDs with bounded
leases and authenticated publisher provenance under ADR-009. Audit storage failure
must not produce a fabricated successful business receipt; define coherent rollback
in T2. Read-only audit queries must not mutate academic state.

PostgreSQL/Flyway append guards and separate owner/runtime/custodian roles enforce
no ordinary UPDATE/DELETE. Privileged database administrators remain a trust
boundary; a hash is not proof against them. Private TLS, encrypted storage/backups,
bounded query/body budgets and no-store responses are required. Audit values are
excluded from ordinary logs, analytics, replay tools and error/APM body capture.
Teacher timeline pagination uses server ordinal and re-authorizes every page;
missing or manipulated observations are not academic sanctions. No automatic
conviction/fail/zero-score behavior.

## 7. Retention, deletion, compatibility and threat controls

- TTL, expiry anchor, legal hold grounds/reviewer/expiry, access-receipt TTL,
  notice/lawful basis/minors/accommodations and qualified privacy/legal sign-off
  remain **PENDING**. Code for future synthetic tests must not invent a collection
  default or indefinite retention. Collection gate is OFF until all approvals
  and independent activation authorization exist; reject enabled production
  configuration when required policy is missing. T1 adds no such flag or collector.
- ADR-018 applies to new audit/derived/result access surfaces **before replay and
  return**. Race append/read vs deletion must serialize at the same service-owned
  fence boundary. Add future tables to privileged FK-safe bounded purge, write
  guards, holds, receipts and anti-resurrection tests; do not disable triggers or
  give runtime owner privileges. A legal hold restricts custody, not ordinary access.
- Inventory primary rows, answer references, access audits, outbox/inbox/retries,
  replicas, exports/caches, encrypted backups, telemetry and any provider copies.
  No external answer-audit provider is introduced. Future LiveKit copies still
  need actual provider/operations receipts. Restore must enforce tombstones before
  admission. PRIMARY_PURGED/COPY_EXPIRY_PENDING never means ALL_COPIES_ERASED.
- T2 uses additive migrations after current Assessment V11/Proctoring V5, rechecks
  actual latest versions and verifies clean/upgrade checksums. No T1 migration,
  legacy snapshot/grade rewrite, arbitrary historical fabrication or new grading
  policy. Null/unavailable audit coverage is explicit for legacy records.
- Additive new DTOs/OpenAPI and derived frontend contracts land with T2 actual
  endpoints, not speculative generated clients in T1. Old `/results`, Student
  `/review`, existing source/provenance hashes and replay receipts remain compatible.
- Any new indexes/read models must remain Assessment-owned, fence-aware and
  purgeable; transactional reads over authoritative tables are the initial design.
  Timeouts yield honest unavailable responses, not page-derived approximations.

| Threat | Required control and negative evidence for T2–T4 |
| --- | --- |
| IDOR / confused deputy / dual-role leakage | Wrong Teacher, cross-Publication Attempt, peer Student, role-only owner, ADMIN/SERVICE, revoked role/account, direct service call and forged cursor denied; Student OPEN review omits keys even for dual-role owner |
| Key/PII exfiltration | Separate DTOs/routes; strict projection tests on entry/SAVE/result/error/WS; no-store; no raw source or body logs; Markdown rendered by existing safe renderer |
| Page-derived statistics / rounding errors | >100 students, many retakes, overlaps, abandoned-only, best tie/latest separation, exact half-bin and recurring values, all 20 bins; totals invariant across pages/filters |
| Spoofed/replayed observation | Server-only provenance denied to client; oversize/duplicate JSON/unknown question/rate budget/conflicting event/stream/sequence tested; duplicate/reorder/restart converges |
| Audit/academic coupling | Transaction rollback, outbox outage/recovery, no false persistence fact, no camera/client observation modifying answer/grade/deadline |
| Erasure resurrection / excessive retention | Fence before cached replay, concurrent purge/append/read, new-table least privilege, holds and denied restore; no real collection with pending policy |
| Realtime/media authority confusion | WS carries hints only; reconnect/visibility/online/periodic REST resync, stale-response fencing; LiveKit grant independently checks current eligible CLASS authority and peer isolation |

## 8. Task boundaries and Leader checklist

T1 implementation: reuse Corporate Trust components/tokens; remove duplicate chips,
retain full settings/actions and single-column details; one masked locator box with
44px keyboard-operable reveal/copy, one-line ellipsis and full clipboard value;
Results Refresh remains; existing authoritative publication resync and uncertain
mutation recovery remain; failed detail reads retain explicit retry. Edit maximum
in create/edit DRAFT, validate 0..10 integer/legacy1, show in Review/Confirm, freeze
after OPEN. A confirmed exhaustion disables another retake intent without guessing
entitlement; uncertain retake preserves its exact operation key. No extra backend
functionality, new production flag or sensitive audit activation.

Independent Leader must:

- [ ] Inspect exact PR HEAD and actual CI, not an earlier SHA or this agent's verdict.
- [ ] Review the frozen candidate's owner/resource/dual-role permission matrix;
  decide separate Teacher Review security approval before T2 implementation.
- [ ] Accept or amend statistics, score-bin, pagination, audit category/ownership
  proposals explicitly; retain every privacy/TTL/legal/provider gate as pending.
- [ ] Verify vi/en, 375/768/1280/1920 geometry, keyboard focus/clipboard behavior,
  create/edit confirmations, legacy defaults, CLOSED secrecy and retake retries.
- [ ] Confirm no Tasks 2–4 implementation, new ingestion, production activation,
  unrelated local runtime files or deployment changes in diff.
- [ ] Review/merge T1 independently; authorize Task 2 explicitly only afterward.

Evidence/limitations: [T1 delivery record](../development/w4f-t1-delivery.md).
