# W3-FINAL — Future authorized Assessment deployment runbook

Status: **PROCEDURE ONLY — not executed in production and not activation authority.**
See [acceptance evidence](../architecture/w3-final-assessment-acceptance.md) and
[gate matrix](../architecture/w3-final-acceptance-matrix.md). Execute rollout only
after explicit Leader/security authorization and closure of applicable external
gates. Testcontainer/loopback evidence is not staging acceptance.

## Dependency and credential matrix

| Component                     | Required authority/configuration                                                                                                                                                 | Secret/trust ownership                                                             | Default and release dependency                                                                   |
| ----------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| Identity USER admission       | Primary writable PostgreSQL, complete Redis authority, exact allowed Gateway/Assessment/Quiz/Classroom clients; `IDENTITY_USER_ADMISSION_ENABLED`                                | Identity database/Redis credentials, RS256 signing key; private server certificate | false; independently verify reconciliation and per-actor equality before callers                 |
| Identity recipient capability | `IDENTITY_NOTICE_RECIPIENTS_ENABLED`, exact Assessment client ID, `identity.notice-recipient.read`, secure requests                                                              | Identity-owned capability/allowlist; no USER credential borrowed                   | false; prove actual private HTTPS, wrong-client/scope and minimal response                       |
| Gateway                       | Own `identity.user-admission` client; issuer/JWKS; `USER_ADMISSION_*`; Assessment route origin                                                                                   | Gateway-only client secret and TLS trust; never Assessment's secret                | Admission and business gates false; original USER bearer forwarded only to reviewed destinations |
| Assessment                    | Own OAuth client with `identity.user-admission`, `quiz.version-snapshot.read`, `classroom.teacher-authority.read`, `classroom.membership.read`, `identity.notice-recipient.read` | Assessment-only client secret, own DB credential, TLS trust                        | Admission/source/business switches false; provision individual scope-specific calls              |
| Quiz / Classroom              | Own separate `identity.user-admission` clients; exact Assessment source caller/scopes and private authority enabled explicitly                                                   | Each owns its DB and secret/trust configuration                                    | `INTERNAL_AUTHORITY_ENABLED=false`; no cross-service database grants                             |
| Assessment cursor             | `ASSESSMENT_CURSOR_ACTIVE_KEY`, runtime `ASSESSMENT_CURSOR_KEY_V1` and accepted rotation versions                                                                                | Runtime Base64 key material ≥32 bytes; no committed default key                    | Provision all accepted versions before active-key rotation; test actor/resource binding          |
| Inbox readiness               | Both `ASSESSMENT_INBOX_ENABLED`; Assessment `ASSESSMENT_EDGE_BASE_URL`, `ASSESSMENT_EDGE_INBOX_VERIFIED`, `ASSESSMENT_RECIPIENT_ELIGIBILITY_ENABLED`                             | Pinned private Gateway HTTPS origin/trust and network policy                       | false/blank; attestation alone is insufficient                                                   |
| Worker                        | `ASSESSMENT_CLOSURE_WORKER_ENABLED`, 1..50 unit budget, bounded scan/poll controls                                                                                               | Assessment runtime; restricted workflow audit/metrics                              | false; enable only after real publish/confirm/accessibility proof                                |
| Persistence / observability   | Assessment Flyway owner separated from runtime DML role; backups, audit and Micrometer scrape policy                                                                             | Per-service DB credentials, backup encryption/access and telemetry sink controls   | No superuser, trigger/function ownership or broad public metrics access for runtime              |

Never copy fixture secrets, zero-filled cursor keys, 96-hour test token TTL or
insecure-loopback overrides into deployment examples. Provision credentials through
existing controlled service registration, not a new public administration API.
Required scopes are not a blanket introspection or USER impersonation capability.

## Preflight, migration and traffic drain

1. Obtain named authorization, target environment identity and database host/primary
   verification. Inventory every old Gateway, Assessment and worker instance. Do not
   point the test runner at operator-provided databases.
2. Take and verify an access-controlled backup. Rehearse restore and compare identities,
   snapshot/result digests, cutoff/recipient evidence, receipts and audits on isolated
   deployment-sized data. Approve RPO/RTO and acknowledged-write durability separately.
3. Drain/block all Assessment writers and optional workers. Prevent old binaries
   from resuming writes against V5/live cutoffs; preserve accepted retry identities.
4. Confirm Flyway V1–V5 checksums, current version, disk/WAL/temporary-space headroom,
   lock/statement budgets, backup and roll-forward recovery. V5 takes ACCESS EXCLUSIVE
   locks; do not infer deployment duration from the small local rehearsal.
5. Run reviewed Flyway with the migration owner against the verified target. Runtime
   gets schema usage and required DML/execute privileges, not CREATE/ALTER/TRUNCATE,
   function ownership, superuser or cross-service database access. Verify schema
   validation, all triggers/FKs and unchanged legacy snapshot/result identity.

