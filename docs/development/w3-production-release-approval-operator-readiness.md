# W3 production release approval and operator readiness

## 1. Status and decision provenance

**APPROVAL READINESS PACK — PRODUCTION NO-GO.** Recorded 2026-10-14
(Asia/Bangkok), from requester-provided coordinator selections in decision rounds
4 and 5. These selections are not independently authenticated external approvals,
appointment/delegation certificates or execution permissions. No operation has
been authorized or executed by this pack; no release receipt or sign-off is created.

The [decision pack](w3-production-release-decision-pack.md) retains rounds 1–3.
This document records subsequent selections and distinguishes proposed identities,
formal appointment, feasibility evaluation, staging authorization, acceptance and
final GO. The [register](w3-production-evidence-register.yaml) remains authoritative
for gate states and evidence; [acceptance](w3-production-release-acceptance.md) and
[handoff](w3-production-evidence-handoff.md) retain the governing boundaries.

| Decision | Exact selection                                                                                                       | Current boundary                                                                            |
| -------- | --------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| D4-01    | Preserve `10615f4a9cb73ceb4bce7728f23bf210d0bc99a2` as nominated/selected candidate.                                  | No formal candidate/artifact approval.                                                      |
| D4-02    | Dev 1 — Backend and Leader are the SAME supplied human, GitHub identity `hhtuann`.                                    | Proposed identity only; no authenticated appointment or independent approval.               |
| D4-03    | DEDICATED MANAGED CLOUD HA STAGING                                                                                    | Direction selected; provider, provisioning and execution authorization absent.              |
| D4-04    | MANAGED POSTGRESQL HA WITH VERIFIED SYNCHRONOUS DURABILITY AND FENCING                                                | Provider-specific feasibility/design and actual zero-loss/fencing evidence absent.          |
| D5-01    | Option A: Dev 1 and Leader are one person, `hhtuann`.                                                                 | Two coordination roles cannot count as two independent human approvals.                     |
| D5-02    | Option D: measure approved staging recovery first, then select and formally approve quantitative RTO.                 | RTO UNDECIDED; no recovery-time commitment or acceptance PASS.                              |
| D5-03    | Option C: approved 100-concurrent-Student baseline first, then quantitative SLO approval.                             | No numeric SLO, error budget, request rate or benchmark result.                             |
| D5-04    | Option A: STRICT CONTINUOUS ELIGIBLE OBSERVATION, at least 48 genuine elapsed hours.                                  | Formal interruption/completeness criteria and execution remain pending; no waiver selected. |
| D5-05    | Option A: Leader drafts privacy/retention/erasure/access policy; qualified independent Privacy/Security evaluates it. | No approved periods, legal bases, deletion schedule or backup-erasure rule.                 |

## 2. Baseline and deliberately preserved RC SHA

Source baseline: PR #86 merge `e3c698fb489b0f78867ef7b7a59028a3445250b7`.
The nominated candidate deliberately remains
`10615f4a9cb73ceb4bce7728f23bf210d0bc99a2`, predating this governance merge.
Latest develop, CI success and governance edits do not replace it or approve artifacts.
The register retains **`release_candidate_sha: null`** until actual formal evidence
exists. Historical matrix pinning at `e615764...` remains unchanged.

