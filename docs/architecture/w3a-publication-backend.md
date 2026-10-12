# W3-A Publication backend increment

This increment implements Quiz/Classroom source authority, Assessment DRAFT and
OPEN persistence, safe discovery and exact Gateway/Assessment LD-05 enforcement.
The [ASSESS-15 contract](../specifications/assess-15-publication-contract.md) records
bounded engineering choices submitted for Leader review. Accepted LD-01..05 policy
is preserved. **Production activation is not authorized.** W3-A remains partial:
CLOSE, frontend journeys and W3-B/C/M runtime are not implemented.

## Ownership and transactions

Quiz supplies one immutable version identified by quizId + quizVersionId +
versionNumber, only to the configured Assessment SERVICE client and independently
verified/currently admitted owning Teacher. Classroom supplies separate Teacher
ownership and evidence-derived Student membership confirmations. Invitations are
not memberships. Internal routes are hidden from public Swagger and denied by
Gateway; they require distinct SERVICE Authorization and original USER evidence.
Each source independently obtains Identity admission; Assessment never supplies
an actor UUID as a substitute for USER credentials.

Assessment alone writes its PostgreSQL database. Source handoff preserves original
source, all four question types, order, Markdown, correctness, numeric answer
strings and explanations. The source adapter validates the complete versioned wire
model; unknown/missing/malformed facts fail closed. It accepts a maximum 2 MiB
response, an engineering capacity bound submitted for review; a larger valid Quiz
version returns dependency 503 rather than truncated or partial publication.

Remote authority/content preparation happens before a short local transaction.
OPEN locks the Publication row, rechecks owner/DRAFT/revision and atomically inserts
the complete immutable snapshot and OPEN state. Concurrent OPEN for the same draft
revision converges on the committed resource. An intervening edit conflicts.
Replay performs fresh USER admission and owner checks, without refetching source.
Publication identity/source/audience/Classroom/monitoring and snapshot freeze;
OPEN schedule updates permit only end/duration extensions. No distributed atomicity
is claimed across Identity/Classroom authority snapshots and Assessment commit.

The migration uses one-way local snapshot FK, explicit non-null state invariants,
configuration/relational consistency checks, durable owner-scoped creation replay
keys, and same-table immutable guards. There is no Attempt table, active counter,
CLOSE placeholder, automatic closure or published answer/review API. Later W3-B
must coordinate START/CLOSE/finalization using the same Publication row lock and
supply authoritative Attempt state before CLOSE can be enabled.

## Deployment controls

All controls default closed:

- Gateway and Assessment: `PUBLICATION_ENABLED=false`.
- Assessment sources: `PUBLICATION_SOURCES_ENABLED=false`.
- Quiz and Classroom: `INTERNAL_AUTHORITY_ENABLED=false`.
- All required clients: `USER_ADMISSION_ENABLED=false` until provisioned.

Disabled operations return sanitized unavailable responses after authentication;
configuration never falls back to JWT-only or Redis-only authorization. Provision
Identity clients through its existing controlled registration mechanism. Each
client needs its own secret and `identity.user-admission`, with exact Identity
caller allowlisting: Gateway, Assessment, Quiz and Classroom deployment identities.
Only Assessment additionally needs `quiz.version-snapshot.read`,
`classroom.teacher-authority.read` and `classroom.membership.read`. The private
source clients request separate tokens for these narrow scopes. Configure Quiz
and Classroom `ASSESSMENT_SERVICE_CLIENT_ID` to exactly Assessment's identity.
Do not grant source scopes to Gateway/browser clients. SERVICE JWTs remain valid
until their accepted expiration; USER cutoff is not immediate SERVICE revocation.

Pin each private origin using `USER_ADMISSION_IDENTITY_BASE_URL`,
`PUBLICATION_QUIZ_BASE_URL` and `PUBLICATION_CLASSROOM_BASE_URL`. Only HTTPS origins
are accepted outside explicit test-only loopback overrides. Keep
`INTERNAL_AUTHORITY_REQUIRE_TLS=true`, terminate authenticated encrypted transport
at the correctly trusted boundary and validate certificates/hostnames. Do not trust
uncontrolled forwarded scheme headers. Enforce private destination/caller network
allowlists; never publicly route `/internal/**` or add USER evidence to browser
CORS. `Idempotency-Key` is the sole new browser header.

Apply Assessment Flyway migration before enabling traffic, deploy sources with
provisioned scopes and caller allowlists, verify admission, deploy Assessment and
Gateway with flags disabled, then obtain operational release approval. Drain/block
older bypass-capable Gateway/downstream instances first. Rollback begins by blocking
Publication traffic; do not roll back to a JWT-only adapter. Restart requires no
business cache reconstruction: durable replay/snapshots survive. Identity remains
responsible for complete Redis per-actor reconciliation and primary equality.
Missing/stale Redis denies regardless of process health or previous success.

