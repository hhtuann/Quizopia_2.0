# Frontend LD-05 takeover review

Date: 2026-10-11. Owner: Dev 2 implementation agent. Status: verification in progress.

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
