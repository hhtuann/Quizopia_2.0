# UI/UX Round 2 — Verification Record

> **Branch:** `feature/frontend-attempt-workspace-polish`
> **Baseline:** `0a64a1286ab5d2a04c75e9f4f02b6b4f976bb7f2` (merged PR #113);
> amendment UX-10/UX-11 landed first at `1815df9`, Round 2 tasks UX-07…09 on
> top, then the seven Leader-correction items (Tasks A–G) on top of those.
> PR: **#114** (open, **not merged**). Production **NO-GO** unchanged; BR-01
> was resolved 2026-10-29 as policy-only (see the BR-01 decision memo).

## Leader correction round (Tasks A–G) — all seven DONE

**A — Language controls removed from both focused headers.** The Teacher
editor and Student Attempt headers no longer render `LanguageMenu` (or any
language selector). Language switching remains available through the
authenticated account menu (application pages) and the public/auth
`LanguageMenu`; nothing was hidden with CSS. i18n SSR/hydration, cookie
persistence and state preservation are unchanged.

**B — Icon-only back button.** New shared `components/ui/focused-back-button.tsx`
(44×44, chevron-only, `Button variant="secondary"` — the exact editor
recipe). The attempt header and the editor both use it; the attempt back
keeps its unresolved-mutation confirmation guard. Localized `aria-label`
retained.

**C — Header geometry parity.** Both focused headers now share the same
structure: logo (size-9 mark, `xl` wordmark), shared icon-only back, `h-7`
separator, `min-w-[16rem]` title block. Regression spec measures rendered
rectangles on both pages at 1440 and asserts equal back-button size, brand
mark size and header height (±1–2px), with zero language controls.

**D — Compact countdown.** The timer renders only `00:29:49`-style tabular
digits in a fixed-height container; the visible caption texts are removed
(accessible name kept via `aria-label` + `role="timer"`). Unlimited attempts
still render no timer. Server-time authority is untouched.

**E — Exam title + student name; technical metadata removed.** The visible
header now shows: the **authorized publication title** threaded from the
student's own entry preview through in-memory ephemeral context (the smallest
existing-source integration; `AttemptView` carries no title and no new API
was invented — hard reload of a deep-linked attempt honestly falls back to
the short generic "Assessment"/"Bài kiểm tra"), and the **authenticated
student's own username** as `Candidate: {name}` (from the session `/me`
user; never another student's identity). ACTIVE badge, attempt UUID,
ordinal and revision are gone from the header (kept internally for API
calls).

**F — Wide layout + DESIGN.md rule.** The attempt workspace dropped the
`mx-auto max-w-6xl` centered column for a fluid `lg:grid-cols-[minmax(0,1fr)_18rem]`
with design-token gutters (`px-4 sm:px-6 lg:px-8`); question stems keep a
comfortable in-card `max-w-3xl`. DESIGN.md §8.1.1 now carries the
authoritative dense-workspace width rule. Regression spec asserts at 1920:
content >1500px, gutters ≤40px, navigator at the right, cards >1300px.

**G — Navigator redesign + flags.** Numbered boxes only: unanswered neutral,
answered green-bordered, **flagged amber (precedence over answered)**,
current question a restrained `ring-foreground/40` + `aria-current` that
never overrides state colors — no checkmarks, no solid purple, no legend.
Per-card accessible flag toggle (`aria-pressed`, localized, keyboard/touch)
flips the box instantly; flags are client-side React state only (no backend
call, no autosave trigger, nothing persisted to browser storage — honestly
documented: flags do not survive reload). Save-phase honesty labels are
unchanged.

## UX-07 — Student Attempt Workspace Redesign — DONE (superseded header details in the correction round above)

Focused workspace replacing the ApplicationShell attempt layout (join/result/
review keep the standard shell; only the active attempt is focused, mirroring
the Quiz editor exception):

- **Dedicated sticky header** (`attempt-workspace-header.tsx`): shared editor
  chrome geometry (`APPLICATION_HEADER_GEOMETRY`), brand mark, guarded
  icon-only **Back to app** control, exam title + candidate line, compact
  tabular countdown (deadline-only), aggregate autosave status + answered
  count (fixed-width, `aria-live` — no layout shift), realtime status,
  compact Submit/Abandon with the exact previous disable rules. No account or
  language controls (correction Tasks A/E).
