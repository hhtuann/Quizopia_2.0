# ADR-015: W3-B/W3-C Leader product policy and historical amendments

Status: **LEADER PRODUCT POLICY: APPROVED. ENGINEERING CONTRACT DIRECTION: APPROVED WITH CONDITIONS — ENGINEERING CONTRACT FREEZE.**

Recorded: 2026-10-13 (Asia/Bangkok), from the Leader-approved policy supplied in
the initial contract assignment. The subsequent Leader directive below records
conditional engineering sign-off; it authorizes no production activation.
Baseline: merged W3-A PR #78, `8777d484645117b910fd9735ec2f8b8fd4eee4df`.

## Authority and scope

This decision supersedes conflicting recommendations in the Assessment Core,
W3-A and open-question documents for W3-B/C only. Historical LD-01/LD-04 text
remains intact. LD-02/03/05, LD78-01..04, ADR-003/007/008/009/013/014, service
ownership and immutable QuizVersion/snapshot protections remain applicable.

**Already implemented W3-A:** DRAFT create/read/update, OPEN with atomic snapshot,
OPEN schedule extension and safe CLASS/PUBLIC entry; exact routes are in
[ASSESS-15](../specifications/assess-15-publication-contract.md). No Attempt,
CLOSE, grading, result, review or authenticated notification inbox is implemented.

**Proposed engineering pack:** [lifecycle](../specifications/w3-b-attempt-lifecycle-contract.md),
[closure/notices](../specifications/w3-b-closure-notification-contract.md),
[grading/results](../specifications/w3-c-grading-results-contract.md),
[persistence/concurrency](../architecture/w3b-w3c-persistence-concurrency-design.md),
[HTTP/OpenAPI](../specifications/w3b-w3c-http-contract.md) and
[review/sequence/evidence](../architecture/w3b-w3c-contract-review-evidence.md).
The engineering direction in this pack is now approved subject to EC79-01..04
below. Its endpoints/migrations remain PROPOSED/UNIMPLEMENTED artifacts; independent
read-only amendment review, implementation review and runtime/security acceptance
remain required. This assignment adds no executable migration or endpoint.

## Accepted decisions

| ID                       | Leader-approved behavior                                                                                                                                                                                                                                                                                                                                                                                                            |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ASSESS-10                | `maxAttempts=0` unlimited; `1..10` finite; otherwise invalid. Existing Publications default to 1. Freeze on OPEN. Separate Attempt identity and ordinal; at most one ACTIVE per Student/Publication. START creates first, resumes ACTIVE, otherwise returns latest terminal. Only explicit RETAKE creates another; current eligibility, OPEN/window, terminal prior Attempt and entitlement required. ABANDON consumes entitlement. |
| ASSESS-11                | Question-scoped SAVE; Attempt-wide monotonic revision, expectedRevision and durable operation identity. Identical replay returns prior acknowledgment, changed replay or independent stale revision conflicts. Terminal Attempts immutable; serialize all finalizers with SAVE.                                                                                                                                                     |
| ASSESS-12/17             | Duration is integer `0` or `5..240`; 0 pins null deadline, positive pins authoritative start plus duration. CLASS/PUBLIC retain `startAt < endAt` and inclusive-start/exclusive-end new creation. Existing ACTIVE unlimited work may continue after endAt. Window end never finalizes, closes or releases answers. Preserve existing bounded deadlines; no independently approved post-OPEN duration mode switch.                   |
| ASSESS-16                | Manual SUBMIT may atomically replace answers with a complete final set and expectedRevision. Serialize eligibility using server time; persist final answers, deterministic exact grade, policy and terminal state together. Matching completed retry returns stored result; conflicting submit 409. Timeout uses latest committed answers, exactly once; no timely-worker dependency or grace.                                      |
| ABANDON                  | Explicit, Student-owned, durable, irreversible, idempotent, terminal and ungraded. Excluded from aggregation and submitted-owner review. Close/logout/disconnect/inactivity never imply ABANDON. Allowed strictly before applicable notice cutoff.                                                                                                                                                                                  |
| GRADE-07                 | Student numeric answer is exactly four characters after outer ASCII whitespace trim under ADR-013 grammar. Blank/null unanswered; malformed nonblank 400 without mutation. Preserve accepted spelling; compare exact decimal numerical values.                                                                                                                                                                                      |
| GRADE-08                 | Equal maximum question weights; preserve GRADE-01..06 and policy v1, exact earned/possible weights and no intermediate rounding. Student-visible decimals are strings. Percentage presentation is two places HALF_UP only. Highest successfully graded Attempt determines aggregate; ABANDON contributes no zero.                                                                                                                   |
| ASSESS-13 ordinary CLOSE | Current owning Teacher; serialize/reconcile overdue bounded Attempts; authoritative persisted ACTIVE check; sanitized `409 ACTIVE_ATTEMPTS` with no Student identities/counts. CLOSED only when every Attempt terminal. No automatic close at endAt.                                                                                                                                                                                |

