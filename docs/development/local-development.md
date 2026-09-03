# Local Development

Status: **Accepted pre-scaffold baseline v0.2**

## Development mode

Use a **hybrid** workflow by default.

### Docker infrastructure

Default local infrastructure includes:

- PostgreSQL 17;
- Redis;
- RabbitMQ;
- MinIO;
- Mailpit;
- LiveKit.

### Code under active development

Run directly from IDE/dev server when practical:

- Spring Cloud Gateway;
- backend microservice(s) currently being edited;
- Next.js frontend.

This improves debugging/build feedback compared with rebuilding every application container for every code change.

## Full Docker profile

Provide a full-container profile/compose capability for:

- integration testing;
- demo;
- environment reproduction;
- team verification.

The exact Compose file split/profile names are scaffold details.

## Optional observability profile

Observability tools are optional for normal local development.

An optional profile may run:

- Prometheus;
- Grafana;
- Tempo;
- Loki if chosen for local log aggregation.

The code itself must still emit the agreed telemetry whether this profile is running or not.

## PostgreSQL

One local PostgreSQL server may host:

```text
identity_db
quiz_db
classroom_db
assessment_db
community_db
proctoring_db
ai_db
```

Use separate credentials/permissions per service.

## Redis

One local Redis instance is acceptable with clear service key prefixes/namespaces.

## Mail

Use Mailpit for local/test OTP/email flows.

Do not send real verification emails by default in developer tests.

## Object storage

Use MinIO locally behind the S3-compatible storage abstraction.

## AI

- use `ai_db` + pgvector initially;
- external AI provider credentials must be optional for developers not working on AI;
- support a fake/test provider path.

## Service discovery

Do not add Eureka/Consul to the baseline.

Use environment-configured internal URLs and deployment/Docker/Kubernetes DNS.

## Database migrations

Local/dev:

```text
Spring service startup
-> Flyway migration for own DB
-> Hibernate validate
```

## Configuration

Provide root and service-specific `.env.example`/configuration documentation.

Real `.env` and secrets remain uncommitted.

## Scaffold-time details still to assign

- exact local ports;
- compose/profile names;
- helper script names;
- exact command aliases;
- frontend package-manager lock-in if not already finalized;
- exact Spring Boot patch/dependency versions.
