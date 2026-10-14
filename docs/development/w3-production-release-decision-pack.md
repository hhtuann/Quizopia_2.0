# W3 production release decision pack

Status: **DECISIONS RECORDED FOR REVIEW — PRODUCTION NO-GO.** Recorded
2026-10-14 (Asia/Bangkok). **11 OPEN RELEASE GATES — 6 BLOCKED / 5 NOT VERIFIED.**
No gate is completed, no actual human appointment or approval is recorded, and no
staging or production operation is authorized.

## Provenance and baseline

This pack records requester-provided, coordinator-selected release-planning
selections from decision rounds 1, 2 and 3 in the W3 Production Release Decision
Freeze & DRI Coordination Handoff assignment. These are project planning directions,
not an externally authenticated Leader approval, formal delegation, independent
Security/Privacy sign-off or release authorization. Required authorities must
confirm applicable decisions and execution permissions with actual written records.

PR #85 is merged at develop `10615f4a9cb73ceb4bce7728f23bf210d0bc99a2`. The
[historical W3-FINAL matrix](../architecture/w3-final-acceptance-matrix.md) remains
pinned to Git object `e615764613b661f8503de5fb5494fc7ea5224ba3` by the validator;
its 59 results and accepted severities are unchanged. The
[release register](w3-production-evidence-register.yaml) remains authoritative for
current gate states, specialist-team proposals, coordination roles, evidence and
approval requirements. This pack is the source for the selected planning decisions;
[acceptance](w3-production-release-acceptance.md),
[gate inventory](w3-production-release-gates.md) and
[operator handoff](w3-production-evidence-handoff.md) apply those selections.

Rounds 4–5 are recorded in the [Approval & Operator Readiness Pack](w3-production-release-approval-operator-readiness.md):
the same requester-supplied `hhtuann` holds Dev 1/Leader as one proposed human,
managed-cloud HA staging and synchronous-durability/fencing evaluation are selected,
RTO/performance baselines precede quantitative criteria, and strict continuous
≥48-hour eligible observation is selected. Formal appointments, permissions and
acceptance remain pending; the nomination and register states are unchanged.

## Round 1 — RC nomination and coordination model

`nominated_rc_sha: 10615f4a9cb73ceb4bce7728f23bf210d0bc99a2`

Classification: **NOMINATED RELEASE CANDIDATE**. Nomination does not authorize
production artifacts. The register retains `release_candidate_sha: null` until
formal RC approval is evidenced; BUILD-02 remains NOT VERIFIED. The repository
owner must still verify effective protections and required checks, and the Leader
must formally accept the exact candidate/artifact scope. If develop advances,
preserve this nomination until explicitly changed; evaluate revalidation instead
of transferring approvals to the new SHA automatically.

Actual merged-SHA GitHub Actions observations, retrieved 2026-10-14 at
09:33:15–19 UTC, all bind to `10615f4a9cb73ceb4bce7728f23bf210d0bc99a2`, push
event, completed/success:

| Workflow                         | Actual run                                                                      | Observed jobs                                         |
| -------------------------------- | ------------------------------------------------------------------------------- | ----------------------------------------------------- |
| Backend CI                       | [38538788322](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38538788322) | Eight of eight successful.                            |
| W3 Production Release Validation | [38538788360](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38538788360) | `release-register-validation` successful; one of one. |
| Repository validation            | [38538788388](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38538788388) | Two of two successful.                                |

These are CI observations, not release receipts or approvals. CI alone cannot
close BUILD-02 without the required policy/provenance and formal candidate acceptance.

Coordination model: **HYBRID COORDINATION** across Leader, Dev 1 — Backend and
Dev 2 — Frontend. Role selections organize requests and follow-through. They do
not replace qualified DBA, Platform/SRE, Security or Privacy operators/reviewers.

## Round 2 — Eleven-gate coordination view

The YAML `coordination_dri_role` field is the single machine-readable mapping.
This table is its human view; `proposed_owner` continues to identify each specialist
team. Schema version 2 adds the controlled coordination field; legacy version 1
remains readable for historical records without that field. The active register
requires version 2 and cannot silently downgrade to omit the selected roles. Every current gate has
`ownership_status: PROPOSED`,
`dri: UNASSIGNED — LEADER ASSIGNMENT REQUIRED`, and empty external evidence and
approval arrays. Coordination never satisfies a named DRI or approval requirement.

