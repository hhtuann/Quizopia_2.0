# W3-M frontend Activity Evidence and LiveKit implementation

Status: **FUNCTIONAL IMPLEMENTATION — REAL-LEARNER ACTIVATION BLOCKED**. This
document records browser-side engineering evidence, not production approval,
provider qualification, legal authorization or proof of hard media revocation.

## Source and ownership

- Worktree: `/home/hhtuann/Documents/Projects/Quizopia_2.0-w3m`.
- Branch: `feature/frontend-w3m-monitoring`.
- Starting `origin/develop`: `e5fbf50fda2389dcd72499ef4979df0217ef8f30`.
- That baseline includes merged backend PRs #88 through #91 and frontend PRs #77
  and #92. Implemented OpenAPI/controllers/Gateway adapters were inspected rather
  than inferred from historical Draft PR text.
- Frontend-owned source is under `frontend/features/proctoring/**` plus the
  monitoring App Router entries and monitoring-specific tests.
- No Assessment Attempt editor, answer SAVE/SUBMIT, Teacher Results, backend
  schema, event contract, Gateway rule or production feature default was changed.

The exact final frontend HEAD and Draft PR URL are added to the final report after
commit and push; this document does not predict a self-referential SHA.

## Implemented backend route inventory

The browser uses only these Gateway paths with the existing in-memory USER token
executor:

| Method | Path                                                                     |
| ------ | ------------------------------------------------------------------------ |
| POST   | `/api/proctoring/monitoring/sessions`                                    |
| GET    | `/api/proctoring/monitoring/sessions/by-attempt/{attemptId}`             |
| GET    | `/api/proctoring/monitoring/sessions/{id}`                               |
| POST   | `/api/proctoring/monitoring/sessions/{id}/events`                        |
| GET    | `/api/proctoring/monitoring/sessions/{id}/events?after=&limit=`          |
| POST   | `/api/proctoring/monitoring/sessions/{id}/reconcile`                     |
| POST   | `/api/proctoring/monitoring/sessions/{id}/media-grants`                  |
| GET    | `/api/proctoring/monitoring/sessions/{id}/media-grants/{grantId}`        |
| POST   | `/api/proctoring/monitoring/sessions/{id}/media-grants/{grantId}/renew`  |
| POST   | `/api/proctoring/monitoring/sessions/{id}/media-grants/{grantId}/revoke` |

Create-session sends only `{attemptId}`. All media commands are bodyless and
query-free. No browser-selected Classroom, Teacher, Student, role, room,
participant privilege or server credential exists. A parity test reads the current
OpenAPI documents and guards the operation/event inventory.

The suggested Teacher session-ID route was intentionally not implemented. Current
Gateway source maps `GET /sessions/{id}` to STUDENT, while the reviewed Teacher-safe
composition is exact `GET /sessions/by-attempt/{attemptId}`. The functional Teacher
route is therefore `/app/monitoring/teacher/attempts/[attemptId]`, with
`/app/monitoring/teacher` as a known-Attempt entry. This avoids depending on a
dual-role token or bypassing the public edge.

## M1 Activity Evidence

`useStudentMonitoring` exposes the composition boundary for a future Attempt UI:

- `start(eligibility)` accepts the authoritative Attempt projection and rejects
  absent, PUBLIC, unconfigured/disabled, durationless or non-ACTIVE input before
  contacting Proctoring;
- `verifyAndStart(attemptId)` supports the explicit standalone route, where the
  backend performs the complete current authority check;
- `questionViewed`, `questionLeft`, `answerSelected`, `answerChanged` and
  `answerCleared` accept only a question number and never answer content;
- `stop()` detaches browser observers, reconciles, clears the in-memory queue and
  does not mutate Assessment.

The stream owns one random `streamId`, an immutable random `eventId` per original
observation and monotonic integer sequences. It holds a bounded 64-event in-memory
queue and one in-flight append. An uncertain outcome stops automatic retry; explicit
reconciliation replays the identical head event once so backend dedupe can return
the durable acknowledgment. It never uses localStorage, sessionStorage, IndexedDB,
analytics or LiveKit DataChannels. Queue overflow is counted and reported by the
stream snapshot rather than hidden. Logout/account switch fences the generation and
drops pending local evidence so it cannot cross identities.

