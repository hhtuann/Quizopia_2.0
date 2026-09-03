# ADR-004: REST + WebSocket + WebRTC Responsibilities

Status: **Accepted**

## Decision

Use different transports according to required guarantees.

### REST/HTTP

Authoritative business operations:

- attempt start;
- autosave;
- submit;
- results;
- normal CRUD/commands.

### WebSocket

Application realtime signals:

- attempt lifecycle updates;
- monitoring;
- presence;
- server-time synchronization;
- proctoring events.

### WebRTC via LiveKit

Realtime media:

- camera;
- optional microphone;
- future screen sharing.

## Explicit non-decision

Do not use WebRTC DataChannel as the authoritative answer-persistence path.

## Rationale

Assessment writes require transactionality, retry, ordering, persistence, and idempotency.

Media transport has different latency/bandwidth requirements.

## Consequence

Quizopia will use REST, WebSocket, and WebRTC together rather than replacing one with another.
