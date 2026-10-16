# Wave 3 Real End-to-End Acceptance Evidence

**Issue**: [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97)  
**Branch**: `test/w3-real-acceptance-runtime`  
**Base Commit (Backend / Parent)**: `399e1fb1704daeb4e597bf901e03a48af5cd2689`  
**Execution Timestamp**: 2026-10-16T19:26:48Z  
**Execution Status**: **REAL ACCEPTANCE VERIFIED (Assessment & Edge Core)** / **LiveKit & 48h Soak Gated as BLOCKED**

---

## 1. Executive Summary & Gate Status

In compliance with `AGENTS.md` and `docs/development/w3-real-acceptance-isolation-preflight.md`, the primary implementation agent has built and executed reproducible real Gateway, backend (Identity, Quiz, Classroom, Assessment), and Chromium browser acceptance tests for Wave 3 without mocking or intercepting HTTP requests.

All tests executed against an isolated synthetic test topology with separate ephemeral ports, dedicated Docker containers, independent databases and credentials, and isolated synthetic test accounts. Developer services running on default host ports (5432, 6379, 5672, 8025, 7880, 9000) remained untouched throughout.

### Gate Evaluation Matrix

| Gate | Status | Evidence / Notes |
| --- | --- | --- |
| **Frontend Wave 3 Composition / Mocked Suite** | **PASS** | 55 test files, 674 unit tests passed (`pnpm test`); production build succeeded (`pnpm build`). |
| **Safety Preflight & Isolation on Developer Host** | **PASS** | `python3 scripts/local/w3-real-acceptance-preflight.py` exit code 0. Unit tests in `scripts/local/tests` pass (11/11). |
| **Real Assessment / Identity / Classroom / Quiz / Gateway HTTP + Chromium** | **PASS** | 5/5 real Playwright tests passed with zero mocks (`Real W3-A suite: passed`). |
| **Real Student Attempt, Autosave (4 Question Types), Revision, Submit & Score** | **PASS** | Verified on `SINGLE_CHOICE`, `MULTIPLE_CHOICE`, `TRUE_FALSE_MATRIX`, and `NUMERIC_FILL` with 100.00% server grading. |
| **Real Assessment Closure & Teacher Results Table** | **PASS** | Teacher CLOSE succeeds after submission; Teacher results table renders 100.00% result; post-closure student review authorized. |
| **Real Classroom Membership Gate & 409 Conflict** | **PASS** | Student entry denied before membership fixture; ACTIVE attempt correctly returns 409 ACTIVE_ATTEMPTS conflict blocking teacher CLOSE. |
| **Real Notifications Inbox** | **PASS** | Authenticated student notifications inbox accessible via real Gateway. |
| **48-Hour Background Closure Worker Soak** | **BLOCKED / NOT VERIFIED** | Unsuitable for bounded real CI/acceptance run; genuine 48-hour operational soak requires dedicated long-running staging worker. |
| **Real W3-M Activity Evidence + LiveKit WebRTC Media** | **BLOCKED / NOT VERIFIED** | Separate conditional gate. Camera/WebRTC media monitoring kept strictly DISABLED (`PROCTORING_ENABLED=false`). Approval references (`PROCTORING_PRIVACY_APPROVAL_REFERENCE`) were not fabricated. |
| **Production Release & Merge** | **OPEN / NO-GO** | Draft PR only. Never merge unverified live privacy/provider capabilities. |

---

## 2. Runtime Environment & Service Provenance

### 2.1 Backend Build Artifacts

All service JARs were freshly built and packaged from commit `399e1fb1704daeb4e597bf901e03a48af5cd2689` under Java 21:

| Service | SHA256 Digest |
| --- | --- |
| `identity-service` | `cee2da64d4e5aa2f7367f72596542b1d2cccccd0d01c706786192b8362746dfc` |
| `quiz-service` | `cff06819951b36b1798b5df488c6dfd8b0029ea382408d542299dca0b4f692ef` |
| `classroom-service` | `633abc2a579da16ed09d3403615ebe25ebb7a2ae2f965781769e9160084b5a1e` |
| `assessment-service` | `1589033dd84dfee5b0f73b657699eca37196c81fc5119495cf2d835f18beaf26` |
| `gateway` | `8aa6b56d6cf177c2a8de9a5119ae6bc7467ea3fd2ac53ca1b7399c3c50b894a3` |

### 2.2 Isolated Topology & Ports

All dependencies ran in dedicated, ownership-labeled containers with dynamic ephemeral ports:

- **Resource Prefix**: `quizopia-w3a-e527f267bd`
- **Containers**:
  - `quizopia-w3a-e527f267bd-identity-postgres` (PostgreSQL 17)
  - `quizopia-w3a-e527f267bd-quiz-postgres` (PostgreSQL 17)
  - `quizopia-w3a-e527f267bd-classroom-postgres` (PostgreSQL 17)
  - `quizopia-w3a-e527f267bd-assessment-postgres` (PostgreSQL 17)
  - `quizopia-w3a-e527f267bd-redis` (Redis 7)
  - `quizopia-w3a-e527f267bd-mailpit` (Mailpit loopback test SMTP/HTTP)
- **Microservice Loopback Endpoints**:
  - Identity Service: `http://127.0.0.1:55695`
  - Quiz Service: `http://127.0.0.1:47833`
  - Classroom Service: `http://127.0.0.1:58691`
  - Assessment Service: `http://127.0.0.1:32967`
  - Gateway: `http://127.0.0.1:8080`
  - Mailpit API: `http://127.0.0.1:54053`
- **Frontend WebServer**: Next.js Dev Server running on `http://localhost:3000` via Playwright harness.

