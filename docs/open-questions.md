# Open Questions

Status: **Active after pre-scaffold decisions — v0.2**

The infrastructure/architecture questions required to scaffold are now largely accepted. The remaining items are feature-level, policy-level, or deployment-vendor choices unless explicitly marked otherwise.

Agents MUST NOT silently resolve these items.

## Identity

**ID-01.** Does the local-registration Gmail rule mean strictly `@gmail.com`, or also Google Workspace/custom-domain Google accounts?

**ID-02.** OTP expiry, maximum attempts, resend cooldown, and throttling values?

**ID-03.** Exact account-link conflict/recovery UX?

**ID-04.** Exact user access-token TTL and refresh-family lifetime/configuration?

**ID-05.** If Redis revocation lookup is unavailable, what is the required fail-open/fail-closed/degraded behavior for Gateway/services?

**ID-06.** Teacher self-enablement anti-abuse/audit/rate-limit policy?

## Classroom

**CLASS-01.** Join-by-code/invite-link becomes active immediately or requires teacher approval?

**CLASS-02.** Can a student leave a class without teacher action?

**CLASS-03.** Final subject/grade taxonomy/catalog model?

**CLASS-04.** Late assignment/submission policy?

## Quiz Markdown

**QM-01.** Final explicit question-type syntax?

**QM-02.** Final `NUMERIC_FILL` Markdown answer syntax?

**QM-03.** Final allowed four-character `NUMERIC_FILL` character set and normalization rules?

**QM-04.** Markdown support for LaTeX, code blocks, images, explanations, other rich content?

**QM-05.** Does canonical Markdown preserve teacher formatting exactly or normalize on save?

**QM-06.** Accept/reject the proposed "structured question representation -> canonical Markdown renderer" AI/import strategy?

## Import/export

**IMP-01.** Exact Excel import template?

**IMP-02.** Exact DOCX import template?

**IMP-03.** Exact offline DOCX exam format?

**IMP-04.** Exact shuffled paper variants/answer-key requirements?

## Publication / Assessment

**ASSESS-01.** Exact persisted Publication status/state model?

**ASSESS-02.** At which publication transition is the self-contained Assessment delivery snapshot finalized?

**ASSESS-03.** Exact score-visibility policy enum/behavior?

**ASSESS-04.** Exact answer-review policy enum/behavior?

**ASSESS-05.** Publication password hashing/rate-limit/access policy?

**ASSESS-06.** Guest nickname required or optional?

**ASSESS-07.** Anonymous guest session/cookie identity design?

**ASSESS-08.** Exact public slug/opaque identifier format?

**ASSESS-09.** Policy for changing availability start after attempts have begun?

## Grading

This group was added after independent Codex/Claude review identified that v0.1 did not define per-type scoring.

**GRADE-01.** `SINGLE_CHOICE` scoring policy?

**GRADE-02.** `MULTIPLE_CHOICE`: all-or-nothing or partial credit?

**GRADE-03.** If multiple-choice partial credit exists, are incorrect selections penalized/capped?

**GRADE-04.** `TRUE_FALSE_MATRIX` scoring policy (proportional, ladder, all-or-nothing, other)?

**GRADE-05.** `NUMERIC_FILL` exact comparison/normalization semantics consistent with the fixed four-character rule?

**GRADE-06.** Are grading/scoring policies versioned/pinned with the immutable delivery snapshot?

## Practice

**PRACTICE-01.** Does practice use Assessment-owned practice sessions, a separate learning-progress model, or another persistence model?

**PRACTICE-02.** Is spaced repetition in scope?

**PRACTICE-03.** Exact AI tutor policy/modes?

**PRACTICE-04.** How do classroom due dates/assignments interact with practice activities if class-distributed practice is supported?

## Community

**COMM-01.** Exact visibility names (`PRIVATE`/`UNLISTED`/`PUBLIC` or alternatives)?

**COMM-02.** Can students author community posts, or teachers only initially?

**COMM-03.** Rating scale?

