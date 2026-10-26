# Assessment Service

The private Proctoring authority contract requires an explicit `HISTORY` or
`LIVE_MEDIA` purpose. Teacher live authority additionally uses separate narrow
Identity/Classroom target-Student scopes and current checks; historical evidence
authorization cannot be reused for media. All new target checks default disabled.
See [the correction contract/evidence](../../docs/architecture/w3m-correction-evidence.md).

## W4-B2 realtime subscription authority

The private `POST /internal/realtime/subscription-authorizations` decision is
default-disabled (`ASSESSMENT_REALTIME_AUTHORITY_ENABLED=false`) and requires the
exact dedicated Gateway realtime SERVICE client: RS256 `principal_type=SERVICE`,
`sub` equal to `ASSESSMENT_REALTIME_GATEWAY_CLIENT_ID`, audience exactly
`quizopia-assessment-realtime-authority` and scope
`assessment.realtime.subscription-authorize`. The original USER JWT is validated
independently (signature, issuer, timestamps, roles, fresh LD-05 admission).
`ATTEMPT_OWNER` requires current Student ownership of an ACTIVE attempt under an
OPEN publication before its deadline; `PUBLICATION_TEACHER` requires the owning
Teacher of an OPEN publication. CLASS publications re-verify current Classroom
membership/teacher authority through the existing authorized integration.
Missing, ineligible or foreign resources are masked with 404 `NOT_ACCESSIBLE`;
uncertain dependencies fail closed with 503. There is no positive decision
cache and the route is never exposed through the public Gateway.

Independent Spring Boot 4.1.1 / Java 21 Assessment service.

## W4-B3 realtime invalidation outbox

A dedicated transactional outbox (V7, independent from the W3 monitoring
outbox) captures committed Publication OPEN/meaningful-extend/CLOSED and
Attempt creation/submitted/abandoned transitions via AFTER triggers gated by
the `quizopia.realtime_invalidation` connection GUC. Only the frozen six-field
metadata (`eventId`, `eventType`, `eventVersion`, `resourceKind`,
`resourceId`, `occurredAt`) is stored; no answers, grades, identities or
monitoring data. Rollback discards events; an outbox failure aborts the
writer. The bounded publisher dispatches with confirms to
`quizopia.realtime.invalidation.v1` (mandatory=false per R3 best-effort
semantics), with leased claims, exponential backoff and poison-event
quarantine. `ASSESSMENT_REALTIME_INVALIDATION_CAPTURE_ENABLED=false` and
`ASSESSMENT_REALTIME_INVALIDATION_PUBLISHER_ENABLED=false` default OFF;
production activation requires owner acceptance.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

Implements the seven default-disabled W3-A Publication operations and W3-B1
Publication configuration plus PostgreSQL Attempt/result persistence. W3-C1 adds
an internal deterministic grader and atomic manual/timeout finalization core,
pending independent review. See the
[implemented Publication contract](contracts/publications.openapi.yaml) and
[W3-B1 implementation evidence](../../docs/architecture/w3b1-persistence-implementation.md).
See also [W3-C1 implementation evidence](../../docs/architecture/w3c1-grading-finalization-implementation.md).
Merged B2 supplies Student Attempt/submission/result/review. B3 adds Teacher results,
CLOSE, REQUEST/CANCEL/COMPLETE and the recipient-owned inbox. See
[B3 implementation and verification](../../docs/architecture/w3b3-closure-notification-implementation.md).
The internal finalizer requires a root transaction; callers must establish current
authority separately. The bounded closure worker defaults OFF.

Default local port: 8084. See `.env.example` and the root development documentation for configuration.

## LD-05 admission boundary

### T3 FE-01 owner Publication directory

`GET /api/assessments/publications` requires a validated USER with TEACHER and
fresh current LD-05 admission, behind the existing `PUBLICATION_ENABLED` gate.
The database query filters exclusively by JWT-derived owner. Optional `state`
(`DRAFT|OPEN|CLOSED`) and `search` (literal title substring, stripped/lowercased,
maximum 300 characters) apply server-side. `limit` defaults to 20, range 1–100.
Unknown/duplicate parameters and invalid/foreign/filter-mismatched cursors return
400 `INVALID_REQUEST`. Responses are `Cache-Control: no-store`.

The safe `{items,nextCursor}` projection contains only Publication id/title,
audience/state, start/end/created times, revision and Quiz/version references.
Ordering is immutable `created_at DESC,id DESC`, with V8 owner/order and
owner/state/order indexes. Draft edits and lifecycle updates cannot move rows.
This is live keyset pagination: filter membership can change during traversal;
it is not a frozen snapshot. No total count or answer-bearing data is returned.

Continuation uses the existing `ASSESSMENT_CURSOR_ACTIVE_KEY` /
`ASSESSMENT_CURSOR_KEY_V1` deployment-backed HMAC key ring, domain-separated for
this route and bound to owner plus normalized filters; changing page size is
allowed. Provision the existing cursor key even if inbox/results are disabled.
Without an active key, issuing a continuation fails closed with sanitized 503
`PERSISTENCE_UNAVAILABLE`. Key rotation retains configured verification keys.
The exact wire schema is in `contracts/publications.openapi.yaml`.

See the [exact protected-route inventory and deployment gates](../../docs/architecture/ld05-enforcement.md).
Admission and Publication are independently disabled by default. Exact Publication
adapters enforce fresh authority; unknown/future business routes remain denied.
`ATTEMPTS_ENABLED`, `RESULTS_ENABLED` and `CLOSURE_ENABLED` default false; setting
them true opens only the exact implemented route inventory. The inbox has an
independent `ASSESSMENT_INBOX_ENABLED=false` switch. Provision this
deployment's own least-privilege SERVICE client and runtime secret. No Wave 3
business feature or Identity activation is enabled by this prerequisite.

Do not route activated protected traffic to bypass-capable older Gateway or
downstream instances. Drain/block old instances before activation or rollback.

## Default-disabled authoritative monitoring metadata

The dependent integration increment adds V6 capture/outbox and a confirmed RabbitMQ
publisher for [answer-persistence metadata](../../docs/specifications/w3m-authoritative-answer-integration.md).
All six prior Assessment migrations, answer repository/finalizer and public API remain
unchanged. `ASSESSMENT_MONITORING_CAPTURE_ENABLED=false` and
`ASSESSMENT_MONITORING_PUBLISHER_ENABLED=false` are independent defaults. A typed
capture opt-in fixes a dedicated connection GUC at pool startup; capture/answer
rollback is atomic and no broker I/O occurs in the answer transaction. Broker outage
leaves durable metadata pending; local DB capture failure rolls back coherently.

Provision the exact dedicated private-vhost exchange/ACL and trusted TLS separately;
no runtime topology declarations or production credentials are supplied. Production
capture/publication is unconditionally rejected pending privacy/security acceptance.
The fact contains bindings/question/revision/time only, no answer, answer digest,
Quiz key or JWT, and can reflect server normalization after deadline rather than a
Student action. No final result/gradebook event or retention policy is introduced.
