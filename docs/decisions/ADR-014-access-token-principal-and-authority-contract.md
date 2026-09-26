# ADR-014: Access-Token Principal and Authority Contract

Status: **Accepted**

## Context

Quizopia issues both user access tokens and OAuth2 Client Credentials service
tokens from Identity Service. Gateway and each protected service validate these
tokens independently.

The existing architecture did not define how consumers distinguish user and
service principals, obtain the internal user ID, or map Identity roles without
conflating them with OAuth service scopes. This blocked owner-aware and
role-aware business endpoints.

## Decision

All Quizopia access tokens contain a case-sensitive `principal_type` claim.

For user tokens:

- `principal_type` is `USER`;
- `sub` is the internal Quizopia user UUID string;
- `roles` is a JSON array containing unprefixed Identity global role names;
- allowed role values are `STUDENT`, `TEACHER`, and `ADMIN`;
- `scope` is absent.

For Client Credentials service tokens:

- `principal_type` is `SERVICE`;
- `sub` is the OAuth client ID/service identity;
- `scope` is a mandatory, non-empty JSON array of OAuth service-scope strings;
- every `scope` element is a non-empty, non-blank string;
- `roles` is absent.

Both token kinds retain the standard `iss`, `iat`, and `exp` claims and use
Quizopia RS256 signing keys.

Spring resource servers use these exact authorities:

- `TOKEN_USER` for a valid user principal;
- `ROLE_STUDENT`, `ROLE_TEACHER`, and `ROLE_ADMIN` from user `roles`;
- `TOKEN_SERVICE` for a valid service principal;
- exactly one `SCOPE_<scope-value>` for each accepted service scope.

User-only endpoints require `TOKEN_USER`. Teacher-only user endpoints require
both `TOKEN_USER` and `ROLE_TEACHER`. Service endpoints require
`TOKEN_SERVICE` and the applicable `SCOPE_*` authority.

Consumers reject missing or unknown `principal_type`, non-UUID user subjects,
unknown role values, and tokens that mix user roles with service scopes. They
must not infer principal type by parsing `sub`.

For service tokens, consumers reject a missing or empty `scope`, every non-array
JSON representation (including a scalar or object), and arrays containing null,
non-string, empty, or blank values. They validate the complete service claim set
before granting `TOKEN_SERVICE` or any `SCOPE_*` authority. A service token that
contains `roles` is rejected.

## Consequences

Classroom and other services can derive an authenticated caller UUID from
`sub` only after requiring `TOKEN_USER`. A valid Client Credentials token
cannot satisfy a user-only endpoint.

Global user roles and OAuth service scopes remain separate authorization
namespaces. Existing service scope names and Client Credentials behavior remain
unchanged apart from the new explicit `principal_type=SERVICE` claim.

User-token issuance remains a reusable Identity security capability. Login and
refresh HTTP flows may invoke it later; this ADR does not introduce those
endpoints or redesign refresh sessions.

The user access-token TTL and service access-token TTL remain independently
configurable short-lived durations. When the user TTL is omitted, it uses the
configured service-token TTL for backward-compatible deployment configuration.
