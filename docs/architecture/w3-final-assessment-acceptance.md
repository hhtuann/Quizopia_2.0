# W3-FINAL — Integrated Assessment acceptance

Status: **CONDITIONAL ACCEPTANCE — external release gates remain open. No production activation or release authorization.**

Recorded 2026-10-14 (Asia/Bangkok). Baseline is merged PR #83,
`4a75be8f446493ad3dd5025a3930b42639cafecb`. Work branch:
`chore/w3-final-runtime-security-acceptance`. Accepted
[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md), C1–C10 and EC79-01..04
remain unchanged. All executable Assessment V1–V5 migrations, including V4.1,
and the implemented/proposed OpenAPI contracts remain byte-identical to this merge.

This is a new execution against merged develop, not a reuse of PR #83's green CI.
The same primary agent authored B3; these fresh executions and self-review do not
constitute independent human/security review or Leader sign-off. The PR description
records exact candidate HEAD, remote verification and actual post-push CI results.

The [acceptance matrix](w3-final-acceptance-matrix.md) records individual gates,
owners and limits. The [deployment runbook](../development/w3-final-deployment-runbook.md)
describes a future separately authorized rollout; it was not executed in production.

## Actual environment and isolation

Java OpenJDK 21.0.12.1; repository Maven wrappers; Docker Engine 29.9.0;
PostgreSQL 17.11 (`postgres:17-alpine`) and Redis 8.2.10 (`redis:8.2-alpine`). Docker context was the local
`unix:///var/run/docker.sock`. The cached Redis image version was independently
executed with `docker run --rm --network none --entrypoint redis-server redis:8.2-alpine --version`.
Heavy suites ran sequentially with Maven heap 256 MiB, test heap 384 MiB and
two active processors. Gateway used one isolated JVM fork per test class.
No external staging environment, production database, real personal data or
production credentials were used. Tests create their own containers, mapped
loopback ports, separate service databases and synthetic users.

The existing five-service harness starts actual Identity, Classroom, Quiz and
Assessment JVMs plus actual Gateway HTTP runtime. USER and SERVICE credentials
are issued by the actual isolated Identity Authorization Server. Verified account,
roles, clients and Redis projections are explicitly seeded test fixtures; this
is not a real SMTP-based registration/verification journey. Identity's separate
full tests execute registration/verification and reconciliation, without claiming
external mail delivery. Production code performs no cross-service SQL.

Enabled mode sets admission for Gateway/Assessment/Quiz/Classroom, Identity admission
and recipient capability, Publication/source authority, Attempt/results/closure,
inbox/worker, edge attestation/origin and a test cursor key. The test-only loopback
HTTP overrides and 96-hour USER TTL allow controlled PostgreSQL clock progression;
neither is a production example. Production feature defaults remain false.

Default mode removes future switches and authority configuration from real peer
processes. Gateway and Assessment reject all nine B2/eight B3 operations and a W3-A
read with 503; Identity's recipient endpoint returns 503. Quiz/Classroom private
source routes deny with 403 because the default caller allowlist is empty. Gateway
denies the private Identity route. These are independent per-service runtime checks,
not a separately provisioned staging deployment or concurrent default-mode cluster.

## Reproducible commands and evidence

From the repository root, with Java 21, Docker and the existing Python packages
PyYAML, jsonschema and openapi-spec-validator available:

```sh
bash services/assessment-service/contracts/verify-w3-final.sh
python3 services/assessment-service/contracts/verify-w3-final.py
git diff --check
```

The wrapper runs all five projects sequentially with their own Maven wrapper:

```sh
bash MODULE/mvnw -B -f MODULE/pom.xml \
  -Dquizopia.build.directory=target/w3-final-verification spotless:check verify \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
# Gateway additionally: -DreuseForks=false -DforkCount=1
```

`MODULE` is, in order, services/assessment-service, services/identity-service,
services/classroom-service, services/quiz-service and gateway. Logs are local ignored
`gateway/target/w3-final-evidence/MODULE-NAME.log`; reports are each module's
`target/w3-final-verification/surefire-reports`. Do not publish raw diagnostic logs,
generated keys, JWTs, database archives or fixture dumps. Committed source/tests
and sanitized counts/measurements are the review evidence. No dependency was added.

The baseline command used `target/w3-final-baseline` with otherwise the same options.
All five commands exited 0 before source editing: Assessment **331**, Identity **427**,
Classroom **113**, Quiz **167**, Gateway **100**, zero failures/errors/skips (**1138**).
The three real OpenAPI artifacts, 11 answer fixtures, route/schema parity, local
references, scoped credential patterns and historical migration hashes passed.
GitHub's commit-workflow lookup returned no runs for the exact merge SHA; that
merged-commit CI gate is NOT VERIFIED, independently of successful local runs.

New focused commands actually executed:

```sh
bash services/assessment-service/mvnw -B -f services/assessment-service/pom.xml \
  -Dquizopia.build.directory=target/w3-final-verification spotless:apply test \
  -Dtest=AssessmentAcceptanceIntegrationTest \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
bash gateway/mvnw -B -f gateway/pom.xml \
  -Dquizopia.build.directory=target/w3-final-verification spotless:apply test \
  '-Dtest=PublicationTopologyTest#acceptance*' \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

The final database focused run passed eight cases, including metric and explicit
REQUEST/RETAKE and CLOSE/worker lock-barrier coverage;
the corrected topology focused run passed all three cases. Full final execution
and repeated capacity samples are recorded below. Focused cases
overlap final module counts and must not be added to them again.

## Exercised runtime and security behavior

The retained suites execute CLASS/PUBLIC preparation/opening, immutable delivery,
locator/path binding, START/RESUME/RETAKE, finite/unlimited entitlement, question SAVE,
global revisions, idempotency, complete final-answer replacement, ABANDON, bounded
timeout, exact four-type grading, totals/best score, ordinary CLOSE and CLOSED review.
Real Gateway/Assessment checks cover USER/SERVICE separation, issued/current roles,
ownership/membership/recipient masking, revocation, malformed/oversized input,
forged headers, private-route denial and authority outages without session invalidation.
Real Identity tests cover primary READ COMMITTED facts, complete Redis equality,
actual issued credentials, recovery and exact scope/client boundaries.

Delayed closure exercises atomic capture, publish-before-confirm, real owner inbox,
actual recipient authority, cancellation/replacement, exact individual confirmation
plus 172800 elapsed seconds, saved-only real C1 grading, cancellation fencing and
Teacher-only CLOSED. Default/disabled inboxes fail closed, including a separately
enabled Assessment peer with only its inbox disabled: REQUEST returns 503 with no
capture mutation. Existing PostgreSQL barriers cover cutoff/deadline lock waits,
Student mutations, REQUEST/schedule/START, CANCEL/publish/confirm/finalizer and
COMPLETE/worker races. The additional acceptance cases directly observe REQUEST/RETAKE waiters and a
Teacher CLOSE waiting behind an uncommitted worker result, then inspect durable rows.

The new second Assessment **OS process/JVM** uses the same Assessment database and
real independent admission clients. Both workers run; after confirmed notice the
primary Assessment process is stopped, the other JVM grades two saved Attempts,
and Publication remains OPEN. The primary is recreated at its original port.
Concurrent same-key COMPLETE through the two actual processes returns one coherent
CLOSED acknowledgment, with exactly two cutoff audits/results. This is one-host
two-instance evidence, not multi-node partition/failover acceptance.

One deliberately lost acknowledgment is injected **after actual JDBC commit**;
the caller receives sanitized PERSISTENCE_UNAVAILABLE, persisted SUBMITTED identity
remains, and a fresh connection returns the exact durable replay. A separate test
terminates the real PostgreSQL backend after result insertion but before commit;
all partial artifacts roll back and identical retry succeeds. Neither is database
promotion or proof of RPO-zero acknowledged-write durability.

## Confirmed HIGH privacy defects and corrections

The initial HTTPS/root-TRACE regression reproduced a complete actual SERVICE JWT
in Tomcat `org.apache.coyote.http11.Http11InputBuffer` DEBUG request dumps. After
suppressing that category, the next run exposed the generated TLS key-store password
in `org.hibernate.orm.factory` DEBUG configuration dumps. No credential value or
raw offending log is included here. Both are real diagnostic-mode disclosure defects;
ordinary root INFO success did not establish safe DEBUG/TRACE operation.

The narrow fix sets these diagnostic categories to WARN in Identity, Assessment,
Quiz and Classroom; it also suppresses Hibernate bind and Spring JDBC bind diagnostics.
No route, policy, schema, authorization rule, timeout or feature flag was relaxed.
The corrected real HTTPS test verifies minimal eligibility, actual issued SERVICE
credentials, wrong role/client/scope and route/capability denials, no-store, trusted
certificate validation, rejection of an untrusted certificate and rejection of a
trusted certificate at the wrong hostname. It checks credential absence under root
TRACE, then probes each other actual servlet service under root TRACE and checks its
log for the same bearer. All three new topology cases passed after correction.
Explicit category overrides, wiretap, proxy/APM capture and deployed log sinks still
require operational controls and independent review; the fix is not universal DLP.

Earlier test-authoring failures were corrected, not counted as product defects:
an incorrect enum name, stale Docker port mapping after restart, noncanonical empty
fixture IDs, the documented 403 versus an incorrectly expected 503, an expired
one-minute SERVICE probe credential, and closing a
parallel in-process Gateway context that shared Reactor resources. The final fixture
refreshes the owned container's mapping and restores the single Gateway runtime.
SERVICE probe credentials are freshly issued per test/operation; production SERVICE
TTL and all authorization expectations remain unchanged. A first CLOSE/worker barrier
incorrectly expected a SKIP LOCKED worker to wait; the corrected test pauses a worker
after actual result insertion and observes the Teacher waiting on its real lock.

## Migration, backup and capacity observations

The full real chain remains V1→V2→V3→V4→V4.1→V5, including populated historical
upgrade fixtures. A new real V5 drill observes an ungranted ACCESS EXCLUSIVE lock
behind an independent connection, releases the holder and verifies historical
Flyway checksums. First focused observation: total **170.701 ms**, observed lock-wait
interval **87.481 ms**, WAL delta **387712 bytes**, database growth **327680 bytes**.
This tiny fixture is not production migration timing, isolated migration-only WAL,
or peak temporary-disk measurement; other cluster work can affect size/WAL deltas.

Actual pg_dump/pg_restore copies the test schema into another owned test database,
validates Flyway history and compares canonical row fingerprints across **17 tables**,
including snapshots, saved answers, results/digests, notice recipients/cutoffs,
requests, receipts and audits. Immutable-cutoff mutation remains rejected after
restore. An actual PostgreSQL container restart preserves both databases' fingerprints.
Only the generated ephemeral container/database names are targets. The restricted
nonowner-role drill finalizes and replays successfully but cannot replace the clock
function, disable triggers, truncate audit or rewrite immutable results.

The retained 12613-question fixture has a **2097086-byte snapshot**, **909644-byte
answer array**, **909679-byte complete HTTP body** and **1769150-byte redacted delivery**.
Baseline submission measured **3437 ms**; 51-member capture **60.783 ms** and recovery
of 49 remaining finalizations **3256.430 ms**. Production budgets stay 15 seconds per
root transaction, 3000 ms lock/statement and 5/3 seconds JDBC socket/login.
The 2 MiB budget is provisional; no question/payload reduction or truncation occurred.
Final unchanged-fixture samples (n=3, separate sequential Maven executions) were
**3294, 3464 and 3441 ms**: minimum **3294**, median **3441**, maximum **3464 ms**.
The two additional runs each executed both 1024/12613 cases, exit 0 with no
failures/errors/skips; these four repeated executions are not new unique test cases.
No P95/P99 or production SLA is inferred. Reproduce the repeats with:

```sh
for sample in 2 3; do
  bash services/assessment-service/mvnw -B -f services/assessment-service/pom.xml \
    -Dquizopia.build.directory=target/w3-final-verification test \
    -Dtest=StudentCapacityIntegrationTest \
    -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