**COMM-04.** Comment editing/deletion policy?

**COMM-05.** Moderation/report workflow?

**COMM-06.** Does a contribution post reference Quiz, QuizVersion, Publication, or a dedicated Community read-model ID?

**COMM-07.** How are visibility changes/deletions propagated to Community posts/ratings/read models?

## Proctoring

**PROCTOR-01.** Evidence retention default: 7 days, 30 days, or another platform value?

**PROCTOR-02.** Suspicious snapshot trigger/frequency policy?

**PROCTOR-03.** Which AI detectors are MVP vs later?

**PROCTOR-04.** Is microphone ever required?

**PROCTOR-05.** Is strict consent-based screen-sharing mode in roadmap?

**PROCTOR-06.** Exact teacher violation-review workflow?

**PROCTOR-07.** Production LiveKit deployment: self-hosted or managed?

**PROCTOR-08.** Which service is authoritative for answer-change/question-navigation evidence in the proctor timeline, and what sanitized event is copied from Assessment to Proctoring?

**PROCTOR-09.** Exact modeling of the "exactly one classroom" constraint between Publication/Assignment/Proctoring?

## AI

**AI-01.** Model/provider(s)?

**AI-02.** Supported source-document types, file size/page/token limits?

**AI-03.** Source citation/provenance UX?

**AI-04.** Usage quotas/cost controls?

**AI-05.** Long-term vector-store change criteria beyond the accepted initial pgvector baseline?

**AI-06.** Exact AI -> Quiz authoring mediation contract (frontend-mediated structured output vs service-to-service draft integration)?

## Messaging/contracts

**EVT-01.** Final authoritative Assessment result event used to update Classroom gradebook, including event name/schema?

**EVT-02.** Exact outbox implementation strategy/library/polling/CDC mechanism? The durability requirement is already accepted.

**EVT-03.** Exact RabbitMQ exchange/queue/routing/retry/DLQ conventions?

## Realtime

**RT-01.** Exact horizontally scalable WebSocket/STOMP broker/relay topology?

**RT-02.** WebSocket event sequencing/gap-detection contract?

## API

**API-01.** Pagination convention: cursor vs page-number/size?

**API-02.** Standardize whether `422` is used or validation remains primarily `400`?

**API-03.** Exact API-versioning convention/path/header strategy?

## Development / Deployment (non-blocking for scaffold)

**DEV-01.** Dependabot vs Renovate for centralized dependency monitoring/alignment?

**DEV-02.** Exact local port map and helper script names?

**DEV-03.** Exact deployment target?

**DEV-04.** Production observability/log backend/vendor?

**DEV-05.** Production secret-manager vendor/platform?

**DEV-06.** Exact production Flyway deployment-job strategy?

## Explicitly resolved since v0.1

The following are no longer open:

- true monorepo;
- independent Maven project per service;
- one frontend;
- `com.quizopia` package root;
- limited shared contracts/test support;
- Spring Cloud Gateway;
- browser business API only through Gateway;
- Spring Authorization Server in Identity;
- RS256 short-lived access JWT + rotating opaque refresh;
- Gateway and every service validate JWT;
- Redis-backed near-immediate revocation;
- OAuth2 Client Credentials for synchronous service authentication;
- internal REST + events split;
- OpenAPI-derived frontend contracts;
- RabbitMQ;
- transactional outbox durability requirement;
- separate DB + credential per service;
- Redis baseline;
- MinIO local + S3 abstraction;
- PostgreSQL + pgvector initial vector baseline;
- Mailpit local/test;
- hybrid local development;
- no Eureka/Consul baseline;
- local/dev Flyway-on-startup + Hibernate validate;
- GitHub Actions;
- targeted PR / full develop-main CI;
- Spotless/ArchUnit/ESLint/Prettier/TS strict;
- structured logging;
- OpenTelemetry;
- Actuator/Micrometer;
- optional local observability profile;
- environment/config/secrets convention;
- Testcontainers;
- Playwright;
- centrally monitored/aligned dependencies.
