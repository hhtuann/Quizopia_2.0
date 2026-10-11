# ADR-002: Local + Google Authentication Linked to One Internal User

Status: **Accepted**

## Context

Quizopia supports traditional credentials and Google login while maintaining one internal user identity.

Microservices also require a consistent Quizopia authorization/token topology.

## Decision

One internal Quizopia user may have:

- local username/password credential;
- Google provider identity.

Local registration requires Gmail OTP verification before activation.

Every activated user receives `STUDENT` and may later add `TEACHER`.

Safe Google login matching an already-verified Gmail identity links to the same Quizopia user rather than creating a duplicate.

Identity Service acts as the Quizopia Authorization Server using Spring Authorization Server.

Quizopia services use Quizopia-issued tokens, not Google access tokens.

User access tokens are short-lived RS256 JWTs.

Refresh tokens are opaque HttpOnly credentials with server-side hashing, rotation, refresh-family lineage, and reuse detection.

Gateway and every protected service validate user JWTs using Quizopia public signing material/JWKS.

Near-immediate account disable/revocation uses Redis-backed revocation state in addition to refresh/session invalidation.

Internal service authentication is defined by ADR-008.

## Open details

Still TBD:

- Gmail vs Workspace registration scope;
- OTP exact parameters;
- account-link conflict UX;
- exact token TTL values;
- Redis revocation implementation, authoritative recovery mechanism and security
  verification; the later [W3-A LD-05 follow-up](../specifications/w3-a-publication-scheduling-monitoring-policy.md#ld-05--redis-revocation-outage-and-recovery)
  accepts initial Wave 3 fail-closed/no-automatic-fallback/no-degraded-read policy.
  Broader outage contracts outside that scope remain open.
