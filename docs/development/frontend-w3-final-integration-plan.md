# W3 Final Frontend Integration — execution and acceptance

Status: **WORKSTREAM CREATED — IMPLEMENTATION / REAL ACCEPTANCE NOT YET RUN.** This is an execution contract, not a completed validation report or production approval.

## Exact baseline

- Repository: `hhtuann/Quizopia_2.0`
- Branch: `feature/frontend-w3-final-integration`
- Parent develop at workstream creation: `12094b6aaf8ea91b1585a08790a6cffca2ce8b8e`
- Merged W3-B/C Student PR #94: `5eeb218d8202f902506dd0698e09c2144ec26021`
- Merged W3-B3 Closure/Inbox/Teacher Results PR #93: `81321d91d04b2c7ecd2ca246bbbf83ff81b89ee3`
- Merged W3-M1/M2 Proctoring PR #95: `12094b6aaf8ea91b1585a08790a6cffca2ce8b8e`
- Each standalone PR's original green CI applies to its own isolated branch. The combined journeys require fresh evidence.

## Mandatory composition (single owner)

1. **Student Attempt ↔ Proctoring.** Compose `features/attempts/components/student-assessment-pages.tsx` and its read-only `AttemptMonitoringObserver` boundary with `features/proctoring` exports `useStudentMonitoring` and `StudentCamera`. Source eligibility solely from authorized server projections: eligible monitored bounded ACTIVE CLASS Attempt; PUBLIC, unbounded, terminal and uncertain projections fail closed. StudentView has `monitoringRequired`, while OwnerView has `monitoringChoice`; do not fabricate a Student monitoring-choice field or infer entitlement from merely seeing a UUID. Verify fresh backend authority before any collection. Browser events carry metadata only. No Proctoring call is part of the critical answer SAVE/SUBMIT path. Stop/revoke and clean camera on terminal, expiry, logout/account switch or authority loss.
2. **Teacher Results ↔ authorized Teacher Monitoring.** Update `features/teacher-results/components/teacher-results-pages.tsx` to link only actual authorized `attemptId` to existing `/app/monitoring/teacher/attempts/[attemptId]` where allowed. The destination always performs fresh `GET /api/proctoring/monitoring/sessions/by-attempt/{attemptId}` authorization. No fabricated global roster or shortcut around resource checks. Preserve original results paging, totals and null rows.
3. **Student Inbox ↔ Student Attempt.** In `features/notifications/components/notification-pages.tsx`, replace the placeholder disabled link with navigation to existing `/app/attempts/[attemptId]`, not the historical unsupported nested URL. Route always revalidates current Student ownership. Do not leak old recipients or bypass the inbox's recipient-only access guarantees.
4. **Navigation and cross-feature cohesion.** Preserve both Student Assessments and Notifications navigation merged in `application-shell.tsx`, design system, privacy disclosures, typed API clients, JWT session-generation fencing and no-store handling.

## Authoritative sources

Inspect current `DESIGN.md`, `services/assessment-service/contracts/publications.openapi.yaml`, `services/proctoring-service/contracts/{monitoring,media}.openapi.yaml`, `docs/development/w3-frontend-backend-integration-handoff.md`, `docs/product/proctoring.md`, `docs/specifications/w3-{b-attempt-lifecycle,b-closure-notification,c-grading-results,m-proctoring,m2-media}-contract.md`, and all three merged frontend implementation records.

## Required tests and gates

- Add targeted cross-feature component tests and Playwright journeys for authorized CLASS monitoring, PUBLIC/no-monitoring, terminal/expiry fencing, camera denial/outage, Teacher own/foreign Attempt view, Student inbox navigation, membership/recipient isolation, cross-account switching, dependency 503 and unknown committed saves.
- Keep the prior three feature suites intact. During iteration run targeted checks; for final HEAD run full `pnpm test`, `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm build` and `pnpm test:e2e`.
- **Real Assessment acceptance** requires owned isolated Gateway + Identity + Quiz + Classroom + Assessment databases/Redis/notice topology, real synthetic Teacher/Student membership and explicitly authorized test-only flags. Exercise authorized PUBLIC/CLASS entry, START/RETAKE/SAVE/SUBMIT/ABANDON, result/review after durable CLOSED, delayed closure and recipient notifications, outage/recovery and account switching through real HTTP + Chromium. Record exact service SHAs, sanitized request evidence, fixture flags, process/port ownership, and cleanup.
- **Real monitoring acceptance** additionally requires owned Proctoring + RabbitMQ + actual LiveKit provider with test credentials, eligible synthetic bounded CLASS Attempt, real Chromium camera publish + authorized Teacher subscribe, denied permission, disconnect/reconnect, no-peers, stale-role / membership cutoff and provider outage. Mocked SDK/Playwright checks must NOT be reported as provider acceptance. Provider Cloud hard revocation, private TLS/WAN, retention/privacy approvals and true 48-hour soak remain separate gates until genuinely executed/approved.
- Never run or stop another developer's services, DBs, Docker containers or ports. Never change production feature flags or use real learners to make a test pass. If prerequisites cannot be safely met, label the affected gate **BLOCKED/NOT VERIFIED** rather than substituting mock PASS.

## Agent execution

A single primary Codex implementation agent should autonomously inspect → plan internally → implement → run focused tests → fix → run final validation → independent read-only review (if available) → document evidence → commit → push → create Draft PR. Do not merge without explicit review of the final implementation HEAD. Stay in the assigned branch/worktree; no unrelated scope, force-push, or rewriting the merged PR histories.

Final evidence location: `docs/development/frontend-w3-final-integration-evidence.md` (to be authored by the implementing agent).

Required final report: source/final SHAs; composed routes; accepted API/permission behavior; targeted and full checks; real backend/provider topology with evidence or exact blocker; unresolved privacy/production gates; Draft PR URL and Leader-review decision.
