# W3 Proctoring correction campaign

Status: **H1/M1/M2 CORRECTIONS IMPLEMENTED; CUMULATIVE REAL-SERVICE TESTS PASSED;
EXACT-HEAD CI AND INDEPENDENT RE-REVIEW REQUIRED. PRODUCTION NO-GO.** Updated 2026-10-16 (Asia/Bangkok).

## Reviewed baseline and propagation

Develop remains `e5c4ef3500f1ce40303af161cd6ca74b706c21b2`. Original reviewed heads:
#88 `a0b1e393ec0826fa543ad9bee1b115c555640725`, #89
`430dbdf19ac50aee464c08e861c2c8ab761f7407`, #90
`4e1aa7d783bb3546e75fba15e07866a72f9b74b2`, #91
`d9d68f8b384352a6e580afa4394bec5babad115f`. Preserve existing Draft identities and
normal history through parent-to-child merges; no fifth PR or agent merge to develop.
Final heads, base SHAs and actual CI belong in the corresponding PR descriptions.

## H1 — Purpose-bound live target authority

The pre-fix application probe reproduced `active=true` for an owning Teacher with
zero Student membership calls, both when target membership would deny and when it
would be unavailable. Local probe: `/tmp/QuizopiaH1Probe.java`, using the original
compiled application classes. It is a port-level reproduction, not remote-service proof.

[Assessment authority](../../services/assessment-service/contracts/proctoring-authority.openapi.yaml)
now requires explicit `purpose: HISTORY | LIVE_MEDIA` and echoes that purpose in
the minimal ten-field response. Missing/unknown purpose fails 400; there is no
implicit historical-to-live fallback. HISTORY `active` describes local Attempt
lifecycle only and cannot authorize a media grant. Owning/currently admitted
Teachers retain historical access independently of current target eligibility.

LIVE_MEDIA requires current initiating USER admission, owning Publication/current
Teacher assignment, eligible monitored CLASS configuration and active bounded
Attempt. For Teacher requests, Assessment additionally checks the exact bound
Student through two separate, default-disabled private capabilities:

- [Identity Student eligibility](../../services/identity-service/contracts/proctoring-student.openapi.yaml):
  exact Assessment SERVICE identity and `identity.proctoring-student.read`; no USER
  evidence or browser/Gateway alias. Strict bounded body binds original authoritative
  `attemptStartedAt`; response contains only Student/time binding, Boolean and check time.
- [Classroom target membership](../../services/classroom-service/contracts/publication-authority.openapi.yaml):
  exact Assessment SERVICE identity and `classroom.proctoring-membership.read`, plus
  independently verified/admitted original **Teacher** USER evidence. Classroom checks
  owning Teacher and actual target membership in its own database; response contains
  only Classroom/Student binding and check time. Teacher evidence never authenticates
  the Student or supplies a Student-issued-at value.

Identity requires complete PostgreSQL-primary/Redis equality, ACTIVE/verified/STUDENT
facts and no revocation at/after the original Attempt start. This is a conservative
revocation fence for that Attempt's monitoring entitlement, not verification of a
fabricated Student JWT or an assertion about a currently held Student credential.
It denies live subscription after revocation rather than guessing that reauthentication
restored the original monitoring binding. Any expansion to a reviewed re-admission
mechanism is separate work; this correction changes no score, deadline, Attempt state
or academic consequence.

The dedicated Assessment client requests separate exact scopes and never reuses
notice-recipient authority. HTTPS/hostname trust, bounded connection/acquisition/
response/total deadlines, bounded responses, no redirects/automatic retries and no
positive eligibility cache apply. Confirmed target ineligibility/membership absence
is masked 404; unavailable/untrusted authority is sanitized 503. Identity SERVICE
authentication failures never become Teacher logout. Current original Teacher USER
failure remains distinguishable from a dependency credential failure.

