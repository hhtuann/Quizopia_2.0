# Quizopia 2.0

> Project Context Pack v0.1

Quizopia 2.0 is a public web platform for creating, publishing, sharing, practicing, and taking quizzes. It also supports classroom workflows, public community content, AI-assisted authoring/tutoring, and optional proctoring for time-bounded classroom assessments.

Quizopia 2.0 is a **new system** inspired by the lessons and strongest engineering invariants of Quizopia 1.x. It is **not** an incremental refactor of the legacy codebase.

## Current baseline

- Architecture: coarse-grained microservices
- Frontend: Next.js 16, React 19, TypeScript, Tailwind CSS 4
- Business services: Java 21 + Spring Boot 4.x
- Primary transactional database: PostgreSQL 17
- Schema migrations: Flyway
- Realtime application events: WebSocket/STOMP
- Realtime media: WebRTC via LiveKit
- Object storage: S3-compatible storage such as MinIO
- Cache / temporary distributed state: Redis where justified
- AI stack/provider details: to be finalized

## Core product areas

- Identity and authentication
- Quiz authoring and immutable quiz versions
- Assessment and practice publications
- Public guest quiz taking
- Classroom, assignments, announcements, gradebook
- Community/blog, comments, reactions, ratings, quiz copy/fork
- Proctoring for eligible classroom assessments
- AI authoring and AI tutor

## Documentation

- Product requirements: [`docs/product/`](docs/product/)
- Architecture: [`docs/architecture/`](docs/architecture/)
- Specifications: [`docs/specifications/`](docs/specifications/)
- Architecture Decision Records: [`docs/decisions/`](docs/decisions/)
- Development workflow: [`docs/development/`](docs/development/)
- Open questions: [`docs/open-questions.md`](docs/open-questions.md)
- Legacy reference: [`docs/reference/legacy-analysis.md`](docs/reference/legacy-analysis.md)

## Source-of-truth rule

Chat conversations are not the project source of truth.

Accepted decisions and product behavior must be written into this repository. If code and documentation disagree, agents and developers must report the conflict instead of silently guessing.

## Legacy project

The original Quizopia system is reference material only. The legacy analysis records valuable behavior such as:

- immutable published exam snapshots;
- attempt question ordering snapshots;
- sequence-aware autosave;
- idempotent submit;
- transactional grading;
- server-authoritative deadlines;
- after-commit realtime events;
- PostgreSQL constraints for business invariants;
- refresh-token rotation/reuse detection.

Quizopia 2.0 may reuse these ideas and selected implementation patterns, but the Quizopia 2.0 specifications take precedence.
