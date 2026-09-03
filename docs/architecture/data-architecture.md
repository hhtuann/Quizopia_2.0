# Data Architecture

Status: **Accepted baseline v0.2**

## Principle

Quizopia uses polyglot persistence only when the workload justifies it.

Microservices do not imply a different database product per service.

## PostgreSQL 17

PostgreSQL is the default transactional source of truth.

Local/early deployment may use one physical PostgreSQL server/cluster with service-owned databases:

```text
identity_db
quiz_db
classroom_db
assessment_db
community_db
proctoring_db
ai_db
```

Each service has its own database credential and permissions.

A service credential must not grant access to another service database.

No cross-service SQL joins.

## Flyway

Each service owns its Flyway migrations.

Local/dev baseline:

```text
service starts
-> Flyway migrates its own DB
-> Hibernate validates mappings
-> application becomes ready
```

Do not use production `ddl-auto=update/create`.

Production may later run migrations as a separate deployment job without changing schema ownership.

## Redis

Redis is accepted for justified ephemeral/distributed concerns such as:

- account revocation propagation/checks;
- rate limiting;
- short-lived distributed state;
- presence;
- realtime coordination;
- temporary access state;
- quotas/caching where appropriate.

Local baseline may use one Redis instance with service-specific key prefixes/namespaces.

Redis is NOT authoritative for:

- answers;
- grades/results;
- publications;
- classroom membership;
- durable quiz content.

## RabbitMQ

RabbitMQ is the accepted asynchronous integration broker.

It is not the transactional database.

Critical integration events use a transactional outbox in the owning service DB and idempotent consumers.

## Object storage

Binary content uses an S3-compatible abstraction.

Local implementation: MinIO.

Possible production backends include MinIO or managed S3-compatible/object-storage providers.

Examples of stored objects:

- quiz images;
- AI source PDF/DOCX/PPTX documents;
- generated DOCX files;
- proctoring snapshots;
- future recordings.

The owning business service stores authoritative metadata/access policy in its database.

## AI vector storage

Initial baseline:

```text
AI Service
-> ai_db (PostgreSQL)
-> pgvector
```

This avoids adding a separate vector database before measurements justify it.

A later specialized vector store requires a deliberate decision based on product/scale needs.

## Future specialized stores

Not baseline requirements:

- OpenSearch for large search/discovery workloads;
- ClickHouse for very high-volume event analytics.

Introduce only after measured need.

## Integrity

Use defense in depth inside each service boundary:

- FK constraints;
- unique constraints;
- check constraints;
- PostgreSQL indexes/partial indexes where useful;
- application/domain validation;
- intentional locking/concurrency control;
- exact transactions for correctness-sensitive operations.

Cross-service integrity is handled through stable IDs, contracts, outbox/events, reconciliation, and idempotency rather than cross-database foreign keys.