After remote checks, Assessment rereads monitorability/lifecycle and critical
Publication, Classroom, owner, Student, duration, original start and deadline
bindings. No network call is made inside an Assessment answer transaction. Media
create/renew must explicitly request LIVE_MEDIA and validate the echoed purpose;
historical authority must not be reused for issuance. The downstream adaptation and
grant-denial tests are completed in the cumulative evidence below.

New operator flags remain false:

```dotenv
ASSESSMENT_PROCTORING_TARGET_ELIGIBILITY_ENABLED=false
IDENTITY_PROCTORING_STUDENTS_ENABLED=false
CLASSROOM_PROCTORING_MEMBERSHIP_ENABLED=false
```

Provision the new scopes only on the exact Assessment client when an isolated target
is authorized. The notice endpoint/scope, public API inventory, accepted product
policies, grading, answer storage and existing Flyway bytes remain unchanged.
Independent review of the new purpose-bound contracts is required before merging;
local checks do not grant production activation.

## Executed evidence and pending work

Focused Assessment verification passed 22 tests with zero failures/errors/skips:
actual PostgreSQL/HTTP authority tests, binding-race port tests and real bounded
HTTP/OAuth client tests with explicit remote doubles. Identity's full suite passed
433 tests, followed by 33 focused HTTP/configuration tests after final header/logging
hardening. Full Assessment verification passed 362 tests. Classroom's final full
primary/expiry-hardened suite passed 127. These are parent-increment results;
cumulative results are recorded below. Do not transfer a historical PASS to a
later untested head.

Executed focused Assessment command:

```bash
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash services/assessment-service/mvnw -B -f services/assessment-service/pom.xml -Dquizopia.build.directory=target/h1-assessment-focused spotless:apply verify -Dtest=ProctoringAuthorityIntegrationTest,ProctoringAuthorityServiceTest,ProctoringAuthorityTransportTest,ProctoringTargetClientTest -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

The first focused run exposed constructor binding ambiguity from a convenience
constructor on configuration properties. Removing that constructor restored normal
record binding; no security or fixture assertion was relaxed. Identity intermediate
formatting/test-package defects were corrected before its passing full run.

## M1 — Strict browser wire representations

The actual service HTTP/PostgreSQL regression reproduced eight accepted invalid
representations before the fix, among 28 negative cases. Fractions were truncated,
integral exponent/decimal forms were accepted and numeric observedAt became an
epoch timestamp. Log: `/tmp/m1-wire-repro.log` (28 tests, eight expected pre-fix
failures, zero errors/skips). The separate cached-Jackson probe reproduced the same
root cause despite scalar-coercion settings.

Event-only Jackson mix-ins now require `VALUE_NUMBER_INT` for sequence/question/
schema version, with signed long/int parser bounds. observedAt requires a timestamp
string or permitted null. A strict RFC3339 shape guard also rejects 24:00, empty fractions and offset seconds
that Instant.parse otherwise normalizes. Lowercase t/z and standard numeric offsets
remain valid. No global Instant or numeric deserializer changes other routes. Duplicate/unknown/trailing guards, required values and schema version 1 remain.

All 31 negative HTTP cases now return sanitized 400/no-store and preserve the full
Session/evidence/audit rows. Reusing the rejected event/stream/sequence with corrected
valid JSON succeeds and replays the same receipt. ISO and nullable timestamps pass.
Focused verification passed 63 tests; full Proctoring verification passed **132**
with zero failures/errors/skips. Reports: `services/proctoring-service/target/m1-service-final-3/surefire-reports`;
log `/tmp/m1-service-final-3.log`.

The private Assessment adapter now sends/validates HISTORY versus LIVE_MEDIA.
Historical-purpose responses cannot satisfy live checks; history-only port adapters
default to denying live permission. MediaService adaptation is implemented on #90;
cumulative real-service H1 grant tests passed on #91 as recorded below.

Executed full module command:

```bash
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash services/proctoring-service/mvnw -B -f services/proctoring-service/pom.xml -Dquizopia.build.directory=target/m1-service-final-3 spotless:apply verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

## M2 and downstream H1 — Bounded SDK authority and live grant admission

