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
