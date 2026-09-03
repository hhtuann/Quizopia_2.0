# ADR-005: PostgreSQL-First Polyglot Persistence

Status: **Accepted**

## Context

Microservices allow independent persistence choices, but unnecessary database diversity increases operational complexity.

Quizopia also needs object storage, distributed temporary state, and AI retrieval.

## Decision

Use PostgreSQL 17 as the default transactional database.

Use additional storage only when the workload justifies it.

Baseline:

- PostgreSQL -> transactional business data;
- Redis -> cache/rate/presence/temporary distributed state where justified;
- S3-compatible object storage (MinIO or managed) -> binary files/evidence;
- pgvector is a candidate for early AI vector search.

Do not introduce a different database per service merely to demonstrate polyglot persistence.

## Future

OpenSearch/ClickHouse or other specialized stores may be introduced only after requirements/measurements justify them.

## Consequence

Database ownership remains per service even when services share one physical PostgreSQL cluster.
