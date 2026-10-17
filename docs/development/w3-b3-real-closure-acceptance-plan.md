# W3-B3 real closure + notice acceptance — next workstream

**Status: VERIFIED IN SYNTHETIC ACCEPTANCE (Targets 1–5 & 7); Target 6 Genuine ≥48h Soak & Post-Cutoff Completion Gated as BLOCKED.** This is an independent follow-up to merged real Assessment smoke harness PR #99; parent issue [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97).

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

## Staged execution sequence and acceptance targets

1. **Target 1 — Actor and attempt readiness**: Real synthetic Teacher and Student with eligible ACTIVE CLASS Attempt (`durationMinutes: 0`, no deadline), authoritative recipient state and distinct owned resources.
2. **Target 2 — Pre-activation prerequisite verification**:
   - Identity recipient capability is enabled at runtime boot (`W3A_IDENTITY_NOTICE_RECIPIENTS_ENABLED=true`), while Assessment edge attestation (`ASSESSMENT_EDGE_INBOX_VERIFIED`), recipient eligibility (`ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED`), and closure worker (`ASSESSMENT_CLOSURE_WORKER_ENABLED`) default to `false`.
   - Before enabling Assessment edge attestation, recipient eligibility, or worker activation, verify:
     - Actual Gateway route & readiness (`/actuator/health/assessment-inbox` returns HTTP 200 `UP`).
     - Fresh Student inbox returns HTTP 200 `[]`.
     - Non-existent notice ID returns HTTP 404 (random-UUID unknown-ID probe).
     - Identity recipient contract returns eligible with least-privilege SERVICE token scope (`identity.notice-recipient.read`).
     - Unauthenticated recipient request denied with HTTP 401.
     - Gateway/USER scope recipient request denied (least-privilege enforcement).
   - Verify Teacher delayed-closure REQUEST returns expected HTTP 503 `DEPENDENCY_UNAVAILABLE` while Assessment edge/worker prerequisites remain disabled.
3. **Target 3 — Phase 1 edge activation, Teacher REQUEST, and pre-worker state**:
   - Operator-controlled staged transition activates Assessment edge inbox attestation and recipient eligibility (`ASSESSMENT_EDGE_INBOX_VERIFIED=true`, `ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED=true`) while the synthetic closure worker remains OFF (`ASSESSMENT_CLOSURE_WORKER_ENABLED=false`).
   - Teacher delayed-closure REQUEST under explicit user action: recorded durable request identity (`PENDING`), Publication remains OPEN.
   - Observe authoritative pre-worker state with worker OFF: `attempt.closureCutoffAt=null`, student inbox contains 0 unconfirmed items.
   - Capture actual UI REQUEST payload and idempotency key: exact same-key replay returns identical HTTP 201 with full response-body equality; altered payload with same key returns HTTP 409 `IDEMPOTENCY_CONFLICT`; new key while pending returns HTTP 409 `CLOSURE_PENDING`.
4. **Target 4 — Phase 2 worker activation, PREPARING notice, 48h cutoff, former-member read, and post-activation cross-recipient masking**:
   - Operator-controlled staged transition activates the assessment closure worker (`ASSESSMENT_CLOSURE_WORKER_ENABLED=true`, poll delay 4000ms).
   - Observe authoritative server-issued `PREPARING` notice before worker confirmation (`confirmedNoticeAt=null`, `closureCutoffAt=null`, UI alert 'Your notice is being prepared' and badge 'Notice preparing').
   - Worker confirms recipient notice with server-issued timestamps: actual `confirmedNoticeAt` and immutable `closureCutoffAt = confirmedNoticeAt + 48 hours` (+172,800,000ms).
   - Student navigates browser UI to notice detail showing authoritative 48-hour cutoff.
   - Former CLASS member read verified: upon removal of classroom membership, entry to assessment is rejected (404), while recipient notice inbox and detail remain accessible (HTTP 200).
   - **Post-activation cross-recipient authorization masking**: explicitly distinguished from pre-activation unknown-ID 404; Student B accesses Student A's confirmed notice ID and receives HTTP 404 `NOTICE_NOT_FOUND` with empty inbox.
5. **Target 5 — Teacher CANCEL, foreign-Teacher fencing, and exact replay**:
   - Foreign-Teacher authorization fence: unrelated Teacher receives HTTP 404 `PUBLICATION_NOT_FOUND` on GET, REQUEST, CANCEL, and COMPLETE.
   - Teacher confirms CANCEL in UI: backend returns HTTP 200, state transitions to `CANCELLED`, UI shows request resolved.
   - Cancelled notice persists durably in database with `noticeStatus = CANCELLED` (no deletion).
   - Capture actual UI CANCEL payload and idempotency key: exact same-key replay returns identical HTTP 200 with full response-body equality; altered payload with same key returns HTTP 409 `IDEMPOTENCY_CONFLICT`; new key on resolved request returns HTTP 409 `CLOSURE_RESOLVED`; student denied CANCEL (HTTP 403).
6. **Target 6 — Early COMPLETE denial & genuine cutoff protection**:
   - COMPLETE before real cutoff stays blocked with HTTP 409 `CLOSURE_NOT_READY`; request identity and `PENDING` state preserved. Never backdate timestamps, fast-forward DB clocks, alter real cutoff, or claim the ≥48h soak PASS.
   - Genuine ≥48-hour soak and post-cutoff completion remain explicitly gated as **BLOCKED / NOT VERIFIED** for a dedicated long-running staging soak.
   - Outage recovery: safely feasible single-node dependency outage (SIGSTOP/resume) verified; multi-node failover and W3-M LiveKit proctoring remain separate gates.
7. **Target 7 — Regressions, fail-closed runtime controls, and cleanup**:
   - Preserve existing real W3-A/W3-B/C 5/5 journeys, zero Playwright Gateway interception.
   - Process-exit detection distinguishes verified process exit (`ProcessLookupError`) from missing runtime state (`RuntimeError`). Missing state or ownership uncertainty fails closed without signaling foreign PIDs or claiming verified cleanup.
   - Deterministic resource teardown verified with zero unverified resources.

## Boundaries

- No existing developer containers, ports, DBs, credentials or processes may be modified or terminated.
- Entire run uses dedicated named disposable localhost-only resources and synthetic actors.
- Proctoring/Activity/LiveKit remain disabled. **Do not invent privacy, retention, security, provider or operator approval references.**
- If technically impossible to verify the full recipient/notice edge protocol without forbidden flags or external approvals, leave that gate **BLOCKED / NOT VERIFIED** with exact missing prerequisites; document safely testable pieces only.
- No production/real learner enablement. Formal NOTICE-04 48-hour operational soak and production approval remain separate gates.

## Deliverable

One primary Codex agent: inspect, plan, implement, run focused checks, rerun actual dedicated real service/Chromium acceptance, document exact SHAs/times/HTTP receipts/test flags/owned cleanup and remaining gates, independently self-review, commit/push Draft PR. Do not merge. Evidence in `docs/development/w3-b3-real-closure-acceptance-evidence.md`.