Actual SDK wire reproduced the old missing iat/nbf and approximately 21,599 seconds
remaining lifetime; the new regression failed before correction. The pinned public
explicit-token RoomServiceClient constructor now receives per-operation Nimbus HS256
bearers with explicit iat=nbf=current whole second and exp=iat+30 seconds. Fresh
opaque jti is mint identity, not provider single-use enforcement. The existing SDK
protobuf converter 3.0.0 is explicitly declared for compile use, without adding a
different resolved runtime library/version or replacing the official SDK.

Wire tests verify signature, timestamps, exact privileges, fresh minting and sanitized
errors/logs. Create/Delete carry only roomCreate; the provider cannot narrow that
shared grant by operation/room. Removal carries only roomAdmin and exact room;
the provider grant permits other administration within that room. No participant,
recording or data grant is added. TLS/hostname verification, timeouts, no retries/
redirects, Unix-second cutoff and Student/Teacher participant token code are preserved.

Eight wire and eight owned real LiveKit tests passed, including custom administrative
adapter create/remove/delete. The cached-token rejoin negative remains intact; this
does not qualify Cloud, strict revocation, hard-outage cutoff or browser camera E2E.

Media create and renew now invoke explicit live-purpose checks both before and after
provider I/O. Local Session/grant binding, activity, deadline and immutable fences
remain enforced. Target denial/outage prevents issuance/renewal; target loss during
provider work cannot activate or extend a grant. Teacher history and own status/
revoke retain historical authority and durable access audits without consulting
target live eligibility. No claim of immediate SFU revocation is made.

Final complete Proctoring verification passed **201 tests, zero failures/errors/skips**.
Reports: `services/proctoring-service/target/m2-correction-final-2/surefire-reports`;
log `/tmp/m2-correction-final-2.log`. An intermediate run caught two test-fixture
errors (throwing Mockito restub and wrong SQL column). Focused review also caught an
audit regression in the initial purpose adaptation; it was corrected and an actual
HTTP historical-read/revoke audit regression added before the final full PASS.

```bash
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash services/proctoring-service/mvnw -B -f services/proctoring-service/pom.xml -Dquizopia.build.directory=target/m2-correction-final-2 spotless:apply verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

Application HTTP tests use real PostgreSQL/RS256 with explicit authority/provider
doubles. The cumulative five-service target account/membership and media negative
cases passed as recorded below. Codex focused/self-review is not independent human approval.

## Cumulative correction evidence — #91

Normal parent merges preserve the published history: #88 into #89 `25b462b`,
#89 into #90 `0e7d78e`, and #90 into #91 `6f339dc`. The final PR descriptions record
the exact pushed heads and CI outcomes without embedding a self-referential source SHA.

Full cumulative Assessment verification passed **388 tests**, and Proctoring passed
**229 tests**, each with zero failures/errors/skips. Reports are in each module's
`target/correction-stack-final/surefire-reports`; logs are
`/tmp/correction-stack-assessment.log` and `/tmp/correction-stack-proctoring.log`.
Full Gateway verification passed **119 tests**, zero failures/errors/skips, including
the ten correction topology journeys and unchanged Publication topology suite.
Reports: `gateway/target/correction-stack-final/surefire-reports`; log
`/tmp/correction-stack-gateway.log`.

```bash
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash services/assessment-service/mvnw -B -f services/assessment-service/pom.xml -Dquizopia.build.directory=target/correction-stack-final spotless:check verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash services/proctoring-service/mvnw -B -f services/proctoring-service/pom.xml -Dquizopia.build.directory=target/correction-stack-final spotless:check verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash gateway/mvnw -B -f gateway/pom.xml -Dquizopia.build.directory=target/correction-stack-final spotless:check verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

