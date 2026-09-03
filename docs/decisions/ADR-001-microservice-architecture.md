# ADR-001: Coarse-Grained Microservice Architecture

Status: **Accepted**

## Context

Quizopia 2.0 expands beyond the legacy modular monolith into:

- public quiz platform;
- classroom workflows;
- assessment/practice;
- community;
- AI authoring/tutor;
- realtime proctoring/video.

These areas have different scaling, security, data-ownership, and workload characteristics.

## Decision

Use coarse-grained microservices.

Initial services:

- Identity
- Quiz
- Classroom
- Assessment
- Community
- Proctoring
- AI

Do not split every aggregate into a separate service.

## Consequences

Positive:

- clearer ownership;
- independent scaling for AI/video vs transaction-heavy assessment;
- stronger security/data boundaries;
- better team/task partitioning.

Costs:

- distributed consistency;
- more deployment complexity;
- API/event contracts;
- observability needs;
- no cross-service database joins.

## Rules

- one service owns each piece of authoritative data;
- no cross-service DB access;
- new microservice requires an ADR/change review.
