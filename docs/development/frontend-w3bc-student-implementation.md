# W3-B/W3-C Student Assessment frontend

Evidence checkpoint: 2026-10-16 (Asia/Bangkok). Branch:
`feature/frontend-w3bc-student`.

## Source and verdict boundary

Implementation started from and remains based on develop
`e5fbf50fda2389dcd72499ef4979df0217ef8f30`. At the final pre-commit evidence
checkpoint, local `HEAD`, `origin/develop`, and their merge-base were that exact
revision. Final feature and evidence commit IDs are recorded in the Draft PR and
the delivery report because a commit cannot contain its own object ID.

The bounded frontend implementation and intercepted-browser verification are
complete. Real-service integration is **BLOCKED**: no agent-owned,
revision-identified Gateway + Identity + Classroom + Assessment topology with
explicitly authorized synthetic fixture flags was available. No shared service,
database, container, or port was started, stopped, or modified. This document does
not treat intercepted Playwright responses as real backend evidence and does not
authorize production activation.

## Implemented UI and routes

- `/app/assessments/join`: authenticated Student-only manual PUBLIC locator entry
  and known CLASS Publication UUID entry, authoritative preview, and explicit
  start/resume. No unsupported browse or shareable-locator URL was added.
- `/app/attempts/[attemptId]`: authoritative Attempt load, stable paged question
  navigation, all four question types, answered progress, server-relative
  countdown, serialized autosave, conflict/uncertainty recovery, Submit, Abandon,
  and explicit Retake.
- `/app/attempts/[attemptId]/result`: exact server-issued result and aggregate score;
  it does not calculate, round, or infer a grade.
- `/app/attempts/[attemptId]/review`: explicit, separately authorized review load.
  Correct answers and explanations render only after a successful Review response.
- Learning-workspace Assessment navigation and home entry were added without
  changing Teacher Publication behavior.

The read-only monitoring composition boundary in
`features/attempts/model/monitoring-boundary.ts` exposes only eligible opt-in state,
authorized Attempt ID/status, question selection, and lifecycle observations. It
imports no future proctoring adapter and creates no monitoring endpoint or success
state.

## Implemented Gateway operations

All calls use the canonical authenticated request executor and public Gateway
target. Runtime responses are validated against OpenAPI-derived types plus bounded
Zod invariants.

- `POST /api/assessments/publications/entry`
- `POST /api/assessments/publications/{publicationId}/entry`
- `POST /api/assessments/publications/{publicationId}/attempts/start`
- `POST /api/assessments/publications/{publicationId}/attempts/retake`
- `GET /api/assessments/attempts/{attemptId}`
- `POST /api/assessments/attempts/{attemptId}/questions/{questionNumber}/answer`
- `POST /api/assessments/attempts/{attemptId}/submit`
- `POST /api/assessments/attempts/{attemptId}/abandon`
- `GET /api/assessments/attempts/{attemptId}/result`
- `GET /api/assessments/publications/{publicationId}/aggregate-score`
- `GET /api/assessments/attempts/{attemptId}/review`

The implemented backend also emits `CLOSURE_CUTOFF_REACHED` and
`CLOSURE_PENDING`, while the current `StudentError` OpenAPI enum omits those codes.
The frontend accepts bounded uppercase error codes and provides safe messages for
the actual controller/service behavior. It does not relax successful response
validation.

## Attempt, revision, and time correctness

- Attempt revisions and ordinals remain canonical decimal strings through parsing,
  display, mutation payloads, reconciliation, and tests. Values above signed
  64-bit maximum are rejected; no JavaScript number conversion occurs.
- Every new mutation intent receives one `[A-Za-z0-9_-]{16,128}` key. Unknown
  outcomes retain the exact key and immutable payload in memory. Exact retry is
  available; a changed operation never reuses the key.
- Saves are serialized per Attempt. An intent reads the latest acknowledged
  revision only when it reaches the queue. Later typing cannot be overwritten by
  an older response.
- A stale save refreshes authoritative state, merges remote answers only into
  locally untouched saved drafts, and preserves the genuine local conflict for a
  deliberate save.
- A newer edit made while an earlier save is uncertain remains visible and
  actionable after exact recovery. Other saves and terminal actions remain fenced
  until uncertainty is resolved.
- Submit and Abandon first flush pending saves and consume the coordinator's atomic
  post-flush Attempt/draft snapshot. Terminal revision conflicts refresh the new
  revision while preserving all local answers and require deliberate resolution
  and retry.
- Submit sends exactly one typed entry for every question, including explicit
  unanswered values. The complete UTF-8 JSON body is checked against the 2 MiB
  contract limit and is never truncated.
- An uncertain Retake cannot be inferred from the old terminal Attempt; only exact
  retained-key replay can select/return the created Attempt.
