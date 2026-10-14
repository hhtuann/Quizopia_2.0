# W3-B3 — Teacher closure, durable inbox and Identity integration

Status: **IMPLEMENTED, DEFAULT DISABLED, PENDING INDEPENDENT SECURITY/RUNTIME REVIEW AND LEADER MERGE APPROVAL. Production activation is NOT AUTHORIZED.**

Historical implementation evidence: PR #83 is now merged at
`4a75be8f446493ad3dd5025a3930b42639cafecb`. The status/review observations below
record its submission; current [W3-FINAL acceptance](w3-final-assessment-acceptance.md)
tracks merged-baseline verification and external release gates.

Baseline: merged PR #82, develop `5db5d41008ded12c8abdd27a2307d77f43dfdbd8`.
[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md) remains authoritative.
EC79-01..04 engineering conditions remain in force. B3 adds runtime adapters to
those accepted contracts; it introduces no product policy, public administration,
generic notification service or automatic Publication closure.

## Ownership and implemented inventory

Assessment owns closure requests, captured membership, immutable notices,
confirmation/cutoff evidence, operation receipts, worker audit, saved answers and
results. Identity owns account/role/revocation facts and the complete primary/Redis
comparison. Classroom continues to own current membership. Production code never
reads another service's database.

The [Assessment OpenAPI](../../services/assessment-service/contracts/publications.openapi.yaml)
retains seven W3-A and nine B2 operations, adding exactly eight B3 operations.
Let P be `/api/assessments/publications/{publicationId}`, C be
`P/closure-requests/{closureRequestId}` and N be `/api/assessments/notifications`.

| Operation               | Role    | Default-off gate                                     | Response                   |
| ----------------------- | ------- | ---------------------------------------------------- | -------------------------- |
| GET P/results           | TEACHER | RESULTS_ENABLED                                      | TeacherResults keyset page |
| POST P/close            | TEACHER | CLOSURE_ENABLED                                      | ClosedPublication          |
| POST P/closure-requests | TEACHER | CLOSURE_ENABLED plus real inbox/dependency readiness | ClosureView, 201           |
| GET C                   | TEACHER | CLOSURE_ENABLED                                      | ClosureView                |
| POST C/cancel           | TEACHER | CLOSURE_ENABLED                                      | ClosureView                |
| POST C/complete         | TEACHER | CLOSURE_ENABLED                                      | ClosureCompletion, 200/202 |
| GET N                   | STUDENT | ASSESSMENT_INBOX_ENABLED                             | NotificationPage           |
| GET N/{notificationId}  | STUDENT | ASSESSMENT_INBOX_ENABLED                             | Notification               |

Gateway and Assessment independently verify RS256 USER JWT, issued role and fresh
LD-05 admission. Teacher commands/results bind the persisted Publication owner.
Inbox/detail queries bind the persisted recipient and have no CLASS membership,
private locator, OPEN, ACTIVE, browser acknowledgment or read-receipt predicate.
Attempt operations retain current CLASS authority. All responses/errors are
no-store; errors contain only fixed `code`. Unknown neighboring routes remain denied.

POST commands require one Idempotency-Key and exact integer `expectedRevision`.
B2 Attempt revisions remain decimal strings. JSON, query, encoding and 2 MiB input
checks precede mutation. The capacity limit remains an initial engineering budget,
not a permanent product restriction. Teacher results order by Attempt UUID ascending;
inbox orders by publishedAt/notification UUID descending. Both use limit 20 by
default, range 1..100, no total, and actor/route/resource-bound HMAC cursors.
Keys are runtime-supplied Base64 material of at least 32 bytes. Deploy all accepted
key versions before changing the active version; retain old versions during the
rotation window. Retiring a key rejects its old cursors with sanitized 400; clients
restart pagination. There is no production signing-secret default.

## Additive V5 and database enforcement

