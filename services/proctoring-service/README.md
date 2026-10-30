# Proctoring Service

Independent Spring Boot 4.1.1 / Java 21 service. The current W3-M1 increment adds
Proctoring-owned PostgreSQL/Flyway session, activity evidence and access-audit
persistence, six exact USER endpoints including current Teacher session lookup and narrow Assessment authority integration.
See the [M1 contract](../../docs/specifications/w3-m-proctoring-contract.md),
[implemented OpenAPI](contracts/monitoring.openapi.yaml) and
[checkpoint](../../docs/architecture/w3m-backend-implementation.md).

Run `./mvnw verify` (Windows: `mvnw.cmd verify`). PostgreSQL integration tests use
owned Testcontainers. Default local port is 8086; `.env.example` lists configuration.

`PROCTORING_ENABLED=false` closes the business adapters. Enabled local synthetic
integration also requires this service's own Identity Client Credentials admission
client and `PROCTORING_ASSESSMENT_BASE_URL`, with the narrow
`assessment.proctoring-authority.read` scope. Assessment must separately allowlist
that exact caller. Each request requires current USER and resource authority;
Gateway approval or JWT signature alone never substitutes for downstream admission.
HTTP exceptions are explicit loopback-only local test settings; use private HTTPS
with validated hostname/trust for deployment.

Production-profile enablement remains closed until actual M2 bundle integration
and product/privacy/security acceptance. M1 is not an Activity-only product mode.
No default retention period exists. The owner-controlled `monitoring_purge_expired`
function has no PUBLIC execution grant or scheduler and only deletes already-expired
evidence/audit rows. Runtime roles must not own tables/functions, disable guards or
receive that retention privilege. Session-binding erasure and final policy/control
parity still require an approved policy and reviewed implementation.

The dependent M2 increment adds [four bodyless media-grant operations](contracts/media.openapi.yaml),
durable room/grant intent and bounded recovery through the official LiveKit JVM SDK.
See the [M2 contract](../../docs/specifications/w3-m2-media-contract.md).
`PROCTORING_MEDIA_ENABLED=false` and `PROCTORING_MEDIA_WORKER_ENABLED=false` remain
independent defaults. `UNVERIFIED` mode denies issuance/renewal; explicit non-production
qualification configuration is a synthetic operator attestation, not provider
approval. Enabled production media is unconditionally rejected. App `leaseUntil`
is not an SFU hard expiry; self-hosted removal cannot revoke an old token. Never
store/log the returned bearer; actual Cloud/refresh/clock-bound revocation and
privacy acceptance remain open. The later integration adds a producer-owned authoritative answer-persistence
outbox/Rabbit/inbox adapter, with verification/review still separate; see the
[contract](../../docs/specifications/w3m-authoritative-answer-integration.md). Browser answer-change observations are untrusted;
this service cannot alter answers, grades, deadlines or Attempt state. No recordings,
clipboard, URLs, keystrokes, answer content or academic sanctions are implemented.
Drain/block bypass-capable older Gateway/downstream instances before any future
separately authorized activation. Deployment/independent review is not inferred
from local tests or a source merge.

## Authoritative answer metadata consumer

`PROCTORING_ASSESSMENT_EVENTS_ENABLED=false` closes the dedicated consumer. Enabled
isolated integration requires exact private vhost/queue read ACL, independent broker
identity, configured authenticated Assessment publisher identity and trusted TLS.
Production consumption is unconditionally rejected pending policy/security review.
Durable inbox/deduplication or fingerprint-only quarantine precedes ACK; DB failure
requeues, missing sessions wait without creation and historical facts can attach
post-END without reopening eligibility. No answer content or raw invalid payload is
stored. A transport-oversize poison requires approved audited operator quarantine.
No automatic TTL or global Rabbit/gradebook convention is selected.

## FB-015 PR C corrections: quiz purge (Leader review)

V4/V5 + `QuizPurgeService`/`SecurityDeletionController` implement the ADR-018
quiz-ID revocation fence and the dependent hard purge behind
`PROCTORING_DELETION_ENABLED=false` (`quizopia.deletion.enabled`). The private
surface is documented in `contracts/quiz-purgings.openapi.yaml`.

