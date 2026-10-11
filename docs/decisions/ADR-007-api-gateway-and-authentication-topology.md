# ADR-007: Spring Cloud Gateway and User Authentication Topology

Status: **Accepted**

## Decision

Use Spring Cloud Gateway as the public business/API edge.

Browser business HTTP calls go through the Gateway.

Protected user requests carry Quizopia-issued short-lived RS256 JWT access tokens.

Gateway verifies the JWT, and each target protected microservice verifies the JWT independently using Quizopia JWKS/public signing material.

Identity Service uses Spring Authorization Server and owns user token issuance, refresh/session security, Gmail/local auth, and Google account linking.

Refresh uses rotating opaque HttpOnly tokens stored server-side only as hashes, with family lineage/reuse detection.

Admin/account disable supports near-immediate Redis-backed revocation checks rather than waiting only for JWT expiry.

WebRTC media to LiveKit is not proxied as business REST through Gateway.

## Consequences

- stronger defense in depth than "gateway validates, service blindly trusts";
- services do not query Identity DB on every authenticated request merely to re-resolve roles;
- revocation introduces a Redis security dependency. The later
  [W3-A LD-05 follow-up](../specifications/w3-a-publication-scheduling-monitoring-policy.md#ld-05--redis-revocation-outage-and-recovery)
  accepts fail-closed/no-automatic-Identity-fallback/no-degraded-authenticated-read
  behavior for affected protected Assessment/Classroom/Proctoring operations in
  initial Wave 3, until required authority is trustworthy. Connectivity alone is
  insufficient for recovery. Implementation/recovery/verification contracts
  remain open; this does not change service ownership or rewrite authentication.
