# LD-05 Gateway and downstream enforcement

Status: bounded implementation submitted for Leader review against develop
`123c8f5abe16c266c4d06c549cb8c84cca19ea92`, following merged Identity PR #75.
Candidate B remains authoritative. This is not Wave 3 activation approval.

## Protected route inventory and compatibility boundary

Inventory was derived from controller mappings and Gateway configuration before
implementation. A prefix is not evidence of a controller. Paths are not rewritten.
The following table determines enforcement; methods not listed are not opened.

| Method    | Path                                                                         | Classification                                             | Enforcement                                                                                  |
| --------- | ---------------------------------------------------------------------------- | ---------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| POST      | `/api/auth/register`                                                         | PUBLIC INFRASTRUCTURE / AUTH TRANSPORT                     | Existing Identity transport                                                                  |
| POST      | `/api/auth/email-verification/request`                                       | PUBLIC INFRASTRUCTURE / AUTH TRANSPORT                     | Existing Identity transport                                                                  |
| POST      | `/api/auth/email-verification/confirm`                                       | PUBLIC INFRASTRUCTURE / AUTH TRANSPORT                     | Existing Identity transport                                                                  |
| POST      | `/api/auth/login`                                                            | PUBLIC INFRASTRUCTURE / AUTH TRANSPORT                     | Existing Identity transport                                                                  |
| POST      | `/api/auth/refresh`                                                          | PUBLIC INFRASTRUCTURE / AUTH TRANSPORT                     | Existing Identity transport                                                                  |
| POST      | `/api/auth/logout`                                                           | PUBLIC INFRASTRUCTURE / AUTH TRANSPORT                     | Existing Identity transport                                                                  |
| GET       | `/api/auth/me`                                                               | EXISTING NON-W3 ROUTE — PRESERVE CURRENT ACCEPTED CONTRACT | USER; Identity-owned authority, no recursive admission                                       |
| POST      | `/api/auth/teacher-enablement`                                               | EXISTING NON-W3 ROUTE — PRESERVE CURRENT ACCEPTED CONTRACT | Existing Identity contract                                                                   |
| GET, POST | `/api/quizzes`                                                               | EXISTING NON-W3 ROUTE — PRESERVE CURRENT ACCEPTED CONTRACT | Existing Quiz authoring/library contract                                                     |
| GET, PUT  | `/api/quizzes/{quizId}/draft`                                                | EXISTING NON-W3 ROUTE — PRESERVE CURRENT ACCEPTED CONTRACT | Existing Quiz owner checks                                                                   |
| GET, POST | `/api/quizzes/{quizId}/versions`                                             | EXISTING NON-W3 ROUTE — PRESERVE CURRENT ACCEPTED CONTRACT | Existing Quiz contract                                                                       |
| GET       | `/api/quizzes/{quizId}/versions/{versionNumber}`                             | EXISTING NON-W3 ROUTE — PRESERVE CURRENT ACCEPTED CONTRACT | Existing Quiz contract                                                                       |
| POST      | `/classrooms`                                                                | CURRENTLY IMPLEMENTED — AUTHORIZED LD-05 TARGET            | USER + TEACHER in JWT and current admission, independently at Gateway and Classroom          |
| GET       | `/classrooms/{canonical-UUID}`                                               | CURRENTLY IMPLEMENTED — AUTHORIZED LD-05 TARGET            | USER + STUDENT in JWT/current admission; existing Classroom owner check                      |
| ALL       | Other `/classrooms` methods/nested paths; `/api/classes/**`                  | FUTURE PROTECTED ROUTE — DEFAULT CLOSED                    | Deny; no implemented controller at `/api/classes/**`                                         |
| ALL       | `/api/assessments/**`, `/assessments/**`, `/publications/**`, `/attempts/**` | FUTURE PROTECTED ROUTE — DEFAULT CLOSED                    | No Assessment business controller; deny                                                      |
| ALL       | `/api/proctoring/**`, `/proctoring/**`                                       | FUTURE PROTECTED ROUTE — DEFAULT CLOSED                    | No Proctoring business controller; deny                                                      |
| ALL       | Future W3-A/B/C/M1/M2 routes including alternative prefixes                  | FUTURE PROTECTED ROUTE — DEFAULT CLOSED                    | Assessment/Proctoring downstream deny all business paths until reviewed exact adapters exist |
| GET       | `/actuator/health`, `/actuator/health/**`, `/actuator/info`                  | PUBLIC INFRASTRUCTURE / AUTH TRANSPORT                     | Public; no admission                                                                         |
| GET       | `/v3/api-docs`, `/v3/api-docs/**`, `/swagger-ui/**`                          | PUBLIC INFRASTRUCTURE / AUTH TRANSPORT                     | Downstream development documentation only; closed in prod                                    |
| POST      | `/internal/security/user-admissions`                                         | INTERNAL SERVICE ONLY                                      | Identity dual credentials; never Gateway routed                                              |
| POST      | `/oauth2/token`                                                              | INTERNAL SERVICE ONLY                                      | Direct Identity Client Credentials; never Gateway routed                                     |
| GET       | `/oauth2/jwks`, authorization-server metadata                                | INTERNAL SERVICE ONLY                                      | Direct Identity verification infrastructure; never Gateway routed                            |
| ALL       | Other `/internal/**`                                                         | INTERNAL SERVICE ONLY                                      | Gateway and affected downstream deny; future exact scopes/actor binding require review       |

