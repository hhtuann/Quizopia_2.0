# Quiz Service

Independent Spring Boot 4.1.1 / Java 21 project for the Quizopia 2.0 platform scaffold.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

The project contains the stable Quiz identity, mutable QuizDraft foundation, and the minimal teacher-owned create/read/update draft HTTP API under `/api/quizzes`. It intentionally has no list, folder, publication/version, parser, or event API yet.

Default local port: 8082. See `.env.example` and the root development documentation for configuration.
