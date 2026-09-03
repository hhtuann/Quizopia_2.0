# Service Boundaries

Status: **Baseline v0.1**

## General rule

Every service owns its data.

No service may query another service's database directly.

Cross-service needs use:

- documented synchronous APIs; or
- documented asynchronous events/read models.

## Identity Service

Owns:

- users;
- local credentials;
- Google identities;
- Gmail verification state;
- OTP lifecycle;
- roles (`STUDENT`, `TEACHER`, `ADMIN`);
- teacher role enablement;
- refresh/session security;
- account status;
- admin user management.

Does not own:

- classroom membership;
- quizzes;
- attempts;
- community posts.

## Quiz Service

Owns:

- teacher quiz library/folders;
- quiz drafts;
- Quiz Markdown parsing/validation;
- question content;
- immutable quiz versions;
- public/unlisted/private quiz metadata;
- import from approved Excel/DOCX templates;
- DOCX offline rendering;
- copy/fork source provenance.

Does not own:

- assessment attempts;
- classroom membership;
- community comments/reactions;
- proctor evidence.

## Classroom Service

Owns:

- classrooms;
- classroom metadata;
- join code/invitation links;
- manual email invitations;
- classroom memberships;
- announcements;
- assignments;
- classroom-facing gradebook/read-model composition.

Assessment remains authoritative for grades.

## Assessment Service

Owns:

- publications;
- delivery configuration;
- immutable assessment/practice delivery snapshot as required;
- guest participation identity/session metadata;
- attempts;
- attempt question/option order;
- answers/autosave sequence;
- submission/idempotency;
- automatic grading;
- results;
- assessment reports/statistics.

It must not call Quiz Service during an active attempt to obtain authoritative question content.

## Community Service

Owns:

- contribution posts;
- comments;
- reactions;
- ratings for public quizzes;
- content report/moderation records as applicable;
- community feed/read models.

Quiz Service remains authoritative for quiz ownership/version/copy policy.

## Proctoring Service

Owns:

- proctoring eligibility/session metadata;
- append-only proctor events;
- suspicious flags/risk signals;
- LiveKit room/token integration;
- evidence metadata;
- evidence retention lifecycle;
- teacher monitoring APIs/read models.

Assessment Service remains authoritative for attempt start/deadline/submit.

## AI Service

Owns:

- AI document ingestion metadata;
- source-document processing;
- retrieval/RAG indexes where applicable;
- AI quiz generation workflow;
- AI tutor orchestration;
- provider abstraction.

AI Service does not directly publish quizzes or mutate attempt grading.

## Cross-service examples

### Verified user claims pending classroom invitation

1. Identity verifies Gmail.
2. Identity emits a verified-identity event.
3. Classroom consumes event.
4. Classroom finds matching pending invitations.
5. Classroom creates/activates membership.

### Quiz version becomes assessment publication

1. Teacher chooses an immutable QuizVersion.
2. Assessment receives a validated publication snapshot/contract.
3. Assessment stores everything needed for delivery.
4. Active attempts no longer depend on Quiz Service.

### Assessment result updates classroom gradebook

1. Assessment commits result.
2. Assessment emits result event after commit.
3. Classroom consumes event.
4. Classroom updates its gradebook read model.
