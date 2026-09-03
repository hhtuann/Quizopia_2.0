# Event Conventions

Status: **Baseline v0.1**

## Event envelope

Integration/realtime events should include stable metadata such as:

```json
{
  "eventId": "...",
  "eventType": "ATTEMPT_SUBMITTED",
  "eventVersion": 1,
  "occurredAt": "...",
  "aggregateId": "...",
  "payload": {}
}
```

Exact transport serialization is TBD.

## Rules

- Every event has a unique event ID.
- Event names are business-oriented.
- Event schemas are versioned.
- Avoid unnecessary PII.
- Never put credentials/tokens/secrets in events.
- Critical integration events are published after transaction commit through a recoverable mechanism.
- Consumers are idempotent.
- Consumers tolerate duplicate delivery.
- Consumers should handle out-of-order delivery where possible or use revisions/sequence numbers when order matters.

## Realtime events

Realtime UI events are acceleration, not the authoritative database.

After reconnect:

- client reconciles with REST/read API;
- client must tolerate event loss.

## Assessment/proctoring

Do not publish learner answer keys/correctness through monitoring events before policy allows them.

Answer-change audit events are sensitive evidence and require strict authorization.

## Event naming

Examples:

```text
USER_EMAIL_VERIFIED
QUIZ_VERSION_PUBLISHED
CLASSROOM_MEMBER_JOINED
ASSIGNMENT_CREATED
ATTEMPT_STARTED
ATTEMPT_SUBMITTED
GRADE_CREATED
PROCTORING_SESSION_STARTED
PROCTORING_FLAG_CREATED
```

Use past-tense facts for integration events.
