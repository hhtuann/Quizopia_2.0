# Frontend LD-05 takeover review

Audit started: 2026-10-11; verification/finalization: 2026-10-12 (Asia/Bangkok).
Owner: Dev 2 implementation agent. Verdict: **BLOCKED — BACKEND CONTRACT**.
Frontend correctness/quality and independent agent review pass; public LD-05
contracts and outage integration remain open. Prepare a Draft PR for Leader review.

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
all PR #75 changes from develop.

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
establish public backend contract compatibility or actual Redis-outage behavior.

G1 evidence includes success/invalid/unavailable completions across login and
bootstrap phases, overlapping accounts, local clear/logout, superseded bootstrap,
stale Teacher enablement and protected payload/replay fencing. G2 covers both
Web Locks and fallback queue, non-cooperating transports, ten-second fake timers,
actual fetch signals, recovery after timeout, and no automatic rotation replay.
No token/cookie/password/OTP logging or JavaScript credential persistence was added.
Only `/me` supplies roles; the candidate never enters the business token vault.
Outage alone sends no logout or refresh-family invalidation.

## Backend contract request for Dev 1

PR #75 implements only `POST /internal/security/user-admissions`. Accepted internal
errors use a code-only envelope: 400 `INVALID_ADMISSION_REQUEST`, 401
`SERVICE_AUTHENTICATION_INVALID` / `USER_EVIDENCE_INVALID`, 403
`SERVICE_CALLER_FORBIDDEN`, 503 `AUTHORITY_UNAVAILABLE`; success contains actor ID
and current roles. Redis/primary mismatch is unavailable before eligibility checks.
Gateway/downstream admission adapters are explicitly outstanding and capability is
disabled by default. Existing `/me` reloads durable eligibility/roles/cutoff; it is
not the new complete Redis/primary admission protocol.

Required handoff: freeze browser status/envelope/code and cookie side effects for
Gateway/downstream authority unavailable, SERVICE auth failure and timeout; document
`/me` and refresh outage behavior separately from genuine USER denial; enumerate
protected routes and supply real Redis outage/stale-restore/recovery evidence. The
frontend currently passes protected 503 through without refresh/logout and treats
non-denial hydration failures as verification unavailable. Do not map upstream
SERVICE 401/403 to invalid browser credentials. No backend source changes belong
to this frontend PR.

## Verification evidence

All commands ran from `frontend/`, except Git checks. Linux Node24.21.0 and
pnpm10.15.1 were selected from the existing NVM installation. Existing dependencies
contained missing Linux native optional packages; `CI=true pnpm install
--frozen-lockfile` repaired the installation without changing the lockfile.

| Command                                                                                                          | Final result                         | What it proves                                                                                                                                                                                                                      |
| ---------------------------------------------------------------------------------------------------------------- | ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `pnpm test` on untouched handoff                                                                                 | PASS: 381 tests, 30 files            | Independently reproduced handoff baseline after frozen install.                                                                                                                                                                     |
| `pnpm exec vitest run features/auth/session features/auth/forms/login-verification.integration.test.tsx lib/api` | PASS: 139 tests, 10 files            | Focused session, client, transport and React regressions; mocked transports/fetch.                                                                                                                                                  |
| `pnpm test` on final source                                                                                      | PASS: 408 tests, 30 files            | All frontend regressions, including ApplicationShell and Quiz authoring; 27 additional takeover tests.                                                                                                                              |
| `pnpm typecheck`                                                                                                 | PASS                                 | Strict TypeScript.                                                                                                                                                                                                                  |
| `pnpm lint`                                                                                                      | PASS                                 | ESLint.                                                                                                                                                                                                                             |
| `pnpm format:check`                                                                                              | PASS                                 | Prettier.                                                                                                                                                                                                                           |
| `NEXT_PUBLIC_API_URL=http://localhost:8080 pnpm build`                                                           | PASS                                 | Production build of this branch.                                                                                                                                                                                                    |
| `pnpm test:e2e --config=.ld05-contract.config.ts --workers=4`                                                    | PASS: 55 tests                       | Routed API contract/browser regressions; real native Web Locks, keyboard Enter/Space recovery, responsive UI. Backend replies are intercepted.                                                                                      |
| `pnpm test:e2e:real --config=.ld05-real.config.ts --workers=1`                                                   | PASS: 2 tests                        | This branch's production frontend against existing local Gateway/Identity/Quiz/PostgreSQL/Redis/Mailpit for normal auth, rotating HttpOnly cookie, logout, teacher grant and immutable version history. No business-response mocks. |
| `pnpm test:e2e:real:executor`                                                                                    | UNAVAILABLE: exit1 before assertions | Fixed127.0.0.1:3000 is occupied by another checkout; runner reports port3000 already in use. No executor test passed or ran.                                                                                                        |
| `git diff --check`                                                                                               | PASS                                 | Patch whitespace.                                                                                                                                                                                                                   |

Browser isolation: existing IPv4 frontend/backend servers belong to another
checkout and were left running. Temporary Playwright overrides started **this
branch's built frontend** on `::1:3000`, kept the browser Origin
`http://localhost:3000` through a Chromium localhost resolver rule, and used
temporary plain TCP IPv6-to-IPv4 forwarding for Gateway8080/Mailpit8025. This
preserved the backend's configured exact browser Origin without changing backend
configuration. Temporary configs/forwarders were removed. The existing backend
runtime revision was not established or rebuilt in this frontend workstream.
Normal real-API success is not proof of PR #75 activation, Gateway/downstream
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
real tests then passed. The executor port blocker remains explicitly unavailable.

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
Final reviewer fixes and this evidence are committed normally on the same feature
branch. No force-push, reset, clean, shared-history rewrite or backend modification
was performed. The frontend PR must remain Draft until the public response mapping
and actual LD-05 outage/recovery integration gates have evidence.

Leader/Dev2 next action: have Dev1 supply the public Gateway/downstream
status/envelope/cookie contract and route-enforcement handoff described above;
then verify real Redis outage, stale restore and authoritative recovery before
removing Draft status. Human final review is still required before merge.
