# Frontend i18n (T4)

Bilingual Vietnamese/English presentation layer for the Quizopia 2.0 frontend.
Vietnamese (`vi`) is the product default; English is the typed source-catalog
key language and the deterministic fallback.

This README is the developer-facing reference for the i18n architecture,
terminology rules, state-preservation contracts, and test patterns.
Task-level verification evidence lives in
[`docs/development/t4-i18n-verification.md`](../../docs/development/t4-i18n-verification.md).

## Architecture

### Typed English-key catalogs merged into one `TranslationKey` union

`lib/i18n/core.ts` merges five domain catalogs into a single frozen `messages`
object; `TranslationKey = keyof typeof messages`.

- The **key is the exact rendered English source text** (e.g.
  `"My Assessments"`). English rendering needs no lookup table; a missing key
  is a TypeScript compile error, not a runtime blank.
- The **value is Vietnamese presentation only** (e.g. `"Bài kiểm tra của tôi"`).
- Catalogs are split by domain, not by locale:

  | Catalog                  | Domain                                                 | Keys (verified 2026-10-26) |
  | ------------------------ | ------------------------------------------------------ | -------------------------: |
  | `messages/common.ts`     | shell, navigation, workspaces, landing, auth chrome    |                        166 |
  | `messages/authoring.ts`  | Quiz Library, editor, publish/version history          |                        123 |
  | `messages/assessment.ts` | teacher Publications, closures, results, notifications |                        459 |
  | `messages/student.ts`    | student attempts/join/result/review, realtime status   |                        195 |
  | `messages/monitoring.ts` | proctoring consent/camera and teacher monitoring       |                        126 |

- `{placeholder}` interpolation is shared by `translate`/`translateMessage`;
  unknown placeholders are preserved verbatim.

### Locale model

- `Locale = "vi" | "en"`; `DEFAULT_LOCALE = "vi"`.
- `resolveLocale()` accepts only `vi`/`en` and otherwise falls back to `vi`.
- `localeTags`: `vi → "vi-VN"`, `en → "en-GB"` (used by all `Intl` formatting).

### Cookie — preference only

`LOCALE_COOKIE = "quizopia-locale"`, written by `I18nProvider.setLocale`:

```
quizopia-locale=vi|en; Path=/; Max-Age=31536000; SameSite=Lax[; Secure on https]
```

It is a **UI preference cookie only** — no credentials, tokens, account or
role data is persisted, and it is never sent as an authorization fact.

### Server-resolved initial locale (no flash, no URL change)

1. `lib/i18n/server.ts#getServerLocale()` reads the cookie in React server
   components.
2. The root server layout (`app/layout.tsx`) awaits it once, renders
   `<html lang={locale}>`, localizes metadata, and passes the value as
   `initialLocale` into `AppProviders` → `I18nProvider`.
3. The provider hydrates with the same value, so SSR output, first paint and
   hydration agree — there is no locale flash and no `/en`-style routing.

The client keeps `document.documentElement.lang` in sync: `setLocale` updates
it immediately, and an effect re-asserts it on every locale change.

### `t()` vs `localize()`

- `t(key: TranslationKey, values?)` — typed catalog lookup for static product
  copy. Use this in JSX.
- `localize(message: string, values?)` — explicit **string-key** lookup for
  runtime domain messages (API failure mappings, status text produced outside
  JSX). It has a deterministic English fallback: for `en`, or for any key not
  in the catalog, the input string is returned unchanged (with interpolation
  applied), so unknown/server-authored messages render verbatim instead of
  crashing or leaking a key placeholder.

### `Intl` formatting

All formatting goes through `core.ts` with `localeTags`:

- `formatDateTime(locale, value, options?)` — `Intl.DateTimeFormat`; default
  `dateStyle: "medium"`, `timeStyle: "short"`, `timeZone: "UTC"`. Display
  only; server timestamps/deadlines stay authoritative.
- `formatNumber(locale, value, options?)`.
- `formatDecimal(locale, decimalString)` — group separators and the locale
  decimal separator for display **only** (e.g. `4.00` may display as `4,00`
  in vi). The exact decimal string remains untouched in API payloads,
  application state and grading logic.

## Never translated

The following must never pass through the catalog lookup automatically:

- teacher-authored content: Quiz Markdown source, question stems, options,
  explanations, quiz/assessment titles authored by users;