[V5](../../services/assessment-service/src/main/resources/db/migration/V5__teacher_closure_and_notifications.sql)
adds closure_requests, closure_request_members, assessment_notifications and
publication_operations, plus append-only restricted closure_work_audit. It alters
the existing finalization audit and closure prohibitions; it does not recreate
V4's audit table. V1–V4.1 remain unchanged. V4.1's generated type lookup and bounded
child/full-parent consistency validation remain enabled.

Real local FKs bind Publication/request/Attempt/Student/notification/result
identities. CHECKs reject NULL escapes, invalid states, nonfinite evidence and
anything other than confirmed timestamp plus exactly 172800 elapsed seconds.
Capture stores a server-derived count and ordered-ID fingerprint in the request's
creating transaction. Deferred verification rejects omitted, extra or later-added
members, including terminalization within the capture transaction. REQUEST uses
one authoritative INSERT SELECT; any timeout/capacity/constraint failure rolls
back the request, every member, revision and receipt.

Guards protect immutable notification content, notice evidence, request identity,
one live workflow, cancellation fence, operation identity/budget/acknowledgment,
all-terminal CLOSED and terminal/result/audit consistency. CLOSED additionally
requires the owning Teacher command's acknowledgment in the same transaction.
Resolved completion/early closure timestamps match the durable CLOSED transition.
Late Student raw writes cannot backdate answer/terminal timestamps past a cutoff.
Set-order normalization of a previously saved multiple-choice answer preserves its
meaning and saved timestamp and still requires full terminal consistency.

`assessment_time()` is a volatile PostgreSQL wrapper over clock_timestamp(), with
no production time-offset setting. Test schemas replace that function to exercise
48 elapsed hours without changing persisted notice evidence. This capability must
belong to the migration owner, never the restricted runtime database role.

## Transactions, commands and finalization

Every mutation acquires Publication, live request, Attempt, member and notification
locks in that order where applicable. Existing child parent-lock functions include
the workflow tier. Root transaction boundaries reject an ambient application
transaction. Network calls execute outside local transactions. PostgreSQL samples
authoritative time after locks; normal statement/lock budgets are 3000 ms and root
transaction budget is 15 seconds, preserving the authorized V4.1 correction.

REQUEST requires OPEN, current owner/revision and serverNow >= endAt. It captures
all ACTIVE unlimited Attempts atomically and increments Publication revision.
Before capture, a bounded eligibility capability probe for an actual recipient
runs outside locks. Trusted false still permits full capture; unavailable capability
returns 503. Probe evidence is never reused for confirmation. Zero-member workflows
need no recipient probe. Pending closure blocks new START/RETAKE and schedule extension. START may still
select existing work. Zero captured members is valid. REQUEST performs no grading.

PUBLISH commits the complete version-1 warning and NOTICE_PUBLISHED member state
in a short transaction. Confirmation/cutoff remain null. After that commit, the
worker verifies actual local inbox handlers, configured/verified edge readiness,
and a bounded private Gateway technical readiness response,
and the exact owner-only projection used by the HTTP API. It obtains fresh narrow
Identity eligibility outside locks, then re-enters a transaction, rechecks durable
state/ownership/content and samples PostgreSQL time. Both Identity checkedAt age
and a monotonic deadline measured from the outbound call start must stay within
five seconds, including OAuth acquisition and lock wait. Same-transaction publish
and confirmation are rejected using server transaction identities.

`ASSESSMENT_EDGE_INBOX_VERIFIED` is a deployment attestation after real authenticated
topology verification. It is not sufficient alone: actual enabled handlers,
recipient capability, owner projection and fresh coherent Identity evidence are
also required. Gateway readiness reflects the actual inbox switch, admission and
routing; disabled/unreachable/malformed readiness fails closed. Its existing
Actuator health component conveys no USER/recipient assertion. Health, a table,
an email job or a flag alone cannot confirm notice.
Transient authority failures remain fail-closed under LD-05's accepted in-flight
snapshot limitation; there is no invented continuous-uptime/reset policy.