Redact Authorization, USER evidence, client secrets, cookies, private locators and
answer bodies in ingress/egress proxies, APM, audit and application logs. Internal
clients use dedicated pools/builders, disable redirects/retries, forward no refresh
cookies, and cap response sizes. Record string rendering redacts private DTOs;
MVC body and reactive credential codec logging are suppressed. PUBLIC locator is
retained privately in PostgreSQL for owner retrieval, with a SHA-256 lookup digest;
protect database/backups and keep sharing out of URL query/access logs. Link
possession never replaces authenticated current STUDENT eligibility.

Admission clients retain bounded pools/deadlines. Source calls use the same bound
settings in a separate pool; sequential operations may perform multiple admissions.
Assessment uses 3-second JDBC statement/lock/acquisition limits, a 5-second local
transaction/socket bound and controlled UTC business clocks. Measure end-to-end
latency, pool saturation and multi-instance contention before activation. Preserve
the earlier observed Netty buffer-leak diagnostic as an unresolved investigation;
passing local assertions cannot establish production leak/latency safety.

Identity must use one fenced writable primary and preserve acknowledged security
commits across failover. Assessment must preserve committed replay/snapshot writes
across its own failover. TLS/redaction, failover, mixed-version rollout and capacity
verification are external gates. Camera permission/refusal/loss/accommodation,
evidence retention and LiveKit runtime policy remain W3-M privacy/security gates.
Persisting ENABLED records only the approved activity/camera bundle choice; it does
not activate monitoring or authorize its release.

## Verification boundaries

Source focused tests use real PostgreSQL and RSA-signed credentials with a mocked
admission client. Assessment persistence/concurrency tests use real PostgreSQL,
with source/admission mocks to control races. Gateway isolated failure tests use
HTTP OAuth/admission stubs. These are supplementary evidence.

`PublicationTopologyTest` starts actual Identity, Quiz, Classroom, Assessment and
Gateway, four independent PostgreSQL 17 containers and Redis. USER tokens come
from real Identity login; SERVICE tokens come from Client Credentials. It persists
and publishes actual Quiz content, verifies CLASS membership and PUBLIC entry,
executes concurrent HTTP OPEN requests and checks persisted numeric/source fidelity.
It also tests direct-service missing/stale authority and preserves prior topology
outage/role/cutoff scenarios. Fixtures seed complete actor projections with the
reconciler disabled; Identity's separate compatibility suite verifies reconciliation.
Explicit loopback HTTP is controlled test transport, not production TLS evidence.

## Executed local verification

Java 21 / Maven verification passed with zero failures, errors or skipped tests:

| Project                                           | Tests |
| ------------------------------------------------- | ----: |
| Quiz                                              |   166 |
| Classroom                                         |   113 |
| Assessment                                        |    85 |
| Gateway                                           |    84 |
| Identity compatibility (unchanged implementation) |   423 |
| Total distinct tests in these five suites         |   871 |

Gateway includes eight new five-service topology cases and six retained prior
Identity/Classroom topology cases. Assessment includes eighteen real PostgreSQL
Publication cases, including concurrent creation/OPEN, edit versus OPEN, rollback,
constraints, immutable snapshots, locator changes and controlled schedule boundaries.
These counts do not represent unexecuted Frontend, Proctoring, AI or Community tests.

Commands, from repository root:

```sh
export MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven
export MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2'
for w3a_project in services/quiz-service services/classroom-service services/assessment-service gateway; do
  bash "$w3a_project/mvnw" -B -f "$w3a_project/pom.xml" \
    -Dquizopia.build.directory=target/w3a-verification \
    spotless:apply clean verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
done
bash services/identity-service/mvnw -B -f services/identity-service/pom.xml \
  verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

The changed services were subsequently verified again with the same command
without `clean` after validated fixes. Reports live in their selected build
directories; local evidence logs are under ignored `gateway/target/w3a-evidence`.
The Maven-cache export is environment-specific and is unnecessary in normal CI.
OpenAPI 3.1 documents were validated using `openapi-spec-validator==0.7.2` in an
isolated ignored target directory; the eight changed Markdown/OpenAPI documents
passed repository Prettier. Both working and staged `git diff --check` passed.
An initial Quiz read timeout under host pressure disappeared on rerun. Test
fixture/profile and strict JSON parsing defects found during implementation were
fixed before the successful verification above. Production release gates remain
unverified by these local results.
