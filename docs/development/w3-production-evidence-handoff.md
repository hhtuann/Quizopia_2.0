# W3 production evidence owner handoff

Status: **ACTIONABLE REQUESTS ONLY — NO EXTERNAL EXECUTION AUTHORIZED.** The
[register](w3-production-evidence-register.yaml) is the authoritative checklist;
[acceptance protocol](w3-production-release-acceptance.md) defines decision
boundaries. All named DRIs remain **UNASSIGNED — LEADER ASSIGNMENT REQUIRED**.
Selected coordination roles in the [decision pack](w3-production-release-decision-pack.md)
organize follow-through: Dev 1 — Backend handles BUILD-02/DB-05/CAPACITY-04/DEPLOY-02;
Leader handles the other seven. Dev 2 has no backend gate. Specialist teams remain
proposed and must return actual appointments and authorization before any
deployment, restore, fault injection, load or notice exercise.

## Selected directions and remaining formal decisions

1. Formally accept the exact nomination/artifact scope in the decision pack; obtain
   identifiable human DRI/executor/reviewer appointments and incident/rollback owners.
   Request repository-owner confirmation of exact `release-register-validation`
   required context, docs-only merge behavior and effective protection/rulesets.
   The required-check policy is proposed, not configured; no settings change is authorized.
2. Leader drafts the nine-category privacy policy for independent Privacy/Security
   acceptance. Obtain actual periods/erasure/access/backup decisions and matching
   controls. Zero acknowledged-write loss is selected; Platform/DBA must propose
   and independently verify feasible durability/fencing/recovery architecture.
   RTO remains UNDECIDED pending operational assessment, measurements and cost/readiness/impact.
3. Dev 1/Platform turn the selected 100 concurrent active Students into a realistic
   profile (not 100 RPS), with proposed think time, mix, warm-up/steady/recovery duration,
   latency/error/resource budgets and stop conditions for formal approval. No SLO exists yet.
4. Mandatory genuine ≥48-hour soak is selected. Obtain formal interruption and
   completeness acceptance before starting; then authorized synthetic staging actors,
   actual authenticated inbox/PUBLISH/CONFIRM/cutoff and full telemetry/outage history.
   No current waiver is selected; any future change needs a new explicit decision
   and existing formal Leader/Security safeguards, never a shortcut because time elapses.
5. Develop the selected isolated production-like staging direction into a provider/
   topology/budget/operator proposal: separate identities/databases/private transport,
   synthetic or separately authorized sanitized data, multi-node/fencing capability,
   restricted roles, cursor/secret custody, backup/restore and actual telemetry.
   No provisioning, production credentials/data use or live fault injection is authorized.
6. Recruit a qualified identifiable Security reviewer outside Leader/Dev 1/Dev 2,
   distinct from the Leader release approver; obtain authority/scope and independent
   Privacy/Security review. Separately authorize each bounded staging exercise with
   exact target, operators, reviewed commands, window, stop/recovery and data controls.
   No requester planning selection is an externally authenticated approval or receipt.

## Per-gate owner requests

