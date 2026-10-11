# Proctoring Service

Independent Spring Boot 4.1.1 / Java 21 project for the Quizopia 2.0 platform scaffold.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

The project contains technical health/metrics and OpenAPI configuration only. No Quizopia business endpoints, entities, repositories, migrations, or event classes are implemented.

Default local port: 8086. See `.env.example` and the root development documentation for configuration.

## LD-05 admission boundary

See the [exact protected-route inventory and deployment gates](../../docs/architecture/ld05-enforcement.md).
Admission is independently disabled by default; selected Classroom operations
fail closed while disabled/unconfigured. Assessment and Proctoring business
routes remain denied until exact reviewed adapters exist. Provision this
deployment's own least-privilege SERVICE client and runtime secret. No Wave 3
business feature or Identity activation is enabled by this prerequisite.

Do not route activated protected traffic to bypass-capable older Gateway or
downstream instances. Drain/block old instances before activation or rollback.
