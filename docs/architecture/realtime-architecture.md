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

### Wave 3 authorized camera monitoring

The [W3-A Leader decision](../specifications/w3-a-publication-scheduling-monitoring-policy.md)
requires LiveKit camera monitoring for optionally monitored eligible CLASS
Publications. Proctoring owns room/session/token orchestration; Assessment owns
Attempt validity/deadlines; Classroom owns Teacher authorization and membership.
Teachers see only authorized active participants and Students cannot subscribe
to peers. Freeze scoped short-lived access, revocation and session-end behavior.
Required privileged checks fail closed when authority cannot be verified.

Capture requires transparent browser permission and visible active status;
permission denial/loss, unavailable devices, reconnect and network interruption
must be represented truthfully. Exact refusal/loss/accommodation outcomes remain
OPEN. Media/audio recording and mandatory/strict screen sharing are excluded.
Satisfy the W3-A privacy/security release gates before real-learner enablement.

Durable Activity Evidence belongs to Proctoring, with reviewed sanitized
Assessment integration. Browser observations can be manipulated and do not prove
misconduct. Exact evidence schema/transport/retention remain OPEN; transient
WebSocket delivery is not durable evidence storage. Monitoring/realtime failures
must not corrupt answers/results, change grades or silently extend deadlines.
AI behavior monitoring remains future design, deferred beyond Wave 3.

## Proctoring video grid

Design for adaptive subscription:

- low-resolution layers for many thumbnails;
- higher layer for selected student;
- pause/unsubscribe hidden tracks where supported.

Full-session recording remains deferred from MVP.

## Browser limitations

Normal web code cannot list unrelated browser tabs/URLs or inspect arbitrary desktop applications.

Screen capture, if added later, requires browser/user consent.