| Gate / proposed owner                     | Provide / execute / return                                                                                                                                                                                                                                                                                                             | Reject evidence when                                                                                                                                                                                                                          | Blocks final acceptance of                                           |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| BUILD-02 — Repository/CI                  | Effective classic/ruleset protections and required-check list; formal approval of the recorded nominated SHA/artifacts; paginate exact-SHA runs/jobs and record reviewed coverage. Existing actual run links are below.                                                                                                                | Wrong SHA/PR-head substitution; missing required job; failed/skipped check unexplained; protection policy not retrievable.                                                                                                                    | DEPLOY-02, APPROVAL-01.                                              |
| NOTICE-04 — Operations/Security           | Mandatory real-soak selection and formal interruption/completeness acceptance; actual authenticated staging window; actual UTC confirmation+48h/cutoff, availability/exception history and reviewed Teacher COMPLETE/CLOSED results.                                                                                                   | Simulated clock; invented confirmation; missing telemetry/approved interruption treatment; current selection treated as waived, or future waiver without a new explicit decision and existing authorized risk/scope/expiry-review safeguards. | APPROVAL-01.                                                         |
| RECOVERY-05 — Platform/SRE/Security       | Selected zero-acknowledged-write-loss objective plus formally accepted architecture/failure assumptions and multi-node target/fault plan and operator command bindings; execute partition/fence/promote/recover; return acknowledged Assessment/Identity manifests, uncertain-commit and stale/old-writer negatives, independent logs. | Same-DB peer/restart substituted; fencing absent; any selected acknowledged committed writes lost; stale authority accepted.                                                                                                                  | NOTICE-04, APPROVAL-01.                                              |
| DB-05 — DBA/Platform                      | Approved target-size data/counts/sizes/thresholds; execute fresh/upgrade Flyway V5/drain; return checksums, lock/time/WAL/disk/temp peaks, runtime/invariant comparisons and roll-forward result.                                                                                                                                      | Small fixture relabeled; unknown thresholds/representativeness; partial capture/integrity loss; merged migration edited.                                                                                                                      | CAPACITY-04, DEPLOY-02, APPROVAL-01.                                 |
| DB-08 — DBA/SRE/Security                  | Encrypted backup/access/retention and independently accepted zero-loss preservation architecture; obtain explicit RTO decision after assessment; actual isolated restore; return coverage/provenance, measured recovery, Assessment/Identity reconciliation, restricted-role checks and accepted runbook.                              | Local dump alone; unapproved restore target; secrets/dumps in Git; missing acknowledged answer/grade/notice/receipt/audit evidence.                                                                                                           | DB-05, RECOVERY-05, DEPLOY-02, APPROVAL-01.                          |
| TLS-02 — Platform/DevSecOps               | Actual private/OAuth/JWKS topology/certificates/trust/client scopes/network controls; execute positive and wrong cert/host/client/scope/USER checks; return sanitized manifests, public-internal denial and review.                                                                                                                    | Insecure override; local self-signed test substituted; USER introspection/extra account fields; public internal route; stale authority fallback.                                                                                              | DB-08, RECOVERY-05, CAPACITY-04, OBSERVE-02, DEPLOY-02, APPROVAL-01. |
| CAPACITY-04 — Performance/Platform        | Selected 100-concurrent-Student profile with subsequently approved SLO/error/resource/duration criteria; repeated full-payload/target-load tests preserving 12,613-question fixture; return sizes, headroom/GC/pool/CPU/lock traces, throughput and failure/retry/atomicity outcomes.                                                  | Invented SLA; missing full answers; truncation; partial successful capture; fixture reduced; timeout raised without evidence/authorization.                                                                                                   | NOTICE-04, APPROVAL-01.                                              |
| OBSERVE-02 — SRE/Operations/Security      | Actual sinks/restricted access, approved thresholds and named escalation; execute synthetic alert/redaction/runbook cases; return backlog/age/outcome samples, real delivery and root/proxy/APM/access/exporter scan results.                                                                                                          | Unit/test-only evidence; public identity labels; credentials/answer data leaked; ineligible closure remediation bypass.                                                                                                                       | NOTICE-04, APPROVAL-01.                                              |
| PRIVACY-02 — Leader/Privacy/Security      | Leader draft and independently approved nine-category ownership/periods/erasure/anonymization/backup/access decisions; actual synthetic control parity and independent sign-off.                                                                                                                                                       | Guessed TTL/duration; missing backup coverage; deletion violates immutable grading/notice/audit integrity; policy without matching controls.                                                                                                  | DB-08, OBSERVE-02, DEPLOY-02, APPROVAL-01.                           |
| DEPLOY-02 — Platform/SRE/Backend          | Selected isolated staging direction with approved provider/topology/budget and formal RC/digests/config/flags, old-instance inventory and bound rollout commands; execute drain/migration/dependency smoke/restart/roll-forward/mixed-version/disable; return reviewed timings and cutoff-rights reconciliation.                       | Unexecuted runbook; bypass-capable old writers; readiness attestation without authenticated inbox; emergency disable erases cutoff history.                                                                                                   | NOTICE-04, APPROVAL-01.                                              |
| APPROVAL-01 — Independent Security/Leader | Retrieve all receipts/risk dispositions, appoint qualified outside-team Security reviewer and independently review actual RC/config/topology, resolve HIGH/BLOCKER governance, name incident/rollback owners and store actual Security approval plus exact scoped Leader decision.                                                     | Self-review/merge/register substituted; different SHA/config; unresolved ungoverned mandatory failure; no explicit environment/artifact Leader GO.                                                                                            | Production release decision; no execution is authorized here.        |

These are final-signoff dependencies, not permission to skip preparation or a
requirement to claim completed staging evidence before staging authorization.
Owners can prepare plans and collect prerequisites while gates are open. Final
acceptance must follow the dependency graph in the register.

## Concrete procedure bindings

The register supplies ordered operations and expected results. Infrastructure
provider, cluster IDs, target databases and approved operator toolchain have not
been supplied, so destructive/provider-specific commands are deliberately not
invented. Before authorization, the assigned operator must attach the exact reviewed
commands/job IDs, target verification step, output capture/redaction, stop conditions
and recovery command for each procedure. A procedure with unbound execution steps
cannot be executed or marked PASS.

