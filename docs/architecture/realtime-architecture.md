# Realtime Architecture

Status: **Accepted transport responsibilities v0.2**

## Decision

```text
REST/HTTP   -> authoritative business mutations/queries
WebSocket   -> application realtime signals/UI acceleration
WebRTC      -> realtime media
RabbitMQ    -> asynchronous service integration events
```

These transports coexist; one does not replace the others.

## Public WebSocket edge

Browser WebSocket connections are exposed through the public gateway/edge topology rather than exposing arbitrary service ports publicly.

Exact internal WebSocket broker/relay topology for horizontal scale is still TBD.

RabbitMQ is accepted as the integration-event broker, but using RabbitMQ as a STOMP broker relay is not automatically implied unless explicitly chosen later.

## REST

Use for:

- attempt start;
- autosave;
- submit;
- results;
- ordinary commands/queries.

Assessment writes need persistence, retry, sequencing, idempotency, and transactionality.

## WebSocket

Use for:

- monitoring signals;
- attempt lifecycle UI updates;
- presence where required;
- server-time synchronization;
- proctoring signals;
- fast UI invalidation/refresh hints.

WebSocket delivery is not the database.

Clients reconcile after reconnect/event loss.

## WebRTC / LiveKit

Use for:

- camera;
- optional microphone if later required;
- future consent-based screen sharing.

Business services authorize participant access; LiveKit transports media.

Do not route media through Spring business REST services.

Do not use WebRTC DataChannels as authoritative assessment answer persistence.

## Proctoring video grid

Design for adaptive subscription:

- low-resolution layers for many thumbnails;
- higher layer for selected student;
- pause/unsubscribe hidden tracks where supported.

Full-session recording remains deferred from MVP.

## Browser limitations

Normal web code cannot list unrelated browser tabs/URLs or inspect arbitrary desktop applications.

Screen capture, if added later, requires browser/user consent.