Start operations reserve a unique operation ticket before any asynchronous
cleanup or request, abort the superseded request, verify the returned Attempt
binding, and refuse to attach observers after unmount or account change. A
definitive append rejection stops and clears the stream instead of retaining a
hidden blocked queue. The standalone page uses the server deadline only to
trigger authoritative reconciliation and shows pending, dropped, uncertain and
delivery state without treating it as Assessment state.

The browser adapter observes only this page's visibility, window focus, fullscreen,
online/offline and scoped duplicate-tab BroadcastChannel state. It cannot and does
not inspect other-tab URLs, browser history, other applications, clipboard,
keystrokes, screen contents or answer values.

Browser emission is limited to the 22 OpenAPI browser types. `SESSION_STARTED`,
`SESSION_ENDED` and `ANSWER_PERSISTED` are absent from the emit type. Teacher
timeline rendering labels `CLIENT_OBSERVATION`, `SERVER_AUTHORITY` and
`ASSESSMENT_AUTHORITY` separately. `assessmentRevision` stays a nullable canonical
decimal string. The UI explicitly says browser reports and Assessment persistence
facts are not proof of misconduct.

## M2 Student camera lifecycle

`StudentCamera` explains scope and viewers before any media request. A deliberate
button is required before lazy-loading `livekit-client` and requesting one local
video track. It never calls microphone or screen-capture APIs.

The lifecycle is:

1. Existing authorized ACTIVE monitoring session.
2. Deliberate browser camera request.
3. Truthful granted/denied/missing/device-error Activity observation.
4. Fresh backend Student media grant.
5. LiveKit connect with `autoSubscribe: false`.
6. Publish only the camera source with simulcast.
7. Display live only after SDK connection and camera publication succeed.
8. Renew the short backend lease with bounded, platform-safe timers.
9. On SDK reconnect, disconnect rather than silently reuse the prior credential;
   cancel renewal, revoke the prior grant, await teardown, and require a fresh
   backend grant before recovery.
10. On device end, terminal cleanup, account switch or unmount: detach/stop the
    local track, disconnect the SDK and separately request backend revocation.

The grant token stays only in controller memory. It is never placed in component
state, React Query, URLs, storage, logs, analytics or rendered output.
`GRANT_ISSUED` is shown as credential issuance, not camera activity. Denial/loss
applies no academic consequence because PROCTOR-11 remains open.

Student and Teacher media starts allocate unique pre-await operation tickets.
Tracks, rooms and grants are cleaned up by the operation that owns them; a
successful grant arriving after cancellation is best-effort revoked. Connection
loss cancels its renewal timer before SDK disconnect and backend revocation, so a
disconnected room cannot keep renewing. Monitoring Playwright tests explicitly
disable trace, screenshot and video artifacts because grant responses are bearer
credentials.

Teardown operations are serialized before a fresh grant can be requested. The UI
reports local-track stop, SDK disconnect and backend revoke outcomes separately;
`REVOKE_PENDING` remains an intent/state result and is never presented as hard
provider revocation.

## Teacher monitoring experience

The Teacher entry accepts only a known Attempt ID expected from Teacher Results.
Every lookup uses the fresh server-authorized by-Attempt route and never creates a
Student session. A masked 404 is rendered as inaccessible without revealing foreign
resource existence. The view shows only server-returned bindings and status.

Timeline pagination uses server ordinals (`after`, limit default) and de-duplicates
rows by ordinal. Refresh repeats the Attempt lookup before replacing the timeline.
Loading, empty, unavailable, masked-not-found and populated states are explicit.

