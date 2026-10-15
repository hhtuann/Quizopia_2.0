# W3 Proctoring correction campaign

Status: **H1/M1/M2 CORRECTIONS IMPLEMENTED; CUMULATIVE REAL-SERVICE ACCEPTANCE AND
INDEPENDENT RE-REVIEW PENDING. PRODUCTION NO-GO.** Updated 2026-10-15 (Asia/Bangkok).

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
historical authority must not be reused for issuance. That downstream adaptation and
its grant-denial tests are required before campaign H1 can be marked closed.

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
primary/expiry-hardened suite passed 127. Cumulative real-service grant coverage and
exact-HEAD CI remain pending at this source checkpoint. Do not transfer a historical PASS to a
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
cumulative real-service H1 grant tests remain required on #91.

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
doubles. Cumulative five-service target account/membership and media negative cases
remain required on #91. Codex focused/self-review is not independent human approval.

All eleven Assessment gates remain **6 BLOCKED / 5 NOT VERIFIED**. Formal RC remains
null; nomination remains `10615f4a9cb73ceb4bce7728f23bf210d0bc99a2`. PROCTOR-01/07/11/12,
qualified Security/Privacy acceptance, provider revocation/hard-outage qualification
and browser camera-grid acceptance remain outstanding. No production features, cloud
resources, fault/restore exercise or 48-hour soak are activated by this campaign.
