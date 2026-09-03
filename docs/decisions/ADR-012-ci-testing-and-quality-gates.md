# ADR-012: CI, Testing, and Quality Gates

Status: **Accepted**

## Decision

Use GitHub Actions.

Pull requests use path-aware/targeted checks.

`develop` and `main` run comprehensive repository validation.

Backend quality baseline:

- Spotless;
- ArchUnit;
- JUnit;
- Testcontainers.

Frontend quality/testing baseline:

- ESLint;
- Prettier;
- TypeScript strict;
- Vitest;
- React Testing Library;
- Playwright E2E.

Correctness-sensitive persistence tests use real PostgreSQL Testcontainers rather than H2 substitution.

Use RabbitMQ/Redis containers when their real behavior is under test.

Framework/dependency versions remain independently declared per Maven project but are centrally monitored/aligned through repository automation.