CANCEL commits only before the first closure-forced finalization. It preserves
notices/cutoffs as nonactionable history and resolves members. Replacement creates
new request/notice identities and fresh 48-hour cutoffs. Ordinary Student SUBMIT,
ABANDON and bounded timeout resolve members without starting the cancellation fence.

The shared C1 finalizer grades saved answers only at a confirmed live unlimited
cutoff. It atomically commits submitted document/digest, exact v1 total/question
weights, immutable result, SYSTEM audit with full notice/Teacher evidence, terminal
Attempt, FINALIZED member and first FINALIZING fence. It creates no Student SUBMIT
receipt. A rollback removes all of these, including the fence. No worker closes
the Publication. Stale workers bind the exact request again under locks.

SAVE/SUBMIT/ABANDON validate input and matching committed receipt before new cutoff
work. Eligible late requests reconcile in an independent committed C1 transaction
then return CLOSURE_CUTOFF_REACHED. A fixed V5 late-write rollback marker handles a
cutoff crossed during SQL mutation: the Student transaction rolls back before
system reconciliation. Other database failures are sanitized and are never treated
as a successful or certainly rolled-back commit. Matching historical Student
acknowledgments remain replayable after fresh authorization.

COMPLETE reserves IN_PROGRESS operation identity/digest/revision and a 1..50 unit
budget. Each committed finalization increments processed_units in its own root
transaction. Restarts/concurrent retries share the same total per-key budget.
Acknowledged 202 is immutable and performs no further work on replay; the Teacher
uses a new key to continue. Unconfirmed/future-cutoff work gives 409 NOT_READY,
retaining committed units/reservation. Excessive contention fails 503 for safe
same-key recovery. Fresh Teacher admission runs outside locks immediately before
final CLOSED. CLOSED requires all Attempts terminal and all workflow members resolved.

Ordinary CLOSE reconciles bounded timeouts only. Remaining ACTIVE work yields
code-only ACTIVE_ATTEMPTS without counts/identities. All-terminal CLOSE can resolve
a workflow early and commit CLOSED/revision/closedAt/receipt together. Workers,
reads, endAt and cutoff never independently produce CLOSED.

## Workers, accepted blocked closure and operations

Workers default OFF. Indexed bounded candidate scans and at most the configured
1..50 work units per tick use durable state, Publication-first SKIP LOCKED and
state rechecks. Recovery covers publish, confirm, cutoff finalization and overdue
bounded work. Failed/ineligible notice work has a durable retry count, append-only
sanitized outcome audit and a 60-second engineering backoff, allowing other work
to progress. Worker timing is not a correctness dependency: B2 and Teacher commands
continue to enforce genuine cutoffs with workers disabled.

Aggregate Micrometer gauges expose unconfirmed count, oldest unresolved age and
eligible cutoff backlog. Bounded outcome counters expose publication/confirmation,
ineligibility, authority/inbox failure, retries, finalization and audit/scan failure.
No metric label contains Student/Attempt/Publication identity, locator, answer or
credential. Keep metrics/alerts and restricted audit access controlled. Alert on
persistent readiness/authority failures, increasing retry failures and old unresolved
notices; thresholds are operational choices, not a remediation bypass.

EC79-02 explicitly accepts indefinite blocking for ineligible, disabled or
unverifiable recipients. No confirmed accessible notice means no cutoff or forced
submission. There is no timestamp fabrication, auto-abandon or administrator
force-close. Future remediation requires a new Leader product/security decision.

## Identity capability

