# W3-M1 historical implementation checkpoint and resumption

Historical scope: M1 increment at `430dbdf` / Draft PR #89. The
[current M2 checkpoint](w3m2-backend-implementation.md) records subsequent dependent
media work; this file's M2 absence and test observations describe M1's scope, not
the latest working branch. Preserve the historical executed evidence below.

Status at that checkpoint: **M1 POLICY-NEUTRAL CORE IMPLEMENTED, DEFAULT DISABLED; functional M1
still PARTIAL (authoritative answer-change adapter pending); M2 NOT IMPLEMENTED.
Engineering acceptance NOT READY; PRODUCTION NO-GO.** Local verification below is
executed evidence, not independent approval or deployed release acceptance.

## Baseline, branch and delivery boundary

Starting merged develop: `e5c4ef3500f1ce40303af161cd6ca74b706c21b2` (PR #87).
Current bounded M1 branch: `feature/w3m1-proctoring-activity-evidence` in the original
checkout. Its base is `feature/w3-proctoring-assessment-authority`, not develop:
[Draft PR #88](https://github.com/hhtuann/Quizopia_2.0/pull/88), authority commit
`a0b1e393ec0826fa543ad9bee1b115c555640725`, separately targets develop `e5c4ef35...`.
That private capability is unmerged; M1 must declare the dependency. Final M1
commit/PR/exact-HEAD CI belong in its PR/report after push; no future self-SHA is
invented here. Do not create worktrees, merge or force-push.

Assessment W3-A/B/C through PR #84 is implemented and merged, with separate release
governance through PR #87. Its
[implemented OpenAPI](../../services/assessment-service/contracts/publications.openapi.yaml)
and [W3-FINAL evidence](w3-final-assessment-acceptance.md) supersede old scaffold-only
status prose. The [M1 contract](../specifications/w3-m-proctoring-contract.md)
freezes policy-neutral Activity Evidence mechanics and unresolved policy boundaries.
M1 core source/API/persistence/security now has executed local checks below; its
actual Proctoring-wide external integration, authoritative answer adapter and
independent acceptance are separate. M2 requires its own bounded dependent branch/PR.

The nominated Assessment RC remains
`10615f4a9cb73ceb4bce7728f23bf210d0bc99a2`; it excludes later monitoring work.
The register retains `release_candidate_sha: null`. A combined Assessment/Proctoring
release requires explicit new scope/artifact/configuration decisions and revalidation.
No source HEAD, local switch or synthetic test supplies that approval.

## M1 source and contract checkpoint

Owning service: Proctoring, independently buildable Java/Maven with its own
PostgreSQL/Flyway persistence. Five exact `/api/proctoring/monitoring/sessions`
operations cover session create/status, Student event append, authorized Teacher
timeline and reconciliation. Gateway forwards identical paths without rewriting;
no wildcard authenticated fallback is authorized.

Assessment adds only the narrow private
`POST /internal/proctoring/attempts/{attemptId}/authority`, scope
`assessment.proctoring-authority.read`, exact configured Proctoring caller plus
original USER evidence. It returns minimal actor/role/Attempt/Publication/Classroom/
Student/deadline/eligibility/time bindings; no answers, keys, locator or grade.
Assessment delegates current Classroom membership/Teacher checks and repeats current
resource binding, without cross-service SQL, new Assessment migrations or grading changes.

Current core implements real append-only evidence/session/audit persistence,
durable replay/server-ordinal pagination, local irreversible END, strict metadata
shape/budgets, independent JWT/LD-05 and bounded private authority, unconditional
production closure for M1, retention-aware fields and owner-controlled manual cleanup.
No cleanup scheduler or arbitrary retention policy is installed. Exact
DTO/type/replay rules are in the contract. Browser answer observations remain
untrusted; the authoritative Assessment answer-change outbox/consumer adapter is
not delivered by this initial increment and must not be implied by browser events.

Authority is a bounded remote snapshot, not a distributed transaction with
Classroom/Identity/Assessment. Test stale/malformed/outage responses, expiry between
checks, concurrent local END/append and terminal replay. Status/reconciliation may
end only Proctoring monitoring; it never submits, abandons or alters Assessment.

## Evidence and verification boundary

Executed local verification: Java 21, owned synthetic PostgreSQL/Testcontainers and
the existing separate Maven projects. Fresh report totals were re-read after the
successful runs: **555 tests, 0 failures, 0 errors, 0 skips**.

| Module     | Executed result                                            | Local report directory                                                    |
| ---------- | ---------------------------------------------------------- | ------------------------------------------------------------------------- |
| Proctoring | 97 tests across 9 report classes; Spotless/package PASS.   | `services/proctoring-service/target/w3m1-verification/surefire-reports`   |
| Assessment | 352 tests across 25 report classes; Spotless/package PASS. | `services/assessment-service/target/w3m-authority-final/surefire-reports` |
| Gateway    | 106 tests across 16 report classes; Spotless/package PASS. | `gateway/target/w3m1-final/surefire-reports`                              |

Actual command forms executed from the repository root (each is a separate module,
with the same bounded local JVM settings):

```bash
env MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash services/proctoring-service/mvnw -B -f services/proctoring-service/pom.xml -Dquizopia.build.directory=target/w3m1-verification spotless:check verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
env MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash services/assessment-service/mvnw -B -f services/assessment-service/pom.xml -Dquizopia.build.directory=target/w3m-authority-final spotless:check verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
env MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' bash gateway/mvnw -B -f gateway/pom.xml -Dquizopia.build.directory=target/w3m1-final spotless:apply verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

Coverage includes real PostgreSQL constraints/concurrency/append/replay/ordering/
hash/audit/manual-purge guards, actual HTTP validation/default-off behavior and
bounded authority transport negative cases. New authority/Proctoring cases inject
Identity/Classroom outcomes or controlled authority HTTP responses where stated in
tests; they are not proof of a fully provisioned real Proctoring + Identity +
Classroom private-TLS topology. Existing Gateway real Assessment journeys remain
in its full suite. The new media/LiveKit implementation is absent, so no M2 test
PASS or real browser camera-grid acceptance is claimed.

Root static audit also executed: scope/credential/syntax/local references, Compose
configuration, 23 off flags, 16 protected files including all six Assessment
migrations/public API/answer-finalization/ADRs/matrix/register validator/tests, and
release-register validation plus 60 safety tests PASS. These are static/synthetic
checks, not external release receipts. Final exact-HEAD CI and independent review
remain pending until retrieved after push; earlier failures/fixes and final run URLs
belong in the PR/report. Do not relabel historical Assessment evidence as new M2
coverage or local tests as deployed operational acceptance.

The [isolated LD-05 runtime helper](../../scripts/local/ld05-real-executor-runtime.py)
has its separate [frontend integration handoff](../development/w3-frontend-backend-integration-handoff.md):
actual early runtime checks include 17 backend checks, 24 Assessment route/OpenAPI
parity checks and one real-browser executor PASS as recorded there. These do not
constitute frontend PR #77 independent acceptance or prove the new Proctoring/media
journey. Preserve the exact runtime/revision and handoff limitations. The owned
browser-process cleanup regression also passed two real subprocess cases, covering
leader exit/detached children and preservation of unrelated processes. Run
`python3 -B scripts/local/test-ld05-runtime.py`; this does not rerun the browser
scenario or claim new deployment evidence.

## M2 and remaining functional work

On `feature/w3m2-proctoring-livekit`, explicitly identify an unmerged M1 base/dependency
rather than pretending M1 exists on develop. Freeze actual official SDK/provider
room/token/participant mechanisms; implement scoped camera-only Student publishing,
no Student subscription, authorized Teacher subscription, terminal/revocation/reconnect
and failure isolation with actual permission tests. A later integration branch may
exercise an owned isolated LiveKit server after prerequisites are available.

PROCTOR-01 retention, PROCTOR-07 deployment, PROCTOR-11 refusal/loss/accommodation
outcomes and PROCTOR-12 privacy/accessibility/security approvals remain external
or product/security gates. Technical media grant/revocation mechanics do not decide
academic outcomes. No recording/microphone/screen-sharing/AI or automatic sanctions.
M1 alone is not an approved Activity-only production mode; the accepted bundle must
be implemented/reviewed before real-learner activation.

### Verified LiveKit design constraint for the next increment

Read-only local inspection found `livekit/livekit-server:v1.9.0`; the existing
container was not changed. Current [official token documentation](https://docs.livekit.io/frontends/reference/tokens-grants/)
states that expiry governs initial connection, the server refreshes connected-client
tokens, and self-hosted removal/permission updates do not invalidate an existing
join token. Therefore a short token TTL plus `RemoveParticipant` alone must not be
claimed to fence all reconnects after eligibility loss. Provider-specific revocation
and restart/partition behavior need explicit security design and actual negative tests.
This finding does not select LiveKit Cloud or authorize a deployment.

The [official JVM server SDK](https://github.com/livekit/server-sdk-kotlin) currently
publishes `io.livekit:livekit-server:0.16.0`; compatibility with the installed server
and strict revocation contract has not been tested in this increment. Use its actual
room/token/participant APIs, bounded private transport and camera-only grants in the
separate M2 branch. No SDK dependency, token issuer, room worker or media acceptance
is claimed by this read-only inspection.

## Eleven unchanged Assessment release gates

The [schema-2 register](../development/w3-production-evidence-register.yaml) is the
single source for actual evidence/approvals, original severities and dependencies.
Every current state below equals its inherited state; no new external receipt or
approval is supplied by this implementation. Actual named DRIs remain unassigned;
coordination is four Dev 1 roles/seven Leader roles, supplied as the same proposed
human `hhtuann`, not independent Security appointment.

| Gate        | Original = current | Coordination / required next operator evidence                                                                                                                                                             |
| ----------- | ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| BUILD-02    | NOT VERIFIED       | Dev 1; repository owner verifies nominated exact-SHA required checks/effective protections and formal artifact scope. CI success alone is insufficient.                                                    |
| NOTICE-04   | NOT VERIFIED       | Leader + Operations/Security; authorized actual authenticated ≥48h strict continuous eligible observation, approved interruption/completeness and full timeline; no real soak executed here.               |
| RECOVERY-05 | BLOCKED            | Leader + Platform/DBA/Security; provider-specific synchronous-durability/fencing design, accepted failure model and authorized multi-node zero-acknowledged-write-loss drill; no fault injection here.     |
| DB-05       | NOT VERIFIED       | Dev 1 + DBA; approved representative target data/thresholds, fresh/upgrade V5/drain/locks/WAL/disk/temp/integrity/roll-forward evidence; local tiny fixtures cannot substitute.                            |
| DB-08       | BLOCKED            | Leader + DBA/SRE/Security; encrypted authorized backup/isolated restore, separate Assessment/Identity integrity and measured recovery baseline; RTO still undecided.                                       |
| TLS-02      | BLOCKED            | Leader + Platform/Security; actual private certificate/hostname/OAuth/JWKS/client-scope/network negative tests, no public internal routes.                                                                 |
| CAPACITY-04 | NOT VERIFIED       | Dev 1 + Platform; authorized 100-concurrent-Student baseline with unchanged 12,613-question fixture, metrics/integrity; later quantitative criteria freeze and formal acceptance.                          |
| OBSERVE-02  | BLOCKED            | Leader + Operations/Security; actual restricted sinks/redaction, approved thresholds, delivered alerts and named escalation exercise.                                                                      |
| PRIVACY-02  | BLOCKED            | Leader + independent Privacy/Security; actual nine-category policy/periods/access/erasure/backup decisions and matching controls; monitoring privacy remains separately reviewed.                          |
| DEPLOY-02   | NOT VERIFIED       | Dev 1 + Platform; authorized exact-artifact staging drain/migrate/order/inbox/smoke/restart/disable/roll-forward, preserving cutoff/history rights.                                                        |
| APPROVAL-01 | BLOCKED            | Leader + qualified outside-team Security; actual independently retrievable gate receipts and distinct real-person Security/Leader approval of exact environment/artifacts, named incident/rollback owners. |

The [managed HA provider assessment](../development/w3-managed-ha-provider-assessment.md)
compares official AWS RDS single-standby Multi-AZ and Google Cloud SQL regional HA
mechanisms, qualification gaps and required authorized fault evidence. It is
read-only research, not provider selection, actual failover or gate acceptance.

No cloud provider is selected, no chargeable resources/credentials are used and no
external acceptance operation is authorized. Follow the
[operator readiness pack](../development/w3-production-release-approval-operator-readiness.md)
and [gate handoff](../development/w3-production-evidence-handoff.md) for specific
missing permissions, criteria, operators and evidence. Strict-soak observation
restart never changes real confirmation/cutoff or combines invalid fragments into PASS.

## Safe resumption

1. Inspect `git status`, current branch/log, remote develop and all local work;
   preserve unfinished implementation/unrelated edits. Resume the existing M1 branch.
2. Read this checkpoint, the M1 contract, current controllers/persistence/security/
   OpenAPI and actual test reports. Complete engineering defects rather than treating
   policy gates as a reason to stop unrelated code.
3. Run focused real persistence/authorization/integration checks, fix defects and
   record executed results. Keep defaults off; do not enable real learners or invent
   retention/refusal policy. Update this checkpoint only from actual evidence.
4. Finish the bounded M1 diff, scoped self-review, commit/push and one Draft PR with
   actual exact-HEAD CI. Independent review/Leader merge remain separate.
5. Continue M2 on an explicitly dependent branch/PR and preserve the approved bundle.
   Supply Dev 2 exact implemented API/flags/synthetic runtime and known limits early.
6. External release work requires separately documented target/provider/operator,
   artifact/configuration, synthetic data, commands/window/stop/recovery and independent
   review authority. Without them complete preparation, leave gates open and continue
   repository engineering; no background/future success is claimed.

**Functional M1/M2 backend delivery is PARTIAL; engineering acceptance NOT READY;
11 release gates remain open (6 BLOCKED / 5 NOT VERIFIED); PRODUCTION NO-GO.**
