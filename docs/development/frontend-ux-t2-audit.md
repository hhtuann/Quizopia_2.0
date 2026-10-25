# T2 — Global UX/UI Consistency: preliminary audit and acceptance register

> Status: INITIAL INVENTORY ONLY. Implementation and route-by-route browser verification are **not yet complete**.
> Baseline: `develop` at `5ac5adeaffe983e4855de0841fdb684c4000c952` (PR #109 merge).
> Primary UX authority: [DESIGN.md](../../DESIGN.md). Follow [AGENTS.md](../../AGENTS.md).

## Purpose and approved scope

Normalize *all currently implemented frontend routes*, not only pages that were reported by users.

In scope:
- UX-01: overly constrained application content and poor use of available width;
- UX-02: redundant navigation/back controls (keep necessary task escape routes);
- UX-03: nonfunctional decorative labels/breadcrumbs;
- UX-04: inconsistent banner/gradient/status-badge treatments;
- UX-05: page hierarchy, spacing, responsive behavior and shared components;
- UX-07: switch LEARNING/TEACHING to its home route without changing role or JWT;
- use the shared local date/time control for OPEN schedule extension without losing authoritative UTC precision or validation.

Out of scope: new Publication listing/My Assessments (T3), Classroom listing/visual selector (T3), owner self-attempt policy and Preview/Test (T3), Vietnamese/English internationalization (T4), API/backend/security/production flag changes, any local Windows runtime provisioning.

## Evidence-backed preliminary findings

| Component | Observation at baseline | Expected T2 resolution |
|---|---|---|
| `frontend/components/ui/page-container.tsx` | Application width is fluid by default; marketing uses `max-w-7xl` | Keep the working shared width model; audit page-level nested constraints rather than blindly modifying the shell |
| `frontend/features/notifications/components/notification-pages.tsx` | Includes `mx-auto max-w-4xl` inbox, `max-w-3xl` detail, duplicate back-to-home and `Learning · Assessment notices` chrome | Rework page composition and redundant chrome while retaining essential deep-link recovery |
| `frontend/features/assessments/components/publication-pages.tsx` | Publication entry still has `mx-auto max-w-3xl`; create-specific duplicate header removed by PR #109 | Avoid unnecessary narrow layout; do not invent a Publication-list API |
| `frontend/components/shell/authenticated-user-menu.tsx` | `switchTo()` updates workspace state and closes menu but does not navigate | On successful *actual* workspace change, `router.replace('/app')`; keep JWT/roles unchanged and protect unsaved work |
| `frontend/features/assessments/components/publication-schedule-extension.tsx` | OPEN extension still uses a raw UTC text field | Reuse shared local date/time controls with correct UTC conversion, microsecond-preserving untouched instants and no-decrease validation |
| `frontend/components/ui/state-badge.tsx` | Semantic state variants use solid/tinted styles | Preserve semantic statuses; reconcile mismatched header/banner decoration against DESIGN.md rather than adding arbitrary gradients |
| `frontend/features/quizzes/components/quiz-authoring-pages.tsx` | Multiple application/editor regions, some intentionally narrow dialogs/panels | Treat focused editor navigation and reading widths as intentional until audited; do not remove required Back to app path |

These findings are not a claim that every route was visually inspected. The implementation agent must expand this document to a complete route/component disposition matrix before PR review.

## Route inventory to audit

Observed app page routes at baseline (verify fresh inventory before finalizing):

- `/app`
- `/app/quizzes`, `/app/quizzes/new`, `/app/quizzes/[quizId]`
- `/app/assessments/new`, `/app/assessments/[publicationId]`, `/app/assessments/[publicationId]/results`
- `/app/assessments/[publicationId]/closure`, `/app/assessments/[publicationId]/closure/[closureRequestId]`
- `/app/assessments/join`
- `/app/attempts/[attemptId]`, `/app/attempts/[attemptId]/result`, `/app/attempts/[attemptId]/review`
- `/app/notifications`, `/app/notifications/[notificationId]`
- `/app/monitoring/attempts/[attemptId]`, `/app/monitoring/teacher`, `/app/monitoring/teacher/attempts/[attemptId]`
- `/login`, `/register`, `/verify-email`
- public/entry route `/`

Audit corresponding conditional states, fallback/error/empty/loading views, teacher and student workspaces, editor/fullscreen layouts, and authenticated/unauthenticated redirects. Dynamic routes need representative accessible fixtures (real local runtime where available; explicit mocks otherwise).

## Design and safety constraints

1. Respect DESIGN.md: application screens may be wide for dashboards/tables; reading prose and login forms can intentionally be narrow.
2. Brand Indigo-to-Violet gradient belongs on approved CTAs/heading emphasis; routine status badges and data labels must remain semantic and restrained.
3. Keep source/edit controls, answer views, dialogs, essential back navigation, keyboard access, focus handling and 44px mobile interaction targets usable.
4. Workspace selection is a frontend persona switch; it must **not** mutate Identity roles or refresh JWT purely for a UI change. Redirect to `/app` via client navigation on successful change, with no unintended draft loss.
5. Preserve all prior backend business policy, feature gates, private locators and review/answer-release restrictions.
6. Preserve untracked Windows runtime helper files and ignored secrets; never use `git clean`, `git reset --hard` or blanket staging.
7. Do not claim a browser visual review based on source scans or clipping assertions alone: collect actual screenshots and list what was/was not manually reviewed.

## Required exit evidence (to fill during implementation)

- Complete page/route issue matrix (before, root cause, fix, verification and accepted exceptions).
- Shared design changes explained; absence of duplicated scattered CSS overrides.
- Vitest, TypeScript, lint, Prettier, Next build and applicable Playwright results.
- Browser screenshots for desktop 1920/1440, tablet 768, mobile 390 across representative Teacher, Student, Notifications, Quiz and Assessment screens.
- Successful workspace switch both ways and unsaved-work protection tests.
- Schedule extension time boundary/precision and contract regression tests.
- Final branch/commit/PR, CI report and explicit remaining risks.

**Update this register with actual findings and proof before requesting independent review.**
