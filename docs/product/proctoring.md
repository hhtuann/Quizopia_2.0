# Proctoring

Status: **Baseline v0.1**

## Purpose

Proctoring provides live monitoring and suspicious-event evidence for certain classroom assessments.

It is not a guarantee that cheating is impossible.

## Eligibility

Proctoring may be enabled only when all of the following are true:

- publication audience is one classroom;
- learner must be authenticated and a classroom member;
- publication has a bounded availability window;
- attempt has a bounded positive duration.

Therefore:

- public guest assessment -> proctoring not allowed;
- unlimited availability -> proctoring not allowed;
- unlimited attempt duration -> proctoring not allowed;
- practice mode -> proctoring not allowed in baseline.

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

Sensitive answer-change logging must be access-controlled and used for exam evidence/audit, not exposed publicly.

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

## MVP evidence

Initial proctoring should prioritize:

- live camera;
- event log;
- AI flags/risk indicators;
- optional suspicious-event snapshots.

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
