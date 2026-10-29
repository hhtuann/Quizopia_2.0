# Quizopia frontend

Next.js 16 / React 19 application using pnpm, strict TypeScript and Tailwind CSS 4.
The application includes browser authentication, Teaching workspace enablement,
Quiz Library, Markdown authoring, immutable publishing/history/preview and the
bounded W3-A Teacher Assessment Publication experience.

## Development and verification

```sh
pnpm install --frozen-lockfile
pnpm dev
pnpm test
pnpm typecheck
pnpm lint
pnpm format:check
pnpm build
pnpm test:e2e
```

Focused W3-A checks:

```sh
pnpm exec vitest run features/assessments
pnpm test:e2e assessment-publication.spec.ts --workers=2
```

The W3-A browser suite uses actual Chromium with intercepted Gateway/auth responses.
It does not prove live Assessment/Quiz/Classroom/Identity integration. Existing
`pnpm test:e2e:real` and `pnpm test:e2e:real:executor` have their own documented
runtime prerequisites and do not establish W3-A acceptance by themselves.

## Internationalization (vi default / en)

The frontend is bilingual: Vietnamese is the product default, English is the
typed catalog-key source language. Developer reference with the full
architecture, terminology glossary and state-preservation rules:
[`lib/i18n/README.md`](lib/i18n/README.md).

- **Workflow:** copy lives in five domain catalogs under
  `lib/i18n/messages/` (`common`, `authoring`, `assessment`, `student`,
  `monitoring`). A key is the exact English source text; its value is the
  Vietnamese translation. Render through `const { t } = useI18n()` —
  `t("My Assessments")` — so a typo is a compile error, and use
  `localize(message)` (deterministic English fallback) for runtime domain
  messages such as API failure mapping.
- **Locale preference:** the `quizopia-locale` cookie (`vi|en`, `SameSite=Lax`,
  one year, preference only — no credentials). The root server layout resolves
  it (`getServerLocale()`) so SSR, `<html lang>` and hydration agree with no
  flash; the `LanguageMenu` popover (public/auth chrome and dedicated
  workspaces) or the account-menu language options (authenticated shell) flip
  the locale immediately and persist it. Dates/numbers go through
  `formatDateTime`/`formatNumber`/
  `formatDecimal` (`vi-VN` / `en-GB`).
- **Never localized:** teacher-authored Quiz Markdown and its grammar markers
  (`Câu`, `Đáp án`, `Lời giải`), canonical API enums (`DRAFT`/`OPEN`/`CLOSED`,
  `CLASS`/`PUBLIC`, ...), error codes, IDs and authoritative timestamps.
  Some vi values deliberately keep canonical tokens (Publication, Attempt in
  teacher chrome) — see the glossary before coining a term.
- **Tests:** `pnpm test` includes the cross-catalog duplicate-guard and
  per-domain i18n RTL suites; `tests/e2e/t4-i18n-acceptance.spec.ts` covers
  vi default, immediate reversible switching, cookie persistence and
  authenticated surfaces in both locales. Locale switches must never remount
  (`key={locale}`) or refetch data — mid-attempt and mid-form switches have
  dedicated regression journeys.
- **E2E English pinning:** suites asserting English copy pin the locale
  instead of switching in-test:
  `test.use({ cookies: [{ name: "quizopia-locale", value: "en", url: "http://localhost:3000" }] })`.
  New e2e tests must pin English this way or assert both locales explicitly.

Regenerate/check Publication types from repository root (Python with PyYAML,
already used by repository tooling):

```sh
python3 scripts/local/generate-publication-types.py
python3 scripts/local/generate-publication-types.py --check
python3 scripts/local/generate-classroom-types.py
python3 scripts/local/generate-classroom-types.py --check
```

## Quiz authoring creates on first save (FB-014)

`/app/quizzes/new` opens a blank, deliberately unsaved authoring workspace:
no network call happens until the teacher's first explicit **Save**, so
cancelling never leaves an empty Quiz record. The first Save is gated on at
least one recognizable question (the same client `analyzeQuizMarkdown`
grammar the live preview uses; publish-level completeness stays enforced by
the backend publish validation). It fires exactly one `createDraft` with the
authored title/source, then `router.replace`s to `/app/quizzes/{quizId}` so
reload works; later Saves use the ordinary `updateDraft` on that same quiz.
Rapid double Saves are collapsed by an in-flight guard, and a create failure
never auto-retries: `POST /api/quizzes` has no Idempotency-Key contract
(unlike Assessment create), so the failure copy states honestly that an
explicit retry may create a duplicate if the first attempt reached the
server. Leaving an empty new-quiz screen asks nothing; typed content keeps
the standard discard confirmation.

## Permanent quiz deletion (FB-015 / ADR-018)

The owner-only library/editor action confirms the server-persisted title,
trimmed and case-sensitive, rather than the unsaved editor text or the localized
"Untitled quiz" display label. Historical null/blank titles require an explicit,
localized irreversible-deletion checkbox and send `confirmTitle: ""`.

