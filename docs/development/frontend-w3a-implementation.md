# W3-A Teacher Publication frontend

Implementation, real-acceptance and develop-merge checkpoints: 2026-10-16
(Asia/Bangkok). Branch: `feature/frontend-w3a-publication`.
The original implementation used develop `4744f81625632fd6e2c24d75017597c0abcbf711`.
After PR #77 merged, this branch normally merged develop
`532fe986ca2a49a179b9740dc5546772618b81f0`; current merged-source evidence is
recorded in the PR #77 follow-up section below. Historical checkpoints retain their
original source revisions and do not substitute for current-source verification.

## Scope and current gate

The bounded Teacher workflow is implemented: pinned published QuizVersion entry,
create/review DRAFT, owner detail, DRAFT update, CLASS/PUBLIC configuration,
explicit monitoring choice, OPEN, OPEN schedule extension and direct CLOSE.
Implementation, frontend quality gates and independent review are complete.
The feature branch is published in [Draft PR #92](https://github.com/hhtuann/Quizopia_2.0/pull/92)
targeting develop; evidence is below. The PR remains unmerged.

**Bounded real-topology acceptance: PASS, including the merged frontend.** Three actual Chromium scenarios passed
against an isolated, revision-pinned five-service topology. PUBLIC and CLASS owner
journeys, real error responses, account isolation and a committed creation whose
response was withheld were exercised without response interception. The original
missing-topology blocker is resolved for this fixture. PR #92 remains Draft for
Leader review; this checkpoint does not complete Wave 3, enable monitoring or
authorize production activation. Separate LD-05 outage/recovery and deployment
security gates remain outstanding. The fresh merged-source verification checkpoint
is recorded in the PR #77 follow-up below.

## Plan and progress

This document is the bounded implementation plan and completion mapping; there
are no separate phase files or task records. The original frontend implementation,
bounded real-topology acceptance, independent review and publication are complete.
Leader acceptance and independent platform/release gates remain separate.

- [x] Inspect the latest develop baseline, accepted contracts and existing frontend;
      preserve the separate LD-05 branch and PR #77.
- [x] Implement generated Publication types, runtime validation, exact UTC helpers
      and the Gateway API client with bounded requests and session fencing.
- [x] Deliver pinned QuizVersion selection, reviewed create/edit DRAFT, CLASS/PUBLIC
      configuration, explicit monitoring choice and authoritative owner detail.
- [x] Deliver confirmed OPEN, revision-checked schedule extension and direct CLOSE
      with safe idempotency, conflict and uncertain-outcome recovery.
- [x] Verify locator privacy, Teacher gating, stale-account isolation, responsive
      presentation, keyboard dialogs and screen-reader-readable feedback.
- [x] Pass focused tests before/after full verification: 107 tests in four files;
      preserve the 327-test baseline in the passing 434-test, 31-file full suite.
- [x] Pass TypeScript, ESLint, Prettier, production build, generated-contract parity
      and diff checks; pass 57 intercepted-browser tests with zero skipped.
- [x] Complete independent read-only review, fix confirmed findings and obtain
      re-review PASS; document unsupported capabilities and integration handoff.
- [x] Commit scoped changes and publish normally by SSH: feature commit
      `c7feb20c981004b1fcf9769aa77a520bd9ce507e`, initial evidence commit
      `914bc5d7198d0875689a186e7faaea9d295a2eaf`, and
      [Draft PR #92](https://github.com/hhtuann/Quizopia_2.0/pull/92) targeting develop.
      Before this evidence update, the branch was clean and matched its remote;
      develop remained `4744f81625632fd6e2c24d75017597c0abcbf711`.
- [x] Provision an isolated, revision-identified five-service topology and execute
      three real Chromium scenarios covering PUBLIC/CLASS lifecycle, authoritative
      errors, account isolation and committed-response-loss recovery.
- [x] Reverify all frontend gates: 434 unit/component tests, 57 intercepted-browser
      tests, TypeScript, ESLint, Prettier, production build, generated-contract
      parity and diff checks; pass eight isolated-runtime helper regressions.
- [x] Obtain focused independent read-only PASS for the new acceptance source delta;
      no frontend product change or backend production modification was required.
- [x] Complete final independent source/evidence review (PASS), commit scoped
      acceptance tooling as `8ce81accd6bdec0b5d2d0a683a271e7ed02342d5` and evidence
      as `d648e5a4a1cffc1ec3e2eca6d8f4717f65795f1f`, push normally by SSH, and
      update existing PR #92 with exact results. Local/remote matched after push;
      PR #92 remains OPEN, Draft and unmerged.

Current PR #77 integration follow-up:

- [x] Inspect clean W3-A HEAD `85455ba` and latest develop `532fe986`; normally merge
      both histories as `40f77b5` without rebasing or overwriting unrelated work.
- [x] Resolve the README conflict semantically, retain all LD-05 security behavior,
      and expose only the canonical runtime session-generation getter to W3-A.
- [x] Add and pass ten deterministic auth/Publication composition regressions for
      stale accounts, authority failure, refresh coordination, logout and switching.
- [x] Pass current merged-source quality gates: 542 tests in 36 files, TypeScript,
      ESLint, Prettier, production build, 59 intercepted-browser tests without skips,
      generated contract parity, eight fixture-helper tests and diff checks.
- [x] Reproduce the isolated five-service fixture and pass all three real Chromium
      scenarios against exact merged frontend `40f77b5`; verify owned cleanup and
      preserve shared processes and containers.
- [x] Obtain independent read-only conflict/composition review PASS with no confirmed
      Blocker/High/Medium findings; retain separate historical and current evidence.
- [x] Complete final independent source/evidence review (PASS), publish normal merge
      `40f77b5` and evidence `3e582494c9c338e409932e10da8f83619252e94b` by normal
      SSH push, and update existing PR #92. Local/remote matched and GitHub reported
      mergeable against develop `532fe986`; the PR remains OPEN, Draft and unmerged.
      Final-head CI is tracked live on PR #92 and reported in the final handoff;
      publication does not imply that pending checks have passed.
- [ ] Obtain Leader acceptance of PR #92 and complete separately owned release
      gates; production activation remains unauthorized.

## PR #77 develop merge follow-up — 2026-10-16

### Git provenance and semantic resolution

The clean W3-A branch started at `85455ba533d2a405218d77a19b23531fc773dbfa`.
A normal merge integrated develop `532fe986ca2a49a179b9740dc5546772618b81f0`,
which contains merged PR #77. Merge commit
`40f77b5b9a7aff28d7ce81b50bffce3b3311d07a` retains both parent histories;
no rebase, force push, reset or cherry-pick was used.

The only textual merge conflict was `frontend/README.md`. Its resolution retains
both the W3-A workflow/integration instructions and LD-05 real-executor guidance.
The authentication files merged textually, but received explicit semantic review:

- Removed the W3-A `AuthSessionService.getSessionGeneration` declaration and
  implementation. `SessionRuntime.getSessionGeneration` is the single canonical
  API; AuthProvider exposes that exact runtime function to Publication consumers.
- Removed obsolete service-getter fields from ApplicationShell and Quiz authoring
  test fakes. Publication's existing context hook and response-generation checks
  continue to use the runtime generation.
- Retained the LD-05 distinction between operation generation and session
  generation, unavailable-verification state, bounded refresh, cancellation, lock
  release and uncertainty latch. No fallback authorization or automatic refresh
  replay was added to satisfy Publication tests.

Independent read-only review confirmed that the merged auth-session service,
runtime, token vault, refresh coordinator, browser lock, authenticated executor,
auth API/transport and LD-05 recovery components match `origin/develop` byte for
byte. The provider's feature delta only exposes the canonical runtime getter.
No backend production file, dependency or lockfile changed in this integration.

### New composition regression evidence

`frontend/features/assessments/api/publication-session.integration.test.tsx` adds
10 deterministic tests composing actual AuthProvider, session runtime, auth service,
refresh coordinator, Publication hook/client and authenticated executor with mocked
HTTP transports. They establish:

- Late owner success and late 401 after logout/account switch cannot populate or
  clear the newer identity, and an old client cannot dispatch another operation.
- Local clear invalidates an in-flight Publication response without cookie logout.
- Authority-unavailable login stays unverified and cannot issue a protected
  Publication request until successful authoritative manual verification.
- Protected authority 503 preserves verified identity without refresh or logout.
- Concurrent Publication 401s single-flight refresh, preserve session/workspace and
  replay the exact original create command.
- Refresh 503 latches failure until a new session; a stalled refresh is bounded,
  ignores late credentials, releases logout and permits subsequent login.
- An authoritative refresh that changes account invalidates the old Publication
  client; an injected runtime exposes the same canonical generation without
  inventing an authenticated executor.

These are unit/component composition tests with mocked transports, not real-service
or Redis-outage evidence. Independent review reported PASS, with no confirmed
Blocker/High/Medium findings, and independently passed 95 focused tests in four
files using:

```sh
pnpm exec vitest run features/assessments/api/publication-session.integration.test.tsx features/auth/session/auth-lifecycle.test.ts features/auth/session/login-verification.test.ts lib/api/authenticated-request.test.ts
```

The final source/evidence review also passed without confirmed Blocker/High/Medium
findings. The delegated broad focused command
`pnpm exec vitest run features/auth features/assessments lib/api/authenticated-request.test.ts`
passed 303 tests in 20 files. The parent independently reran
`pnpm exec vitest run features/assessments/api/publication-session.integration.test.tsx`:
10 tests passed.

### Fresh merged-source quality gates

All frontend commands below ran from `frontend/` against merge
`40f77b5b9a7aff28d7ce81b50bffce3b3311d07a` source; root commands are marked.

| Exact command                                                                                                        | Result                              | Evidence boundary                                         |
| -------------------------------------------------------------------------------------------------------------------- | ----------------------------------- | --------------------------------------------------------- |
| `pnpm test`                                                                                                          | **PASS 542 tests / 36 files**       | Unit/component and mocked transport regression            |
| `pnpm typecheck`                                                                                                     | **PASS**                            | TypeScript                                                |
| `pnpm lint`                                                                                                          | **PASS**                            | ESLint                                                    |
| `pnpm format:check`                                                                                                  | **PASS**                            | Frontend Prettier                                         |
| `pnpm build`                                                                                                         | **PASS**                            | Production Next.js build                                  |
| `pnpm test:e2e --workers=4`                                                                                          | **PASS 59 tests, 0 skipped**, 29.9s | Actual Chromium with intercepted responses                |
| Root: `python3 scripts/local/generate-publication-types.py --check`                                                  | **PASS**                            | Generated Publication contract parity                     |
| Root: `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/local/tests -p test_w3a_real_runtime.py -v` | **PASS 8 tests**                    | Isolated-runtime helper regressions with mocked OS/Docker |
| Root: `git diff --check`                                                                                             | **PASS**                            | Whitespace check                                          |

### Fresh merged-source real acceptance

The isolated fixture was safely reprovisioned and all three existing actual
Chromium scenarios passed with zero retries against the exact archived frontend
`40f77b5b9a7aff28d7ce81b50bffce3b3311d07a`. The actual backend services were built
from `4744f81625632fd6e2c24d75017597c0abcbf711`; comparing `gateway/` and
`services/` between that revision and merged develop `532fe986` showed no changes.
This establishes the current merged frontend against the unchanged backend source
subset, not mixed revisions or a production deployment. No responses were replaced
with Playwright interception and no acceptance assertion was weakened.

The command from `frontend/` was:

```sh
W3A_REAL_GATEWAY_URL=http://localhost:8080 \
W3A_REAL_MAILPIT_URL=http://127.0.0.1:44489 \
W3A_REAL_STATE_DIRECTORY=/tmp/quizopia-w3a-v_e8hovu \
  pnpm exec playwright test --config=playwright.w3a-real.config.ts
```

The source archive used the existing webpack Next.js fixture at
`http://localhost:3000`; business HTTP traversed actual Gateway. Actual backend
endpoints were Identity `http://127.0.0.1:46983`, Quiz
`http://127.0.0.1:35473`, Classroom `http://127.0.0.1:60997`, Assessment
`http://127.0.0.1:48955`, Gateway `http://127.0.0.1:8080` and Mailpit
`http://127.0.0.1:44489`. Fresh JAR identities were:

| Service    | JAR SHA-256                                                        |
| ---------- | ------------------------------------------------------------------ |
| Identity   | `0bcbe0019e42fb99328af698c5e374c5a346d544151a6701ed39de416c71f151` |
| Quiz       | `8639cdb32ae09464530ac6a2beaee02a0ae92b98896dee318b7927ec034ebbda` |
| Classroom  | `03dad1b6dce8d10c85c2c7b341d9acda6b49f251358fb8427054774119cd4f7a` |
| Assessment | `60af5d751a787234dbc18a51714349026955209ca02f0664dc911d45468df9c5` |
| Gateway    | `10cebf031e8f09ddd60d1cbb3be9e738180d485c5d3dff726547e751f1aa6029` |

The sanitized log `/tmp/w3a-merged-real-chromium.log` and sanitized metadata
`/tmp/w3a-merged-real-evidence.json` record **PASS 3, FAIL 0**. The three cases
re-executed actual PUBLIC create/replay/edit/conflicts/dependency-503/OPEN/extension/
CLOSE; CLASS authority/membership/explicit monitoring/Student denial/ACTIVE CLOSE;
and committed creation with response loss and exact original-key recovery. Actual
same-document logout/account switch canceled the stalled old owner GET and retained
foreign-owner denial. HTTP evidence included authority readiness 503 before bounded
read-only recovery, no refresh/logout after dependency 503, and no automatic replay
when a committed create exceeded the 15-second deadline. HttpOnly cookie, USER-only
bearer and observed locator/token confidentiality assertions also passed. Actual
Origin probes returned 200 for `http://localhost:3000` and 403 for
`http://localhost:3100`.

Cleanup completed through the owned fixture helper plus an independent absence
check at `2026-10-16T07:18:29Z` (14:18:29 Asia/Bangkok). All five backend PIDs,
six owned containers and three archived-frontend PIDs were absent; all seven
recorded service/frontend ports were closed. The original six shared containers
were unchanged. The helper initially marked four conservative Java-exit checks
pending; the subsequent PID/port/container inspection resolved those checks rather
than inferring success from the helper alone. The private state directory retains
`cleanup-verification.json` for local inspection; no credentials are committed.
The fresh fixture retained USER TTL PT5M: the PT5S real-executor suite and separate
Redis outage/stale-restore/recovery scenarios were not run or claimed here.

Final independent source/evidence review passed with no confirmed
Blocker/High/Medium findings. Merge `40f77b5` and evidence commit
`3e582494c9c338e409932e10da8f83619252e94b` were normally pushed to the existing
branch, and PR #92's description was updated. The first published checkpoint
matched its remote and was mergeable against develop `532fe986`; Repository
validation passed and Frontend CI was running. The final PR-head checks are live
in GitHub Actions and are reported in the final handoff; this checkpoint does not
assert their completion. PR #92 remains Draft and unmerged.

The earlier real acceptance below remains historical evidence for frontend
`fbfc46cbf8cf892083e758d0ac76f30588526942`; it is not counted as the fresh run.

## Contract audit

Assessment owns Publication state. All browser requests use the existing Gateway
base URL, HTTP transport and authenticated request executor. Gateway and Assessment
independently enforce current TEACHER authority and ownership. Teaching workspace
selection is navigation preference, never a role grant.

Sources: [implemented OpenAPI](../../services/assessment-service/contracts/publications.openapi.yaml),
[ASSESS-15](../specifications/assess-15-publication-contract.md),
[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md),
[W3-B/C HTTP contract](../specifications/w3b-w3c-http-contract.md),
[closure contract](../specifications/w3-b-closure-notification-contract.md),
and actual Publication/Closure controllers and Gateway route inventories.
Older W3-A checkpoints deferring CLOSE are superseded by merged B3 implementation.
The OpenAPI Error enum omits runtime `ACTIVE_ATTEMPTS`, `REVISION_CONFLICT` and
`CLOSURE_PENDING`; accepted closure/HTTP contracts cover them. The client validates
the sanitized code-only envelope and provides a safe generic unknown-code message.

All paths below are relative to `/api/assessments/publications`.

| Operation    | Method/path            | Exact command and successful response                                        |
| ------------ | ---------------------- | ---------------------------------------------------------------------------- |
| Create       | `POST /`               | Complete `Configuration`, `Idempotency-Key`; **201** `OwnerView`             |
| Detail       | `GET /{id}`            | No body; **200** `OwnerView`                                                 |
| Edit DRAFT   | `PUT /{id}`            | `{expectedRevision, configuration}`; **200** `OwnerView`                     |
| OPEN         | `POST /{id}/open`      | `{expectedRevision}`; **200** `OwnerView`                                    |
| Extend OPEN  | `PATCH /{id}/schedule` | `{expectedRevision, endAt, durationMinutes}`; **200** `OwnerView`            |
| Direct CLOSE | `POST /{id}/close`     | `{expectedRevision}`, `Idempotency-Key`; **200** minimal `ClosedPublication` |

Create path is exactly `/api/assessments/publications` without an appended slash.
CLOSE does not return full OwnerView. The client validates its acknowledgement,
retains already verified owner configuration and applies only returned state/revision.
Student DTOs are separate generated types and are not used to expose owner metadata.
Unsafe integer revisions, resource-ID mismatches, malformed shapes and wrong success
statuses fail closed. Requests have a 15-second feature deadline with AbortSignal
propagation; late completions are fenced independently of cancellation.

The generated TypeScript file includes the OpenAPI SHA-256. From repository root:

```sh
python3 scripts/local/generate-publication-types.py
python3 scripts/local/generate-publication-types.py --check
```

The bounded generator uses PyYAML, already used by repository tooling. It generates
contract types; Zod validates runtime responses. No frontend dependency was added.

## Delivered experience

`/app/assessments/new` is an honest entry page with instructions and a known-ID
lookup, not a Publication directory. Existing owned-Quiz published-version history
links into creation with the exact `quizId`, `quizVersionId`, `versionNumber` triple.
The selected source is fetched and verified again; no mutable draft or newer version
is substituted. Existing unsaved Quiz Draft navigation confirmation is preserved.
`/app/assessments/[publicationId]` renders owner-authorized details and lifecycle actions.

Feature modules under `frontend/features/assessments/` contain the API client,
generated contracts, Zod model, exact UTC helpers, client hook, create/detail screens,
configuration form, summaries, confirmation dialog, schedule extension and private
locator control. Existing ApplicationShell, buttons, alerts, fields and surfaces are
reused with Plus Jakarta Sans, Calistoga branding and JetBrains Mono identifiers.

CLASS accepts exactly one canonical Classroom ID because no authorized selection
directory exists. The server confirms assignment authority. `UNCONFIGURED` remains
available for an unfinished CLASS DRAFT and blocks OPEN until an explicit ENABLED
or DISABLED choice. ENABLED describes the Activity Evidence + LiveKit Camera bundle;
this page does not collect activity, request camera access or claim runtime readiness.
PUBLIC clears Classroom and forces DISABLED monitoring; participants still require
current authenticated STUDENT authority.

All scheduling controls are explicitly labeled UTC text. They accept up to six
fractional digits, preserve microseconds and validate calendar dates/offsets without
browser-local DST guessing. Exact bigint microsecond comparisons enforce start < end
and nondecreasing schedule extension. New bounded forms accept whole durations 5–240
minutes and default `maxAttempts` to 1. Current backend amendments also permit
unlimited duration 0 and `maxAttempts` 0–10: existing values are displayed and preserved,
not silently reset. Unlimited duration cannot enable monitoring or switch modes here.
Extension never changes startAt or existing Attempt deadlines. Window end is not CLOSE.

DRAFT edits preserve entered values on failure; revision conflicts read current
state and require explicit review/discard rather than replaying against a newer
revision. OPEN and CLOSE use reviewed confirmations. Active-Attempt/pending-closure
conflicts remain authoritative; there is no force-close, local closure timer,
frontend terminalization or incomplete notification workflow.

## Session, mutation and privacy safety

After integrating PR #77, Publication consumes the canonical read-only
`SessionRuntime.getSessionGeneration` through AuthProvider. The former W3-A service
getter was removed rather than retained as a duplicate API. LD-05 operation fencing
continues to govern asynchronous authentication work; runtime session generation
fences Publication requests and account/resource changes remount and abort the
screen. Stale successful responses are rejected even if transport ignores abort.
Login, bootstrap, refresh, logout, authority-unavailable recovery and the shared
executor retain the merged LD-05 implementation. Separate LD-05 real-integration
scenarios are not established by Publication acceptance.

Create and CLOSE generate secure 64-character ASCII keys in memory. Concurrent
submission is prevented. Uncertain create retries retain the exact payload/key;
changed payload requires a fresh key only when the earlier outcome is resolved.
Uncertainty remains sticky after a later rejection because that response cannot
prove an earlier create did not commit. Confirmed creation has a direct detail link
if client navigation does not complete. Full reload loses the in-memory receipt;
users must not speculate about duplicate creation after an uncertain reload.

For update/OPEN/extension/CLOSE, network, timeout, abort, malformed success and 5xx
outcomes retain the original command. Authoritative GET reconciliation precedes an
intentional retry; failed reconciliation keeps retry disabled. No command receives a
newer revision automatically. A refresh failure triggers no automatic follow-up GET,
which could otherwise repeat uncertain refresh rotation through the existing executor.
No mutation recovery loop or speculative fresh-key replay is introduced.

401/session failure, 403 denial, masked 404, 409 revision/idempotency/lifecycle conflict,
and authority/dependency/persistence 503 are distinct. Infrastructure failure is not
classified as wrong credentials and sends no feature-initiated logout.

Private PUBLIC locators appear only in authorized in-memory owner state, with explicit
reveal/copy. They never enter routes/query strings, logs, telemetry or persistent
inventories. No Student sharing URL is fabricated. Answer-bearing QuizVersion content
is not retained in Publication state. No SERVICE credentials, answer keys or source
snapshot fields are included in owner Publication models.

## Confirmed findings and resolutions

| Severity | Evidence/reproduction                                                             | Resolution                                                                                      |
| -------- | --------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Medium   | Same configuration with reordered JSON properties could produce a new create key. | Compare named contract fields and exact instants; retry the original serialized request.        |
| Medium   | Cancellation after dispatch may leave a committed mutation.                       | Treat abort as uncertain and reconcile before command replay.                                   |
| Medium   | Create accepted a 200 response contrary to the implemented 201 contract.          | Require endpoint-specific success status; reject malformed/wrong-status success.                |
| Medium   | A 403 after an uncertain create could unlock changed-payload submission.          | Preserve sticky uncertainty and original payload/key until authoritative success.               |
| Medium   | Failed detail reconciliation could leave original-command retry enabled.          | Reset reconciliation before every read; disable retry until a successful current read.          |
| Medium   | Native datetime controls could sanitize microseconds and alter a valid schedule.  | Use labeled UTC text controls with exact parsing; Chromium asserts six-digit round-trip.        |
| Low      | Native dialog Tab traversal could reach a browser focus sentinel.                 | Explicit Tab/Shift-Tab wrapping, Escape dismissal and trigger restoration verified in Chromium. |

The independent read-only reviewer `/root/w3a_security_review` confirmed three
Medium findings (sticky create uncertainty, reconciliation gate, native datetime
precision). All were fixed and re-reviewed: PASS, no remaining confirmed Blocker/High/Medium. The reviewer independently
ran 107 focused tests across four files and `git diff --check`, both passing.

## Original implementation verification

The following results belong to the original implementation checkpoint. The real
historical real-acceptance run and its quality gates are recorded separately below;
current merged-source gates are in the PR #77 follow-up above.

| Layer / exact command from `frontend/` unless stated                | Result                              | Boundary                                                     |
| ------------------------------------------------------------------- | ----------------------------------- | ------------------------------------------------------------ |
| Untouched develop `pnpm test`                                       | PASS **327 / 27 files**             | Unit/component baseline                                      |
| `pnpm exec vitest run features/assessments` before/after full gates | PASS **107 / 4 files**              | Unit/components and mocked API/executor contracts            |
| `pnpm test`                                                         | PASS **434 / 31 files**             | Original 327 plus 107 new cases                              |
| `pnpm typecheck`                                                    | PASS                                | TypeScript                                                   |
| `pnpm lint`                                                         | PASS                                | ESLint                                                       |
| `pnpm format:check`                                                 | PASS                                | Prettier                                                     |
| `pnpm build`                                                        | PASS                                | Production Next.js build                                     |
| `pnpm test:e2e assessment-publication.spec.ts --workers=2`          | PASS **4 tests**                    | Actual Chromium; intercepted Gateway/auth responses          |
| `pnpm test:e2e --workers=4`                                         | PASS **57 tests, 0 skipped**, 25.7s | Browser contract/regression suite with intercepted responses |
| Root: `python3 scripts/local/generate-publication-types.py --check` | PASS                                | Generated OpenAPI parity                                     |
| Root: `git diff --check`                                            | PASS                                | Whitespace/scope verification                                |
| Real W3-A browser topology at original checkpoint                   | **NOT RUN — BLOCKED — INTEGRATION** | Historical inspection; superseded by real acceptance below   |

Runtime: Playwright 1.62.1, Chromium 151.0.7922.34 (cached build 1234).
No dependency install, dependency upgrade or lockfile change was needed.

Browser cases cover pinned history selection with unsaved-draft guard, PUBLIC
create/edit/OPEN/extension/CLOSE, CLASS explicit monitoring and blocked CLOSE at
375px, dialog keyboard wrapping/Escape/focus restoration, and Student owner-route
restriction. Visual artifacts `test-results/w3a-public-desktop.png` (1280×720, durable CLOSED,
microseconds, hidden locator) and `test-results/w3a-class-mobile.png` (375×812,
OPEN after ACTIVE_ATTEMPTS 409) were inspected by the primary agent: readable,
no horizontal overflow. They are intercepted-browser artifacts, not real-service
integration evidence.

## Historical real integration acceptance — 2026-10-16

### Runtime provenance and isolation

The browser served the exact frontend archive
`fbfc46cbf8cf892083e758d0ac76f30588526942` from a private temporary directory,
using Next.js webpack development mode at `http://localhost:3000`. Installed
`node_modules` were reused through a symlink; no dependencies or lockfile changed.
An initial Turbopack launch rejected the external symlink; selecting webpack
resolved that test-environment issue without application configuration changes.
Node was 24.21.0, pnpm 10.15.1, Playwright 1.62.1 and Chromium
151.0.7922.34. No frontend product defect was confirmed and no product source was
changed during acceptance.

`scripts/local/w3a-real-runtime.py up` used `git archive` of backend commit
`4744f81625632fd6e2c24d75017597c0abcbf711`, built fresh JARs for Gateway,
Identity, Quiz, Classroom and Assessment, ran their migrations, and verified all
five services healthy. It reused existing local ownership/lifecycle helpers, not
another developer's service checkout. Build artifact identifiers:

| Service    | JAR SHA-256                                                        |
| ---------- | ------------------------------------------------------------------ |
| Identity   | `e6bdbccc9cb2f81649c477e72e187990ef1ca20885b9153a5ef66d1f391bfa0a` |
| Quiz       | `32f9fde0308b0dd688dbb0375137e19f33cb9f8026c664de2fc15953e3becb67` |
| Classroom  | `6666580c337a2ed59f658ab3fd396e901a8d31f92c58f5409e90c1d45b8455c7` |
| Assessment | `b8b63d19d55ea3ec3bf65750625da0ec0844676bfda6742927c18ebc0db37231` |
| Gateway    | `dd826c734465d45f0c5414e6aa9968187b451a09cb2b1aa6d1e873f3e681ab29` |

Non-secret endpoints for this completed run were Gateway `http://localhost:8080`,
Identity `http://127.0.0.1:54997`, Quiz `http://127.0.0.1:38281`, Classroom
`http://127.0.0.1:47673`, Assessment `http://127.0.0.1:36081` and Mailpit
`http://127.0.0.1:43429`. Business browser traffic traversed Gateway. Four owned
PostgreSQL 17 containers provided separate service databases; owned Redis 8.2 and
Mailpit 1.27.8 used random loopback ports. Fixture resources had explicit ownership
labels. The original six shared containers were unchanged.

Only the disposable fixture enabled `PUBLICATION_ENABLED`, `ATTEMPTS_ENABLED`,
`RESULTS_ENABLED`, `CLOSURE_ENABLED`, `PUBLICATION_SOURCES_ENABLED`,
`INTERNAL_AUTHORITY_ENABLED` and `USER_ADMISSION_ENABLED`. Identity authorization,
email outbox delivery, authoritative reconciliation and user admission were enabled.
Admission clients `gateway`, `quiz-service`, `classroom-service` and
`assessment-service` received only `identity.user-admission`; Assessment also
received `quiz.version-snapshot.read`, `classroom.teacher-authority.read` and
`classroom.membership.read`. Source authorities allowed the Assessment service
identity. Credentials/signing keys stayed in the private fixture; no SERVICE
credential was supplied to the frontend.

Test-only loopback overrides were `USER_ADMISSION_ALLOW_INSECURE_LOOPBACK=true`,
`IDENTITY_USER_ADMISSION_REQUIRE_SECURE_TRANSPORT=false` and
`INTERNAL_AUTHORITY_REQUIRE_TLS=false`. Gateway's allowed origin was exactly
`http://localhost:3000`. These values did not change production defaults. Monitoring,
proctoring/media, inbox delivery, closure worker and recipient-eligibility features
remained disabled. USER access-token TTL remained PT5M; observed JWTs had
`exp - iat = 300`.

### Executed real browser assertions

All three tests in `frontend/tests/w3a-real-e2e/publication.spec.ts` passed with one
worker, zero retries and actual backend responses. No Playwright route interception
or mock server was used. UI lifecycle requests exercised the existing production
authenticated executor. Supplemental idempotency, denial, concurrency and Attempt
probes used browser-origin fetch with the same real USER session and Gateway;
they are real HTTP assertions, not additional UI flows.

- **PUBLIC:** Disposable registration, Mailpit OTP, login and authoritative `/me`;
  real Quiz create/save/publish/history selected immutable version 1. Publication
  create returned 201; owner GET returned 200/no-store with exact source IDs and
  six-digit microseconds. Original key/payload replay returned 201 and the same ID;
  altered payload under that key returned 409 `IDEMPOTENCY_CONFLICT`. Missing source
  and owner resources returned 404; an invalid USER bearer returned 401. Concurrent
  actual update returned 200, stale UI update returned 409 and preserved local
  inputs; explicit discard/review then saved with 200.
- **Dependency recovery:** Pausing only the ownership-verified Quiz process produced
  actual OPEN 503 `DEPENDENCY_UNAVAILABLE`. Publication stayed DRAFT, with no refresh
  or logout. Resuming Quiz and explicit authoritative reconciliation/original-command
  retry returned OPEN 200. Schedule PATCH returned 200 with fixed start/source and
  allowed duration/end extension. A real PUBLIC ACTIVE Attempt returned 201; CLOSE
  returned 409 and fresh GET remained OPEN. The Student abandoned its own Attempt
  with 200; eligible direct CLOSE returned 200 and fresh owner GET was durably CLOSED.
- **CLASS:** Real Teacher-owned Classroom POST and Publication create returned 201.
  UNCONFIGURED prevented UI OPEN; explicit DISABLED monitoring PUT and authoritative
  OPEN returned 200. Missing and existing foreign-Teacher Classroom references were
  masked with 404 `CLASSROOM_NOT_ACCESSIBLE`. A separate current Student received
  owner GET 403 and a blocked Teacher UI; nonmember Attempt start returned 404.
  Membership was then seeded only in the owned disposable Classroom database,
  after verifying the actual owner/Classroom. This setup does not test a membership
  creation API or UI. The authorized Student's actual start returned ACTIVE/201;
  Teacher CLOSE returned 409 `ACTIVE_ATTEMPTS`, with fresh owner GET still OPEN.
  The Student's own abandon returned 200. A different current Teacher received
  owner GET 404 `PUBLICATION_NOT_FOUND`.
- **Same-document account isolation:** Pausing owned Assessment stalled an old owner
  GET. The same browser document logged out with 204 and authenticated another
  account. After resume the old request was canceled, the old Publication payload
  did not render, and the other Teacher's authoritative request remained 404. A
  document sentinel proved logout/login did not reset the JavaScript document.
- **Committed creation, lost response:** Pausing owned Quiz, dispatching real create,
  and observing queued request bytes on that exact Quiz PID/port established
  forwarding before owned Gateway was paused. After Quiz resumed, an owned read-only
  Assessment receipt query proved one committed creation while no browser response
  had arrived. The real 15-second feature deadline displayed a locked uncertain form;
  no automatic replay, refresh or logout occurred. After resume, explicit retry sent
  byte-identical payload and key, returned 201 for the existing resource, left exactly
  one receipt and reconciled through fresh owner GET 200.

Protected admission checks immediately after Teacher enablement returned real
503 `AUTHORITY_UNAVAILABLE` while the new authority state propagated. The enablement
endpoint itself returned 204. Fixture preparation used bounded read-only GET
polling until authoritative 404 `PUBLICATION_NOT_FOUND`, before dispatching any
Publication mutation. An early CLASS run failed on this provisioning lag; the final
three-case run passed with this explicit readiness barrier. No mutation assertion
or backend authorization behavior was weakened.

### Auth, Origin and confidentiality

Actual browser cookies had `quizopia_refresh`, HttpOnly, SameSite=Lax and
`/api/auth` path; JavaScript could not read them. Tokens, refresh values, OTPs,
passwords and private locators were absent from observed URLs/query strings,
links, console output and local/session storage; IndexedDB had no databases.
All observed browser bearer JWTs were USER tokens, and the browser made no
`/oauth2/token` SERVICE request. Locators were initially hidden. These assertions
cover observed surfaces in these journeys, not an audit of external telemetry
systems. Next.js telemetry was disabled for the fixture.

Actual Gateway preflight from `http://localhost:3000` returned 200 with credentialed
CORS; `http://localhost:3100` returned 403. An actual refresh POST with Origin
`http://localhost:3100` also returned 403. Trusted Origin/CORS checks were exercised,
not bypassed. The loopback fixture does not establish HTTPS/TLS deployment behavior.

The sanitized reporter prints static evidence/statuses, not credentials or raw
payloads. Browser trace, screenshots and video were disabled. Fixture faults and
read-only receipt/socket observations verify PID/JAR/cwd or container ownership;
no shared processes or databases were injected with faults.

### Commands and cleanup

From repository root, provision and run fixture-only regressions:

```sh
python3 scripts/local/w3a-real-runtime.py up
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/local/tests -p test_w3a_real_runtime.py -v
```

From the owned archived frontend, with Node 24.21.0 available:

```sh
NEXT_PUBLIC_API_URL=http://localhost:8080 NEXT_TELEMETRY_DISABLED=1 \
  pnpm exec next dev --webpack --hostname localhost --port 3000
```

From this checkout's `frontend/`, the exact completed acceptance command was:

```sh
W3A_REAL_GATEWAY_URL=http://localhost:8080 \
W3A_REAL_MAILPIT_URL=http://127.0.0.1:43429 \
W3A_REAL_STATE_DIRECTORY=/tmp/quizopia-w3a-kypnaoqq \
  pnpm exec playwright test --config=playwright.w3a-real.config.ts
```

| Historical acceptance command                                       | Result                              | Evidence boundary                                                             |
| ------------------------------------------------------------------- | ----------------------------------- | ----------------------------------------------------------------------------- |
| Real Chromium command above                                         | **PASS 3/3, zero retries**          | Archived frontend; real isolated five-service backend                         |
| Root fixture unittest command above                                 | **PASS 8 tests**                    | Mocked OS/Docker cleanup and socket-witness regressions; no real faults       |
| `pnpm test:e2e --workers=4`                                         | **PASS 57 tests, 0 skipped**, 23.7s | Fresh intercepted-browser regression; isolated real runtime already stopped   |
| `pnpm test`                                                         | **PASS 434 tests / 31 files**       | Fresh full unit/component and mocked contract regression                      |
| `pnpm lint`                                                         | **PASS**                            | Fresh ESLint check including new real-suite TypeScript                        |
| `pnpm build`                                                        | **PASS**                            | Fresh production Next.js build; real fixture used archived webpack dev server |
| `pnpm typecheck`                                                    | **PASS**                            | Fresh TypeScript check including new real-suite config and helpers            |
| `pnpm format:check`                                                 | **PASS**                            | Fresh frontend Prettier check                                                 |
| Root: `python3 scripts/local/generate-publication-types.py --check` | **PASS**                            | Generated OpenAPI parity                                                      |
| Root: `git diff --check`                                            | **PASS**                            | Scoped whitespace check                                                       |

All required frontend gates were rerun after the real acceptance harness stabilized.
The original results above remain historical implementation evidence; this table
records the historical acceptance-session reruns. New scoped files contain only the isolated
runtime, its regressions, real-browser config/helpers/spec/reporter and documentation.
No backend production logic, frontend authentication behavior or deployment defaults
changed.

The acceptance delta received independent read-only review from
`/root/w3a_real_review`: final PASS, no remaining confirmed Blocker/High/Medium.
An initial Medium fixture defect could stop all cleanup when a paused process had
already exited. Cleanup now verifies each resource independently, continues after
exited processes, refuses replacement PIDs/foreign containers, and records uncertain
Docker or process-exit results. Eight regression tests cover cleanup and the
read-only forwarding witness. The reviewer independently ran those tests, matched
all five JAR hashes to this evidence, and verified the recorded processes and
containers absent. No frontend product defect required an implementation change.

Cleanup used `python3 scripts/local/w3a-real-runtime.py down` with the private state
directory. Its conservative five-second exit bound initially left Identity/Gateway
exit verification pending. Follow-up independent inspection verified all five
recorded backend PIDs absent, all six owned containers absent, all private service
ports and frontend port 3000 closed, and the six original shared containers unchanged.
The owned frontend processes also exited. The private evidence directory remains
for local inspection; its secrets are not committed. Cleanup is reported from that
follow-up inspection, not falsely inferred from the launcher's initial result.

## Integration handoff and deferred work

The original no-listener blocker was resolved through the isolated fixture above.
Leader review of the bounded real evidence remains required; keep PR #92 Draft until
acceptance is explicit. This run does not establish compatibility across newer or
mixed backend revisions, external TLS deployment, production flag activation,
monitoring privacy/runtime readiness, or the separately scoped LD-05 Redis outage,
stale-restore/recovery and uncertain refresh-cookie rotation gates from merged
PR #77. Those scenarios were not executed by this W3-A acceptance suite.

Backend/product dependencies remain: owner Publication list and authorized Classroom
selection directory. Deferred frontend scope: Student entry/Attempt/autosave/submit,
results, grading/review, delayed closure notifications, Activity Evidence/camera/AI
monitoring, and sharing-route/fragment conventions. Real Attempt HTTP probes above
verify authoritative closure eligibility; they do not deliver those Student workflows.
No fake inventory or placeholder business workflow substitutes for missing experiences.
