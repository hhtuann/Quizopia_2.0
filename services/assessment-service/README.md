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
Student Attempt, submission, result/review, CLOSE and inbox APIs remain unavailable.
The internal finalizer requires a root transaction; callers must establish current
authority separately. No scheduler is activated.

Default local port: 8084. See `.env.example` and the root development documentation for configuration.

## LD-05 admission boundary

See the [exact protected-route inventory and deployment gates](../../docs/architecture/ld05-enforcement.md).
Admission and Publication are independently disabled by default. Exact Publication
adapters enforce fresh authority; unknown/future business routes remain denied.
`ATTEMPTS_ENABLED`, `RESULTS_ENABLED` and `CLOSURE_ENABLED` default false; setting
them true exposes no additional route in this increment. Provision this
deployment's own least-privilege SERVICE client and runtime secret. No Wave 3
business feature or Identity activation is enabled by this prerequisite.

Do not route activated protected traffic to bypass-capable older Gateway or
downstream instances. Drain/block old instances before activation or rollback.