- **Vertical question list** (`attempt-question-list.tsx`): all questions as
  cards in canonical order, existing answer components untouched, stable
  identity via canonical `question.number` (no index keys — no remounts on
  scroll or locale switch, regression-tested), fluid dominant column with
  in-card prose `max-w-3xl`; per-card flag toggle (correction Task G).
- **Right-side sticky navigator** (`attempt-question-navigator.tsx`):
  desktop `lg+` sticky box grid; below `lg` the same boxes collapse behind an
  `aria-expanded` toggle. Boxes use stable question numbers, jump with header
  clearance (scroll-margin), show unanswered/answered/flagged/save-phase
  states (never implying persistence while saving/failed; flag takes visual
  precedence), current-question indicator via a neutral ring +
  IntersectionObserver (passive scroll does not fire monitoring events).
  Accessible labels: `Question {n}, {status}` including the flag state.
- **Lifecycle unchanged**: submit confirmation/exactly-once, pending/failed
  save guards, expiry, abandon semantics, reconciliation, result visibility,
  realtime REST resync, proctoring gating and monitoring lifecycle hooks all
  preserved (5/5 w3-integration monitoring e2e pass through the new
  workspace).

## UX-08 — No standalone language dropdowns — DONE

Audit (before → after): landing `app/page.tsx`, auth layout, and the focused
Quiz editor header each rendered `LanguageSwitcher` (a `<select>`); the
authenticated app header was already account-menu based (Round 1). All three
now use the new shared **`LanguageMenu`** popover (globe + current-locale
trigger, `menuitemradio` options with checked indicator, arrow-key/Escape/
outside-click contract, 44px targets). `language-switcher.tsx` is **deleted**
(zero imports remain repo-wide). `<select>` audit: exactly one remains — the
Publication state filter inside the shared `SelectInput` (a form filter, not
a language dropdown). vi default, English option, immediate/persistent
switching, cookie persistence, SSR hydration, `html lang` and form/attempt
state preservation all re-verified (t4-i18n e2e + RTL).

## UX-09 — NUMERIC_FILL autocomplete — DONE

Grammar-fact (spec-verified): the canonical marker is exactly `Đáp án:`
(column 1, outside fenced code; the numeric token follows on the same line).
The grammar-aware engine (`quiz-markdown.ts` shared by both editors) already
had a NUMERIC_FILL suggestion branch; it was unreachable on an empty fragment
because `singlePrefixSuggestion` rejected empty input. The fix allows the
empty-fragment offer gated on `stemHasContent` — insertion, caret, undo
(single atomic edit), keyboard selection, Escape dismissal, duplicate-marker
prevention (`answerPresent`) and prose/fenced/invalid-state gating are all
inherited from the existing SINGLE_CHOICE path. No parser/backend/grammar
change; no sample answers inserted. 11 focused unit tests + 1 additive
Playwright editor test (byte-exact insert + caret at end).

## Verification summary

| Gate                                                 | Result                                                                                                                                                |
| ---------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `pnpm test` (Vitest)                                 | **850/850** (79 files; +6 workspace, +11 autocomplete, harness adaptations)                                                                           |
| `pnpm typecheck` / `lint` / `format:check` / `build` | PASS                                                                                                                                                  |
| Playwright full suite                                | **140 passed + 2 known parallel-load flakes** (teacher-route header test, scrollbar gutter test — both pass in isolation and are documented since T2) |

### Real-stack evidence (persistent local runtime, no interception)

**Correction round (Tasks A–G)**: `C:\Users\GIGABYTE\AppData\Local\Temp\opencode\ux3-live\report.json`
(+3 sanitized screenshots) — **18/18 assertions** on fresh disposable
fixtures: real create/OPEN/entry/start; corrected header shows the
**authorized preview title**, the **candidate username line**, **no
ACTIVE/UUID/ordinal/revision**, **time-only timer**, **no language control**,
and a **44×44 icon-only back**; at 1920 the workspace is **fluid** (content

> 1500px, gutters ≤40px, navigator at the right); autosave "Đã lưu"; flag
> toggle pressed + amber navigator box; submit **200 exactly once**,
> authoritative **100.00%** grading; result reached.

