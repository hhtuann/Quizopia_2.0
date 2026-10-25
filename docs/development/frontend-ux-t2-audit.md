# T2 — Global UX/UI Consistency: audit and acceptance register

> Status: IMPLEMENTATION COMPLETE — route-by-route disposition recorded below with verification evidence.
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

## Shared layout conventions adopted (UX-01/UX-05)

The shell's `PageContainer` already provides a fluid full-width application container with responsive gutters. Pages no longer add their own `mx-auto max-w-*` wrappers except for the justified reading-width cases below.

| Convention | Rule |
|---|---|
| Management/data screens | Fluid: no `mx-auto max-w-*` wrapper; the shell provides gutters. Tables and card grids consume the available width. |
| Reading prose inside fluid screens | Paragraph text is constrained (`max-w-2xl`/`max-w-3xl`) for line length; containers stay fluid. |
| Focused task forms | Moderate constraint retained (`max-w-4xl`): Publication entry guidance, student Join, Attempt taking (`max-w-3xl` question reading width — documented exception), Student result/review (`max-w-4xl`). |
| Authentication | Intentionally narrow welcome/form split (unchanged). |
| Dialogs | `max-w-2xl` modals (unchanged). |
| Quiz Editor | Viewport-filling focused shell (unchanged, DESIGN.md §9.6 exception). |

## Route-by-route disposition matrix

Legend: ✔ verified in this change · — no issue · E exception with justification.

