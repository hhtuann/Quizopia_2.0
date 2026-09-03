# Quizopia 2.0 Agent Instructions

## Project

Quizopia 2.0 is a public quiz and learning platform.

This repository is a new implementation. It is NOT an incremental refactor of Quizopia 1.x.

## Mandatory context

Before non-trivial work, read the relevant files under:

- `docs/product/`
- `docs/architecture/`
- `docs/specifications/`
- `docs/decisions/`

For legacy behavior and lessons:

- `docs/reference/legacy-analysis.md`

Quizopia 2.0 documentation always takes precedence over legacy behavior.

## Architecture baseline

Quizopia 2.0 uses coarse-grained microservices.

Initial business services:

- Identity Service
- Quiz Service
- Classroom Service
- Assessment Service
- Community Service
- Proctoring Service
- AI Service

Do not create a new microservice or change service ownership without documenting the decision.

## Hard rules

- A service MUST NOT read or write another service's database.
- Cross-service communication MUST use documented APIs or events.
- PostgreSQL remains the primary source of truth for transactional business data unless an accepted ADR says otherwise.
- Published quiz versions are immutable.
- An active assessment attempt must not depend on Quiz Service availability.
- Authoritative business mutations use transactional HTTP APIs.
- WebSocket is for application realtime events, presence, monitoring signals, and server-time synchronization.
- WebRTC/LiveKit is for realtime media such as camera, microphone, or future screen sharing.
- WebRTC DataChannels must not be used as the authoritative persistence path for assessment answers.
- AI-generated quiz content must remain reviewable/editable before publication.
- Proctoring AI produces suspicious-event/risk signals; it does not automatically declare a student guilty or automatically fail an attempt.
- Standard browser proctoring must not claim it can inspect unrelated browser tabs, their URLs, or all applications running on the user's device.
- Database schema changes use Flyway. Do not enable Hibernate schema auto-creation/update in production.
- Do not expose answer keys/correctness to a learner before the publication review policy allows it.
- Do not silently change accepted business rules.

## Before coding

For every non-trivial task:

1. Read the relevant product, architecture, specification, and ADR files.
2. Inspect existing implementation and tests.
3. Identify the owning service(s).
4. Produce a short implementation plan.
5. Identify schema/API/event changes.
6. Implement only the documented scope.
7. Add or update tests.
8. Run the relevant checks.
9. Update documentation when behavior or architecture changes.

## Conflict handling

If code, tests, docs, or requirements conflict:

1. Stop expanding the implementation.
2. Report the exact conflict.
3. Identify the files/behavior involved.
4. Ask for a product/architecture decision when the precedence rules do not resolve it.

Never silently choose an interpretation for a material business rule.

## Changes requiring ADR review

Examples:

- new microservice;
- changing service ownership;
- changing REST/WebSocket/WebRTC responsibilities;
- introducing a new primary database technology;
- changing authentication trust boundaries;
- removing immutable published versions;
- changing authoritative attempt persistence;
- introducing synchronous dependencies into the active exam path.

## Agent collaboration

Prefer:

- one task -> one branch -> one primary implementation agent;
- a second agent may perform review without rewriting the same branch concurrently;
- humans perform final review before merge.
