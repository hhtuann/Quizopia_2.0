# ADR-011: Observability Baseline

Status: **Accepted**

## Decision

All Spring applications include:

- structured production logging;
- Spring Boot Actuator;
- Micrometer metrics/observation;
- OpenTelemetry-compatible distributed tracing.

Trace context should propagate across HTTP and RabbitMQ where supported.

Local observability tools are optional through a Docker profile and may include Prometheus, Grafana, Tempo, and a log backend such as Loki.

## Security

Do not log:

- passwords;
- OTPs;
- access/refresh tokens;
- private keys/client secrets;
- raw media;
- unnecessary PII.

Production observability vendor/backend remains TBD.
