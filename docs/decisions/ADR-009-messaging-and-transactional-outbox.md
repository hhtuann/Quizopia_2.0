# ADR-009: RabbitMQ + Transactional Outbox

Status: **Accepted**

## Decision

Use RabbitMQ for initial asynchronous microservice integration.

Critical business events use a transactional outbox (or equivalent recoverable mechanism) written atomically with the domain transaction.

Outbox publication to RabbitMQ can retry after broker/network failures.

Consumers are idempotent and use stable event identity; inbox/processed-event state may be used where side effects require it.

## Rationale

This avoids the dual-write failure where database commit succeeds but broker publication is permanently lost.

Kafka is not baseline infrastructure; it may be evaluated later for measured high-volume streaming/retention workloads.
