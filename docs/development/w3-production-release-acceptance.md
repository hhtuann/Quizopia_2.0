# W3 production release acceptance

Status: **REGISTER READY FOR LEADER REVIEW — PRODUCTION NO-GO.** Recorded
2026-10-14. This is a release governance/evidence overlay for the merged Assessment
backend through PR #84, develop `e615764613b661f8503de5fb5494fc7ea5224ba3`.
No approved release candidate, named DRI, staging execution authorization,
independent security approval or Leader production GO is recorded.

## Scope and authority

The [59-gate W3-FINAL matrix](../architecture/w3-final-acceptance-matrix.md) remains
the historical engineering baseline: **46 PASS, 0 FAIL, 6 BLOCKED, 5 NOT VERIFIED,
2 NOT APPLICABLE**. This overlay tracks exactly its **11 open gates** and preserves
their IDs and inherited statuses. Current overlay counts remain **6 BLOCKED and
5 NOT VERIFIED**; no release gate is closed by this assignment.

The [YAML register](w3-production-evidence-register.yaml) is authoritative for
current release-gate criteria, ownership proposals, procedures, dependencies,
blockers and evidence. The [human inventory](w3-production-release-gates.md) and
[operator handoff](w3-production-evidence-handoff.md) summarize it. Do not copy the
other 48 historical rows or relabel their local evidence as deployed acceptance.

[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md), including EC79-01..04,
[ADR-014](../decisions/ADR-014-access-token-principal-and-authority-contract.md) and
[LD-05](../architecture/ld05-enforcement.md) retain authority. Ineligible, disabled
or unverifiable recipients can block delayed closure indefinitely: this is an
accepted operational limitation. Missing accessible confirmation means no cutoff
and no closure-driven submission. No synthetic delivery, auto-abandon,
administrative force-close or remediation bypass is authorized. No product
retention period, SLA or timeout change is introduced.

Frontend/browser integration and PR #77, W3-M1 Activity Evidence, W3-M2
LiveKit/camera monitoring, the whole Wave 3 milestone and full-product launch each
require separate acceptance. Backend release evidence does not certify them.

## Ownership and decision protocol

Every proposed team is a recommendation. Every gate DRI is
**UNASSIGNED — LEADER ASSIGNMENT REQUIRED**. Executor and independent reviewer
fields identify required roles, not invented appointments. Leader must assign
actual people and their authority; execution operators and independent reviewers
must be distinct. A backend implementer cannot approve privacy policy, production
TLS or release GO by virtue of implementing the service.

Before any external drill, the assigned owner must bind the register procedure to
an approved target, reviewed provider-specific commands, synthetic dataset,
operator permissions, bounded window, stop conditions and recovery plan. The
[existing deployment runbook](w3-final-deployment-runbook.md) provides service
ordering and safeguards. Its existence is not evidence of execution.
Environment-specific staging permission is separate from final production GO;
staging is how the gates obtain evidence. It must not require a prior claim that
all staging-dependent gates have already passed. No Stage B–E deployment, fault,
backup/restore or notice action is authorized by this register itself.

A PASS requires an actual completed evidence receipt and authorized gate approval.
The independent reviewer retrieves sources and verifies integrity, authority,
exact candidate/artifacts/configuration and limitations. Only NOTICE-04 may become
NOT APPLICABLE here, after an explicit written Leader/Security waiver with risk,
scope and expiry/review conditions. Other exceptional risk dispositions require
separate explicit governance; this register does not silently waive them. Keep the
actual failed/open state visible alongside any such decision.

APPROVAL-01 needs independent Security approval of the actual candidate,
configuration and topology, reconciled technical gates, and explicit Leader GO for
the exact environment/artifacts with named incident/rollback owners. Codex
self-review, CI success, register completeness and GitHub merge are not those
approvals. A new SHA, image, migration or security configuration never inherits GO
automatically: evaluate gate-specific revalidation first.

## Dependency order

| Stage                   | Required work and boundary                                                                                                                                                                                                                  |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A — Governance/baseline | Select exact RC; confirm BUILD-02 required CI and protections; appoint DRIs/reviewers; approve privacy rules, workload/latency/error/resource budgets and durability/RPO/RTO; decide NOTICE-04 soak requirement and interruption treatment. |
| B — Infrastructure      | Under separate target authorization provision private TLS/network and exact SERVICE clients/scopes; backup/fencing; Identity/Redis authority; cursor keys and restricted runtime roles.                                                     |
| C — Staging engineering | Execute target-scale migration, drain/rollout, multi-node failover, repeated load, deployed telemetry/redaction and authenticated end-to-end negative smoke checks.                                                                         |
| D — Notice/closure      | Verify real authenticated inbox and narrow eligibility, durable PUBLISH/CONFIRM, actual 48-hour soak if mandated, individual cutoff, Teacher COMPLETE and post-CLOSED owner review.                                                         |
| E — Decision            | Independently reconcile evidence/security, record exact scoped Leader GO/NO-GO and final release record.                                                                                                                                    |