## Future activation order and smoke checks

1. Provision and verify private DNS/routing, certificates, hostname/trust validation,
   ingress restrictions, primary fencing and logging redaction. Deny `/internal/**`
   at the public edge. Never bypass a failed certificate check or follow redirects
   carrying credentials.
2. Under separate Identity approval, verify primary/Redis reconstruction and USER
   admission using each exact client. Stale/missing authority stays unavailable;
   connectivity alone is not recovery. Verify OAuth outage/recovery and no logout
   or refresh-family invalidation solely from authority failure.
3. Provision Quiz/Classroom narrow source authority and Assessment source clients.
   Configure Gateway/Assessment admission and private origins before routing traffic.
4. Enable only separately authorized `PUBLICATION_ENABLED`, `PUBLICATION_SOURCES_ENABLED`,
   `ATTEMPTS_ENABLED` and `RESULTS_ENABLED` at their owning boundaries. Prove CLASS
   membership, PUBLIC locator rules, saved submit, timeout, score/redaction and review
   policy. A switch never replaces authentication, admission or ownership.
5. Provision/verify Identity recipient eligibility. Enable both actual inbox routes
   and configure the pinned Gateway origin. Require technical
   `/actuator/health/assessment-inbox` readiness **and real authenticated owner inbox/detail
   access**, including wrong recipient and former CLASS member. Prove disabled,
   malformed/unavailable readiness and stale authority fail closed.
6. Only after those results are reviewed set the deployment attestation, enable the
   narrowly required recipient dependency and authorize `CLOSURE_ENABLED`. Execute a
   synthetic isolated/staging REQUEST, committed PUBLISH, fresh confirmation and
   owner inbox check. Missing confirmation must leave cutoff null. Never backdate a
   real notice to shorten its 48 hours; simulated clocks belong only in isolated tests.
7. Enable optional workers last. Observe bounded recovery and replay, CANCEL before
   finalization and Teacher-only COMPLETE. Complete any required real 48-hour soak
   and multi-node/failover tests before release approval. Reconcile through REST.

## Monitoring, privacy and blocked-closure operations

Use actual aggregate metrics `assessment.closure.unconfirmed`,
`assessment.closure.oldest_seconds`, `assessment.closure.eligible` and
`assessment.closure.work` with the bounded `outcome` tag. Track `ineligible`,
`authority_unavailable`, `inbox_unavailable`, `failed`, `scan_failed` and
`audit_retry_required`, alongside publish/confirm/finalize progress. Alert thresholds
need measured operational review; this document invents no SLA or Student policy.

Restrict metrics, traces and append-only workflow audits. Labels must not contain
Student/Attempt/Publication IDs, locators, answers or credentials. Authorized operators
may inspect restricted durable audit for recovery, without copying it to public alerts.
Increasing ineligible counts indicate the accepted possibly indefinite block;
authority/inbox failures indicate unavailable dependencies, not permission to force-close.
Preserve retry/cutoff history. Any remediation bypass requires a new Leader decision.

Keep Tomcat HTTP decoder, Hibernate factory/bind and Spring JDBC bind diagnostics
suppressed. Root DEBUG/TRACE must not re-enable raw request/configuration dumps.
Review explicit category overrides, access logs, query strings, proxy/APM/exporter
capture and support bundles. Redact Authorization, USER evidence, cookies, client
secrets, signing/encryption keys and protected bodies. Local log checks do not certify
deployed telemetry. Retention/erasure durations remain an approval gate, not a guessed TTL.

## Emergency disable and data-preserving recovery

Disable optional workers to stop background processing when needed; this does not
invalidate confirmed cutoffs, reset countdowns or prevent authoritative Student/Teacher
reconciliation. Worker failure must not fabricate delivery or imply ABANDON.
Blocking business traffic or disabling a route can deny access; it cannot erase durable
rights/history or make old confirmed evidence nonactionable. Evaluate live workflows
under the accepted in-flight/outage semantics and obtain incident/security direction.

Preserve authenticated inbox availability wherever safe. Do not reset cutoff timestamps,
delete history, use old bypass-capable binaries, restore a stale authority projection as
truth, auto-abandon or perform administrative force-close. Genuine CANCEL remains only
the owning Teacher's approved pre-fence operation.

After uncertain commit, re-establish current authority and retry the exact same durable
key/payload; an acknowledged 202 replays unchanged and a new key continues work. Never
infer rollback from a socket error. Prefer reviewed roll-forward fixes; no destructive
down-migration or rollback that loses acknowledged answers/security/notice evidence.
Database promotion requires fencing and verified preservation of acknowledged authority
and assessment commits. Missing infrastructure evidence keeps release NO-GO.
