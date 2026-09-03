# Coding Standards

Status: **Baseline v0.1**

## Backend

Baseline stack:

- Java 21
- Spring Boot 4.x
- Spring Web/MVC
- Spring Security
- Spring Data JPA where appropriate
- Flyway

### Internal organization

Within each microservice, prefer package-by-feature/domain organization rather than one global `controller/service/repository` structure.

Keep responsibilities separated:

- API/controller;
- application/use-case;
- domain;
- persistence/repository adapters;
- DTO/contracts;
- exceptions/error codes.

Do not let controllers contain business workflows.

Do not let services reach into another microservice's repository/database.

### Persistence

- Flyway owns schema.
- Hibernate/JPA validates schema.
- Use DB constraints for critical invariants.
- Use `Clock`/time abstraction for business time instead of scattered direct system-time calls.
- Use exact decimal types (`BigDecimal`) for grading math.
- Use intentional locking/idempotency for concurrency-sensitive workflows.

### Errors

Use stable error codes and sanitized public messages.

Do not expose:

- stack traces;
- SQL;
- secrets;
- internal class names;
- answer keys to unauthorized learners.

## Frontend

Baseline stack:

- Next.js 16
- React 19
- TypeScript
- Tailwind CSS 4
- TanStack Query
- Axios or the standardized generated HTTP client
- Zustand for focused client/working state
- React Hook Form + Zod for forms/UX validation

Prefer feature-oriented organization.

Keep:

- server state in TanStack Query;
- working/client state in small focused stores;
- API contracts generated/derived from OpenAPI where practical.

Frontend authorization/route guards are UX only.

## Quiz Markdown

Parser/formatter must be deterministic and tested.

Do not let UI silently "fix" malformed quiz source into a different question.

## Realtime

- REST database state is authoritative.
- WebSocket events are hints/acceleration.
- WebRTC is media.
- Reconcile after reconnect.

## Documentation

Code changes that alter business behavior or architecture must update the relevant docs.

Do not leave accepted decisions only in PR comments/chat.
