# W3 production release gate inventory

Status: **11 gates open — 6 BLOCKED, 5 NOT VERIFIED; PRODUCTION NO-GO.**
The [YAML register](w3-production-evidence-register.yaml) contains complete ordered
procedures, prerequisites, criteria, evidence requirements, authority and
revalidation conditions. This table summarizes that single source. Baseline is
[W3-FINAL](../architecture/w3-final-acceptance-matrix.md), inherited from merged
PR #84 at `e615764613b661f8503de5fb5494fc7ea5224ba3`; none of its 59 historical
results is rewritten.

All rows are release-blocking pending actual acceptance or explicit authorized
scope/risk disposition. BUILD-02 has MEDIUM severity; the other ten have HIGH
severity, matching the inherited matrix. Severity describes the impact of a failed
invariant, not a newly asserted defect. NOTICE-04 requires
formal interruption/completeness acceptance before execution. D3-05 selects mandatory
real ≥48-hour observation; no waiver is selected. Any future change requires a new
explicit decision and existing full formal waiver authority, not convenience.

**Every named DRI: UNASSIGNED — LEADER ASSIGNMENT REQUIRED.** All teams below are
proposed; no appointment or approval is claimed. Gate evidence/approvals arrays
remain empty. Selected coordination roles are recorded separately in the register
and viewed in the [decision pack](w3-production-release-decision-pack.md): four Dev 1 — Backend
gates and seven Leader gates, no Dev 2 — Frontend backend gate. Coordination is not
a verified human appointment, operator privilege or approval. The nominated RC and
its three successful merged-SHA workflows remain CI observations only.