Fenced library cards and editor drafts discover the durable owner receipt with
`GET /api/quizzes/{quizId}/deletion-requests/latest`. A detail URL also discovers
that receipt after a draft 404/410, including after primary purge. Status requests
use `cache: "no-store"`; errors offer explicit refresh without claiming completion.
No in-session operation ID is required for recovery. The runtime schemas/types
are derived from `services/quiz-service/contracts/quiz-deletion.openapi.yaml`;
the parity test checks the accepted-state and progress-stage inventories.

An unresolved request's random idempotency key is retained in tab session storage
before transmission. Reopen/reload retries first reconcile the latest receipt;
only a not-found response permits replay with that same key. No credentials or
quiz content are stored with the key. When browser storage is unavailable, the
key survives only within the mounted dialog; server-side status discovery still
works. `PRIMARY_PURGED` receipts and navigation notices distinguish primary-data
deletion from pending backup-copy erasure; only `ALL_COPIES_ERASED` renders
"Fully deleted".

`tests/e2e/quiz-deletion.spec.ts` exercises Chromium with intercepted responses,
including untitled records, reload discovery, vi/en accessibility and uncertain
retries. It does not establish real Browser → Gateway → service deletion,
authorization, purge correctness or provider/backup erasure evidence.

## Teacher Assessment Publications

In the Teaching workspace, open an owned Quiz, choose **Published versions**, select
an exact immutable version, then **Create assessment**. The create flow preserves
Quiz ID, QuizVersion ID and version number and confirms the selection through the
existing Quiz API. It never uses the mutable draft or silently selects a newer version.
Unsaved Quiz Draft changes retain their existing navigation confirmation.

`/app/assessments` is the **My Assessments** owner directory, discoverable from
Teaching navigation and application home. Search submits a title query to the
server; state changes apply DRAFT/OPEN/CLOSED filters immediately. Both reset
continuation. The authenticated client calls GET `/api/assessments/publications`
with limit 20, optional search/state, and the unchanged opaque nextCursor. Safe
metadata rows adapt to stacked cards on narrow screens; OPEN/CLOSED expose the
existing protected results destination. Obsolete requests are aborted and their
responses ignored. Loading, empty, error, retry and load-more states are explicit.

`/app/assessments/new` consumes the exact QuizVersion source triple. CLASS create
and DRAFT edit use a native-radio owned-Classroom selector backed by Gateway
GET `/classrooms?limit=20&cursor=...`. It never selects automatically. A saved
selection outside the loaded pages is resolved with existing GET `/classrooms/{id}`;
the client verifies its resource and owner binding and retains only safe metadata.
Inaccessible/unverified selection blocks form review, preserving entered fields
and allowing another selection or retry. PUBLIC clears classroomId and disables
monitoring. The backend remains authoritative for assignment access. Zero-class
copy explains the prerequisite; there is no fabricated create-Classroom route.

`/app/assessments/[publicationId]` displays owner-authorized details and the supported
DRAFT edit, OPEN, OPEN schedule extension and direct CLOSE actions.

Schedule fields use the existing local DateTimeField and convert instants to UTC
for requests. New bounded durations are whole minutes 5–240. Existing unlimited duration
and maxAttempts values remain visible and are preserved. Participation-window end,
OPEN, individual Attempt deadlines and durable CLOSE are distinct.

CLASS requires an explicit monitoring choice before OPEN. PUBLIC has no Classroom
and always disables monitoring. Monitoring configuration does not activate activity
collection/camera access; privacy and runtime gates are separate. PUBLIC participants
require current authenticated STUDENT authority. Owner locators are sensitive, shown
only on demand; no Student sharing URL is fabricated.

Uncertain create retries retain the exact in-memory request/idempotency key. Other
uncertain commands require authoritative detail reconciliation before an explicit
original-command retry. No mutation is automatically replayed with a fresh key or
revision. Reloading loses the in-memory uncertain-create receipt; do not speculate
about creating again after an unknown outcome.

### FE-01 / FE-02 verification

Focused client/RTL coverage lives in `features/assessments` and
`features/classrooms`. `tests/e2e/teacher-directories.spec.ts` exercises the real
Chromium UI and authenticated executor with intercepted Gateway contracts:
directory filtering/paging/retry/race cancellation, role gates, Teaching-home
discovery, CLASS creation, saved off-page Classroom resolution, inaccessible
selection, keyboard selection, PUBLIC clearing, and cancelled unsaved navigation.
It writes full-page directory and selector PNGs at widths 1920, 1440, 768 and 390
under each test's `test-results/teacher-directories-*/` directory and checks
document overflow. These browser-contract checks do not establish live
Browser → Gateway → service ownership or LD-05 admission acceptance.

## API and real integration

Browser business requests use `NEXT_PUBLIC_API_URL` to reach Spring Cloud Gateway.
Access tokens remain in memory and feature clients reuse the existing authenticated
request executor. The frontend never connects directly to business services or
receives SERVICE credentials. Teacher workspace selection does not grant authority.

## Realtime hints (W4-B4, default OFF)

