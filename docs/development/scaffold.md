# Development scaffold

Status: **Implemented baseline v0.1**

The repository now contains one Next.js frontend, one Spring Cloud Gateway,
and seven independently buildable Spring projects. The scaffold exposes only
technical configuration; business endpoints, entities, repositories, event
classes, outbox tables, and domain migrations are intentionally absent.

The selected baseline is Java 21, Spring Boot 4.1.1, Spring Cloud 2025.1.2,
Springdoc 3.1.0, Maven Wrapper 3.9.11, Testcontainers 1.21.3, Spotless
2.46.1, and ArchUnit 1.4.1. The frontend uses Next.js 16.1, React 19.2,
TypeScript strict mode, Tailwind CSS 4, and pnpm.

## Local workflow

1. Copy `.env.example` to an untracked `.env` only when overriding defaults.
2. Start infrastructure with `scripts/dev-infra.ps1` or `scripts/dev-infra.sh`.
3. Run the frontend from `frontend/` with `pnpm install` then `pnpm dev`.
4. Run the gateway or the service being edited directly from its IDE or its
   Maven Wrapper.
5. Stop infrastructure with the matching `stop-infra` script.

The default Compose project is infrastructure-only. The optional
`observability` profile adds Prometheus, Grafana, and Tempo:

```text
docker compose --profile observability up -d
```

## Ports

| Component                                |                      Port |
| ---------------------------------------- | ------------------------: |
| Gateway                                  |                      8080 |
| Identity / Quiz / Classroom / Assessment | 8081 / 8082 / 8083 / 8084 |
| Community / Proctoring / AI              |        8085 / 8086 / 8087 |
| PostgreSQL                               |                      5432 |
| Redis                                    |                      6379 |
| RabbitMQ / management                    |              5672 / 15672 |
| MinIO API / console                      |               9000 / 9001 |
| Mailpit SMTP / web                       |               1025 / 8025 |
| LiveKit HTTP / RTC TCP / RTC UDP         |        7880 / 7881 / 7882 |

PostgreSQL initialization creates seven service databases and seven service
roles. The init script runs only when the PostgreSQL volume is first created;
recreate the local volume if the database catalog is intentionally reset.

## Configuration ownership

Each project has a local `.env.example`. Runtime values are supplied through
environment variables. Identity alone receives Google, mail, Redis, and
authorization-server configuration. AI receives only its own database and
technical broker configuration. No production secrets or private signing keys
are committed.

## OpenAPI contract workflow

When a real API is implemented, run the owning service and download its
`/v3/api-docs` document. Review the contract, store the versioned artifact in
`shared/api-contracts/`, and generate frontend types/client code from that
reviewed artifact. No generated business client exists yet because no business
API exists.

## Verification

The root scripts run the frontend checks and `verify` independently for the
gateway and all services. CI repeats these checks with path-aware PR jobs and
full validation on `develop`/`main`.
