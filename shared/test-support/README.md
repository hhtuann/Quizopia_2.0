# Test support

`shared/test-support` is reserved for deliberately stable, domain-neutral
technical test support. Backend tests use focused JUnit 5 tests, Spring Boot
Test where appropriate, ArchUnit, and Testcontainers.

## Service ownership

Each service owns its business fixtures, domain-specific builders and test
utilities, JPA entities, repositories, Flyway migrations, database initialization
and data, and test database state. Persistence integration tests exercise that
service-owned boundary.

Cross-service tests must not depend on shared database fixtures, direct access
to another service's database or tables, or a common persistence model. None of
those concerns belong in this directory.

## Container scope

A smoke or context-load test may omit a PostgreSQL container when it verifies
only technical wiring that does not depend on persistence behavior. A test that
does depend on PostgreSQL, JPA, Flyway, SQL constraints, or transaction semantics
must use real PostgreSQL Testcontainers rather than H2 or substitute database
behavior. This does not require every Spring test to start a database container.

Use RabbitMQ or Redis Testcontainers only when a test specifically needs the
real semantics of that technology. They are not required for every backend
test.

## Sharing criteria

Prefer local, service-owned test setup until a repeated, stable, technical
abstraction is demonstrated. Consider extraction here only when the support:

- is repeated across multiple services;
- is entirely technical and independent of business/domain models and
  service-specific tables;
- does not create a runtime dependency from a service to shared test code; and
- provides reuse that clearly outweighs its coupling and dependency cost.

No repeated stable need has been demonstrated yet, so this directory currently
provides no shared helper code, test artifact, or Maven module. Services remain
independently buildable and own their test setup.