- The countdown derives from `serverNow` and the earliest of `deadlineAt` and a
  closure cutoff. Local expiry, browser reconnect, and return to a visible tab
  pause mutations until an authoritative GET succeeds.
- The 12,613-question compatibility fixture uses valid NUMERIC_FILL questions and
  answers. Runtime validation retains the full collection while navigation renders
  at most 100 question controls per page.

## Authentication and confidentiality

- API clients bind to the canonical SessionRuntime generation. Each request checks
  generation before dispatch and after response; route content is keyed by user ID,
  generation, and resource ID.
- Join state remounts on identity/generation change. Ephemeral locator entries are
  identity-tagged, synchronously unreadable by a new identity, and cleared after a
  switch. Locators appear only in the authorized request body and are never placed
  in URLs, logs, analytics, localStorage, sessionStorage, or IndexedDB.
- Obsolete requests are aborted or fenced on unmount/account switch. A dependency
  503 stays a feature error and does not initiate feature logout or refresh.
- Attempt/result/review IDs do not confer authority. Masked 404 and authorization
  messages do not claim client-side ownership, membership, or admission checks.
- ACTIVE payload schemas contain no grading keys. Result loading never calls Review;
  Review is an explicit route and correct-answer data renders only from its
  authorized successful response.
- Unsaved work activates full-unload, in-app link, and Chromium Navigation API
  history-traversal warnings.

## Verification evidence

Commands were run from `frontend/` unless marked root.

| Command | Result | Evidence boundary |
| --- | --- | --- |
| `pnpm install --frozen-lockfile` | **PASS** | Lockfile installation; ignored optional build-script warning only |
| `pnpm test` | **PASS: 594 tests / 41 files** | Unit/component/mocked transport regression |
| `pnpm typecheck` | **PASS** | Strict TypeScript |
| `pnpm lint` | **PASS** | ESLint including React hooks/refs rules |
| `pnpm format:check` | **PASS** | Prettier |
| `pnpm build` | **PASS** | Next.js 16 production build, all new routes emitted |
| `pnpm test:e2e --workers=4` | **PASS: 64 Chromium tests, 0 skipped** | Intercepted Gateway responses; not real backend |
| Root: `python3 scripts/local/generate-attempt-types.py --check` | **PASS** | Generated type parity with implemented OpenAPI SHA-256 |
| Root: `git diff --check` | **PASS** | Whitespace integrity |

New coverage includes PUBLIC confidentiality, CLASS known-ID entry, exact start,
all answer types and empty forms, numeric grammar, signed-64-bit strings,
serialized/newer typing, stale and unknown saves, terminal post-flush revision,
terminal conflict retry, account-scoped entry reset, dependency 503 isolation,
explicit result/review separation, mobile/keyboard behavior, reconnect fencing,
history warning, stale-GET monotonicity, authority-paused mutation fencing,
uncertain-intent leave protection, and the 12,613-question bounded-navigation
fixture.

## Independent review

An independent read-only reviewer inspected disclosure, ownership, session
switching, locator handling, revision/idempotency races, numeric precision,
deadlines, result/review confidentiality, navigation, and large-Attempt behavior.
The first pass found no Blocker and identified two High and six Medium issues. The
implementation fixed every confirmed item: identity-scoped join/locator state,
authoritative answer merging, newer drafts after uncertainty, Retake recovery,
terminal revision recovery, reconnect fencing, history warning, and bounded large
navigation. A separate final reviewer then found one High and two Medium recovery
gaps: in-flight mutation leave fencing, definitive exact-retry cleanup, and expiry
reconciliation recovery. All three were fixed and regression-tested. Final
read-only re-review found **no remaining Blocker, High, or Medium findings** and
passed all 52 focused Attempt tests without editing files. Broad suites were rerun
after the fixes.

## Deferred ownership and release gates

Not implemented here: Teacher Closure, Student Inbox, Teacher Results, proctoring
activity evidence, LiveKit/camera handling, a proctoring adapter, backend production
changes, class browsing, or an Attempt inventory endpoint.

Remaining release gates:

1. Provision an isolated topology from exact revisions with authorized synthetic
   fixture flags, unique ports/databases/containers, and no shared developer state.
2. Run real HTTP/Chromium PUBLIC and CLASS admission, start/resume/retake, saves,
   revision/unknown-outcome recovery, submit/abandon, result, durable-CLOSED review,
   account switching, and dependency failure scenarios through Gateway.
3. Record revision IDs, fixture flags, service identities, sanitized evidence, and
   owned cleanup verification.
4. Obtain Leader review of the Draft PR and separately owned cross-workstream and
   release approval. Do not merge or enable production flags from this branch.
