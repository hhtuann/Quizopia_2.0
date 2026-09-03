# Testing Strategy

Status: **Baseline v0.1**

## Goals

Quizopia 2.0 must preserve the strong backend correctness discipline of the legacy project and correct the legacy frontend testing gap.

## Backend

Use:

- JUnit 5;
- Spring Boot Test where appropriate;
- Testcontainers with real PostgreSQL;
- architecture tests such as ArchUnit;
- focused unit tests for pure domain logic.

High-priority backend test areas:

- OTP expiry/retry/rate behavior;
- account linking;
- role authorization;
- classroom invitation claim;
- quiz parser/validation;
- immutable versions;
- publication time-extension rules;
- sequence-aware autosave;
- concurrent start;
- submit idempotency;
- timeout finalization;
- grading;
- guest attempt behavior;
- result/review policy;
- event outbox/idempotent consumers;
- proctor eligibility.

## Frontend

Use:

- Vitest;
- React Testing Library.

High-priority frontend tests:

- auth session bootstrap;
- workspace/persona switching;
- quiz Markdown preview/parser error mapping;
- autosave edit-during-flight behavior;
- submit idempotency-key reuse;
- countdown/server-time behavior;
- review-policy rendering;
- proctoring permission/error UI;
- realtime reconnect/reconciliation.

## End-to-end

Use Playwright.

Critical E2E flows:

- local register -> Gmail verification (test mail environment) -> login;
- Google auth via safe test strategy/mocked provider where appropriate;
- teacher enables role;
- teacher creates class;
- manual invite before student account exists;
- student registers and receives class membership;
- create/publish quiz;
- public guest attempt;
- class assignment attempt;
- proctored eligibility flow;
- submit/result;
- community contribution/copy quiz.

## Contract tests

Services need API/event contract tests.

Cross-service compatibility should not depend on shared database fixtures.

## Concurrency

Use real PostgreSQL for:

- autosave sequence;
- submit idempotency;
- active attempt uniqueness;
- invitation claim race;
- publication timing update races.

## Security

Add tests that prove learners cannot receive:

- answer keys before allowed;
- another learner's attempt;
- unauthorized proctor evidence;
- another teacher's private classroom/quiz data.
