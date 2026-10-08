# Quiz Service

Independent Spring Boot 4.1.1 / Java 21 project for the Quizopia 2.0 platform scaffold.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

The project contains the stable Quiz identity, mutable QuizDraft foundation, the accepted Quiz Markdown parser/validator domain core, immutable schema-versioned QuizVersion JSONB snapshots, and the teacher-owned authoring HTTP API under `/api/quizzes`. Publishing the current draft is available at `POST /api/quizzes/{quizId}/versions`; unchanged drafts reuse the latest published version. An owning teacher can page immutable snapshot metadata through `GET /api/quizzes/{quizId}/versions` and read one exact historical snapshot through `GET /api/quizzes/{quizId}/versions/{versionNumber}`. Standalone validation, folder APIs, Assessment publication, and QuizVersion integration events are intentionally outside this service slice.

Default local port: 8082. See `.env.example` and the root development documentation for configuration.
