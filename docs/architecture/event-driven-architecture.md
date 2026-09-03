# Event-Driven Architecture

Status: **Accepted integration baseline v0.2**

## Broker

RabbitMQ is the accepted initial integration-event broker.

Kafka is not baseline infrastructure. Re-evaluate only if measured stream throughput/retention/analytics needs justify it.

## When to use events

Use an asynchronous event when:

- a business fact has already occurred;
- the producer does not need an immediate consumer result;
- eventual consistency is acceptable.

Use direct internal REST when the caller needs an immediate response.

## Transactional outbox

Critical integration events MUST NOT use an unrecoverable pattern like:

```text
commit database
-> best-effort RabbitMQ publish
-> event may disappear forever
```

Required conceptual flow:

```text
BEGIN
  write domain data
  insert outbox event
COMMIT

outbox publisher
-> RabbitMQ
-> mark/record publication progress safely
```

Each service owns its own outbox data.

Exact polling/CDC/outbox library implementation is a scaffold/implementation detail as long as the durability invariant holds.

## Consumers

Consumers must be idempotent.

Use stable `eventId` and/or an inbox/processed-event strategy for side effects where duplicate processing would be unsafe.

RabbitMQ redelivery/duplicates must be expected.

Use dead-letter/retry strategy for non-transient failures.

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
- grading/result integration event — **final authoritative event name/schema TBD**

Proctoring:

- `PROCTORING_SESSION_STARTED`
- `PROCTORING_SESSION_ENDED`
- `PROCTORING_FLAG_CREATED`

## Event contracts

- version schemas;
- stable event IDs;
- business-oriented past-tense facts;
- avoid secrets/unnecessary PII;
- propagate trace context where supported;
- consumers tolerate duplicate delivery;
- handle ordering explicitly when it matters.

## Realtime is separate

RabbitMQ integration events and browser WebSocket events are related but not identical responsibilities.

Do not assume every integration event must be exposed verbatim to the browser.

Application realtime payloads should be intentionally designed and remain reconcilable with authoritative REST/read state.
