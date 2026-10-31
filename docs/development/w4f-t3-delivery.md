# W4F Task 3 — Unified Assessment Management Dashboard delivery record

Recorded: 2026-10-31. Branch `feature/w4f-t3-unified-assessment-dashboard`
from merged Task 2 develop `03fc95428649a223be019624f6635421acffc71e`.
Issue: [#135](https://github.com/hhtuann/Quizopia_2.0/issues/135).

## Preflight gate (verified before any T3 code)

- Task 2 PR #143 merged at `03fc954`; exact-merge CI: Frontend 41196840201,
  Backend 41196840202, W4F private synthetic demo 41196840203 all SUCCESS;
  Repository validation 41196840208 SUCCESS **including full-validation**
  (11:51:16Z) — the #144 `GatewayAuthIntegrationTest` 5s timeout on the prior
  SHA `ad16450a` is resolved at the merged develop SHA.
- Acceptance authority recorded in #134 (11:41Z): PO delegates coursework
  technical review/acceptance to the Technical Leader with PO self-approval;
  separate named human reviewer waived for coursework. Task 3 authorized.

## What was built

**One student-centric dashboard replaces the separated attempt-row results
experience.** The Student is the primary row; attempts are subordinate
details. No duplicate attempt-row table remains in the product UI (the old
`/results` attempt-row screen, its client and hook were removed).

| Area | Implementation |
| --- | --- |
| Wire model | `frontend/features/teacher-results/model/teacher-dashboard.ts` — strict zod schemas for `/student-overview`, `/students`, `/students/{id}/attempts`: canonical UUIDs, exact decimal-string counts/weights, 20 ordered half-point bins with bound-lattice and `sum(bins) = gradedStudentCount` invariants, segmented opaque cursors, cross-field refinements limited to what the T2 aggregate SQL guarantees. |
| API client | `teacher-dashboard-api-client.ts` on the shared assessment requester; only the allowlisted `limit/cursor/studentId/status/sort/direction` query keys are ever sent. `assessment-foundation.ts` gained an additive `search` parameter for non-paging GET params. |
| Dashboard | `student-results-dashboard.tsx` — KPI cards (distinct ACTIVE / SUBMITTED / total participants / graded, best out of ten, average of one best per graded student) with an explicit overlap note (groups are never summed); server 20-bin histogram with counts, focusable region and an always-open accessible data table; sortable (LATEST/BEST_SCORE × ASC/DESC), status-filterable (ACTIVE/SUBMITTED/ABANDONED) and exact-student-ID searchable directory with server cursor pagination and by-studentId dedupe; per-student detail panel with server-paginated history, best/latest badges, terminal reasons, exact scores (ungraded shows —, never 0), authorized Teacher Review link (graded SUBMITTED only) and Activity Evidence link. |
| Embedding | The OPEN/CLOSED Publication detail main column embeds the same dashboard; the page's single `PUBLICATION_TEACHER` WebSocket subscription forwards invalidation hints into the dashboard's debounced REST refresh (`onRegisterInvalidate`). The standalone `/results` route wraps the dashboard with the authoritative publication header and subscribes while OPEN. Manual refresh stays exactly one control. |
| Realtime | `useRealtimeResync` coalesces hints/periodic ticks into sequence-fenced REST refetches; refresh resets pagination; in-flight reads are aborted and stale resolutions ignored; no LIVE claims beyond the existing `RealtimeStatusInline`. |
| Teacher review | T2 review/timeline reused, not rewritten: failure classification now distinguishes 503 `SENSITIVE_POLICY_PENDING` (policy-gated), 401/403, 404/409 state, and transient unavailability; audit provenance labels localize SERVER_AUTHORITY (`Server record`) vs CLIENT_OBSERVATION (`Browser report`), server-received vs browser-reported times, coverage reasons and transition-detail statuses. Sensitive endpoints remain fail-closed outside the demo profile. |
| Responsive/a11y | Directory and history render dense tables at md+ and stacked card rows on mobile (no viewport-level horizontal scrolling; verified 375/768/1280/1920). Responsive column prioritization, labelled controls, `aria-expanded` rows, focusable table regions, keyboard-operable sort/filter/search, sr-only captions, VI/EN catalog additions. |

## Security and truthfulness properties preserved

- All aggregates come from the owner-authorized T2 endpoints; the browser
  never recalculates counters, averages, bins or totals; no fetch-all clients.
- Ungraded attempts never render as scores (— with truthful labels).
- Directory/history contain no answers or keys; review links appear only when
  the server marks `teacherReviewAvailable`; Student ASSESS-04 CLOSED gate and
  the review route's own authorization are untouched.
- 403/404 reads clear rows and show masked messages; no other teacher's data
  renders; audit history is never fabricated — coverage gaps stay explicit.

## Verification (all on the final branch state)

- Vitest: **1007/1007** (93 files), including 24 new/updated dashboard tests
  (KPIs, bins, filters/sort/search query shapes, cursor dedupe, refresh reset,
  stale-response fencing, realtime invalidate refetch, denial masking) and the
  rewritten wire-contract suite.
- ESLint, Prettier, `tsc --noEmit`, production `next build`: clean.
- Playwright (intercepted Chromium): **174/174** — new
  `unified-assessment-dashboard.spec.ts` (PUBLIC + CLASS fixtures, exact
  upper-inclusive boundary bins, server-side query allowlists, retake history
  with gated review/evidence links, non-owner denial, secrecy, 375–1920
  no-overflow + keyboard focus, truthful empty state) and updated results,
  teacher-directory, W3 journey specs.
- Generated-contract parity: `generate-teacher-assessment-types.py` re-run
  against OpenAPI 1.1.0 produces a byte-identical file.
- Real-stack private synthetic demo (`AcademicDemoTopologyTest -Dw4f.demo=true`):
  **PASS** — extended journey logs the teacher into the Teaching workspace,
  opens the real dashboard for both the PUBLIC and CLASS publications, asserts
  the server histogram (single 10.00 in (9.5, 10]), 100.00% best, best/latest
  badges, submitted-review link and abandoned retake, and no horizontal
  overflow at 375/768/1280/1920, on real Identity/Classroom/Assessment/Quiz
  services, PostgreSQL ×3, Redis, RabbitMQ and a real browser.

## Deliberate scope boundaries

- No Camera/LiveKit embedding (Task 4), no monitoring/AI/recordings, no
  production flags, no new backend endpoints, no T2 grading/finalization
  changes, no WS authority beyond invalidation.
- The `/results` attempt-row REST endpoint remains available in the backend
  contract; only the frontend consumer was superseded.
