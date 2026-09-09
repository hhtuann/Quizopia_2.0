# Event contracts

`shared/event-contracts` is the technical coordination and review boundary for
versioned integration-event schemas. The producer or owning service owns the
semantics of the business fact; this directory does not own domain behavior.

## Ownership and publication

An integration event describes a business fact that has already occurred. Use
business-oriented, past-tense names. Publish a reviewed schema here only for an
accepted producer-owned fact; do not create command-like or speculative
contracts, or invent names for unresolved product behavior. Names shown in
architecture documentation are examples, not production schemas by themselves.

## Identity, versioning, and consumers

Schemas are versioned and every event has a stable, unique `eventId`. The
accepted conceptual envelope is approximately:

```text
eventId
eventType
eventVersion
occurredAt
aggregateId
traceId
payload
```

This is a conceptual baseline, not a mandatory JSON Schema, shared runtime DTO,
Java class, exact serialization contract, or finalized universal field list.
Stable identity and schema versioning are required, while exact serialization
and final field details must be accepted separately.

Consumers must expect duplicate delivery and remain idempotent. Use `eventId`
for deduplication or inbox/processed-event handling when side effects require
it, without cross-service database access or shared business persistence models.
Assume ordering only when an explicit sequence or revision convention exists.
Propagate trace context where practical.

## Durable publication and transport

RabbitMQ is the integration-event transport baseline. Critical integration
events use a transactional outbox or equivalent recoverable mechanism. Each
owning service owns its domain transaction, outbox persistence, and publication
implementation; this directory provides none of those components.

The exact outbox implementation strategy, library, polling, or CDC mechanism
remains open under `EVT-02`. Exact RabbitMQ exchange and queue conventions,
routing, retry, and DLQ conventions remain open under `EVT-03`.

## Security and realtime boundary

Never put credentials or tokens in events. Avoid secrets and unnecessary PII.
Sensitive assessment or proctoring data requires accepted ownership and access
rules before it is included in an event contract.

RabbitMQ integration events communicate business facts between services.
Browser WebSocket events accelerate realtime UI behavior; they do not need to
mirror integration events one-to-one and are not an authoritative integration
store. WebSocket schemas are not defined here.

## Open semantics and shared boundary

Under `EVT-01`, the final Assessment result/gradebook event name and schema are
still TBD; do not choose between documented candidate names or create another.

This directory must not contain Java business-event DTOs, JPA entities,
repositories, domain-event superclasses, shared business or persistence models,
Rabbit publisher business libraries, a common-domain module, or a Maven module.
Services must not depend on a shared business JAR to publish or consume events.
