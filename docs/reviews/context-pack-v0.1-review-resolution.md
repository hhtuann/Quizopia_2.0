# Context Pack v0.1 Independent Agent Review — Resolution

Status: **Review checkpoint completed before v0.2**

## Review result

Independent read-only reviews by Codex and Claude showed strong alignment on:

- product vision and roles;
- seven coarse-grained service boundaries;
- `Quiz -> QuizVersion -> Publication -> Attempt` separation;
- REST/WebSocket/WebRTC responsibilities;
- classroom/community/AI/proctoring concepts;
- inherited legacy correctness invariants;
- separation of accepted vs TBD material.

This validated that Context Pack v0.1 was understandable to independent repository agents.

## Issues promoted into v0.2

The reviews identified several useful gaps:

- per-question-type grading policy was missing entirely;
- Proctoring vs Assessment ownership of answer-change/question-navigation evidence was ambiguous;
- exact Publication delivery-snapshot finalization point was unspecified;
- integration event naming for classroom assignment/result/gradebook needed normalization/clarification;
- Community cross-service reference behavior remains feature-level TBD;
- practice persistence remains feature-level TBD;
- NUMERIC_FILL intentionally overrides a legacy recommendation and deserved an explicit rationale ADR.

These are now represented in `docs/open-questions.md` and ADR-013 where applicable.

## Pre-scaffold blockers resolved after review

The architecture interview accepted:

- monorepo/repository structure;
- Spring Cloud Gateway;
- Spring Authorization Server and RS256/JWKS topology;
- rotating opaque refresh tokens;
- Redis-backed immediate revocation;
- OAuth2 Client Credentials service auth;
- internal REST + RabbitMQ event split;
- transactional outbox;
- database-per-service credentials;
- MinIO/S3 abstraction;
- pgvector baseline;
- Mailpit;
- hybrid local development;
- GitHub Actions;
- code-quality/test gates;
- OpenTelemetry/Actuator/Micrometer;
- config/secrets rules.

## Items intentionally not resolved by v0.2

Feature-level behavior such as Quiz Markdown grammar, grading formulas, publication result/review policies, practice persistence, guest identity details, proctor evidence policy, Community reference model, and exact import templates remain open intentionally.

They do not block repository scaffold but must be resolved before implementing the affected feature domain.