- canonical Quiz Markdown grammar markers (`Câu`, `Đáp án`, `Lời giải`) —
  the backend grammar is authoritative in Vietnamese regardless of UI locale;
- canonical API enums and tokens: `DRAFT` / `OPEN` / `CLOSED`, `CLASS` /
  `PUBLIC`, `ACTIVE` / `SUBMITTED` / `ABANDONED`, `PENDING`, question types,
  monitoring/cleanup outcome tokens;
- error codes, server diagnostics (they render verbatim; presentation layers
  may map a _known_ code to a localized sentence via `localize`), UUIDs,
  Publication/QuizVersion/Attempt identifiers and opaque cursors;
- authoritative UTC timestamp strings (format a copy for display, never
  rewrite the stored value).

Some vi catalog values deliberately keep a canonical domain token inline —
most importantly **Publication** and **Attempt** in teacher chrome (see the
glossary). That is a terminology decision, not an omission.

## Terminology glossary (vi/en)

Real pairs extracted from the catalogs on 2026-10-26 — do not invent
alternates; extend this table when a term is first used.

| English (canonical in code/docs)                                                                   | Vietnamese (catalog value)                 | Notes                                                                                                                                                                  |
| -------------------------------------------------------------------------------------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Workspace                                                                                          | Không gian làm việc                        | `Learning workspace` / `Không gian học tập`, `Teaching workspace` / `Không gian giảng dạy`                                                                             |
| Learning / Teaching (workspaces)                                                                   | Học tập / Giảng dạy                        | frontend personas only, never authority                                                                                                                                |
| Student / Teacher / Administrator                                                                  | Học sinh / Giáo viên / Quản trị viên       |                                                                                                                                                                        |
| Sign in / Sign out                                                                                 | Đăng nhập / Đăng xuất                      |                                                                                                                                                                        |
| Assessment                                                                                         | Bài kiểm tra                               | `My Assessments` / `Bài kiểm tra của tôi`                                                                                                                              |
| Quiz (product UI)                                                                                  | Bộ câu hỏi                                 | `Create quiz` / `Tạo bộ câu hỏi`; Quiz Library / Thư viện bộ câu hỏi                                                                                                   |
| Quiz Markdown                                                                                      | Quiz Markdown (kept)                       | grammar + markers `Câu`, `Đáp án`, `Lời giải` canonical                                                                                                                |
| Quiz Draft / draft                                                                                 | Bản nháp                                   | `Draft only` / `Chỉ bản nháp`                                                                                                                                          |
| QuizVersion                                                                                        | QuizVersion (kept) + Phiên bản as the noun | `Publish QuizVersion` / `Xuất bản QuizVersion`; `Version {number}` / `Phiên bản {number}`; `Published versions` / `Các phiên bản đã xuất bản`                          |
| Publish                                                                                            | Xuất bản                                   | `Publish complete` / `Đã xuất bản`                                                                                                                                     |
| Publication                                                                                        | **Publication (kept in vi)**               | deliberate: canonical entity name; `Publication state` / `Trạng thái Publication`, `Publication ID` / `Mã Publication`, `Publication details` / `Chi tiết Publication` |
| Classroom                                                                                          | Lớp học                                    |                                                                                                                                                                        |
| Attempt — teacher chrome                                                                           | **Attempt (kept in vi)**                   | `Attempt duration` / `Thời lượng Attempt`, `Attempt ID` / `Mã Attempt`                                                                                                 |
| Attempt — student, specific session                                                                | Bài làm                                    | `Attempt {attemptId}` / `Bài làm {attemptId}`, `Attempt result` / `Kết quả bài làm`                                                                                    |
| Attempt — occurrence / entitlement                                                                 | Lượt làm bài                               | `Attempt {ordinal}` / `Lượt làm bài {ordinal}`; `Best successfully graded Attempt` / `Lượt làm đã chấm có điểm cao nhất`                                               |
| Submit attempt / Abandon                                                                           | Nộp bài / Bỏ bài                           | `Attempt submitted` / `Đã nộp bài`, `Attempt abandoned` / `Đã bỏ bài`                                                                                                  |
| Grading policy                                                                                     | Chính sách chấm điểm                       | graded / đã chấm                                                                                                                                                       |
| Monitoring (proctoring)                                                                            | Giám sát                                   | `Monitored Attempt` / `Bài làm được giám sát`                                                                                                                          |
| Locator (private publication)                                                                      | Locator (kept)                             | `Private locator` / `Locator riêng tư`                                                                                                                                 |
| State enums `DRAFT`/`OPEN`/`CLOSED`, `CLASS`/`PUBLIC`, `ACTIVE`/`SUBMITTED`/`ABANDONED`, `PENDING` | canonical, untranslated                    | badges may pair the token with localized adjacent copy                                                                                                                 |

