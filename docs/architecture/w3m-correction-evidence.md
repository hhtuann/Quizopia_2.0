# W3 Proctoring correction campaign

Status: **H1 PRIVATE AUTHORITY CORRECTION IMPLEMENTED; DOWNSTREAM CORRECTIONS AND
CUMULATIVE ACCEPTANCE PENDING. PRODUCTION NO-GO.** Updated 2026-10-15 (Asia/Bangkok).

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

M1 browser numeric/timestamp coercion and M2 SDK administrative bearer hardening
remain to be applied on their owning branches after the parent correction. Existing
SDK probe confirmed the six-hour default and absent explicit iat/nbf. The supported
custom-token client mechanism and operation-specific privileges must be wire-tested
before claiming M2 closed.

All eleven Assessment gates remain **6 BLOCKED / 5 NOT VERIFIED**. Formal RC remains
null; nomination remains `10615f4a9cb73ceb4bce7728f23bf210d0bc99a2`. PROCTOR-01/07/11/12,
qualified Security/Privacy acceptance, provider revocation/hard-outage qualification
and browser camera-grid acceptance remain outstanding. No production features, cloud
resources, fault/restore exercise or 48-hour soak are activated by this campaign.