`features/realtime` adds a strict browser WebSocket client for best-effort
invalidation hints over the same-origin `/ws/realtime` surface with only the
`quizopia.realtime.v1` subprotocol. A one-time ticket is fetched through the
authenticated executor with the exact `{}` body; the ticket and the current
in-memory USER token travel only inside the first AUTH frame — never in URLs,
storage, subprotocols or logs. Active student Attempt views subscribe to
`ATTEMPT_OWNER` and authorized teacher Publication views to
`PUBLICATION_TEACHER`; every hint, resync signal, reconnect, visibility
recovery and a bounded periodic tick reconcile through the existing REST
clients, so drafts, autosave, revision and idempotency rules are untouched and
REST keeps working when realtime is unavailable. Build-time activation requires
`NEXT_PUBLIC_REALTIME_ENABLED=true` plus the enabled W4 gateway stack; without
it no sockets are opened at all.

Real W3-A verification requires an isolated, revision-identified Gateway, Identity,
Assessment, Quiz, Classroom, PostgreSQL and Redis runtime with migrations, exact
Publication/CLOSE routes, admission/source/Classroom clients, scopes and allowlists
provisioned. Defaults are closed. Provide separate authorized Teacher CLASS/PUBLIC
resources and preserve accepted Origin/CORS/cookie rules. Missing topology is an
integration blocker, not a reason to replace backend responses with mocks.

### Isolated W3-A acceptance

The disposable fixture in `scripts/local/w3a-real-runtime.py` builds five backend
JARs from pinned commit `4744f81625632fd6e2c24d75017597c0abcbf711`. It owns
separate PostgreSQL databases, Redis, Mailpit and loopback service processes.
Prerequisites are Java 21, Docker, the Maven cache, OpenSSL and Python bcrypt.
From repository root:

```sh
python3 scripts/local/w3a-real-runtime.py up
```

Record the printed private state directory and non-secret Mailpit endpoint. Start
only an owned, revision-pinned frontend on the required trusted origin
`http://localhost:3000`; never replace or reuse another developer's listener.
From that isolated frontend directory, with its dependencies available:

```sh
NEXT_PUBLIC_API_URL=http://localhost:8080 NEXT_TELEMETRY_DISABLED=1 \
  pnpm exec next dev --webpack --hostname localhost --port 3000
```

From this checkout's `frontend/`, export `W3A_REAL_STATE_DIRECTORY` to the printed
private directory and `W3A_REAL_MAILPIT_URL` to its Mailpit endpoint, then run:

```sh
W3A_REAL_GATEWAY_URL=http://localhost:8080 \
  pnpm exec playwright test --config=playwright.w3a-real.config.ts
```

The suite uses real Gateway responses, disposable accounts and reversible faults
on ownership-verified fixture processes. CLASS membership is seeded only in the
owned disposable Classroom database; this does not test a membership UI. Traces,
screenshots, video and payload-bearing failure output are disabled. Fixture credentials
stay in the private temporary directory. The loopback transport overrides and
enabled test features are confined to the fixture; production defaults remain
unchanged. Stop the owned frontend separately and, from repository root, clean up:

```sh
python3 scripts/local/w3a-real-runtime.py down "$W3A_REAL_STATE_DIRECTORY"
```

The cleanup verifies process/container ownership and records any resource it could
not safely verify. Inspect that result; an attempted cleanup is not proof that all
resources stopped. These journeys do not replace LD-05 Redis outage/recovery tests.

See [W3-A implementation and evidence](../docs/development/frontend-w3a-implementation.md)
for contract references, review findings, actual checks and outstanding gates.
LD-05 PR #77 is now merged through develop. Publication requests reuse its canonical
session-generation fence; Redis outage/recovery acceptance remains a separate gate.

## Real executor verification

The runner binds `127.0.0.1:3000` by default and keeps Vite `strictPort` enabled.
`LD05_REAL_EXECUTOR_PORT` optionally selects an integer from 1 to 65535; runner
and Playwright share validation and never choose a fallback port. An alternate
port changes the browser Origin and must already be explicitly trusted by both
the isolated Gateway and Identity. The normal localhost:3000 allowlist does not
permit localhost:3100. Do not loosen security rules to run this suite.

If another server binds only IPv4 port 3000 and IPv6 loopback port 3000 is free,
run `LD05_REAL_EXECUTOR_HOST=::1 pnpm test:e2e:real:executor`. The only accepted
bind hosts are `127.0.0.1` and `::1`. Chromium resolves only the harness's
localhost port to that address; Gateway8080 and Mailpit8025 retain normal DNS
resolution. The browser Origin remains `http://localhost:3000`. Neither the other
server nor backend configuration is changed.

The existing expiry test requires
`IDENTITY_AUTHORIZATION_SERVER_USER_ACCESS_TOKEN_TTL=PT5S` on an **isolated**
Identity instance and verifies actual issued JWT timing before waiting past
expiry and validation clock skew. A normal five-minute instance fails this
precondition. Do not restart or reconfigure another developer's services. The
test uses actual backend responses and imports the production auth service and
authenticated request executor; it does not use Playwright response mocks.
Redis outage/stale-restore/recovery verification is a separate requirement.
