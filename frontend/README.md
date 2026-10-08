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
- `pnpm build`

Browser business requests use `NEXT_PUBLIC_API_URL` to reach the public
Gateway. Access tokens remain in memory and authenticated feature clients use
the shared request executor; the frontend does not call Quiz Service directly.
