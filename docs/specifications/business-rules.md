# Business Rules

Status: **Baseline v0.2 with accepted Wave 3 Assessment Core policy**

For Wave 3, [Assessment Core policy](assessment-core-policy.md) governs the MVP
grading/visibility rules and the subsequent
[W3-A Leader decision](w3-a-publication-scheduling-monitoring-policy.md) governs
audience, scheduling and monitoring scope. CLASS/PUBLIC, bounded start windows,
positive Attempt duration and optional eligible CLASS Activity/LiveKit monitoring
are accepted. The LD-01..05 follow-up accepts timing boundaries/deadline/duration,
explicit CLASS monitoring choice, authenticated link-only PUBLIC participation,
reject-CLOSE while active and Wave 3 revocation outage/recovery policy. Remaining
engineering/W3-B/C/M1/M2 gates stay open; broader guest/configurable visibility
features are not implicitly approved.

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

Wave 3 CLASS targets exactly one Classroom; Teacher assignment authority and
Student membership are checked authoritatively through Classroom Service. Shared
URLs cannot bypass membership. PUBLIC has no membership requirement and cannot
enable Classroom Proctoring. LD-03 requires authenticated USER with authoritative
STUDENT and Identity account/token-validity/revocation rules; TEACHER + STUDENT
may participate through STUDENT. Wave 3 uses a shared direct link with a suitably
non-enumerable locator; no directory, guests, passwords or anonymous-session
identity is required. Teacher management remains owner-authorized and Student
APIs expose safe projections. Link possession bypasses no eligibility rule;
exact locator/route/error contracts remain engineering work. No new owner
self-participation permission or prohibition is introduced.

## Assessment time mutation

24. Before attempts begin, editable timing follows normal publication rules.
25. After the first attempt starts, availability end must not move earlier.
26. After the first attempt starts, attempt duration must not be reduced.
27. Time may be extended according to authorization/business rules.

Wave 3 requires a bounded participation window for starting Attempts and a
positive bounded per-Attempt duration. Opening is distinct from scheduling;
window end is not durable closure, automatic Attempt finalization or answer-review
permission. Server time controls starts/deadlines without relying solely on jobs.
LD-01 accepts OPEN + eligible Student + `startAt <= serverNow < endAt`, integer
5–240-minute duration with one-minute granularity, and authoritative start plus
duration deadlines. Timezone-safe authoritative instants and unambiguous local
conversion are required. Window end does not shorten an already-started Attempt;
configuration must never retroactively shorten existing authoritative deadlines.
Rules 24–27 remain applicable; no blanket post-OPEN timing ban is introduced.
Wire/validation details and W3-B expiration, cutoff, timeout finalization,
resumption and response ordering remain gates. Future unbounded/practice timing is
not decided by this limited scope.

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

LD-04 rejects OPEN → CLOSED while any Attempt is authoritatively active, with a
documented conflict response. Teacher may close once all Attempts are terminal.
CLOSE must handle overdue Attempts and start/finalization races transactionally;
concurrent start/CLOSE cannot leave an unauthorized active Attempt in CLOSED.
W3-B must ensure expired Attempts eventually become terminal independently of
timely background processing. No automatic closure or force-close is introduced.
Saved/unsaved answer treatment, deadline finalization and submit semantics remain
W3-B contracts; only durable CLOSED enables eligible submitted-owner review.

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

Under W3-A, Activity Evidence and LiveKit camera monitoring are required Wave 3
outcomes, enabled together optionally for eligible CLASS Publications after clear
Student disclosure. LD-02 requires explicit CLASS Teacher choice before OPEN,
with no implicit ON/OFF default for unconfigured CLASS DRAFT; the choice is
immutable upon OPEN. PUBLIC monitoring must be disabled. No additional
Activity-only, AI-enabled or A/B modes are approved. Persisted representation/UI
terminology and W3-M1/M2 runtime authorization/privacy/failure contracts stay open. Browser evidence must not collect unrelated app activity, other-tab
URLs, browsing history, keystrokes or clipboard contents; answer-change metadata
must not leak answer content. Evidence never automatically disqualifies or zeros
a score. Teachers view only authorized active participants; Students cannot view
peer streams; capture/permission/failure status must be truthful. Required
privileged checks fail closed when unverifiable. Monitoring failures cannot
corrupt answers/results, change grading or silently extend deadlines.

AI behavior analysis, recording, mandatory/strict screen sharing, remote device
control and automatic facial identification are outside Wave 3. Evidence retention,
camera refusal/loss/accommodations, scoped access/revocation and the W3-A
privacy/security release gates must be finalized before dependent release.

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

LD-05 requires trustworthy revocation/account authority for affected protected
Assessment/Classroom/Proctoring operations. Valid unexpired JWTs cannot bypass
required near-immediate revocation checks; fail closed during required authority
unavailability/untrustworthiness. Prefer sanitized service-unavailable responses
where appropriate, subject to API review. Outage alone must not invalidate
refresh credentials, silently log out or cause refresh loops. No automatic
Identity fallback or degraded authenticated lower-risk reads are approved for
initial Wave 3. Recovery requires trustworthy coverage or durable reconstruction,
not merely Redis health/connectivity. Independent truly public nonprivileged
infrastructure may remain available. ID-05 implementation/recovery/verification
remain open; no auth subsystem rewrite is authorized.

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
