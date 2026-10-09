# Event Conventions

Status: **Accepted integration-event conventions v0.2**

## Transport

RabbitMQ is the integration-event broker baseline.

Critical events are persisted through transactional outbox (or an equivalent recoverable mechanism) before broker publication.

Consumers are idempotent.

## Envelope

Conceptual event envelope:

```json
{
  "eventId": "...",
  "eventType": "ATTEMPT_SUBMITTED",
  "eventVersion": 1,
  "occurredAt": "...",
  "aggregateId": "...",
  "traceId": "...",
  "payload": {}
}
```

Exact serialization/field list may be finalized during scaffold, but stable event identity/versioning is required.

## Rules

- unique `eventId`;
- business-oriented past-tense event names;
- versioned schemas;
- avoid secrets/unnecessary PII;
- never emit credentials/tokens;
- publish critical facts only after/with durable commit semantics;
- consumers tolerate duplicate delivery;
- use sequence/revision when ordering matters;
- trace context should propagate across RabbitMQ where practical.

## Canonical example names

```text
USER_EMAIL_VERIFIED
USER_DISABLED
QUIZ_VERSION_PUBLISHED
QUIZ_VISIBILITY_CHANGED
CLASSROOM_MEMBER_JOINED
CLASSROOM_ASSIGNMENT_CREATED
ATTEMPT_STARTED
ATTEMPT_SUBMITTED
PROCTORING_SESSION_STARTED
PROCTORING_FLAG_CREATED
```

The final assessment result/gradebook integration event name and schema remain **TBD**; do not create competing `GRADE_CREATED` vs `ASSESSMENT_RESULT_UPDATED` contracts without resolving it.

These names are examples, not frozen Proctoring schemas. AI suspicious-flag
processing is future-facing and is not a Wave 3 delivery requirement.

## Realtime events

Browser WebSocket events are UI acceleration, not authoritative integration storage.

They may be derived from committed domain/integration facts but do not need to mirror every RabbitMQ event one-to-one.

After reconnect, clients reconcile through REST/read APIs.

## Sensitive assessment/proctoring data

Do not leak correct-answer data before review policy allows it.

Answer-change evidence is sensitive and must have explicit ownership/access rules before implementation.

The [W3-A Leader decision](w3-a-publication-scheduling-monitoring-policy.md)
requires durable Activity Evidence for monitored eligible CLASS Attempts.
Proctoring owns evidence; Assessment remains authoritative for answers and Attempt
lifecycle/deadlines. Copy sanitized answer-change metadata without answer content,
not authoritative answer state. Freeze evidence schemas/ingestion transport,
timestamps, identity, ordering/retry handling, authorization and retention under
PROCTOR-01/08. Transient browser/WebSocket signals are not durable storage, and
untrusted observations must not automatically alter grades or impose sanctions.
Critical integration facts retain the accepted outbox/idempotency guarantees.
