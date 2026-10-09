# Proctoring

Status: **Baseline v0.1 with approved Wave 3 Activity Evidence and LiveKit scope**

## Wave 3 scope

The [approved W3-A Leader decision](../specifications/w3-a-publication-scheduling-monitoring-policy.md)
supersedes blanket Wave 3 Proctoring deferral. Eligible CLASS Publications may
require a disclosed bundle of Browser Activity Evidence and LiveKit camera
monitoring. W3-M1 and W3-M2 are required Wave 3 outcomes; enabling monitoring is
optional per eligible Publication. No previously accepted A/B enum exists, and
configuration encoding, UI terminology and defaults remain OPEN.

AI behavior monitoring remains accepted future design, deferred beyond Wave 3.
Media/audio recording, mandatory/strict screen sharing, remote device control and
automatic facial identification are outside Wave 3. Preserve future AI extension
points without requiring detectors, AI snapshots, risk scoring or AI alerts for
Wave 3 closure.

## Purpose

Proctoring provides live monitoring and suspicious-event evidence for certain classroom assessments.

It is not a guarantee that cheating is impossible.

## Eligibility

Proctoring may be enabled only when all of the following are true:

- publication audience is one classroom;
- Teacher is authorized to assign to that Classroom;
- learner must be authenticated and a classroom member;
- publication has a bounded availability window;
- attempt has a bounded positive duration.

Therefore:

- any PUBLIC assessment, authenticated or guest -> proctoring not allowed;
- unlimited availability -> proctoring not allowed;
- unlimited attempt duration -> proctoring not allowed;
- practice mode -> proctoring not allowed in baseline.

Runtime monitoring additionally requires an active eligible Attempt. Classroom
Service is authoritative for Teacher/Class membership; a shared URL cannot bypass
membership. Assessment is authoritative for Attempt validity and deadlines.

## Lifecycle

Proctoring starts only when the learner starts the attempt.

It ends when:

- learner submits; or
- server-authoritative attempt deadline is reached.

Camera monitoring must not run merely because the learner is browsing the class, reading instructions, or viewing results.

## Browser-side monitoring signals

The product may record an append-only attempt/proctor timeline including:

- attempt started/submitted/timed out;
- question viewed/left;
- answer selected;
- answer changed;
- answer cleared;
- document/tab hidden/visible;
- window blur/focus;
- fullscreen entered/exited;
- camera started/muted/unmuted/ended;
- network disconnected/reconnected;
- duplicate Quizopia attempt tab detected;
- AI suspicious-event flags.

For Wave 3, implement supported Activity Evidence for monitored eligible Attempts;
AI flags remain future-facing. Answer-change evidence contains metadata without
answer content. Exact schemas, transport, ordering/retry handling, sanitized
Assessment-to-Proctoring integration and retention require contract review.
Evidence must be durable and tamper-resistant within the accepted architecture,
with appropriate timestamps and access controls. Browser signals can be incomplete
or manipulated; they do not prove misconduct and must not automatically
disqualify, zero a score or trigger disciplinary sanctions.

Sensitive answer-change logging must be access-controlled and used for exam evidence/audit, not exposed publicly.

Do not collect browsing history, other-tab URLs, unrelated application activity,
keystrokes or clipboard contents.

## Browser privacy limitations

A normal website can detect that its own page became hidden or lost focus.

A normal website cannot inspect arbitrary unrelated browser tabs, list all tab URLs, or reliably determine which external website the learner switched to.

Do not promise:

- "Quizopia can see every open tab";
- "Quizopia knows which external website the student opened";
- "Quizopia can prevent Alt+Tab/Esc at OS level."

Future strict modes may request screen sharing, but browser/user consent remains required.

## Camera/video

Realtime camera uses WebRTC through LiveKit.

The teacher monitoring dashboard subscribes to student video streams while eligible attempts are active.

Use adaptive stream/simulcast concepts so a grid of many students does not require every stream to be consumed at full resolution.

Proctoring owns room/session/token orchestration. Teachers may view only active
eligible Students in authorized monitored Publications; Students must not
subscribe to other Students' camera streams. Capture requires transparent browser
permission and a visible active-capture indicator. Model denied/lost permission,
device rejection, disconnect/reconnect and ended sessions truthfully. Freeze
short-lived scoped access/revocation and camera-refusal/loss/accommodation outcomes
before dependent implementation or real-learner enablement.

## MVP evidence

Initial proctoring should prioritize:

- live camera;
- event log;
- AI flags/risk indicators;
- optional suspicious-event snapshots.

The original priority list describes the broader initial Proctoring design.
For the approved Wave 3 cut, Activity Evidence and LiveKit camera monitoring are
required; AI flags/risk indicators and automatic AI snapshot extraction are
deferred. This is not a requirement to implement AI for Wave 3.

Full-session video recording is intentionally deferred.

## Recording and storage

Full recording may be added later.

When enabled, recordings may be written to S3-compatible object storage such as MinIO or a managed provider.

A third-party storage provider is not technically required, but managed storage may be operationally easier at scale.

## Retention

Proctoring evidence is retained for a short platform-controlled period, for example 7 or 30 days.

The exact default retention is **TBD**.

Evidence must have an expiry timestamp and a cleanup process.

## AI proctoring

AI may emit signals such as:

- no face;
- multiple faces;
- phone detected;
- looking away;
- other suspicious observations.

AI output must be treated as evidence/risk signals.

It must not automatically:

- declare cheating as fact;
- fail a student;
- permanently sanction an account.

A teacher/reviewer makes the final decision.

## Wave 3 privacy and security release gates

Before enabling live Proctoring for real learners, finalize clear collection and
viewer notice; lawful basis and required authorization/consent; provisions for
minors; accessibility/accommodations; refusal and camera-unavailable behavior;
evidence retention/deletion; access controls and audits; deployment location;
incident and data-subject request procedures; and the no-recording policy.
Privacy/security review must examine applicable Vietnamese and other deployment
jurisdictions' requirements. Browser permission alone does not establish all
required legal authorization.

No hidden activation, unrestricted Teacher room access or indefinite event
retention. Unavailable/denied cameras must not be shown as active. Required
privileged checks fail closed when authoritative verification is unavailable.
Monitoring failures must not corrupt answers/results, change grading or silently
extend deadlines. The exact refusal/loss outcome remains OPEN, not an automatic
failure or an implicitly authorized bypass.