The [private Identity contract](../../services/identity-service/contracts/user-admission.openapi.yaml)
adds POST /internal/security/notice-recipients/{studentId}/eligibility. Independent
RS256/issuer/time/principal verification requires SERVICE, exact Assessment subject
and identity.notice-recipient.read. USER bearers/evidence, cookies, body/query and
identity forwarding headers are rejected. The minimal response contains only
studentId, eligible and checkedAt. Complete PostgreSQL-primary/Redis equality
includes account existence/status/verification, full roles and revocation facts;
matching ineligible/missing authority returns false, disagreement/unavailability
returns 503. No positive cache, JWT fallback or account/profile introspection exists.
The private client has dedicated bounded transport/OAuth scope, no redirects or
retries, 4096-byte response limit and at most five seconds total. Identity bounds
concurrent eligibility work to eight by default (valid range 1..64). Identity's
shared JDBC pool additionally defaults to three-second acquisition/socket/login
bounds and one-second validation/cancellation transport; primary SQL keeps its
existing query budget. This also bounds OAuth metadata I/O during outages. Validate
other Identity transactions and migration duration against these transport defaults.
No LD-05 authority fact, revocation rule or positive-cache policy changes.

## Verification evidence

Java 21, Maven wrappers, Docker, PostgreSQL 17 Testcontainers and Redis 8.2 were
available. Detailed local logs/reports live in ignored target directories; the
committed tests are the reproducible evidence. Final executed counts and commands
are recorded below after the complete verification run.

The new real PostgreSQL suite covers atomic capture/rollback, committed publish
before confirmation, missing/stale/false eligibility, exact cutoff boundary,
immutable evidence, owner paging/cursor tampering, cancellation/replacement/fence,
Student races, COMPLETE replay/recovery, raw backdating attacks and real CLOSED
review. Independent connections and barriers/observed lock waits exercise races.
Existing populated V4 and V4.1 upgrade fixtures preserve snapshots, results,
receipts and historical checksums through V5. Existing B2/C1 and capacity suites
remain included without exclusions.

The real five-service harness uses issued USER/Client Credentials tokens, separate
service databases and Redis. It exercises ordinary CLOSE, Teacher results, real
recipient eligibility, two-stage notices, cancellation/replacement, saved-answer
cutoff grading and CLOSED review. A former CLASS member can still read their own
notice/history while Attempt routes deny current membership. Every B3 route is
checked independently at Gateway and Assessment for roles, ownership, forged
headers, malformed/oversized input and authority/revocation/outage failures.
For its 48-hour progression only, the isolated fixture uses a 96-hour issued token
TTL and advances Assessment's database clock after genuine confirmation; production
retains its short-lived token configuration. This is not a real-time 48-hour soak,
WAN/TLS test or production activation.

Representative measurements are local engineering observations, not benchmarks:
51-member atomic capture and a restarted 50-unit COMPLETE, plus retained 1024-question
and near-2-MiB full-answer tests. No production-scale throughput or failover result
is claimed. REQUEST never splits a capture, and valid answers are never truncated.

## Migration rollout and remaining release gates

Drain Assessment writers and workers before V5; coordinate Gateway/Assessment and
Identity provisioning before enabling any B3 capability. V5 takes ACCESS EXCLUSIVE
locks on existing Assessment tables, creates indexes and validates constraints;
measure production-size lock time, WAL/disk growth and backup/restore before rollout.
Local small-fixture migration times do not establish production upgrade duration.
Do not rewrite historical Flyway files or down-migrate durable notice/result history.
Use reviewed roll-forward recovery and preserve acknowledged writes. Old binaries
must not resume authoritative writing against live B3 cutoffs.

Required external gates remain: independent security/runtime review, Leader merge
approval, private HTTPS/ingress and exact SERVICE scope provisioning, authenticated
edge readiness (`ASSESSMENT_EDGE_BASE_URL` pins the private Gateway origin), cursor key rotation, restricted database/application/metrics/audit
roles, production-size capacity, WAN/TLS/load, multi-node deployment recovery,
uncertain commit, database failover and acknowledged-write durability, clock behavior,
backup/restore, retention/erasure and operational alert/runbook acceptance. Existing
W3-A 2 MiB source-handoff limits remain until separately resolved. This PR activates
nothing in production and does not provide merge/release authorization.

## Dev 2 handoff

