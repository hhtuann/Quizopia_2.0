# W3-B3 Real Closure & Notifications Acceptance Evidence

**Parent Issue**: [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97)  
**Draft PR**: [#100](https://github.com/hhtuann/Quizopia_2.0/pull/100)  
**Branch**: `test/w3-b3-real-closure-acceptance`  
**Base Commit (`develop`)**: `e8bd838092b82a0de1ed652b1fce59ef019f2b32`  
**Execution Timestamp**: 2026-10-17T12:42:06Z  
**Execution Status**: **REAL SYNTHETIC ACCEPTANCE VERIFIED (Targets 1–5 & 7; H1, M1, M2 Confirmed)** / **Target 6 Genuine ≥48h Soak & Post-Cutoff Completion Explicitly Gated as BLOCKED**

---

## 1. Executive Summary & Acceptance Matrix

In compliance with `AGENTS.md`, `docs/development/w3-b3-real-closure-acceptance-plan.md`, the B3 closure contract (`docs/specifications/w3-b-closure-notification-contract.md`), and the Leader review findings on PR #100, the primary implementation agent has built, executed, and verified real Gateway/Chromium synthetic acceptance testing for the W3-B3 closure request, notice publication, recipient authorization, confirmation, cutoff calculation, cancellation, foreign-Teacher fencing, and early complete denial workflows without Playwright Gateway interception or request mocking.

All tests ran in an isolated synthetic test topology with separate ephemeral ports, dedicated Docker containers, independent databases and credentials, and synthetic test actors. Existing developer host containers (5432, 6379, 5672, 8025, 7880, 9000) remained untouched and healthy.

### Acceptance Target Matrix

| Target | Description | Status | Verification & Evidence |
| --- | --- | --- | --- |
| **Target 1** | Real synthetic Teacher and Student with eligible ACTIVE CLASS Attempt (`durationMinutes: 0`), authoritative recipient state, and distinct owned resources. | **PASS** | Synthetic Teacher created Classroom and CLASS Assessment with `durationMinutes: 0` (unlimited, no deadline). Student enrolled via membership fixture, entered assessment, and established an `ACTIVE` attempt (`deadline_at: null`). |
| **Target 2 (H1)** | Pre-worker prerequisite verification & H1 pre-worker readiness: Gateway health UP, Student inbox 200 (empty) / 404 unknown notice, Identity recipient contract with least-privilege SERVICE scope, and Teacher REQUEST denied with 503 `DEPENDENCY_UNAVAILABLE` before edge attestation and closure worker activation. | **PASS** | Gateway `/actuator/health/assessment-inbox` returned 200 `UP`. Fresh Student inbox returned 200 `[]` and random UUID returned 404. Service OAuth2 token probe verified `identity.notice-recipient.read` on `POST /internal/security/notice-recipients/{studentId}/eligibility` (eligible=true, unauthenticated=401, gateway scope denied). **H1 verified**: Teacher REQUEST denied with 503 `DEPENDENCY_UNAVAILABLE` when worker and edge attestation flags are `false`. Operator-controlled staged transition enabled edge attestation while synthetic worker remained OFF. |
| **Target 3 (M1, M2)** | Real Teacher REQUEST under explicit user action: recorded durable request identity (`PENDING`), notice created without confirmation or cutoff, no false CLOSED, pre-worker null cutoff, and M2 request replay/conflict handling. | **PASS** | Teacher requested delayed closure via UI: recorded durable `closureRequestId` (`ca76dd77-ba28-4871-874e-fdf98776ebe9`), state `PENDING`, Publication remained `OPEN`. Prior to window expiry, early REQUEST was rejected with 409 `CLOSURE_NOT_READY`. **M2 verified**: exact same-key REQUEST replay returned HTTP 201 identical response; altered payload with same key returned HTTP 409 `IDEMPOTENCY_CONFLICT`; new key while pending returned HTTP 409 `CLOSURE_PENDING`. **M1 verified**: pre-worker state observed with worker OFF showing `attempt.closureCutoffAt=null` and student inbox 0 unconfirmed items. |
| **Target 4 (H1, M1)** | Owned synthetic bounded worker controls: staged activation (H1), pre-worker authoritative `PREPARING` notice (M1), durable PUBLISHED recipient notice, fresh eligibility, actual `confirmedNoticeAt` and immutable `closureCutoffAt = confirmedNoticeAt + 48 hours` (+172,800,000ms), owner inbox/detail, former CLASS member read. | **PASS** | **H1 verified**: operator-controlled staged transition activated assessment closure worker with 4000ms poll delay after verified pre-worker state. **M1 verified**: server-issued authoritative `PREPARING` notice captured before worker confirmation (`id=15cde82b-6d7d-4e34-bfea-c88544772c50`, `confirmedNoticeAt=null`, `closureCutoffAt=null`, UI alert 'Your notice is being prepared' and badge 'Notice preparing'). Worker confirmed notice with server-issued timestamps: `confirmedNoticeAt = 2026-10-17T12:41:12.294886Z`, `closureCutoffAt = 2026-10-19T12:41:12.294886Z` (delta = exactly 172,800,000ms = 48.0000h). Student navigated browser to notice detail. After membership removal, student entry was blocked (404), while notice inbox and detail remained accessible (200 OK). |
| **Target 5 (M2)** | Real Teacher CANCEL pre-finalization and durable non-actionable notice (no deletion). Replay/idempotency and conflicts (M2), foreign-Teacher fence (M2), account fencing, foreign denial. | **PASS** | **M2 verified**: foreign-Teacher authorization fence confirmed—unrelated Teacher receives HTTP 404 `PUBLICATION_NOT_FOUND` on GET, REQUEST, CANCEL, COMPLETE. Teacher confirmed CANCEL in UI: backend returned 200, state transitioned to `CANCELLED`, UI showed resolved. Cancelled notice persists in DB with `noticeStatus = CANCELLED` (no deletion). **M2 verified**: exact same-key CANCEL replay returned HTTP 200 identical response; altered payload returned HTTP 409 `IDEMPOTENCY_CONFLICT`; new key on resolved request returned HTTP 409 `CLOSURE_RESOLVED`; student denied CANCEL (403). |
| **Target 6** | Early COMPLETE before cutoff blocked with 409 `CLOSURE_NOT_READY`; preserve request identity. Genuine ≥48-hour operational soak, multi-node DB cluster failover outage recovery, and W3-M real LiveKit media. | **EARLY COMPLETE PASS / ≥48H SOAK & MULTI-NODE FAILOVER BLOCKED** | Early COMPLETE before cutoff blocked with 409 `CLOSURE_NOT_READY`; request identity and `PENDING` state preserved in UI. **Genuine ≥48-hour cutoff was not modified, database clocks were not manipulated, and staging soak PASS is NOT claimed.** Post-cutoff completion remains explicitly gated as **BLOCKED / NOT VERIFIED** for dedicated long-running staging soak. **Outage recovery**: safely feasible single-node dependency outage (SIGSTOP/resume) verified in public journey; multi-node cluster failover / split-brain recovery and W3-M real LiveKit proctoring explicitly marked as **BLOCKED / NOT VERIFIED** in synthetic single-node acceptance topology. |
| **Target 7** | Preserve existing real W3-A/W3-B/C 5/5 journeys, zero Playwright Gateway interception, and deterministic cleanup. | **PASS** | All 6/6 Playwright acceptance journeys passed cleanly (`Real W3-A suite: passed`). No mock/route interceptions. Python harness unit tests passed (19/19). Deterministic runtime teardown verified. |

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
  Target 2 verified: Gateway /actuator/health/assessment-inbox UP, Student inbox 200/404 verified, and Identity recipient contract verified with least-privilege SERVICE token scope.
  CLASS Assessment opened with durationMinutes: 0 and monitoringChoice: DISABLED.
  Student started ACTIVE unlimited attempt (duration_minutes: 0, deadline_at: null) and saved answer.
  Target 2 / H1 verified: Teacher REQUEST denied with 503 DEPENDENCY_UNAVAILABLE while worker and edge inbox attestation are OFF.
  H1 verified: Operator-controlled staged transition successfully activated edge inbox attestation while synthetic closure worker remains OFF.
  Early REQUEST denied with 409 CLOSURE_NOT_READY while participation window is active (now < endAt).
  M2 verified: REQUEST exact same-key replay returned 201 identical response; altered payload returned 409 IDEMPOTENCY_CONFLICT; new key while pending returned 409 CLOSURE_PENDING.
  Teacher requested delayed closure via UI: recorded durable closureRequestId ca76dd77-ba28-4871-874e-fdf98776ebe9, state PENDING, publication remains OPEN.
  M1 verified: Authoritative pre-worker state observed with worker OFF: closureRequestId=ca76dd77-ba28-4871-874e-fdf98776ebe9, state=PENDING, attempt.closureCutoffAt=null, student inbox=0 unconfirmed items.
  H1 verified: Operator-controlled staged transition successfully activated assessment closure worker with 4000ms poll delay after verified pre-worker state.
  M1 verified: Authoritative PREPARING notice captured before worker confirmation: id=15cde82b-6d7d-4e34-bfea-c88544772c50, confirmedNoticeAt=null, closureCutoffAt=null, UI alert 'Your notice is being prepared'.
  Worker published and confirmed recipient notice: confirmedNoticeAt=2026-10-17T12:41:12.294886Z, closureCutoffAt=2026-10-19T12:41:12.294886Z, exact delta=172800000ms (+48 hours).
  Student successfully navigated to notice detail in browser UI showing authoritative 48-hour cutoff.
  Former CLASS member read verified: entry rejected 404, but recipient notice inbox & detail remain accessible 200 OK.
  Separate disposable STUDENT authenticated via Gateway/OTP and real browser session.
  Cross-recipient masking verified: Student B receives 404 on Student A's notice and has empty inbox.
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

## 3. Detailed Verification of B3 Targets & Leader Findings

### 3.1 Target 1: Synthetic Actors & Unlimited CLASS Attempt
- **Teacher**: Registered via Gateway with disposable email and Mailpit OTP; enabled with `ROLE_TEACHER`. Created a Classroom and published an immutable Quiz with 4 question types.
- **CLASS Assessment**: Configured with `audience: "CLASS"`, `durationMinutes: 0` (unlimited, no deadline), and `monitoringChoice: "DISABLED"`.
- **Student**: Registered independently. Enrolled into Classroom using owned runtime database fixture. Entered assessment via `/app/assessments/join`, successfully started attempt with `deadline_at: null`, and submitted an initial answer to enter active in-progress state.

### 3.2 Target 2 & Finding H1: Pre-Worker Readiness & Two-Phase Operator Activation
- **H1 Finding**: The test harness previously had edge attestation and closure worker enabled unconditionally from boot.
- **Resolution & Evidence**:
  - The test suite now explicitly calls `fixtureControl("reset-closure-flags")` at the outset of the B3 journey, resetting `ASSESSMENT_EDGE_INBOX_VERIFIED=false`, `ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED=false`, and `ASSESSMENT_CLOSURE_WORKER_ENABLED=false`.
  - With both worker and edge attestation OFF, Teacher submitted `POST /api/assessments/{id}/closure-requests` and received HTTP 503 `DEPENDENCY_UNAVAILABLE` (`Closure notification delivery is temporarily unavailable; delayed closure cannot be initiated at this time`).
  - **Operator Staged Activation Phase 1**: Test harness invoked `fixtureControl("activate-edge-attestation")`. Assessment Service restarted with `ASSESSMENT_EDGE_INBOX_VERIFIED=true` and `ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED=true`, while `ASSESSMENT_CLOSURE_WORKER_ENABLED` remained `false`.
  - Gateway health check `GET /actuator/health/assessment-inbox` returned HTTP 200 with status `"UP"`.
  - Fresh Student inbox returned HTTP 200 with `items: []`; random UUID returned HTTP 404.
  - Identity recipient eligibility contract verified using OAuth2 `client_credentials` service token (`identity.notice-recipient.read` on `POST /internal/security/notice-recipients/{studentId}/eligibility` -> 200 `eligible: true`; unauthenticated -> 401; gateway client token -> 403 denied).

### 3.3 Target 3, Finding M1 & Finding M2: Request Idempotency, Conflicts & Pre-Worker Notice
- **Early REQUEST Denial**: Prior to participation window end (`now < endAt`), early REQUEST was rejected with HTTP 409 `CLOSURE_NOT_READY`.
- **Teacher REQUEST**: After window expiration, Teacher submitted delayed closure request via the UI (`/app/assessments/{id}/closure`):
  - Received HTTP 201 `ClosureView` with durable `closureRequestId`: `ca76dd77-ba28-4871-874e-fdf98776ebe9`.
  - Initial request state: `PENDING`.
  - Assessment Publication remained `OPEN` (no false CLOSED).
- **M2 Verification (REQUEST)**:
  - **Exact same-key replay**: Resending identical payload and idempotency key returned HTTP 201 with identical response body.
  - **Altered payload conflict**: Sending different payload (`durationMinutes: 15` vs `0`) with the same idempotency key returned HTTP 409 `IDEMPOTENCY_CONFLICT`.
  - **New key conflict**: Sending a fresh idempotency key while closure is already pending returned HTTP 409 `CLOSURE_PENDING`.
- **M1 Verification (Pre-Worker Authoritative State)**:
  - While worker remained OFF in Phase 1, the student attempt state was observed via API: `attempt.closureCutoffAt` was `null`.
  - Student notifications inbox was observed via API: `items: []` (0 unconfirmed notices delivered to recipient).
  - Assessment publication remained `OPEN`, request remained `PENDING`.

### 3.4 Target 4, Finding H1 & Finding M1: Staged Worker Activation, PREPARING Notice & Exact Cutoff
- **Operator Staged Activation Phase 2 (H1)**: Test harness invoked `fixtureControl("activate-closure-worker")` with a configured 4000ms initial polling delay. Assessment Service restarted with `ASSESSMENT_CLOSURE_WORKER_ENABLED=true`.
- **M1 Verification (Server-Issued PREPARING Notice)**:
  - Immediately following worker startup (during the 4000ms delay before confirmation processing), the unconfirmed notice was captured:
    - `id`: `15cde82b-6d7d-4e34-bfea-c88544772c50`
    - `confirmedNoticeAt`: `null`
    - `closureCutoffAt`: `null`
  - In Chromium, the student navigated to `/app/notifications/{noticeId}`:
    - UI displayed the "Notice preparing" badge and "Your notice is being prepared" informational alert.
- **Worker Confirmation & Cutoff Enforcement**:
  - The worker executed confirmation processing:
    - Recipient notice transitioned to `noticeStatus: "CONFIRMED"`.
    - Server-issued timestamps captured:
      - `confirmedNoticeAt`: `2026-10-17T12:41:12.294886Z`
      - `closureCutoffAt`: `2026-10-19T12:41:12.294886Z`
      - Exact delta: **172,800,000 milliseconds** (+48.0000 hours).
  - Student refreshed the notice detail in Chromium:
    - UI displayed "48-hour period confirmed" and authoritative cutoff `2026-10-19T12:41:12.294886Z`.
- **Former Member Access**:
  - Student was removed from Classroom membership via controlled database fixture.
  - Student attempt entry check was re-evaluated and blocked with HTTP 404.
  - Student notifications inbox and notice detail remained accessible with HTTP 200 OK.
- **Cross-Recipient Isolation**:
  - Separate disposable Student B authenticated.
  - Student B inbox returned 200 with empty list.
  - Student B attempting to access Student A's notice returned HTTP 404.

### 3.5 Target 5 & Finding M2: Foreign-Teacher Fence, CANCEL Replay & Conflicts
- **M2 Verification (Foreign-Teacher Fence)**:
  - A separate, unrelated Teacher (Teacher B) authenticated via Gateway/OTP.
  - Teacher B attempted operations on Teacher A's assessment publication:
    - `GET /api/assessments/{id}/closure` -> HTTP 404 `PUBLICATION_NOT_FOUND`
    - `POST /api/assessments/{id}/closure-requests` -> HTTP 404 `PUBLICATION_NOT_FOUND`
    - `POST /api/assessments/{id}/closure-requests/{requestId}/cancel` -> HTTP 404 `PUBLICATION_NOT_FOUND`
    - `POST /api/assessments/{id}/closure-requests/{requestId}/complete` -> HTTP 404 `PUBLICATION_NOT_FOUND`
- **Teacher CANCEL**:
  - Teacher confirmed cancellation in the UI:
    - Sent `POST /closure-requests/{id}/cancel` with idempotency key and expected publication revision.
    - Backend returned HTTP 200 with state `CANCELLED`.
    - UI confirmed "Cancellation confirmed by the server" and "This request is resolved".
- **Notice Retention**:
  - Student reloaded notice detail: displayed "Closure cancelled" alert.
  - Direct API `GET /api/assessments/notifications/{noticeId}` returned HTTP 200 with `noticeStatus: "CANCELLED"` (retained durably as history, not deleted).
- **M2 Verification (CANCEL Replay & Conflicts)**:
  - **Exact same-key replay**: Resending identical payload and idempotency key returned HTTP 200 identical response.
  - **Altered payload conflict**: Sending different expectedRevision with the same key returned HTTP 409 `IDEMPOTENCY_CONFLICT`.
  - **New key conflict on resolved request**: Sending a fresh idempotency key to cancel an already-resolved request returned HTTP 409 `CLOSURE_RESOLVED`.
  - **Student denial**: Student attempting to cancel returned HTTP 403.

### 3.6 Target 6: Early COMPLETE Denial & Gated Operational Subgates
- Prior to the 48-hour cutoff, Teacher executed "Review and COMPLETE closure" in the UI:
  - Backend returned HTTP 409 `CLOSURE_NOT_READY`.
  - UI preserved the command identity and `PENDING` request state.
  - Publication remained `OPEN`.
- **Strict Boundary Maintained**:
  - The genuine 48-hour cutoff was **not manipulated**.
  - Database clocks and timestamps were **not backdated**.
  - Operational soak PASS is **not claimed**.
  - Actual post-cutoff completion remains **BLOCKED / NOT VERIFIED** (deferred to long-running staging soak).

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
2. **Python Runtime Harness Unit Tests**: 19/19 tests passed (`test_w3a_real_runtime.py`).
3. **Frontend Vitest Unit Suite**: 56/56 test files, 682/682 tests passed.
4. **Frontend Code Quality**:
   - `pnpm --dir frontend format:check`: PASS (0 style issues)
   - `pnpm --dir frontend lint`: PASS (0 errors, 0 warnings)
   - `pnpm --dir frontend typecheck`: PASS (0 TypeScript errors)
5. **Teardown Verification**:
   - Runtime directory cleanly torn down via `python3 scripts/local/w3a-real-runtime.py down`.
   - Host developer containers (`quizopia-postgres-1`, `quizopia-redis-1`, `quizopia-rabbitmq-1`, `quizopia-mailpit-1`, `quizopia-livekit-1`, `quizopia-minio-1`) remained healthy and untouched.
