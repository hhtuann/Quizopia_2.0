# W3 production evidence owner handoff

Status: **ACTIONABLE REQUESTS ONLY — NO EXTERNAL EXECUTION AUTHORIZED.** The
[register](w3-production-evidence-register.yaml) is the authoritative checklist;
[acceptance protocol](w3-production-release-acceptance.md) defines decision
boundaries. All named DRIs remain **UNASSIGNED — LEADER ASSIGNMENT REQUIRED**.
Proposed teams below must return actual appointments and authorization before any
deployment, restore, fault injection, load or notice exercise.

## Leader decisions and resources required first

1. Select the exact release-candidate SHA and artifact scope; appoint one DRI,
   executor and independent reviewer for each gate, plus incident/rollback owners.
2. Approve privacy categories/retention/erasure/access and backup handling; approve
   workload/concurrency/duration, latency/error/resource budgets and durability/RPO/RTO.
   No value is invented by this workstream.
3. With Security decide whether an actual 48-hour soak is required, and the accepted
   observation/interruption treatment. A waiver must be written with decision
   maker, authority, risk, scope and expiry/review conditions; it is not a PASS.
4. Provision an authorized isolated deployment-like environment, private DNS and
   certificates/trust, separately scoped service identities, restricted runtime and
   migration roles, backup/restore and fenced multi-node failover controls, telemetry
   sinks/alert access and approved representative synthetic/sanitized data. Operators
   obtain required credentials through existing secret controls; do not send them
   to Codex or commit them.
5. Authorize bounded staging exercises independently from final production GO,
   specifying targets, operators, window, reviewed commands, stop/recovery conditions
   and data handling. Assign a genuinely independent Security release reviewer.

## Per-gate owner requests

| Gate / proposed owner                     | Provide / execute / return                                                                                                                                                                                                              | Reject evidence when                                                                                                                                 | Blocks final acceptance of                                           |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| BUILD-02 — Repository/CI                  | Effective classic/ruleset protections and required-check list; approved SHA; paginate exact-SHA runs/jobs and record reviewed coverage. Existing actual run links are below.                                                            | Wrong SHA/PR-head substitution; missing required job; failed/skipped check unexplained; protection policy not retrievable.                           | DEPLOY-02, APPROVAL-01.                                              |
| NOTICE-04 — Operations/Security           | Written requirement/waiver decision; real authenticated staging window if required; actual UTC confirmation+48h/cutoff, availability/exception history and reviewed Teacher COMPLETE/CLOSED results.                                    | Simulated clock; invented confirmation; missing telemetry/approved interruption treatment; waiver without authorized risk/scope/expiry-review terms. | APPROVAL-01.                                                         |
| RECOVERY-05 — Platform/SRE/Security       | Approved multi-node target/RPO/fault plan and operator command bindings; execute partition/fence/promote/recover; return acknowledged Assessment/Identity manifests, uncertain-commit and stale/old-writer negatives, independent logs. | Same-DB peer/restart substituted; fencing absent; acknowledged writes lost beyond approved requirement; stale authority accepted.                    | NOTICE-04, APPROVAL-01.                                              |
| DB-05 — DBA/Platform                      | Approved target-size data/counts/sizes/thresholds; execute fresh/upgrade Flyway V5/drain; return checksums, lock/time/WAL/disk/temp peaks, runtime/invariant comparisons and roll-forward result.                                       | Small fixture relabeled; unknown thresholds/representativeness; partial capture/integrity loss; merged migration edited.                             | CAPACITY-04, DEPLOY-02, APPROVAL-01.                                 |
| DB-08 — DBA/SRE/Security                  | Approved encrypted backup/access/retention and RPO/RTO; actual isolated restore; return coverage/provenance, measured recovery, Assessment/Identity reconciliation, restricted-role checks and accepted runbook.                        | Local dump alone; unapproved restore target; secrets/dumps in Git; missing acknowledged answer/grade/notice/receipt/audit evidence.                  | DB-05, RECOVERY-05, DEPLOY-02, APPROVAL-01.                          |
| TLS-02 — Platform/DevSecOps               | Actual private/OAuth/JWKS topology/certificates/trust/client scopes/network controls; execute positive and wrong cert/host/client/scope/USER checks; return sanitized manifests, public-internal denial and review.                     | Insecure override; local self-signed test substituted; USER introspection/extra account fields; public internal route; stale authority fallback.     | DB-08, RECOVERY-05, CAPACITY-04, OBSERVE-02, DEPLOY-02, APPROVAL-01. |
| CAPACITY-04 — Performance/Platform        | Approved workload/budgets/resources; repeated full-payload/target-load tests preserving 12,613-question fixture; return sizes, headroom/GC/pool/CPU/lock traces, throughput and failure/retry/atomicity outcomes.                       | Invented SLA; missing full answers; truncation; partial successful capture; fixture reduced; timeout raised without evidence/authorization.          | NOTICE-04, APPROVAL-01.                                              |
| OBSERVE-02 — SRE/Operations/Security      | Actual sinks/restricted access, approved thresholds and named escalation; execute synthetic alert/redaction/runbook cases; return backlog/age/outcome samples, real delivery and root/proxy/APM/access/exporter scan results.           | Unit/test-only evidence; public identity labels; credentials/answer data leaked; ineligible closure remediation bypass.                              | NOTICE-04, APPROVAL-01.                                              |
| PRIVACY-02 — Leader/Privacy/Security      | Explicit category ownership/periods/erasure/anonymization/backup/access decisions; actual synthetic control parity and independent sign-off.                                                                                            | Guessed TTL/duration; missing backup coverage; deletion violates immutable grading/notice/audit integrity; policy without matching controls.         | DB-08, OBSERVE-02, DEPLOY-02, APPROVAL-01.                           |
| DEPLOY-02 — Platform/SRE/Backend          | Authorized RC/digests/config/flags, old-instance inventory and bound rollout commands; execute drain/migration/dependency smoke/restart/roll-forward/mixed-version/disable; return reviewed timings and cutoff-rights reconciliation.   | Unexecuted runbook; bypass-capable old writers; readiness attestation without authenticated inbox; emergency disable erases cutoff history.          | NOTICE-04, APPROVAL-01.                                              |
| APPROVAL-01 — Independent Security/Leader | Retrieve all receipts/risk dispositions, independently review actual RC/config/topology, resolve HIGH/BLOCKER governance, name incident/rollback owners and store actual Security approval plus exact scoped Leader decision.           | Self-review/merge/register substituted; different SHA/config; unresolved ungoverned mandatory failure; no explicit environment/artifact Leader GO.   | Production release decision; no execution is authorized here.        |

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

The following read-only public metadata commands are usable for BUILD-02; they
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
never transfer these runs to another SHA. Policy reads require appropriate
repository authorization. An unauthorized policy read or empty ruleset list cannot
establish absence of classic protections. Review the actual workflow files
[backend-ci.yml](../../.github/workflows/backend-ci.yml) and
[repository-validation.yml](../../.github/workflows/repository-validation.yml)
against the authorized required-check policy.

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
exact candidate SHA and source. NOTICE-04 waiver requires the separate formal
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
