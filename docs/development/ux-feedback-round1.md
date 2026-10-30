# UI/UX Feedback Round 1 — Verification Record

> **Branch:** `feature/frontend-ux-feedback-round1` (baseline: merged PR #112,
> `9d5ce4d803717bd7c874d384b74ac18156eb1806`)
> **Status:** implementation complete on the branch; awaiting independent
> review. Production **NO-GO** unchanged. BR-01 was resolved 2026-10-29 as
> policy-only (see the BR-01 decision memo).

Approved user-feedback corrections UX-01…UX-06. All changes are
frontend-presentation only unless explicitly noted; no business rule, scoring,
closure, monitoring, authentication or i18n-architecture change is included.

## UX-01 — Language selection in the authenticated user menu — DONE

- Standalone header dropdown removed from the authenticated application
  shell; language selection is now a "Language / Ngôn ngữ" section inside the
  existing account menu using `menuitemradio` semantics (`aria-checked` +
  check icon on the current locale), arrow-key reachable, menu stays open on
  switch, immediate `setLocale` (cookie persists, `html lang` syncs), no
  route remounts or form/attempt state resets (T4 guarantees re-verified).
- Public/unauthenticated surfaces (login/register/verify/landing) keep the
  standalone accessible `LanguageSwitcher`; no account is required there.
- The focused Quiz Editor header retains its standalone switcher (editor
  chrome has no user menu by design).

## UX-02 — Consistent page header cards — DONE

- `/app/assessments` (My Assessments) and the Notifications inbox now use the
  Quiz-Library hero card pattern: `rounded-2xl border border-primary/10
bg-surface shadow-card p-6 sm:p-8` + shared `HeroOrb` gradient, with
  heading, description and the primary CTA inside the card; responsive at
  390px (verified with overflow assertions).

## UX-03 — Dropdown/select padding — DONE

Audit found exactly two native `<select>` controls (language switcher,
directory state filter; monitoring/classroom/OTP surfaces have none):

- `language-switcher.tsx`: `px-3` → `py-2.5 pl-3 pr-10`
- `publication-directory.tsx` state filter: `px-4` → `max-w-full py-2.5 pl-4 pr-10`

12–16px right spacing before the native chevron; no accessibility, focus or
selection semantics changed.

## UX-04 — Unified Assessment detail (results-first) — DONE

- OPEN/CLOSED detail pages are now results-first: compact header (title +
  state badge + revision) → collapsible **configuration summary** (compact
  chip row: state, participation window with explicit timezone, duration,
  attempts policy, audience/CLASS context, monitoring; expandable
  `<details>`-based disclosure holding the full summary + locator, default
  expanded on desktop, collapsed on mobile, fully keyboard operable, locale
  switches never remount it) → existing lifecycle controls (labels and
  behavior unchanged) → **embedded student results section** (reused
  table + load-more + loading/empty/error/retry states via the existing
  teacher-results client). DRAFT keeps the management-first layout with no
  results section. The standalone `/results` route remains functional and
  shares the same component.
- Directory rows are keyboard navigable: the assessment title is the primary
  link (preserving the `Manage {title}` accessible name), `Results {title}`
  remains a separately tabbable nested link, rows show `:focus-within` ring,
  and Enter navigation is native. No nested control was removed.

## UX-05 — Student display names in Teacher Results — INVESTIGATED,

PAUSED FOR LEADER ARCHITECTURE APPROVAL

Investigation (source-verified): Identity currently exposes only
`/me` (self, USER JWT) and boolean-style internal eligibility endpoints
(`/internal/security/notice-recipients|proctoring-students/{id}/eligibility`,
SERVICE JWT, admission scopes). There is **no individual or bulk
user-profile/display-name lookup contract**, and Assessment results rows
carry only `studentId` UUIDs. Displaying human names therefore requires a
**new cross-service contract**.

Proposed contract (not implemented, awaiting approval):

- Identity: `POST /internal/security/user-profiles/batch` — SERVICE JWT
  (new least-privilege scope, e.g. `identity.user-profile.read`),
  caller restricted to the Assessment service client; request bounded to
  ≤100 `studentId`s per call; response `{items: [{studentId, displayName}]}`;
  unknown ids omitted (not errored); `Cache-Control: no-store`;
  display name = existing verified account display field only — **no emails,
  credentials or profile details**.
- Assessment: when serving an owner-authorized results page, resolve page-sized
  batches of student ids server-side (≤ limit+1 rows) and merge `displayName`
  into the existing rows; never trust client-provided mappings; frontend
  renders displayName primary with the id as secondary only where needed and
  falls back to the id when the lookup fails.
- Security implications: new service scope (least privilege), batch bound
  prevents unbounded enumeration, ownership still enforced by Assessment
  before any lookup, no new public edge route, LD-05 unaffected.
- Until approved and implemented, Teacher Results continue showing the
  authoritative student identifier; **no names are fabricated**.

## UX-06 — Human-friendly localized date/time — DONE

`lib/i18n/core.ts` (additive; existing exports unchanged):

- `DISPLAY_TIME_ZONE = "Asia/Ho_Chi_Minh"`; `formatInstant(locale, value,
{withTimeZone, dateStyle, timeStyle})` — vi `HH:mm, dd/MM/yyyy` (24h),
  en `25 Oct 2026, 15:38` (24h, en-GB); seconds/microseconds/UTC suffixes
  never rendered; invalid/missing → `—`; optional explicit zone suffix
  (offset derived from Intl) for business-critical schedules/deadlines.
- `formatInstantRange` for same-day windows.

Adopted across Quiz Library/version history dates, publication directory
windows (also fixes the previous browser-timezone rendering bug there),
publication summary/schedule-extension current end, student preview
window/deadline/cutoff, notification timeline (authoritative cutoffs keep
explicit timezone), closure history, teacher-results finalizedAt, proctoring
timeline/monitoring deadlines. UTC instants remain untouched in APIs and
business logic; deadlines and server-time authority unchanged.

## Verification summary

- `pnpm test`: **829/829** (79 files; +4 UX-04, +16 formatter tests,
  +5 user-menu tests, updated expectations)
- `pnpm typecheck` / `pnpm lint` / `pnpm format:check` / `pnpm build`: PASS
- Playwright: **133/133** full local suite (both locales pinned as before);
  responsive overflow checks at 390/768/1440/1920 in the visual suites.

### Real-stack spot verification (persistent local runtime, no interception)

`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\ux1-live\spot-report.json`
(+3 sanitized screenshots) — **14/14 assertions** against the real Gateway /
Identity / Quiz / Classroom / Assessment services with a production build of
this branch:

- vi-default login; authenticated header has **no standalone language
  dropdown**; the user-menu "Language / Ngôn ngữ" group shows the current
  locale checked (`menuitemradio`), switching to English and back to
  Vietnamese is immediate and state-preserving.
- My Assessments renders the **hero card** and **friendly Vietnam-time
  windows** ("26 Oct 2026, 14:31 (Vietnam time, UTC+7)") with no
  seconds/microseconds/UTC suffixes.
- Directory rows are keyboard focusable and **Enter navigates** to the
  unified detail page.
- Unified detail shows the **configuration summary** (before lifecycle
  controls) and the **embedded real student results section** with the
  actual graded 100.00% attempt row, in DOM order summary → results.
- Vietnamese detail renders `HH:mm, dd/MM/yyyy … giờ Việt Nam, UTC+7`.

## Limitations

- UX-05 paused (section above) — Leader architecture approval required
  before any backend/frontend name display.
- Real-stack spot check is local-runtime acceptance, not production
  acceptance; inherited release gates unchanged.