For DB-05, the operator must first supply a reviewed Flyway execution job, then bind it
to the verified isolated
target and migration-owner secret reference: capture `info`, `validate`, fresh/
upgrade `migrate` through V5 and post-migration `validate`. Confirm the job/tool
versions and original V1–V5 checksums first; do not improvise new production Maven
configuration or expose JDBC credentials. Collect approved PostgreSQL lock/WAL/
space monitors and invariant comparisons with that job's timestamps.

For DEPLOY-02, bind each step of the
[existing deployment runbook](w3-final-deployment-runbook.md) to the actual service
manager/ingress/drain/migration/roll-forward commands. For notice/TLS checks derive
exact methods and bodies from the implemented
[Assessment OpenAPI](../../services/assessment-service/contracts/publications.openapi.yaml)
and [Identity OpenAPI](../../services/identity-service/contracts/user-admission.openapi.yaml).
The narrow private operation is
`POST /internal/security/notice-recipients/{studentId}/eligibility`, scope
`identity.notice-recipient.read`; no Gateway alias or generic introspection is
permitted. Token acquisition and test subjects remain under the operator's secure
tooling, with no token/body logging. For recovery/DR/load/telemetry, return reviewed
provider/fault/load/sink commands before requesting execution authorization.

The following historical-baseline read-only public metadata commands are usable
for BUILD-02; they
are operator instructions, not a claim that the CLI is installed or was executed
by this workstream:

```bash
W3_RELEASE_SHA=e615764613b661f8503de5fb5494fc7ea5224ba3
git show --no-patch --format=fuller "$W3_RELEASE_SHA"
gh api --paginate "repos/hhtuann/Quizopia_2.0/actions/runs?head_sha=$W3_RELEASE_SHA&per_page=100"
gh api --paginate "repos/hhtuann/Quizopia_2.0/actions/runs/38507476186/jobs?per_page=100"
gh api --paginate "repos/hhtuann/Quizopia_2.0/actions/runs/38507476067/jobs?per_page=100"
gh api repos/hhtuann/Quizopia_2.0/branches/develop/protection
gh api repos/hhtuann/Quizopia_2.0/rules/branches/develop
```

URL arguments containing `?` are quoted for zsh. Replace the baseline
SHA/run IDs with the actual approved candidate and its retrieved runs when selected;
use the separately recorded nomination only after formal acceptance, and never transfer
these historical runs to that SHA. Current nomination's actual three run links are in
the decision pack. Policy reads require appropriate
repository authorization. An unauthorized policy read or empty ruleset list cannot
establish absence of classic protections. Review the actual workflow files
[backend-ci.yml](../../.github/workflows/backend-ci.yml) and
[repository-validation.yml](../../.github/workflows/repository-validation.yml)
and [release validation workflow](../../.github/workflows/w3-production-release-validation.yml)
against the actual required-check policy. The proposed required job context must be
confirmed by the repository owner before any separately authorized configuration change.

Actual observed merged-SHA runs:
[Backend CI](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38507476186)
(eight successful jobs) and
[Repository validation](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38507476067)
(two successful jobs). Metadata was observed at `2026-10-14T04:04:08Z`; this is a
read-only observation, not independent gate approval or RC selection.

## Evidence return contract

Return a sanitized manifest using the register's `evidence_contract` fields:
exact candidate SHA; named executor; actual UTC date; environment name/class;
procedure/tool versions; expected and actual results; actual retrievable evidence
reference and SHA-256 integrity digest; independent review reference and limitations.
Deployment evidence must include actual image digests and secret-free configuration
fingerprint; deployment approvals must bind the same digest and fingerprint. Return required written approvals with actual authority/person/date,
exact candidate SHA and source. D3-05 selects mandatory real soak and no waiver;
any future NOTICE-04 waiver requires a new explicit decision plus the separate formal
waiver fields and both authority approvals. These are empty requirements today,
not pre-filled evidence templates presented as execution.

Keep confidential logs, database backups, credentials/tokens, private locators and
Student answers in approved restricted storage. Commit only safe sanitized
manifests and real references; reviewers verify source integrity/access independently.
Do not add placeholder evidence URLs. Report any redaction failure via approved
incident controls, and leave affected gates open. Re-evaluate per-gate freshness
when SHA/images/configuration/topology/policy/workload changes. Return results to
the release coordinator for review; no operator may infer production GO from the
register or this Draft PR.
