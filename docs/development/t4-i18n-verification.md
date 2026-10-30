# T4 i18n — Verification Record

> **Branch:** `feature/frontend-i18n-final-ux-acceptance` (based on reviewed T3
> merge `f5a2196`, PR #111)
> **Date:** 2026-10-26 (corrected after independent Leader review)
> **Status:** implementation committed to PR **#112** (open, **not merged**),
> awaiting Leader re-review. Production release **NO-GO / NOT AUTHORIZED**
> unchanged.
> **Developer reference:** [`frontend/lib/i18n/README.md`](../../frontend/lib/i18n/README.md)

T4 delivers the bilingual (vi default / en) presentation layer for the entire
frontend: typed English-key catalogs with Vietnamese values, a cookie-backed
locale preference, server-resolved initial locale, `Intl` formatting, the
visible language switcher, and localized application/auth/landing shells with
strict state-preservation guarantees. **No backend, contract, policy or
`DESIGN.md` change is part of T4.**

## 0. Correction summary (Leader review of 2026-10-26)

The independent Leader review found three blocking gaps against head
`5b3378c05fd193d4403c550e177304ee82bc3a39`. All three are resolved by this
correction (final SHA recorded in section 8):

1. **Real complete user journey** — executed end-to-end on the real runtime:
   **18/18 assertions** (section 5.1), Teacher login → Quiz draft → publish
   immutable QuizVersion → create + OPEN PUBLIC assessment → Student join →
   answers → autosave → submit → authoritative 100.00% grading → Student
   result in **both locales** → Teacher results in Vietnamese.
2. **Visual sign-off record** — an explicit, classified measurement-based
   visual review of key components at all four viewports in both locales
   (section 6): 56 checks pass; 6 initial minor findings were **corrected in
   code** (touch-target padding) and the re-audit passes with **0 findings**.
3. **Documentation accuracy** — this record now reflects the committed SHA,
   PR state, final green format checks and CI links (sections 7–8).

## 1. Implementation scope (files by domain)

Committed to the T4 branch (PR #112). Grouped by owning area:

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
- **Correction (visual review outcome):** vertical touch-target padding on
  inline primary links — `auth-footer-link.tsx` and the
  "Return to application" links in `publication-pages`, `closure-pages`,
  `notification-pages`, `teacher-results-pages` (no logic change).
- **Tests (new):** catalog integrity + per-domain i18n RTL suites
  (`features/**/*-i18n.test.tsx`, `quiz-authoring-catalog.test.ts`,
  `public-auth-localization.test.tsx`), e2e
  `tests/e2e/t4-i18n-acceptance.spec.ts` and
  `tests/e2e/t4-visual-evidence.spec.ts`; existing e2e suites updated for the
  en-pinning cookie pattern (plus two new state-preservation e2e tests, below).

Unrelated working-tree content is **not** T4 and must not be reviewed as
such: untracked `scripts/local/windows_*` /
`scripts/local/tests/test_windows_runtime.py` /
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

## 3. Local check evidence (final state, after correction)

| Check | Result |
| --- | --- |
| `pnpm test` (Vitest) | **804/804 passed, 77 files** (re-run after correction) |
| `pnpm typecheck` (`tsc --noEmit`) | PASS |
| `pnpm lint` (ESLint) | PASS |
| `pnpm build` (production Next.js) | PASS |
| `pnpm format:check` (Prettier) | **PASS** — the two new T4 e2e specs were
  formatted (`prettier --write`) before the implementation commit; the
  earlier FAIL recorded in the pre-correction draft of this document is
  resolved and stays green after the correction commit. |

### Playwright (intercepted browser-contract suite)

**130 tests in 18 files**, all passing locally and in CI (section 8):

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
- 16 new `t4-visual-evidence.spec.ts` tests (see section 4).

Known pre-existing flake: one teacher-route suite test can fail under
parallel worker load on this machine (documented since T2); it passes in
isolation and in CI and is not attributed to T4.

## 4. Both-locale visual evidence (intercepted)

`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t4-evidence\` — **40 PNG
screenshots** produced by `t4-visual-evidence.spec.ts`:
**5 screens × 4 viewports × 2 locales** (teacher-home, my-assessments,
create-form, login, student-join; 1920×1080, 1440×900, 768×1024, 390×844; vi
default + en cookie). Every capture asserts no document-level horizontal
overflow before the screenshot.

## 5. Real-stack evidence (no interception)

All real-stack runs used real Chromium with a production build of the T4
frontend (Gateway URL baked at build time; isolated temp checkout at the
reviewed head) against the persistent local runtime: Identity (8081), Quiz
(8082), Classroom (8083), Assessment (8084), Gateway (8080),
PostgreSQL/Redis/Mailpit. **No request interception, no auth injection.** No
credentials/tokens/locators/PII appear in any report or screenshot.

### 5.1 Full complete user journey (Leader-correction run) — 18/18 PASS

Sanitized machine report:
`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t4-live-20261026\journey-report.json`
(+ `REPORT.md` section updated). Sequence and bilingual checkpoints:

| # | Step (real services) | Locale checkpoint |
| --- | --- | --- |
| 1 | Teacher logs in through real Gateway | **vi default** (`html lang="vi"` asserted) |
| 2 | Teacher switches language via visible control | **en**, in place |
| 3 | Quiz draft created and saved with authored Vietnamese-grammar Markdown (`Câu/Đáp án` markers intact), en chrome | en |
| 4 | Immutable **QuizVersion published** (real Quiz service) | en |
| 5 | **PUBLIC assessment DRAFT created** (real POST → **201**) | en |
| 6 | Publication **OPENed** (real POST /open → 200) | en |
| 7 | Owner locator revealed (43 chars; never logged) | en |
| 8 | Student logs in (separate real session) | **vi default** asserted |
| 9 | Student enters via private locator (real POST /entry → 200) | vi |
| 10 | Attempt started (real POST /attempts/start) | vi |
| 11 | Q1 answered; **autosave persisted with Vietnamese status label ("Đã lưu")** | vi |
| 12 | **Mid-attempt vi→en switch**: saved answer + attempt state preserved | vi → en |
| 13 | Q2–Q4 answered (all four question types) and autosaved ("Saved") | en |
| 14 | **Submit** accepted (real POST /submit → 200) | en |
| 15 | **Authoritative grading returned exactly `100.00%`** | en |
| 16 | Student result screen renders `100.00%` | en |
| 17 | Switch to Vietnamese: result renders locale-formatted **`100,00%`** | **vi** |
| 18 | **Teacher Results** shows the graded attempt row in Vietnamese chrome | **vi** |

Created disposable fixtures (retained for inspection, existing business data
untouched): 1 quiz + QuizVersion, 1 OPEN publication, 1 submitted attempt —
IDs recorded in `journey-report.json`.

### 5.2 Prior real-stack evidence (unchanged, still valid)

`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t4-live-20261026\REPORT.md`
(with `browser-report.json`, `completion-report.json`): 64 assertions (J1–J9)
covering vi-default login, immediate/persistent/reversible switching, owned
directory/detail both locales with verbatim server titles, editor Markdown
byte-identical across switches, mid-form retention (review cancelled),
read-only prior student result both locales, mobile persistence + overflow.

## 6. Independent visual review record (Leader correction)

Method (honest scope): a **measurement-based DOM/geometry audit** of the real
production build on the real runtime — per viewport × locale it measures
document overflow, per-element text clipping (excluding intentional
`sr-only`/`md:sr-only` screen-reader text), interactive-control inventory,
sub-32px control heights at touch widths, and Vietnamese diacritic glyph
coverage of the loaded UI font (`document.fonts.check` + width probe) —
alongside fresh screenshots. Machine record:
`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t4-visual-review\visual-review.json`
(+ per-screen PNGs). This is a tool-based review, **not** a human eyeball
sign-off; final human visual acceptance remains with the Leader.

Findings and classification:

| Finding (initial audit) | Class | Resolution |
| --- | --- | --- |
| `html lang` mismatch | — | none observed (0 cases) |
| Vietnamese glyph coverage missing | — | none observed (Plus Jakarta Sans vi subset loaded at every viewport/locale) |
| Document horizontal overflow | — | none observed on any screen/viewport/locale |
| Text clipping | — | none after excluding intentional `sr-only` labels |
| 19px inline links at 768/390 (auth footer "Create an account"/"Tạo tài khoản"; "Return to application") | **minor, corrected** | `py-2` padding added to both link families (5 files); full 804-test suite re-run green; re-audit: **56 passes / 0 findings** |

Baseline comparison: the corrected audit re-measures the same components the
T2/T3 visual baselines captured (hero/header/table/cards); no regression
against those baselines was observed, and the only deltas found (compact
links) were improved rather than regressed.

## 7. PR state and checks

- PR: [#112](https://github.com/hhtuann/Quizopia_2.0/pull/112) → `develop`,
  **open, not merged**.
- Implementation head at Leader review: `5b3378c05fd193d4403c550e177304ee82bc3a39`
  — CI on that head: [Frontend CI run 40282990255](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40282990255)
  **pass** (804 unit / 130 e2e), [repository validation run 40282990238](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40282990238)
  compose-config **pass** (full-validation skipped by workflow config).
- Correction commit (this change set): see final SHA in section 8; its CI
  results are recorded in section 8 after the run completes.

## 8. Final SHA / CI (after correction push)

- **Final implementation head:** `91893bc78ca00bb80c5117de67392af12b833948`
- CI on the final head: [Frontend CI run 40292436262](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40292436262)
  **pass** — 804 unit tests, 130 Playwright tests (run log); [repository
  validation run 40292436260](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40292436260)
  compose-config **pass**, full-validation skipped by workflow configuration.
  PR #112 remains **open, not merged**.

## 9. Limitations and unchanged gates

- This is **local acceptance on the persistent development runtime, not
  production acceptance**; the inherited release **NO-GO / production
  activation NOT AUTHORIZED** is unchanged.
- **BR-01 status at T4 time: PENDING**; it was resolved 2026-10-29 as policy-only
  ([Leader decision
  memo](../decisions/teacher-self-attempt-br01-decision-memo.md)): T4 makes
  **no** teacher self-attempt policy change and adds no Preview/Test attempt
  path. (The full-journey student is a distinct fixture account, not the
  owner.)
- Workspace persona state is in-memory by design (documented T2 behavior);
  real journeys re-enter the Teaching gate after reloads.
- Interception-based suites prove browser contract only; real-stack evidence
  covers the listed journeys, not every screen, and does not replace LD-05
  or other inherited release gates.
- The visual review is tool-based measurement + screenshots; human visual
  acceptance remains with the Leader.
- Real-time/proctoring surfaces were localized but not exercised in the real
  journey (their runtime gates remain default-off per release constraints).