| Gate        | Selected coordination role       | Current state |
| ----------- | -------------------------------- | ------------- |
| BUILD-02    | `DEV1_BACKEND` — Dev 1 — Backend | NOT VERIFIED  |
| NOTICE-04   | `LEADER` — Leader                | NOT VERIFIED  |
| RECOVERY-05 | `LEADER` — Leader                | BLOCKED       |
| DB-05       | `DEV1_BACKEND` — Dev 1 — Backend | NOT VERIFIED  |
| DB-08       | `LEADER` — Leader                | BLOCKED       |
| TLS-02      | `LEADER` — Leader                | BLOCKED       |
| CAPACITY-04 | `DEV1_BACKEND` — Dev 1 — Backend | NOT VERIFIED  |
| OBSERVE-02  | `LEADER` — Leader                | BLOCKED       |
| PRIVACY-02  | `LEADER` — Leader                | BLOCKED       |
| DEPLOY-02   | `DEV1_BACKEND` — Dev 1 — Backend | NOT VERIFIED  |
| APPROVAL-01 | `LEADER` — Leader                | BLOCKED       |

Exactly four gates select DEV1_BACKEND and seven select LEADER. No backend gate
selects DEV2_FRONTEND. Dev 2's independent frontend responsibilities, PR #77 and
W3-M1/M2 acceptance remain outside this backend release pack.

For each gate distinguish the selected coordination role, actual named DRI,
execution/operator role, independent reviewer and final approval authority. No
human names or GitHub identities were supplied in rounds 1–3. D4-02/D5-01 later
supply `hhtuann` for both Dev 1 and Leader, as one proposed human; actual appointment
remains unverified. The future outside Security
reviewer must be identifiable, qualified, appropriately authorized, independent of
the current Leader/Dev 1/Dev 2 team and different from the Leader release approver. Existing
normalized identity, DRI/executor separation and exact-artifact approval rules remain.

## Round 3 — Eight selected decisions

| Decision | Selected objective                                                                                                                                                                                                | Exact planning status                                          | Formal acceptance / execution still required                                                                                                                                                                                         |
| -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| D3-01    | Establish isolated production-like staging with separate service identities/databases, actual private transport, synthetic or authorized sanitized data and multi-node/fencing experiment support.                | APPROVED PLANNING DIRECTION — NOT PROVISIONED                  | Provider/topology/budget, operators, secret/trust custody, data authorization and bounded provisioning/drill permissions. No production credentials/data use authorized.                                                             |
| D3-02    | Require zero loss of acknowledged committed writes: saved answers, submitted results, durable receipts, Publication/closure transitions, notice confirmation/cutoffs and Identity authoritative security changes. | SELECTED DURABILITY OBJECTIVE — NOT VERIFIED                   | Platform/DBA architecture and independent evidence for replication, commit durability, fencing and recovery; formal acceptance and authorized drills. Ordinary backups or asynchronous replication alone do not prove the objective. |
| D3-03    | RTO remains UNDECIDED.                                                                                                                                                                                            | REQUIRES OPERATIONAL ASSESSMENT                                | Failure scenarios, proposed architecture/dependencies, measured staging recovery times, resource cost, operator readiness and expected user impact before selecting any recovery-time commitment.                                    |
| D3-04    | Initial acceptance test scale is 100 concurrent active Students.                                                                                                                                                  | SELECTED INITIAL TEST SCALE — PERFORMANCE SLO NOT YET APPROVED | Representative profile/think time/duration and approved latency/error/resource/headroom criteria; target-like execution and independent results. Concurrency is not 100 requests per second and cannot alone PASS.                   |
| D3-05    | A genuine wall-clock observation of at least 48 elapsed hours is mandatory for release acceptance.                                                                                                                | REQUIREMENT SELECTED — NOT EXECUTED                            | Actual staging inbox, real committed PUBLISH/CONFIRM, individual cutoff, full telemetry/outage history, formal interruption/completeness treatment and independent Security review before/after execution.                           |
| D3-06    | Leader drafts retention/erasure/access policy; independent Privacy/Security reviews before implementation acceptance.                                                                                             | POLICY WORKFLOW SELECTED — POLICY NOT APPROVED                 | Actual draft, category decisions, periods/access/backup handling and reviewed matching controls. No duration, TTL, legal basis or deletion permission supplied.                                                                      |
| D3-07    | Recruit/appoint qualified independent Security reviewer from outside the current Leader/Dev 1/Dev 2 team.                                                                                                         | REVIEW MODEL SELECTED — REVIEWER NOT APPOINTED                 | Actual identity, qualification, authority, independence and accepted review scope; no review or sign-off issued. Reviewer cannot also be Leader release approver.                                                                    |
| D3-08    | Propose W3 Production Release Validation as a required repository status check; actual job is `release-register-validation`.                                                                                      | REQUIRED-CHECK POLICY PROPOSED — NOT CONFIGURED                | Repository owner verifies exact context, docs-only behavior, merge policy and effective classic protection/ruleset configuration. This assignment does not mutate repository settings.                                               |

