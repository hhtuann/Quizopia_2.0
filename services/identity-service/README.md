# Identity Service

Independent Spring Boot 4.1.1 / Java 21 project for the Quizopia 2.0 platform scaffold.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

The service includes the in-progress Identity authentication core and technical
health/metrics and OpenAPI configuration. The internal email-verification core
persists and verifies caller-supplied OTP material, enforces explicit challenge
policy, and atomically reuses trusted account activation. See
[the Step 10 implementation notes](../../docs/development/identity-email-verification-core.md).
Email-verification HTTP endpoints, OTP generation, email delivery, and production
OTP policy remain deferred.

Default local port: 8081. See `.env.example` and the root development documentation for configuration.
