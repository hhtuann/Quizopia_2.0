# UI/UX Round 2 — Verification Record

> **Branch:** `feature/frontend-attempt-workspace-polish`
> **Baseline:** `0a64a1286ab5d2a04c75e9f4f02b6b4f976bb7f2` (merged PR #113);
> amendment UX-10/UX-11 landed first at `1815df9`, Round 2 tasks UX-07…09 on
> top. PR: **#114** (open, **not merged**). Production **NO-GO** and
> **BR-01 PENDING** unchanged.

## UX-07 — Student Attempt Workspace Redesign — DONE

Focused workspace replacing the ApplicationShell attempt layout (join/result/
review keep the standard shell; only the active attempt is focused, mirroring
the Quiz editor exception):

- **Dedicated sticky header** (`attempt-workspace-header.tsx`): shared editor
  chrome geometry (`APPLICATION_HEADER_GEOMETRY`), brand mark, guarded **Back
  to app** link (existing unsaved-work interceptor), **LanguageMenu**, h1
  title (localized `Student assessment` fallback when the attempt payload
  carries no title — never fabricated), state badge/ordinal/attempt id,
  honest **informational** countdown (only when an effective deadline exists —
  unlimited/terminal attempts render no timer), aggregate autosave status +
  answered count (fixed-width, `tabular-nums`, `aria-live` — no layout shift),
  realtime status, compact Submit/Abandon with the exact previous disable
  rules. No account menu (outside the current security contract).
- **Vertical question list** (`attempt-question-list.tsx`): all questions as
  cards in canonical order, existing answer components untouched, stable
  identity via canonical `question.number` (no index keys — no remounts on
  scroll or locale switch, regression-tested), `max-w-6xl` readable column.
- **Right-side sticky navigator** (`attempt-question-navigator.tsx`):
  desktop `lg+` sticky box grid; below `lg` the same boxes collapse behind an
  `aria-expanded` toggle. Boxes use stable question numbers, jump with header
  clearance (scroll-margin), show answered/unanswered + save-phase state
  (never implying persistence while saving/failed), current-question
  indicator via IntersectionObserver (passive scroll does not fire monitoring
  events). Accessible labels: `Question {n}, {status}`.
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

`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\ux2-live\report.json` (+2
sanitized 390px screenshots) — **18/18 assertions**: login page has no
combobox and LanguageMenu switches en; real teacher journey (fresh disposable
quiz + published version + PUBLIC publication **201** + **OPEN** 200 +
locator); student joins via real entry (**200**), starts a real attempt into
the **new workspace**: no application navbar, all cards in canonical order,
navigator operable at 390px (collapsed toggle expands), autosave ("Đã lưu"),
**locale switch via header menu preserves answers**, no horizontal overflow
at 390px, submit **200** exactly once, authoritative grading **100.00%**,
result renders. Existing business data untouched; new fixtures recorded.

### Amendment tasks (already on this branch, commit `1815df9`)

- **UX-10** dropdown chevron: rendered-geometry fix + regression (see PR #114
  description and commit message).
- **UX-11** student names: **BLOCKED FOR LEADER CONTRACT APPROVAL** —
  architecture package at
  `docs/decisions/ux11-student-names-architecture-package.md`.

## Design-system note

DESIGN.md §9.6 documents the Quiz editor as the focused-shell exception; the
Student Attempt workspace is now a **second intentional exception** with the
same guarantees (explicit back navigation, unsaved-work protection, compact
chrome, shared brand/header geometry).

## Known limitations

- Mobile navigator panel is non-sticky (top of main) — a sticky jump bar
  under the wrapping mobile header needs dynamic offset math; deferred.
- The terminal-attempt view intentionally renders no navigator (nothing to
  navigate); verified on the real terminal attempt.
- UX-09 browser coverage is intercepted-contract (real editor, mocked auth) —
  consistent with all existing editor e2e; no real-stack editor journey was
  added in this round.
- UX-11 remains blocked pending Leader contract approval (no names
  fabricated; ids still authoritative).
