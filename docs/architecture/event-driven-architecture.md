# Event-Driven Architecture

Status: **Baseline v0.1**

## Purpose

Microservices need asynchronous integration without cross-database access.

Use domain/integration events where eventual consistency is acceptable and where synchronous coupling would make the system fragile.

## Event broker

Broker technology is **TBD**.

Current leading candidate: RabbitMQ for the initial system.

Kafka is not required unless measured stream/retention/throughput needs justify it.

Do not treat the candidate as an accepted implementation until the team records the final decision.

## Example integration events

Identity:

- `USER_REGISTERED`
- `USER_EMAIL_VERIFIED`
- `USER_DISABLED`
- `TEACHER_ROLE_GRANTED`

Quiz:

- `QUIZ_VERSION_PUBLISHED`
- `QUIZ_VISIBILITY_CHANGED`

Classroom:

- `CLASSROOM_MEMBER_JOINED`
- `CLASSROOM_ASSIGNMENT_CREATED`

Assessment:

- `ATTEMPT_STARTED`
- `ATTEMPT_SUBMITTED`
- `GRADE_CREATED`
- `ASSESSMENT_RESULT_UPDATED`

Proctoring:

- `PROCTORING_SESSION_STARTED`
- `PROCTORING_SESSION_ENDED`
- `PROCTORING_FLAG_CREATED`

Community:

- community events as needed for notifications/search/read models.

## Reliability rules

- Publish business events only after the owning transaction commits.
- Consumers must be idempotent.
- Events need stable event IDs.
- Cross-service consumers must tolerate duplicate delivery.
- Do not encode secrets or unnecessary PII in events.
- Event schemas must be versioned/evolvable.

## Outbox

For business events whose loss would break cross-service consistency, use a transactional outbox or equivalent durable publication mechanism.

Exact outbox/broker implementation is **TBD**, but "save DB then best-effort publish without recovery" must not be used for critical integration events.
