# Frontend LD-05 takeover review

Audit started: 2026-10-11; verification/finalization: 2026-10-12 (Asia/Bangkok).
Owner: Dev 2 implementation agent. Verdict: **BLOCKED — INTEGRATION**.
Frontend correctness/quality and independent agent review pass. Merged PR #76 now
defines the scoped Gateway/Classroom public contract; browser LD-05 outage/recovery
integration and the real-executor expiry suite remain outstanding. Draft PR #77
is open. The executor port conflict is resolved; the current backend endpoints
are unavailable and isolated short-TTL provisioning remains required.

## Initial read-only audit

Completed before application edits on handoff `6c1192631ba7042410e3cd9bf81ad2616022fbbc`.
Branch: `fix/frontend-ld05-login-hydration`; clean worktree. Develop:
`123c8f5abe16c266c4d06c549cb8c84cca19ea92`; merge-base/handoff base:
`3827822ffe59d906804a805ea6efb983a963eb2b`. All 21 handoff files were inspected.
Existing implementation and test reports were treated as unverified.

| ID  | Severity | Evidence/reproduction at handoff                                                                                                                                                                                                                           | Planned resolution                                                                      |
| --- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| F1  | High     | `frontend/lib/api/authenticated-request.ts`, `execute`, `executeOnce`, `retryOnce`: start a protected request as A, clear/switch to B, resolve A's transport with 200. Only 401 paths check session generation; A's successful payload reaches the caller. | Fence every protected completion, including retry and one-shot success.                 |
| F2  | Medium   | `auth-session-service.ts`, `refreshOperation`: bootstrap refresh succeeds, `/me` returns 503. Candidate is discarded and coordinator enters `bootstrap-failed`; no verification retry exists.                                                              | Retain bootstrap candidate privately and expose the existing manual verification state. |
| F3  | Medium   | `auth-session-service.ts`, `refreshOperation`: non-settling ordinary/bootstrap refresh holds local queue and browser lock; logout waits indefinitely. Recovery refresh alone has a timeout.                                                                | Bound all interactive refresh requests; preserve transient-failure semantics.           |
| F4  | Medium   | `browser-auth-lock.ts`: a lock held in another tab prevents callback entry, so the recovery request's ten-second timer never starts. New intent cannot cancel queued acquisition.                                                                          | Bound and cancel acquisition separately from request execution.                         |
| F5  | Medium   | `session-verification-recovery.tsx`: retry, unmount, resolve success. The old component still invokes navigation; remount resets pending presentation while service retry remains active.                                                                  | Fence component completion and observe the shared retry across remounts.                |
| F6  | Low      | Recovery UI ignores failure results and leaves identical text after each retry; a permanent denial removes the control without new login feedback.                                                                                                         | Announce retry failure and genuine ended-session outcomes.                              |

Confirmed retained behavior: operation generation invalidates login/bootstrap/recovery
before late success, invalid and unavailable commits; session generation separately
guards 401 replay. Candidate tokens remain in memory outside the business token vault.
`/me` has a ten-second raced deadline and actual fetch signal propagation. Explicit
expired-candidate retries are single-flight and bound refresh to ten seconds, with
no automatic refresh/logout on infrastructure failure. Real `/me` 401/403 and
`401 AUTH_REFRESH_FAILED` retain denial semantics. Roles come only from `/me`;
workspace selection grants no role. No new credential persistence or logging found.

Missing coverage at handoff: bootstrap candidate recovery, ordinary refresh stall,
queued native Web Lock cancellation, successful stale protected responses, Teacher
enablement during account change, recovery unmount/remount, keyboard retry, repeated
failure announcements, malformed JSON and response classification.

Suspected risks/dependencies: cancellation cannot undo server cookie rotation or
late Set-Cookie; fallback queue cannot coordinate other tabs. Explicit retry after
uncertain rotation is user initiated, never automatic; real cookie behavior needs
backend integration evidence. Public outage status/error mappings are not frozen
by the Identity-only internal admission endpoint. No frontend bypass is authorized.

