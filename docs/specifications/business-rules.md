# Business Rules

Status: **Baseline v0.2 with accepted Wave 3 Assessment Core policy**

For Wave 3, [Assessment Core policy](assessment-core-policy.md) governs the MVP
cut. Broader baseline capabilities below do not implicitly approve guest,
Classroom, configurable timing, or configurable visibility contracts.

## Identity

1. Every verified registered account receives `STUDENT`.
2. A verified user may additionally enable `TEACHER`.
3. `ACADEMIC_ADMIN` does not exist in Quizopia 2.0 baseline.
4. Local login and Google login may represent the same internal user.
5. Local registration requires Gmail verification by OTP before activation.
6. A user's learning/teaching workspace is UI context, not a destructive role switch.

## Classroom

7. Teacher may create owned classrooms.
8. Students may join via code/invitation URL according to the final join policy.
9. Teacher may manually add a student by required full name + Gmail.
10. If the Gmail has no existing account, keep a pending invitation rather than creating a fake user.
11. A later account that verifies the matching Gmail can claim the pending classroom membership.

## Quiz authoring

12. Teacher can organize quizzes in folders.
13. Quiz supports `SINGLE_CHOICE`, `MULTIPLE_CHOICE`, `TRUE_FALSE_MATRIX`, `NUMERIC_FILL`.
14. `NUMERIC_FILL` keeps an exact four-character answer token after surrounding-whitespace trim; accepted characters are ASCII digits plus an optional leading `-` and an optional single decimal point under the validation rules in `docs/specifications/quiz-markdown-spec.md`.
15. Quiz Markdown uses explicit `Câu <n> [TYPE]:` headers, multiline Markdown content, optional `Lời giải:`, and supported fenced code blocks; structural markers are recognized only at column 1 outside code fences. Backend validation is authoritative, and manually authored source is preserved rather than rewritten on save.
16. Published quiz versions are immutable.
17. Editing after publication creates later draft/version content; old published versions remain unchanged.

## Publication

18. Quiz content (`QuizVersion`) and delivery configuration (`Publication`) are separate.
19. Publication modes include `ASSESSMENT` and `PRACTICE`.
20. Assessment may be `PUBLIC` or `CLASS`.
21. Eligible public assessment may allow guest participation.
22. Class publication requires authenticated classroom membership.
23. Practice does not use assessment timing configuration.

## Assessment time mutation

24. Before attempts begin, editable timing follows normal publication rules.
25. After the first attempt starts, availability end must not move earlier.
26. After the first attempt starts, attempt duration must not be reduced.
27. Time may be extended according to authorization/business rules.

## Attempt correctness

28. Server time is authoritative.
29. Attempt question/option order is stable after creation.
30. Autosave must prevent older delayed writes from overwriting newer answers.
31. Submit must be idempotent.
32. Submit + grading + authoritative result persistence must be transactionally coherent.
33. Active attempts must not depend on mutable Quiz Service content or availability.

## Results/review

34. Wave 3 score visibility requires successful submission and completed grading, and is restricted to the authenticated Attempt owner; Teachers may view results for owned Publications.
35. Policy design must avoid contradictory boolean combinations.
36. Hidden answer keys must not leak through learner APIs/realtime payloads.
37. Wave 3 uses equal question weights: exact single-choice correctness, exact multiple-choice set matching without partial credit, equal per-statement true/false credit, and exact decimal numeric equality without tolerance. No negative marking. Snapshot-pinned grading policy version 1 is mandatory. See `assessment-core-policy.md` for accepted rules and remaining numeric-input/score-representation contract gates.

Wave 3 Publications follow `DRAFT → OPEN → CLOSED`; opening atomically finalizes
the self-contained snapshot. Opened references cannot change, no return to draft
or reopening is allowed in MVP, and closure blocks new Attempts. Correct answers
and explanations require closure and an eligible submitted owner. These rules
are enforced by Assessment backend, including omission of hidden data before
review is allowed.

## Proctoring

38. Proctoring is allowed only for one-class, authenticated, bounded-time assessments.
39. Proctoring starts with attempt start.
40. Proctoring ends with submit or server deadline.
41. Standard browser monitoring may record visibility/focus/fullscreen/camera/network/proctor events.
42. Standard browser code must not claim it can inspect unrelated external tab URLs/apps.
43. Proctoring AI flags suspicious events for human review.
44. AI flags do not automatically fail a learner or declare cheating as fact.
45. Proctoring evidence has short-lived retention; exact default is TBD.
46. Full video recording is deferred from MVP.

## Community

47. Teachers may publish contribution posts referencing eligible public quiz content.
48. Posts support reactions and comments.
49. Ratings attach conceptually to the public quiz rather than an individual post.
50. Eligible public quizzes may be copyable/forkable.
51. Forks create a new owned quiz and preserve source provenance.
52. Copy/fork respects source owner permission.

## Architecture and security

53. Quizopia uses a true monorepo; backend services remain independently buildable/deployable Maven projects.
54. Services do not access each other's databases.
55. Each service owns a separate PostgreSQL database and credential even when sharing a physical server/cluster.
56. Browser business HTTP traffic goes through Spring Cloud Gateway.
57. Gateway and each protected service validate Quizopia user JWTs.
58. Identity Service issues Quizopia tokens through Spring Authorization Server.
59. User access JWTs use RS256 and are short-lived; frontend stores them in memory.
60. Refresh tokens are opaque HttpOnly credentials with server-side hashing, rotation, family lineage, and reuse detection.
61. Account disable/revocation must support near-immediate Redis-backed rejection rather than relying only on JWT expiry.
62. Synchronous service-to-service calls use direct internal REST authenticated by OAuth2 Client Credentials service JWTs.
63. Asynchronous integration events use RabbitMQ.
64. Critical integration events use transactional outbox/equivalent durable publication.
65. Integration consumers must be idempotent.
66. Redis is not authoritative storage for durable business data.
67. Binary content uses an S3-compatible abstraction; local object storage is MinIO.
68. AI vector retrieval initially uses PostgreSQL + pgvector.
69. REST handles authoritative business mutations.
70. WebSocket handles application realtime signals/UI acceleration.
71. WebRTC/LiveKit handles realtime media.
72. WebRTC DataChannels must not be the authoritative assessment answer-persistence path.
73. Services publish OpenAPI contracts; frontend contracts/clients should be generated/derived where practical.
74. Flyway owns database schema changes; Hibernate validates schema.
75. Production secrets must never be committed to Git.

## Quality/operations

76. GitHub Actions is the CI baseline.
77. Pull requests use path-aware/targeted checks; `develop` and `main` run comprehensive validation.
78. Backend uses Spotless and architecture tests such as ArchUnit.
79. Frontend uses ESLint, Prettier, and TypeScript strict mode.
80. Correctness-sensitive integration tests use real dependencies through Testcontainers where appropriate.
81. Playwright is the E2E baseline.
82. Backend services expose health/metrics through Actuator/Micrometer.
83. Distributed tracing uses OpenTelemetry/Micrometer tracing across HTTP and RabbitMQ where supported.
84. Production logs are structured and must not include credentials/tokens/OTP/media payloads/unnecessary PII.
