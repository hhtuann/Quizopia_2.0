# W3 managed PostgreSQL HA provider assessment

Status: **READ-ONLY DESIGN RESEARCH — PROVIDER UNSELECTED; RECOVERY-05 BLOCKED.**
Official documentation inspected 2026-10-14. No account, credentials, cloud resource,
benchmark, failover or operational acceptance was used/executed. This compares two
specific options for the selected
[managed-cloud synchronous-durability/fencing direction](w3-production-release-approval-operator-readiness.md#6-provider-specific-zero-acknowledged-write-loss-evaluation).
It is not a vendor selection, deployment approval, contractual guarantee or gate receipt.

## Documented mechanisms and qualification gaps

| Dimension                       | AWS RDS for PostgreSQL — Multi-AZ DB instance, one standby                                                                                                                                                                                                                                                                                                                                                                                                         | Google Cloud SQL for PostgreSQL — regional HA                                                                                                                                                                                                                                                                                                                    |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Replication/fault scope         | Synchronous standby in a different AZ, intended for instance/AZ disruption. Standby does not serve reads. This option is distinct from the two-readable-standby RDS DB cluster and Aurora. [AWS single-standby architecture](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZSingleStandby.html).                                                                                                                                           | Regional primary/standby uses two zones; writes reach both zones' disks before commit is reported. Supports instance/zone failover. [Cloud SQL HA](https://docs.cloud.google.com/sql/docs/postgres/high-availability).                                                                                                                                           |
| Commit/durability qualification | Official architecture states synchronous replication, with possible increased commit latency. Reviewed pages do not fully specify durable WAL acknowledgment/fencing for every partition or concurrent failure. Obtain exact provider/version/configuration and support/design confirmation for the project's acknowledged-write definition. [AWS replication/latency](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZSingleStandby.html). | Explicit cross-zone-before-commit wording is evidence for evaluating the selected objective. Failover requires healthy standby; both instances unresponsive blocks failover. This is not an unlimited guarantee across regional or compound disasters. [Cloud SQL requirements](https://docs.cloud.google.com/sql/docs/postgres/high-availability#requirements). |
| Promotion/old writer            | Automatic standby promotion and endpoint DNS change require existing connections to be re-established. These observations alone do not prove rejection of every stale-primary connection; require provider-specific fencing evidence and authorized negative tests. [AWS failover](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZ.Failover.html).                                                                                         | Provider describes keeping the promoted primary and destroying/recreating the recovered original as standby; clients reconnect. Validate partition/old-writer behavior, not merely endpoint continuity. [Cloud SQL failover lifecycle](https://docs.cloud.google.com/sql/docs/postgres/high-availability).                                                       |
| PostgreSQL baseline             | PG17 versions are published; exact minor, region/class availability, extensions and migration/runtime privileges still need qualification. [RDS PostgreSQL versions](https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-versions.html).                                                                                                                                                                                                | PG17 appears in the supported-version matrix. Select version explicitly and verify regional/edition availability and controls. [Cloud SQL versions](https://docs.cloud.google.com/sql/docs/postgres/db-versions).                                                                                                                                                |
| Cost/resource decision          | Quote exact region, Multi-AZ instance/storage/IOPS, backups, networking, telemetry and support through current pricing/calculator; no budget or price is selected. [RDS pricing](https://aws.amazon.com/rds/postgresql/pricing/).                                                                                                                                                                                                                                  | HA adds a standby cost; the HA overview describes doubled CPU/RAM/storage versus standalone. Obtain region/edition/resource/backup/network/telemetry quote; no price/budget selected. [Cloud SQL HA cost](https://docs.cloud.google.com/sql/docs/postgres/high-availability).                                                                                    |

The sources describe service mechanisms, not measured Quizopia RTO/SLO. Published
provider failover-time examples are deliberately not adopted as application recovery
commitments. The project requires actual trusted Identity/Redis reconstruction and
end-to-end authenticated availability, not merely a reachable database endpoint.

## Inferences and limits — not provider assurances

Both options warrant qualified evaluation under a deliberately bounded instance/
single-zone failure model. **This is an engineering inference from the cited
mechanisms, not proof that either option satisfies RECOVERY-05.** Neither reviewed
set establishes the complete required old-writer/fencing, acknowledged-write,
compound-failure and application recovery evidence for this deployment.

A regional HA pair must not be described as zero loss for every multi-region,
multiple-storage or simultaneous disaster. Platform/DBA/Security must explicitly
accept the tested failure model and identify residual failures; preserve the zero
loss of acknowledged committed Assessment and Identity writes within that model.
No asynchronous-only or backup-only replacement is authorized. Backup/restore is a
separate DB-08 discipline; the cited Cloud SQL HA page's standalone recovery section
warns restored/promoted standalone recovery can omit recent transactions. That is
a different scenario from regional HA and cannot silently substitute for it.
[Cloud SQL standalone recovery limits](https://docs.cloud.google.com/sql/docs/postgres/high-availability#recovery-options-for-standalone-instances).

Commit acknowledgment can be lost in transport even if the database committed.
The application must reconcile trusted authority and retry the same durable key/
payload, preserving one result/receipt/cutoff identity. Provider endpoint failover
alone does not settle an uncertain commit, reconstruct Redis or prevent old
application binaries from writing. These are project acceptance requirements,
not claims that provider documentation proves them.

## Required operator/design return before selection

Platform/DBA plus independent Security must return:

1. Exact provider product/edition/region/PG17 minor, topology/fault domains, supported
   migration/runtime roles/extensions and sanitized durability/commit settings.
2. Written commit/WAL replication/standby-health semantics, automatic/manual promotion
   conditions, fencing authority, old-primary/old-client rejection and partition/
   simultaneous-failure limits. Obtain authoritative clarification where public
   documentation is insufficient; distinguish contractual assurances from inference.
3. Accepted failure model and acknowledged-write manifests covering Assessment saved
   answers, grades/results, durable receipts, Publication/closure and notice/cutoff
   facts plus Identity security authority. Specify reconstruction/reconciliation and
   uncertain-commit replay, without cross-service SQL or stale authority fallback.
4. Bounded synthetic feasibility drill design with actual permitted provider fault
   controls, start/end/stop/recovery semantics, restricted logs and independent review.
   Include losing the standby, losing/partitioning the primary, reconnecting old
   writers, in-flight commit loss, failback and actual application recovery.
5. Actual region/resource/cost quote and operator readiness; data residency/privacy,
   TLS/trust, secret custody, backup/access/retention and telemetry controls. Then obtain
   an explicit provider/topology/budget decision and target-specific authorization.

Only later authorized measurements may inform RTO and 100-Student SLO decisions;
freeze quantitative criteria before formal acceptance. A documentation comparison
cannot PASS RECOVERY-05, DB-08, TLS-02 or DEPLOY-02. All eleven release-gate states,
empty external receipts/approvals and **PRODUCTION NO-GO** remain unchanged.

## Retrieval and freshness

Only current official AWS/Google documentation was used. Architecture/failover,
version and AWS pricing pages were opened; the Google pricing page exceeded tool
response size, so this assessment relies on the opened HA overview for qualitative
cost and requires a real operator quote. No prices were scraped or invented.
Recheck sources, provider terms and exact target settings when an option/version/
region is selected or before executing any acceptance drill. Retain actual retrieved
configuration and review references in the restricted evidence pack; these public
links are research provenance, not deployment receipts.
