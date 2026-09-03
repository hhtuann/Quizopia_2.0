# Quizopia 2.0

Quizopia 2.0 is a public quiz and learning platform built as a new system.

## Required reading

Before architecture or feature work, read:

- `AGENTS.md`
- `docs/product/product-overview.md`
- `docs/architecture/system-architecture.md`
- `docs/architecture/service-boundaries.md`
- `docs/specifications/business-rules.md`

Then read the feature-specific docs and relevant ADRs.

For legacy reference:

- `docs/reference/legacy-analysis.md`

## Hard constraints

- Do not invent missing product requirements.
- Do not access another microservice's database.
- Do not change service boundaries silently.
- Published quiz versions are immutable.
- Active assessment attempts must be self-contained in Assessment Service.
- REST is authoritative for business mutations.
- WebSocket is for application realtime signals.
- WebRTC/LiveKit is for media.
- Proctoring AI flags suspicious behavior for human review; it does not automatically convict a student.
- Standard browser code cannot inspect unrelated tabs/websites.
- Run relevant tests after implementation.
- Update docs when behavior changes.

`AGENTS.md` contains the shared repository rules and has priority for repository-wide working conventions.