| Route / screen | UX-01 width | UX-02/03 chrome | UX-04/05 visual | Action taken | Verification |
|---|---|---|---|---|---|
| `/` public landing | — (marketing `max-w-7xl`, intentional) | — | — | None | Existing `landing.spec.ts` passes |
| `/login`, `/register`, `/verify-email` | E: intentional narrow split-screen | — | — | None | `auth-layout.spec.ts`, `auth.spec.ts` pass; screenshots `login-1440x900/390x844` |
| `/app` Application Home (Teacher/Learning) | Fluid hero already | — | Hero orb unified to shared `HeroOrb` gradient treatment | Replaced ad-hoc solid orb | Screenshot at all 4 viewports; overflow assertion |
| `/app/quizzes` Quiz Library | Fluid + responsive card grid already | — | Empty-state radial gradient removed (now plain Surface like other empty states); hero uses shared `HeroOrb` | Removed one-off gradient | Screenshot at 4 viewports; `quiz-authoring.spec.ts` passes |
| `/app/quizzes/new` Create Quiz | E: focused form `max-w-2xl` (intentional) | — | — | None | Covered by authoring tests |
| `/app/quizzes/[quizId]` Editor | E: viewport-filling focused shell (DESIGN.md §9.6) | `Back to Quiz Library` + unsaved confirm kept (required escape) | — | Wired shared `useUnsavedNavigationGuard(dirty)` so reloads, link clicks and menu-driven workspace switches offer the same cancel-without-loss protection | Unit: editor tests pass; E2E: unsaved-cancel switch test |
| `/app/quizzes/[quizId]` Published versions dialog | E: modal widths intentional | — | — | None | `published-version-history` tests pass |
| `/app/assessments` Publications entry | `max-w-3xl` → `max-w-4xl` (guidance/lookup reading screen) | — | — | Widened | Manual + build |
| `/app/assessments/new` Create | Two-column responsive grid (PR #109) | Redundant strip removed in #109 | DateTimeField pair | Wired unsaved-work guard on the configuration form | `assessment-publication-visual.spec.ts` 4 viewports + `assessment-create-*.png` |
| `/app/assessments/[publicationId]` Detail | `mx-auto max-w-4xl` → fluid | — | Raw `bg-primary/10` state chip → shared `StateBadge` with semantic `publicationStateVariants` (DRAFT neutral, OPEN success, CLOSED info) | Fluid + StateBadge | `publication-pages.test.tsx` 24 pass; `publication-detail-*.png` |
| `/app/assessments/[publicationId]/results` Teacher results | `mx-auto max-w-5xl` → fluid table region | `Teaching · Results` decorative label removed; deep-link `Back to Publication details` kept | StateBadge already used | Fluid + chrome removal | `teacher-results-pages.test.tsx` passes; w3 journey e2e passes |
| `/app/assessments/[publicationId]/closure` Closure workflow | `mx-auto max-w-4xl` → fluid; loading surfaces fluid | `Teaching · Closure workflow` removed; back link kept | Raw state chip → StateBadge | Fluid + chrome + StateBadge | `closure-pages.test.tsx` passes |
| `/app/assessments/[publicationId]/closure/[id]` Closure request | `mx-auto max-w-4xl` → fluid | `Teaching · Closure request` removed; back link kept | StateBadge already used | Fluid + chrome | `closure-pages.test.tsx` passes |
| `/app/assessments/join` Student join | E: focused entry form `max-w-4xl` kept | `Learning · Assessments` decorative eyebrow removed (heading + shell nav carry context) | — | Chrome removal | `student-assessment-pages.test.tsx` passes; `student-join-*.png` |
| `/app/attempts/[attemptId]` Attempt | E: `max-w-3xl` kept — question reading width | — | Attempt header state moved from eyebrow text to semantic StateBadge (`attemptStateVariants`) | StateBadge | Attempt unit tests + `student-assessment.spec.ts` pass |
| `/app/attempts/[attemptId]/result` | `max-w-3xl` → `max-w-4xl` (align with review) | — | — | Widened | Unit tests pass |
| `/app/attempts/[attemptId]/review` | `max-w-4xl` already consistent | — | — | None | Unit tests pass |
| `/app/notifications` Inbox | `mx-auto max-w-4xl` → fluid; card list becomes `xl:grid-cols-2` responsive grid | `Back to application home` removed (shell nav covers it with active state); `Learning · Assessment notices` decorative strip removed | — | Fluid grid + chrome removal | `notification-pages.test.tsx` passes; `notifications-inbox-*.png` at 4 viewports |
| `/app/notifications/[id]` Notice detail | `mx-auto max-w-3xl` → fluid; message prose constrained to `max-w-3xl` | `Back to inbox` kept (deep-link recovery); `Learning · Assessment notice` strip removed | — | Fluid + prose constraint + chrome | Unit tests pass |
| `/app/monitoring/teacher`, `/app/monitoring/teacher/attempts/[id]` | `mx-auto max-w-6xl` → fluid (dense monitoring) | — | Inner lookup form stays `max-w-3xl` | Fluid | `proctoring-monitoring.spec.ts` passes |
| `/app/monitoring/attempts/[id]` Student monitoring | `mx-auto max-w-5xl` → fluid | — | — | Fluid | Monitoring tests pass |
| Loading / empty / error states | Follow their screens' new widths | — | Quiz library empty state normalized; others already shared `Surface`/`Alert`/`LoadingIndicator` | Normalized | Full e2e suite passes |
| Workspace gates (`Open the Teaching/Learning workspace`) | E: `max-w-2xl` focused guidance cards | Kept as defensive fallbacks for direct URLs | — | None (gates remain) | Gate tests pass |

## UX-07 — Workspace switch behavior

`AuthenticatedUserMenu.switchTo()` now:

1. consults the shared unsaved-work registry first — cancelling keeps workspace state and URL untouched;
2. updates `activeWorkspace` and closes the menu on success;
3. navigates with `router.replace("/app")` so the previous workspace's URL is never left on screen (client navigation only; no reload, no role mutation, no role-change API, no JWT refresh);
4. restores trigger focus.

Unsaved-work safety reuses the existing `useUnsavedNavigationGuard` hook, extended with a module-level registry that menu-driven SPA navigation can consult. Registered screens:

- Quiz Editor (dirty draft) — also gains reload/link protection that previously existed only on the Back control;
- Assessment create/edit configuration form (dirty form);
- Student Attempt screens (already registered; now also protected against menu switches).

Workspace gates (`Open the Teaching workspace` on Teaching routes, and vice versa) remain as defensive fallbacks for direct URLs after reload (workspace state is per-session).

Tests: `application-shell.spec.ts` covers Teaching→Learning from `/app/quizzes`, Learning→Teaching from `/app/assessments/join`, and cancel/accept of the unsaved-form guard (URL, workspace label and form content asserted after cancel).

## Schedule extension correctness (shared DateTimeField)

`publication-schedule-extension.tsx` now uses the shared local date/time field while preserving the backend UTC contract:

- The authoritative `endAt` string is kept verbatim; an **untouched** field resubmits the exact server value, including seconds and microseconds (unit test: `resubmits the authoritative end exactly when the field is untouched, preserving microseconds`).
- Edits emit millisecond ISO instants from the user's local timezone; DST-nonexistent local times are rejected by the shared field.
- No-decrease validation compares exact microseconds: re-entering the same displayed minute for an end that carries microseconds is rejected as a decrease instead of silently shortening the schedule (unit test covers this).
- Current authoritative end is shown in helper text (`formatUtc`).
- Duration and bounded/unlimited restrictions unchanged.
- E2E assertions updated to millisecond precision for UI-edited instants; w3a real-stack selectors updated to the DateTimeField (create form, create/edit, extension).

## Shared components changed

| Component | Change | Rationale |
|---|---|---|
| `components/ui/hero-orb.tsx` (new) | One canonical subtle Indigo→Violet blurred orb for product hero banners | Application Home and Quiz authoring heroes previously used different ad-hoc decorations; both now share the same treatment |
| `features/attempts/hooks/use-unsaved-navigation-guard.ts` | Added `confirmUnsavedWorkLeaving()` registry | Lets the user menu's SPA navigation consult the same guards that protect links/reloads, without inventing a second mechanism |
| `components/shell/authenticated-user-menu.tsx` | Guard consultation + `router.replace("/app")` | UX-07 |
| `features/assessments/model/publication.ts` | `publicationStateVariants` | One semantic mapping for Publication states, reused by detail + closure screens |
| `StateBadge` | Unchanged; now used where raw chips existed | Kills ad-hoc `bg-primary/10` chips |

No scattered page-specific CSS overrides were introduced; all width changes remove wrappers rather than add new ones.

## Evidence

### Automated (CI-reproducible)

- `pnpm test` (Vitest): 752/752 pass.
- `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm build`: clean.
- `pnpm test:e2e` (Playwright): 87/87 pass, including:
  - `application-shell.spec.ts` workspace switch both directions + unsaved-work cancel/accept;
  - `assessment-publication.spec.ts` full create/edit/OPEN/**extend (DateTimeField)**/CLOSE journey with UTC contract assertions;
  - `assessment-publication-visual.spec.ts` schedule field no-overflow/clipping at 4 viewports × locales;
  - `t2-visual-evidence.spec.ts` (new): Teacher Home / Quiz Library / Assessment Create / Publication Detail / Student Home / Join / Notifications Inbox at 1920×1080, 1440×900, 768×1024, 390×844 + login at 1440/390, each with a no-horizontal-overflow assertion and full-page screenshot.

### Visual review (manual observations, not automated)

Screenshots were captured by the spec above (30 files). Honest limitations:

- This agent cannot view images (no image input); acceptance rests on the automated geometry/overflow assertions plus DOM-level snapshot checks in the specs. A human reviewer should skim the captured PNG set for aesthetic judgments (spacing balance, hero appearance) that automation cannot make.
- Native date/time rendering differs per OS; AM/PM visual differences could not be produced in headless Chromium on Windows (see `assessment-publication-visual.spec.ts` comments). Width floors bound the widest native rendering.
- The w3a real-stack suite (`pnpm test:e2e:real:w3`) selectors were updated for the new field structure but the suite was **not executed** in this task (requires a provisioned local runtime; not part of CI).

## Design and safety constraints (verified)

1. Reading widths preserved for prose, auth forms and dialogs; only management/data screens went fluid.
2. Brand gradient usage unchanged on approved CTAs; the only gradient normalization is the shared hero orb and removal of one ad-hoc empty-state gradient.
3. Task-critical back navigation retained: Quiz Editor back, deep-link backs on detail screens, workspace gates.
4. Workspace switching performs no role mutation, no API call, no token refresh; verified by existing storage-leak assertions in `application-shell.spec.ts`.
5. Backend contracts unchanged: schedule extension still sends exact UTC instants; microsecond values survive untouched flows.
6. Untracked Windows runtime helpers and ignored secrets untouched; no `git clean`/`reset --hard`/blanket staging used.

## Remaining accepted exceptions and risks

- Publication detail/config on very wide monitors is intentionally fluid; if future content density demands a cap, decide centrally in DESIGN.md, not per page.
- Attempt-taking screen keeps `max-w-3xl` for question readability (documented above).
- Workspace selection remains per-session (lost on reload by design); gates cover direct URLs.
- w3a real-stack suite updated but not executed locally in this task.
- Native picker visuals vary by platform (Chromium-only coverage in automation).
