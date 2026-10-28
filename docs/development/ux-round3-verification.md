# UI/UX Round 3 — Verification Record (FB-009 … FB-015)

> **Branch:** `feature/ux-round3-assessment-workspace` (baseline: merged
> develop `0a64a12`, PR #113). FB-014 lives on its own branch
> `feature/ux-round3-quiz-create-on-save` (same baseline; no dependency on
> unmerged PR #114). Tracking issue: **#115**. Production **NO-GO** and
> **BR-01 PENDING** unchanged; PR #114 remains open and untouched.

## Status summary

| Item                                    | Status                                   | Where                                                        |
| --------------------------------------- | ---------------------------------------- | ------------------------------------------------------------ |
| FB-009 results-first publication detail | **DONE**                                 | this branch                                                  |
| FB-010 compact private locator          | **DONE**                                 | this branch                                                  |
| FB-011 action hierarchy                 | **DONE**                                 | this branch                                                  |
| FB-012 student names in Teacher Results | **BLOCKED for Leader contract approval** | `docs/decisions/fb012-student-names-architecture-package.md` |
| FB-013 global microcopy cleanup         | **DONE**                                 | this branch                                                  |
| FB-014 create-on-first-save             | **DONE**                                 | `feature/ux-round3-quiz-create-on-save`                      |
| FB-015 quiz + dependent deletion        | **DESIGN READY / APPROVAL PENDING**      | `docs/decisions/fb015-quiz-deletion-architecture-package.md` |

## FB-009 — Results-first publication detail — DONE

- OPEN/CLOSED detail is now a full-width workspace:
  `lg:grid-cols-[minmax(0,1fr)_20rem]` — dominant main column (closure
  notice, schedule extension when open, **embedded student results**) and a
  sticky right sidebar (compact configuration card + actions). DRAFT remains
  management-first full width.
- The old full-width expanded configuration summary, "OPEN finalizes the
  snapshot…" paragraph and the header UUID line are gone from ordinary
  presentation; the publication id survives only as a small support line in
  the sidebar.
- **Participation metrics honesty:** no authoritative teacher-side aggregate
  exists (only the student `aggregate-score` endpoint). The workspace shows
  only the verified loaded-rows count inside the embedded results section —
  no invented totals/averages/coverage. **Missing contract documented:** a
  teacher-side participation aggregate (e.g. attempts-open/submitted counts
  per publication) would need an Assessment API addition; deliberately not
  invented.
- Monitoring: no realtime/proctoring/camera features enabled; the sidebar
  reserves the organization (Actions card) for future gated capabilities.

## FB-010 — Compact private locator — DONE

`private-publication-locator.tsx` rewritten: one-line masked field
(`••••••••••••`) + icon-only eye toggle (`aria-pressed`) + icon-only copy
button (44px targets, localized labels, polite clipboard feedback). Hidden by
default; never logged; no URLs/telemetry; owner-only server authorization
unchanged. The old informational panel is gone.

## FB-011 — Action hierarchy — DONE

- OPEN sidebar: "Extend participation schedule" (toggle; the wide extension
  form opens in the main column), "Manage delayed closure", destructive-
  styled "Review and CLOSE" — visually distinct from ordinary actions.
- DRAFT: "Edit configuration" / "Open assessment" retained management-first.
- CLOSED: results embedded + closure link; **duplicate "View attempt
  results" buttons removed** from OPEN and CLOSED blocks (results are
  embedded); the standalone `/results` route is preserved and unchanged.
- Revision checks, confirmations, idempotency keys, uncertain-outcome
  reconciliation ("Check now" / "Retry") all preserved with new compact
  labels.

## FB-013 — Microcopy — DONE

~150 keys rewritten across all surfaces (landing/auth, app home, library,
editor/publish/history, assessments, classroom selector, closures, teacher
results, student attempt/result/review, notifications, realtime, monitoring,
API-failure mappers): backend terms (authoritative/snapshot/metadata/
idempotency/service) removed from user-facing copy; raw UTC sub-spans and
decorative UUID rows removed; one shared "Your session has ended. Please
sign in again." key replaces 9 verbose variants; Vietnamese values rewritten
in plain language in parallel. Essential warnings (privacy, deadlines,
destructive actions, cutoff guidance) kept with the same meaning, shorter.
DESIGN.md §27.1 "Editorial voice" codifies the rules.

## FB-014 — Create on first save — DONE (separate branch)

`5e38005` on `feature/ux-round3-quiz-create-on-save`: CreateQuizPage is a
thin wrapper over the shared authoring workspace in unsaved mode (zero
network calls); first explicit Save validates ≥1 grammar-recognized question
then fires exactly one `createDraft`, `router.replace`s to the persisted
editor URL; later saves update the same quiz; empty leave never POSTs and
never prompts; double-click/StrictMode guarded; ambiguous create failures
surface an honest duplicate-risk warning with explicit retry (no blind
replay). Backend gap documented: `POST /api/quizzes` has no Idempotency-Key
contract (smallest compatible addition proposed in code + report; not
invented client-side). 834 vitest + 18/18 + 11/11 targeted playwright green
in the isolated worktree.

## Verification (this branch)

| Gate                                                 | Result                                                                            |
| ---------------------------------------------------- | --------------------------------------------------------------------------------- |
| `pnpm test`                                          | **829/829** (count-neutral rewrite)                                               |
| `pnpm typecheck` / `lint` / `format:check` / `build` | PASS                                                                              |
| Playwright full mocked suite                         | **133/133**                                                                       |
| Responsive vi+en sweep                               | detail workspace overflow-free at 1920/1440/768/390 (visual suites + spot checks) |

Real-stack: covered for FB-014 flows via existing journeys in CI-pending
state; per-item real evidence recorded in the PR description after CI.

## Known limitations

- Teacher-side participation aggregate API absent (documented above).
- FB-012/FB-015 blocked as stated; their packages list the exact Leader
  decisions required.
- Real-e2e/w3a real suites had copy assertions updated but need the real
  runtime (as before).
