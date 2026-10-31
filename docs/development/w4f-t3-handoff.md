# Task 3 Implementation Handoff (pre-authorization prep only — no Task 3 code)

Prepared by the Task 2 primary implementation agent on 2026-10-31 at PR #143
HEAD `d5f9786`. **Task 3 must not start** until PR #143 is independently
reviewed, merged, and Task 2 is confirmed 23/23 with explicit PO/Leader
handoff per [#135](https://github.com/hhtuann/Quizopia_2.0/issues/135).

## What Task 2 leaves behind (the contract T3 builds on)

| Area | Stable surface T3 can consume |
| --- | --- |
| Aggregates | `GET /student-overview`, `GET /students`, `GET /students/{id}/attempts` (owner-authorized, live keyset pages, exact 20-bin histogram). Generated TS types in `frontend/features/teacher-results/api/teacher-assessment-contract.generated.ts`; regenerator `scripts/local/generate-teacher-assessment-types.py`. |
| Sensitive reads | `GET /attempts/{id}/teacher-review` and `GET /attempts/{id}/answer-audit` — graded-SUBMITTED-only, receipts committed before body; positive paths available **only** under the loopback `academic-demo` profile with seeded fictional subjects; outside it endpoints stay 503 `SENSITIVE_POLICY_PENDING`. |
| Opt-in observations | `GET/PUT /attempts/{id}/answer-observation-choice` + `POST .../answer-observations`; workspace consent card + memory-only queue already exist in `frontend/features/attempts/audit/` (reuse — do not duplicate). |
| Server facts | V13 trigger-coupled capture (START/SAVE/SUBMIT+RESULT/ABANDON/notice) with quiz-keyed admission ordering vs expiry/purge/holds. |

## T3 build order (suggested)

1. Replace the current `/results` attempt-row page with the **single
   student-centric dashboard** (A: one list, KPI cards from `/student-overview`,
   0.5-bin bar chart with accessible table alternative) — data layer first,
   Corporate Trust components, i18n VI/EN.
2. Student detail drawer/page → `/students/{id}/attempts` + review links
   (Student review stays the existing CLOSED-only route).
3. Teacher submitted-review + timeline UI already exists from T2
   (`teacher-attempt-review.tsx`) — link it from the dashboard instead of
   rewriting.
4. Realtime: reuse W4 invalidation hooks only for refetch hints; REST
   authoritative; dedupe by studentId and reset pagination on refresh.
5. Truthful states: audit disabled/partial/expired/legacy coverage from the
   timeline `coverage` — never fabricate "complete history"; students who
   declined observation simply show "no optional history".

## Guardrails carried from the accepted decisions

- Sensitive endpoints remain fail-closed outside the demo profile; T3 UI must
  render 503 `SENSITIVE_POLICY_PENDING` honestly, not bypass it.
- No new backend endpoints needed for the core dashboard; if one becomes
  necessary, it requires contract-first OpenAPI + generator updates and review.
- FB-012 profile enrichment (names) already server-side; UI must handle null
  names without fabrication.
- DESIGN.md rules apply (dense fluid workspace, no marketing max-width,
  375/768/1280/≥1920 verification, Corporate Trust tokens).
- Vitest/RTL + Playwright mandatory; reuse the `w4f-demo` harness pattern for
  any new real-stack T3 journeys.

## Open dependencies to confirm at T3 kickoff

- PR #143 merged and 23/23 accepted (this handoff does not presume it).
- Whether the demo profile stays enabled for T3 development UX or T3 wires
  only the always-available aggregates (PO decision at T3 authorization).
- Named independent human reviewer for T3 review rounds.