Derive DTOs from implemented OpenAPI. Display serverNow with nullable confirmedNoticeAt
and closureCutoffAt; start no countdown for PREPARING. Notification status, cancellation
history and resultId come from the owner-only API. AttemptClosure retains exactly its
three frozen fields: request ID and nullable confirmation/cutoff, without a new status
field. Preserve CLASS denial on Attempt screens while leaving the inbox reachable.

Teacher commands send current integer Publication revision and a fresh operation key.
After COMPLETE 202, inspect ClosureView and use a new key for another batch. A 409
NOT_READY can follow committed partial progress; refresh authoritative state instead
of assuming rollback of earlier units. 503/unknown HTTP outcome requires same-key retry
after authority recovers. Show totals after grading, detailed owner review only after
CLOSED, and ABANDON as ungraded. Do not imply deployed availability from this branch.

## Author self-review and independent review boundary

Author review is not independent verification or Leader approval. Review corrections
include V5 action/state widths, exact policy ID, stable receipt projections, stale
request binding, monotonic eligibility lifetime, bounded/fair durable worker recovery,
CLOSED timestamp consistency and post-lock cutoff backdating guards. The independent
reviewer should inspect the complete baseline-to-final diff, V5/V4.1 interaction,
notice accessibility proof, every authority boundary, cancellation/budget races,
release gates and the executed evidence below. No new Leader product decision is
needed for the implemented scope; EC79-02 remediation remains outside it.

## Executed final verification — 2026-10-14

| Check                                                                    | Actual final result                         |
| ------------------------------------------------------------------------ | ------------------------------------------- |
| Assessment Maven verify                                                  | PASS: 331 tests, zero failures/errors/skips |
| Closure PostgreSQL suite                                                 | PASS: 38 cases, included above              |
| C1 atomic finalization/upgrade suite                                     | PASS: 28 cases, included above              |
| B2 Student PostgreSQL regression                                         | PASS: 37 cases, included above              |
| Identity Maven verify                                                    | PASS: 427 tests, zero failures/errors/skips |
| Identity admission/recipient integration                                 | PASS: 27 cases, included above              |
| Gateway Maven verify                                                     | PASS: 100 tests, zero failures/errors/skips |
| Actual five-service Publication/B2/B3 topology                           | PASS: 18 journeys, included above           |
| Existing real Identity admission topology                                | PASS: 6 journeys, included in Gateway       |
| Spotless and ArchUnit                                                    | PASS in all three affected modules          |
| OpenAPI structural/local-reference audit                                 | PASS for all three contract artifacts       |
| Frozen DTO and retained W3-A/B2 operation/schema parity                  | PASS                                        |
| Answer-schema fixtures                                                   | PASS: 11 positive/negative fixtures         |
| ADR-015 and Assessment V1–V4.1 byte preservation                         | PASS                                        |
| Changed-scope links, route inventory, scope and credential-pattern audit | PASS                                        |

Executed wrapper commands, with the existing local Maven cache and Java 21:

```sh
export MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven
export MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2'
bash services/identity-service/mvnw -B -f services/identity-service/pom.xml \
  -Dquizopia.build.directory=target/w3b3-verification spotless:apply spotless:check verify \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
bash gateway/mvnw -B -f gateway/pom.xml \
  -Dquizopia.build.directory=target/w3b3-verification spotless:apply spotless:check verify \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2' -DreuseForks=false -DforkCount=1
bash services/assessment-service/mvnw -B -f services/assessment-service/pom.xml \
  -Dquizopia.build.directory=target/w3b3-verification spotless:apply spotless:check verify \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
PYTHONPATH=gateway/target/w3a-evidence/openapi-python \
  python3 services/assessment-service/contracts/validate-w3b3.py
```

The [read-only contract audit](../../services/assessment-service/contracts/validate-w3b3.py)
uses the already available PyYAML, jsonschema and openapi-spec-validator tooling;
no production dependency was installed. Gateway fork isolation limits local test
context accumulation and retains every suite. Quiz and Classroom source are
unchanged and rebuilt/exercised in the actual topology rather than reported as
separately executed full-module verify runs.

