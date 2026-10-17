# Wave 3 Real End-to-End Acceptance Evidence

**Issue**: [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97)  
**PR**: [#99](https://github.com/hhtuann/Quizopia_2.0/pull/99)  
**Branch**: `test/w3-real-acceptance-runtime`  
**Base Commit (Backend / Parent)**: `399e1fb1704daeb4e597bf901e03a48af5cd2689`  
**Execution Timestamp**: 2026-10-17T05:04:03Z  
**Execution Status**: **REAL ACCEPTANCE VERIFIED (Assessment & Edge Core)** / **B3 Delayed Closure, LiveKit & 48h Soak Gated as BLOCKED**

---

## 1. Executive Summary & Gate Status

In compliance with `AGENTS.md` and `docs/development/w3-real-acceptance-isolation-preflight.md`, the primary implementation agent has built and executed reproducible real Gateway, backend (Identity, Quiz, Classroom, Assessment), and Chromium browser acceptance tests for Wave 3 without mocking or intercepting HTTP requests.

All tests executed against an isolated synthetic test topology with separate ephemeral ports, dedicated Docker containers, independent databases and credentials, and isolated synthetic test accounts. Developer services running on default host ports (5432, 6379, 5672, 8025, 7880, 9000) remained untouched throughout.

### Gate Evaluation Matrix

| Gate | Status | Evidence / Notes |
| --- | --- | --- |
| **Frontend Wave 3 Composition / Unit Suite** | **PASS** | 56 test files, 681 unit tests passed (`pnpm test`); production build succeeded (`pnpm build`). |
| **Safety Preflight & Isolation on Developer Host** | **PASS** | `python3 scripts/local/w3-real-acceptance-preflight.py` exit code 0. Unit tests in `scripts/local/tests` pass (13/13). |
| **Real Frontend Server Ownership & Port Isolation (H1)** | **PASS** | `playwright.w3a-real.config.ts` enforces `reuseExistingServer: false`. Port is dynamically aligned via `w3a-real-config.ts`. Occupied ports fail closed; verified by unit tests in `w3a-real-config.test.ts`. |
| **Real Assessment / Identity / Classroom / Quiz / Gateway HTTP + Chromium** | **PASS** | 5/5 real Playwright tests passed with zero mocks (`Real W3-A suite: passed`). |
| **Real Student Attempt, Autosave (4 Question Types), Revision, Submit & Score** | **PASS** | Verified on `SINGLE_CHOICE`, `MULTIPLE_CHOICE`, `TRUE_FALSE_MATRIX`, and `NUMERIC_FILL` with 100.00% server grading. |
| **Real Direct Assessment Closure & Teacher Results Table** | **PASS** | Direct Teacher CLOSE succeeds after submission; Teacher results table renders 100.00% result; post-closure student review authorized. |
| **Real Classroom Membership Gate & 409 Conflict** | **PASS** | Student entry denied before membership fixture; ACTIVE attempt correctly returns 409 ACTIVE_ATTEMPTS conflict blocking teacher CLOSE. |
| **Student Notifications Inbox Screen & Authenticated Contact (M1)** | **PASS** | Student notifications inbox screen reached (`Your inbox`, `No assessment notices yet`); authenticated `GET /api/assessments/notifications` returns 200 with empty notice list prior to delayed closure requests. |
| **B3 Notice Publication & Delayed Closure Protocol (M1 & M2)** | **BLOCKED / NOT VERIFIED** | **Explicitly Gated**: Per-recipient confirmation, confirmed timestamps/cutoffs, REQUEST/CANCEL/COMPLETE, and 48-hour soak are not exercised in direct-CLOSE smoke harness. In accordance with Leader review M2, `ASSESSMENT_CLOSURE_WORKER_ENABLED=false`, `ASSESSMENT_EDGE_INBOX_VERIFIED=false`, and `ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED=false` are defaulted OFF. |
| **48-Hour Background Closure Worker Soak** | **BLOCKED / NOT VERIFIED** | Unsuitable for bounded real CI/acceptance run; genuine 48-hour operational soak requires dedicated long-running staging worker. |
| **Real W3-M Activity Evidence + LiveKit WebRTC Media** | **BLOCKED / NOT VERIFIED** | Separate conditional gate. Camera/WebRTC media monitoring kept strictly DISABLED (`PROCTORING_ENABLED=false`). Approval references (`PROCTORING_PRIVACY_APPROVAL_REFERENCE`) were not fabricated. |
| **Production Release & Merge** | **OPEN / NO-GO** | Draft PR only. Never merge unverified live privacy/provider capabilities. |

---

## 2. Review Finding Resolutions (PR #99)

### 2.1 H1 — Real-Browser Ownership and Isolation
- **Problem**: `playwright.w3a-real.config.ts` previously used `reuseExistingServer: true` and hardcoded `--port 3000`, creating a risk of silently adopting an unrelated running process on port 3000.
- **Resolution**:
  - Created [`frontend/tests/w3a-real-e2e/w3a-real-config.ts`](file:///home/hhtuann/Documents/Projects/Quizopia_2.0/frontend/tests/w3a-real-e2e/w3a-real-config.ts), dynamically extracting host, port, and command from `W3A_REAL_FRONTEND_URL` while enforcing loopback-only binding (`localhost`, `127.0.0.1`, `::1`).
  - Set `reuseExistingServer: false` strictly in `playwright.w3a-real.config.ts`, ensuring Playwright fails closed if an existing listener occupies the URL.
  - Implemented `assertFrontendPortFree` to probe port availability on loopback and fail closed with a clear error prohibiting killing another developer's listener.
  - Added regression unit tests in [`frontend/tests/w3a-real-config.test.ts`](file:///home/hhtuann/Documents/Projects/Quizopia_2.0/frontend/tests/w3a-real-config.test.ts) (7/7 pass) and [`scripts/local/tests/test_w3_real_acceptance_preflight.py`](file:///home/hhtuann/Documents/Projects/Quizopia_2.0/scripts/local/tests/test_w3_real_acceptance_preflight.py).

### 2.2 M1 — Accurate Inbox and Closure Acceptance Evidence
- **Problem**: The CLASS journey asserted only screen navigation to `/app/notifications` without verifying the authenticated HTTP API response, while evidence previously overstated inbox/closure acceptance.
- **Resolution**:
  - Updated [`frontend/tests/w3a-real-e2e/assessment-journeys.spec.ts`](file:///home/hhtuann/Documents/Projects/Quizopia_2.0/frontend/tests/w3a-real-e2e/assessment-journeys.spec.ts) to explicitly intercept and assert `GET /api/assessments/notifications` HTTP 200, confirming authenticated gateway routing and validating the empty inbox DOM headings (`Your inbox` and `No assessment notices yet`).
  - Corrected the evidence matrix: only Direct Assessment CLOSE and Student Inbox Screen Navigation / Authenticated 200 are certified. Full B3 Notice Publication and Delayed Closure (REQUEST/CANCEL/COMPLETE, 48-hour soak, per-recipient confirmed delivery) is explicitly marked **BLOCKED / NOT VERIFIED**.

### 2.3 M2 — Premature Closure-Worker and Readiness Attestation
- **Problem**: `scripts/local/w3a-real-runtime.py` previously preconfigured `ASSESSMENT_CLOSURE_WORKER_ENABLED=true`, `ASSESSMENT_EDGE_INBOX_VERIFIED=true`, and `ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED=true` before proving the full B3 protocol.
- **Resolution**:
  - Updated [`scripts/local/w3a-real-runtime.py`](file:///home/hhtuann/Documents/Projects/Quizopia_2.0/scripts/local/w3a-real-runtime.py) so that optional background closure worker, edge inbox attestation, recipient eligibility, and identity notice recipients default to `"false"`.
  - Added regression tests in [`scripts/local/tests/test_w3a_real_runtime.py`](file:///home/hhtuann/Documents/Projects/Quizopia_2.0/scripts/local/tests/test_w3a_real_runtime.py) (`FlagDefaultTests`) verifying that optional B3 machinery is defaulted OFF.

---

## 3. Runtime Environment & Service Provenance

### 3.1 Backend Build Artifacts

All service JARs were freshly built and packaged from commit `399e1fb1704daeb4e597bf901e03a48af5cd2689` under Java 21:

| Service | SHA256 Digest |
| --- | --- |
| `identity-service` | `398e843c1bd80d06d1868c83a9e34926afdbd58558452ceb60ae95c9a4a4758c` |
| `quiz-service` | `c4c1f56028a920154a7404e49141a952883a51bcc0588221af46291bbae66edd` |
| `classroom-service` | `c1f3ffca18327287915ee0c2d4723d5853c1c3b2db93cf0d1d6b76ce5d819c4b` |
| `assessment-service` | `f1337446efa4a8ef7f92760dad7666d34f6de2a39eda058fda5abf4a6c1be582` |
| `gateway` | `722be3c9e840f607ae00a710aae9b022a9f7da9abb08ba857cfa7be233589608` |

### 3.2 Isolated Topology & Ports

All dependencies ran in dedicated, ownership-labeled containers with dynamic ephemeral ports:

- **Resource Prefix**: `quizopia-w3a-69e4cfc5b6`
- **Containers**:
  - `quizopia-w3a-69e4cfc5b6-identity-postgres` (PostgreSQL 17, `identity_db`)
  - `quizopia-w3a-69e4cfc5b6-quiz-postgres` (PostgreSQL 17, `quiz_db`)
  - `quizopia-w3a-69e4cfc5b6-classroom-postgres` (PostgreSQL 17, `classroom_db`)
  - `quizopia-w3a-69e4cfc5b6-assessment-postgres` (PostgreSQL 17, `assessment_db`)
  - `quizopia-w3a-69e4cfc5b6-redis` (Redis 7)
  - `quizopia-w3a-69e4cfc5b6-mailpit` (Mailpit loopback test SMTP/HTTP)
- **Microservice Loopback Endpoints**:
  - Identity Service: `http://127.0.0.1:56699`
  - Quiz Service: `http://127.0.0.1:35543`
  - Classroom Service: `http://127.0.0.1:43229`
  - Assessment Service: `http://127.0.0.1:41443`
  - Gateway: `http://127.0.0.1:8080`
  - Mailpit API: `http://127.0.0.1:60641`
- **Frontend WebServer**: Dedicated Next.js Dev Server running on `http://localhost:3000` via Playwright harness with `reuseExistingServer: false`.

### 3.3 Active Feature Flags & Safety Controls

| Flag | Value | Purpose |
| --- | --- | --- |
| `PUBLICATION_ENABLED` | `true` | Enables assessment authoring & publication. |
| `ATTEMPTS_ENABLED` | `true` | Enables student attempt lifecycle. |
| `RESULTS_ENABLED` | `true` | Enables server grading and results presentation. |
| `CLOSURE_ENABLED` | `true` | Enables teacher review and direct publication closure. |
| `ASSESSMENT_INBOX_ENABLED` | `true` | Enables student notifications inbox routing. |
| `INTERNAL_AUTHORITY_ENABLED` | `true` | Permits service-to-service internal calls. |
| `ASSESSMENT_CLOSURE_WORKER_ENABLED` | `false` | **Defaulted OFF per M2**: Unverified background closure worker disabled. |
| `ASSESSMENT_EDGE_INBOX_VERIFIED` | `false` | **Defaulted OFF per M2**: Unproven edge inbox attestation omitted. |
| `ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED` | `false` | **Defaulted OFF per M2**: Recipient eligibility checks omitted. |
| `IDENTITY_NOTICE_RECIPIENTS_ENABLED` | `false` | **Defaulted OFF per M2**: Notice recipient lookup disabled. |
| `PROCTORING_ENABLED` | `false` | **Safety constraint**: Disables unapproved proctoring. |
| `PROCTORING_MEDIA_ENABLED` | `false` | **Safety constraint**: Prevents unapproved media capture. |
| `ASSESSMENT_MONITORING_CAPTURE_ENABLED` | `false` | **Safety constraint**: Blocks browser surveillance. |

---

## 4. Real Acceptance Execution & Evidence

### 4.1 Playwright Real Acceptance Execution Output

Command executed:
```bash
W3A_REAL_GATEWAY_URL=http://localhost:8080 \
W3A_REAL_MAILPIT_URL=http://127.0.0.1:60641 \
W3A_REAL_STATE_DIRECTORY=/tmp/quizopia-w3a-px0zhxke \
pnpm exec playwright test --config=playwright.w3a-real.config.ts
```

Output:
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

## 5. Unresolved / Gated Areas (Blocker Declaration)

In strict accordance with `AGENTS.md` and user instructions:

1. **B3 Notice Publication & Delayed Closure**:
   - **Status**: **BLOCKED / NOT VERIFIED**
   - **Reason**: The direct-CLOSE smoke harness does not exercise delayed closure requests, per-recipient confirmed delivery cutoffs, or the 48-hour soak period. Edge inbox attestation and closure worker remain defaulted OFF.
2. **LiveKit WebRTC Media Proctoring**:
   - **Status**: **BLOCKED / NOT VERIFIED**
   - **Reason**: LiveKit server and client WebRTC media streams require hardware cameras and real media topology. Furthermore, real camera capture requires human-reviewed privacy references (`PROCTORING_PRIVACY_APPROVAL_REFERENCE`), which must never be fabricated. This capability is intentionally isolated to a separate gated acceptance phase.
3. **48-Hour Operational Closure Soak**:
   - **Status**: **BLOCKED / NOT VERIFIED**
   - **Reason**: Testing the 48-hour auto-close worker soak cannot be simulated legitimately within a bounded real E2E run without synthetic clock manipulation, which would violate real runtime integrity. Must be monitored on long-running staging environments.

---

## 6. Cleanup Verification

- All temporary state and runtime data were stored in `/tmp/quizopia-w3a-px0zhxke`.
- `w3a-real-runtime.py down` cleanly stopped all 5 owned service processes (`identity`, `quiz`, `classroom`, `assessment`, `gateway`), removed all 6 dedicated Docker containers (`quizopia-w3a-69e4cfc5b6-*`), and purged temporary database volumes.
- Host developer containers on ports 5432, 6379, 5672, 8025, 7880, and 9000 were verified completely unaffected.
- Ports 3000 and 8080 were confirmed free following teardown.