- **Least privilege (H1).** V5 separates the privileged table owner
  (`quizopia_proctoring_owner`, NOLOGIN) from the application runtime role
  (`proctoring`, the `DB_USERNAME` the service authenticates with). The runtime
  role receives USAGE + SELECT/INSERT/UPDATE only - **no DELETE or TRUNCATE
  anywhere** - plus EXECUTE on `monitoring_purge_quiz` alone. Every child DELETE
  inside the procedure is bound to the captured session scope (the quiz's own
  sessions plus legacy rows resolved through the Assessment-owned dependents
  inventory); the transaction-local markers only inform the immutable-history
  guard triggers as a second layer. The operator-only
  `monitoring_purge_expired` retention procedure stays ungranted to the runtime
  role. **Provisioning:** the cluster must provide the owner role, the runtime
  role and a migration principal that is a member of the owner role
  (  `quizopia_proctoring_flyway`, used as `spring.flyway.user`; provisioned by
  `infrastructure/postgres/01-init-databases.sh` locally - a pre-FB-015 local
  volume must be recreated (`docker compose down -v && docker compose up -d
  postgres`) or the roles provisioned by hand before the service boots). V5 fails
  loudly when
  the roles are missing; future migrations creating tables must repeat the
  ownership transfer + grants.
- **Bounded rounds (H3).** Each invocation is one transaction with a hard total
  deleted-row budget (`PROCTORING_DELETION_PURGE_BUDGET`, default 5000,
  bounded 100..50000). An exhausted budget durably persists `purge_progress`
  and answers `IN_PROGRESS`; `PURGED` (with the `verified_zero` receipt) is
  emitted only by a final round whose zero-remaining verification passed in
  that same transaction.
- **Legacy dependents (H5).** Pre-V4 sessions (`quiz_id NULL`) are resolved
  ONLY through the optional `publicationIds`/`attemptIds` arrays in the purge
  request (the Assessment dependents inventory, fetched by the Quiz deletion
  coordinator). When the mapping is unavailable and any legacy row exists, the
  purge answers `RECONCILIATION_BLOCKED` - never a false `PURGED` - and the
  coordinator surfaces `WAITING_DEPENDENCY` with reason
  `legacy_dependents_unresolved`.
- **Immutable retry inventory.** V5 stores canonical input arrays and an append-only
  session/attempt/publication resolution snapshot in restricted `purge_inventory`.
  Capture consumes the same work budget as content deletion. Input arrays cannot
  change on a retry (`INVENTORY_MISMATCH`), even after Assessment deletes its rows;
  the coordinator must retain its original inventory. Null mapping with unresolved
  legacy rows still returns `RECONCILIATION_BLOCKED`. Budget exhaustion preserves
  capture/step progress and returns `IN_PROGRESS`. The one command-inbox row and a
  fixed number of progress/receipt writes are bounded bookkeeping outside the content
  budget; no content table gets its own independent budget.
- **Inbox and anti-resurrection.** `assessment_monitoring_inbox` (APPLIED, PENDING,
  QUARANTINED and pre-session publication copies) participates in purge and zero
  verification. Durable resolved IDs fence stale delivery after local sessions are
  gone. Inbox consumers and ordinary monitoring/media transactions take advisory
  transaction lock `18015` before row locks; privileged purge, insert guards and
  custodian holds use the same boundary. This deliberately serializes local writers
  across quizzes, with existing lock/statement timeouts and retryable failure;
  throughput qualification remains open. Triggers stay enabled.
  Advisory locks do **not** refresh an MVCC snapshot: every privileged function,
  admission probe and write trigger enforces **READ COMMITTED** in PostgreSQL.
  REPEATABLE READ/SERIALIZABLE callers receive SQLSTATE `25001` and must retry in
  a fresh READ COMMITTED transaction. Java locking alone is not this boundary.
  Replaying a completed operation and reading `purged` both recheck current absence
  against the durable inventory; a stale receipt cannot attest restored/late payloads.
  A failed replay check invalidates `verified_zero` and reports `RECONCILIATION_BLOCKED`.
