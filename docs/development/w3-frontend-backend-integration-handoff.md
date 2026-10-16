# Wave 3 frontend/backend integration handoff

Status: **LD-05 real executor expiry/refresh scenario PASS; broader browser LD-05
integration remains open. Assessment API inventory verified. Production NO-GO.**
This handoff identifies backend `e5c4ef3500f1ce40303af161cd6ca74b706c21b2`
and frontend PR #77 HEAD `efd3f8b1964b4ad6e3de3847fac5c5e33d7d87bb`.
It does not appoint reviewers, enable production, close release gates, or certify
frontend Assessment screens or Proctoring features.

The runtime revision is an engineering baseline. It does not replace the nominated
Assessment release candidate `10615f4a9cb73ceb4bce7728f23bf210d0bc99a2` or its
required artifact/configuration approval; the release register's formal
`release_candidate_sha` remains null.

Recorded run: 2026-10-14, started `12:47:41Z`; the owned runtime was stopped at
`13:19:49Z` after successful browser, backend smoke and inventory checks. No live
runtime or future execution is promised by this document.

## Chronological PR #77 evidence

[PR #77](https://github.com/hhtuann/Quizopia_2.0/pull/77) remains Draft. Its recorded
chronology distinguishes an earlier port conflict; a subsequent registration,
OTP and login run that failed because the USER JWT lifetime was 300 seconds; and
later runs that stopped at Gateway `ECONNREFUSED`. Those runs did not establish
expiry, refresh/retry or authoritative recovery. The existing IPv6 harness support
preserves the exact trusted `http://localhost:3000` Origin and avoids replacing
another developer's IPv4 listener.

The new run rebuilt Identity and Gateway from a Git archive of the exact backend
SHA above, archived the exact PR #77 frontend SHA, and used its existing dependency
lock and installed browser. It executed the unchanged approved browser command:

```sh
LD05_REAL_EXECUTOR_HOST=::1 pnpm test:e2e:real:executor
```

Result: **1 test / 1 worker passed in 1.2 minutes**. The issued JWT had an actual
5-second `exp - iat` lifetime. The production request executor observed protected
responses `[401, 200]`, exactly one refresh, a rotated HttpOnly refresh cookie,
the same authenticated user, memory-only token handling and no local/session
storage credentials. It waited past actual expiry plus the existing timestamp
skew; timestamp verification and production defaults were unchanged.

This single-caller test does not establish concurrent single-flight, account-switch
isolation, uncertain refresh rotation, browser Redis outage/stale-restore recovery,
or all no-logout assertions. Those remain separate PR #77 integration gates.
Backend smoke checks below are separate evidence and cannot close those browser gates.

## Reproduce the owned runtime

Use [the local launcher](../../scripts/local/ld05-real-executor-runtime.py):

```sh
python3 scripts/local/ld05-real-executor-runtime.py up --run-browser
```

Prerequisites: Java 21, Docker, the repository Maven wrapper/cache, OpenSSL,
Python `bcrypt`, Node/Corepack, the existing frontend dependencies and installed
Playwright Chromium. Corepack selects the repository's existing `pnpm@10.15.1`;
the launcher provides a private executable wrapper, without changing global tools.
It refuses a different installed dependency lock. It does not install production
dependencies or alter the frontend checkout.

The launcher checks that IPv6 loopback ports `8080`, `8025` and `3000` are free.
It never stops an existing listener. It creates uniquely named, ownership-labelled
PostgreSQL 17, Redis 8.2 and Mailpit 1.27.8 containers. Their other host ports are
random IPv4 loopback ports. Identity also uses a random IPv4 loopback port; Gateway
and Mailpit's browser endpoint bind IPv6 loopback. The exact localhost Origin,
cookie behavior and JWT verification remain in force.

Only the generated isolated Identity environment selects USER TTL `PT5S`.
Production/default USER TTL remains `PT5M`. The runtime enables independent Identity
admission/reconciliation and provisions only the exact `gateway` SERVICE client
with `identity.user-admission`. Local loopback HTTP transport exceptions are confined
to these test processes; they do not satisfy deployed private TLS acceptance.
Publication, Attempt, result, closure and inbox flags remain disabled in this
authentication-only runtime. Assessment, Quiz, Classroom and Proctoring are not
started by this launcher.

The command prints its private `STATE_DIRECTORY` and an exact cleanup command.
JAR SHA-256 values, revision identifiers and owned resource names are recorded in
`state.json`. Credentials, generated signing keys, raw service logs and browser
artifacts stay inside that private temporary directory. Do not publish those files.
After the run, the owned runtime remains available for an explicitly bounded local
integration session; it is not a promised background test or a production service.

```sh
python3 scripts/local/ld05-real-executor-runtime.py smoke STATE_DIRECTORY
python3 scripts/local/ld05-real-executor-runtime.py down STATE_DIRECTORY
```

`smoke` creates synthetic actors and performs **17 actual backend assertions**:
registration, OTP request/confirmation, login, five-second USER TTL, `/me`, exact
SERVICE issuance and primary/Redis equality admission, `no-store`, rejection of USER
impersonation/private Gateway access, fail-closed Redis outage and sanitized error,
restored admission, and trusted/untrusted Origin behavior. The outage stops and
restarts only the recorded ownership-labelled Redis container. The real browser
executor and this smoke both passed in the supplied isolated runtime. Neither test
uses actual Student accounts or production resources.

`down` checks the recorded Java command and container ownership labels before
terminating only those resources. It retains private evidence for the operator to
review and dispose of safely. Repeating `up` while its IPv6 ports remain occupied is
rejected. Do not kill unrelated services to make the command succeed.

## Implemented Assessment contract

The implemented source of truth is
[Assessment OpenAPI 3.1](../../services/assessment-service/contracts/publications.openapi.yaml),
version `1.3.0`, with **24 public operations**. The proposed contract document is
not a replacement. The baseline's three controllers and exact Gateway role
adapters were checked against all 24 operations with:

```sh
PYTHONPATH=gateway/target/w3a-evidence/openapi-python \
  python3 scripts/local/audit-assessment-frontend-contract.py
```

[The audit](../../scripts/local/audit-assessment-frontend-contract.py) reads the
pinned Git objects, normalizes only path-parameter names for comparison, compiles
the real Gateway inventory classes and verifies every documented operation has an
exact role adapter. It passed: 24 OpenAPI = 24 controller = 24 Gateway operations;
private admission has no public adapter. This is static route evidence, not an
execution of 24 Assessment browser journeys.

Every route below is under `/api/assessments`; identifiers are canonical lowercase
UUIDs. All browser business traffic goes through Gateway. Even PUBLIC entry requires an authenticated STUDENT.

| Role    | Method | Relative route                                                               | Request / response schemas             |
| ------- | ------ | ---------------------------------------------------------------------------- | -------------------------------------- |
| TEACHER | POST   | `/publications`                                                              | `Configuration` / `OwnerView`          |
| TEACHER | GET    | `/publications/{id}`                                                         | none / `OwnerView`                     |
| TEACHER | PUT    | `/publications/{id}`                                                         | `Update` / `OwnerView`                 |
| TEACHER | POST   | `/publications/{id}/open`                                                    | `Open` / `OwnerView`                   |
| TEACHER | PATCH  | `/publications/{id}/schedule`                                                | `Schedule` / `OwnerView`               |
| STUDENT | POST   | `/publications/entry`                                                        | `Entry` / `StudentView`                |
| STUDENT | POST   | `/publications/{id}/entry`                                                   | none / `StudentView`                   |
| STUDENT | POST   | `/publications/{publicationId}/attempts/start`                               | `StartRequest` / `AttemptView`         |
| STUDENT | GET    | `/attempts/{attemptId}`                                                      | none / `AttemptView`                   |
| STUDENT | POST   | `/publications/{publicationId}/attempts/retake`                              | `StartRequest` / `AttemptView`         |
| STUDENT | POST   | `/attempts/{attemptId}/questions/{questionNumber}/answer`                    | `SaveRequest` / `SaveAck`              |
| STUDENT | POST   | `/attempts/{attemptId}/submit`                                               | `SubmitRequest` / `Result`             |
| STUDENT | POST   | `/attempts/{attemptId}/abandon`                                              | `AbandonRequest` / `AbandonAck`        |
| STUDENT | GET    | `/attempts/{attemptId}/result`                                               | none / `Result`                        |
| STUDENT | GET    | `/publications/{publicationId}/aggregate-score`                              | none / `Aggregate`                     |
| STUDENT | GET    | `/attempts/{attemptId}/review`                                               | none / `Review`                        |
| TEACHER | GET    | `/publications/{publicationId}/results`                                      | `limit,cursor` / `TeacherResults`      |
| TEACHER | POST   | `/publications/{publicationId}/close`                                        | `TeacherCommand` / `ClosedPublication` |
| TEACHER | POST   | `/publications/{publicationId}/closure-requests`                             | `TeacherCommand` / `ClosureView`       |
| TEACHER | GET    | `/publications/{publicationId}/closure-requests/{closureRequestId}`          | none / `ClosureView`                   |
| TEACHER | POST   | `/publications/{publicationId}/closure-requests/{closureRequestId}/cancel`   | `TeacherCommand` / `ClosureView`       |
| TEACHER | POST   | `/publications/{publicationId}/closure-requests/{closureRequestId}/complete` | `TeacherCommand` / `ClosureCompletion` |
| STUDENT | GET    | `/notifications`                                                             | `limit,cursor` / `NotificationPage`    |
| STUDENT | GET    | `/notifications/{notificationId}`                                            | none / `Notification`                  |

Derive DTO types from the actual OpenAPI; do not invent a second schema model.

## Frontend request and recovery rules

- Send an in-memory RS256 USER bearer; Gateway and each downstream service verify
  it independently. Current Identity admission and CLASS membership/Teacher
  assignment checks remain mandatory. Possession of a UUID, locator or valid JWT
  alone does not grant access. Private SERVICE endpoints are never browser APIs.
- JSON mutations use `Content-Type: application/json`; B2/B3 reject duplicate
  fields, unknown fields, trailing JSON, unsupported encodings and unexpected
  query parameters. Do not send a request body with GET. Only the documented
  Teacher results/inbox lists accept `limit` (`1..100`, default `20`) and opaque
  `cursor` query parameters.
- Send an `Idempotency-Key` for Publication creation and the B2/B3 commands that
  require it. B2/B3 require exactly one key matching `[A-Za-z0-9_-]{16,128}`.
  Retain the same key and identical payload after an uncertain commit; reconcile
  authoritative REST state. A user action with different intent gets a new key.
  Do not indiscriminately replay mutations after refresh or network uncertainty.
- B2 Attempt `expectedRevision` and large ordinals are canonical decimal **strings**;
  W3-A Publication and Teacher closure commands retain JSON integer revisions.
  Preserve exact representations and prevent JavaScript precision loss. SAVE is
  question-scoped and rejects stale revisions; a conflict requires reconciliation.
- SUBMIT persists complete answers, grading and result identity transactionally;
  it is retry-safe. The initial body budget is 2 MiB, not a permanent Quiz product
  restriction. Never truncate answers. Validate snapshot/complete-answer sizing;
  preserve the 12,613-question compatibility fixture and the existing W3-A handoff
  limitations described in the implementation evidence.
- Server time/deadlines are authoritative. Publication `DRAFT/OPEN/CLOSED`, Attempt
  terminal state and returned projections control UI. `endAt` alone never closes
  a Publication or releases correct answers. Results expose only authorized totals;
  owner review reveals correctness/explanations only after durable CLOSED.
- Consume fixed `{code}` errors and documented HTTP statuses. Distinguish invalid
  USER evidence (`401`), denied roles (`403`), inaccessible resources (`404`),
  revision/idempotency/lifecycle conflicts (`409`) and unavailable authority (`503`).
  Do not refresh/logout solely on `503`; existing `/me` and refresh contracts retain
  their own documented codes. Respect `Cache-Control: no-store` and render no
  answer content, bearer, private locator or sensitive error details in logs.
- Inbox access belongs to the authenticated recipient independently of current
  CLASS membership, OPEN/ACTIVE state or locator. Missing confirmed accessible
  notice means no cutoff. Each real confirmation establishes its own unchanged
  48-hour cutoff; the accepted indefinite blocked-closure limitation remains.
  No browser read receipt, fabricated timestamp, automatic abandonment or force-close
  replaces this policy.

## Full Assessment integration and remaining work

The authentication-only launcher intentionally does not claim full Assessment
readiness. A complete isolated topology additionally needs Quiz, Classroom,
Assessment and separate owned databases; actual synthetic Teacher/Student
membership and immutable snapshots; least-privilege SERVICE clients per caller;
Identity/Redis reconciliation; and reviewed loopback-only configuration.

The existing
[Publication topology suite](../../gateway/src/test/java/com/quizopia/gateway/PublicationTopologyTest.java)
is the real multi-service reference for this setup. Use its exact implementation
and [B3 implementation evidence](../architecture/w3b3-closure-notification-implementation.md)
for credentials, notice readiness and fixture provisioning rather than exposing
new public internal routes. Its historical results are not relabelled as a fresh
run in this handoff.

For an authorized isolated Assessment session, independently configure both
Gateway and downstream `PUBLICATION_ENABLED`, `USER_ADMISSION_ENABLED`,
`ATTEMPTS_ENABLED`, `RESULTS_ENABLED`, `CLOSURE_ENABLED` and
`ASSESSMENT_INBOX_ENABLED` as needed. SUBMIT requires real attempts and results;
closure additionally needs downstream worker/recipient/edge-inbox readiness,
the exact Assessment Identity notice scope, and private trust/transport settings.
Refer to [the deployment dependency order](w3-final-deployment-runbook.md).
No production flag is changed by this handoff or either local script.

Dev 2 can now reproduce the exact PR #77 expiry/refresh executor. Retain Draft
until its broader real-browser LD-05 denial/stale-restore/uncertain-rotation and
session-isolation gates receive actual evidence and independent review. Assessment
frontend journeys should cover complete mutations, conflicts, uncertain retries,
result/review redaction and per-recipient inbox/closure behavior against the actual
multi-service backend. W3-M1/M2 endpoints and camera-grid browser acceptance remain
separate workstreams. All eleven production release gates remain open.

## Dependent M2 integration boundary

The [M2 media contract](../specifications/w3-m2-media-contract.md) and
[media OpenAPI](../../services/proctoring-service/contracts/media.openapi.yaml)
add four bodyless, query-free exact grant adapters on a separate dependent branch.
They retain independent Gateway/downstream current admission and actor/resource
checks. Own grants return a scoped bearer only under explicitly configured synthetic
qualification; default UNVERIFIED mode denies issuance/renewal. No production or
qualified Cloud-provider acceptance is supplied. Hold any returned token in browser
memory only, with no logs/storage/query/analytics exposure.

`GRANT_ISSUED` is technical credential state, not camera permission/capture proof.
Student grants allow camera-only publishing and no peer subscription; Teacher grants
subscribe only to authorized per-session rooms. App `leaseUntil` is not a provider-
enforced hard deadline during outage, and self-hosted removal does not revoke old
tokens. M2 privacy/refusal policy, provider refresh/revocation/clock acceptance and
real browser camera-grid E2E remain separate blockers. These backend mechanics do
not mark frontend PR #77 accepted or alter its existing browser-executor evidence.

The correction campaign requires explicit private `LIVE_MEDIA` authority for grant
create/renew, including current target Student Identity eligibility and Classroom
membership for Teacher access, checked again after provider I/O. Historical evidence,
status and own revoke retain current caller/ownership authorization and access audits
without requiring the target's live eligibility; `HISTORY` cannot authorize a grant.
LiveKit administrative SDK bearers now expire after 30 seconds and carry only the
required provider-supported privileges. Their remaining grant granularity and TTL
are not Cloud revocation or hard-outage acceptance.

## Current monitoring integration additions

The [integration checkpoint](../architecture/w3m-integration-implementation.md)
records the new dependent branch and actual verification limits. Current authorized
Teachers can call bodyless/query-free
`GET /api/proctoring/monitoring/sessions/by-attempt/{attemptId}` with fresh admission
and owning Publication/current Classroom authority before local existence is
revealed. Use Attempt IDs from existing Teacher results; return Session or masked
404, never create a session or enumerate unrelated resources. Then use existing
Teacher timeline and own media-grant APIs. Independent frontend grid acceptance
and actual provider enforcement remain pending.

The [producer-owned answer fact contract](../specifications/w3m-authoritative-answer-integration.md)
adds server-only `ANSWER_PERSISTED` / `ASSESSMENT_AUTHORITY` timeline history with
nullable decimal-string `assessmentRevision` (non-null for those facts only).
Browser event POST must never send this type, provenance or revision. Event numeric
fields require integral JSON tokens within their declared bounds, without fraction,
decimal or exponent forms. `observedAt` is a valid RFC3339 string or permitted null,
never a numeric epoch timestamp. These representation corrections add no public route
or DTO field. A fact records
Assessment persistence, including server normalization/full SUBMIT, not proof a
Student clicked or saved before deadline; historical facts can attach after END
without extending runtime/media. No answer content/digest or credential is copied.
Render browser versus authoritative provenance faithfully and keep caches/logs
free of protected data. Existing public Assessment answer/result schemas stay fixed.

Seven original owned real five-service journeys passed at the historical #91
`d9d68f8b384352a6e580afa4394bec5babad115f` baseline, including the authoritative
Rabbit answer-fact journey, current authority/lifecycle, private signature/substitution
denial and pause/Redis fail-closed checks. See the checkpoint's historical scope.
The expanded correction topology now passed **10 journeys, zero failures/errors/skips,
98.027 seconds**, using actual Gateway/Identity/Classroom/Assessment/Proctoring,
four owned PostgreSQL 17 databases, Redis 8.2, RabbitMQ 4.1 and owned LiveKit v1.9.0,
with Identity-issued JWTs. The three additions cover live create/renew after target
membership loss; account disable/stale Redis/original Attempt-start revocation fencing;
and Classroom database pause. That pause also affects Teacher assignment; target-only
outage and terminal/deadline no-live cases are direct module evidence with explicit
doubles, not new real-topology assertions. Cumulative Assessment **388** and Proctoring
**229** full-suite tests passed; full Gateway verification passed **119 tests**, with
zero failures/errors/skips. See
[the correction evidence](../architecture/w3m-correction-evidence.md) for commands and limits.
Original PASS counts do not certify corrected heads or latest CI. The synthetic
`CLOUD_STRICT` fixture and loopback checks do not establish private TLS, Cloud
revocation, real-browser camera/grid,
monitoring privacy approval or PR #77 independent acceptance. The actual LD-05 browser
PASS remains the single expiry/refresh scenario above; broader browser gates stay open.
No frontend PR #77 changes or human Security approval are supplied by this handoff.
All eleven release gates remain **6 BLOCKED / 5 NOT VERIFIED**; the nominated Assessment
SHA is unchanged and formal `release_candidate_sha` remains null.
