# Event contracts

This directory is reserved for versioned RabbitMQ integration-event schemas.
No business event is defined by the scaffold. Future events must use stable
event identity/versioning, avoid secrets and unnecessary PII, and be backed by
the owning service's transactional outbox where required.