Classroom compatibility is explicit: both existing operations acquire a mandatory
authority dependency. DTOs, statuses for business validation/ownership, JWT-derived
ownership, and owner reads by STUDENT remain intact. Unconfigured or disabled
admission makes these operations unavailable, including existing Wave 2 clients;
there is no legacy toggle permitting JWT-only traffic on these same routes.
Deployment must provision authority before routing Classroom traffic to this binary.

The existing Gateway `/api/classes/**` prefix did not reach the implemented
`/classrooms` controller. This PR adds exact method/path `/classrooms` routes
without rewrites; it does not invent aliases or open the future prefix. No manual
student-addition/invitation HTTP controller exists despite application services.
No folders HTTP API exists in Quiz. Generic community/AI routes retain their
existing policy and are outside this admission contract.

## Enforcement and dual credentials

Gateway validates the original RS256 USER JWT and ADR-014 claims before its exact
route authorization and reactive admission filter. It acquires its own short-lived
SERVICE token, calls Identity directly with that bearer and the original USER
JWT in `X-Quizopia-User-Evidence`, strictly validates the bounded JSON response,
checks actor equality and current/JWT roles, then forwards the original USER
Authorization to Classroom. Each Classroom operation independently repeats
admission using Classroom's separate SERVICE identity before business code.
Owner checks remain in Classroom. Admission is not membership or resource authority.

Assessment and Proctoring install the same private technical client and ADR-014
principal conversion, but have **zero opened business routes**. Their default
business/internal authorization is deny-all. A future business PR must add an
exact method/path/role adapter and its independent admission before removing that
route's denial. Merely adding a controller, JWT role or route configuration is
insufficient. Unreviewed internal endpoints remain denied, including valid SERVICE
JWTs. No generic authenticated fallback can open them.

Adapters are service-local technical code so the four Maven projects remain
independently buildable within this PR's ownership scope. No shared domain,
persistence, account database access, migration or event contract is introduced.
The existing Identity wire contract is unchanged.

Gateway strips all `X-Quizopia-*`, `X-Authenticated-*`, `X-User-Id`, `X-User-Roles`
and `X-Service-Identity` headers before any routing, including out-of-scope routes.
Classroom rejects these headers on protected direct requests. No spoofable header
establishes actor identity. Duplicate Authorization is rejected. Gateway rejects
percent-encoded paths, backslashes, doubled slashes, matrix parameters and dot
segments before route matching; query parameters are not decoded as route paths.
This excludes ambiguous aliases rather than relying on downstream rewrites.
Browser CORS remains limited to Content-Type/Authorization; preflight obtains no
SERVICE token and performs no admission. Refresh cookies are never copied to the
private admission/OAuth client. Identity auth paths retain their transport behavior.

## Bounded clients and failures