Visual inspection of source: existing Alert/Button/Surface/PageContainer and
Corporate Trust typography/tokens are retained. Keyboard focus styles and loading
disable semantics exist in shared Button. Browser responsive and keyboard evidence
will be recorded after implementation.

## Confirmed fixes and independent final review

The handoff architecture was retained. No auth rewrite, backend, schema, event,
dependency, or runtime configuration change was made. The PR diff is frontend
plus authentication/review documentation. A normal conflict-free merge preserves
all PR #75 and #76 changes from develop.

| ID  | Severity | Final affected source                                                                                                     | Resolution and regression                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| --- | -------- | ------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| F1  | High     | `frontend/lib/api/authenticated-request.ts:60`                                                                            | Every protected response is session-generation fenced, including successful one-shot and replay responses. Controlled A request/B login/late A200 tests reject the old payload.                                                                                                                                                                                                                                                                                       |
| F2  | Medium   | `frontend/features/auth/session/auth-session-service.ts:253`                                                              | Bootstrap retains candidate privately on transient hydration failure and enters verification unavailable; retry uses the candidate without rotating again. Explicit login discards a candidate produced while awaiting bootstrap.                                                                                                                                                                                                                                     |
| F3  | Medium   | `frontend/features/auth/session/auth-session-service.ts:236`                                                              | Ordinary/bootstrap refresh, login and logout are bounded to ten seconds per request. Fake-timer non-settling transport tests prove signal abort, queue/held lock release, logout and subsequent login.                                                                                                                                                                                                                                                                |
| F4  | Medium   | `frontend/features/auth/session/auth-session-service.ts:118`, `browser-auth-lock.ts:3`                                    | Lock acquisition is bounded separately and aborts on superseding intent; late callbacks cannot execute. Controlled queued-lock tests cover timeout/cancellation independently of fetch cooperation.                                                                                                                                                                                                                                                                   |
| F5  | Medium   | `frontend/features/auth/components/session-verification-recovery.tsx:8`, `frontend/features/auth/forms/login-form.tsx:30` | Pending presentation uses the existing shared runtime; remount stays disabled, old component callbacks are fenced, and the mounted login form owns recovery navigation. Unmount/remount tests prove no duplicate retry or obsolete navigation.                                                                                                                                                                                                                        |
| F6  | Low      | `frontend/features/auth/components/session-verification-recovery.tsx:47`                                                  | A named live status announces each failed attempt and pending verification; genuine denial returns to the existing sign-in failure presentation.                                                                                                                                                                                                                                                                                                                      |
| F7  | Medium   | `frontend/features/auth/session/auth-session-service.ts:580`                                                              | Final reviewer reproduced failed logout acquisition leaving candidate on obsolete operation generation. Guarded recovery restoration now covers acquisition and HTTP failure. Timeout/rejection tests recover via a later successful `/me`.                                                                                                                                                                                                                           |
| F8  | Medium   | `frontend/lib/api/http-transport.ts:122`, `frontend/features/auth/api/auth-api-client.ts:146`                             | Malformed JSON previously hid received `/me`401/403 as transport failure. Decode failure now retains HTTP status; normalization preserves real denial. Actual fetch-transport tests cover malformed200/401/403/503.                                                                                                                                                                                                                                                   |
| F9  | High     | `frontend/features/auth/session/refresh-coordinator.ts:59`                                                                | Final reviewer reproduced ordinary refresh abort/network followed by another protected401 automatically replaying a possibly consumed cookie. A transient result is now latched per session generation; subsequent automatic refresh returns failure without rotation/expiry. New verified login resets by generation; existing explicit Teacher action opts into one explicit refresh. Timeout/network/503 tests assert no extra refresh/logout across repeated401s. |
| F10 | Low      | `frontend/features/auth/components/session-verification-recovery.tsx:55`                                                  | Real Chromium accessibility tree reported the loading button unnamed despite its nested status. Explicit `aria-label="Verifying account"` preserves the disabled control's accessible name. Native-browser regression verifies the label and lock timeout.                                                                                                                                                                                                            |