Focused Gateway `ProctoringTopologyTest` passed **10 journeys**, zero failures/errors/
skips, in **98.027 seconds**. It retains the original seven and adds three correction
journeys using actual Identity-issued USER/SERVICE tokens, Gateway, Identity,
Classroom, Assessment and Proctoring; four owned PostgreSQL 17 databases, Redis 8.2,
RabbitMQ 4.1 and owned LiveKit v1.9.0. Explicit local loopback transport and synthetic
`CLOUD_STRICT` configuration are test fixtures, not production/provider qualification.

- Eligible Teacher live create and renew succeed; target membership removal denies
  both with 404 while HISTORY and timeline remain accessible. Non-owner and revoked
  Teacher assignment deny live creation/renewal.
- Disabled/revoked target primary state with stale Redis returns 503; complete equal
  ineligible authority returns 404. Both create/renew through Gateway and directly
  preserve full grant rows and Assessment fingerprints. The revocation fence binds
  the original Attempt start; historical access remains available.
- Paused owned Classroom database denies create/renew with 503 and permits renewal
  after recovery. This also affects Teacher-assignment authority; it does not isolate
  only the target endpoint. Target-only outage isolation and live denial for terminal/
  deadline states are directly covered by module tests/explicit authority doubles.

Student camera-only and Teacher subscribe-only participant token metadata are
verified safely in memory; owned logs are checked for USER/media tokens and secrets.
The original confirmed outbox/inbox SAVE replay, broker recovery, post-END history
and Assessment-invariant journeys remain intact. No browser camera/media acceptance,
Cloud revocation qualification or immediate SFU token invalidation is claimed.

```bash
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash gateway/mvnw -B -f gateway/pom.xml -Dquizopia.build.directory=target/h1-proctoring-topology -Dtest=ProctoringTopologyTest spotless:apply test -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

Reports: `gateway/target/h1-proctoring-topology/surefire-reports`; log
`/tmp/h1-proctoring-topology.log`. Focused read-only Codex review found no remaining
HIGH/MEDIUM issue in the correction topology and handoff; separate independent
re-review of the complete correction stack remains required.

Executed static audit `/tmp/quizopia-correction-final-audit.py` validated five OpenAPI
documents (private Assessment 1, private Identity 1, private Classroom 3, public
monitoring 6 and media 4 operations), ten disabled feature defaults, 185 protected
files byte-identical to original #91, and scoped high-confidence key/access-token/JWT
scan. Protected scope includes all historical migrations including Assessment V6 and
Proctoring V3, accepted ADRs, frontend, shared event schema and release governance.
Synthetic fixture credentials were manually reviewed. Relative Markdown targets were
checked; fragment anchors were not. `docker compose config --quiet` and
`git diff --check` passed. No new public route or public Assessment schema was added.

The unchanged release-register validator passed, and all **60** unchanged safety
tests passed using the existing Python dependency directory:

```bash
PYTHONPATH=gateway/target/w3a-evidence/openapi-python python3 /tmp/quizopia-correction-final-audit.py
PYTHONPATH=gateway/target/w3a-evidence/openapi-python python3 services/assessment-service/contracts/validate-w3-production-release.py
PYTHONPATH=gateway/target/w3a-evidence/openapi-python python3 services/assessment-service/contracts/test-w3-production-release.py
```

CI history must remain visible: #89's initial Gateway job timed out in unchanged
`PublicationTopologyTest` (106 tests, zero failures, one error); a retry passed all
eight backend jobs. #90's initial Proctoring job passed all 201 tests but failed
packaging on Maven `file-management:3.1.0` artifact-descriptor resolution. Its retry
passed all eight backend jobs. Final #91 CI belongs in its PR description once
observed. No assertion,
authorization, fixture size or timeout was relaxed to obtain a passing result.

All eleven Assessment gates remain **6 BLOCKED / 5 NOT VERIFIED**. Formal RC remains
null; nomination remains `10615f4a9cb73ceb4bce7728f23bf210d0bc99a2`. PROCTOR-01/07/11/12,
qualified Security/Privacy acceptance, provider revocation/hard-outage qualification
and browser camera-grid acceptance remain outstanding. No production features, cloud
resources, fault/restore exercise or 48-hour soak are activated by this campaign.