The private client uses Spring Security's
[reactive Client Credentials manager](https://docs.spring.io/spring-security/reference/reactive/oauth2/client/core.html),
an in-memory authorized-client service, and dedicated Reactor Netty/WebClient
transport. Unexpired SERVICE tokens may be reused until five seconds before
expiry; there is no positive USER admission cache. Every admitted operation
makes another Identity admission call. Separate clients never attach their SERVICE
bearer to browser business routing.

Defaults are connection/pool acquisition timeout 1s, response timeout 3s, and
total admission deadline 5s (including token acquisition). Each instance permits
8 private connections and 32 queued acquisitions. Configuration validates bounds:
1–64 connections, 1–256 queued acquisitions, positive timeouts within total,
and total at most 30s. There are no automatic transport retries or redirects.
Cancellation and errors cannot continue the business pipeline. Servlet admission
waits only on its request thread with the same total bound plus a 100ms blocking
wait guard; Gateway never blocks its event loop. These bounds are operational
controls, not a measured production SLA or hard realtime guarantee under host
starvation. Independent JWT verification/JWKS and business dependency deadlines
must also be reviewed for the deployment.

Response size is bounded to 4096 bytes. The response must have exactly actorId
and roles, canonical matching UUID, known unique lexically ordered roles including
STUDENT, no duplicate JSON fields and no trailing document. Required roles must
exist in both local JWT and current response; unexpected role disagreement denies.
USER expiry is checked again after the asynchronous call.

| Identity/dependency result                                                   | Public outcome                  |
| ---------------------------------------------------------------------------- | ------------------------------- |
| Valid matching 200 with required roles                                       | Continue current operation only |
| 401 with exactly `USER_EVIDENCE_INVALID`                                     | 401 `UNAUTHENTICATED`           |
| Valid response without required/current matching roles                       | 403 `ACCESS_DENIED`             |
| SERVICE 401, SERVICE 403, integration 400                                    | 503 `AUTHORITY_UNAVAILABLE`     |
| Identity 503, disabled/missing dependency, timeout, refusal, pool exhaustion | 503 `AUTHORITY_UNAVAILABLE`     |
| Malformed/oversized/unknown JSON, actor mismatch, redirect, unknown status   | 503 `AUTHORITY_UNAVAILABLE`     |

Errors contain no upstream body, exception cause, credential or destination.
Sensitive outcomes use Cache-Control no-store. Gateway transport failure after
successful admission retains normal dependency error handling; admission errors
are handled before subscribing to downstream routing. It cannot retry or bypass
admission. Resource-level business statuses remain unchanged. No dependency
failure changes refresh families, clears cookies, triggers server-side logout,
or substitutes `/me`, sparse Redis or an earlier verdict.

## Provisioning and deployment controls

All four projects expose the same environment names in their separate deployment
contexts. Set each context's own values; do not share a broad administrative client.

| Environment                              | Meaning/default                                                      |
| ---------------------------------------- | -------------------------------------------------------------------- |
| `USER_ADMISSION_ENABLED`                 | `false`; protected Classroom routes return 503 while disabled        |
| `USER_ADMISSION_IDENTITY_BASE_URL`       | Exact private Identity origin; empty/unconfigured by default         |
| `USER_ADMISSION_CLIENT_ID`               | Exact separately registered client ID                                |
| `USER_ADMISSION_CLIENT_SECRET`           | Runtime secret only; no committed value                              |
| `USER_ADMISSION_ALLOW_INSECURE_LOOPBACK` | `false`; test/development override only for literal loopback HTTP    |
| `USER_ADMISSION_CONNECT_TIMEOUT`         | `PT1S`                                                               |
| `USER_ADMISSION_RESPONSE_TIMEOUT`        | `PT3S`                                                               |
| `USER_ADMISSION_TOTAL_TIMEOUT`           | `PT5S`                                                               |
| `USER_ADMISSION_MAX_CONNECTIONS`         | `8` per instance                                                     |
| `USER_ADMISSION_PENDING_ACQUIRES`        | `32` per instance                                                    |
| `IDENTITY_ISSUER`, `IDENTITY_JWKS_URI`   | Expected issuer and verification keys; prod requires explicit values |

Register separate Gateway and Classroom clients with only `identity.user-admission`
through existing Identity provisioning. Assessment/Proctoring clients receive no
privilege automatically; provision only when reviewed adapters need it. Add exact
client identities to Identity's existing admission allowlist. Store secrets in
runtime secret storage. This PR does not seed clients, enable Identity admission,
or enable its reconciler.

HTTPS with certificate/hostname validation, private routing, strict destination
and network allowlists and controlled DNS are production gates. Insecure HTTP is
accepted only with an explicit override and literal loopback origin; it provides
no production transport evidence. Do not trust arbitrary Forwarded/X-Forwarded
headers or expose Identity internal paths through Gateway. Terminating proxies
must have reviewed forwarding trust; forwarded claims never establish USER identity.

Private clients do not inherit browser headers/cookies, automatic observations or
HTTP dumps. Response decoding uses bytes rather than logging decoded strings.
Committed logging defaults suppress OAuth/client/codec debug bodies; keep these
redactions in deployment overrides. Never enable wiretap/request-detail logging
for credential traffic. Also redact Authorization, Cookie, USER evidence and
client secrets at proxies, ingress, APM, HTTP clients, traces and support tooling.
Access logs/trace URI capture must exclude secret-bearing query strings. Logging
configuration alone is not proof that external infrastructure is redacted.

## Activation and mixed-version rollout

1. Keep future Assessment/Proctoring routes closed; no W3 feature is opened here.
2. Review and provision narrow clients, internal TLS/routing/redaction and primary
   durability/failover requirements from the Identity addendum.
3. Independently enable and verify Identity reconciliation and admission under
   their own release approval. Cold/stale actors remain unavailable until verified.
4. Configure the new Gateway and Classroom clients, test actual dependency and
   owner denials, and measure capacity with both admissions per Classroom request.
5. Before routing protected traffic, **drain, block or replace every bypass-capable
   old Gateway and old downstream instance**. Network/routing controls must prevent
   direct access to old downstream binaries. Feature flags cannot secure old code.
   A rollback to bypass-capable binaries requires blocking affected traffic first.
6. Deploying this binary with disabled admission deliberately makes current
   Classroom operations unavailable; do not use a JWT-only compatibility window.
7. Future route activation needs exact Gateway and owning-service coverage, no
   alternate path bypass, role/ownership/membership contracts, real topology tests
   and Leader/security release approval. No default-allow route flag is provided.

Quiz actor-bearing snapshot handoff and Classroom Teacher/membership internal
business APIs are separate prerequisites, not implemented here. W3-A Publication,
W3-B Attempts, W3-C results, W3-M1/M2 monitoring and privacy gates remain open.
Capacity/pool contention, multi-instance behavior, mixed-version deployment drills,
TLS/redaction validation and fenced primary failover remain external release gates.

## Verification evidence and limits

All four scoped projects passed verification: Gateway 75 tests, Classroom 106,
Assessment 36 and Proctoring 36 (253 total, zero failures/errors/skips). Checks
use Java 21, Spotless, compilation,
JUnit/security integration, ArchUnit and Maven packaging. The Gateway suite
includes six real-topology cases: actual Identity login/JWKS and Client Credentials,
actual Gateway and Classroom HTTP servers, separate PostgreSQL 17 Identity and
Classroom databases, and Redis 8.2. It covers both boundaries, resource ownership,
wrong caller/scope/secret, durable role/cutoff changes, stale/missing Redis,
Redis/primary/Identity outages and recovery without refresh-family invalidation.
The topology seeds explicit test authority projections with the reconciler disabled;
it does not re-prove the Identity reconciler or its full PR #75 test suite.

Other failure tests use isolated HTTP OAuth/admission stubs, with real RSA-signed
USER validation at the local HTTP boundary. They prove response handling, routing,
header stripping, bounded pool contention/timeouts and lack of positive reuse;
they are not additional real Identity authority tests. Cancellation tests mock
only the private client to check subscription cancellation, no continuation on
failure, and preservation of downstream error classification.

Run from the repository root (each project has its own Maven wrapper):

```sh
export MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven
export MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2'
for ld05_project in gateway services/classroom-service services/assessment-service services/proctoring-service; do
  bash "$ld05_project/mvnw" -B -f "$ld05_project/pom.xml" \
    -Dquizopia.build.directory=target/ld05-verification \
    spotless:apply clean verify \
    -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
done
```

The distribution-cache path above records the local environment; omit that export
on a normal CI/developer installation. The optional build-directory property
isolates Maven output from an active IDE compiler; its default remains `target`.
The topology uses a separate nested Classroom artifact directory for the same
reason. Test reports are under each selected build directory's `surefire-reports`.
CI retains the standard `./mvnw -B verify` command.

Local topology transport uses explicit loopback HTTP overrides. Production TLS,
proxy/APM redaction, failover, multi-instance/mixed-version rollout and measured
capacity remain release gates. The local full Gateway run emitted a Netty buffer
leak diagnostic. A paranoid diagnostic repeat passed 34 tests and reproduced it;
a clean archive of unchanged develop also reproduced four diagnostics while its
13 Gateway auth tests passed. This is an observed baseline issue, with exact
origin unconfirmed, requiring assessment during the capacity gate. Passing assertions do not establish
production memory/latency behavior. No business feature or production authority
configuration is activated by these tests.

## W3-A bounded extension

The [Publication backend increment](w3a-publication-backend.md) extends the earlier
closed Assessment inventory with only the exact implemented ASSESS-15 operations.
Gateway and Assessment each validate USER credentials and obtain fresh admission.
Quiz/Classroom private source authority additionally validates Assessment SERVICE
credentials and independently verifies/admit the original initiating USER.
Earlier verification figures above describe PR #76, not this extension. Default
off deployment controls, deferred CLOSE/Attempts/results/monitoring and external
release gates remain explicit; no production route activation follows.
