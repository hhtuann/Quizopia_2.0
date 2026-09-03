# Realtime Architecture

Status: **Baseline v0.1**

## Decision

Quizopia uses different transports for different guarantees.

```text
REST/HTTP   -> authoritative business mutations and queries
WebSocket   -> application realtime events
WebRTC      -> realtime media
```

Do not collapse all three concerns into one transport.

## REST

Use REST/transactional HTTP APIs for:

- attempt start;
- answer autosave;
- submit;
- grading-triggering command;
- result retrieval;
- quiz/class/community mutations.

Assessment answers require:

- durability;
- retries;
- ordering;
- transactionality;
- idempotency.

Therefore they must not use WebRTC DataChannel as the authoritative persistence mechanism.

## WebSocket

Use WebSocket/STOMP for:

- teacher monitoring events;
- attempt start/submit events;
- active participant counts;
- proctor signals;
- presence where needed;
- server-time synchronization;
- fast UI updates.

REST/database remains the source of truth.

Clients should reconcile/refetch authoritative state after reconnect or when event gaps are detected.

## WebRTC / LiveKit

Use WebRTC for media:

- camera;
- optional microphone;
- future screen sharing.

LiveKit is the baseline media platform/SFU.

Business services authorize who may join/publish/subscribe; LiveKit handles realtime media transport.

## Scaling

The legacy in-memory Spring simple broker is not assumed to be sufficient for horizontally scaled Quizopia 2.0.

Exact WebSocket broker/topology is **TBD**.

## Proctoring media

For a teacher grid:

- thumbnails should use lower-resolution layers;
- selected student may use a higher-resolution layer;
- hidden/not-visible video tracks should be paused where supported.

Full video recording is deferred from MVP.

## Browser limitations

WebSocket/WebRTC do not grant access to unrelated browser-tab metadata.

Screen sharing, if added, uses browser media APIs and explicit user consent.
