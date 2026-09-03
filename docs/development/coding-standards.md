# Coding Standards

Status: **Accepted scaffold baseline v0.2**

## Backend baseline

- Java 21
- Spring Boot 4.x
- independent Maven project per gateway/service
- Spring Security
- Spring Data JPA where appropriate
- Flyway
- OpenAPI

## Backend structure

Within each microservice, prefer package-by-feature/domain organization.

Keep clear responsibilities for:

- API/controller;
- application/use case;
- domain;
- persistence/adapters;
- external clients/messaging;
- DTO/contracts;
- error handling.

Do not put business workflows in Gateway.

Do not reach into another service database/repository.

## Formatting/architecture enforcement

Backend baseline:

- Spotless;
- ArchUnit or equivalent architecture tests.

CI must fail for required formatting/architecture violations.

## Persistence

- Flyway owns schema.
- Hibernate validates.
- DB constraints protect critical invariants.
- Inject/use a controllable `Clock` for business time.
- Use `BigDecimal` for grading score arithmetic.
- Use intentional locking/idempotency for concurrency-sensitive workflows.

## HTTP/API

- explicit DTOs/contracts;
- stable public error codes;
- OpenAPI as contract source;
- generated/derived TypeScript clients/types where practical.

## Messaging

- RabbitMQ integration events;
- transactional outbox for critical events;
- idempotent consumers;
- no secrets/unnecessary PII in events.

## Frontend baseline

- Next.js 16
- React 19
- TypeScript strict
- Tailwind CSS 4
- TanStack Query
- React Hook Form + Zod
- focused Zustand stores where local/working state warrants them
- ESLint
- Prettier

Prefer feature-oriented organization.

Frontend authorization guards are UX only.

## Realtime

- REST state is authoritative;
- WebSocket accelerates UI/realtime updates;
- WebRTC/LiveKit transports media;
- reconcile after reconnect.

## Logging

Production backend logging is structured JSON (or equivalent structured output).

Include useful fields such as:

- timestamp;
- level;
- service;
- traceId;
- spanId;
- stable event/action name.

Never log:

- password;
- OTP;
- access token;
- refresh token;
- private keys/client secrets;
- raw media;
- unnecessary PII;
- sensitive answer payloads unless an explicitly designed, access-controlled audit requirement exists.

## Dependencies

Services build independently but framework/dependency versions must be centrally monitored/aligned.

Use repository automation such as Dependabot or Renovate; exact tool is TBD.

Do not introduce a giant shared parent/domain artifact that erodes service independence.
