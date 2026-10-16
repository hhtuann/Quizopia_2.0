# W3-B3 frontend — closure, notifications and Teacher results

Status: **IMPLEMENTED FOR DRAFT REVIEW; PRODUCTION FLAGS REMAIN OFF.**

Branch: `feature/frontend-w3b3-closure`

Base: `e5fbf50fda2389dcd72499ef4979df0217ef8f30` (`origin/develop`)

Final implementation code/test HEAD: `44eb3d0bd1b226e34acf8619f2be4fffad163cda`.
The evidence document is committed immediately after that implementation commit;
the exact pushed branch HEAD is recorded in the final report and Draft PR because
a commit cannot contain its own object ID.

This change is frontend-only. It adds no schema, event, service, public endpoint,
feature flag, read receipt, notification administration, force-close path or
grading policy.

## API and UI inventory

All browser calls use the existing authenticated request executor and Gateway base
`/api/assessments`.

| Actor   | Method | API route                                                                    | UI route                                                      |
| ------- | ------ | ---------------------------------------------------------------------------- | ------------------------------------------------------------- |
| TEACHER | `GET`  | `/publications/{publicationId}`                                              | `/app/assessments/{publicationId}/closure`                    |
| TEACHER | `POST` | `/publications/{publicationId}/close`                                        | Existing Publication detail, linked from closure management   |
| TEACHER | `POST` | `/publications/{publicationId}/closure-requests`                             | `/app/assessments/{publicationId}/closure`                    |
| TEACHER | `GET`  | `/publications/{publicationId}/closure-requests/{closureRequestId}`          | `/app/assessments/{publicationId}/closure/{closureRequestId}` |
| TEACHER | `POST` | `/publications/{publicationId}/closure-requests/{closureRequestId}/cancel`   | Closure detail                                                |
| TEACHER | `POST` | `/publications/{publicationId}/closure-requests/{closureRequestId}/complete` | Closure detail                                                |
| TEACHER | `GET`  | `/publications/{publicationId}/results?limit&cursor`                         | `/app/assessments/{publicationId}/results`                    |
| STUDENT | `GET`  | `/notifications?limit&cursor`                                                | `/app/notifications`                                          |
| STUDENT | `GET`  | `/notifications/{notificationId}`                                            | `/app/notifications/{notificationId}`                         |

Only `limit` and an unmodified, opaque server cursor are sent for list paging.
Teacher commands contain exactly numeric `expectedRevision` and one
`Idempotency-Key`. Unsafe JavaScript revisions fail closed before a request is
sent. No answer or correct-answer field is modeled by the results feature.

## Screens and authoritative transitions

The Teacher Publication detail retains its W3-A `DRAFT`, `OPEN` and direct-CLOSE
behavior and now exposes Closure and Results destinations. Closure management shows
the owner projection, state, integer revision and returned `endAt`, plus
direct-CLOSE compatibility and a separately typed `REQUEST` confirmation. The
browser never compares local time to `endAt`; the request response remains the
only eligibility decision.

Closure detail is keyed by the exact request UUID and displays only returned
Teacher identity, request/finalization/resolution timestamps, request state and
Publication revision. Its state model is:

```text
PENDING -> FINALIZING -> COMPLETED
    |
    +-> CANCELLED
    +-> RESOLVED_EARLY
```

`PENDING` is durable intent, not CLOSED. Cancellation is offered only while the
request is `PENDING` and `finalizationStartedAt` is null. COMPLETE is offered only
for a live `PENDING` or `FINALIZING` request. A 200/202 response and the subsequent
authoritative GET control the displayed outcome. No recipient count, completion
percentage or premature CLOSED state is inferred.

The Student inbox has loading, empty, paging, refresh, inaccessible and dependency
failure states. Detail differentiates `PREPARING`, `NOTICE_CONFIRMED`,
`CUTOFF_REACHED`, `CANCELLED`, `SUBMITTED`, `ABANDONED`, `FINALIZED` and
`RESOLVED_EARLY`; it exposes no read/unread or acknowledgment control. The Teacher
results table displays exact returned `attemptId`, `studentId`, ordinal, state and
nullable result. `ACTIVE` and `ABANDONED` rows are unscored; result decimals remain
strings and are never converted through JavaScript `Number`.

## Closure and 48-hour correctness

A closure request records durable intent and notice preparation only. A Student's
period begins only when that recipient's response includes both
`confirmedNoticeAt` and `closureCutoffAt`; the client validates that they are exactly
48 elapsed hours apart. `PREPARING` must have both values null and starts no timer.
Countdown text is guidance computed from returned `serverNow`, never from browser
wall time, and the returned timestamps remain visible and authoritative.

Recipients whose accessible notice cannot be confirmed may block closure
indefinitely under the accepted backend policy. The UI describes that limitation
without inventing an acknowledgment, global broadcast cutoff, shortened grace
period, automatic abandonment, missing-user workaround or force-close action.

Each deliberate mutation creates one command identity. Its payload and key are
retained while the outcome is uncertain or the server returns retryable capacity
throttling. Conflicts trigger an authoritative read. Direct CLOSE and delayed
closure commands preserve the same key across eligible explicit retries and clear
obsolete retry state after a definitive conflict. COMPLETE `CLOSURE_NOT_READY`
may have committed bounded work and keeps its original key for an explicit retry;
a losing CANCEL race is not replayed. An acknowledged 202 is reconciled before a
later deliberate batch receives a new key.

