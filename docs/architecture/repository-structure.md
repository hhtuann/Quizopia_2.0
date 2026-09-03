# Repository Structure

Status: **Accepted — Context Pack v0.2**

## Decision

Quizopia 2.0 uses one true monorepo.

It does not use the Quizopia 1.x frontend/backend Git-submodule structure.

## Planned layout

```text
Quizopia_2.0/
├── frontend/
├── gateway/
├── services/
│   ├── identity-service/
│   ├── quiz-service/
│   ├── classroom-service/
│   ├── assessment-service/
│   ├── community-service/
│   ├── proctoring-service/
│   └── ai-service/
├── shared/
│   ├── api-contracts/
│   ├── event-contracts/
│   └── test-support/
├── infrastructure/
├── scripts/
├── docs/
├── .github/
├── AGENTS.md
├── CLAUDE.md
└── README.md
```

Exact secondary folder names may be adjusted during scaffold if they do not change the accepted ownership model.

## Backend builds

Every backend service and the gateway are independently buildable Maven projects with their own `pom.xml`.

There is no required Maven multi-module parent build that makes all business services one deployable/build unit.

Framework/dependency versions should nevertheless be centrally monitored/aligned through repository automation and scaffold conventions.

## Java packages

Root package:

```text
com.quizopia
```

Examples:

```text
com.quizopia.identity
com.quizopia.quiz
com.quizopia.classroom
com.quizopia.assessment
com.quizopia.community
com.quizopia.proctoring
com.quizopia.ai
```

Gateway uses an appropriate `com.quizopia...` package under the same organization root.

## Frontend

Use one Next.js application.

Do not create independent student/teacher/admin frontend repositories/apps by default.

The one frontend may use route groups/workspaces for public, auth, learning, teaching, and admin experiences.

## Shared directory

Allowed examples:

- API schema/contract tooling;
- integration-event schema contracts;
- reusable test support;
- generated/technical artifacts deliberately shared across consumers.

Forbidden coupling:

- shared JPA entities;
- shared repositories;
- shared persistence model;
- shared business-domain services;
- a common domain jar that effectively recreates a distributed monolith.

## Team implication

The monorepo makes cross-service/frontend/API changes visible in one pull request while preserving independent service build/deployment boundaries.
