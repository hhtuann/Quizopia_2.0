# ADR-018 — Permanent deletion of a Quiz and all dependent data (FB-015)

Date: 2026-10-28
Status: **ACCEPTED FOR GUARDED IMPLEMENTATION — NOT APPROVED FOR PRODUCTION DELETION**
Decision owner: Quizopia Leader
Tracking: [Issue #120](https://github.com/hhtuann/Quizopia_2.0/issues/120), [Round 3 #115](https://github.com/hhtuann/Quizopia_2.0/issues/115)
Supersedes the retention-first recommendation and proposed `ACCESS_REVOKED` Publication lifecycle state in `fb015-quiz-deletion-architecture-package.md`.

## 1. Product decision

The owning Teacher may request **irreversible, permanent deletion** of the Quiz and **all dependent QuizVersions, Assessment Publications, snapshots, Attempts, saved/submitted answers, grades and results, closure/notification records, monitoring/proctoring data and quiz-scoped derived records**. Students must no longer start, resume, save, submit or read results/review for deleted Publications. Hiding a Quiz or merely revoking access while keeping grades forever does **not** fulfill this decision.

Identity user accounts, Student profiles and independent Classroom membership **are never deleted** by a Quiz deletion. Other Quizzes and their independent Publications/Attempts must be unaffected. Shared external references (future Community/Practice/AI and gradebook integration) require an ownership and propagation inventory before their purge behavior is implemented.

This decision is permission to implement guarded code and migrations on an isolated branch using disposable test data. It is **not** authorization to delete existing/non-disposable data, run destructive operations in production, or turn on flags.

## 2. Retention and legal holds are mandatory exceptions

Permanent erasure is the default **once deletion eligibility has been verified**, not a blanket promise of instant physical destruction:

- **No arbitrary 90-day product retention of submitted grades**. After the cross-service access-revocation barrier and absent an approved hold, eligible primary-store content must be purged promptly through the controlled workflow.
- A documented **legal, regulatory, institutional grading/appeal retention requirement or preservation order** may prohibit destruction of particular records. Treat holds as record-scoped, require authority/reason/reviewer/expiry/review date, preserve only the minimum required content in restricted custody, and deny access from ordinary Quizopia routes. Record hold decisions without copying sensitive answers into the operation log.
- Teacher ownership is sufficient to request deletion of the Teacher's Quiz, **not** to override a Student's rights or an institution's legally required preservation. Do not claim a Teacher can waive third-party retention requirements.
- Unknown retention classification, missing required custodian approval, missing backup policy, or an indefinite/ambiguous hold **blocks irreversible purge**. Return a truthful `BLOCKED_BY_RETENTION` or `PENDING_POLICY` status; do not report `PURGED`.
- Operational backups, export jobs, object storage, CDN, LiveKit/media providers, logs, telemetry, outbox/inbox, caches and replicas are separate deletion surfaces. Define verified expiry, destruction or cryptographic erasure for every copy. **Do not claim all copies erased until evidence exists**. If routine backup expiry is used, its exact period must be approved from actual deployment configuration; this ADR does **not** invent a legally required 7/30/90-day duration.
- A small deletion receipt may remain for a policy-approved, bounded period: operation ID, timestamps, stage, service acknowledgments, counts, error codes and legal-hold reference, with no quiz text, answers, private locator, student names or raw attempt payloads. It is not an indefinite grade/PII archive; exact audit TTL needs privacy/operations approval.
- Applicable Vietnam references: Law 91/2025/QH15, effective 2026-01-01, especially Article 14 on erasure and exceptions (including Article 19), and Decree 356/2025/ND-CP. A qualified privacy/legal review must confirm this specific deployment's educational record, age, contract and cross-border obligations **before production activation**. An app-initiated Quiz delete is not identical to a Student's statutory erasure request.

References: https://vanban.chinhphu.vn/?classid=1&docid=214590&pageid=27160 ; https://chinhphu.vn/?classid=1&docid=216387&pageid=27160&typegroupid=4

## 3. Ownership and invariant boundaries

- Quiz Service: `quizzes`, `quiz_drafts`, immutable `quiz_versions`; sole authority for Teacher-owned deletion requests and authoring/publishing denial.
- Assessment Service: Publications, immutable delivery snapshots, Attempts, autosave, submitted answers, grading results, closure requests/notifications, durable outboxes. Sole authority for denying and purging these records.
- Proctoring Service: monitoring sessions/evidence/access audits, media grants and provider artifacts. Sole authority for suppression/cleanup; any independent privacy retention hold remains enforceable.
- Identity: Teacher/Student authority, profiles and service credentials; **no identity-account deletion**.
- Classroom: membership and roster remain; only quiz-scoped references/projections are removed.
- Gateway and external consumers: invalidate cached locator/links and quiz-scoped projections, enforce auth admission and prevent stale access; do not use deletion events as a replacement for server access gates.

Existing Assessment tables have `ON DELETE RESTRICT`, deferred cyclic FKs and transaction/audit invariants. Existing Proctoring triggers disallow ordinary removal, with separate expired-evidence procedures. Do not bypass them, change service DB ownership, disable triggers, or use cross-database CASCADE. Design bounded service-owned privileged cleanup/migrations and FK-safe ordered removal or controlled transactional procedures, with explicit isolated migration tests. Published QuizVersions remain immutable **until a separately authorized hard purge removes the entire record**; never edit their snapshot contents in place.

## 4. Forward-only, recoverable deletion state machine

**Separate deletion state from the accepted Publication lifecycle `DRAFT → OPEN → CLOSED`.** Do not invent `ACCESS_REVOKED` as a new canonical Publication lifecycle enum or reinterpret deadline/CLOSE grading rules.

`REQUESTED → QUIZ_FENCED → DEPENDENCIES_FENCING → ACCESS_REVOKED → PURGE_PENDING → PURGING → PRIMARY_PURGED → ALL_COPIES_ERASED`.

Additional nonterminal states: `WAITING_DEPENDENCY`, `BLOCKED_BY_RETENTION`, `PENDING_POLICY`, `RETRYABLE_FAILURE`. Each state is durable and owner-queryable; no false terminal success. An operation cannot move backward from the irreversible boundary.

**Phase A — revoke barrier**
1. Authenticated current TEACHER, server-verified owner and LD-05 admit a deletion request with a durable idempotency key, canonical title confirmation and frozen quiz/version inventory. Atomically mark Quiz authoring/publishing/source-version handoff **fenced** and persist an outbox command. Deny future references from new Publications.
2. Assessment applies a durable **quiz-ID revocation fence** to all existing Publications/Attempts in its own transaction and acknowledges it. Every affected read/write route (directory, public/private locator, entry, start, retake, autosave, submit, abandon, grade/result/review, teacher results and closure actions) must check this fence in server authority, **before returning any cached/replayed response**. Do not expose old answers, result rows or details.
3. Concurrent START/SAVE/SUBMIT vs revoke are serialized at the owning Assessment DB boundary. Committed before-fence operations may be subsequently purged; post-fence operations are denied with a stable non-disclosing error (e.g. `ASSESSMENT_REMOVED`), even for idempotency replay. No new grading or terminal finalization after the fence.
4. Proctoring disables new observations/capture/media grants, denies monitoring access and acknowledges capture cessation. Browser receives a clear removal state; camera/media tracks are stopped. Realtime/notifications must not resurrect the attempt.
5. Only **after the mandatory dependency acknowledgments** and verification may the operation state become `ACCESS_REVOKED`, meaning there is no remaining student/teacher application access to any linked assessment. If a service is down, keep `WAITING_DEPENDENCY`, retry/reconcile, and **never claim access has already been fully revoked**. Existing operations at other services may be temporarily reachable until their fences commit; do not promise an atomic cross-service transaction.

**Phase B — hard purge**
6. Inventory holds, reference counts and every storage surface. A hold defers only legally necessary records, but status remains `BLOCKED_BY_RETENTION` until the required complete deletion can actually finish; no silently retained accessible grades.
7. Proctoring first purges permissible media/evidence/session records and receives provider erasure receipts; Assessment then deletes its dependent records in an approved FK-safe order (or appropriate batched transactions), including snapshots, grades, attempt operations, closure, inbox/outbox/projections and locators. All service operations are idempotent, bounded, restart-safe and guarded against stale events.
8. Quiz Service hard-deletes the draft/QuizVersions/Quiz only after all dependent owners attest purge; never remove the primary authoritative tombstone while it is still needed to reject stale commands. Preserve a minimal non-content anti-resurrection deletion ledger with approved TTL.
9. Verify both the absence of readable primary data and zero outstanding owned dependencies before `PRIMARY_PURGED`. Separately verify backup/external expiry/crypto-erasure before `ALL_COPIES_ERASED`. No standard restore path after irreversible purge. Restore of an old backup must restore revocation/tombstone enforcement **before** exposing any service.

The coordinator is Quiz (Teacher-owned operation), but each service is sole authority over its own data. Use ADR-009 transactional outbox/inbox, service-to-service JWT scopes tied to exact caller identity, durable operation IDs, per-step receipts, retries/backoff, poison handling and reconciliation. A one-way event such as `quiz.deletion.requested.v1` is an implementation detail, **not** sufficient evidence that Assessment has denied access. Service calls/events must never contain answers or locator secrets.

## 5. Proposed API and UX contract

Public, Gateway-routed, owner-only operations (exact paths to be frozen against current route inventory):

- `POST /api/quizzes/{quizId}/deletion-requests` with `Idempotency-Key` and an explicit title-confirmation value; `202 {operationId, status}` for ongoing work. Same key/body replays safely; mismatched replay `409`. Not a blind retry under uncertain outcome; the UI reconciles first.
- `GET /api/quizzes/{quizId}/deletion-requests/{operationId}` returns authorized status/stage, hold/pending reason and verified per-service completion, without PII.
- Do not expose a DELETE request body, bulk all-Quiz delete, restore endpoint, public operator purge route, or a success response claiming data erased before actual receipts. Any privileged internal API must have an explicit scope, strict caller, bounded transport and no-store semantics.

UI: in Quiz Library/editor, a secondary destructive **Xóa bộ câu hỏi** action requires a localized review dialog with exact Quiz title typed, affected Publications/Attempts/results preview **only if authoritative counts are available**, the irreversible consequence and Student-access consequence. Progress uses honest steps (`Đang thu hồi truy cập`, `Đã chặn truy cập · Đang xóa dữ liệu`, `Chờ hết thời hạn lưu giữ bắt buộc`, `Đã xóa dữ liệu chính`, `Đã hoàn tất xóa các bản sao`). Never show `Đã xóa hoàn toàn` for merely hidden, pending, failed or held data. Retry/status checks preserve original idempotency identity. Preserve keyboard/aria, vi/en, loading/failure and mobile behavior.

## 6. Nonnegotiable tests and release gates

Before merge: ownership/TEACHER/LD-05 denial, idempotent replay/mismatch, transaction race with START/SAVE/SUBMIT, stale result/monitoring links, private locator invalidation, PUBLIC/CLASS, DRAFT/OPEN/CLOSED, multiple QuizVersions and Publications, old snapshots, outbox lag and duplicates, service outages, untrusted callers, hold and backup states, purge cancellation/poison/restart, FK/trigger migrations, no accidental purge of Identity/Classroom or unrelated Quizzes, no PII in events/logs. Add service integration tests with actual PostgreSQL/Testcontainers and real Browser→Gateway→services acceptance on disposable data. Verify missing record counts per service and media provider receipts; backend authorization is mandatory even if UI hides actions.

**Three distinct gates**:
1. **Code implementation**: permitted on isolated branch, flags OFF, destructive tests only on disposable isolated fixtures; CI and independent review required.
2. **Non-disposable staging data**: requires explicit owner/operations approval after retention classification, schema backups and a reversible rehearsal; do not run destructive tests against real Student records.
3. **Production**: requires separate Leader authorization, independent legal/privacy + operations signoff, documented actual retention/backup/provider policy, tested replay and disaster recovery, observability, emergency stop and feature flags. **Production NO-GO; BR-01 PENDING.**

## 7. Correction of the earlier FB-015 proposal

The previous package's defaults **retain submitted results**, permit **graceful completion of ACTIVE Attempts**, use a **90-day purge window**, add **`ACCESS_REVOKED` as a Publication lifecycle state**, and offer an admin **restore** action. Those are superseded:

- Results are scheduled for permanent purge unless a valid time-bound hold prevents it.
- ACTIVE Attempts lose further access immediately **once the Assessment revocation fence commits**; no continuation beyond that barrier.
- No arbitrary 90-day retention; actual hold and backup times require evidence.
- Revocation is orthogonal to DRAFT/OPEN/CLOSED; no automatic CLOSED semantics.
- No ordinary restore after permanent deletion.
- Full revocation must be acknowledged; asynchronous delivery alone is not a success guarantee.
