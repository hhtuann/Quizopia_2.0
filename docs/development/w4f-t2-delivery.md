# W4F-T2 — Assessment backend implementation and review record

Authority: [Issue #134](https://github.com/hhtuann/Quizopia_2.0/issues/134), including
[Leader authorization 6519641252](https://github.com/hhtuann/Quizopia_2.0/issues/134#issuecomment-6519641252).
Predecessor: merged PR #137. Baseline `origin/develop`:
`4c7803733eee53997c7c0cbce99af1136c86014a`.
Branch: `feat/w4f-t2-assessment-backend`; PR target: `develop`.
Status: **READY FOR INDEPENDENT LEADER REVIEW — SAFE INCREMENT VERIFIED**.
Production remains **NO-GO**. This is not Task 3 or Task 4 authorization.

## 1. Baseline verification before implementation

Fetched origin and waited for all three **post-merge develop** workflows at the
exact baseline, rather than assuming PR-head CI established merge acceptance:

- [Frontend CI 41034720144](https://github.com/hhtuann/Quizopia_2.0/actions/runs/41034720144): SUCCESS.
- [Backend CI 41034719958](https://github.com/hhtuann/Quizopia_2.0/actions/runs/41034719958): SUCCESS, eight matrix jobs.
- [Repository validation 41034719909](https://github.com/hhtuann/Quizopia_2.0/actions/runs/41034719909):
  SUCCESS, including actual full-validation (21m59s) and compose-config.

No baseline CI failure was observed. The dedicated branch was created from the
fresh verified develop checkpoint. Six unrelated untracked Windows runtime files
remain user work, preserved and excluded.

## 2. Ownership and delivered APIs

Assessment owns all new business data/queries. Gateway independently verifies
USER roles/current LD-05 and forwards only the exact reviewed paths. No cross-service
database access or new service is introduced. All responses/errors are no-store.
New operations inherit the existing default-OFF results feature gate; no production
flags or deployment configuration were changed.

All paths below are under `/api/assessments/publications/{publicationId}`:

| Operation | Delivered behavior |
| --- | --- |
| GET `/student-overview` | Full-publication distinct ACTIVE, any SUBMITTED, participated and graded-student counts; best and average-best exact score pairs; all twenty bins |
| GET `/students` | Bounded live keyset page with student equality/state filter, LATEST/BEST_SCORE sort, ASC/DESC, attempt/graded counts, overlapping states, latest Attempt, latest graded result, best result and graded-only mean |
| GET `/students/{studentId}/attempts` | Separate Attempt rows, descending ordinal, timings/state/reason/result and truthful sensitive-review availability |
| GET `/attempts/{attemptId}/teacher-review` | Implemented immutable submitted-answer/snapshot/per-question-grade projection; graded SUBMITTED only; application returns `503 SENSITIVE_POLICY_PENDING` while required receipt/privacy policy is pending |
| POST `/attempts/{attemptId}/answer-observations` | Strict encrypted observation append/replay implementation verified with synthetic permits; application fails closed before parsing observation input while policy is pending |
| GET `/attempts/{attemptId}/answer-audit` | Submitted-only owner Teacher partial timeline, protected transition decryption and committed access receipt; application remains policy-gated |

Implemented artifact:
[`teacher-assessments.openapi.yaml`](../../services/assessment-service/contracts/teacher-assessments.openapi.yaml).
Existing Publication/Student OpenAPI and generated clients are unchanged. New
TypeScript client types derive from this artifact using
`scripts/local/generate-teacher-assessment-types.py`, reusing existing derived
Result/QuestionReview types. Generator `--check`, exact response-record property
tests and path-inventory tests guard drift. No Dashboard/collector/browser UI exists.

## 3. Exact aggregation and page semantics

- SQL groups **all** authorized Publication Attempts by studentId. ACTIVE and
  SUBMITTED overlap; participated includes abandoned-only students and is not their sum.
- Best selects one graded SUBMITTED result, exact earned weight descending,
  ordinal ascending then result UUID. An ACTIVE retake never hides the prior best.
- Latest Attempt and latest graded result are independently selected by ordinal.
  ACTIVE/ABANDONED results remain null and contribute no artificial zero.
- Per-student means use summed earned/possible weights over all graded submissions;
  Publication average-best uses one best result per graded student. Null means no
  eligible samples. Score display out of ten uses HALF_UP two places only;
  existing Result percentageDisplay remains its established 0–100 representation.
- Twenty upper-inclusive bins are `[0,0.5]`, `(0.5,1]`, …, `(9.5,10]`.
  PostgreSQL integer-quarter `div(20*earned*4-1,possible*4)` with explicit zero
  handling avoids approximate division. Counts sum to gradedStudentCount.
- Pages default20, range1..100. UUID ties are stable. Existing HMAC cursor tooling
  binds actor, Publication, filter, sort/direction and position; implemented input
  budget is1024 bytes, reusing the established codec (tighter than the candidate's
  proposed2048 budget). Every page re-authorizes; live score changes may move rows
  between pages, so consumers deduplicate by studentId and reset on refresh.
- One bounded SQL reference batch supplies latest/latest-graded/best Attempts;
  one post-authorization FB-012 profile call enriches only current-page distinct
  students. Unknown/unavailable profiles remain null. No N+1 Identity requests or
  name cache. Publication row locks serialize reads with authoritative writes/fences;
  READ COMMITTED ensures a fence committed while waiting for the lock is observed.

## 4. Authorization and sensitive disclosure

| Boundary | Enforcement |
| --- | --- |
| Teacher metadata | Issued/current USER+TEACHER at Gateway and service, persisted Publication owner, current CLASS Teacher authority before local queries |
| Full Teacher Review / new audit read | Above checks plus OPEN/CLOSED and coherent graded SUBMITTED target; deny ACTIVE, ABANDONED, DRAFT, incomplete result and foreign Publication/Attempt binding |
| Student observation write | Issued/current USER+STUDENT, own ACTIVE Attempt, current CLASS Student membership, deadline/cutoff, fresh authority and policy permit; browser actor/source/snapshot/revision fields rejected |
| Dual-role BR-01 | Ordinary Student participation retained; separate Teacher route has separate ownership/state checks; submitted sibling gives no ACTIVE answer access |
| Student review | Existing ASSESS-04 CLOSED-only submitted-owner projection unchanged; no Teacher role or new endpoint weakens it |
| Sensitive receipt | Minimal actor/resource/time/purpose/expiry, no content; insert commits before response. Missing policy, required key, authority, receipt persistence or fence check fails closed |

Remote CLASS checks run outside local transactions with a five-second authority
budget and no positive cache. Local operations recheck resource binding, JWT expiry
and FB-015 fences under publication-first lock order. The bounded cross-service
in-flight sample race is not a claim of distributed atomicity. Reads do not finalize
Attempts, regrade, fetch mutable Quiz content or expose raw snapshot/source.

`SensitiveAssessmentPolicy.Pending` and `AuditEncryption.Pending` are unconditional
compiled application defaults, not attestations/flags. There is **no learner-enable
setting or production key binding**. Test-only mocks/constructors supply disposable
expiry/key material to verify positive paths; they are not included as runtime
activation configuration. History reports teacherReviewAvailable=false in the
pending application policy.

## 5. Audit foundation, provenance and explicit blocked scope

The new ingress accepts only strict bounded CLIENT_OBSERVATION envelopes. Choice
detail is snapshot-bound A–D before/after sets for SINGLE/MULTIPLE_CHOICE; matrix
detail is unsupported and numeric answer events/values/keystrokes are prohibited.
An unsaved []→A→C sequence is stored as two observations, never inferred from SAVE.
Canonical replay input is recursively ordered, encrypted with AES256-GCM and
authenticated resource/event/stream/sequence context. Identical retries compare
canonical plaintext only inside the authorized backend, never randomized ciphertext
or unkeyed answer hashes. Changed event/stream/sequence/transition conflicts409.
Per-Attempt ordinals order receipt, not trusted browser chronology. Exact committed
replay may return metadata after terminalization, but never after erasure fencing.

Ingress is capped at4096 bytes, global concurrency8, accepted events60/min/Attempt
and120/min/actor. The actor budget is serialized without token/session keys.
No offline disk queue, browser collector, beacon, credentials in payloads or
question/answer-key text. Client sequences are signed64 JSON integers; future JS
producers must remain within their safe-integer range rather than round values.
Teacher pages are bounded, never claim complete browser history, omit expired
events and distinguish unavailable/unsupported/legacy coverage without fabrication.

**Not activated / deliberately blocked by pending approvals:**

- All real-learner observation ingestion and sensitive review/audit reads.
- Production key custody/rotation, privacy notice/lawful basis/minors/accommodations,
  audit/read-receipt TTL/expiry/legal-hold policy, backups/providers and activation.
- New automatic server-fact capture into this unified audit timeline. Existing
  authoritative Attempt/finalization/closure records and W3 metadata outboxes remain
  unchanged; they are not backfilled or relabeled as new observations. The new schema
  reserves separate SERVER_AUTHORITY fields, but there is no fabricated producer,
  new Rabbit payload or new capture trigger/enable flag.
- Periodic retention cleanup. Synthetic expiry suppression and FB-015 purge are
  verified mechanics, not approved retention duration or a real-learner cleanup job.

These limits implement the Leader's explicit safe-independent-pieces condition;
neither synthetic permits nor a merge closes the missing approvals. No 7/30/90/365-
day retention default is introduced. Browser observations never prove misconduct.

## 6. Migration and FB-015 compatibility

Additive Assessment **V12** adds indexes, `assessment_answer_audit` and
`assessment_sensitive_read_receipts`; all prior migration bytes remain unchanged.
Validated service-local binding FKs, immutable-history triggers and existing
admission/fence triggers guard inserts/updates. Owner/runtime/custodian remain
separate; runtime receives SELECT/INSERT only, not UPDATE/DELETE/TRUNCATE/DDL.
No roles or credentials are provisioned by the migration.

The existing V11 purge implementation is renamed/private; only a scoped SECURITY
DEFINER wrapper is executable by runtime. It drains new children within the caller's
remaining budget before invoking V11, preserving existing progress step numbers,
held custody, verified-zero semantics, receipts and restart/retry behavior. No
trigger disabling, cross-database CASCADE or ciphertext archive. Provider/key/backup
erasure evidence remains separate; no ALL_COPIES_ERASED claim.

## 7. Verification and engineering review

Focused primary checks passed before the full-suite review: whole-population
121-student histograms/pages; row/history/score semantics; signed-RS256 HTTP roles,
foreign-owner/binding denial and dual-role Student OPEN secrecy; synthetic A→C,
encrypted replay, concurrency, quota, hold/fence/purge and receipt failures; Gateway
exact inventory/admission/body bounds; typecheck/format and generated-type drift.

The first full Assessment review run executed542 tests and failed six assertions:
five pre-V12 migration expectations and one notice-confirmation setup assertion
before a delayed-closure race. The three-case focused race rerun passed, but the
first full result is not classified as passing or dismissed as infrastructure.
Migration expectations are updated without removing checksum/ownership/no-rewrite
checks. Read-only review also reproduced cross-JVM canonical map-order instability;
recursive sorted serialization and a fresh-JVM regression correct it.

The explicitly authorized CI reliability correction replaces raw numeric-substring
non-disclosure checks with an exact twelve-field typed allowlist, binding/UUID/time
assertions and deterministic UUID metadata containing both0001/0002. It repeats
five times; disclosure protection is not bypassed by random UUID digits.

The complete rerun passed, including all42 delayed-closure tests without a closure
business-rule or fixture workaround. The original one-off preparation failure is
retained as observed/unproven, not called a demonstrated root-cause fix.
Read-only engineering re-review confirmed the canonicalization/migration findings
resolved and found no outstanding actionable code defects. That is not independent
Leader/security/privacy approval.

### Executed final verification

| Command / verification | Actual result |
| --- | --- |
| Assessment `.\mvnw.cmd -B spotless:apply verify` | BUILD SUCCESS, **543 tests**, 0 failures/errors/skips, 7m46; includes22 audit unit tests (six fresh JVM probes),8 audit PostgreSQL tests,10 Teacher query tests,4 signed-HTTP tests,2 contract parity tests,17 monitoring-outbox tests including five disclosure repetitions, and4 ArchUnit rules |
| Gateway `.\mvnw.cmd -B spotless:apply verify` | BUILD SUCCESS, **184 reported / 182 executed successfully**, 0 failures/errors,2 opt-in browser skips,14m40 |
| Gateway actual W4 topology `e2_journey7_attemptLifecyclePropagation` | PASS: real Identity/Classroom/Assessment processes, PostgreSQL/Redis/RabbitMQ and Gateway; new ACTIVE→SUBMITTED totals, exact bins, directory/history, Student-role denial, policy-gated Teacher Review503 and unchanged Student OPEN review409, alongside real submit/WS propagation |
| `pnpm typecheck`, `pnpm lint`, `pnpm format:check` | PASS; only new generated client types changed in frontend |
| Isolated validation Python → `scripts/local/generate-teacher-assessment-types.py --check` | PASS, derived client types match implemented artifact |
| Release-register validator and `test-w3-production-release.py` | PASS; **60** governance tests; inherited6 BLOCKED /5 NOT VERIFIED release gates preserved |
| `docker compose config --quiet`, `git diff --check` | PASS |
| Prior V1–V11 migration comparison vs develop; clean/upgrade migration tests | Prior scripts unchanged; all upgrade/checksum/no-rewrite/role assertions pass |

Gateway's two skips are exactly `w4.browser` and `fb015.browser`, requiring explicit
opt-in. No new real-browser/full-product/T4 acceptance, production transport,
provider or backup qualification is claimed. New signed-HTTP positive sensitive
tests use an explicit synthetic policy override; the real multi-service topology
proves that the application's actual pending policy fails closed.

Final exact commit/PR HEAD and GitHub CI receipts are posted on Issue #134 and the
PR after commit; this file does not invent a self-referential commit SHA or claim
an unexecuted check. Independent Leader/security review remains required. No
Task3/4, deployment, production flag change or merge is authorized here.

## 8. Independent Leader checklist

- [ ] Verify exact PR HEAD, complete diff, migration checksums and actual CI results.
- [ ] Inspect SQL aggregates/pages/bins across all records and bounded profile calls.
- [ ] Check graded-SUBMITTED-only Teacher disclosure, current CLASS/Identity authority,
  Student CLOSED secrecy, dual-role/foreign bindings and receipt fail-closed behavior.
- [ ] Inspect synthetic crypto/canonical replay, append-only/role/fence/purge guards.
- [ ] Confirm no learner activation path, new collector/server capture or guessed TTL.
- [ ] Disposition explicitly blocked sensitive activation/retention/provider work.
- [ ] Review/merge independently; authorize Task3 only through explicit handoff.