Unlimited retakes with immediate total-score feedback are an accepted trade-off;
correctness, keys and explanations remain hidden until durable CLOSED. Submitted
owners (including timeout/closure-finalized owners) may then review their own
Attempt; ABANDONED owners do not gain review eligibility. Teacher results require
owned-Publication authorization.

## LD-01 amendment

The original [LD-01](../specifications/w3-a-publication-scheduling-monitoring-policy.md#ld-01--participation-timing-and-duration)
5..240 rule and deferral of unbounded assessments describe the historical W3-A
approval. W3-B/C now also permit duration 0, meaning unlimited individual duration,
with a null deadline. The participation window remains bounded. Practice timing
remains deferred. PUBLIC monitoring remains DISABLED; unlimited CLASS cannot
enable the monitoring bundle. Bounded CLASS keeps explicit optional monitoring.
No existing OPEN bounded configuration/deadline is rewritten and no post-OPEN
bounded/unlimited switch is approved.

## LD-04 amendment: C1 through C10

The historical [LD-04](../specifications/w3-a-publication-scheduling-monitoring-policy.md#ld-04--closing-with-active-attempts)
ordinary CLOSE rule remains. The following narrow delayed-closure mechanism is
now explicitly approved; it is not general force-close or timer closure.

| Clause | Approved requirement                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| C1     | REQUEST only at/after endAt. Durable request records owning Teacher, authoritative time, relevant ACTIVE unlimited Attempts and required notifications. Committed authenticated owner-accessible in-app notice suffices, email supplementary, read receipt unnecessary. Queued/inaccessible notice does not start time. Each cutoff equals its confirmedNoticeAt plus exactly 48 hours. Missing/failed notice blocks that Attempt's forced finalization. |
| C2     | Eligible owner may RESUME/SAVE/SUBMIT/ABANDON strictly before individual cutoff under ordinary security/lifecycle/revision checks. At/after cutoff no new Student mutation.                                                                                                                                                                                                                                                                              |
| C3     | Time sampled in serialized authoritative operation, strictly before cutoff; HTTP arrival/client clock gives no entitlement. Mutations and finalization produce one coherent outcome.                                                                                                                                                                                                                                                                     |
| C4     | Separate REQUEST and COMPLETE. REQUEST leaves OPEN and creates pending workflow/notices. COMPLETE is owning-Teacher action: reconcile bounded expiry, finalize eligible unlimited work and commit CLOSED only when all terminal. Workers may assist but never commit CLOSED. Early ordinary CLOSE allowed when all terminal; resolve pending workflow durably.                                                                                           |
| C5     | Owning Teacher may CANCEL before closure-driven finalization begins. After it begins/commits, cancellation forbidden; no undo. Replacement is a new request/notices/fresh 48 hours, never cutoff edit. REQUEST/CANCEL/COMPLETE retry-safe; cancelled work cannot reactivate.                                                                                                                                                                             |
| C6     | Pending workflow freezes endAt extension, duration mode, maxAttempts, window reopening and new START/RETAKE. Existing bounded deadlines fixed. Extension requires cancellation while permitted.                                                                                                                                                                                                                                                          |
| C7     | Durable per-Attempt progress, bounded recoverable batches, exact-once grade. Answers/result/terminal/audit commit atomically per Attempt. Partial batches survive crash; unresolved ACTIVE/notice/finalization keeps OPEN. Only authoritative Teacher COMPLETE can commit CLOSED.                                                                                                                                                                        |
| C8     | Distinct `TEACHER_NOTICE_CUTOFF` reason; audit Attempt/Publication/request/Teacher, notice/cutoff, finalizer/time, grading policy/result. Student owner unchanged; never impersonate manual SUBMIT or log protected answers/credentials.                                                                                                                                                                                                                 |
| C9     | Closure finalization is a graded terminal submission from committed answers, included in highest-score aggregation and post-CLOSED submitted-owner review. ABANDON remains ungraded/ineligible.                                                                                                                                                                                                                                                          |
| C10    | Owner-specific notice/status shows Publication, Teacher initiation, exact cutoff, 48-hour warning, pre-cutoff SAVE/RESUME/SUBMIT rights, unsaved-work exclusion, consequences and eventual result. Authorized information only; countdown informational.                                                                                                                                                                                                 |

## PR #79 Leader engineering sign-off — EC79-01 through EC79-04

Recorded: 2026-10-13 (Asia/Bangkok), from the Leader's authoritative
**APPROVED WITH CONDITIONS — ENGINEERING CONTRACT FREEZE** directive following
reviewed HEAD `0ec889ee7dcd128c0506b53acac1f21ff6229dfe`.

| Boundary                                                | Current status                                              |
| ------------------------------------------------------- | ----------------------------------------------------------- |
| Leader product policy in this ADR                       | APPROVED; existing ASSESS/GRADE/C1..C10 decisions unchanged |
| Engineering contract direction                          | APPROVED WITH CONDITIONS — EC79-01..04                      |
| Executable W3-B/W3-C backend implementation             | NOT YET IMPLEMENTED                                         |
| Independent security review and real runtime acceptance | STILL REQUIRED                                              |
| Production activation                                   | NOT AUTHORIZED                                              |

The approved direction covers START/RESUME/RETAKE, maxAttempts 0..10, duration 0
or 5..240, durable question SAVE, ABANDON, coherent SUBMIT/TIMEOUT, synchronous
exact grading/GRADE-07/08, protected results/review, ordinary CLOSE, Teacher
REQUEST/CANCEL/COMPLETE, the real Assessment inbox, individual 48-hour cutoffs,
additive PostgreSQL migrations and Publication-first concurrency.

### EC79-01 — Conditional narrow Identity recipient eligibility approval

The future W3-B3 operation
`POST /internal/security/notice-recipients/{studentId}/eligibility`, requiring
`identity.notice-recipient.read`, is conditionally approved with all safeguards:

1. SERVICE-only authentication; USER bearer never accepted.
2. Exact allowlisted Assessment client identity.
3. Independent RS256 SERVICE JWT verification, including accepted issuer,
   timestamps and ADR-014 principal/scope validation.
4. No Gateway or browser exposure.
5. No USER impersonation or stored Student credentials.
6. No profile, email, password, complete roles or credentials in the response.
7. Only Boolean eligibility plus required studentId/time binding.
8. Current ACTIVE, verified, STUDENT eligibility.
9. Complete PostgreSQL-primary/Redis equality under accepted LD-05 semantics.
10. Fail-closed 503 when authority cannot be trusted.
11. No JWT-only fallback.
12. No positive eligibility cache.
13. Bounded private HTTPS transport and capacity controls.
14. Independent security review and REAL multi-service integration tests before
    activation.

This is a narrowly approved future Identity dependency within W3-B3, not generic
account introspection authority and not an endpoint implemented by PR #79.

### EC79-02 — Accepted operational limitation of notice delivery

An ineligible, disabled or unverifiable recipient can block delayed closure
**indefinitely**. This is an **ACCEPTED OPERATIONAL LIMITATION**, not an unresolved
Leader product decision. Missing confirmed accessible notice means no 48-hour
cutoff and no closure-driven forced submission of that Attempt. Preserve C1,
Student-specific inbox accessibility, durable per-Attempt evidence, fail-closed
checks and auditable retry/recovery. Sanitized, access-controlled operational
metrics/alerts must expose neither Student identity nor answers.

Never fabricate delivery timestamps, auto-abandon work, add administrative
force-close or silently introduce a remediation bypass. Any future remediation
requires a **NEW Leader product/security decision**. Existing authorized Student
terminalization and ordinary all-terminal closure rules remain unchanged.

### EC79-03 — Initial capacity budgets and atomic capture accepted

The 2 MiB request-body ceiling is an **INITIAL ENGINEERING BUDGET ONLY**, not a
permanent Quiz product restriction. Future implementation must verify compatibility
with all accepted delivery snapshots and complete answer payloads, including
realistic sizing/expansion tests. Never truncate valid answers or accept a snapshot
that cannot be safely submitted without explicitly resolving the capacity mismatch.
Oversized rejection must be sanitized and mutation-free. Existing W3-A handoff
capacity limitations remain documented until separately resolved.

Initial closure membership capture is atomic and complete. A successful request
must omit no member of its authoritative eligible capture set (then ACTIVE
unlimited Attempts). If capacity/transaction limits prevent complete capture,
REQUEST rolls back; partial membership is never successful. Required evidence
includes real PostgreSQL concurrency, full-answer sizing, bounded finalization
batches, multi-instance recovery, uncertain-commit retry, DB failover/durability,
lock/statement timeouts and security/authorization during recovery. Documentation
validation or invented benchmarks cannot satisfy these runtime gates.

### EC79-04 — Approved implementation sequence

**W3-B1 Persistence/Configuration → W3-C1 Atomic Grading/Finalization Core →
W3-B2 Complete Student Attempt API → W3-B3 Closure/Inbox/Identity Integration.**
The existing [increment scopes](../architecture/w3b-w3c-contract-review-evidence.md#dependency-aware-implementation-pr-sequence)
are approved, including legacy OPEN compatibility/default-disabled features,
shared manual/timeout/closure finalization, real Student SUBMIT/results/review,
and the narrow W3-B3 Identity adapter. Each increment requires its own scoped
implementation PR, real tests, independent review and Leader merge approval.
No public SUBMIT, grading-dependent route or delayed-closure workflow may activate
without its actual authoritative implementations. No implementation PR is opened
by this finalization assignment.

The Leader explicitly accepts all three MEDIUM review considerations: the narrow
Identity dependency conditionally; potentially indefinite blocked closure; and
initial capacity budgets/atomic-capture trade-off. They are accepted engineering
trade-offs subject to the stated safeguards, not undecided policy blockers.

## Deferred functionality and external gates

Practice persistence, anonymous participation, passwords, shuffle, new grading
versions, Classroom gradebook event contract, generic multi-service notification
platform, AI monitoring and media recording remain deferred. Assessment owns
only its closure notices; it does not take Classroom announcements or Identity
OTP ownership. Any broader inbox ownership transfer requires a separate ADR.

Conditional engineering sign-off is recorded above. Independent read-only review
of these amendments and the Leader merge-review gate remain pending; this agent
does not mark either complete. Each implementation increment still needs its own
independent review and Leader merge approval.
Runtime activation additionally requires tested LD-05 adapters/topology, authentic
inbox availability, capacity/clock/failover/durability/TLS/redaction/rollout controls
and reviewed answer/result/notice/audit retention and deletion. Existing monitoring
privacy/failure gates remain separate. No runtime or legal compliance is claimed.