Separate read-only reviewer: `/root/ld05_final_review`, reviewing merged
`424fd2121ce458dd4019928e26b76769bd2e5537` plus focused final patches.
The reviewer independently reproduced F7/F9 before and after their fixes and
confirmed F8's classification. Final finding: **no remaining confirmed
Blocker/High**. This is agent review, not human/Leader approval. It does not
establish actual Redis-outage behavior. A separate read-only delta review after
merge `718cd26` checked PR #76 actual filters, response codes and the two new
fetch-envelope tests, and found no new confirmed security/correctness findings.
It confirmed scoped contract compatibility while retaining the integration gate.

G1 evidence includes success/invalid/unavailable completions across login and
bootstrap phases, overlapping accounts, local clear/logout, superseded bootstrap,
stale Teacher enablement and protected payload/replay fencing. G2 covers both
Web Locks and fallback queue, non-cooperating transports, ten-second fake timers,
actual fetch signals, recovery after timeout, and no automatic rotation replay.
No token/cookie/password/OTP logging or JavaScript credential persistence was added.
Only `/me` supplies roles; the candidate never enters the business token vault.
Outage alone sends no logout or refresh-family invalidation.

## Backend contract handoff and remaining integration request

At initial audit, PR #75 implemented only `POST /internal/security/user-admissions`.
Its code-only errors are 400 `INVALID_ADMISSION_REQUEST`, 401
`SERVICE_AUTHENTICATION_INVALID` / `USER_EVIDENCE_INVALID`, 403
`SERVICE_CALLER_FORBIDDEN`, and 503 `AUTHORITY_UNAVAILABLE`; success contains actor
ID and current roles. Redis/primary mismatch is unavailable before eligibility.

