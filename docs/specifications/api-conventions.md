# API Conventions

Status: **Baseline v0.1**

## General

- External APIs are versioned/evolvable.
- Do not serialize persistence entities directly.
- Use explicit request/response contracts.
- Validate input at the boundary and again at the domain layer where needed.
- Use stable machine-readable error codes.
- Never expose secrets, answer keys, internal SQL, stack traces, or sensitive auth details.

## Error envelope

Adopt a consistent error shape conceptually similar to the legacy system:

```json
{
  "code": "PUBLICATION_TIME_CANNOT_BE_REDUCED",
  "message": "Publication time cannot be reduced after attempts have started.",
  "status": 409,
  "path": "/...",
  "traceId": "..."
}
```

Exact field names may be finalized by the API contract.

## HTTP semantics

Use HTTP methods/statuses consistently.

Examples:

- `400` invalid request syntax/validation;
- `401` unauthenticated;
- `403` authenticated but unauthorized;
- `404` not found / anti-enumeration where appropriate;
- `409` business-state conflict;
- `422` may be used for structured parse/domain validation if the team standardizes it.

Do not mix meanings arbitrarily across services.

## Idempotency

Correctness-sensitive commands that are retried by clients must have explicit idempotency semantics where applicable.

Attempt submit is mandatory idempotent behavior.

## Pagination

List endpoints with unbounded growth must use a documented pagination scheme.

Exact cursor vs page-number convention is TBD.

## OpenAPI

Preferred baseline:

- services publish OpenAPI contracts;
- frontend TypeScript API types/clients are generated or derived from the contracts where practical.

The goal is to avoid manual Java DTO vs TypeScript DTO drift.

## Authentication

Clients send authenticated access credentials according to the final Identity architecture.

Frontend route guards are UX only; every service must enforce authorization server-side.

## Public identifiers

Do not expose guessable sequential database IDs as the only public-share identifier for public quizzes.

Use opaque/random public identifiers/slugs for public URLs.

Internal database IDs may remain numeric.