| Gate        | Original → current          | Proposed accountable team              | Measurable PASS conditions and minimum evidence                                                                                                                                                                                                                                                                                                                                                                                      | Reviewer / authority                                                                                     |
| ----------- | --------------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------- |
| BUILD-02    | NOT VERIFIED → NOT VERIFIED | Repository/CI owner                    | Every required backend/repository workflow and job succeeds at exact approved RC SHA, with checked-SHA/run URLs, merge provenance and effective protection/required-check policy. Successful nominated-candidate merge CI is linked in the decision pack; formal RC/artifact approval and effective required-check/protection acceptance remain missing. Required release-validation check is proposed, not configured.              | Independent repository reviewer; repository policy owner and Leader RC selection.                        |
| NOTICE-04   | NOT VERIFIED → NOT VERIFIED | Operations + Security                  | Mandatory real ≥48-hour soak selected; formal requirement/interruption/completeness acceptance, authenticated staging notice accessibility, actual confirmation/cutoff, full telemetry/outage history and reviewed Teacher COMPLETE/CLOSED results remain required. No current waiver; future change needs a new explicit decision and existing formal Leader/Security safeguards. Clock advancement cannot PASS.                    | Independent Security; Leader/Security for requirement, observation treatment and any waiver.             |
| RECOVERY-05 | BLOCKED → BLOCKED           | Platform/SRE + Security                | Actual authorized multi-node partition/fencing/promotion; acknowledged Assessment and Identity writes preserve selected zero loss under formally accepted architecture/failure assumptions; uncertain commits replay once; result/receipt/cutoff manifests reconcile; stale authority and old writers rejected. Retain topology, negative cases, timelines and reviewed recovery logs.                                               | Independent Security/DBA; Platform change authority and approved durability/RPO policy.                  |
| DB-05       | NOT VERIFIED → NOT VERIFIED | DBA/Platform                           | Approved representative target-size dataset/counts/sizes; fresh/upgrade Flyway through V5 and unchanged checksums; executed writer drain; runtime compatibility; measured lock/time/WAL/disk/temp peaks within approved headroom; authoritative identity/digest/cutoff/receipt integrity and roll-forward evidence.                                                                                                                  | Independent DBA/Backend; DBA/Platform and approved capacity/maintenance criteria.                        |
| DB-08       | BLOCKED → BLOCKED           | DBA/SRE + Security                     | Actual encrypted access-controlled backup and isolated restore; selected zero acknowledged-write-loss preservation, independently accepted recovery architecture and measured subsequently approved RTO (currently UNDECIDED); Assessment and Identity integrity/acknowledged data preserved; restricted restore roles/secrets and independently accepted executed runbook. Local dump/restore is partial evidence.                  | Independent DBA/Security; backup owner, Security and approved RPO/RTO policy.                            |
| TLS-02      | BLOCKED → BLOCKED           | Platform/DevSecOps                     | Actual private/OAuth/JWKS topology validates issued certs/hostnames/trust stores; no insecure override; exact clients/scopes/network policy; wrong certificate/host/client/scope/USER denials; public `/internal/**` denial; narrow minimal eligibility, no-store/redaction and fail-closed authority evidence.                                                                                                                      | Independent Security; Platform/DevSecOps and Security.                                                   |
| CAPACITY-04 | NOT VERIFIED → NOT VERIFIED | Backend Performance Owner + Platform   | Selected initial 100 concurrent Students (not 100 RPS), with subsequently approved realistic profile/duration and latency/error/resource/headroom budgets; representative complete payloads, unchanged 12,613-question fixture, no truncation and mutation-free oversize rejection; heap/GC/JDBC/acquisition/CPU/locks, SAVE/SUBMIT/closure throughput/failure/retry/timeout traces; exact-once results and complete atomic capture. | Independent Backend/Platform; Leader/Platform budget approval and engineering authority for corrections. |
| OBSERVE-02  | BLOCKED → BLOCKED           | SRE/Operations + Security              | Actual restricted scrapes/audits, approved thresholds, delivered alerts and named escalation exercise; backlog/age, ineligible versus authority unavailable and worker retry visible; actual root/proxy/APM/access/exporter redaction; no public Student/Attempt/Publication metric identifiers.                                                                                                                                     | Independent Security/Operations; Operations thresholds/escalation and Security access/redaction.         |
| PRIVACY-02  | BLOCKED → BLOCKED           | Leader + Privacy/Security              | Leader drafts policy for independent Privacy/Security acceptance; actual approved periods/categories/owners, erasure/anonymization and backup/access policy consistent with immutable grade/notice/audit obligations; actual matching controls and restricted-access evidence across answers/snapshots/notification/closure evidence; written sign-offs. No guessed TTL.                                                             | Independent Privacy/Security; Leader and authorized privacy/security policy authority.                   |
| DEPLOY-02   | NOT VERIFIED → NOT VERIFIED | Platform/SRE + Backend                 | Selected isolated staging direction is not provisioned; actual authorized execution at formally approved RC/digests/config/flags required; old-instance inventory/drain/migration; fresh Identity/Redis, narrow scopes, cursor keys and authenticated inbox; staged activation and negative smoke; failed-start/restart/roll-forward/mixed-version isolation/emergency-disable preserve durable cutoffs; reviewed timelines.         | Independent Backend/Security; Platform change authority, Security and Leader staging scope.              |
| APPROVAL-01 | BLOCKED → BLOCKED           | Independent Security Reviewer + Leader | All mandatory technical gates accepted or explicit authorized risk dispositions; actual outside-three-person-team independent security approval of RC/config/topology and HIGH/BLOCKER dispositions; exact scope/limitations/environment/artifact SHA/digests; actual Leader GO and named incident/rollback owners; stored dated release record.                                                                                     | Named independent Security reviewer and explicit Leader release authority.                               |

## Review and state changes

The [handoff](w3-production-evidence-handoff.md) identifies missing resources,
operator bindings, returned artifacts and rejection conditions. Before proposing a
state change, record the actual named DRI/executor/reviewer, complete receipts and
authorized written approvals in the YAML. The actual approved candidate SHA is
currently `null`; the exact nomination is recorded in the decision pack and is
not formal release-candidate approval.

PASS is accepted only for the exact reviewed environment/artifact/configuration
and stated limits. Independent reviewers must retrieve sources and verify evidence
integrity and actual authority; validator success alone cannot establish truth.
Do not transfer approvals automatically after a SHA, image, security configuration,
topology, workload, policy or evidence-expiry change. Use each gate's `revalidate_on`
conditions and keep production NO-GO while mandatory evidence is missing.