## How to add keys

1. Pick the owning catalog by domain (see table above). A key used on screens
   of several domains still lives in exactly one catalog — cross-screen keys
   stay with their original owner (e.g. `"Student access required"` lives in
   `assessment`, and `monitoring` documents that it does not duplicate it).
2. Add `"Exact English source text": "Bản dịch tiếng Việt",` — key phrased as
   the final English copy, value as final Vietnamese copy, placeholders as
   `{name}`.
3. Render with `const { t } = useI18n(); … t("Exact English source text")`.
4. For runtime domain messages (failure mapping tables), keep the English
   string as the key and call `localize(...)` at render/assignment time.
5. The cross-catalog duplicate guard
   (`features/quizzes/components/quiz-authoring-catalog.test.ts`) fails the
   suite if a new key collides with any other catalog or repeats inside its
   own catalog — a duplicate would silently change which merged value wins.
   Re-run `pnpm test` after adding keys.
6. Never wrap canonical tokens (enums, IDs, error codes, authored content) in
   `t()`/`localize()`; localize the sentence _around_ them instead.

## State-preservation rules (locale must not destroy work)

A locale switch is a presentation-only re-render. Three hard rules:

1. **No `key={locale}`** anywhere. Re-keying remounts the subtree and throws
   away component state (form input, answers, editor caret/scroll).
2. **No refetch on locale change.** Locale must never appear in data-loading
   effect dependencies; a refetch would discard answers, autosave sequence
   state and in-flight attempt data, and violates assessment correctness
   rules. Load effects keep formatting failure copy out of their dependency
   arrays instead (next rule).
3. **`localizeRef` pattern for async/callback copy.** Callbacks, coordinators
   and promise handlers that build localized messages later read the _latest_
   localizer through a stable ref (`useLocalizeRef()` in
   `features/quizzes/components/quiz-authoring-pages.tsx` and
   `features/attempts/components/student-assessment-pages.tsx`;
   same-shaped ref in `features/attempts/hooks/use-attempt-coordinator.ts`):

   ```tsx
   function useLocalizeRef() {
     const { localize } = useI18n();
     const ref = useRef(localize);
     useLayoutEffect(() => {
       ref.current = localize;
     }, [localize]);
     return ref;
   }
   ```

   This keeps the identity-stable ref in effect deps while messages still
   render in the current locale.

Regression coverage: `quiz-authoring.spec.ts` ("switching locale mid-attempt
preserves unsaved answers and submission still succeeds") and
`teacher-directories.spec.ts` ("language switcher preserves an in-progress
DRAFT form and the locale persists across reload").

## Testing

- Unit/RTL: catalog integrity (duplicate guard, non-empty values) plus
  per-domain i18n suites — `publication-i18n.test.tsx`,
  `student-assessment-i18n.test.tsx`, `monitoring-i18n.test.tsx`,
  `quiz-authoring-i18n.test.tsx`, `public-auth-localization.test.tsx` —
  assert real rendered vi and en output under `I18nProvider`.
- Provider-less legacy component tests keep working through the context's
  `en` fallback; all real routes are enclosed by `I18nProvider`.
- E2E acceptance: `tests/e2e/t4-i18n-acceptance.spec.ts` — vi default with
  `html lang`, immediate reversible switching, cookie persistence across
  reload, authenticated surfaces in both locales.
- Visual evidence: `tests/e2e/t4-visual-evidence.spec.ts` captures both
  locales at 1920×1080, 1440×900, 768×1024, 390×844 with a
  no-horizontal-overflow assertion per screenshot (`EVIDENCE_DIR` overrides
  the output directory).

### English-pinning pattern for e2e

Existing English-assertion suites pin the locale instead of rewriting
expectations per locale — set the cookie before navigation:

```ts
test.use({
  cookies: [
    { name: "quizopia-locale", value: "en", url: "http://localhost:3000" },
  ],
});
```

The server resolves the cookie, so SSR, `html lang="en"` and hydrated UI are
English from the first paint with no in-test switching. New e2e suites that
assert copy must either pin English this way or assert both locales
explicitly; never rely on ambient locale.
