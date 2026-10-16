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

Regenerate/check Publication types from repository root (Python with PyYAML,
already used by repository tooling):

```sh
python3 scripts/local/generate-publication-types.py
python3 scripts/local/generate-publication-types.py --check
```

## Teacher Assessment Publications

In the Teaching workspace, open an owned Quiz, choose **Published versions**, select
an exact immutable version, then **Create assessment**. The create flow preserves
Quiz ID, QuizVersion ID and version number and confirms the selection through the
existing Quiz API. It never uses the mutable draft or silently selects a newer version.
Unsaved Quiz Draft changes retain their existing navigation confirmation.

`/app/assessments/new` provides entry instructions and a known Publication ID lookup.
There is no owner Publication list API, browser inventory or fabricated Classroom
selector. CLASS accepts one known canonical Classroom ID; the server checks authority.
`/app/assessments/[publicationId]` displays owner-authorized details and the supported
DRAFT edit, OPEN, OPEN schedule extension and direct CLOSE actions.

Schedule fields explicitly use UTC, independent of device timezone. Enter
`YYYY-MM-DDTHH:mm:ss` with optional microseconds, for example
`2026-10-16T08:30:00.123456`; requests include `Z`. No ambiguous local DST conversion
occurs. New bounded durations are whole minutes 5–240. Existing unlimited duration
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

## API and real integration

Browser business requests use `NEXT_PUBLIC_API_URL` to reach Spring Cloud Gateway.
Access tokens remain in memory and feature clients reuse the existing authenticated
request executor. The frontend never connects directly to business services or
receives SERVICE credentials. Teacher workspace selection does not grant authority.

Real W3-A verification requires an isolated, revision-identified Gateway, Identity,
Assessment, Quiz, Classroom, PostgreSQL and Redis runtime with migrations, exact
Publication/CLOSE routes, admission/source/Classroom clients, scopes and allowlists
provisioned. Defaults are closed. Provide separate authorized Teacher CLASS/PUBLIC
resources and preserve accepted Origin/CORS/cookie rules. Missing topology is an
integration blocker, not a reason to replace backend responses with mocks.

See [W3-A implementation and evidence](../docs/development/frontend-w3a-implementation.md)
for contract references, review findings, actual checks and outstanding gates.
LD-05 PR #77 remains a separate workstream; W3-A does not merge or replace it.