- **Media provider erasure (H7).** When bindings exist, SQL records a request and
  returns `PENDING_PROVIDER` before deleting content. Original room names and
  participant identities remain in the fenced media tables for durable retries.
  Independent `quizopia_proctoring_custodian` custody may append an operation-bound
  receipt reference to `proctoring_provider_cleanup` only after verifying actual
  provider cleanup against the complete frozen inventory. Runtime cannot write it.
  Local purge then proceeds; status reports `ACKNOWLEDGED`. No automated provider
  erasure adapter or real provider cleanup receipt is claimed by synthetic tests.
  Removing a participant, changing grant state, or marking a room deleted is not
  itself a provider-erasure receipt. Disabling media configuration does not bypass
  existing artifacts. No media bindings means no fabricated erasure request.
- **Local custodian holds.** Provision `quizopia_proctoring_custodian NOLOGIN`
  independently of runtime/migration credentials before a non-superuser migration.
  Grant that role only to the independent restricted custodian identity. Use
  `proctoring_set_hold(holdId, quizId, sessionId-or-NULL, authority, reason, reviewer,
  expiresAt, reviewAt)` with nonblank authority/reason/reviewer and finite future
  expiry/review dates. Session scope is optional; quiz scope conservatively blocks
  the complete operation. Holds deny ordinary session access and protect both quiz
  purge and the older expired-evidence procedure. Hold creation serializes with
  purge and is rejected once purge receipts show the irreversible boundary was
  crossed; it cannot retroactively restore deleted records. No runtime hold
  override/release exists. Expiry must follow approved policy, not an invented TTL.
  V5 rejects unsafe runtime membership/ownership and grants no runtime writes to
  holds, purge inventories, progress, receipts or provider-erasure bookkeeping.
  The private status response always includes boolean `retentionHoldActive`, computed
  locally even before the first purge inventory exists. The coordinator must require
  an explicit `false` before any purge; absence/unknown must fail closed. The local
  purge still rechecks holds under its database serialization lock.
- **Flyway boundary.** V5 excludes `flyway_schema_history` from ownership/grant
  sweeps, avoiding a cross-connection Flyway history-lock deadlock. Newly created
  functions are transferred to the NOLOGIN, non-superuser owner **after** creation;
  routine search paths explicitly put `pg_temp` last. The service uses the `public`
  schema; historical V2→V3 checksum tests remain pinned to their intended versions.
  PUBLIC table grants (including malicious default ACLs) are revoked from owned
  tables. Effective table/column write and destructive privileges are validated for
  runtime and every reachable member role, including SET ROLE-only membership.
  Effective EXECUTE on operator-only hold/expiry functions also fails migration.
  Inherited unsafe group ACLs require provisioning correction; they are not silently
  rewritten or accepted. Future migrations must repeat ACL sanitation and validation.

### Verification and limits (FB-015)

Focused real PostgreSQL 17 tests cover actual fresh Flyway V1→V5 and non-superuser
V4→V5 upgrade/validation; runtime credentials, temp-table shadowing, immutable
inventories, one-row durable retries, legacy evidence/media/audits, inbox stale
delivery and pre-session copies, provider-unavailable retries, custodian holds,
expired-evidence protection and concurrent hold/purge serialization.
Two-connection tests additionally establish old runtime/custodian snapshots before
committing a hold or purge, then assert direct SQL purge/inbox/hold rejection; fresh
READ COMMITTED retries reconcile normally. Malicious PUBLIC default-table grants and
inherited/default function grants are exercised through real Flyway migrations and
real runtime connections. An inconsistent receipt/inbox fixture proves receipt
replay/status reverify absence. HTTP tests verify true/false `retentionHoldActive`
before any purge, including expiry, without enabling production capabilities.

2026-10-29 independent-review corrections: focused purge/HTTP suites **30 tests passed**.
Fresh full `mvnw.cmd verify`: **259 passed, zero failures/errors/skips**, including
ArchUnit, packaging and Spotless.
The non-superuser V4→V5 test also verifies that
Flyway history ownership stays with the migration identity and validates checksums.

Run `mvnw.cmd -Dtest=QuizPurgeLegacyDependentsIntegrationTest,QuizDeletionFencePurgeIntegrationTest test`
and `mvnw.cmd verify`. Testcontainers fixtures are disposable. Provider acknowledgments
in these tests are synthetic independent-custody records, not LiveKit erasure proof.
No deployed browser→Gateway→all-services deletion, production provider/backup/replica
expiry, legal-policy approval or large-scale contention acceptance follows from
these checks. All collection/media/deletion flags remain OFF by default; ADR-018
production activation and all-copy erasure remain gated.
