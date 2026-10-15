# W3-M monitoring integration checkpoint and handoff

Status: **BOUNDED INTEGRATION IMPLEMENTED — INDEPENDENT REVIEW REQUIRED; FUNCTIONAL ACCEPTANCE PARTIAL;
PROVIDER/PRIVACY/PRODUCTION ACCEPTANCE NOT READY; PRODUCTION NO-GO.** Updated
2026-10-16 (Asia/Bangkok). No unexecuted test, future CI, approval or release receipt
is asserted.

## Branch and source dependencies

Current branch: `feature/w3m-monitoring-integration` / [Draft PR #91](https://github.com/hhtuann/Quizopia_2.0/pull/91).
The correction campaign incorporates parent #90 through child merge `6f339dc`.
Its dependency chain is [Draft #90](https://github.com/hhtuann/Quizopia_2.0/pull/90)
at `095941b8062e622e604bef457681b3e7ca004a93` →
[Draft #89](https://github.com/hhtuann/Quizopia_2.0/pull/89)
at `3a085b8044065fea1514950069d0eac0bae5e449` →
[Draft #88](https://github.com/hhtuann/Quizopia_2.0/pull/88)
at `9f2f411cc6d5b974928269f8c1c573ef068f339b`.
These parent-to-child merges do not mean a merge to develop. Original develop baseline
remains `e5c4ef3500f1ce40303af161cd6ca74b706c21b2`. The PR descriptions record final
commit/base and observed exact-HEAD CI; this checkpoint does not predict those results.
Historical integration evidence below belongs to the original #91 reviewed HEAD
`d9d68f8b384352a6e580afa4394bec5babad115f`, before the correction campaign.

The [M1](w3m-backend-implementation.md) and
[historical M2](w3m2-backend-implementation.md) checkpoints preserve their executed
facts. Current wire/producer ownership are defined by the
[M1 contract](../specifications/w3-m-proctoring-contract.md),
[authoritative answer integration contract](../specifications/w3m-authoritative-answer-integration.md),
[shared twelve-field schema](../../shared/event-contracts/assessment-answer-persisted.v1.schema.json),
[monitoring OpenAPI](../../services/proctoring-service/contracts/monitoring.openapi.yaml)
and [M2 media contract](../specifications/w3-m2-media-contract.md).

## Functional integration additions

Teacher discovery is implemented as exact bodyless/query-free
`GET /api/proctoring/monitoring/sessions/by-attempt/{attemptId}`. Current issued/
admitted TEACHER and owning Publication/current Classroom authority are checked
before local session existence is revealed; return the existing Session or masked
404, never create one. An authorized Teacher can take Attempt IDs from existing
Assessment Teacher results, look up sessions and then request their own media grants.
This resolves the known-session-ID backend gap without introducing bulk enumeration,
client ownership claims or a broader Classroom roster API. Actual browser camera/
Teacher grid and provider enforcement remain separate acceptance.

Assessment additive V6 captures sanitized answer-persistence facts into a transactional
outbox without changing six existing migrations, answer repository/finalizer or public
OpenAPI. Independent confirmed publisher work delivers to dedicated private RabbitMQ.
Proctoring additive V3 durably receives/deduplicates/quarantines metadata before ACK,
waits for existing sessions and attaches `ANSWER_PERSISTED` history with distinct
`ASSESSMENT_AUTHORITY`/revision/hash guards. Browser answer reports remain untrusted.
Old evidence hashes stay compatible; no answer content or digest is copied.

The fact describes persistence, including server normalization/complete SUBMIT, not
necessarily a Student action or timely SAVE. Actual occurredAt may be after deadline;
post-END attachment does not reopen monitoring or extend Attempt/media entitlement.
No broker I/O occurs in answer transactions. Local database capture failure rolls
back coherently; unavailable Rabbit/Proctoring does not run on the authoritative
answer path. Capture/publisher/consumer and real-learner enablement default OFF.

## Current correction campaign and verification boundary

The [correction evidence](w3m-correction-evidence.md) records fresh H1/M1/M2
reproductions, corrections, executed commands and detailed limitations. Current
cumulative local results are:

| Check                       | Executed result                                               | Limit                                                                                                                                                                            |
| --------------------------- | ------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Focused correction topology | 10 journeys PASS; zero failures/errors/skips, 98.027 seconds. | Actual Gateway, Identity, Classroom, Assessment and Proctoring; four owned PostgreSQL 17 databases, Redis 8.2, RabbitMQ 4.1 and owned LiveKit v1.9.0, with Identity-issued JWTs. |
| Full Assessment verify      | 388 tests PASS.                                               | Local module evidence, not production acceptance.                                                                                                                                |
| Full Proctoring verify      | 229 tests PASS.                                               | Local module evidence, not Cloud/browser acceptance.                                                                                                                             |
| Full Gateway verify         | 119 tests PASS; zero failures/errors/skips.                   | Includes all ten correction journeys and the unchanged Publication topology suite; exact-HEAD CI is recorded separately in the PR.                                               |

The three added journeys cover live create/renew after target membership loss;
target account disable, stale Redis and the original Attempt-start revocation fence;
and Classroom database pause. That pause also affects Teacher assignment authority,
so it does not isolate a target-only dependency outage. Target-only outage and
terminal/deadline no-live cases have direct module proof, including explicit remote
doubles; they are not newly executed real-topology assertions. The synthetic
`CLOUD_STRICT` fixture is not Cloud qualification or real-browser camera/grid proof.
Historical totals below do not certify the corrected cumulative head or latest CI.

Public API routes and field inventory remain unchanged. Browser Event numeric fields
require integral JSON tokens within their declared bounds; fractional, decimal and
exponent forms are rejected. `observedAt` accepts a valid RFC3339 timestamp string or
permitted null, never a numeric epoch value. Rejected input is sanitized and mutation-free.

Media create/renew now require explicit private `LIVE_MEDIA` authority before and
after provider I/O. Teacher live authority additionally checks the bound Student's
current Identity eligibility and Classroom membership. Historical evidence/status
access uses `HISTORY`, with current caller/ownership checks and durable access audits,
without requiring the target to remain eligible for live media. Historical authority
cannot authorize a grant. These checks do not claim atomic cross-service revocation.

LiveKit administrative bearers now have a 30-second TTL and only the SDK-supported
privileges required by each operation. Provider grants retain documented room-create
and room-administration granularity limits; bounded administrative credentials do not
prove Cloud token revocation, refreshed-token safety or hard-outage enforcement.
No independent human Security approval or production authorization is supplied.

## Historical integration evidence and limits — original d9 baseline

The following executed local results and static checks are historical evidence for
original #91 HEAD `d9d68f8b384352a6e580afa4394bec5babad115f`. They are not fresh
correction-campaign results. Recorded test runs had zero failures, errors or skips:

| Check                                       | Actual result                                                                                | Limit                                                                                                                |
| ------------------------------------------- | -------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| Full Assessment Maven verify                | 378 tests PASS, including 26 new outbox/publisher/configuration/shutdown tests.              | Local PostgreSQL 17/Rabbit fixtures, not target-scale migration or deployed broker HA.                               |
| Full Proctoring Maven verify                | 185 tests PASS, including strict event/inbox/consumer and Teacher lookup.                    | Before one final logging regression; default-disabled real-learner acceptance remains separate.                      |
| Full Gateway Maven verify                   | 116 tests PASS, including seven new five-service topology journeys.                          | Synthetic independent databases, Redis and Rabbit; no deployed private TLS or Cloud camera/grid certification.       |
| Final Proctoring logging verification       | Eight `MonitoringDefaultDisabledHttpTest` tests PASS after suppressing raw AMQP diagnostics. | Historical focused verify after the 185-test full suite; not a cumulative correction run.                            |
| Focused seven-journey topology verification | Seven journeys PASS independently, then included in full Gateway verify.                     | Five business services plus Redis/Rabbit; four owned PostgreSQL databases, real Identity-issued USER/SERVICE tokens. |

The seven journeys exercise ownership/current authority, original USER signature and
SERVICE substitution rejection; real SAVE/SUBMIT/ABANDON/deadline reconciliation;
PostgreSQL pause and Redis equality fail-closed behavior; and authoritative Assessment
fingerprints before/after monitoring operations. Owned processes/containers are
cleaned up without terminating unrelated infrastructure. Real SAVE/SUBMIT naturally
mutates Assessment; the fingerprint guarantee concerns monitoring's noninterference,
not a claim that all answer operations leave data unchanged.

The added Rabbit journey exercises actual HTTP SAVE → transactional V6 outbox →
confirmed persistent Rabbit publication → durable Proctoring inbox → Teacher HTTP
timeline. It verifies waiting before Session creation, duplicate SAVE, broker pause
and recovery, and post-SUBMIT/END attachment without resurrection or Assessment
mutation. Broker-verified publisher identity and exact twelve-field metadata are
checked independently through an operator-only synthetic audit queue.

Executed commands from repository root (Java 21, Docker/Testcontainers):

```bash
export MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven
export MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2'
for module in services/assessment-service services/proctoring-service gateway; do
  bash "$module/mvnw" -B -f "$module/pom.xml" \
    -Dquizopia.build.directory=target/w3m-integration-final spotless:apply verify \
    -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
done
bash services/proctoring-service/mvnw -B -f services/proctoring-service/pom.xml \
  -Dquizopia.build.directory=target/w3m-integration-log-check spotless:apply verify \
  -Dtest=MonitoringDefaultDisabledHttpTest \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

The module invocations were executed separately with those arguments, rather than
as one loop. Surefire XML resides in each named build directory; nonsecret local
logs are `/tmp/w3m-integration-{assessment,proctoring,gateway}-full.log` and
`/tmp/w3m-integration-log-check.log`. These local artifacts are not durable external
release receipts. The focused topology reports are under
`gateway/target/proctoring-topology-verification/surefire-reports`.

Failures found and corrected before those passing checks: three old migration-count
assertions were updated for additive V6 (historical V5 writer-drain test explicitly
stays pinned to V5); strict JSON decoding now rejects fractional integers and numeric
revision coercion; the topology fixture waits for authenticated Rabbit management
readiness before provisioning. Original HTTP timeouts and assertions remain intact.
Default-OFF JDBC initialization now defeats inherited capture settings even after
a trailing SQL comment. Publisher shutdown prevents new broker work and releases
remaining claimed leases. These are engineering fixes, not evidence of external
recovery or scale acceptance.

Focused Codex review found no remaining HIGH/MEDIUM defect; it independently compared
the eight previous migrations plus answer/finalizer/public-API bytes with `4e1aa7d`.
Its Rabbit diagnostic-logging finding was corrected and tested; timeline-hash wording
was narrowed to distinguish the full inbox digest. Delegated testing/documentation
agents reached their usage limit; the primary agent completed the final fixes,
validation and handoff. This review is not outside-team Security/Privacy approval.

Final local static checks also passed:

- Repository Prettier check for all fourteen changed Markdown/YAML/JSON files.
- OpenAPI structural validation: monitoring six operations, media four, private
  Assessment authority one. Compiled Gateway inventory matched all ten public
  Proctoring operations and rejected four unsupported/private/malformed cases.
- Draft 2020-12 JSON Schema validation: one valid and twenty-two invalid fixtures.
  Runtime tests cover temporal, duplicate-field and signed-64-bit constraints beyond
  structural schema validation.
- Eighty protected files remained byte-identical to `4e1aa7d`, including all existing
  Assessment Java, eight migrations, public API, unchanged capacity fixture, accepted
  ADRs and release register/validator/tests. Twenty-five feature/admission switches
  across Gateway/Assessment/Proctoring retain false defaults.
- Ninety-six local Markdown link targets exist; fragment anchors were not validated.
  Scoped private-key/access-key/literal-JWT scan and manual synthetic-credential
  review found no committed credential. `git diff --check` passed.
- `docker compose config --quiet` passed.
- `PYTHONPATH=gateway/target/w3a-evidence/openapi-python python3 -B
services/assessment-service/contracts/validate-w3-production-release.py` passed;
  the sibling `test-w3-production-release.py` command passed all sixty safety tests.

OpenAPI/schema/protected/link/flag/credential checks used the noncommitted local
script `/tmp/quizopia-w3m-integration-audit.py`, with the same existing Python package
directory. Compiled route checks used `/tmp/ProctoringRouteAudit.java` and the verified
Gateway classes. These temporary helpers are local review evidence, not installed
production dependencies or a durable release acceptance tool. The first helper run
stopped at an incorrect expected switch count (29); the check was corrected to assert
the three required new switches and inspect all 25 actual switches. No application
setting was changed to satisfy that audit.

Historical exact M2 HEAD `4e1aa7d` has observed successful
[Repository validation 38582702077](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38582702077)
and [Backend CI 38582702186](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38582702186)
after the failed Gateway job was rerun. The initial existing closure/inbox journey
hit its unchanged ten-second HTTP timeout; no established root cause or source fix
is claimed. Unchanged local 21 topology tests passed, including that method in
1.151 seconds, with original timeouts/assertions retained. This is historical
M2 CI evidence, not exact-HEAD integration CI or proof the failure can never recur.

## Frontend handoff

Use [the existing integration handoff](../development/w3-frontend-backend-integration-handoff.md)
for frozen Assessment routes and PR #77's independent auth executor. Add current
Teacher lookup/Session binding, then authorized Teacher timeline/media-grant requests.
The `Evidence` projection now includes nullable decimal-string `assessmentRevision`:
non-null only for server-only `ANSWER_PERSISTED`/`ASSESSMENT_AUTHORITY`, null for old
browser/server rows. Render provenance accurately; neither browser observations nor
persistence facts prove misconduct. Do not send the server-only type through event
POST or copy token/answer content into browser logs.

No answer schema, grading/result contract or existing public Assessment API changes.
No frontend PR #77 merge/review is performed. No actual browser camera/grid PASS,
Cloud qualification, hard-outage media cutoff or lawful collection is inferred from
backend synthetic integration. The media app lease remains distinct from SFU lifetime.

## External gates and operator readiness

All eleven Assessment release gates remain unchanged: **6 BLOCKED / 5 NOT VERIFIED**.
Use the [single register](../development/w3-production-evidence-register.yaml) and
[existing gate handoff](../development/w3-production-evidence-handoff.md); no actual
DRI, external receipt or approval is added. Assessment nomination remains `10615f4...`;
formal RC remains null and a combined monitoring release needs explicit new scope/
artifact/configuration decisions and revalidation.

PROCTOR-01/07/11/12, provider strict revocation/clock/refreshed-token/active-session
hard-outage enforcement, real private broker/TLS/ACL provenance and qualified outside
Security/Privacy acceptance remain open. Dedicated vhost/exact exchange/queue ACLs,
trusted TLS, broker user-ID validation and audited poison recovery need actual
operator provisioning and authorization. One-node Rabbit quorum fixtures are not
production HA. No cloud operation, destructive fault, backup/restore, long real soak,
branch-protection change or feature activation is authorized by this checkpoint.

## Safe exact resumption

1. Inspect status/branch/log/fetched develop and preserve all uncommitted/unrelated
   work. Resume #91 on `feature/w3m-monitoring-integration` with the corrected parent
   SHAs above and explicit #90 → #89 → #88 dependency chain.
2. Retrieve the integration Draft PR and verify remote HEAD, explicit dependency base
   and actual CI. Consult the correction evidence for fresh cumulative H1/M1/M2 results
   before reusing historical PASS claims. Review shared schema, Assessment V6/capture/publisher and Proctoring
   V3/inbox/consumer/Teacher lookup/OpenAPI against the recorded evidence.
3. Obtain independent implementation review of atomic capture, broker provenance,
   durable ACK, conflict quarantine, historical attachment and default-off boundaries.
   Retain protected migrations, answer/finalizer/public API and existing hash bytes.
4. After authorized review, the Leader decides merges in dependency order. Preserve
   Draft status until actual gates pass; no agent merge/auto-merge or production GO.
5. Handoff real frontend grid E2E and provider/privacy/operator acceptance separately.
   External blockers do not prevent remaining safe repository engineering. Do not
   substitute configuration references, Codex self-review or green CI for actual GO.

**Functional integrated delivery remains PARTIAL; engineering/release acceptance
NOT READY; eleven release gates open; PRODUCTION NO-GO.**