Teacher media requests a grant for the returned exact session, validates the
server-returned `TEACHER` role and uses a subscribe-only adapter. It never requests
local media or publishes tracks. Only remote camera video is attached; non-camera
tracks are actively unsubscribed. Connected-without-camera, live, reconnecting,
disconnected and unavailable states remain distinct. SDK reconnect is fenced until
fresh backend authorization. Multiple camera tiles can be composed only from
multiple individually authorized Attempt lookups; no roster or bulk discovery is
invented.

## Integration contract for the parallel frontend workstreams

Agent 1's Attempt UI imports from `features/proctoring` and mounts monitoring only
inside the authoritative active Attempt experience. It passes:

```ts
interface MonitoringEligibility {
  attemptId: string;
  audience: "CLASS" | "PUBLIC";
  monitoringChoice: "UNCONFIGURED" | "ENABLED" | "DISABLED";
  attemptStatus: "ACTIVE" | "SUBMITTED" | "TIMED_OUT" | "ABANDONED";
  deadlineAt: string | null;
}
```

After `start`, navigation and answer controls may call the metadata-only methods.
Assessment SAVE/SUBMIT remains independent and must never await Proctoring delivery.
On terminal state or authority loss, call `stop` and unmount `StudentCamera`.

Agent 2's Teacher Results links a server-returned Attempt ID to
`/app/monitoring/teacher/attempts/{attemptId}` or composes
`TeacherMonitoringScreen` with that ID. It must not pass a fabricated session,
Student, Classroom or room identity.

## Verification and evidence boundaries

Synthetic automated coverage includes:

- eligibility exclusion and eligible CLASS acceptance;
- exact API paths/bodies/query bounds and session-generation fencing;
- strict event type/question/provenance/revision schemas;
- event ordering, queue bound, immutable uncertain replay and account-switch drop;
- visibility/focus/fullscreen/network observation scope;
- deliberate Student permission, denial, Student-only connection and cleanup;
- concurrent-start/unmount fencing, stale-grant revocation and mismatched binding;
- SDK reconnect fencing, disconnect renewal cancellation, memory-only token
  rendering and backend revoke attempt;
- Teacher subscribe-only/no-local-camera isolation;
- controlled official-SDK adapter tests for Student `autoSubscribe: false`,
  camera-only publication and Teacher non-camera unsubscribe behavior;
- Teacher timeline provenance and pagination model;
- OpenAPI route/event parity;
- Chromium flows for no collection before explicit start, denied camera, exact
  Activity envelope, fresh Teacher Attempt lookup and masked foreign Attempt.

The browser tests intercept Gateway responses. Media lifecycle unit tests use a
controlled adapter. They do not prove a real LiveKit publish/subscription, Cloud
revocation, provider outage cutoff or private service topology. The backend's owned
self-hosted LiveKit and five-service evidence remains separate and cannot be
relabeled as frontend/provider acceptance. No production or learner data was used,
and no shared containers, PostgreSQL or Redis state was changed.

An independent read-only security review covered capture scope, token handling,
permission disclosure, role separation, resource authorization, stale grants,
account switching, provenance, Assessment isolation, default-disabled behavior,
unsupported claims and fixture ownership. After race, teardown-ordering and
truthful-cleanup fixes, the final re-review reported 0 Blocker, 0 High and 0
Medium findings (9.7/10). This is code-review evidence, not Privacy approval or
real-provider acceptance.

## Open activation gates

The implementation leaves all documented gates open: evidence retention/deletion,
lawful basis and disclosures, minors, accommodations/refusal outcomes, authorized
viewer notice, provider/data location, secure target transport, operations and
incident procedures, independent Privacy/Security acceptance, and real-provider
original/refreshed-token revocation plus outage enforcement. Backend monitoring and
media defaults remain disabled. A local flag, mock, self-hosted LiveKit run, short
lease or browser permission is not real-learner authorization or a hard-revocation
guarantee.

No monitoring observation automatically submits, abandons, fails, grades or
sanctions a learner. Final verdict remains **FUNCTIONAL IMPLEMENTATION PASS —
REAL-LEARNER ACTIVATION BLOCKED**, subject to final repository verification and
independent code review recorded in the PR report.
