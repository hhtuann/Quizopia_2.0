# T4 i18n — Verification Record

> **Branch:** `feature/frontend-i18n-final-ux-acceptance` (based on reviewed T3
> merge `f5a2196`, PR #111)
> **Date:** 2026-10-26
> **Status:** branch checkpoint — **pending independent review and CI; not
> merged.** Production release **NO-GO / NOT AUTHORIZED** unchanged.
> Developer reference: [`frontend/lib/i18n/README.md`](../../frontend/lib/i18n/README.md)

T4 delivers the bilingual (vi default / en) presentation layer for the entire
frontend: typed English-key catalogs with Vietnamese values, a cookie-backed
locale preference, server-resolved initial locale, `Intl` formatting, the
visible language switcher, and localized application/auth/landing shells with
strict state-preservation guarantees. **No backend, contract, policy or
`DESIGN.md` change is part of T4.**

## 1. Implementation scope (files by domain)

All changes are uncommitted on the branch. Grouped by owning area:

- **i18n core (new):** `frontend/lib/i18n/` — `core.ts` (catalog merge,
  `TranslationKey`, `translate`/`translateMessage`, `resolveLocale`, cookie
  constant, `localeTags`, `formatNumber`/`formatDecimal`/`formatDateTime`),
  `provider.tsx` (`I18nProvider`, `useI18n`, cookie write, `<html lang>`
  sync), `server.ts` (`getServerLocale`), `messages/{common,authoring,
  assessment,student,monitoring}.ts`, plus this directory's `README.md`.
- **Root integration:** `app/layout.tsx` (server locale → `<html lang>` +
  localized metadata + `initialLocale`), `lib/providers/app-providers.tsx`
  (`I18nProvider` outermost provider).
- **Shell/chrome:** `components/shell/{application-home,application-shell,
  authenticated-user-menu,landing-session-links,workspace-switcher,
  workspace-transition-overlay}.tsx`; `components/ui/{language-switcher
  (new),date-time-field,loading-indicator,otp-input,skip-link}.tsx`;
  `features/auth/components/auth-footer-link.tsx` (new).
- **Auth:** `app/(auth)/{layout,login,register,verify-email}` ,
  `features/auth/{forms/{login,registration,email-verification}-form,
  components/{authenticated-boundary,auth-page-header,
  session-verification-recovery}}`.
- **Quiz authoring:** `features/quizzes/components/{quiz-authoring-layout,
  quiz-authoring-pages,quiz-markdown-code-editor,quiz-markdown-editor,
  quiz-preview,published-version-history}.tsx`.
- **Assessment Publications (teacher):** `features/assessments/components/
  {publication-pages,publication-directory,publication-create-screen,
  publication-configuration-form,publication-confirmation,
  publication-detail-screen,publication-schedule-extension,
  publication-summary,private-publication-locator}.tsx`.
- **Student attempts:** `features/attempts/{api/attempt-api-client,
  components/answer-fields,components/student-assessment-pages,
  hooks/use-attempt-coordinator}`.
- **Classrooms / closures / notifications / results:** `features/classrooms/
  classroom-selector`, `features/closures/components/{closure-pages,
  typed-confirmation}`, `features/notifications/{components/notification-pages,
  model/notification}`, `features/teacher-results/components/
  teacher-results-pages`.
- **Proctoring:** `features/proctoring/components/{student-camera,
  student-monitoring-screen,teacher-camera,teacher-monitoring-screen,
  teacher-timeline}`.
- **Realtime status chip:** `features/realtime/components/realtime-status`.
- **Landing:** `app/page.tsx`.
- **Tests (new):** catalog integrity + per-domain i18n RTL suites
  (`features/**/*-i18n.test.tsx`, `quiz-authoring-catalog.test.ts`,
  `public-auth-localization.test.tsx`), e2e
  `tests/e2e/t4-i18n-acceptance.spec.ts` and
  `tests/e2e/t4-visual-evidence.spec.ts`; existing e2e suites updated for the
  en-pinning cookie pattern (plus two new state-preservation e2e tests, below).

Unrelated working-tree content is **not** T4 and must not be reviewed as
such: the user's unstaged `workspace-transition-overlay.tsx` 2500→1500 ms
timer edit (inherited from before T3), and untracked
`scripts/local/windows_*` / `scripts/local/tests/test_windows_runtime.py` /
`docs/development/windows-local-runtime.md` from a separate local-runtime
effort.

## 2. Catalog key counts (verified)

Counted on 2026-10-26 with a throwaway Node script matching one top-level
`key: value,` entry per line inside each exported catalog object:

| Catalog | Keys | File lines |
| --- | ---: | ---: |
| `common.ts` | 166 | 222 |
| `authoring.ts` | 123 | 173 |
| `assessment.ts` | 459 | 667 |
| `student.ts` | 195 | 317 |
| `monitoring.ts` | 126 | 187 |
| **Total** | **1069** | |

The duplicate-guard test (`quiz-authoring-catalog.test.ts`) verifies the
merged union has no cross-catalog or intra-catalog key collisions.

## 3. Local check evidence

Re-run on this checkout during documentation verification (2026-10-26):

| Check | Result |
| --- | --- |
| `pnpm test` (Vitest) | **804/804 passed, 77 files** |
| `pnpm typecheck` (`tsc --noEmit`) | PASS |
| `pnpm lint` (ESLint) | PASS |
| `pnpm build` (production Next.js) | PASS |
| `pnpm format:check` (Prettier) | **FAIL — see finding below** |

**Re-verification finding (formatting only, must be fixed before PR):**
`prettier --check` currently reports style issues in the two new T4 e2e
specs, `tests/e2e/t4-i18n-acceptance.spec.ts` and
`tests/e2e/t4-visual-evidence.spec.ts`. This documentation task is
docs-only and did not modify them; the implementing session must run
`prettier --write` on those two files (no logic change expected) and re-run
`pnpm format:check` before requesting review/CI.

### Playwright (intercepted browser-contract suite)

`pnpm exec playwright test --list`: **130 tests in 18 files**, all passing in
the T4 local run:

- 109 inherited pre-T4 tests (T3 baseline), updated only for the
  English-pinning cookie pattern;
- 2 new state-preservation journeys added to existing suites:
  `quiz-authoring.spec.ts` — "switching locale mid-attempt preserves unsaved
  answers and submission still succeeds"; `teacher-directories.spec.ts` —
  "language switcher preserves an in-progress DRAFT form and the locale
  persists across reload";
- 3 new `t4-i18n-acceptance.spec.ts` tests (vi default + `html lang`,
  immediate reversible switch + persistence, authenticated Teaching surfaces
  in both locales);
- 16 new `t4-visual-evidence.spec.ts` tests (see below).

Known pre-existing flake: one teacher-route suite test can fail under
parallel worker load on this machine — the T2-documented 390px client
exception, recorded in the T3 delivery record as not reproducing in a full
isolated run with no root-cause claim. It passes in isolation and in CI.
It is not attributed to T4 and no T4 change relies on it passing.

## 4. Both-locale visual evidence (intercepted)

`%TEMP%\opencode\t4-evidence\` (i.e.
`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t4-evidence\`) — **40 PNG
screenshots** produced by `t4-visual-evidence.spec.ts`:
**5 screens × 4 viewports × 2 locales**:

- screens: `teacher-home`, `my-assessments`, `create-form`, `login`,
  `student-join` (an earlier summary said "4 screens / 32 screenshots"; the
  verified directory content is 40/5);
- viewports: 1920×1080, 1440×900, 768×1024, 390×844;
- locales: `vi` (default, no cookie) and `en` (via `quizopia-locale=en`
  cookie);
- every capture asserts `documentElement.scrollWidth - clientWidth <= 1`
  before the full-page screenshot, so all 40 images double as
  no-horizontal-overflow evidence at both locales including 390px mobile.

## 5. Real-stack evidence (no interception)

Sanitized report: `C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t4-live-20261026\REPORT.md`
(with `browser-report.json`, `completion-report.json`, 19 sanitized
screenshots). Real Chromium with a production build of the T4 frontend
(Gateway URL baked at build time) against the persistent local runtime:
Identity (8081), Quiz (8082), Classroom (8083), Assessment (8084), Gateway
(8080), PostgreSQL/Redis/Mailpit. **No request interception, no auth
injection.** No credentials/tokens/locators/PII in the report or images.

- **Combined 64 assertions, all passing** (journeys J1–J9): primary run
  49/53; the 4 cut-off journeys re-run after restarting backend services via
  `scripts/local/windows_runtime.py start` (no provisioning changes) at 15/15.
- **Aborted-run explanation:** the primary run's 4 misses were caused by the
  session being aborted while backend services were stopped with it — session
  cutoff, not product failures; all 4 passed unchanged in the completion run.
- Verified behaviors: vi default with real login (`html lang="vi"`);
  immediate reversible UI switch with no reload/form loss; cookie persistence
  across reload; real Publications/editor rows in both locales with
  server-authored titles and **Quiz Markdown byte-identical across a locale
  switch**; **mid-form locale switch preserving entered title and schedule
  values** (review then cancelled — no business record created); read-only
  student result rendering in both locales with locale-formatted score digits
  (`100,00%` vs `100.00%`); mobile 390×844 directory without horizontal
  overflow and with en preference persisting across mobile reload.
- Gateway paths exercised for real: `POST /api/auth/login`,
  `POST /api/auth/refresh`, `GET /api/auth/me`, `GET /api/quizzes`,
  `GET /api/assessments/publications`,
  `GET /api/assessments/attempts/{id}/result`, `GET .../aggregate-score`,
  quiz draft/version endpoints.

## 6. Limitations and unchanged gates

- This is **local acceptance on the persistent development runtime, not
  production acceptance**; the inherited release **NO-GO / production
  activation NOT AUTHORIZED** is unchanged.
- **BR-01 remains PENDING** ([Leader decision
  memo](../decisions/teacher-self-attempt-br01-decision-memo.md)): T4 makes
  **no** teacher self-attempt policy change and adds no Preview/Test attempt
  path.
- The completion run re-entered the Teaching workspace gate after reloads;
  workspace persona state is in-memory by design (documented T2 behavior).
- J6 created no new business record (review cancelled); no new
  Classroom/publication fixtures were provisioned for it.
- Interception-based suites (visual evidence, acceptance) prove browser
  contract only; real-stack evidence covers the journeys listed above, not
  every screen, and does not replace LD-05 or other inherited gates.
- `format:check` finding in section 3 must be resolved before review.
