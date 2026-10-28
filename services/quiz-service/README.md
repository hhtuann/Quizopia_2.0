# Quiz Service

Independent Spring Boot 4.1.1 / Java 21 project for the Quizopia 2.0 platform scaffold.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

The project contains the stable Quiz identity, mutable QuizDraft foundation, the accepted Quiz Markdown parser/validator domain core, immutable schema-versioned QuizVersion JSONB snapshots, and the teacher-owned authoring HTTP API under `/api/quizzes`. Publishing the current draft is available at `POST /api/quizzes/{quizId}/versions`; unchanged drafts reuse the latest published version. An owning teacher can page immutable snapshot metadata through `GET /api/quizzes/{quizId}/versions` and read one exact historical snapshot through `GET /api/quizzes/{quizId}/versions/{versionNumber}`. Standalone validation, folder APIs, Assessment publication, and QuizVersion integration events are intentionally outside this service slice.

## Quiz deletion foundation (FB-015 / ADR-018, guarded)

Behind `quizopia.deletion.enabled` (env `QUIZ_DELETION_ENABLED`, default `false`):

- `POST /api/quizzes/{quizId}/deletion-requests` (owner TEACHER, required `Idempotency-Key` header of 16-128 canonical `[A-Za-z0-9_-]` characters, body `{"confirmTitle": "<exact current draft title>"}`) atomically fences the quiz, persists a durable `quiz_deletion_operations` row at `QUIZ_FENCED` with a frozen version inventory, and writes a transactional outbox row (`quiz.deletion.requested.v1`). Same key + same title replays the same operation idempotently; same key + different title, or a different key against an already fenced quiz, returns `409 DELETION_CONFLICT`; a wrong/missing title returns `400 TITLE_CONFIRMATION_REQUIRED`.
- `GET /api/quizzes/{quizId}/deletion-requests/{operationId}` returns the owner-only status (`404 QUIZ_DELETION_NOT_FOUND` for anyone else). Responses carry no PII and `Cache-Control: no-store`.
- While `quizzes.deletion_fenced_at` is set, draft updates and publishing return `409 QUIZ_DELETION_PENDING`, and the internal source-version handoff endpoint used by Assessment for NEW publications returns `404 SOURCE_NOT_ACCESSIBLE` (existing publications keep using Assessment's own stored delivery snapshot). Owner reads (library, draft, versions) keep working and expose an additive nullable `deletionStatus`.
- A scheduled outbox dispatcher (only while the flag is on) drains due outbox rows with bounded exponential retries (attempts < 10). Deletion states advance forward-only through the `QuizDeletionState` main order; there is intentionally no purge, restore, or quiz-content mutation in this foundation slice, and the quiz row is kept as an anti-resurrection tombstone.

With the flag off, the deletion routes are absent and answer with the standard quiz 404 envelope so the route is not disclosed. Contract: `contracts/quiz-deletion.openapi.yaml`.

Default local port: 8082. See `.env.example` and the root development documentation for configuration.