done
```

Final full-run capture measured **59.210 ms**, recovery of 49 remaining units
**3148.170 ms**. The final migration drill observed **93.878 ms** total,
**41.095 ms** lock-wait interval, **387712 bytes** WAL delta and **327680 bytes**
database growth. There is no production-scale heap/pool, temporary-disk-peak,
WAN, failover or SLA claim.

## Final executed results and author review

The complete portable verification wrapper exited **0** after corrections:

| Module     | Tests | Failures | Errors | Skips |
| ---------- | ----: | -------: | -----: | ----: |
| Assessment |   339 |        0 |      0 |     0 |
| Identity   |   427 |        0 |      0 |     0 |
| Classroom  |   113 |        0 |      0 |     0 |
| Quiz       |   167 |        0 |      0 |     0 |
| Gateway    |   103 |        0 |      0 |     0 |
| Total      |  1149 |        0 |      0 |     0 |

Included subsets: eight new database acceptance cases, 38 existing closure cases,
28 atomic-finalization cases, 37 Student PostgreSQL cases, 27 Identity admission/
recipient cases, **21** actual five-service topology journeys and six separate
Identity admission topology journeys. Spotless and ArchUnit passed. OpenAPI/schema/
route/answer fixtures, local links, protected-file hashes, default-off checks,
changed-scope credential patterns and `git diff --check` passed. Changed Markdown/
YAML passed repository Prettier. No test class was excluded from final verification.

The [59-gate matrix](w3-final-acceptance-matrix.md) records **46 PASS, 0 FAIL,
6 BLOCKED, 5 NOT VERIFIED and 2 NOT APPLICABLE**. The two reproduced HIGH diagnostic
leaks are fixed and retested; no unresolved confirmed HIGH/BLOCKER implementation
finding remains. External HIGH-impact gates are explicitly open. Author review
checked every changed path, fixture cleanup/isolation, logger scope, token renewal,
actual two-process recovery, immutable history/contracts, default flags and truthful
runtime/environment limits. This remains author self-review, not independent review.

## Remaining external gates and recommendation

Local engineering/integration evidence supports independent review; the final
regression passed. Production is NO-GO pending deployed private TLS/trust/network
and exact client provisioning; restricted deployment roles; independent security
review/Leader authorization; production-size migration/load and heap/pool pressure;
multi-node partition and fenced database failover with acknowledged-write durability;
production backup/restore/RPO drills; retention/erasure approval; deployed logs/traces,
metrics access controls and operator alert/runbook acceptance. A true 48-hour monitored
availability soak was not executed; controlled DB-time progression proves boundary
logic only. Existing W3-A source-handoff capacity limits remain.

EC79-02's indefinite ineligible-recipient block remains an accepted limitation, not
a new policy blocker. No force-close, fabricated confirmation, auto-abandon or
remediation bypass exists. Frontend PR #77 and W3-M1/M2 monitoring are separate and
are not declared accepted. No production operations, merge or auto-merge occurred.
