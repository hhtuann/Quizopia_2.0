# W3-B3 Real Closure & Notifications Acceptance Evidence

**Parent Issue**: [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97)  
**Draft PR**: [#100](https://github.com/hhtuann/Quizopia_2.0/pull/100)  
**Branch**: `test/w3-b3-real-closure-acceptance`  
**Base Commit (`develop`)**: `e8bd838092b82a0de1ed652b1fce59ef019f2b32`  
**Execution Timestamp**: 2026-10-17T13:19:32Z  
**Execution Status**: **REAL SYNTHETIC ACCEPTANCE VERIFIED (Targets 1–5 & 7; H1, M1, M2 Confirmed)** / **Target 6 Genuine ≥48h Soak & Post-Cutoff Completion Explicitly Gated as BLOCKED**

---

## 1. Executive Summary & Acceptance Matrix

In compliance with `AGENTS.md`, `docs/development/w3-b3-real-closure-acceptance-plan.md`, the B3 closure contract (`docs/specifications/w3-b-closure-notification-contract.md`), and the Leader review findings on PR #100, the primary implementation agent has built, executed, and verified real Gateway/Chromium synthetic acceptance testing for the W3-B3 closure request, notice publication, recipient authorization, confirmation, cutoff calculation, cancellation, foreign-Teacher fencing, and early complete denial workflows without Playwright Gateway interception or request mocking.

All tests ran in an isolated synthetic test topology with separate ephemeral ports, dedicated Docker containers, independent databases and credentials, and synthetic test actors. Existing developer host containers (5432, 6379, 5672, 8025, 7880, 9000) remained untouched and healthy.

### Acceptance Target Matrix

| Target | Description | Status | Verification & Evidence |
| --- | --- | --- | --- |
| **Target 1** | Real synthetic Teacher and Student with eligible ACTIVE CLASS Attempt (`durationMinutes: 0`), authoritative recipient state, and distinct owned resources. | **PASS** | Synthetic Teacher created Classroom and CLASS Assessment with `durationMinutes: 0` (unlimited, no deadline). Student enrolled via membership fixture, entered assessment, and established an `ACTIVE` attempt (`deadline_at: null`). |
| **Target 2 (H1, M1)** | Pre-worker prerequisite verification & H1 pre-worker readiness: Gateway health UP, Student inbox 200 (empty) / 404 unknown notice ID, Identity recipient contract with least-privilege SERVICE scope, and Teacher REQUEST denied with 503 `DEPENDENCY_UNAVAILABLE` before edge attestation and closure worker activation. | **PASS** | Gateway `/actuator/health/assessment-inbox` returned 200 `UP`. Fresh Student inbox returned 200 `[]` and non-existent random UUID returned 404 (unknown-ID probe). Service OAuth2 token probe verified `identity.notice-recipient.read` on `POST /internal/security/notice-recipients/{studentId}/eligibility` (eligible=true, unauthenticated=401, gateway scope denied). **H1 verified**: Teacher REQUEST denied with 503 `DEPENDENCY_UNAVAILABLE` when worker and edge attestation flags are `false`. Operator-controlled staged transition enabled edge attestation while synthetic worker remained OFF. |
| **Target 3 (M1, M2)** | Real Teacher REQUEST under explicit user action: recorded durable request identity (`PENDING`), notice created without confirmation or cutoff, no false CLOSED, pre-worker null cutoff, and M2 request replay/conflict handling with exact body capture. | **PASS** | Teacher requested delayed closure via UI: recorded durable `closureRequestId` (`50cbd9ff-9ecc-4a39-809d-07485fa5a8b9`), state `PENDING`, Publication remained `OPEN`. Prior to window expiry, early REQUEST was rejected with 409 `CLOSURE_NOT_READY`. **M2 verified**: captured original REQUEST payload from the actual UI command; exact same-key replay returned HTTP 201 with full response body equality (`expect(res.body).toEqual(requestView)`); altered payload with same key returned HTTP 409 `IDEMPOTENCY_CONFLICT`; new key while pending returned HTTP 409 `CLOSURE_PENDING`. **M1 verified**: pre-worker state observed with worker OFF showing `attempt.closureCutoffAt=null` and student inbox 0 unconfirmed items. |
| **Target 4 (H1, M1)** | Owned synthetic bounded worker controls: staged activation (H1), pre-worker authoritative `PREPARING` notice (M1), durable PUBLISHED recipient notice, fresh eligibility, actual `confirmedNoticeAt` and immutable `closureCutoffAt = confirmedNoticeAt + 48 hours` (+172,800,000ms), owner inbox/detail, former CLASS member read, and post-activation cross-recipient authorization masking. | **PASS** | **H1 verified**: operator-controlled staged transition activated assessment closure worker with 4000ms poll delay after verified pre-worker state. **M1 verified**: server-issued authoritative `PREPARING` notice captured before worker confirmation (`id=52247fcb-b265-4a12-b227-6902e1d369e5`, `confirmedNoticeAt=null`, `closureCutoffAt=null`, UI alert 'Your notice is being prepared' and badge 'Notice preparing'). Worker confirmed notice with server-issued timestamps: `confirmedNoticeAt = 2026-10-17T13:18:36.917591Z`, `closureCutoffAt = 2026-10-19T13:18:36.917591Z` (delta = exactly 172,800,000ms = 48.0000h). Student navigated browser to notice detail. After membership removal, student entry was blocked (404), while notice inbox and detail remained accessible (200 OK). **M1 post-activation cross-recipient authorization masking verified**: Student B receives 404 when accessing Student A's confirmed notice ID (distinguished from pre-activation unknown-ID 404). |
| **Target 5 (M2)** | Real Teacher CANCEL pre-finalization and durable non-actionable notice (no deletion). Replay/idempotency with exact body equality (M2), foreign-Teacher fence (M2), account fencing, foreign denial. | **PASS** | **M2 verified**: foreign-Teacher authorization fence confirmed—unrelated Teacher receives HTTP 404 `PUBLICATION_NOT_FOUND` on GET, REQUEST, CANCEL, COMPLETE. Teacher confirmed CANCEL in UI: backend returned 200, state transitioned to `CANCELLED`, UI showed resolved. Cancelled notice persists in DB with `noticeStatus = CANCELLED` (no deletion). **M2 verified**: exact same-key CANCEL replay returned HTTP 200 with full response body equality (`expect(res.body).toEqual(cancelView)`); altered payload returned HTTP 409 `IDEMPOTENCY_CONFLICT`; new key on resolved request returned HTTP 409 `CLOSURE_RESOLVED`; student denied CANCEL (403). |
| **Target 6** | Early COMPLETE before cutoff blocked with 409 `CLOSURE_NOT_READY`; preserve request identity. Genuine ≥48-hour operational soak, multi-node DB cluster failover outage recovery, and W3-M real LiveKit media. | **EARLY COMPLETE PASS / ≥48H SOAK & MULTI-NODE FAILOVER BLOCKED** | Early COMPLETE before cutoff blocked with 409 `CLOSURE_NOT_READY`; request identity and `PENDING` state preserved in UI. **Genuine ≥48-hour cutoff was not modified, database clocks were not manipulated, and staging soak PASS is NOT claimed.** Post-cutoff completion remains explicitly gated as **BLOCKED / NOT VERIFIED** for dedicated long-running staging soak. **Outage recovery**: safely feasible single-node dependency outage (SIGSTOP/resume) verified in public journey; multi-node cluster failover / split-brain recovery and W3-M real LiveKit proctoring explicitly marked as **BLOCKED / NOT VERIFIED** in synthetic single-node acceptance topology. |
| **Target 7 (H1)** | Preserve existing real W3-A/W3-B/C 5/5 journeys, zero Playwright Gateway interception, fail-closed process ownership controls, and deterministic cleanup. | **PASS** | All 6/6 Playwright acceptance journeys passed cleanly (`Real W3-A suite: passed`). No mock/route interceptions. Python harness unit tests passed (21/21), including regression tests for initial ownership mismatch and PID replacement between TERM and KILL. Deterministic runtime teardown verified (0 unverified resources). |

---

## 2. Real Execution Receipts (6/6 Journeys)

Playwright Real Acceptance Suite Output (`playwright.w3a-real.config.ts`):

```text
passed: real PUBLIC Assessment: Teacher OPEN, Student locator entry, 4 question types autosave/revision, submit, result, teacher CLOSE, results table and student closed review
  Teacher-role fixture readiness GET initially 503 AUTHORITY_UNAVAILABLE; bounded read-only polling reaches authoritative 404 before any create mutation.
  Gateway registration 202, Mailpit OTP, login/me 200, Teacher enablement 204; HttpOnly cookie managed by browser.
  Teacher published immutable QuizVersion 1 containing SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE_MATRIX, and NUMERIC_FILL.
  Authoritative PUBLIC assessment OPEN with valid private locator.
  Separate disposable STUDENT authenticated via Gateway/OTP and real browser session.
  Student started real ACTIVE Attempt via private locator; redirected to attempt page.
  Real autosave verified across all four question types: SINGLE_CHOICE, MULTIPLE_CHOICE, TRUE_FALSE_MATRIX, NUMERIC_FILL.
  Answer revision successfully handled with incremented revision sequence.
  Result screen displays 100.00% score calculation.
  Teacher successfully closed assessment after attempt submission.
  Teacher results table displays the submitted student attempt and score.
  Student review screen reveals questions and answers after durable assessment closure.
passed: real CLASS Assessment: Teacher creates Class & Assessment, Student membership gate, ACTIVE blocks CLOSE, Student abandon, Teacher CLOSE and Notifications inbox
  Teacher-role fixture readiness GET initially 503 AUTHORITY_UNAVAILABLE; bounded read-only polling reaches authoritative 404 before any create mutation.
  Gateway registration 202, Mailpit OTP, login/me 200, Teacher enablement 204; HttpOnly cookie managed by browser.
  CLASS Assessment created and opened with monitoring DISABLED.
  Separate disposable STUDENT authenticated via Gateway/OTP and real browser session.
  Student denied entry before classroom membership.
  Student successfully started CLASS attempt after membership fixture.
  ACTIVE attempt correctly produces 409 ACTIVE_ATTEMPTS conflict on CLOSE.
  Student abandoned attempt; state transitions to ABANDONED.
  Assessment successfully closed after attempt abandoned.
  Student notifications inbox screen reached and authenticated GET 200 confirmed; empty inbox prior to delayed closure requests.
passed: real CLASS Assessment B3: Teacher REQUEST, notice publication, recipient authorization, confirmed cutoff +48h, former CLASS member read, CANCEL and early COMPLETE denial
  Teacher-role fixture readiness GET initially 503 AUTHORITY_UNAVAILABLE; bounded read-only polling reaches authoritative 404 before any create mutation.
  Gateway registration 202, Mailpit OTP, login/me 200, Teacher enablement 204; HttpOnly cookie managed by browser.
  Separate disposable STUDENT authenticated via Gateway/OTP and real browser session.
  Target 2 verified: Gateway /actuator/health/assessment-inbox UP, Student inbox 200/404 unknown-ID verified, and Identity recipient contract verified with least-privilege SERVICE token scope.
  CLASS Assessment opened with durationMinutes: 0 and monitoringChoice: DISABLED.
  Student started ACTIVE unlimited attempt (duration_minutes: 0, deadline_at: null) and saved answer.
  Target 2 / H1 verified: Teacher REQUEST denied with 503 DEPENDENCY_UNAVAILABLE while worker and edge inbox attestation are OFF.
  H1 verified: Operator-controlled staged transition successfully activated edge inbox attestation while synthetic closure worker remains OFF.
  Early REQUEST denied with 409 CLOSURE_NOT_READY while participation window is active (now < endAt).
  M2 verified: REQUEST exact same-key replay returned 201 identical response; altered payload returned 409 IDEMPOTENCY_CONFLICT; new key while pending returned 409 CLOSURE_PENDING.
  Teacher requested delayed closure via UI: recorded durable closureRequestId 50cbd9ff-9ecc-4a39-809d-07485fa5a8b9, state PENDING, publication remains OPEN.
  M1 verified: Authoritative pre-worker state observed with worker OFF: closureRequestId=50cbd9ff-9ecc-4a39-809d-07485fa5a8b9, state=PENDING, attempt.closureCutoffAt=null, student inbox=0 unconfirmed items.
  H1 verified: Operator-controlled staged transition successfully activated assessment closure worker with 4000ms poll delay after verified pre-worker state.
  M1 verified: Authoritative PREPARING notice captured before worker confirmation: id=52247fcb-b265-4a12-b227-6902e1d369e5, confirmedNoticeAt=null, closureCutoffAt=null, UI alert 'Your notice is being prepared'.
  Worker published and confirmed recipient notice: confirmedNoticeAt=2026-10-17T13:18:36.917591Z, closureCutoffAt=2026-10-19T13:18:36.917591Z, exact delta=172800000ms (+48 hours).
  Student successfully navigated to notice detail in browser UI showing authoritative 48-hour cutoff.
  Former CLASS member read verified: entry rejected 404, but recipient notice inbox & detail remain accessible 200 OK.
  Separate disposable STUDENT authenticated via Gateway/OTP and real browser session.
  Cross-recipient authorization masking verified: Student B receives 404 on Student A's confirmed notice and has empty inbox.
  Early COMPLETE blocked with 409 CLOSURE_NOT_READY; request identity preserved, Publication remains OPEN, genuine 48-hour cutoff honored without manipulation.
  Teacher-role fixture readiness GET initially 503 AUTHORITY_UNAVAILABLE; bounded read-only polling reaches authoritative 404 before any create mutation.
  Gateway registration 202, Mailpit OTP, login/me 200, Teacher enablement 204; HttpOnly cookie managed by browser.
  M2 verified: Foreign-Teacher authorization fence: unrelated Teacher receives 404 PUBLICATION_NOT_FOUND on GET, REQUEST, CANCEL, COMPLETE.
  Teacher cancelled closure request: backend returns 200, state CANCELLED, UI shows request resolved.
  Cancelled notice persists durably in database with noticeStatus CANCELLED (no deletion).
  M2 verified: CANCEL exact same-key replay returned 200 identical response; altered payload returned 409 IDEMPOTENCY_CONFLICT; new key on resolved request returned 409 CLOSURE_RESOLVED; student denied 403.
passed: real PUBLIC pinned create/replay/edit/conflicts/503/OPEN/extend/CLOSE and confidential session
  Teacher-role fixture readiness GET initially 503 AUTHORITY_UNAVAILABLE; bounded read-only polling reaches authoritative 404 before any create mutation.
  Gateway registration 202, Mailpit OTP, login/me 200, Teacher enablement 204; HttpOnly cookie managed by browser.
  Actual Quiz draft/save/publish/history selected immutable version one through frontend.
  PUBLIC POST 201, fresh no-store owner GET 200; exact pinned IDs and microseconds retained; locator initially hidden.
  Actual creation replay 201 same ID; payload/key conflict 409 IDEMPOTENCY_CONFLICT; source/owner 404 and invalid-token 401.
  Concurrent real PUT 200; stale UI PUT 409 preserves local values; explicit discard then reviewed PUT 200.
  Owned Quiz SIGSTOP yields real 503 DEPENDENCY_UNAVAILABLE; no logout/refresh; actual DRAFT retained; resume and manual reconciliation/original OPEN 200.
  Actual PATCH extension 200; PUBLIC ACTIVE start 201 blocks CLOSE 409, remains OPEN; own abandon 200 permits direct CLOSE 200 and fresh durable CLOSED GET 200.
  HttpOnly SameSite=Lax refresh cookie, USER-only browser bearer, memory-only token; private locator absent URLs/links/console/storage; trace/video/screenshots disabled.
passed: real CLASS ownership/membership, explicit monitoring, STUDENT denial and ACTIVE CLOSE conflict
  Teacher-role fixture readiness GET initially 503 AUTHORITY_UNAVAILABLE; bounded read-only polling reaches authoritative 404 before any create mutation.
  Gateway registration 202, Mailpit OTP, login/me 200, Teacher enablement 204; HttpOnly cookie managed by browser.
  CLASS actual Classroom POST 201 and Publication create 201; unknown Classroom denied 404; UNCONFIGURED prevents UI OPEN; explicit DISABLED PUT 200 then owned CLASS OPEN 200.
  Separate disposable STUDENT authenticated via Gateway/OTP and real browser session.
  Teacher-role fixture readiness GET initially 503 AUTHORITY_UNAVAILABLE; bounded read-only polling reaches authoritative 404 before any create mutation.
  Existing different Teacher Classroom reference 404 CLASSROOM_NOT_ACCESSIBLE; different current Teacher owner GET 404 PUBLICATION_NOT_FOUND.
  Same browser: owned Assessment pause stalls old owner GET; logout 204 and other-account login succeed; resume leaves old request canceled and foreign owner UI/data denied 404.
  Separate Student owner GET 403 and Teacher UI blocked; CLASS membership denial 404; controlled owned-Classroom membership fixture then actual ACTIVE start 201, CLOSE 409 ACTIVE_ATTEMPTS and fresh OPEN GET 200; own abandon 200.
passed: real committed creation with lost response retains original key and reconciles once
  Teacher-role fixture readiness GET initially 503 AUTHORITY_UNAVAILABLE; bounded read-only polling reaches authoritative 404 before any create mutation.
  Gateway registration 202, Mailpit OTP, login/me 200, Teacher enablement 204; HttpOnly cookie managed by browser.
  Owned Gateway paused after real create dispatch; Quiz resumed; owned read-only receipt observer proves one committed creation before browser receives any response.
  Real 15-second frontend deadline yields uncertain locked form; zero automatic replay/refresh/logout; explicit retry sends byte-identical payload/key, returns 201 existing resource, single receipt and fresh owner GET 200.
Real W3-A suite: passed
```

---

## 3. Detailed Verification of Findings & Resolution Mechanics

### 3.1 Finding H1: Runtime Process Ownership, Fail-Closed Mismatches & SIGKILL Revalidation
- **Vulnerability Addressed**: Previously, `_restart_assessment()` caught `(RuntimeError, OSError)` with `pass`, which could swallow ownership mismatches, send `SIGKILL` to recycled PIDs without identity revalidation, or launch replacement processes under uncertain ownership. Furthermore, zombie states during process termination were not distinguished from running processes.
- **Resolution**:
  1. `_restart_assessment()` now calls `process_owned(directory, "assessment")` initially and lets `RuntimeError` propagate immediately. If ownership does not match, execution halts without sending signals or launching processes.
  2. If the process does not exit within the initial 10s wait, `_restart_assessment()` revalidates process identity via `process_owned(directory, "assessment")`. If ownership is mismatched or the PID was recycled by another process, `_restart_assessment()` raises `RuntimeError("Assessment process ownership mismatch before SIGKILL")`. `SIGKILL` is NEVER sent to an unverified or changed PID.
  3. `process_owned()` was updated to inspect `/proc/<pid>/status` for zombie/dead process states (`State: Z`, `State: X`) and raise `ProcessLookupError("Runtime process is not running")`. This enables `wait_for_exit()` to recognize clean terminations without treating terminating address space teardown as ownership corruption.
  4. Never launch or accept a replacement process if ownership was uncertain or termination failed.
- **Regression Unit Tests Added** (`RestartAssessmentOwnershipTests` in `scripts/local/tests/test_w3a_real_runtime.py`):
  - `test_initial_ownership_mismatch_fails_closed`: Verifies that initial ownership mismatch raises `RuntimeError`, `os.kill` is not called, and `Popen` is not launched.
  - `test_pid_replacement_between_term_and_kill_fails_closed`: Verifies that if PID identity changes after SIGTERM, SIGKILL is not sent, `Popen` is not launched, and `_restart_assessment` fails closed with `RuntimeError("Assessment process ownership mismatch before SIGKILL")`.

### 3.2 Finding M1: Accurate Activation Sequence & 404 Evidence Distinction
- **Accurate Activation Sequence**:
  1. **Boot**: Identity Service starts with `W3A_IDENTITY_NOTICE_RECIPIENTS_ENABLED=true` (least-privilege `identity.notice-recipient.read` scope configured for `assessment-service`). All Assessment flags (`ASSESSMENT_EDGE_INBOX_VERIFIED`, `ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED`, `ASSESSMENT_CLOSURE_WORKER_ENABLED`) default to `false`.
  2. **Baseline Reset**: Test suite calls `fixtureControl("reset-closure-flags")`, guaranteeing Assessment edge inbox attestation and closure worker are OFF.
  3. **Target 2 Prerequisites**:
     - Gateway `/actuator/health/assessment-inbox` returns 200 `UP`.
     - Student fresh inbox returns 200 `[]`.
     - **Pre-Activation Unknown-ID Probe**: `GET /api/assessments/notifications/{randomUuid}` returns HTTP 404 (verifying non-existent notification ID lookup).
     - **Identity Recipient Contract**: OAuth2 `client_credentials` probe confirms `identity.notice-recipient.read` on `POST /internal/security/notice-recipients/{studentId}/eligibility` returns 200 `eligible: true`; unauthenticated returns 401; Gateway client is denied scope.
  4. **Pre-Worker REQUEST Denial**: Teacher submits `POST /closure-requests` while flags are `false`; server returns HTTP 503 `DEPENDENCY_UNAVAILABLE`.
  5. **Phase 1 Activation**: `fixtureControl("activate-edge-attestation")` restarts Assessment Service with `ASSESSMENT_EDGE_INBOX_VERIFIED=true` and `ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED=true`, worker remains OFF.
  6. **Pre-Worker State Captured**: Teacher REQUEST accepted (`PENDING`, publication `OPEN`). Server returns `attempt.closureCutoffAt: null` and 0 unconfirmed items in inbox.
  7. **Phase 2 Activation**: `fixtureControl("activate-closure-worker", "4000")` restarts Assessment Service with closure worker enabled (4000ms poll delay).
  8. **PREPARING Notice Captured**: Server returns `noticeStatus: "PREPARING"`, `confirmedNoticeAt: null`, `closureCutoffAt: null`. UI displays "Notice preparing" badge and "Your notice is being prepared" banner.
  9. **Worker Confirmation**: Notice confirmed with exact 48-hour cutoff: `confirmedNoticeAt = 2026-10-17T13:18:36.917591Z`, `closureCutoffAt = 2026-10-19T13:18:36.917591Z` (exact 172,800,000ms delta).
  10. **Post-Activation Cross-Recipient Authorization Masking**: Student B authenticates independently and attempts `GET /api/assessments/notifications/{studentA_noticeId}`. Server returns HTTP 404, proving cross-recipient authorization masking (distinguished from pre-activation unknown-ID 404).

### 3.3 Finding M2: Exact Idempotency Replay & Full Response Body Assertions
- **Original Payload Capture**:
  - For Teacher REQUEST: Captured `originalRequestBody = requestHttp.request().postDataJSON()` and `requestKey = requestHttp.request().headers()["idempotency-key"]` from the actual Playwright UI action.
  - Replayed unchanged with identical key and payload: Server returned HTTP 201.
  - **Full Replay Equality**: Asserted `expect(requestReplayRes.body).toEqual(requestView)` across the entire response structure.
- **Same Key with Altered Payload Conflict**:
  - Replayed with different `expectedRevision`: Server returned HTTP 409 `IDEMPOTENCY_CONFLICT`.
- **New Key Concurrency Conflict**:
  - Sent new key while request is active: Server returned HTTP 409 `CLOSURE_PENDING`.
- **For Teacher CANCEL**:
  - Captured `originalCancelBody` and `cancelKey` from the actual UI cancel action.
  - Replayed unchanged with identical key and payload: Server returned HTTP 200.
  - **Full Replay Equality**: Asserted `expect(cancelSameKey.body).toEqual(cancelView)` across the entire response structure.
  - Altered payload conflict: Server returned HTTP 409 `IDEMPOTENCY_CONFLICT`.
  - New key on resolved request: Server returned HTTP 409 `CLOSURE_RESOLVED`.
  - Student denial: Student attempting to cancel returned HTTP 403.
- **Foreign-Teacher Authorization Fence**:
  - Registered and authenticated unrelated Teacher B.
  - Proved Teacher B receives HTTP 404 `PUBLICATION_NOT_FOUND` across `GET`, `REQUEST`, `CANCEL`, and `COMPLETE`.

---

## 4. Unverified Subgates & Gating Summary

Per project policy, requirements that cannot be safely or authentically proven in single-node synthetic acceptance are explicitly gated below rather than simulated through artificial mocks:

| Subgate | Requirement | Rationale for Gate | Next Action / Resolution Path |
| --- | --- | --- | --- |
| **Subgate A** | Genuine ≥48-hour operational soak & post-cutoff completion (`CLOSURE_COMPLETED`) | Faking system clocks or database timestamps violates acceptance rules; real 48-hour elapsing cannot run in a PR acceptance pipeline. | Execute against dedicated staging environment running continuously over a 48-hour soak window. |
| **Subgate B** | Multi-node database cluster failover & split-brain outage recovery | The synthetic acceptance environment provisions a single isolated PostgreSQL container per service. Safe single-node outage (SIGSTOP/SIGCONT dependency pause) was verified in the public journey, but multi-node cluster failover requires Patroni/PgBouncer cluster infrastructure. | Dedicated cluster resilience testing environment. |
| **Subgate C** | W3-M LiveKit realtime proctoring media journeys | W3-M media proctoring is out of scope for B3 closure/notifications acceptance and owned by the W3-M acceptance track. | Verified under W3-M test suite. |

---

## 5. Test Suite Summary & Quality Checks

1. **Playwright Real Acceptance Suite**: 6/6 journeys passed (`Real W3-A suite: passed`).
2. **Python Runtime Harness Unit Tests**: 21/21 tests passed (`test_w3a_real_runtime.py`).
3. **Frontend Vitest Unit Suite**: 56/56 test files, 682/682 tests passed.
4. **Frontend Code Quality**:
   - `pnpm --dir frontend format:check`: PASS (0 style issues)
   - `pnpm --dir frontend lint`: PASS (0 errors, 0 warnings)
   - `pnpm --dir frontend typecheck`: PASS (0 TypeScript errors)
5. **Teardown Verification**:
   - Runtime directory cleanly torn down via `python3 scripts/local/w3a-real-runtime.py down`.
   - Output: `Owned runtime cleanup complete; private evidence retained at /tmp/...` (0 unverified resources).
   - Host developer containers (`quizopia-postgres-1`, `quizopia-redis-1`, `quizopia-rabbitmq-1`, `quizopia-mailpit-1`, `quizopia-livekit-1`, `quizopia-minio-1`) remained healthy and untouched.
