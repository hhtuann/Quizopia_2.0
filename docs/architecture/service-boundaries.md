# Service Boundaries

Status: **Accepted baseline v0.2**

## Global rules

- Every service owns its authoritative data.
- A service MUST NOT query/write another service's database.
- Cross-service interactions use documented REST APIs or integration events.
- Internal REST is authenticated with OAuth2 Client Credentials service JWTs.
- Critical asynchronous integration uses RabbitMQ + transactional outbox + idempotent consumers.

## Gateway

Spring Cloud Gateway is infrastructure/edge, not a business-domain service.

Owns/handles edge concerns such as:

- public routing;
- edge authentication validation;
- edge rate/security policies where appropriate;
- WebSocket route exposure;
- trace/correlation propagation.

It must not become the location for domain business workflows.

## Identity Service

Owns:

- users;
- local credentials;
- Google identities/account linking;
- Gmail verification/OTP;
- roles (`STUDENT`, `TEACHER`, `ADMIN`);
- teacher-role enablement;
- account status;
- Quizopia authorization-server behavior;
- RS256 signing/JWKS;
- user access-token issuance;
- rotating opaque refresh sessions/families;
- service OAuth clients / Client Credentials;
- security session/revocation source state;
- admin user management.

Redis may distribute short-lived revocation/security state, but Identity remains authoritative.

Does not own classroom memberships, quizzes, attempts, or community posts.

## Quiz Service

Owns:

- teacher quiz library/folders;
- quiz drafts;
- Quiz Markdown parsing/validation;
- question content;
- immutable quiz versions;
- quiz visibility metadata;
- Excel/DOCX import for approved templates;
- offline DOCX rendering;
- copy/fork provenance and copy permission.

Binary files use the object-storage abstraction; Quiz owns their business metadata.

Does not own assessment attempts, classroom membership, community interactions, or proctor evidence.

## Classroom Service

Owns:

- classrooms;
- join codes/invitation links;
- manual Gmail invitations;
- classroom memberships;
- announcements;
- assignments;
- classroom gradebook/read-model composition.

Assessment remains authoritative for attempt/grade/result facts.

## Assessment Service

Owns:

- publications;
- delivery configuration;
- self-contained immutable delivery snapshots;
- guest participation/session metadata;
- assessment attempts;
- stable question/option order;
- answer/autosave sequence state;
- submission/idempotency;
- automatic grading;
- results;
- reports/statistics.

Assessment must not call Quiz Service for authoritative question content during an active attempt.

Practice persistence is not finalized yet; do not invent it.

## Community Service

Owns:

- contribution posts;
- comments;
- reactions;
- public-quiz ratings;
- community feed/read models;
- applicable content-report/moderation records.

Quiz Service remains authoritative for quiz ownership/version/copy permission.

The exact Community reference/read-model contract for QuizVersion vs Publication is a feature-level open question.

## Proctoring Service

Owns:

- proctoring session/evidence metadata;
- browser-proctoring signals that are proctor-domain facts;
- suspicious flags/risk signals;
- LiveKit room/token orchestration;
- evidence retention lifecycle;
- teacher monitoring read models/APIs.

Assessment remains authoritative for attempt start/deadline/submit and answer persistence.

Ownership/duplication of answer-change/question-navigation evidence into the proctor timeline must be finalized before Proctoring implementation.

## AI Service

Owns:

- AI document-ingestion metadata;
- source processing;
- pgvector/RAG indexes in `ai_db`;
- quiz-generation workflow;
- AI tutor orchestration;
- model/provider abstraction.

AI does not directly publish quiz versions and does not mutate assessment grading.

## Example cross-service flow: pending classroom invitation

1. Identity verifies a Gmail address.
2. Identity commits the change and records an outbox event.
3. Outbox publisher sends `USER_EMAIL_VERIFIED` through RabbitMQ.
4. Classroom consumes idempotently.
5. Classroom claims matching pending invitations and creates/activates membership.

## Example cross-service flow: gradebook

1. Assessment commits a result change.
2. Assessment records a durable integration event in the same transaction.
3. Classroom consumes the event and updates a gradebook read model.

The final authoritative gradebook event name/schema remains to be finalized.
