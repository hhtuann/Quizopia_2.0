# T4 — Vietnamese/English i18n & Final UX Acceptance

> **Status: KICKOFF ONLY — implementation, validation and independent review outstanding.**
> **Baseline:** `develop` at `f5a2196d457aa432a3fecc49543dbeb0af59ecec` (PR #111 merge).
> **Branch:** `feature/frontend-vi-en-i18n-acceptance`.
> **Goal:** default Vietnamese `vi-VN`, optional English `en`, complete existing frontend localization and final UX/regression acceptance.

## Source-of-truth and scope

Read `AGENTS.md`, `CLAUDE.md`, `DESIGN.md`, `docs/development/project-status.md`, `docs/development/mvp-plan.md`, relevant product/architecture/specifications, T1/T2/T3 evidence, and `docs/decisions/teacher-self-attempt-br01-decision-memo.md`.

- Localize ALL existing public/authenticated pages and meaningful application states: landing, login/register/verify-email, app home, Quiz Library/editor/publish/version/history, My Assessments/Publication create/edit/detail/results/closure, Classroom selector, Student join/attempt/result/review, notifications, Teacher/Student monitoring, workspace selection/transition, dialogs, loading/empty/error, accessibility/ARIA and validation messages.
- Default application content language to `vi-VN` for first-time visitors. Provide discoverable, keyboard-accessible language switch `Tiếng Việt / English` on public landing, auth screens and logged-in shell/account menu as appropriate. Switching should apply without a full reload, preserving current work, route, role, token and form state.
- Persist choice across navigation, refresh, login/logout (language choice is harmless user preference, not a token or identity claim). SSR, hydration, `html lang`, document title/metadata and initial presentation should avoid incorrect-language flashes/hydration mismatch.
- Store translations centrally in typed dictionaries. Use consistent terminology for Quiz, QuizVersion, Publication, Assessment, Classroom, Attempt, DRAFT/OPEN/CLOSED, Learning/Teaching workspace, score/review/closure and monitoring. Never translate protocol enum values or machine-readable API schemas.
- Localize text from frontend/API error handling, toasts, alerts, confirmation dialogs, tooltips, screen-reader labels, aria descriptions, empty/loading statuses, route-guard notices, form validation and result formatting. Map known structured backend codes to localized user-facing content without changing backend codes; do not expose secrets or raw unsafe exception strings.
- Use `Intl.DateTimeFormat`, `Intl.NumberFormat`, pluralization and explicit locale for display. Preserve authoritative UTC instants, microseconds, deadlines, numeric grammar, answer grading, legal policies and input value types; never use locale formatting in wire payloads.
- Teacher-authored Quiz markdown/title/description, classroom/assessment names, user identities, student answers, user-supplied terms, and source snapshot content must remain exactly as authored. Translation concerns UI chrome only. Never machine-translate immutable QuizVersions.
- Preserve T1/T2/T3 features, 2500ms committed workspace transition baseline, default OFF security/release feature flags and `BR-01` pending product decision. An unrelated Windows local working-tree edit from 2500ms to 1500ms was intentionally excluded from T3; do not overwrite, stage or normalize that local change.

## Baseline inspection (source-verified on merge SHA)

- Frontend Next.js 16 / React 19; `frontend/app/layout.tsx` renders `<html lang="en">` and English metadata. Fonts already load a Vietnamese subset.
- `frontend/package.json` has no i18n/localization library installed.
- Next.js app page inventory includes 23 pages: public landing, three authentication pages and all currently implemented `/app` workspace pages.
- Frontend feature areas are `assessments`, `attempts`, `auth`, `classrooms`, `closures`, `notifications`, `proctoring`, `quizzes`, `realtime`, `teacher-results`.
- Existing controls include the T2 2.5-second workspace overlay and the T3 My Assessments and Classroom selector; translated copy must cover these.
- Current `docs/development/project-status.md` still describes T3 as unmerged. Update that checkpoint on the T4 branch to record PR #111 merge, without rewriting historical release records.

## Implementation phases (one branch, one PR)

1. Create a route/component/string inventory including dynamic/exception states and identify UI versus authored content. Produce a complete localization coverage matrix.
2. Choose a lightweight typed i18n architecture compatible with Next.js SSR/hydration and React client controls; justify any dependency. Add provider, locale selection/persistence, centralized dictionaries, formatting helpers, metadata/lang behavior and extraction/coverage checks.
3. Localize shell, landing and auth; Quiz and Assessment/Teacher/Classroom flows; Student Attempt/Results/Notifications/Monitoring/Realtime; shared components and error/validation mapping.
4. Add stable bi-language tests for language persistence, first-visit Vietnamese, English opt-in, no role/route/form reset on switch, SSR/hydration consistency, date/time/local UTC correctness, accessibility, and data never translated. Keep localization out of API payloads.
5. Run full `pnpm test`, `pnpm typecheck`, `pnpm lint`, `pnpm format:check`, `pnpm build`, `pnpm test:e2e`, and available real-stack browser checks on the final committed tree; follow baseline Maven/security CI if any backend-adjacent files need touching (prefer none).
6. Capture actual browser screenshots at 1920/1440/768/390 widths in both languages for representative teacher/student/auth/Quiz/Assessment/notifications/monitoring states. Record automated DOM/geometry assertions separately from manual visual inspection.
7. Update coverage matrix, project status, pending BR-01 list, accepted UX exceptions, regression evidence and deployment NO-GO; commit/push, open exactly one T4 PR targeting `develop`, verify CI, **do not merge without leader approval**.

## Acceptance criteria

- All routes and user-visible states are covered in both languages, with no unintentional English UI remnants when Vietnamese is active and no untranslated Vietnamese UI remnants in English.
- Default `vi-VN` works for anonymous first visit; English choice persists across navigation/refresh and sign-in/out. `html lang` and semantic labels match the active language.
- No hydration console errors, layout clipping or overflow, inaccessible language controls, unexpected navigation/reset, lost drafts/answers, or invalid locale-dependent UTC values.
- QuizVersion content, user-authored data, results confidentiality, backend contracts and release toggles are unchanged.
- Full test/CI evidence and route-by-route screenshots documented honestly with known gaps; no claim of human visual approval until someone actually inspected captures.
- BR-01 is not silently implemented or accepted; remains a separate written product decision.

## Safeguards

Do not touch untracked `scripts/local/*`, `docs/development/windows-local-runtime.md`, ignored local secrets, live database state or private credentials. No destructive Git reset, clean or blanket staging. Do not deploy or enable production features. Preserve the unrelated 1500ms local overlay edit uncommitted; test final T4 branch in an isolated checkout if needed.

**Deliverable:** T4 frontend implementation and complete acceptance pack, with a single unmerged PR until independent review.