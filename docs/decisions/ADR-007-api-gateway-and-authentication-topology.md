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
- original baseline: services do not query Identity DB on every authenticated request merely to re-resolve roles (amended for affected LD-05 operations below);
- revocation introduces a Redis security dependency. The later
  [W3-A LD-05 follow-up](../specifications/w3-a-publication-scheduling-monitoring-policy.md#ld-05--redis-revocation-outage-and-recovery)
  accepts fail-closed/no-automatic-Identity-fallback/no-degraded-authenticated-read
  behavior for affected protected Assessment/Classroom/Proctoring operations in
  initial Wave 3, until required authority is trustworthy. Connectivity alone is
  insufficient for recovery. Implementation/recovery/verification contracts
  remain open; this does not change service ownership or rewrite authentication.

## Amendment — approved LD-05 Candidate B

The Leader-approved Candidate B security architecture supersedes the original
no-per-request-Identity-query consequence for affected Wave 3 protected
Assessment/Classroom/Proctoring operations. They must use always-required primary
Identity admission with complete Redis authority equality, per-actor recovery,
a coherent PostgreSQL READ COMMITTED statement snapshot, and narrow dual
SERVICE/USER evidence. This is the normal admission path, not an automatic
outage fallback. No positive cache, JWT-only fallback, global READY generation
or one-second lease is accepted.

See [Identity authority admission](../architecture/identity-authority-admission.md)
for the bounded Identity implementation, precise snapshot/in-flight limitation,
credential propagation, failure/recovery and mandatory capacity, rollout,
transport, primary-failover and security verification gates. The original
consequences above remain historical context. This amendment records the Leader
approval without inventing its original date or attributing it to an unrelated PR.

Identity capability alone does not implement Gateway or downstream enforcement.
Their adapters, protected route inventories and real-topology tests are still
required; no production activation or Wave 3 API enablement follows from this PR.
USER roles/cutoffs and SERVICE scopes/token lifecycle remain distinct under
ADR-014 and ADR-008. No cross-service database access is introduced.
