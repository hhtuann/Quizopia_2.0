# Proctoring Service

Independent Spring Boot 4.1.1 / Java 21 service. The current W3-M1 increment adds
Proctoring-owned PostgreSQL/Flyway session, activity evidence and access-audit
persistence, five exact USER endpoints and narrow Assessment authority integration.
See the [M1 contract](../../docs/specifications/w3-m-proctoring-contract.md),
[implemented OpenAPI](contracts/monitoring.openapi.yaml) and
[checkpoint](../../docs/architecture/w3m-backend-implementation.md).

Run `./mvnw verify` (Windows: `mvnw.cmd verify`). PostgreSQL integration tests use
owned Testcontainers. Default local port is 8086; `.env.example` lists configuration.

`PROCTORING_ENABLED=false` closes the business adapters. Enabled local synthetic
integration also requires this service's own Identity Client Credentials admission
client and `PROCTORING_ASSESSMENT_BASE_URL`, with the narrow
`assessment.proctoring-authority.read` scope. Assessment must separately allowlist
that exact caller. Each request requires current USER and resource authority;
Gateway approval or JWT signature alone never substitutes for downstream admission.
HTTP exceptions are explicit loopback-only local test settings; use private HTTPS
with validated hostname/trust for deployment.

Production-profile enablement remains closed until actual M2 bundle integration
and product/privacy/security acceptance. M1 is not an Activity-only product mode.
No default retention period exists. The owner-controlled `monitoring_purge_expired`
function has no PUBLIC execution grant or scheduler and only deletes already-expired
evidence/audit rows. Runtime roles must not own tables/functions, disable guards or
receive that retention privilege. Session-binding erasure and final policy/control
parity still require an approved policy and reviewed implementation.

The dependent M2 increment adds [four bodyless media-grant operations](contracts/media.openapi.yaml),
durable room/grant intent and bounded recovery through the official LiveKit JVM SDK.
See the [M2 contract](../../docs/specifications/w3-m2-media-contract.md).
`PROCTORING_MEDIA_ENABLED=false` and `PROCTORING_MEDIA_WORKER_ENABLED=false` remain
independent defaults. `UNVERIFIED` mode denies issuance/renewal; explicit non-production
qualification configuration is a synthetic operator attestation, not provider
approval. Enabled production media is unconditionally rejected. App `leaseUntil`
is not an SFU hard expiry; self-hosted removal cannot revoke an old token. Never
store/log the returned bearer; actual Cloud/refresh/clock-bound revocation and
privacy acceptance remain open. The authoritative Assessment answer-change event
adapter is separate remaining work. Browser answer-change observations are untrusted;
this service cannot alter answers, grades, deadlines or Attempt state. No recordings,
clipboard, URLs, keystrokes, answer content or academic sanctions are implemented.
Drain/block bypass-capable older Gateway/downstream instances before any future
separately authorized activation. Deployment/independent review is not inferred
from local tests or a source merge.
