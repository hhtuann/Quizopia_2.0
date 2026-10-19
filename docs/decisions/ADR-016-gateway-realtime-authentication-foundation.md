# ADR-016: Gateway realtime authentication foundation

Status: **Accepted — W4 design frozen; B1 implementation only**

Recorded: 2026-10-19 (Asia/Bangkok). Frozen source: external W4-A1 R3 text
with SHA-256
`b5f7a715e8533c1f87a84d274ec34adde4c5891e9c521c0dfcf7c3b1a9c22a86`
and its four co-located machine-readable contracts.

## Decision

RT-01 uses a Gateway-local native WebFlux WebSocket hub at the public edge. It
does not use STOMP and does not add a service. Each Gateway instance owns its
accepted sockets. The exact public surfaces are `POST /api/realtime/tickets`
and `GET /ws/realtime`; both prefixes are reserved from generic route fallback.

The browser obtains a short-lived, one-use, 256-bit ticket with an admitted USER
JWT and approved Origin. Redis stores only the SHA-256 ticket key and bounded
actor/token-fingerprint/Origin/time metadata. The WebSocket HTTP handshake is
credential-free and negotiates only `quizopia.realtime.v1`. A successful 101 is
only `PENDING_AUTH`; the exact first text frame carries AUTH ticket and USER JWT.
Atomic ticket redemption, exact Origin/token/actor binding, JWT verification and
fresh LD-05 admission all precede `AUTHENTICATED`.

B1 deliberately has no successful subscription or resource-delivery state.
Well-formed SUBSCRIBE and UNSUBSCRIBE requests receive correlated
`NOT_ACCESSIBLE`; B2 must add Assessment-owned authorization before any success.

RT-02 uses best-effort invalidation hints. Loss, duplication and reordering are
expected. Clients reconcile authoritative state through REST after reconnect or
gap detection and by a bounded periodic resync. RabbitMQ invalidation transport,
outbox production and Gateway fanout remain B3 work; the schema published in
`shared/event-contracts` is a frozen coordination contract, not an implementation.

Identity may add audience `quizopia-assessment-realtime-authority` only to an
access token issued by `client_credentials` for the exact configured dedicated
Gateway realtime client, with exactly
`assessment.realtime.subscription-authorize`. Other service clients retain their
existing claims. The Assessment private authorization endpoint remains B2.

## Security and rollout consequences

- Realtime runtime capability is default OFF. Missing authority configuration,
  Redis failure or Identity uncertainty fails closed.
- Origin is an ingress allowlist check, never caller authentication.
- Ticket/JWT values are forbidden from URL, query, subprotocol, cookies-as-auth,
  application-added handshake headers, logs, metrics and traces.
- Ticket TTL, AUTH deadline and capacity/rate values are bounded engineering
  defaults, not production SLOs. Load/proxy evidence and security acceptance are
  required before any production activation.
- WebSocket remains UI acceleration only. REST remains authoritative for answers,
  submission, grading, results and reconciliation.
- Existing Wave 3 privacy, LiveKit and proctoring constraints are unchanged.

## Contract map

- `shared/api-contracts/realtime-ticket.openapi.yaml`
- `shared/api-contracts/realtime-ws-v1.schema.json`
- `shared/api-contracts/assessment-realtime-authority.openapi.yaml`
- `shared/event-contracts/realtime-amqp-invalidation-v1.schema.json`

The latter two describe future B2/B3 collaboration boundaries; publishing them
does not activate or implement those capabilities.