D3-02 is an objective for acknowledged writes, not proof of an implemented zero-loss
architecture. Platform/DBA must state failure assumptions, replication/commit
acknowledgment rules, fencing, recovery path and measured availability/latency/cost
trade-offs. Evaluate those trade-offs without relaxing the selected objective.
Backup recovery alone must not silently replace acknowledged-write preservation.
RTO is a separate undecided time objective; no 30-, 60- or 120-minute value is adopted.

D3-05 selects mandatory real-time observation now. Controlled database clocks,
backdated confirmation and real Student accounts are excluded. Formal interruption
and completeness treatment must be agreed before the soak starts; do not invent
an uptime rule or reset existing cutoff rights. D5-04 further selects strict
continuous eligible observation; exact interruption/completeness rules still need
formal acceptance. An invalidated observation must fail/be incomplete and be
repeated after remediation without editing actual confirmation or cutoff history. No waiver is selected. Any future
change requires a new explicit decision and the existing written Leader/Security
waiver authority, risk, scope and expiry/review conditions; time cost alone is no
reason to waive. NOTICE-04 remains NOT VERIFIED.

## Proposed initial workload profile — acceptance thresholds pending

**PROPOSED profile, not an approved performance SLO or executed benchmark.**
Dev 1 and Platform must turn the selected 100 concurrent Students into a reviewed
traffic/data profile, authorize its staging resources and duration, and obtain
latency/error/resource thresholds before formal CAPACITY-04 acceptance execution.
D5-03 permits a separately authorized bounded staging feasibility baseline before
quantitative SLO selection; it is not execution of the register's formal acceptance
procedure and cannot PASS an unstated target. See the readiness pack for the
baseline → target approval/freeze → formal acceptance sequence. No request rate,
think-time value, phase duration, P95/P99 acceptance number or production SLA is
selected here.

| Phase / operation                 | Proposed coverage and measurements                                                                                                                                                             | Acceptance input still needed                                                                                                                |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| Data/resources                    | Representative CLASS/PUBLIC immutable snapshots and complete answers; unchanged 12,613-question fixture plus accepted size/encoding expansion; target JVM/JDBC/database/private-hop resources. | Dataset distribution, target resources, full payload mix and quantitative headroom limits.                                                   |
| Warm-up                           | Establish real authenticated users/admission and resource baselines; warm pools/caches without positive authority caching.                                                                     | Warm-up duration and steady-state entry criteria.                                                                                            |
| START/RESUME                      | Reach 100 concurrent active Students with realistic think time, staggered starts/resumes and ordinary lifecycle/security checks.                                                               | Think-time/session/activity distribution and measured request rate; no equivalence to 100 RPS.                                               |
| Individual SAVE                   | Representative question-level saves with complete accepted answer types.                                                                                                                       | SAVE cadence/mix and expected success/conflict/error classification.                                                                         |
| Concurrent SAVE revisions         | Race overlapping revisions and retries for the same synthetic Attempt; verify stale conflicts and durable operation replay.                                                                    | Collision/retry distribution and approved error/conflict budget.                                                                             |
| SUBMIT                            | Complete final answer payloads and saved-answer submission; durable receipt/result identity, no truncation or duplicate grading/finalization.                                                  | Burst/mix, latency/error and complete-answer compatibility criteria.                                                                         |
| Result reads / Teacher operations | Owner results/review policy, Teacher queries/ordinary CLOSE and delayed REQUEST/CANCEL/COMPLETE as permitted; protect redaction and ownership.                                                 | Read/write/Teacher mix and expected denied/precondition outcomes.                                                                            |
| Closure batches                   | Full atomic membership capture or rollback; bounded finalization batches and same-key replay under competing Student/Teacher work.                                                             | Closure population/batch cadence, throughput and lock/resource limits.                                                                       |
| Steady state / saturation         | Sustain selected concurrency; measure heap/GC, CPU, JDBC occupancy/acquisition, database locks, latency distributions and errors. Separate any saturation pressure from the initial workload.  | Steady-state duration; approved latency/error/headroom/queue thresholds; additional authorization for pressure beyond the selected workload. |
| Failure / recovery                | Observe timeout, retry, lost acknowledgments and recovery with complete answer/result/cutoff/receipt reconciliation and no duplicate terminalization.                                          | Approved failure injection, recovery duration/stop conditions and integrity review.                                                          |

Proposed metric/threshold decision fields are operation throughput, observed request
rate, latency distribution (including reported percentiles), expected versus
unexpected error/conflict rates, pool acquisition/occupancy, heap/GC pauses, CPU,
lock wait/contention, closure backlog/throughput and recovery headroom. **All numeric
acceptance thresholds are UNDECIDED — APPROVAL REQUIRED**. Measurements must be
repeatable and bound to actual SHA/digests/configuration. Preserve initial 2 MiB
engineering-budget/full-answer compatibility and W3-A handoff limitations; never
raise timeouts, reduce fixtures or relax integrity merely to meet the load profile.

## Privacy policy decision checklist

