# ADR-010: Hybrid Local Development Infrastructure

Status: **Accepted**

## Decision

Default local development is hybrid.

Docker runs infrastructure:

- PostgreSQL 17;
- Redis;
- RabbitMQ;
- MinIO;
- Mailpit;
- LiveKit.

Developers normally run the active Spring service/gateway directly from the IDE and the frontend through the Next.js dev server.

Provide a full-Docker profile/path for integration/demo/reproduction.

Do not add Eureka/Consul; use environment URLs and deployment/Docker/Kubernetes DNS.

Local/dev service startup runs Flyway migration for the service-owned database, then Hibernate schema validation.

## Additional accepted local baselines

- one PostgreSQL server with separate DB + credential per service;
- one Redis instance may use separate key namespaces;
- MinIO is the S3-compatible local object store;
- Mailpit captures local/test email;
- AI starts with PostgreSQL + pgvector.
