# API Conventions

Status: **Accepted scaffold-level conventions v0.3**

## Public edge

Browser business APIs go through Spring Cloud Gateway.

Do not expose independent business-service ports as the normal public browser API.

WebRTC/LiveKit is a separate media path.

## Contracts

Every service exposes an explicit API contract.

Preferred baseline:

- Spring endpoints/DTOs -> OpenAPI contract;
- frontend TypeScript types/client generated or derived from OpenAPI;
- contract artifacts may be coordinated under `shared/api-contracts/` without sharing domain/persistence models.

Do not serialize persistence entities directly.

Do not maintain contradictory handwritten frontend DTOs when generated contracts are available.

## Internal REST

Synchronous service-to-service calls:

- use direct internal service URLs/DNS;
- do not route through the public gateway;
- authenticate with OAuth2 Client Credentials service JWTs;
- use least-privilege service scopes.

## Error envelope

Use stable machine-readable error codes.

Conceptual shape:

```json
{
  "code": "PUBLICATION_TIME_CANNOT_BE_REDUCED",
  "message": "Publication time cannot be reduced after attempts have started.",
  "status": 409,
  "path": "/...",
  "traceId": "..."
}
```

Exact final field names may be standardized during scaffold/API implementation.

Do not expose stack traces, SQL, credentials, answer keys, or sensitive internals.

## HTTP semantics

Use methods/statuses consistently.

Examples:

- `400` invalid request/validation;
- `401` unauthenticated/invalid credential;
- `403` authenticated but unauthorized;
- `404` not found / anti-enumeration where appropriate;
- `409` business-state/conflict;
- use of `422` requires one repository-wide convention rather than per-service improvisation.

## Idempotency

Correctness-sensitive retriable commands require explicit idempotency semantics where appropriate.

Assessment submit is mandatory idempotent behavior.

## Pagination

New unbounded collection APIs use cursor pagination by default.

- cursors are opaque to clients;
- ordering must be deterministic and encoded by the cursor;
- APIs may define bounded `limit` defaults/minimums/maximums in their accepted contract;
- malformed cursors and invalid limits return `400` using the standard API error convention;
- total counts are optional and must be explicitly required by the endpoint contract.

## Authentication

User calls carry Quizopia-issued access JWTs.

Gateway and target service independently validate protected user tokens.

Frontend route guards are UX only.

## Public identifiers

Public-share URLs must not rely only on guessable sequential database IDs.

Use opaque/random public identifiers/slugs.

Exact slug format remains TBD.
