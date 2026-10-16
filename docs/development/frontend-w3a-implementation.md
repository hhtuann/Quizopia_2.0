# W3-A Teacher Publication frontend

Implementation checkpoint: 2026-10-16 (Asia/Bangkok).
Branch: `feature/frontend-w3a-publication`, based on develop
`4744f81625632fd6e2c24d75017597c0abcbf711`. LD-05 branch and Draft PR #77
remain separate and untouched; their changes were not merged or cherry-picked.

## Scope and current gate

The bounded Teacher workflow is implemented: pinned published QuizVersion entry,
create/review DRAFT, owner detail, DRAFT update, CLASS/PUBLIC configuration,
explicit monitoring choice, OPEN, OPEN schedule extension and direct CLOSE.
Implementation, frontend quality gates and independent review are complete.
Git publication remains pending; evidence is below.

**Real-topology gate: BLOCKED — INTEGRATION.** Gateway, Identity and business
services had no listeners during inspection. No actual browser-to-Gateway W3-A
journey ran. Backend flags and clients default closed until separately provisioned.
This checkpoint does not complete Wave 3, enable monitoring or establish production
activation. Commit, push and Draft PR finalization remain pending at this checkpoint.

## Plan and progress

This document is the bounded implementation plan and completion mapping; there
are no separate phase files or task records. Frontend implementation is complete,
while Git publication and real-topology acceptance remain pending.

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
- [ ] Commit scoped changes, push the feature branch and open a Draft PR targeting
      develop; record actual commit and PR identifiers after publication.
- [ ] Provision the isolated, revision-identified backend topology and execute real
      Teacher create/read/edit/OPEN/extend/direct-CLOSE journeys. This acceptance
      gate is blocked by missing business-service listeners and provisioning.

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

The only auth addition is a non-secret, read-only session-generation getter in the
existing service/provider. It does not change login, bootstrap, refresh, logout or
role behavior and does not port PR #77. Feature requests bind to that generation;
account/resource changes remount and abort the screen, and stale successful responses
are rejected even if transport ignores abort. Pre-existing auth safety and LD-05
real-integration dependencies remain separate; this feature does not claim to fix them.

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

## Verification evidence

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
| Real W3-A browser topology                                          | **NOT RUN — BLOCKED — INTEGRATION** | No business-service listeners; no real assertions executed   |

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

## Integration handoff and deferred work

Current probes: Gateway :8080, Identity :8081 and Assessment :8084 refused HTTP;
no Quiz/Classroom listeners at :8082/:8083. PostgreSQL 17, Redis 8.2 and Mailpit
1.27.8 were available (Mailpit HTTP 200). Infrastructure presence alone does not
establish admission provisioning, enabled routes, migrations or backend binary revision.

Dev 1 must provide an isolated, revision-identified Gateway/Identity/Assessment/Quiz/
Classroom/PostgreSQL/Redis topology with migrations, exact Publication/CLOSE routes,
admission clients, Quiz snapshot authority and Classroom authority enabled; narrowly
scoped credentials/allowlists and separate Teacher CLASS/PUBLIC resources are required.
Do not weaken CORS, Origin, cookie or authorization rules or embed SERVICE credentials
in the browser. Execute real create/read/edit/OPEN/extend/direct-CLOSE journeys there.
LD-05 outage/stale-restore/recovery and uncertain cookie rotation remain separate gates.

Backend/product dependencies: owner Publication list and authorized Classroom selection
directory. Deferred frontend scope: Student entry/Attempt/autosave/submit, results,
grading/review, delayed closure notifications, Activity Evidence/camera/AI monitoring,
and sharing-route/fragment conventions. No fake inventory or placeholder business
workflow substitutes for these missing experiences.