**Round 2 base**: `C:\Users\GIGABYTE\AppData\Local\Temp\opencode\ux2-live\report.json`
(+2 sanitized 390px screenshots) — **18/18 assertions** through the new
workspace on the persistent local stack. (Superseded header details: the
workspace language menu verified there has since been removed by correction
Task A.) Existing business data untouched; fixtures recorded.

### Amendment tasks (already on this branch, commit `1815df9`)

- **UX-10** dropdown chevron: rendered-geometry fix + regression (see PR #114
  description and commit message).
- **UX-11** student names: **BLOCKED FOR LEADER CONTRACT APPROVAL** —
  architecture package at
  `docs/decisions/ux11-student-names-architecture-package.md`.

### Correction-round verification summary

| Gate                                                 | Result                                                                                                                                                                                                                                                             |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `pnpm test` (Vitest)                                 | **853/853** (79 files; +3 flags/title/header tests, updated expectations)                                                                                                                                                                                          |
| `pnpm typecheck` / `lint` / `format:check` / `build` | PASS                                                                                                                                                                                                                                                               |
| Playwright full suite                                | **145 passed + 1 retry-passed known flake** (documented teacher-route parallel-load flake); new `attempt-workspace-correction.spec.ts` (5 tests: header geometry parity, 1920 width utilization, flags, answered/current boxes, vi+en × 4-viewport overflow sweep) |
| Regression status UX-09 / UX-10                      | UX-09 autocomplete suite green; UX-10 dropdown-chevron (directory select) green                                                                                                                                                                                    |
| Real-stack                                           | 18/18 (above)                                                                                                                                                                                                                                                      |

## Design-system note

DESIGN.md §9.6 documents the Quiz editor as the focused-shell exception; the
Student Attempt workspace is now a **second intentional exception** with the
same guarantees (explicit back navigation, unsaved-work protection, compact
chrome, shared brand/header geometry). DESIGN.md §8.1.1 (added by correction
Task F) is the authoritative dense-workspace width rule.

## Follow-up corrections (same branch)

- **Submit/Abandon button size**: the workspace header actions now use the
  shared `touch` size with `px-3 sm:px-5` — the exact geometry of the
  teacher editor's Publish control.
- **Timer alignment**: the countdown box centers its tabular digits
  (`justify-center`) and matches navbar control height (`min-h-11`), fixing
  the left-shifted digits inside the fixed-width box.
- **Workspace persistence across reload (F5)**: the active Learning/Teaching
  persona now persists per-account in `localStorage`
  (`quizopia-workspace:{userId}`) — a UI preference exactly like the locale
  cookie: no credentials/roles/tokens stored, never an authorization fact.
  Every restore is validated against the CURRENT authoritative roles via
  `reconcileWorkspace` (a stored TEACHING persona with a revoked TEACHER role
  falls back to the role default); an in-session token refresh keeps the live
  persona even when storage is stale; preferences are per-account and never
  leak across identities. Covered by 6 unit tests
  (`workspace-preference.test.ts`). e2e helpers updated: `switchToTeaching`
  now returns early when the persisted persona already renders a Teaching
  surface, and `pinEnglishUi` only drives the user-menu switch when the menu
  exists (focused workspaces have none).

## Known limitations

- **Flags are session-only**: React state per mount; no backend endpoint and
  nothing persisted to browser storage (per the task's safety constraints).
  A hard reload clears flags — documented honestly in the header comment.
- **Attempt title fallback**: the authorized title is threaded from the
  student's own entry preview via ephemeral session memory. A hard reload of
  a deep-linked ACTIVE attempt (no prior entry in the session) falls back to
  the short generic "Assessment"/"Bài kiểm tra" — honest, never fabricated.
- Mobile navigator panel is non-sticky (top of main) — a sticky jump bar
  under the wrapping mobile header needs dynamic offset math; deferred.
- The terminal-attempt view intentionally renders no navigator (nothing to
  navigate); verified on the real terminal attempt.
- UX-09 browser coverage is intercepted-contract (real editor, mocked auth) —
  consistent with all existing editor e2e; no real-stack editor journey was
  added in this round.
- UX-11 remains blocked pending Leader contract approval (no names
  fabricated; ids still authoritative).