The register dependencies form a DAG of **final gate acceptance**, not a prohibition
on preparatory work. For example, approved privacy rules precede backup practice;
actual matching controls are reviewed before PRIVACY-02 closes. Operators may
prepare plans, collect baseline telemetry and rehearse components under bounded
staging authorization while final gates remain open. Failed high-impact security
or integrity prerequisites must never be bypassed to activate dependent traffic.

## Evidence currently available

Actual public all-event Actions metadata for merged `e615764...` identifies
[Backend CI](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38507476186) and
[Repository validation](https://github.com/hhtuann/Quizopia_2.0/actions/runs/38507476067)
as completed success. All eight backend jobs and both repository-validation jobs
succeeded. Required-check policy/classic protections and an approved RC remain
unverified, so BUILD-02 remains NOT VERIFIED. A PR-event connector returned no runs;
that result does not establish absence of push-event CI. Classic protection read
was unauthorized; an empty branch-rules response does not prove no protections.

The [W3-FINAL executed evidence](../architecture/w3-final-assessment-acceptance.md)
supports only its recorded local scope. Controlled database time is not a real
48-hour soak; self-signed local HTTPS is not deployed transport; same-database
peers or a database restart are not fenced failover; a local dump/restore is not
production DR; the small 93.878 ms migration is not target-size acceptance; local
capacity samples are not an SLA. The unchanged 12,613-question fixture and initial
2 MiB engineering budget remain, with complete-answer compatibility required.

The register stores these as partial observations, not completed gate receipts.
External receipts/approvals remain empty. Executed local validation, Git scope,
protected-file/default-off checks and author/delegated Codex review findings belong in
this workstream PR/report; they do not close external runtime gates. Independent human Security/Leader review remains pending. The complete
1,149-test suite is not rerun solely to reproduce historical evidence.

## Validation and evidence protection

Run the [lightweight validator](../../services/assessment-service/contracts/validate-w3-production-release.py)
with the repository's existing PyYAML environment, then documentation formatting,
local references, changed-scope secret scanning and `git diff --check`. The
validator checks structure, inherited IDs/statuses, dependencies, evidence and
approval requirements. It cannot authenticate an external document or a human's
authority; independent retrieval/review remains mandatory.

Evidence receipts bind actual UTC time, target classification, executor, tools,
expected/actual outcome, exact RC, integrity digest, real source and limitations.
Deployment evidence additionally binds image digests and a secret-free
configuration fingerprint. Keep credentials, tokens, private locators, real Student
answers, dumps and confidential logs out of Git. Store sensitive material in
approved restricted storage and commit only sanitized manifests and actual safe
references. Readiness of this register preserves **PRODUCTION NO-GO** until the
required external evidence and real approvals exist.

## Executed local workstream checks

Executed 2026-10-14 in the local development checkout using Python 3.14.4 and
the existing repository verification packages; no dependency was installed.
These are package checks, not external gate execution or independent security
approval. Run from the repository root:

```bash
PYTHONPATH=gateway/target/w3a-evidence/openapi-python python3 services/assessment-service/contracts/validate-w3-production-release.py
PYTHONPATH=gateway/target/w3a-evidence/openapi-python python3 services/assessment-service/contracts/test-w3-production-release.py
PYTHONPATH=gateway/target/w3a-evidence/openapi-python python3 services/assessment-service/contracts/verify-w3-final.py
frontend/node_modules/.bin/prettier --check docs/development/w3-production-*.md docs/development/w3-production-evidence-register.yaml docs/open-questions.md docs/product/assessment.md
git diff --check
```

| Check                                             | Observed result                                                                                                                                                                                                                                |
| ------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Register and pinned historical matrix             | PASS; exactly 11 open gates, 6 BLOCKED / 5 NOT VERIFIED.                                                                                                                                                                                       |
| Validator safety fixtures                         | PASS; 28 synthetic in-memory tests, including missing/duplicate gates, placeholder references, SHA/configuration binding, authority and waiver checks. Fixtures are not execution evidence.                                                    |
| Existing static W3-FINAL audit                    | PASS; three OpenAPI structures, exact routes/least privilege, 11 answer fixtures, protected hashes and default-off settings. Its cached 1,149 test-report totals were read, not rerun.                                                         |
| Baseline byte comparison                          | PASS; 29 protected matrix/migration/ADR/policy/contract/configuration/example files match merged `e615764...`; all six Assessment migrations are unchanged.                                                                                    |
| Static production profile review                  | PASS; 19 default-off flags across Gateway/Assessment/Identity, no production-profile override enabling them, private HTTPS required and insecure-loopback defaults false. External environment overrides remain unverified.                    |
| Scoped local reference, credential and path audit | PASS; exactly eight documentation/validator paths, 44 local Markdown references, no private-key/token/JWT/cloud-key or credential-bearing database URL signatures found. This is a scoped pattern scan, not proof of universal secret absence. |
| Prettier and whitespace                           | PASS for the changed Markdown/YAML and `git diff --check`.                                                                                                                                                                                     |

Draft construction initially failed checks for a not-yet-created linked document
and missing per-gate ownership fields; both were corrected and checks rerun.
No new runtime acceptance result is inferred from these local checks.
