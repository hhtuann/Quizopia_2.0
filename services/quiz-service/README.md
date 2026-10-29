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

## FB-015 PR C corrections: saga purge + ledger scrub (Leader review)

While `quizopia.deletion.enabled` is on, `DeletionSagaService` drives each
operation through dependency fencing, revocation acknowledgment, dependent
purge and the irreversible quiz-owned purge:

- **Dependent purge (H3/H5).** The saga fetches the Assessment-owned
  dependents inventory (`GET /internal/security/quiz-revocations/{quizId}/dependents`,
  dedicated `quiz.deletion.purge` scope) BEFORE purging Assessment, and passes
  the ids to the Proctoring purge so legacy `quiz_id NULL` sessions resolve
  without any cross-service database join. Bounded purge rounds answering
  `IN_PROGRESS` park the operation in `PURGING` (`purge-in-progress`) and retry;
  `RECONCILIATION_BLOCKED` from Proctoring surfaces `WAITING_DEPENDENCY` with
  reason `legacy_dependents_unresolved` - never `PRIMARY_PURGED` while a known
  dependent may remain.
  Both owners' status must first expose acknowledged fences and an explicit
  `retentionHoldActive: false`; absent classification fails closed. Proctoring
  must finish and attest purge before any Assessment purge call. Later retries
  skip an authoritatively purged Proctoring owner, preserving its original
  inventory even after Assessment has partially deleted its live mapping.
  Empty, duplicate, or wrong-operation/quiz batch receipts cannot authorize
  progression. `PENDING_PROVIDER` and `INVENTORY_MISMATCH` remain pending with
  distinct public reason codes.
- **Irreversible boundary scrub (H4).** `PRIMARY_PURGED` advances in one
  transaction that also hard-deletes quiz versions/drafts/quiz rows AND scrubs
  the ledger: `confirmed_title` becomes NULL and `version_inventory` is reduced
  to the bare count `{"versionCount":n}` (V6). Ids, owner reference (needed for
  authorization), statuses, timestamps, reason codes and step receipts remain as
  the minimal anti-resurrection tombstone; raw-storage tests prove the title
  string is absent from the quiz database afterwards. Post-scrub replays of the
  original request return the durable operation state instead of `409`.
  V7 retains a SHA-256 canonical-request digest so changed-title replays still
  conflict after scrub. Already-scrubbed pre-V7 receipts cannot reconstruct a
  digest and reject replay; owner status discovery remains available.
- `GET /api/quizzes/{quizId}/deletion-requests/latest` discovers the operation
  using ledger ownership, including after the Quiz has gone. It requires the
  same current Teacher/LD-05 admission as other deletion routes.
- Historical null/empty draft titles accept explicit `confirmTitle: ""`.
  Frontend integration must use the underlying title (null maps to empty),
  rather than the localized untitled display label, and require an explicit
  confirmation interaction even when the confirmation text is empty.
- `ALL_COPIES_ERASED` remains deliberately unreachable: no backup or
  media-provider erasure evidence exists yet, so `PRIMARY_PURGED` is the honest
  terminal state and the public status carries the `COPY_EXPIRY_PENDING` note.


Default local port: 8082. See `.env.example` and the root development documentation for configuration.