Final representative observations: 51-member capture **52.508 ms**; remaining
49 finalizations after reconstructing the service **3207.367 ms**, completing the
same key's 50-unit budget. Retained 1024-question snapshot/complete-answer sizes
were **196756/72622 bytes**, with **342 ms** end-to-end fixture work. The 12613-question
fixture used **2097086/909644 bytes**, **3165 ms** total fixture work and an actual
**909679-byte** complete HTTP request. Individual transaction/statement/socket
budgets remained unchanged; these whole-fixture durations are not individual SQL
latency or production throughput claims.

Earlier parallel runs exhausted local memory/swap and failed timeout-sensitive
regression/capacity checks. A long serial paused-primary probe also exceeded the
harness HTTP timeout. Final heavyweight runs were sequential; outage probes use
bounded concurrency four while retaining every operation/boundary and the same
HTTP/authority timeouts. Explicit Identity JDBC bounds were added as described
above. These failed observations are not hidden or treated as passes; final runs
passed without excluding suites or increasing production budgets. Prolonged-outage,
failover and production-load acceptance remain external release gates.

The first [Backend CI run](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38447157054)
passed seven jobs but failed Assessment's unchanged 12613-question fixture at
15924 ms. Static parent-lock and answer-validation plans reduced the local fixture
to 4507 ms, but the [second CI run](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38451200542)
still failed Assessment with SQLSTATE 57014 (statement cancellation) at 9735 ms;
the other seven jobs passed again. V5 now also prepares schema-qualified static
plans for the deferred child/full-parent consistency validator. Every V4.1 child
and full-parent check, parent-write requirement, lock, type, identity, digest,
coverage, total and deadline validation remains active. SQL plans cache no authority
facts or answers, and no deferred events are skipped. The final full local reruns
passed all 331 Assessment and 100 Gateway tests; the unchanged large fixture took
3165 ms. Production transaction, statement and socket limits are unchanged.

The cutoff crossing fixture holds a stable volatile clock function and observes
real PostgreSQL time pass the cutoff behind its lock barrier, rather than replacing
a function during an executing statement. Sanitized capacity diagnostics print a
fixed SQL phase label, SQLSTATE and vendor code only, without SQL text or payloads.
No question/payload reduction or suite exclusion was used. Independent review must
compare the V5 static validator with unchanged V4.1; local passes do not resolve the
GitHub CI gate until the new HEAD's run completes successfully.

GitHub Actions is checked after the normal push/Draft PR creation. Its actual
status belongs in the PR/final report; local results do not imply CI success.

## Focused author-review disposition

| Severity | Finding and correction                                                                                                                                                               | Evidence                                                                     |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------- |
| HIGH     | CI exposed a near-budget full-answer regression; V5 guard and deferred consistency SQL now use static schema-qualified plans without relaxing validation or budgets.                 | Unchanged near-2-MiB capacity fixture and complete Assessment/Gateway reruns |
| HIGH     | Raw Student writes could backdate past a confirmed cutoff; added post-lock V5 guards and rollback-marker reconciliation.                                                             | Raw-DML, crossing-save and SAVE/SUBMIT/ABANDON race cases                    |
| HIGH     | A readiness attestation alone could miss a disabled edge inbox; added a bounded private technical readiness probe, actual recipient capability probe and real disabled-Gateway test. | Five-service disabled-inbox and normal notice journeys                       |
| MEDIUM   | Stale workers could otherwise act on replacement requests; finalization now binds the exact request under locks.                                                                     | Cancellation/replacement/stale-worker cases                                  |
| MEDIUM   | Per-key batching/recovery must survive concurrency and process loss; progress commits with each C1 unit and acknowledged 202 never resumes work.                                     | 51-member reconstructed-service budget and concurrent COMPLETE/worker cases  |
| MEDIUM   | Identity shared JDBC I/O lacked the explicit bounds used in admission fixtures; added pool/socket/login/cancellation defaults.                                                       | Complete Identity and real topology reruns                                   |
| MEDIUM   | Teacher results must not add an undocumented OPEN-state restriction; retained owner-only access and explicit command state checks.                                                   | Owned DRAFT results/invalid CLOSE case                                       |
| LOW      | V5 action/state widths, grading policy identifier, receipt projection order and secret/projection string redaction needed correction.                                                | Migration, exact DTO parity and HTTP replay cases                            |

