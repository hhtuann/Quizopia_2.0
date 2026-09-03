# Data Architecture

Status: **Baseline v0.1**

## Principle

Quizopia 2.0 allows polyglot persistence only when a workload justifies it.

Using microservices does not mean every service must use a different database technology.

## Baseline storage

### PostgreSQL 17

Primary source of truth for transactional domain data.

Early deployment may use one PostgreSQL cluster with separate service-owned databases/schemas, provided service ownership is enforced.

Example logical databases:

- `identity_db`
- `quiz_db`
- `classroom_db`
- `assessment_db`
- `community_db`
- `proctoring_db`
- `ai_db`

No cross-service joins.

No direct cross-service table access.

### Redis

Use only where there is a concrete need, for example:

- rate limiting;
- short-lived distributed state;
- online presence;
- realtime fan-out support;
- temporary access/session state;
- AI quotas/caching.

Redis is not the authoritative store for assessment answers/grades.

### Object storage

Use S3-compatible object storage such as MinIO or a managed provider for binary content such as:

- quiz images;
- uploaded PDF/DOCX/PPTX source documents;
- generated DOCX exam files;
- proctoring snapshots;
- future proctoring recordings.

Business metadata remains in the owning service database.

### Vector search

PostgreSQL + pgvector is a candidate baseline for early AI retrieval.

Exact vector database choice is **TBD** and must be driven by measured needs.

### Search/analytics future options

Do not introduce them until justified.

Possible future choices:

- OpenSearch for large full-text/discovery workloads;
- ClickHouse for very high-volume proctor/event analytics.

## Schema management

Each service owns its Flyway migrations.

Hibernate/JPA should validate schema mappings rather than mutate production schema automatically.

## Integrity

Preserve defense-in-depth:

- foreign keys within a service database;
- unique constraints;
- check constraints;
- partial indexes where PostgreSQL semantics are useful;
- application-level validation;
- transaction boundaries for correctness-sensitive workflows.

Cross-service integrity cannot use cross-database foreign keys; it must be handled through contracts, durable identifiers, events, reconciliation, and idempotency.