Develop advanced during finalization to `45bf80fa8ad8a4498b800648138a1af4a60f7839`
(merged PR #76). Its [enforcement contract](../architecture/ld05-enforcement.md)
and actual Gateway `UserAdmissionWebFilter` / Classroom `UserAdmissionFilter`
now establish code-only public `503 AUTHORITY_UNAVAILABLE`,
`401 UNAUTHENTICATED` for genuine USER evidence denial, and
`403 ACCESS_DENIED` for role denial. SERVICE credential/integration failures,
malformed admission responses and timeouts produce 503, with `Cache-Control: no-store`, no cookie clearing and no refresh-family invalidation. The exact
protected routes are POST `/classrooms` (TEACHER) and GET `/classrooms/{UUID}`
(STUDENT plus existing ownership). Assessment/Proctoring business routes remain
closed. Admission defaults disabled and requires separate runtime provisioning.

PR #76 explicitly preserves existing `/me`, refresh and Quiz behavior. `/me`
reloads durable eligibility/roles/cutoff; it is not the complete Redis/primary
admission protocol. No new `/me`/refresh unavailable code is inferred. Frontend
non-denial hydration failure remains verification unavailable; actual 401/403
remain denial. Protected 503/403 pass through without refresh/logout; the exact
PR #76 JSON envelopes now have fetch-transport regression coverage.

Dev1 integration request: provide an isolated, revision-identified PR #75/#76
Identity/Gateway/Classroom/Redis topology with authority clients/reconciliation
configured, then exercise browser outage/stale-restore/recovery and uncertain
refresh cookie rotation. The PR #76 document reports 253 backend tests including
six real-topology cases; this frontend agent inspected that evidence and mapping
code but did not independently execute those backend tests. Their browser/cookie
coverage must not be inferred. No backend implementation is changed by this PR.

## Verification evidence

All commands ran from `frontend/`, except Git checks. Linux Node24.21.0 and
pnpm10.15.1 were selected from the existing NVM installation. Existing dependencies
contained missing Linux native optional packages; `CI=true pnpm install
--frozen-lockfile` repaired the installation without changing the lockfile.

| Command                                                                                                          | Final result                         | What it proves                                                                                                                                                                                                                      |
| ---------------------------------------------------------------------------------------------------------------- | ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `pnpm test` on untouched handoff                                                                                 | PASS: 381 tests, 30 files            | Independently reproduced handoff baseline after frozen install.                                                                                                                                                                     |
| `pnpm exec vitest run features/auth/session features/auth/forms/login-verification.integration.test.tsx lib/api` | PASS: 141 tests, 10 files            | Focused session, client, transport and React regressions; mocked transports/fetch.                                                                                                                                                  |
| `pnpm test` on final source                                                                                      | PASS: 410 tests, 30 files            | All frontend regressions, including ApplicationShell and Quiz authoring; 29 additional takeover tests.                                                                                                                              |
| `pnpm typecheck`                                                                                                 | PASS                                 | Strict TypeScript.                                                                                                                                                                                                                  |
| `pnpm lint`                                                                                                      | PASS                                 | ESLint.                                                                                                                                                                                                                             |
| `pnpm format:check`                                                                                              | PASS                                 | Prettier.                                                                                                                                                                                                                           |
| `NEXT_PUBLIC_API_URL=http://localhost:8080 pnpm build`                                                           | PASS                                 | Production build of this branch.                                                                                                                                                                                                    |
| `pnpm test:e2e --config=.ld05-contract.config.ts --workers=4`                                                    | PASS: 55 tests                       | Routed API contract/browser regressions; real native Web Locks, keyboard Enter/Space recovery, responsive UI. Backend replies are intercepted.                                                                                      |
| `pnpm test:e2e:real --config=.ld05-real.config.ts --workers=1`                                                   | PASS: 2 tests                        | This branch's production frontend against existing local Gateway/Identity/Quiz/PostgreSQL/Redis/Mailpit for normal auth, rotating HttpOnly cookie, logout, teacher grant and immutable version history. No business-response mocks. |
| `pnpm test:e2e:real:executor` (initial takeover)                                                                 | UNAVAILABLE: exit1 before assertions | Initial fixed127.0.0.1:3000 conflict; see dedicated follow-up below.                                                                                                                                                                |
| `git diff --check`                                                                                               | PASS                                 | Patch whitespace.                                                                                                                                                                                                                   |

Browser isolation: existing IPv4 frontend/backend servers belong to another
checkout and were left running. Temporary Playwright overrides started **this
branch's built frontend** on `::1:3000`, kept the browser Origin
`http://localhost:3000` through a Chromium localhost resolver rule, and used
temporary plain TCP IPv6-to-IPv4 forwarding for Gateway 8080/Mailpit 8025. This
preserved the backend's configured exact browser Origin without changing backend
configuration. Temporary configs/forwarders were removed. The existing backend
runtime revision was not established or rebuilt in this frontend workstream.
Normal real-API success is not proof of PR #75/#76 activation, Gateway/downstream
admission, actual Redis outage/stale restore/recovery, or production transport.

The real-auth test had expected an intermediate protected-boundary heading after
logout while the established user menu deliberately navigates to `/login`
(`authenticated-user-menu.tsx:110`). It now waits for that navigation, verifies
login after reload, then opens `/app` and asserts the anonymous boundary. This
fixes a timing-dependent test assertion without changing product navigation.

Resolved verification failures: missing Linux native dependency; one TypeScript
annotation on the new optional refresh argument; formatting; Next's additional
route-announcer alert requiring a scoped test locator; real Chromium's unnamed
loading button; and one unrelated screenshot protocol capture failure at high
worker concurrency. The complete browser suite passed with four workers without
skips/retries. The real-auth navigation assertion was corrected and both complete
real tests then passed. The initial executor port blocker was subsequently resolved as documented below. After PR #76 sync,
the first full Vitest run concurrent with build/quality checks took 317s and
timed out one existing Quiz editor test. A standalone unchanged full rerun
passed all 410 tests in 3.43s; no timeout increase or product change was applied.

UX review: existing Corporate Trust typography/components/tokens retained;
375px recovery screenshot inspected with no horizontal overflow or clipped
controls. Named pending and failed-attempt status, warning alert, explicit loading
button name, native disabled state, keyboard Enter/Space activation and manual
success navigation are exercised in Chromium. Wider auth/shell/editor responsive
regressions pass in the full browser suite. No protected content is rendered while
verification is unavailable. No focus trap or automatic navigation loop was added.

## Finalization and next action

Handoff `6c1192631ba7042410e3cd9bf81ad2616022fbbc` was retained; focused takeover
commit `92fa632` was followed by normal merge `424fd21` of develop `123c8f5`.
Final reviewer fixes and evidence were committed as `624ef3e`. When develop
advanced to PR #76, a second conflict-free normal merge `718cd26` preserved its
71 backend/documentation files; there was no frontend overlap. The new public
response envelope tests and updated contract evidence are the only subsequent
focused changes. Full frontend quality checks were rerun after synchronization.
No force-push, reset, clean, shared-history rewrite or backend implementation edit
was performed. The frontend PR remains Draft for actual browser LD-05 outage/
recovery evidence and the real-executor short-TTL provisioning gate.

Leader/Dev2 next action: run the browser outage/stale-restore/recovery and refresh
rotation cases on the isolated, provisioned PR #75/#76 topology described above.
Human final review is still required before merge.

## Real-executor follow-up — 2026-10-12 (Asia/Bangkok)

Baseline: branch `fix/frontend-ld05-login-hydration`, clean HEAD
`f8d6944416f1791e72235d4c0c5c76a7465b685e`; develop remains
`45bf80fa8ad8a4498b800648138a1af4a60f7839`. This follow-up changes only frontend
test runner/configuration, its validation tests/documentation, and the existing
expiry assertion's diagnostic text. Production auth and backend are unchanged.

### Initial port, Origin and runtime evidence

These observations precede the interrupted turn; current state is recorded below.
`ss -ltnp` and `/proc` identify Next PID 15950 bound only to 127.0.0.1:3000,
working in `/home/hhtuann/Documents/Projects/Quizopia_2.0/frontend`.
Identity PID 16321 and Gateway PID 16551 run from that same other checkout on
8081/8080. Those worktree HEADs currently equal `45bf80f`, but both Java
processes started before the merge; worktree HEAD does not identify loaded
bytecode. Both `/actuator/info` responses are empty, so exact runtime revisions
remain unproven. No other process/worktree was stopped, restarted or edited.

Gateway and Identity explicitly have `GATEWAY_ALLOWED_ORIGINS=http://localhost:3000`.
Real Gateway preflight returns 200 plus exact allow-origin/credentials for that
Origin, and 403 without allow-origin for `http://localhost:3100`. Identity's
accepted refresh/logout contract independently requires exact trusted Origin.
Identity's non-secret process setting is
`IDENTITY_AUTHORIZATION_SERVER_USER_ACCESS_TOKEN_TTL=PT5M`; actual browser login
confirms JWT TTL 300s. Gateway/Identity health both report UP; Mailpit API reports
v1.27.8 and successfully delivers this test's verification email.

Shared validated settings retain port 3000/host 127.0.0.1 defaults and strictPort.
`LD05_REAL_EXECUTOR_PORT` accepts only decimal integers 1–65535, with no fallback;
using another port requires an already provisioned exact Origin on both isolated
backend components. `LD05_REAL_EXECUTOR_HOST` accepts only 127.0.0.1 or ::1.
The exercised safe resolution uses `::1:3000`, preserving browser Origin
`http://localhost:3000`. Chromium's host-resolver rule matches **only**
`localhost:3000`; real Gateway 8080/Mailpit 8025 retain normal resolution. No proxy,
response interception, cookie rewriting, or security relaxation is used. A
native Chromium probe confirmed IPv6 harness and IPv4 Gateway reachability.
Runner and Playwright import the same validation; child inherits the environment.
After failure, Vite closes and only the original IPv4 listener remains.

### Executed real-browser result and assertion limits

- `pnpm test:e2e:real:executor`: exit1 before tests, expected unchanged default
  port conflict. StrictPort does not choose another port.
- `LD05_REAL_EXECUTOR_HOST=::1 pnpm test:e2e:real:executor`: **1 test executed,
  1 failed**, actual services, no Playwright backend mocks. Registration 202,
  email-request 202, confirmation 204, browser `login-ready`, valid JWT timing and
  minimum TTL assertion passed. The unchanged maximum TTL assertion failed:
  actual 300s, required 4–10s (`PT5S` isolated Identity profile).
- The failure occurs before HttpOnly-cookie, expiry wait, protected401→200 retry,
  refresh count, cookie rotation, same-user, storage and Gateway-only assertions.
  Those assertions **did not execute** in this run; no expiry/retry pass is claimed.
- The existing test starts one protected request. A single refresh count for one
  caller does not prove concurrent single-flight; same-user-before/after does not
  prove account-switch isolation, and no explicit logout-event count exists.
  Those broader guarantees have mocked unit/lifecycle regressions in the existing
  410-test baseline; this real suite must not be described as proving them.
  Harness code imports the production service/executor, but its protected
  executor action was not reached because of the backend TTL precondition.

Required backend provisioning: an isolated Gateway/Identity/PostgreSQL/Redis/
Mailpit topology at the suite's existing endpoints, with Identity user TTL PT5S
and exact localhost:3000 trusted Origin. Do not alter the other developer's
running five-minute instance or weaken timing assertions. This integration
blocker is separate from LD-05 Redis outage/stale-restore/recovery and uncertain
rotation verification, which remain pending.

### Current environment after interruption

After the user response, the original Next/Gateway/Identity listeners were absent
on 3000/8080/8081; this agent did not stop or reconfigure them. Infrastructure
containers remain running and Mailpit still listens on 8025, but its current HTTP
probe timed out after two seconds; current SMTP/API readiness is not established. Temporary `/tmp`
logs from before the interruption are no longer present; earlier command results
are retained in this session's tool history and the historical evidence above.

Both fresh commands `pnpm test:e2e:real:executor` and
`LD05_REAL_EXECUTOR_HOST=::1 pnpm test:e2e:real:executor` start the harness and
execute one test, which fails at its first real Gateway registration request
with `ECONNREFUSED ::1:8080`. There is no 8080 listener on either loopback family.
No auth/cookie/expiry assertions execute in these current runs. The unchanged
default now binds successfully because its earlier conflicting listener is gone.
This does not turn the earlier 300s TTL failure into a pass. No isolated runtime
endpoints were supplied in the user's response; shared services remain untouched.

### Follow-up quality and review

- `pnpm test`: PASS 425 tests/31 files, preserving all original 410 plus 15 runner
  validation cases. The first pre-interruption run had four existing tests time
  out under host memory pressure (swap full); no assertions/timeouts changed.
  A bounded-worker rerun was interrupted and no result is claimed for it.
  The fresh exact-command full run passed in 3.53s.
- `pnpm typecheck`, `pnpm lint`, `pnpm format:check`: PASS on current delta.
- `pnpm build`: PASS production build.
- Real-executor outcomes are the actual failures described above, not skips or
  successful expiry/retry verification. Existing 55 mocked browser and 2 normal
  real-browser journeys are prior takeover evidence, not rerun in this follow-up.
- `git diff --check`: PASS during finalization.

New validation cases cover defaults, a shared explicit port, IPv6 isolation,
invalid ports and disallowed bind hosts. During development, Playwright initially
discovered the new Vitest file in its test directory; the unit test now lives
outside that directory, and actual browser execution confirms discovery is fixed.
Focused independent read-only reviewer `/root/executor_delta_review` found no
confirmed correctness/security or scope defects and independently passed 15/15
runner tests plus `git diff --check`. Its documentation check required the
historical/current runtime distinction now recorded above. Keep PR #77 Draft and
unmerged; actual backend integration evidence remains blocked.
