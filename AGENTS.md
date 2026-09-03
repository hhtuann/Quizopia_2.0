# Quizopia 2.0 Agent Instructions

## Project

Quizopia 2.0 is a public quiz and learning platform implemented as a new system.

It is NOT an incremental refactor of Quizopia 1.x.

## Mandatory context

Before non-trivial work, read:

- this file;
- relevant files under `docs/product/`;
- relevant files under `docs/architecture/`;
- relevant files under `docs/specifications/`;
- relevant accepted ADRs under `docs/decisions/`;
- `docs/open-questions.md` when the task touches an unresolved area.

Legacy behavior is reference-only:

- `docs/reference/legacy-analysis.md`

Quizopia 2.0 accepted documentation takes precedence over legacy behavior.

## Pre-scaffold architecture baseline

### Repository

Quizopia is a true monorepo.

Planned top-level code areas:

- `frontend/`
- `gateway/`
- `services/identity-service/`
- `services/quiz-service/`
- `services/classroom-service/`
- `services/assessment-service/`
- `services/community-service/`
- `services/proctoring-service/`
- `services/ai-service/`
- `shared/`
- `infrastructure/`
- `scripts/`

Every backend service is an independently buildable Maven project.

The Java package root is `com.quizopia`.

### Shared code

`shared/` may contain only deliberately stable technical contracts/support, for example:

- API-contract tooling/schemas;
- event contracts/schemas;
- test support.

Do NOT create shared:

- JPA entities;
- repositories;
- business domain models;
- business services;
- persistence abstractions that couple service databases.

### Service boundaries

Initial business services:

- Identity
- Quiz
- Classroom
- Assessment
- Community
- Proctoring
- AI

Do not create a new microservice or move authoritative ownership without an ADR.

A service MUST NOT read or write another service's database.

### Public edge

- Browser business HTTP traffic goes through Spring Cloud Gateway.
- Browser business services are not exposed as independent public APIs.
- Public WebSocket traffic is routed through the public edge/gateway topology.
- WebRTC media connects to LiveKit and does not traverse the REST API Gateway.

### Authentication

Identity Service uses Spring Authorization Server and owns:

- local username/password authentication;
- Gmail OTP verification;
- Google OIDC account linking;
- Quizopia user roles;
- Quizopia-issued access tokens;
- refresh-token families/rotation/reuse detection;
- JWKS/signing keys;
- OAuth2 Client Credentials for service identities.

User access tokens:

- RS256 JWT;
- short-lived;
- held in browser memory only;
- verified by Gateway AND by each protected microservice.

Refresh tokens:

- opaque/high entropy;
- HttpOnly cookie;
- stored only as a server-side hash;
- rotated;
- refresh-family reuse detection is required.

Admin/user-disable must support near-immediate revocation through Redis-backed revocation state in addition to denying refresh.

### Service-to-service calls

If an immediate answer is required:

- call the target service directly through the internal network;
- authenticate using short-lived OAuth2 Client Credentials service JWT;
- do not route internal service calls through the public gateway.

If a business fact has occurred and eventual consistency is acceptable:

- publish an integration event through RabbitMQ.

Critical integration events require a transactional outbox or equivalent recoverable mechanism.

Consumers must be idempotent.

### Data

- PostgreSQL 17 is the default transactional source of truth.
- Each service owns a separate database and database credential, even if databases share one physical cluster/server.
- No cross-service SQL or joins.
- Redis is ephemeral/distributed support only, never the authoritative answer/grade/publication/membership store.
- Binary data uses an S3-compatible object-storage abstraction; local implementation is MinIO.
- AI starts with PostgreSQL + pgvector for vector retrieval.
- Flyway owns schema changes.
- Hibernate validates schema; do not use production `ddl-auto=update/create`.

### Quiz/assessment correctness

- Published quiz versions are immutable.
- Active assessments must not depend on Quiz Service availability.
- Stable attempt question/option order is required.
- Server time/deadlines are authoritative.
- Autosave must reject stale sequence writes.
- Submit is idempotent.
- Submit/grading/result persistence must remain transactionally coherent.
- Correct answers must not leak before review policy allows them.

### Realtime/media

- REST/HTTP = authoritative business mutations/queries.
- WebSocket = application realtime signals/UI acceleration.
- WebRTC/LiveKit = realtime media.
- WebRTC DataChannels must not become the authoritative assessment answer path.
- After reconnect, clients reconcile authoritative state through REST/read APIs.

### Proctoring

- Proctoring is class-only and time-bounded according to product rules.
- Browser monitoring cannot inspect unrelated tab URLs or all applications.
- AI proctoring emits suspicious/risk signals for human review; it does not automatically convict/fail a learner.

### API contracts

- Services publish OpenAPI contracts.
- Frontend TypeScript contracts/clients should be generated/derived from OpenAPI where practical.
- Do not manually create a second contradictory DTO model when a generated contract exists.

### Quality and observability

Backend baseline:

- Spotless;
- ArchUnit;
- JUnit;
- Testcontainers;
- Actuator/Micrometer;
- OpenTelemetry/Micrometer tracing;
- structured production logging.

Frontend baseline:

- ESLint;
- Prettier;
- TypeScript strict;
- Vitest;
- React Testing Library;
- Playwright for E2E.

Do not log credentials, OTPs, access/refresh tokens, unnecessary PII, or media payloads.

### Configuration/secrets

- Non-secret defaults may live in committed configuration.
- Runtime/environment-specific values come from environment/deployment configuration.
- `.env.example` may be committed; real `.env` must not be committed.
- Production secrets never live in Git.
- Each service receives only config/secrets it needs.

## Before coding

For every non-trivial task:

1. Read the relevant docs/ADRs.
2. Inspect the current implementation and tests.
3. Identify the owning service(s).
4. State a short implementation plan.
5. Identify schema/API/event/config impacts.
6. Confirm the task does not rely on an unresolved TBD.
7. Implement only the documented scope.
8. Add/update tests.
9. Run relevant checks.
10. Update docs when behavior/architecture changes.

## Conflict handling

If code, tests, docs, or requirements conflict materially:

1. stop expanding the implementation;
2. report the exact conflict and affected files;
3. apply documented precedence only when clear;
4. otherwise ask for a product/architecture decision.

Do not silently resolve feature-level TBD items.

## Agent collaboration

Prefer:

- one issue -> one task branch -> one primary implementation agent/developer;
- second agent may perform review;
- avoid two agents concurrently rewriting the same branch;
- human performs final review before merge.
