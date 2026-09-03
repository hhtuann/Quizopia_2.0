# Quizopia 1.x Reference Summary

Status: **Reference only**

Quizopia 2.0 specifications take precedence over this file.

## Legacy technology baseline

The reviewed Quizopia 1.x project used:

### Backend

- Java 21
- Spring Boot 4.1
- Spring Web MVC
- Spring Data JPA / Hibernate
- Spring Security
- OAuth2 Resource Server + JWT
- Spring WebSocket / STOMP
- Flyway
- Bouncy Castle / Argon2id
- Apache POI
- JUnit 5 + Testcontainers

### Frontend

- Next.js 16
- React 19
- TypeScript
- Tailwind CSS 4
- TanStack Query
- Axios
- STOMP client
- React Hook Form
- Zod
- Zustand
- Framer Motion

### Data/infrastructure

- PostgreSQL 17
- Redis 7.2 provisioned but unused by core business logic
- MinIO provisioned but unused
- Mailpit provisioned but unused
- Docker Compose

## Legacy architecture

The backend was a package-oriented modular monolith with modules including:

- identity;
- security;
- authentication;
- user;
- academic;
- classroom;
- question;
- exam;
- attempt;
- grading;
- realtime;
- notification.

The frontend used Next.js App Router with role-oriented route areas and separated server state (TanStack Query) from client/working state (Zustand).

## Strong legacy ideas to preserve

- immutable published exam snapshots;
- stable attempt question/option ordering;
- same-tenant/school database constraints;
- sequence-aware autosave via atomic PostgreSQL logic;
- idempotent submit with stored response;
- submit/grading/idempotency in one transaction;
- after-commit realtime events;
- server-authoritative deadlines;
- pure deterministic grading core;
- exact decimal score arithmetic;
- no answer-key leakage;
- refresh-token rotation/reuse detection;
- Flyway-owned schema;
- Testcontainers with real PostgreSQL;
- Excel formula-injection protection.

## Legacy problems not to copy

- competing session eligibility models;
- mutable rows described as "versions";
- unused/contradictory grading/release lifecycle states;
- read-triggered session state transitions;
- module boundary leaks through cross-repository access;
- oversized orchestration services;
- weak frontend automated test coverage;
- unused Redis/MinIO/mail infrastructure;
- realtime limitations tied to a single-process simple broker;
- stale docs diverging from implementation.

For full detail, read `legacy-analysis.md`.