**Draft responsibility: Leader. Independent acceptance: Privacy/Security.**
For every category below, supply owner/purpose/access, actual proposed retention
period and basis for decision, erasure/anonymization behavior and exceptions,
backup/export handling, applicable integrity obligations, implementation/control
mapping, evidence and independent sign-off. All periods and legal bases remain
undecided; this checklist grants no deletion authority.

| Category             | Decisions/control evidence required                                                                                                      |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| Answers              | Saved/submitted answer handling, owner/operator access, erasure/anonymization and complete-result integrity.                             |
| Snapshots            | Immutable delivery content and source identity/digests; preservation needed for grading/review and permissible anonymization.            |
| Results              | Grade/result identity, aggregation/review obligations, permitted data access and policy-compatible retention/erasure.                    |
| Operation receipts   | Durable retry identity and acknowledgment history; safe replay obligations and retention dependencies.                                   |
| Notification history | Authenticated owner inbox history/access, PUBLISH/CONFIRM records and post-cancellation readability obligations.                         |
| Audit records        | Closure/notice/finalization actors and timestamps, immutable evidence access and authorized audit retention.                             |
| Redis authority      | Ephemeral projection lifecycle/reconstruction, access and stale-authority fail-closed behavior; PostgreSQL remains authoritative.        |
| Operational logs     | Suppression/redaction at all real sinks, restricted access, exports/support bundles and approved retention/erasure.                      |
| Backups              | Encryption/key custody, restricted restore access, backup retention/expiry, restored-data handling and integrity/erasure reconciliation. |

Notice confirmation/cutoff evidence is covered explicitly by notification history,
audit and backup decisions. Preserve accepted immutability and integrity; any
conflict requires authorized policy resolution rather than automatic deletion.
PRIVACY-02 remains BLOCKED until actual policy/control approval exists.

## Dependencies and next information requested

| Coordination / specialist | Next deliverable                                                                                                                                                                                                                                             | Remains unavailable until supplied                                                                                                 |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------- |
| Dev 1 — Backend           | Repository-owner required-context/protection review for BUILD-02; target-size migration data/job/threshold plan for DB-05; proposed 100-Student profile and SLO inputs for CAPACITY-04; exact image/configuration/drain/roll-forward plan for DEPLOY-02.     | Verified humans/operator permissions, formal RC/artifact acceptance, approved thresholds and actual staging results.               |
| Leader                    | Confirm role appointments as identifiable humans; formalize nomination scope; draft nine-category privacy policy; obtain staging provider/topology/budget and qualified external reviewers; coordinate soak interruption and RTO decisions.                  | Actual delegation/approvals, policy periods, reviewer appointments and environment-specific execution permissions.                 |
| Platform/DBA/SRE          | Propose isolated topology/cost, zero-acknowledged-write-loss architecture/failure assumptions/fencing/backup/recovery, measured recovery assessment, TLS/trust/clients/roles and actual telemetry; supply reviewed operator commands and stop/recovery plan. | Independent feasibility acceptance, RTO selection, provisioned authorized environment and executed integrity/DR/failover evidence. |
| Outside Security          | Supply qualified identity/authority/independence and review scope; review narrow Identity/LD-05/TLS, durability/recovery, soak interruption/completeness and final exact-artifact evidence.                                                                  | Appointment and actual dated independent reviews/sign-offs; no reviewer is appointed here.                                         |
| Privacy/Security          | Review Leader draft category policy and backup/access/erasure compatibility; require matching isolated control evidence.                                                                                                                                     | Approved periods/legal-policy decisions and implementation acceptance.                                                             |
| Repository owner          | Confirm exact `release-register-validation` context under W3 Production Release Validation, docs-only trigger behavior, effective protections/rulesets and desired merge policy; separately authorize any future settings change.                            | Required-check configuration/acceptance; no branch setting is changed by this pack.                                                |

Selected planning objectives feed Stage A; authorized infrastructure precedes
staging migration/rollout/load/failover/telemetry evidence; real inbox/eligibility and
formal interruption treatment precede mandatory elapsed-time soak; actual technical
receipts precede independent Security and final Leader decision. Follow the
[existing dependency graph and execution boundary](w3-production-release-acceptance.md#dependency-order).

Still-open decisions are RTO; staging provider/topology/budget and operator readiness;
load profile/duration/SLO/error/resource thresholds; soak interruption/completeness
treatment; named humans, qualified outside reviewer appointments and authority;
privacy durations/erasure/access/backup policy; formal candidate/artifact acceptance;
and effective required-check/protection configuration. Existing accepted product
policy, indefinite blocked closure, narrow Identity boundary, migrations and
feature defaults remain unchanged. No planning selection counts as technical
evidence or approval. **PRODUCTION NO-GO** remains until the actual gates and
independent authorization requirements are satisfied.