No confirmed BLOCKER/HIGH author-review finding remains. This is author evidence,
not an independent review or Leader merge decision. Production/release gates above
remain open, including prolonged failure and durability acceptance.

## Exact changed-file inventory

82 paths: Assessment 42, Gateway 17, Identity 11 and documentation 12. No frontend,
Classroom/Quiz runtime, historical migration, ADR-015 policy or production secret
is changed. The complete inventory follows for independent baseline-to-final review.

```text
docs/architecture/w3b-w3c-contract-review-evidence.md
docs/architecture/w3b-w3c-persistence-concurrency-design.md
docs/architecture/w3b2-student-attempt-implementation.md
docs/architecture/w3b3-closure-notification-implementation.md
docs/architecture/w3c1-grading-finalization-implementation.md
docs/open-questions.md
docs/product/assessment.md
docs/specifications/proposed/w3b-w3c.openapi.yaml
docs/specifications/w3-b-attempt-lifecycle-contract.md
docs/specifications/w3-b-closure-notification-contract.md
docs/specifications/w3-c-grading-results-contract.md
docs/specifications/w3b-w3c-http-contract.md
gateway/.env.example
gateway/README.md
gateway/src/main/java/com/quizopia/gateway/configuration/SecurityConfiguration.java
gateway/src/main/java/com/quizopia/gateway/security/admission/AdmissionClientConfiguration.java
gateway/src/main/java/com/quizopia/gateway/security/admission/AdmissionIngressFilter.java
gateway/src/main/java/com/quizopia/gateway/security/admission/AssessmentInboxReadiness.java
gateway/src/main/java/com/quizopia/gateway/security/admission/ClosureRoutes.java
gateway/src/main/java/com/quizopia/gateway/security/admission/InboxProperties.java
gateway/src/main/java/com/quizopia/gateway/security/admission/PublicationRoutes.java
gateway/src/main/java/com/quizopia/gateway/security/admission/StudentAttemptBodyFilter.java
gateway/src/main/java/com/quizopia/gateway/security/admission/StudentAttemptSecurityErrors.java
gateway/src/main/java/com/quizopia/gateway/security/admission/UserAdmissionWebFilter.java
gateway/src/main/resources/application.yml
gateway/src/test/java/com/quizopia/gateway/AssessmentInboxReadinessTest.java
gateway/src/test/java/com/quizopia/gateway/ClosureRouteTest.java
gateway/src/test/java/com/quizopia/gateway/PublicationTopologyTest.java
gateway/src/test/java/com/quizopia/gateway/StudentAttemptRouteTest.java
services/assessment-service/.env.example
services/assessment-service/README.md
services/assessment-service/contracts/publications.openapi.yaml
services/assessment-service/contracts/validate-w3b3.py
services/assessment-service/src/main/java/com/quizopia/assessment/api/ClosureController.java
services/assessment-service/src/main/java/com/quizopia/assessment/application/AttemptFinalization.java
services/assessment-service/src/main/java/com/quizopia/assessment/application/AttemptViews.java
services/assessment-service/src/main/java/com/quizopia/assessment/application/ClosureDependencies.java
services/assessment-service/src/main/java/com/quizopia/assessment/application/ClosureService.java
services/assessment-service/src/main/java/com/quizopia/assessment/application/ClosureStore.java
services/assessment-service/src/main/java/com/quizopia/assessment/application/ClosureViews.java
services/assessment-service/src/main/java/com/quizopia/assessment/application/StudentAttemptService.java
services/assessment-service/src/main/java/com/quizopia/assessment/application/StudentAttemptStore.java
services/assessment-service/src/main/java/com/quizopia/assessment/configuration/ClosureProperties.java
services/assessment-service/src/main/java/com/quizopia/assessment/configuration/PublicationJsonConfiguration.java
services/assessment-service/src/main/java/com/quizopia/assessment/configuration/SecurityConfiguration.java
services/assessment-service/src/main/java/com/quizopia/assessment/infrastructure/ClosureCursor.java
services/assessment-service/src/main/java/com/quizopia/assessment/infrastructure/ClosureDependencyAdapter.java
services/assessment-service/src/main/java/com/quizopia/assessment/infrastructure/ClosureWorker.java
services/assessment-service/src/main/java/com/quizopia/assessment/persistence/ClosureCutoffConflict.java
services/assessment-service/src/main/java/com/quizopia/assessment/persistence/JdbcAttemptFinalization.java
services/assessment-service/src/main/java/com/quizopia/assessment/persistence/JdbcClosureStore.java
services/assessment-service/src/main/java/com/quizopia/assessment/persistence/JdbcPublicationStore.java
services/assessment-service/src/main/java/com/quizopia/assessment/persistence/JdbcStudentAttemptStore.java
services/assessment-service/src/main/java/com/quizopia/assessment/security/admission/AdmissionClientConfiguration.java
services/assessment-service/src/main/java/com/quizopia/assessment/security/admission/ClosureRoutes.java
services/assessment-service/src/main/java/com/quizopia/assessment/security/admission/IdentityRecipientClient.java
services/assessment-service/src/main/java/com/quizopia/assessment/security/admission/PublicationRoutes.java
services/assessment-service/src/main/java/com/quizopia/assessment/security/admission/UserAdmissionFilter.java
services/assessment-service/src/main/resources/application.yml
services/assessment-service/src/main/resources/db/migration/V5__teacher_closure_and_notifications.sql
services/assessment-service/src/test/java/com/quizopia/assessment/AssessmentFeaturePropertiesTest.java
services/assessment-service/src/test/java/com/quizopia/assessment/AssessmentServiceApplicationTests.java
services/assessment-service/src/test/java/com/quizopia/assessment/AtomicFinalizationIntegrationTest.java
services/assessment-service/src/test/java/com/quizopia/assessment/AttemptPersistenceIntegrationTest.java
services/assessment-service/src/test/java/com/quizopia/assessment/ClosedBusinessBoundaryTest.java
services/assessment-service/src/test/java/com/quizopia/assessment/ClosureIntegrationTest.java
services/assessment-service/src/test/java/com/quizopia/assessment/ClosureRouteTest.java
services/assessment-service/src/test/java/com/quizopia/assessment/PublicationIntegrationTest.java
services/assessment-service/src/test/java/com/quizopia/assessment/SnapshotAssociationIntegrationTest.java
services/assessment-service/src/test/java/com/quizopia/assessment/StudentAttemptRouteTest.java
services/assessment-service/src/test/java/com/quizopia/assessment/StudentCapacityIntegrationTest.java
services/identity-service/.env.example
services/identity-service/README.md
services/identity-service/contracts/user-admission.openapi.yaml
services/identity-service/pom.xml
services/identity-service/src/main/java/com/quizopia/identity/api/admission/NoticeRecipientController.java
services/identity-service/src/main/java/com/quizopia/identity/application/admission/NoticeRecipientEligibility.java
services/identity-service/src/main/java/com/quizopia/identity/configuration/NoticeRecipientProperties.java
services/identity-service/src/main/java/com/quizopia/identity/configuration/UserAdmissionConfiguration.java
services/identity-service/src/main/java/com/quizopia/identity/persistence/admission/PrimaryUserAuthorityQuery.java
services/identity-service/src/main/resources/application.yml
services/identity-service/src/test/java/com/quizopia/identity/UserAdmissionIntegrationTest.java
```
