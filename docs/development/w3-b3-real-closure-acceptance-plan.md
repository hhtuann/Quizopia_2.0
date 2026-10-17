# W3-B3 real closure + notice acceptance — next workstream

**Status: PLANNED / NOT VERIFIED.** This is an independent follow-up to merged real Assessment smoke harness PR #99; parent issue [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97).

Baseline `develop`: `e8bd838092b82a0de1ed652b1fce59ef019f2b32`.

## Mission

Expand the **owned isolated synthetic** five-JVM real Gateway/Chromium acceptance harness to specifically verify the B3 notice protocol, without claiming production/staging governance approval or shortening any genuine 48-hour time requirement.

### Required authoritative documents

- `AGENTS.md`
- `docs/specifications/w3-b-closure-notification-contract.md`
- `docs/development/w3-frontend-backend-integration-handoff.md`
- `docs/development/w3-production-evidence-handoff.md`
- `docs/development/w3-real-acceptance-isolation-preflight.md`
- `docs/development/w3-real-acceptance-runtime-evidence.md`
- Assessment `publications.openapi.yaml`, implemented controller/worker/inbox/recipient authority and previous acceptance harness source/tests.

## Acceptance targets (only after genuine staged readiness is verified)

1. Real synthetic Teacher and Student with eligible ACTIVE CLASS Attempt, authoritative recipient state and distinct owned resources.
2. **Before** enabling any closure worker/notice recipient dependency, establish fresh authenticated Student inbox list/detail 200 and masked cross-recipient 404, Gateway route/readiness, matching Identity eligible recipient contract and exact least-privilege SERVICE scope. Do not assert `ASSESSMENT_EDGE_INBOX_VERIFIED=true` without such evidence.
3. Real Teacher **REQUEST** under explicit user action: recorded durable request identity, `PREPARING` notice without confirmation or cutoff; no fabricated CLOSED.
4. Where safely authorized and real prerequisites verified, enable only the **owned synthetic** bounded worker/recipient controls; prove durable PUBLISHED recipient-owned notice and fresh eligibility, actual `confirmedNoticeAt` and immutable `closureCutoffAt = confirmedNoticeAt + 48 hours`, plus owner inbox/detail and former-CLASS-member read. Capture evidence of real server-issued timestamps, not a browser clock.
5. Real Teacher **CANCEL** pre-finalization and resulting durable non-actionable notice (no deletion). Probe replay/idempotency and conflicts, account/session fencing, foreign recipient/teacher denial, 503/recovery with exact key/payload retention.
6. **COMPLETE** before the real cutoff must stay blocked with `CLOSURE_NOT_READY`; preserve request identity. Never backdate timestamps, fast-forward DB clocks, alter real cutoff, or claim the ≥48h soak PASS. Actual post-cutoff completion belongs to a separately approved long-running staging soak unless a genuinely eligible elapsed interval is observed.
7. Preserve the existing real W3-A/W3-B/C 5/5 journeys, no Playwright Gateway interception and deterministic resource teardown; add targeted unit tests and sanitized receipts.

## Boundaries

- No existing developer containers, ports, DBs, credentials or processes may be modified or terminated.
- Entire run uses dedicated named disposable localhost-only resources and synthetic actors.
- Proctoring/Activity/LiveKit remain disabled. **Do not invent privacy, retention, security, provider or operator approval references.**
- If technically impossible to verify the full recipient/notice edge protocol without forbidden flags or external approvals, leave that gate **BLOCKED / NOT VERIFIED** with exact missing prerequisites; document safely testable pieces only.
- No production/real learner enablement. Formal NOTICE-04 48-hour operational soak and production approval remain separate gates.

## Deliverable

One primary Codex agent: inspect, plan, implement, run focused checks, rerun actual dedicated real service/Chromium acceptance, document exact SHAs/times/HTTP receipts/test flags/owned cleanup and remaining gates, independently self-review, commit/push Draft PR. Do not merge. Evidence in `docs/development/w3-b3-real-closure-acceptance-evidence.md`.
