# ADR-006: True Monorepo with Independent Service Builds

Status: **Accepted**

## Decision

Use one Git repository for Quizopia 2.0.

Top-level structure includes one frontend, one gateway, seven backend business-service directories, shared technical contracts/test support, infrastructure, scripts, and docs.

Each backend service/gateway is an independent Maven project with its own `pom.xml`.

Use package root `com.quizopia`.

Do not use Git submodules for frontend/backend as in Quizopia 1.x.

## Shared-code constraint

`shared/` may contain contract/schema/test tooling, but not shared JPA entities, repositories, or business-domain services.

## Rationale

A monorepo makes coordinated frontend/API/infrastructure changes and four-person collaboration easier while independent builds/deployments preserve microservice boundaries.