### 2.3 Feature Flags & Safety Controls

| Flag | Value | Purpose |
| --- | --- | --- |
| `PUBLICATION_ENABLED` | `true` | Enables assessment authoring & publication. |
| `ATTEMPTS_ENABLED` | `true` | Enables student attempt lifecycle. |
| `RESULTS_ENABLED` | `true` | Enables server grading and results presentation. |
| `CLOSURE_ENABLED` | `true` | Enables teacher review and publication closure. |
| `ASSESSMENT_INBOX_ENABLED` | `true` | Enables student notifications inbox. |
| `INTERNAL_AUTHORITY_ENABLED` | `true` | Permits service-to-service internal calls. |
| `PROCTORING_ENABLED` | `false` | **Safety constraint**: Disables unapproved proctoring. |
| `PROCTORING_MEDIA_ENABLED` | `false` | **Safety constraint**: Prevents unapproved media capture. |
| `ASSESSMENT_MONITORING_CAPTURE_ENABLED` | `false` | **Safety constraint**: Blocks browser surveillance. |

---

## 3. Real Acceptance Execution & Evidence

### 3.1 Playwright Real Acceptance Execution Output

Command executed:
```bash
W3A_REAL_GATEWAY_URL=http://localhost:8080 \
W3A_REAL_MAILPIT_URL=http://127.0.0.1:54053 \
W3A_REAL_STATE_DIRECTORY=/tmp/quizopia-w3a-21s_47v7 \
pnpm exec playwright test --config=playwright.w3a-real.config.ts
```

Output:
```text
passed: real PUBLIC Assessment: Teacher OPEN, Student locator entry, 4 question types autosave/revision, submit, result, teacher CLOSE, results table and student closed review
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
  Student notifications inbox accessible and authenticated via real Gateway.
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

## 4. Specific Wave 3 Assessment Capabilities Verified

1. **Full Four-Question Type Real Autosave**:
   - `SINGLE_CHOICE`: Option A selected, HTTP 200 response received, status transitioned to `Saved`.
   - `MULTIPLE_CHOICE`: Option A ("TCP") and Option B ("UDP") selected sequentially, HTTP 200 responses received, status transitioned to `Saved`.
   - `TRUE_FALSE_MATRIX`: Statements A (True), B (False), C (True), D (False) judged, HTTP 200 responses received, status transitioned to `Saved`.
   - `NUMERIC_FILL`: Value `"2.50"` entered, HTTP 200 response received, status transitioned to `Saved`.
2. **Revision Sequence Validation**:
   - Question 1 switched to Option B ("FTP"), HTTP 200 received with incremented revision number.
   - Question 1 switched back to Option A ("HTTP"), HTTP 200 received with next revision number.
3. **Idempotent Submission & Result Calculation**:
   - POST `/attempts/{id}/submit` returned HTTP 200.
   - Direct transition to `/app/attempts/{id}/result`.
   - Authoritative Result Screen rendered server-computed total: `100.00%`, `4 of 4 exact weight`.
4. **Assessment Closure & 409 Conflict Protection**:
   - In CLASS assessment with ACTIVE attempt: Teacher CLOSE attempt returned HTTP 409 `ACTIVE_ATTEMPTS`.
   - After student abandoned attempt: Teacher CLOSE returned HTTP 200 `Publication CLOSED`.
   - In PUBLIC assessment with SUBMITTED attempt: Teacher CLOSE returned HTTP 200.
5. **Teacher Results Table**:
   - Teacher navigated to `/app/assessments/{id}/results`.
   - Table rendered attempt row with exact attempt ID and `100.00%` score.
6. **Post-Closure Student Review**:
   - Student navigated to `/app/attempts/{id}/review`.
   - Rendered "Authorized closed review" with questions, student answers, and explanation text.
7. **Student Notifications Inbox**:
   - Student navigated to `/app/notifications`.
   - Authenticated inbox loaded successfully via Gateway without unauthorized redirection.
8. **Security & Confidentiality**:
   - Refresh token retained in HttpOnly cookie (`SameSite=Lax`, `Path=/api/auth`).
   - Browser `localStorage` and `sessionStorage` contained zero tokens or secrets.
   - Private assessment locator never leaked into browser history or console.

---

## 5. Unresolved / Gated Areas (Blocker Declaration)

In strict accordance with `AGENTS.md` and user instructions:

1. **LiveKit WebRTC Media Proctoring**:
   - **Status**: **BLOCKED / NOT VERIFIED**
   - **Reason**: LiveKit server and client WebRTC media streams require hardware cameras and real media topology. Furthermore, real camera capture requires human-reviewed privacy references (`PROCTORING_PRIVACY_APPROVAL_REFERENCE`), which must never be fabricated. This capability is intentionally isolated to a separate gated acceptance phase.
2. **48-Hour Operational Closure Soak**:
   - **Status**: **BLOCKED / NOT VERIFIED**
   - **Reason**: Testing the 48-hour auto-close worker soak cannot be simulated legitimately within a bounded real E2E run without synthetic clock manipulation, which would violate real runtime integrity. Must be monitored on long-running staging environments.

---

## 6. Cleanup Verification

- All temporary state and runtime data are maintained in `/tmp/quizopia-w3a-21s_47v7`.
- `w3a-real-runtime.py down` cleanly stops all 5 owned service processes (`identity`, `quiz`, `classroom`, `assessment`, `gateway`), removes the 6 dedicated Docker containers (`quizopia-w3a-e527f267bd-*`), and purges temporary database volumes.
- Host developer containers on ports 5432, 6379, 5672, 8025, 7880, and 9000 were verified completely unaffected.
