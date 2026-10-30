# W4F-T1 — Delivery and review evidence

Issue: [#133](https://github.com/hhtuann/Quizopia_2.0/issues/133).
Baseline fetched 2026-10-30: `origin/develop`
`bdf5d116bb7258ced8471a3e4c24b1a3de21f4a2`.
Branch: `feat/w4f-t1-assessment-foundation`; target: `develop`.

Status: **READY FOR LEADER REVIEW** — implementation, local verification and
read-only engineering peer review complete; independent Leader/security decisions
remain pending. Exact final HEAD, PR and CI receipts will be
posted on the issue/PR after commit; no self-referential commit SHA is invented here.

## Delivered scope

- Documentation-only [contract freeze candidate](../specifications/w4f-assessment-completion-contract.md):
  actual source/OpenAPI inventory; distinct-student overlapping counters; exact
  scores and twenty 0.5-wide bins across all authorized records; bounded proposed
  student directory/history; separate owner-scoped Teacher Review; minimal audit
  category/provenance/idempotency/storage/access/deletion design; permission and
  threat matrices; explicit PO intent and PENDING security/privacy/TTL approvals.
- Configuration summary no longer repeats chips; full settings remain in a
  single-column definition list, with responsive native disclosure and actions.
- Private locator is one masked-by-default bordered box with exact-value copy,
  keyboard-operable 44px reveal/copy controls, one-line ellipsis, clipboard success/
  failure feedback and stable focus. No value in URL/logs/storage/telemetry.
- Removed the upper-right global Refresh. Results Refresh, sequence-fenced REST
  reads, realtime/visibility/online/periodic reconciliation and uncertain-command
  checks remain. Failed detail reads keep explicit retry.
- Create/edit DRAFT now exposes maximum attempts, with integer 0..10 validation,
  legacy default1, dirty-form protection and Review/Confirm display. Existing
  backend already supports this; no handler, schema or contract was reimplemented.
  OPEN immutability and PUBLIC/monitored CLASS restrictions remain enforced there.
- Student retake is disabled after a confirmed ATTEMPTS_EXHAUSTED response;
  submitted result remains accessible. An uncertain RETAKE prevents a new intent
  and preserves the original request/key for exact retry. No guessed entitlement
  or new Student DTO is introduced.

## Executed verification receipts

| Executed command | Actual result |
| --- | --- |
| `frontend> pnpm exec vitest run features/assessments features/attempts/components/student-assessment-pages.test.tsx features/realtime/hooks/publication-realtime-lifecycle.test.tsx` | PASS, 11 files / 195 tests |
| `services/assessment-service> .\mvnw.cmd -B verify "-Dtest=PublicationIntegrationTest,PublicationConfigurationTest,StudentAttemptIntegrationTest,AttemptPersistenceIntegrationTest"` | BUILD SUCCESS, 92 tests, 0 failures/errors/skips; real disposable PostgreSQL 17.11 via Testcontainers |
| `frontend> pnpm exec playwright test tests/e2e/assessment-publication.spec.ts tests/e2e/assessment-publication-visual.spec.ts tests/e2e/student-assessment.spec.ts --workers=2` | PASS, 32 Chromium tests; intercepted Gateway responses, not a new real-stack acceptance claim |
| `frontend> pnpm typecheck` | PASS after correcting a test-only unsupported `exact` matcher option |
| `frontend> pnpm lint` | PASS |
| `docker compose config --quiet` | PASS |
| `frontend> pnpm test` (read-only verifier) | PASS, 88 files / 981 tests, no failures/skips |
| `services/assessment-service> .\mvnw.cmd -B verify` (read-only verifier) | BUILD SUCCESS, 493 tests, 0 failures/errors/skips; first invocation hit the terminal's 120-second timeout, full rerun completed in 6m16s |
| `frontend> pnpm test:e2e --workers=2` | PASS, 168 Chromium tests, 0 failures/skips; includes all eight new T1 vi/en viewport cases |
| `frontend> pnpm format:check` | PASS |
| `frontend> pnpm build` | PASS, optimized Next.js compilation and TypeScript/static generation |
| `python services/assessment-service/contracts/validate-w3-production-release.py` | PASS with isolated validation Python, eleven external gates preserved: 6 BLOCKED / 5 NOT VERIFIED |
| `python services/assessment-service/contracts/test-w3-production-release.py` | PASS, 60 structural/governance regression tests |
| `git diff --check` | PASS |

Local toolchain: Java21.0.10, pnpm10.26.2, Chromium via installed Playwright;
CI uses repository-pinned pnpm10.15.1/Node24. The system Python lacked PyYAML;
the two Python validators were rerun using a disposable external venv at
`%TEMP%/opencode/w4f-t1-validation` with PyYAML6.0.3, without repository dependency
changes. No production/release gate was closed by structural checks.

Read-only engineering peer review reported **no actionable defects** after full
diff/source/contract inspection. It performed no source edits or Git mutations.
Primary self-review checked source changes, tests, intended files, pending approval
language and scope exclusions. This is not independent Leader or privacy approval.

The initial new browser matrix detected revealed-locator overflow: an absolutely
positioned screen-reader status could escape the clipped output. Anchoring it to
the output and its origin fixed the measured defect; all eight vi/en ×
375/768/1280/1920 cases then passed. They verify single-column geometry, no document
overflow, masked copy/full clipboard value, reveal/hide, keyboard activation,
44px controls, retained Results Refresh and absent global Refresh.
Screenshots are generated at `frontend/test-results/w4f-t1-{en,vi}-{width}.png`;
synthetic fixtures only. Desktop1920/en and mobile375/vi were visually inspected.
Existing schedule input geometry checks also pass at the inherited viewports.

## Impact and limitations

- Runtime schema/API/event/config changes: **none**. One Assessment HTTP integration
  regression test strengthens DRAFT edit validation/OPEN freeze; main backend code,
  all Flyway migrations, OpenAPI/generated clients and production flags unchanged.
- No Task2–4 implementation: no student overview/histogram/Teacher Review backend,
  audit ingestion, new dashboard, camera embedding, AI, recording or screen sharing.
- New proposed audit storage/access/transport budgets are review candidates, not
  accepted retention or legal policy. Required collection notice, lawful basis,
  minors, TTL/holds and qualified privacy/legal approval remain PENDING.
- No new real Browser→Gateway→services run is claimed by intercepted tests. Backend
  HTTP/PostgreSQL and browser contract tests prove different layers. Production
  TLS/provider/backup/legal/operations acceptance remains external and NO-GO.
- Six pre-existing untracked Windows runtime files are user work, preserved and
  excluded from the PR. No deployment, flag activation, merge or issue closure.

## Independent Leader handoff

Use the checklist in the [freeze candidate](../specifications/w4f-assessment-completion-contract.md#8-task-boundaries-and-leader-checklist).
Inspect exact PR HEAD/CI and full diff, decide the proposed security contract
independently, retain pending policy gates, and merge only after review. Task2
requires explicit Leader authorization after T1 review/merge. Final PO acceptance
remains separate. Stop at **READY FOR LEADER REVIEW**.
