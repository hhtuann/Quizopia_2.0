# Quizopia frontend

Next.js 16 / React 19 application using pnpm, strict TypeScript, Tailwind CSS
4, and the agreed testing/tooling baseline. The current application includes
real browser authentication, teaching-workspace enablement, the teacher Quiz
Library, Quiz Markdown authoring, immutable publishing, and owner-authorized
published-version history with read-only snapshot preview.

Commands:

- `pnpm install`
- `pnpm dev`
- `pnpm lint`
- `pnpm format:check`
- `pnpm typecheck`
- `pnpm test`
- `pnpm test:e2e`
- `pnpm test:e2e:real` (requires the accepted local Gateway, Identity, Quiz,
  PostgreSQL, Redis, and Mailpit topology)
- `pnpm test:e2e:real:executor` (requires isolated Identity issuing five-second
  user access tokens, plus Gateway, PostgreSQL, Redis, and Mailpit)
- `pnpm build`

Browser business requests use `NEXT_PUBLIC_API_URL` to reach the public
Gateway. Access tokens remain in memory and authenticated feature clients use
the shared request executor; the frontend does not call Quiz Service directly.

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
