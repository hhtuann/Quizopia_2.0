# Assessment Service

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
