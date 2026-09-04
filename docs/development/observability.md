# Observability Baseline

Status: **Accepted v0.2**

## Structured logging

Production backend logs use structured output.

Recommended fields:

- timestamp;
- level;
- service;
- trace ID;
- span ID;
- stable event/action name;
- safe business identifiers when useful.

Do not log credentials, tokens, OTPs, media payloads, or unnecessary PII.

Local logs may use human-readable console formatting.

## Distributed tracing

Use OpenTelemetry-compatible tracing through Spring/Micrometer Observation/Tracing.

Propagate trace context across:

- Gateway -> HTTP service requests;
- service-to-service REST;
- RabbitMQ publish/consume where supported.

The purpose is to trace a distributed request/business flow without relying on one process log.

## Metrics and health

Each Spring application uses:

- Spring Boot Actuator;
- Micrometer.

Expose internal/secured endpoints as appropriate for:

- health;
- liveness;
- readiness;
- Prometheus metrics.

Do not expose sensitive actuator information publicly without controls.

## Local observability profile

Observability stack is optional during normal development.

An optional Docker profile may provide:

- Prometheus;
- Grafana;
- Tempo;
- Loki if selected.

Exact log backend/vendor and production telemetry platform remain TBD.

For local IDE-run applications, set `OTEL_EXPORTER_OTLP_ENABLED=true` and
use `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces`. Tempo's
OTLP HTTP and gRPC receivers are bound inside the optional Compose service and
published only on the local host. Applications running in a future container
on the same Compose network should use the service hostname instead of
`localhost`.
