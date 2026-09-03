# System Architecture

Status: **Accepted pre-scaffold baseline v0.2**

## Architectural style

Quizopia 2.0 uses **coarse-grained microservices** in a true monorepo.

The purpose is clear ownership and independently scalable workloads, not maximizing service count.

## Logical topology

```mermaid
flowchart TB
    Browser[Browser / Next.js]
    GW[Spring Cloud Gateway]

    ID[Identity Service\nSpring Authorization Server]
    QUIZ[Quiz Service]
    CLASS[Classroom Service]
    ASSESS[Assessment Service]
    COMM[Community Service]
    PROC[Proctoring Service]
    AI[AI Service]

    RMQ[(RabbitMQ)]
    REDIS[(Redis)]
    PG[(PostgreSQL 17\nservice-owned databases)]
    OBJ[(S3-compatible object storage\nMinIO local)]
    LK[LiveKit / WebRTC]
    MAIL[Mailpit local/test]
    OBS[Optional local observability\nPrometheus / Grafana / Tempo]

    Browser -->|HTTPS business API| GW
    Browser <-->|WebSocket public edge| GW

    GW --> ID
    GW --> QUIZ
    GW --> CLASS
    GW --> ASSESS
    GW --> COMM
    GW --> PROC
    GW --> AI

    Browser <-->|WebRTC media| LK
    PROC --> LK

    ID --> PG
    QUIZ --> PG
    CLASS --> PG
    ASSESS --> PG
    COMM --> PG
    PROC --> PG
    AI --> PG

    ID -. events .-> RMQ
    QUIZ -. events .-> RMQ
    CLASS -. events .-> RMQ
    ASSESS -. events .-> RMQ
    COMM -. events .-> RMQ
    PROC -. events .-> RMQ
    AI -. events .-> RMQ

    ID -. revocation / temporary state .-> REDIS
    GW -. revocation / rate state .-> REDIS
    ASSESS -. justified ephemeral state .-> REDIS
    PROC -. presence / ephemeral state .-> REDIS

    QUIZ --> OBJ
    AI --> OBJ
    PROC --> OBJ

    ID --> MAIL

    GW -. telemetry .-> OBS
    ID -. telemetry .-> OBS
    QUIZ -. telemetry .-> OBS
    CLASS -. telemetry .-> OBS
    ASSESS -. telemetry .-> OBS
    COMM -. telemetry .-> OBS
    PROC -. telemetry .-> OBS
    AI -. telemetry .-> OBS
```

This is a logical architecture. Deployment topology may evolve without violating service ownership.

## Public edge

Spring Cloud Gateway is the public business/API edge.

Browser business HTTP calls do not target backend microservices directly.

Public WebSocket endpoints are routed through the edge topology.

WebRTC media is intentionally separate and connects to LiveKit.

## Business services

- Identity Service
- Quiz Service
- Classroom Service
- Assessment Service
- Community Service
- Proctoring Service
- AI Service

No standalone Grading Service is part of the baseline; grading stays with Assessment to preserve transactional correctness.

## Authentication topology

Identity Service acts as Quizopia's authorization server.

It issues Quizopia credentials/tokens after local or Google-based authentication.

Gateway and each protected service validate user JWTs independently using Quizopia public signing material/JWKS.

Internal synchronous service calls use OAuth2 Client Credentials service JWTs.

## Communication

### Authoritative business operations

Use transactional HTTP/REST APIs.

Examples:

- start/autosave/submit attempt;
- create/update quiz;
- classroom membership commands;
- result retrieval.

### Synchronous internal service communication

Use direct internal REST when the caller needs an immediate answer.

Do not route service-to-service traffic through the public gateway.

### Integration events

Use RabbitMQ for asynchronous business facts.

Critical events require transactional outbox publication and idempotent consumers.

### Application realtime

Use WebSocket for UI acceleration/realtime signals.

The database/read API remains authoritative and clients reconcile after reconnect.

### Realtime media

Use WebRTC/LiveKit for camera, optional microphone, and future screen sharing.

## Data ownership

Every service owns its database and credential.

Databases may share one physical PostgreSQL server/cluster in local or early deployment, but services must not access another service's data store.

## Critical assessment invariant

An active assessment must keep working when Quiz Service is unavailable.

Assessment therefore stores a self-contained immutable delivery snapshot before the active-attempt path depends on it.

The exact publication transition at which that snapshot is finalized remains a feature-level open question.

## Local development

Default workflow is hybrid:

- Docker runs infrastructure;
- developer runs the active backend service/gateway in the IDE;
- developer runs Next.js with its normal dev server;
- a full-container profile may exist for integration/demo use.
