# ADR-005: PostgreSQL-First Polyglot Persistence

Status: **Accepted — updated v0.2**

## Decision

Use PostgreSQL 17 as the default transactional database.

Every business service owns a separate database and database credential, even if databases initially share one physical PostgreSQL server/cluster.

Use additional stores only for justified workloads:

- Redis -> revocation, rate limiting, presence, cache/temporary distributed state;
- S3-compatible object storage -> binary files/evidence; MinIO is the local implementation;
- PostgreSQL + pgvector -> initial AI vector retrieval baseline;
- RabbitMQ -> asynchronous integration events (not a source-of-truth database).

Do not create a different database technology per microservice merely to demonstrate polyglot persistence.

## Rules

- no cross-service DB access;
- no cross-service SQL joins;
- Flyway per service;
- Redis is not durable authority for business records;
- binary object metadata/access policy stays with the owning business service.

## Future

OpenSearch, ClickHouse, dedicated vector stores, or other specialized databases require measured/product justification and a new decision if they materially change the architecture.