## Authentication, privacy and stale-data controls

- The canonical LD-05 provider supplies memory-only USER credentials; feature code
  neither persists nor logs tokens.
- Requests are bound to the current session generation, abort on resource/identity
  changes and fence late list/detail/page responses. Pagination resets on such a
  switch.
- `401`, `403`, masked `404`, `409` and `503` have distinct user-facing recovery.
  A dependency `503` does not force refresh or logout.
- Teacher reads and commands rely on persisted backend ownership. No previously
  rendered owner state grants later authority.
- Notification access relies on current USER recipient ownership, not current
  CLASS membership, Publication OPEN state, an active Attempt or a private locator.
  A masked other-recipient response cannot leave stale notice content on screen.
- Response validators reject mismatched result/Attempt identity, non-null results
  for `ACTIVE`/`ABANDONED`, contradictory notice evidence and unsafe numeric
  Publication revisions.

## Verification evidence

Current local frontend evidence at documentation time:

| Check                                          | Result                                    |
| ---------------------------------------------- | ----------------------------------------- |
| Focused W3-B3/W3-A compatibility Vitest suites | PASS: 57 tests (independent review rerun) |
| Full Vitest suite                              | PASS: 580 tests in 41 files               |
| Playwright suite                               | PASS: 63 tests, stable rerun              |
| TypeScript strict typecheck                    | PASS                                      |
| ESLint                                         | PASS                                      |
| Prettier check                                 | PASS                                      |
| Next.js production build                       | PASS                                      |
| Generated Publication contract check           | PASS                                      |
| OpenAPI/controller/Gateway route audit         | PASS: 24 operations                       |

The focused coverage includes ownership/masked denial, server-time request
eligibility, safe revisions, idempotent replay/conflicting payload, uncertain
commit recovery, all five closure states, cancel/complete races, indefinite blocked
copy, confirmed and absent cutoff evidence, recipient isolation after membership
loss, opaque paging, account/session switches, nullable/exact results, answer-key
absence, 503 behavior, mobile layout and keyboard-accessible controls. Final command
results and exact totals supersede this table in the final report and Draft PR.

## Real integration status and historical evidence

No fresh frontend multi-service topology is claimed. This checkout has no safe
current launcher plus synthetic credentials for Identity, Quiz, Classroom,
Assessment and Gateway with the B3 readiness gates. The existing W3-A launcher does
not start the required B3 services or recipient fixtures, so using it would not
exercise closure, durable inbox or Teacher results. Real integration is therefore
an explicit Draft-PR blocker, not relabeled mocked Playwright evidence.

For context only, the accepted backend documents record historical B3 implementation
commit `4a75be8f446493ad3dd5025a3930b42639cafecb` on baseline
`5db5d41008ded12c8abdd27a2307d77f43dfdbd8`, and a historical five-service run of
18 journeys. The integration handoff separately identifies backend runtime revision
`e5c4ef3500f1ce40303af161cd6ca74b706c21b2`, nominated Assessment release candidate
`10615f4a9cb73ceb4bce7728f23bf210d0bc99a2` and frontend PR #77 revision
`efd3f8b1964b4ad6e3de3847fac5c5e33d7d87bb`. None is a fresh execution of this
frontend branch. Its deterministic database-clock advancement verifies the protocol
boundary; it is not a real-time 48-hour soak.

## Review, boundaries and remaining gates

Author review and early read-only UI/contract review covered recipient leakage,
ownership, cutoff construction, mutation recovery, lifecycle wording, decimal
precision, stale cross-account content, cursor handling and unsupported feature
claims. Confirmed findings were repaired: inbox loading now settles; routes use the
correct imports; semantic badge contrast was strengthened; detail refresh and
accessible table scrolling were added; COMPLETE/CANCEL conflict recovery preserves
the correct command identity; contradictory cutoff/result projections fail closed;
misleading duration/cutoff wording was corrected; direct CLOSE 429 recovery now
preserves its original command identity; and malformed/overflowing ordinals fail
closed without throwing. Final independent baseline-to-HEAD review found no
remaining confirmed Blocker, High or Medium findings, excluding the documented
Agent 1 route-composition dependency below.

Cross-agent boundaries remain deliberate:

- Student Attempt authoring and its route belong to Agent 1. Notifications retain
  the real authorized `attemptId`, but the link stays inactive until that route is
  composed and revalidates access.
- Proctoring belongs to Agent 3. Teacher result rows retain the real `attemptId` and
  a feature-level navigation hook, but no monitoring link or roster endpoint is
  activated before its authorized destination exists.
- No Student grading/review screen, answer editor, proctoring ingestion, LiveKit
  client or auth-core rewrite is included here.

Unmet production gates are the fresh real topology run, real outage/recovery and
uncertain-commit exercise, prolonged failure/durability/load and WAN/TLS acceptance,
production-scale capacity, operational readiness, backup/restore and a genuine
48-hour soak. CI, Leader review and merge approval also remain required. All
Assessment and inbox production flags remain disabled; this change does not
authorize activation or merge.
