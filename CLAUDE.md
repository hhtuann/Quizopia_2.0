# Quizopia 2.0

Quizopia 2.0 is a public quiz and learning platform implemented as a new coarse-grained microservice system.

## Required reading

Before architecture or feature work:

- `AGENTS.md`
- `docs/product/product-overview.md`
- `docs/architecture/system-architecture.md`
- `docs/architecture/repository-structure.md`
- `docs/architecture/service-boundaries.md`
- `docs/architecture/authentication.md`
- `docs/specifications/business-rules.md`
- relevant accepted ADRs
- `docs/open-questions.md` for unresolved areas

Legacy reference:

- `docs/reference/legacy-analysis.md`

## Key accepted architecture

- true monorepo;
- one Next.js frontend;
- Spring Cloud Gateway public edge;
- seven independent Maven/Spring business services;
- Java package root `com.quizopia`;
- Spring Authorization Server in Identity;
- Quizopia-issued RS256 user JWTs;
- rotating opaque refresh tokens;
- gateway and every service verify user JWTs;
- Redis-backed near-immediate account revocation;
- OAuth2 Client Credentials service JWTs for internal synchronous REST;
- RabbitMQ integration events;
- transactional outbox for critical events;
- separate PostgreSQL database + credential per service;
- MinIO locally through an S3-compatible abstraction;
- PostgreSQL + pgvector for initial AI vector retrieval;
- REST for authoritative mutations, WebSocket for application realtime, WebRTC/LiveKit for media;
- OpenAPI-derived frontend contracts;
- GitHub Actions + automated quality/testing gates;
- OpenTelemetry/Actuator/Micrometer baseline.

## Hard constraints

- Never access another service's database.
- Never share JPA entities/repositories/domain services through `shared/`.
- Never change service ownership or create a microservice silently.
- Published quiz versions are immutable.
- Active assessment attempts must remain self-contained in Assessment Service.
- Never persist authoritative answers through WebRTC DataChannels.
- Never claim browser proctoring can inspect arbitrary external tabs/URLs.
- Proctoring AI flags risk for human review; it does not automatically fail/convict.
- Never commit real secrets.
- Do not invent answers to items explicitly marked TBD.
- Run relevant tests/checks and update docs for behavior changes.

`AGENTS.md` is the authoritative shared repository working-instruction file.
