# W4F-T1 — Delivery and review evidence

Issue: [#133](https://github.com/hhtuann/Quizopia_2.0/issues/133).
Baseline fetched 2026-10-30: `origin/develop`
`bdf5d116bb7258ced8471a3e4c24b1a3de21f4a2`.
Branch: `feat/w4f-t1-assessment-foundation`; target: `develop`.

Status: **READY FOR INDEPENDENT LEADER RE-REVIEW** — H1/M1/M2 documentation
corrections and focused checks complete after the Leader's CHANGES REQUESTED
verdict at `62f446cd009974b8199e58756862d3ff842496ca`.
[PR #137](https://github.com/hhtuann/Quizopia_2.0/pull/137) stays open and unmerged.
Independent Leader/security decisions remain pending. Correction HEAD and CI
receipts will be posted on the issue/PR after commit; no self-referential commit
SHA is invented here.

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

## Initial implementation verification receipts — reviewed HEAD `62f446c`

The following runs belong to the original implementation, not a fresh execution
on the documentation-correction HEAD. New correction evidence is recorded below.

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

The initial read-only engineering peer review reported **no actionable defects** after full
diff/source/contract inspection. It performed no source edits or Git mutations.
Primary self-review checked source changes, tests, intended files, pending approval
language and scope exclusions. This is not independent Leader or privacy approval.
The later independent Leader review identified H1/M1/M2 contract defects despite
that earlier peer verdict and passing implementation tests; those findings control
the corrections below.

The initial new browser matrix detected revealed-locator overflow: an absolutely
positioned screen-reader status could escape the clipped output. Anchoring it to
the output and its origin fixed the measured defect; all eight vi/en ×
375/768/1280/1920 cases then passed. They verify single-column geometry, no document
overflow, masked copy/full clipboard value, reveal/hide, keyboard activation,
44px controls, retained Results Refresh and absent global Refresh.
Screenshots are generated at `frontend/test-results/w4f-t1-{en,vi}-{width}.png`;
synthetic fixtures only. Desktop1920/en and mobile375/vi were visually inspected.
Existing schedule input geometry checks also pass at the inherited viewports.

## H1/M1/M2 contract corrections — 2026-10-30

Authority: [full Leader review](https://github.com/hhtuann/Quizopia_2.0/pull/137#issuecomment-6518714960)
of exact HEAD `62f446cd009974b8199e58756862d3ff842496ca`.
This correction changes only this record, the contract candidate,
`docs/product/assessment.md` and `docs/open-questions.md`.

- **H1:** replaced the blanket ban on selection detail with a strictly allowlisted
  `transition:{fromOptionLabels,toOptionLabels}` for SINGLE_CHOICE/MULTIPLE_CHOICE,
  pinned to the Attempt's immutable snapshot. It preserves reported []→A→C even
  without SAVE, under CLIENT_OBSERVATION provenance. Cardinality/category/label
  checks, encrypted storage/replay input, authenticated context, no raw numeric
  values/keystrokes, no keys/logging, Teacher authorization/receipt/state gates,
  TTL/holds/FB-015/backup/key custody and partial/unavailable history are specified.
  Detailed matrix/numeric history is explicitly unsupported; no fabrication from
  present SAVE rows. This is a proposed new Assessment schema, not an expansion
  of existing metadata-only Proctoring events. Collection remains OFF.
- **M1:** corrected twenty bins to `[0,0.5]`, `(0.5,1]`, …, `(9.5,10]`.
  Exact formula is `max(0,ceil(20*earned/possible)-1)` after weight validation;
  v1 integer-quarter equivalent and boundary/rounding examples are explicit.
  The score8 retake example now maps to bin15. T2 boundary/aggregation tests are
  specified; no histogram endpoint is implemented.
- **M2:** full Teacher Review and the new audit/transition read are restricted
  to OPEN/CLOSED **graded SUBMITTED** targets. Explicit matrix denies ACTIVE,
  ABANDONED, DRAFT and incomplete results, including dual-role own Attempts;
  earlier submitted work grants no ACTIVE sibling access. Review uses immutable
  submitted answers. Current owner/CLASS/LD-05/fence checks and committed
  sensitive-access receipts apply to every page/dereference; failures disclose
  no sensitive body. Student ASSESS-04 stays CLOSED-only. Live answer disclosure
  requires a separate explicit PO/security/privacy decision.

All new protected-detail, read-receipt and retention/privacy/security controls
remain proposed and approval-gated. No runtime code, UI, tests, OpenAPI, migration,
flag or dependency changes are part of this correction. Historical test evidence
above must not be represented as new correction-head execution.

### Correction verification actually executed

| Check | Actual result / boundary |
| --- | --- |
| `python "%TEMP%\opencode\w4f_t1_boundary_review.py"` | PASS, 5 synthetic arithmetic tests: normative examples; all21 half-point edges; both sides of all19 interior edges; 4,120 quarter-unit ratios checked against independent interval membership and ceiling formula; invalid weights and >64-bit integer exactness. Temporary verification script only, outside the repository; no histogram runtime/API or data I/O |
| Isolated validation Python → `services/assessment-service/contracts/validate-w3-production-release.py` | PASS; production NO-GO and 6 BLOCKED / 5 NOT VERIFIED external gates preserved |
| Isolated validation Python → `services/assessment-service/contracts/test-w3-production-release.py` | PASS, 60 governance regression tests |
| `docker compose config --quiet` | PASS |
| `git diff --check` | PASS |
| `git diff --exit-code 62f446cd009974b8199e58756862d3ff842496ca -- frontend gateway services shared infrastructure scripts AGENTS.md DESIGN.md` | PASS, no tracked runtime/test/contract/config/design changes since reviewed HEAD |

Primary self-review checked all three findings against the complete revised
candidate, API/caller/state tables, examples, threat tests, product summary and
open approval register. A read-only engineering peer review of all four changed
documents found no additional actionable defects and confirmed the per-finding
resolution; it performed no source edits and grants no independent approval.
H1/M2 encryption/authorization/receipt scenarios are
**future T2 test requirements**, not executed tests of nonexistent runtime code.
Frontend, Maven and browser suites were not rerun locally for this docs-only
correction; their original receipts remain explicitly historical. Exact new
GitHub HEAD/check statuses are reported separately on #133 and PR #137.

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
remains separate. Correction handoff stops at **READY FOR INDEPENDENT LEADER RE-REVIEW**.