Required candidate approval evidence must bind exact source SHA, actual service/
container image digests, sanitized runtime configuration fingerprints, target
environment, required-check/effective branch-protection policy, authorized approver,
real approval reference and time. All actual values/approval are pending; the
[existing nomination CI observations](w3-production-release-decision-pack.md#round-1--rc-nomination-and-coordination-model)
are supporting observations only. Any change to SHA/artifacts/security configuration
requires gate-specific revalidation; no automatic approval transfer.

## 3. Single-human Dev 1/Leader responsibility mapping

The requester reports GitHub identity **`hhtuann`** holds both roles, as one person.
This is a proposed identity linkage, not verified appointment. The current schema-2
role mapping remains exactly:

| Coordination role | Gates                                                                      | Supplied proposed human |
| ----------------- | -------------------------------------------------------------------------- | ----------------------- |
| `DEV1_BACKEND`    | BUILD-02, DB-05, CAPACITY-04, DEPLOY-02                                    | `hhtuann`               |
| `LEADER`          | RECOVERY-05, DB-08, TLS-02, OBSERVE-02, NOTICE-04, PRIVACY-02, APPROVAL-01 | The same `hhtuann`      |

One proposed human therefore coordinates all eleven gates through two roles.
No backend release gate is assigned to Dev 2. Each actual register DRI remains
**UNASSIGNED — LEADER ASSIGNMENT REQUIRED**, `ownership_status: PROPOSED`.
No schema, receipt, role ownership or appointment is changed. Specialist-team
proposals remain separate from coordination and actual operator permissions.

## 4. Separation of duties and reviewer requirements

Distinguish accountable DRI, executor/operator, gate approver, independent Security
reviewer and Leader GO authority. Shared coordination cannot supply independent
review. APPROVAL-01 requires different real people for Leader and Security; the
outside Security reviewer must be identifiable, suitably qualified, explicitly
appointed/authorized and outside the current Leader/Dev 1/Dev 2 team.

Existing gate-specific independent reviewer/approver rules and normalized identity
validation remain mandatory. A Leader may be an accountable governance DRI; this
does not permit self-approval of independent Security work. Role titles or alternate
accounts do not prove different people. Validator normalization catches trivial
case/whitespace variants, while real-person identity/independence still needs
independent verification. No outside reviewer is invented or appointed here.

## 5. Managed HA staging architecture requirements

D4-03 selects dedicated managed-cloud HA staging, not a vendor or provisioning plan.
Platform must propose isolated environment/network boundaries, separate service
identities/credentials, separately owned Assessment and Identity PostgreSQL authority,
private DNS/TLS/trust/OAuth/JWKS, synthetic or specifically authorized sanitized data,
managed PostgreSQL HA, multi-node fault/recovery capability, restricted operators,
encrypted backups and safe actual telemetry. No production credentials or data use
is authorized.

The proposal must identify provider/region/fault domains, database/service topology,
resource cost/budget, trusted secret/key custody, restricted runtime versus migration
roles, ingress denial of `/internal/**`, exact SERVICE client/scope provisioning,
Identity/Redis authority reconstruction and fail-closed checks, and supported fault/
restore mechanisms. It needs environment-specific approval before provisioning or
operations; labels such as “HA” or “Multi-AZ” do not establish durability.

## 6. Provider-specific zero acknowledged-write loss evaluation

D4-04 selects managed PostgreSQL HA with verified synchronous durability and fencing.
The existing objective is zero loss of acknowledged committed Assessment saved
answers, results, receipts, Publication/closure transitions, notice confirmations/
cutoffs and Identity authoritative security changes. Asynchronous-only failover
cannot silently replace it. Platform/DBA must supply independent reviewable answers:

| Evaluation                   | Required design/evidence                                                                                                                                                              |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Commit acknowledgment        | Exact provider/version/settings and success semantics; where WAL is durably persisted before acknowledgment; synchronous standby count/quorum and failure behavior.                   |
| WAL/replication              | Durable replication guarantees across specified fault domains; lag, missing standby and storage/node failures; no reliance on a marketing availability label.                         |
| Fencing/promotion            | Actual fencing authority/ordering, promotion criteria and prevention of simultaneous writable primaries; prove old-primary/old-binary writes are rejected after reconnect.            |
| Partitions/uncertain commits | Network partition/split-brain cases and lost acknowledgment; replay exact durable identities without assuming socket failure implies rollback or causing duplicate finalization.      |
| Recovery/data integrity      | Pre/post acknowledged-write manifests for both owned databases, answers/results/receipts/notices/audits and cutoffs; supported recovery paths and corruption/backup limits.           |
| Identity authority           | Trusted current PostgreSQL primary facts plus complete Redis equality; reconstruction before admission, stale authority rejected and no JWT-only fallback/positive eligibility cache. |
| Trade-offs                   | Availability, acknowledgment latency, maintenance/fault behavior and cost under the same objective; explicit failure assumptions and independent validation.                          |

Record provider documentation/configuration provenance and reviewed test design.
Any unproven failure case remains open. Subsequent bounded fault rehearsal needs
separate authorization; no failover, backup/restore or fault injection occurs here.

## 7. RTO feasibility and formal acceptance sequence

D5-02 selects **measure first, then approve target**. RTO remains **UNDECIDED**;
there is no 30-, 60- or 120-minute commitment.

1. Define scenario classes, failure boundary, service-restoration scope, provider
   recovery assumptions, Redis/Identity reconstruction, admission/business availability
   checks, topology/resources, operator readiness and expected user impact.
2. Propose explicit measurement start/end events for each scenario, including
   fault detection, fencing/promotion, database readiness, current authority convergence
   and actual authenticated application restoration. Distinguish component readiness
   from end-to-end recovery; obtain review of the measurement protocol.
3. Obtain separate target-specific feasibility authorization: identified operators,
   synthetic dataset, reviewed provider commands, bounded window, resource limits,
   stop conditions, telemetry and safe recovery. Such permission is not final GO and
   need not pretend an RTO has already been approved.
4. If authorized later, execute staging feasibility measurements, retain actual
   timing/failure/uncertainty and integrity evidence, and compare alternatives/cost/
   readiness. Label results **BASELINE — NOT FORMAL RTO ACCEPTANCE**.
5. Use measured information to select a quantitative target with scope, start/end
   semantics, limitations and actual authorized decision reference/time. Then freeze
   criteria and execute separately authorized formal acceptance against them.

An unstated target cannot yield retrospective PASS. Baseline facts may inform the
decision but do not close DB-08/RECOVERY-05 or replace their integrity/security gates.

## 8. Performance baseline and quantitative-SLO freeze

D5-03 selects an approved baseline at **100 concurrent active Students**, not 100
requests per second. No latency percentile, allowed error percentage, request rate,
resource headroom threshold, duration or production SLA is adopted.

Dev 1/Platform must prepare the [existing proposed workload profile](w3-production-release-decision-pack.md#proposed-initial-workload-profile--acceptance-thresholds-pending):
authenticated START/RESUME, revision-safe individual/concurrent SAVE conflicts,
complete SUBMIT, deterministic exactly-once grading/finalization, owner results,
authorized Teacher operations and closure batch interactions. Review realistic
think times/distribution, warm-up/steady/recovery phases, approved synthetic accounts,
target resources, pool/acquisition/CPU/GC/memory/lock/error/retry/recovery telemetry,
actual operation/request rates and safety stop conditions before the baseline.

A bounded staging feasibility baseline may be separately authorized while numeric
acceptance criteria remain undecided. Its resource/time/safety limits must be
approved even though they are not performance SLOs. Record tested SHA/digests/config,
actual parameters and results as **BASELINE — NOT CAPACITY-04 PASS**. Then obtain
actual quantitative SLO/error/headroom approval and freeze criteria before formal
CAPACITY-04 acceptance execution. No retrospective PASS for an unstated target.

Retain the unchanged 12,613-question fixture, complete accepted answers and initial
payload engineering constraints/W3-A handoff limitations. No truncation, duplicate
grading/finalization, reduced integrity or unauthorized timeout increase can make
a baseline acceptable. Staging/provider/secret access remains unauthorised here.

## 9. Strict 48-hour notice-soak protocol

D5-04 selects **STRICT CONTINUOUS ELIGIBLE OBSERVATION**, at least 48 genuine
elapsed hours. No waiver is selected; NOTICE-04 remains NOT VERIFIED. Before
starting, Leader/Security must formally accept the exact eligible-availability,
interruption classification/invalidation, telemetry completeness, stop/restart and
incident-response criteria. No unspecified outage tolerance, probe interval or
telemetry-gap threshold is invented.

1. Independently review the observation protocol and separately authorize the
   bounded staging scenario, synthetic actors, private topology, clocks, probes,
   operator coverage and protected evidence capture. Confirm actual authenticated
   inbox/eligibility readiness; attestation alone is insufficient.
2. Use actual committed PUBLISH and real accessibility CONFIRM. Record authoritative
   `confirmedNoticeAt`, immutable individual `closureCutoffAt = confirmedNoticeAt + 48h`,
   observation bounds, actual clocks and durable evidence. Never advance database
   time, backdate confirmation or use real Student accounts.
3. Collect authenticated accessibility and complete telemetry/outage/exception
   history for a continuous eligible ≥48-hour observation under the formally
   approved criteria. Review cutoff enforcement and Teacher COMPLETE/CLOSED owner
   review under the existing protocol; elapsed time alone never closes a Publication.
4. If an interruption invalidates accepted availability/completeness, stop and
   classify the observation **FAILED/INCOMPLETE**, preserve evidence, investigate
   under the approved incident procedure, remediate and obtain the required restart
   permission. It cannot PASS through aggregation of ineligible fragments.
5. Perform a new eligible observation under the approved procedure. Restarting
   observation never resets or edits actual confirmation, `closureCutoffAt`, prior
   evidence or Student rights. If a new synthetic scenario is needed, separately
   approve it with its own genuine notices and preserve earlier evidence/deadlines.
6. Independent Security retrieves the complete timeline and accepts results only
   against the pre-approved protocol. Missing/failed notice never creates a cutoff;
   ineligible/unverifiable recipients retain the accepted potentially indefinite block.

Do not manufacture deadlines, auto-abandon, administratively force-close or alter
C1–C10. Future exceptions still require a new explicit decision and existing full
waiver authority; no convenience waiver is created by this selection.

## 10. Privacy drafting and independent review checklist

D5-05 retains Leader drafting and requires a qualified independent Privacy/Security
reviewer before policy/control acceptance. Use the
[nine-category checklist](w3-production-release-decision-pack.md#privacy-policy-decision-checklist):
Student answers, Quiz snapshots, results/grades, operation receipts, notification
history, audit records, Redis authority, operational logs and backups.

For every category propose owner/purpose/access, period and review basis, erasure/
anonymization and exceptions, backup/export/restore handling, immutable grade/notice/
cutoff/audit obligations and actual control/evidence mapping. Actual periods, legal
bases, automatic deletion schedules and backup-erasure behavior remain pending
real review/authorization. Retain separate
[monitoring privacy obligations](../product/assessment.md#current-wave-3-assessment-core)
and [open Proctoring/privacy decisions](../open-questions.md#proctoring); this backend
pack does not accept W3-M1/M2. No automatic erasure of authoritative evidence is
introduced. PRIVACY-02 remains BLOCKED.

## 11. Approval matrix and actual evidence requirements

All listed people/authorities require real appointment/authority evidence; the
requester-supplied `hhtuann` linkage alone is not it. Use the
[existing per-gate approvals and receipt contract](w3-production-evidence-register.yaml),
not a new approval schema or fabricated signatures.

| Decision / acceptance               | Accountable coordination                      | Required actual authority/evidence                                                                                                                                                                    |
| ----------------------------------- | --------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Human appointments / delegation     | Leader role, proposed `hhtuann`               | Verified human role linkage, authority/scope, actual appointment/delegation reference/date and incident coverage; register DRI remains unassigned until evidenced.                                    |
| Candidate/artifact / BUILD-02       | Dev 1 role plus Leader formal authority       | Exact SHA/images/config/environment, effective required-check/protection policy, actual CI/provenance and authorized approval/time.                                                                   |
| Staging/provisioning/operator scope | Leader coordination plus Platform specialists | Provider/topology/budget, restricted permissions, data/secret custody, approved target/change window/commands/stop/recovery plan; no permission supplied here.                                        |
| Durability/migration/DR/failover    | Leader recovery/DB-08; Dev 1 DB-05            | Independent Platform/DBA/Security design and actual integrity/fencing/restore evidence; gate-specific approvals and approved RTO for formal time acceptance.                                          |
| RTO / performance baseline → target | Leader RTO; Dev 1 CAPACITY-04                 | Separate feasibility authorization and measured baselines, subsequent actual target decision/freeze, then actual formal acceptance receipt and required independent approver.                         |
| Notice observation                  | Leader NOTICE-04                              | Formal interruption/completeness protocol, bounded scenario authorization, continuous real ≥48h evidence and independent Security approval; no cutoff modification.                                   |
| Privacy                             | Leader PRIVACY-02                             | Actual draft/category decisions, qualified independent Privacy/Security review, authorized policy/control parity and signed approvals.                                                                |
| Final APPROVAL-01                   | Leader role, proposed `hhtuann`               | All required gates accepted, separately appointed outside Security reviewer distinct from Leader, actual Security approval and explicit Leader environment/artifact GO with incident/rollback owners. |

## 12. Operator-readiness requirements

Before any later authorized operation, identify real operators/backup coverage,
least-privilege access, appointment/qualification, availability/on-call windows,
provider-supported commands, target verification, synthetic data authorization,
secret/key custody and restricted evidence storage. Review drain/old-writer inventory,
migration/runtime separation, restore/fencing and safe roll-forward, actual telemetry/
alerts, incident escalation, stop/recovery decisions and handover after uncertain commit.

One proposed human coordinates all gates: this is a scheduling, escalation,
execution/review bottleneck and single-person availability risk. Obtain named
incident/rollback responders, review availability and explicit scoped delegation/
backup coverage; no delegate or new permissions are appointed here. Independent
review cannot be simulated by the same person using two roles/accounts.

## 13. Safe stages, prerequisites and execution boundaries

| Stage                  | Readiness work                                                                                                                        | Execution boundary                                                                                                          |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| Planning               | Preserve nomination, resolve proposed-human appointment, provider/design/cost and qualified independent reviewers.                    | Read-only planning only; no cloud/credential use.                                                                           |
| Feasibility permission | Approve target, operators, synthetic data, bounded recovery/load baseline protocols, safety limits and commands.                      | Separate explicit environment authorization required; this pack supplies none.                                              |
| Feasibility evaluation | If later authorized, measure recovery and 100-Student behavior; preserve integrity/failure evidence.                                  | Numeric RTO/SLO still undecided; no formal PASS or production GO.                                                           |
| Criteria freeze        | Obtain actual RTO/SLO and strict soak interruption/completeness decisions; candidate/config/artifact and applicable policy approvals. | Freeze before formal acceptance; no retrospectively invented target.                                                        |
| Acceptance             | If separately authorized, execute applicable migration/DR/failover/load/rollout/telemetry/notice gates against approved criteria.     | Gate receipts, independent approvals and preserved history required; do not bypass failed security/integrity prerequisites. |
| Release decision       | Reconcile all gate evidence and obtain actual independent Security plus exact scoped Leader GO/NO-GO.                                 | Only subsequent explicit release authorization can permit production operations.                                            |

Follow [existing safe ordering](w3-production-release-acceptance.md#dependency-order)
and [future authorized deployment safeguards](w3-final-deployment-runbook.md).
Provider-specific runbooks and commands remain missing operator bindings, not implied
execution authority. No live load, HA faults, backup/restore, notice creation/soak,
settings mutation or feature activation is performed by this assignment.

## 14. Open decisions and blockers

Formal identity appointments/delegation, outside Security and qualified Privacy
reviewers, provider/region/topology/budget/operator coverage, zero-loss durability/
fencing design acceptance, reviewed baseline protocols and execution permissions
remain missing. RTO and quantitative performance criteria remain undecided; strict
soak interruption/completeness rules await formal acceptance. Policy periods/legal
bases/erasure/access/backup handling, effective required-check/protection policy,
actual RC/images/configuration/environment approval and all external evidence remain
open. No vendor, quantitative target, signature or secret is invented.

[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md),
[ADR-014](../decisions/ADR-014-access-token-principal-and-authority-contract.md),
[LD-05](../architecture/ld05-enforcement.md), immutable grading/results and the
accepted indefinite notice block retain authority. Frontend PR #77 and W3-M1/M2
remain independent; this pack neither implements nor certifies them.

## 15. Explicit Production NO-GO

**11 OPEN RELEASE GATES — 6 BLOCKED / 5 NOT VERIFIED. PRODUCTION NO-GO.**
All original IDs, severities, schema-2 4/7 coordination, proposed ownership,
unassigned actual DRIs, null formal RC, empty external receipts/approvals and
source-controlled default-disabled flags remain. No infrastructure, migration,
API, feature default or product policy is changed. This document is reviewable
approval readiness, not actual appointment, independent sign-off or deployment
permission. No production GO is issued.
