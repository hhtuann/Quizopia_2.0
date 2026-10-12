# ADR-015: W3-B/W3-C Leader product policy and historical amendments

Status: **Accepted product policy; proposed engineering contracts awaiting independent review and Leader sign-off.**

Recorded: 2026-10-13 (Asia/Bangkok), from the Leader-approved policy supplied in
this assignment. No prior engineering approval or production activation is inferred.
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
Only policy in this ADR is accepted; technical choices in those documents are
reviewable proposals. This assignment adds no executable migration or endpoint.

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

## Deferred functionality and external gates

Practice persistence, anonymous participation, passwords, shuffle, new grading
versions, Classroom gradebook event contract, generic multi-service notification
platform, AI monitoring and media recording remain deferred. Assessment owns
only its closure notices; it does not take Classroom announcements or Identity
OTP ownership. Any broader inbox ownership transfer requires a separate ADR.

Independent contract review and Leader engineering sign-off precede implementation.
Runtime activation additionally requires tested LD-05 adapters/topology, authentic
inbox availability, capacity/clock/failover/durability/TLS/redaction/rollout controls
and reviewed answer/result/notice/audit retention and deletion. Existing monitoring
privacy/failure gates remain separate. No runtime or legal compliance is claimed.
