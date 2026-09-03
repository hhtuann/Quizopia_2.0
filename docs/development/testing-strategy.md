# Testing Strategy

Status: **Accepted baseline v0.2**

## Goals

Preserve the strong correctness testing of Quizopia 1.x while adding first-party frontend and distributed-system coverage.

## Backend

Use:

- JUnit 5;
- Spring Boot Test where appropriate;
- Testcontainers;
- ArchUnit;
- focused pure unit tests.

Use real PostgreSQL Testcontainers for correctness-sensitive persistence behavior rather than H2 substitution.

Use RabbitMQ/Redis Testcontainers when a test specifically needs their real semantics.

High-priority areas:

- OTP lifecycle/security;
- account linking;
- revocation;
- OAuth2 service authentication;
- classroom invitation claim;
- Quiz Markdown parser/validation;
- immutable versions;
- publication timing rules;
- autosave sequence;
- concurrent attempt start;
- submit idempotency;
- timeout finalization;
- grading rules once finalized;
- guest attempts;
- review policies;
- transactional outbox;
- idempotent consumers;
- proctor eligibility.

## Frontend

Use:

- Vitest;
- React Testing Library.

High-priority areas:

- auth bootstrap/refresh queue;
- learning/teaching workspace switching;
- generated API client integration boundaries;
- quiz editor preview/error mapping;
- autosave edit-during-flight;
- idempotency key reuse;
- server-time countdown;
- review-policy rendering;
- proctor permission/error UX;
- WebSocket reconnect/reconciliation.

## E2E

Use Playwright.

Critical journeys eventually include:

- local register -> OTP verify -> login;
- Google login/account linking through a safe test strategy;
- teacher enablement;
- classroom creation/invitation claim;
- quiz authoring/publish;
- public guest attempt;
- class attempt;
- proctor eligibility/start;
- submit/result;
- community contribution/copy.

## CI execution

PR:

- path-aware/affected checks;
- targeted smoke/E2E where practical.

`develop` / `main`:

- comprehensive build/tests;
- critical E2E suite according to CI environment capability.

## Contract testing

API/event contracts must be tested/versioned.

Cross-service tests must not depend on shared database fixtures or direct cross-service tables.

## Security tests

Prove that learners cannot access:

- hidden answer keys;
- another learner's attempt;
- unauthorized proctor evidence;
- private classroom/quiz data they do not own/have access to.
