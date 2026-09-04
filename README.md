# Quizopia 2.0

> Project Context Pack v0.2 — Pre-Scaffold Architecture Baseline

Quizopia 2.0 is a public web platform for creating, publishing, sharing, practicing, and taking quizzes. It also supports classroom workflows, public community content, AI-assisted authoring/tutoring, and optional proctoring for time-bounded classroom assessments.

Quizopia 2.0 is a **new system** inspired by the strongest engineering invariants and lessons of Quizopia 1.x. It is **not** an incremental refactor of the legacy codebase.

## Current scaffold

Context Pack v0.2 finalizes the architectural decisions required to scaffold the repository for a four-person team.

Accepted pre-scaffold decisions include:

- true monorepo;
- one Next.js frontend;
- Spring Cloud Gateway as the public API edge;
- seven coarse-grained business microservices;
- independent Maven build per backend service;
- Spring Authorization Server in Identity Service;
- Quizopia-issued RS256 access JWTs + rotating opaque refresh tokens;
- gateway **and** every business service validate user JWTs;
- immediate account revocation state distributed through Redis;
- OAuth2 Client Credentials for service-to-service authentication;
- internal REST for synchronous service communication;
- RabbitMQ for asynchronous integration events;
- transactional outbox + idempotent consumers for critical integration events;
- one PostgreSQL 17 server/cluster may host separate service-owned databases with separate credentials;
- Redis for justified ephemeral/distributed concerns only;
- MinIO locally behind an S3-compatible object-storage abstraction;
- PostgreSQL + pgvector as the initial AI vector-storage baseline;
- Mailpit for local/test email;
- WebRTC/LiveKit for realtime media;
- hybrid local development (infrastructure in Docker, active code from IDE/dev server);
- GitHub Actions, path-aware PR checks, and full `develop`/`main` validation;
- OpenAPI-derived frontend contracts;
- structured logging, OpenTelemetry, Actuator/Micrometer;
- optional local Prometheus/Grafana/Tempo observability profile;
- Flyway-owned schemas, Hibernate validation only;
- JUnit/Testcontainers, frontend unit/component tests, and Playwright E2E.

The initial platform scaffold is implemented. It deliberately contains no
Quizopia business features. See [`docs/development/scaffold.md`](docs/development/scaffold.md)
for the hybrid local workflow and exact commands.

## Repository structure

```text
Quizopia_2.0/
├── frontend/                       # one Next.js application
├── gateway/                        # Spring Cloud Gateway
├── services/
│   ├── identity-service/           # independent Maven project
│   ├── quiz-service/
│   ├── classroom-service/
│   ├── assessment-service/
│   ├── community-service/
│   ├── proctoring-service/
│   └── ai-service/
├── shared/                         # contracts/test support only
│   ├── api-contracts/
│   ├── event-contracts/
│   └── test-support/
├── infrastructure/
├── scripts/
├── docs/
├── AGENTS.md
├── CLAUDE.md
└── README.md
```

The scaffold does **not** need to implement the business domains yet. Feature-level questions remain intentionally open.

## Technology baseline

### Frontend

- Next.js 16
- React 19
- TypeScript
- Tailwind CSS 4
- TanStack Query
- React Hook Form + Zod
- Zustand for focused client/working state

### Backend / edge

- Java 21
- Spring Boot 4.x
- Spring Cloud Gateway
- Spring Security
- Spring Authorization Server
- Spring Data JPA where appropriate
- Flyway
- OpenAPI

### Infrastructure

- PostgreSQL 17
- Redis
- RabbitMQ
- MinIO/S3-compatible object storage
- Mailpit for local/test email
- LiveKit/WebRTC
- pgvector for initial AI retrieval
- Docker Compose for local infrastructure

## Getting started

Prerequisites: Java 21, Docker Desktop with Compose, Node.js 24, and pnpm
10.15.1. Start local infrastructure with `scripts/dev-infra.ps1` (Windows) or
`scripts/dev-infra.sh` (Unix-like shells), then run the frontend and the
gateway/service under development directly from their project directories.

Use `scripts/verify.ps1` or `scripts/verify.sh` for the complete local gate.

## Documentation

- Product: [`docs/product/`](docs/product/)
- Architecture: [`docs/architecture/`](docs/architecture/)
- Specifications: [`docs/specifications/`](docs/specifications/)
- ADRs: [`docs/decisions/`](docs/decisions/)
- Development: [`docs/development/`](docs/development/)
- Open questions: [`docs/open-questions.md`](docs/open-questions.md)
- Review resolution: [`docs/reviews/context-pack-v0.1-review-resolution.md`](docs/reviews/context-pack-v0.1-review-resolution.md)
- Legacy reference: `docs/reference/legacy-analysis.md` in the project repository

## Source-of-truth rule

Chat conversations are not the project source of truth.

Accepted product behavior, architecture, and engineering rules must be represented in repository documentation and ADRs.

When there is a material conflict:

1. accepted ADRs and explicit Quizopia 2.0 specifications take precedence over legacy reference behavior;
2. agents must report unresolved conflicts instead of silently inventing a rule;
3. feature-level TBD items remain TBD until accepted explicitly.

## Legacy reference

Keep the full existing `docs/reference/legacy-analysis.md` from the Quizopia 2.0 repository. Context Pack v0.2 deliberately does **not** bundle or overwrite that file.
