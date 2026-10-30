# Assessment Service

The private Proctoring authority contract requires an explicit `HISTORY` or
`LIVE_MEDIA` purpose. Teacher live authority additionally uses separate narrow
Identity/Classroom target-Student scopes and current checks; historical evidence
authorization cannot be reused for media. All new target checks default disabled.
See [the correction contract/evidence](../../docs/architecture/w3m-correction-evidence.md).

## W4-B2 realtime subscription authority

The private `POST /internal/realtime/subscription-authorizations` decision is
default-disabled (`ASSESSMENT_REALTIME_AUTHORITY_ENABLED=false`) and requires the
exact dedicated Gateway realtime SERVICE client: RS256 `principal_type=SERVICE`,
`sub` equal to `ASSESSMENT_REALTIME_GATEWAY_CLIENT_ID`, audience exactly
`quizopia-assessment-realtime-authority` and scope
`assessment.realtime.subscription-authorize`. The original USER JWT is validated
independently (signature, issuer, timestamps, roles, fresh LD-05 admission).
`ATTEMPT_OWNER` requires current Student ownership of an ACTIVE attempt under an
OPEN publication before its deadline; `PUBLICATION_TEACHER` requires the owning
Teacher of an OPEN publication. CLASS publications re-verify current Classroom
membership/teacher authority through the existing authorized integration.
Missing, ineligible or foreign resources are masked with 404 `NOT_ACCESSIBLE`;
uncertain dependencies fail closed with 503. There is no positive decision
cache and the route is never exposed through the public Gateway.

Independent Spring Boot 4.1.1 / Java 21 Assessment service.

## W4-B3 realtime invalidation outbox

A dedicated transactional outbox (V7, independent from the W3 monitoring
outbox) captures committed Publication OPEN/meaningful-extend/CLOSED and
Attempt creation/submitted/abandoned transitions via AFTER triggers gated by
the `quizopia.realtime_invalidation` connection GUC. Only the frozen six-field
metadata (`eventId`, `eventType`, `eventVersion`, `resourceKind`,
`resourceId`, `occurredAt`) is stored; no answers, grades, identities or
monitoring data. Rollback discards events; an outbox failure aborts the
writer. The bounded publisher dispatches with confirms to
`quizopia.realtime.invalidation.v1` (mandatory=false per R3 best-effort
semantics), with leased claims, exponential backoff and poison-event
quarantine. `ASSESSMENT_REALTIME_INVALIDATION_CAPTURE_ENABLED=false` and
`ASSESSMENT_REALTIME_INVALIDATION_PUBLISHER_ENABLED=false` default OFF;
production activation requires owner acceptance.

Run independently from this directory:

- Windows: `mvnw.cmd test` or `mvnw.cmd verify`
- Unix-like shells: `./mvnw test` or `./mvnw verify`

Implements the seven default-disabled W3-A Publication operations and W3-B1
Publication configuration plus PostgreSQL Attempt/result persistence. W3-C1 adds
an internal deterministic grader and atomic manual/timeout finalization core,
pending independent review. See the
[implemented Publication contract](contracts/publications.openapi.yaml) and
[W3-B1 implementation evidence](../../docs/architecture/w3b1-persistence-implementation.md).
See also [W3-C1 implementation evidence](../../docs/architecture/w3c1-grading-finalization-implementation.md).
Merged B2 supplies Student Attempt/submission/result/review. B3 adds Teacher results,
CLOSE, REQUEST/CANCEL/COMPLETE and the recipient-owned inbox. See
[B3 implementation and verification](../../docs/architecture/w3b3-closure-notification-implementation.md).
The internal finalizer requires a root transaction; callers must establish current
authority separately. The bounded closure worker defaults OFF.

## FB-012 owner-results display names

`GET /publications/{id}/results` adds a nullable `studentName` (contract `1.5.0`)
resolved AFTER ownership and page authorization commit: one bounded direct
service-to-service Identity batch (`POST /internal/security/user-profiles/batch`,
SERVICE JWT scope `identity.user-profile.read`, <=100 distinct CURRENT-page ids,
unknown ids omitted). The lookup is fail-open — any Identity failure, non-200 or
defensively invalid entry leaves `studentName` null while the authorized rows,
scores and cursor are returned unchanged and no error surfaces to the Teacher.
Zero result-name cache (Leader decision #115), no N+1, no client-supplied mapping,
no public/Gateway route for the internal Identity call. Controlled by
`quizopia.student-profiles.enabled` (env `ASSESSMENT_STUDENT_PROFILE_ENABLED`,
default false) with short independent
connect/response/total timeouts so an Identity outage cannot stall the page;
requires Identity admission credentials when enabled. The exact wire schema is in
`contracts/publications.openapi.yaml`.

Names accept Identity display names up to 100 Unicode code points and legacy
username fallbacks up to 255, without truncation. The private batch response is
capped at 100 entries and 128 KiB (including a full page of Unicode legacy
usernames). Invalid names/unknown IDs are omitted; Identity 429 rate limits,
transport errors and timeouts yield null `studentName` while preserving
authorized rows. No name cache is retained.

Default local port: 8084. See `.env.example` and the root development documentation for configuration.

## LD-05 admission boundary

### T3 FE-01 owner Publication directory

`GET /api/assessments/publications` requires a validated USER with TEACHER and
fresh current LD-05 admission, behind the existing `PUBLICATION_ENABLED` gate.
The database query filters exclusively by JWT-derived owner. Optional `state`
(`DRAFT|OPEN|CLOSED`) and `search` (literal title substring, stripped/lowercased,
maximum 300 characters) apply server-side. `limit` defaults to 20, range 1–100.
Unknown/duplicate parameters and invalid/foreign/filter-mismatched cursors return
400 `INVALID_REQUEST`. Responses are `Cache-Control: no-store`.

The safe `{items,nextCursor}` projection contains only Publication id/title,
audience/state, start/end/created times, revision and Quiz/version references.
Ordering is immutable `created_at DESC,id DESC`, with V8 owner/order and
owner/state/order indexes. Draft edits and lifecycle updates cannot move rows.
This is live keyset pagination: filter membership can change during traversal;
it is not a frozen snapshot. No total count or answer-bearing data is returned.

Continuation uses the existing `ASSESSMENT_CURSOR_ACTIVE_KEY` /
`ASSESSMENT_CURSOR_KEY_V1` deployment-backed HMAC key ring, domain-separated for
this route and bound to owner plus normalized filters; changing page size is
allowed. Provision the existing cursor key even if inbox/results are disabled.
Without an active key, issuing a continuation fails closed with sanitized 503
`PERSISTENCE_UNAVAILABLE`. Key rotation retains configured verification keys.
The exact wire schema is in `contracts/publications.openapi.yaml`.

See the [exact protected-route inventory and deployment gates](../../docs/architecture/ld05-enforcement.md).
Admission and Publication are independently disabled by default. Exact Publication
adapters enforce fresh authority; unknown/future business routes remain denied.
`ATTEMPTS_ENABLED`, `RESULTS_ENABLED` and `CLOSURE_ENABLED` default false; setting
them true opens only the exact implemented route inventory. The inbox has an
independent `ASSESSMENT_INBOX_ENABLED=false` switch. Provision this
deployment's own least-privilege SERVICE client and runtime secret. No Wave 3
business feature or Identity activation is enabled by this prerequisite.

Do not route activated protected traffic to bypass-capable older Gateway or
downstream instances. Drain/block old instances before activation or rollback.

## Default-disabled authoritative monitoring metadata

The dependent integration increment adds V6 capture/outbox and a confirmed RabbitMQ
publisher for [answer-persistence metadata](../../docs/specifications/w3m-authoritative-answer-integration.md).
All six prior Assessment migrations, answer repository/finalizer and public API remain
unchanged. `ASSESSMENT_MONITORING_CAPTURE_ENABLED=false` and
`ASSESSMENT_MONITORING_PUBLISHER_ENABLED=false` are independent defaults. A typed
capture opt-in fixes a dedicated connection GUC at pool startup; capture/answer
rollback is atomic and no broker I/O occurs in the answer transaction. Broker outage
leaves durable metadata pending; local DB capture failure rolls back coherently.

Provision the exact dedicated private-vhost exchange/ACL and trusted TLS separately;
no runtime topology declarations or production credentials are supplied. Production
capture/publication is unconditionally rejected pending privacy/security acceptance.
The fact contains bindings/question/revision/time only, no answer, answer digest,
Quiz key or JWT, and can reflect server normalization after deadline rather than a
Student action. No final result/gradebook event or retention policy is introduced.

## FB-015 PR B: quiz-ID revocation fence

ADR-018 Phase A steps 2-3. V9 adds `quiz_revocation_fences` (quiz_id primary
key; row presence means revoked; no cross-service FK) and the ADR-009
`deletion_command_inbox` (unique operation id for dedup/redelivery). Everything
is default OFF behind `ASSESSMENT_DELETION_ENABLED=false`
(`quizopia.deletion.enabled`): only the private command endpoint and the
acknowledgment worker follow the flag. Route enforcement is always compiled and
table-driven; with an empty fence table behavior is byte-identical, and a
`RECONCILE`-installed fence is enforced even while the flag is off.

The private `POST /internal/security/quiz-revocations/batch` mirrors the
Identity user-profiles hardening: exact Quiz deletion SERVICE client
(`ASSESSMENT_DELETION_QUIZ_CLIENT_ID`, default `quiz-service`), dedicated
`quiz.deletion.revoke` scope, RS256 + iat/exp + TOKEN_SERVICE checks, HTTPS
required outside explicit loopback, no query/Cookie/TE/CE, forged-identity
headers rejected, single application/json Content-Type, 16KB body cap, strict
duplicate/trailing JSON rejection, 1..50 unique canonical UUIDs, semaphore plus
rolling per-minute budget (429 `REVOCATION_RATE_LIMITED`), `no-store`
everywhere. Responses are sanitized: accepted/alreadyRevoked with quiz id,
operation id and revokedAt only. Installation first locks all affected
`publications` rows in id order, then samples `revoked_at` from the database
clock and inserts fence/inbox rows (`ON CONFLICT DO NOTHING`).

Serialization: every Assessment writer takes the owning publication row lock
first and rechecks the fence inside the same transaction. A START/SAVE/SUBMIT
that commits before the fence keeps its rows for the later purge; every
operation after the fence denies with the stable non-disclosing 404
`ASSESSMENT_REMOVED` - including exact-key idempotent replays, closure
commands/replays, teacher results, monitoring/proctoring/realtime authority
reads and new publication creation. No new grading or terminal finalization can
linearize after the fence. The owner directory, recipient notification inbox,
closure worker scans and both outbox claim queries silently exclude revoked
quizzes. Deadline/CLOSE behavior for unrelated quizzes is unchanged.

`GET /internal/security/quiz-revocations/{quizId}/status` (same hardening)
reports truthful aggregate state: revokedAt, acknowledgedAt and
publication/attempt counts, no PII. acknowledgedAt is set only by the bounded
background drainer after it holds the affected publication row locks and
verifies that no committed mutation timestamp (attempt answers, grades,
operation receipts, finalization audit, creation receipts) postdates
revoked_at. The exact wire contract is in `contracts/quiz-revocations.openapi.yaml`.
Production activation remains unauthorized (ADR-018 gates).

## FB-015 PR C corrections: bounded least-privilege purge (Leader review)

V10/V11 + `QuizPurgeService`/`QuizPurgeController` implement ADR-018 Phase B
steps 6-7 behind the same `ASSESSMENT_DELETION_ENABLED=false` flag, with the
private surface in `contracts/quiz-purgings.openapi.yaml`.

- **Least privilege (H1).** V11 separates the privileged table owner
  (`quizopia_assessment_owner`, NOLOGIN) from the application runtime role
  (`assessment`, the `DB_USERNAME` the service authenticates with). The runtime
  role receives USAGE + SELECT/INSERT/UPDATE only - **no DELETE or TRUNCATE on
  any protected history table** (the one legitimate draft-race DELETE now runs
  through the row- and state-bound `assessment_delete_draft` SECURITY DEFINER
  procedure) - plus EXECUTE on `assessment_purge_quiz` alone. Every DELETE
  inside the purge procedure is bound to the captured quiz-scoped publication/
  attempt inventory, never to the session marker; the GUC marker only informs
  the immutable-history guard triggers as a second layer. Retention holds,
  receipts and progress rows are read-only for the service: holds are inserted
  by approved principals granted `quizopia_assessment_custodian` only (NOLOGIN
  group role; never grant it to `assessment`). The owner is a trusted migration
  boundary, not an operational login. Provision that custodian role before a
  non-superuser Flyway run; V11 can create missing roles only in disposable
  superuser fixtures. All new SECURITY DEFINER functions are transferred to the
  owner after creation, have `pg_catalog,<service schema>,pg_temp` search paths,
  and restricted EXECUTE grants. Flyway history retains its migration owner and
  is excluded from both the ownership sweep and runtime grants. Runtime ownership
  of the database/schema, privileged role membership, or inherited destructive
  grants fail migration rather than weakening the boundary.
  **Provisioning:** the cluster must provide the owner role, the runtime role,
  and a migration principal that is a member of the owner role
  (  `quizopia_assessment_flyway`, used as `spring.flyway.user`; provisioned by
  `infrastructure/postgres/01-init-databases.sh` locally - a pre-FB-015 local
  volume must be recreated (`docker compose down -v && docker compose up -d
  postgres`) or the roles provisioned by hand before the service boots). V11 fails
  loudly
  when the roles are missing instead of shipping an unenforced boundary;
  future migrations creating tables must repeat the ownership transfer + grants.
- **No runtime DDL (H2).** Exactly the five audited cyclic FKs
  (`attempts<->submitted_answers`, `attempts<->grading_results`,
  `attempt_finalization_audit<->grading_results`,
  `attempt_finalization_audit<->closure_request_members`,
  `closure_request_members<->assessment_notifications`) were converted at
  migration time from ON DELETE/UPDATE RESTRICT to NO ACTION DEFERRABLE
  INITIALLY DEFERRED. Identical referential integrity is enforced at COMMIT
  (violating orphans abort the commit - proven by test); the purge procedure
  performs no `pg_constraint` scanning and never drops constraints.
- **Genuinely bounded rounds (H3).** Each invocation is one transaction with a
  hard work-unit budget across inventory and all deletion steps
  (`ASSESSMENT_DELETION_PURGE_BUDGET`, default 5000, bounded 100..50000). An
  exhausted budget durably persists `purge_progress` and answers `IN_PROGRESS`;
  `PURGED` (with the `verified_zero` receipt) is emitted only by a final round
  whose zero-remaining verification passed inside that same transaction. Each
  unit captures one durable publication/attempt ID or removes at most three
  content rows: the attempt/result/submission cycle is indivisible, as are the
  member/notification and publication/snapshot pairs. Arbitrarily many answers,
  per-question grades, operations and outbox entries drain separately first.
  Total writes are bounded by a linear function of the budget plus fixed
  bookkeeping: at most `budget` inventory inserts, `3*budget` content deletes,
  `3*budget+18` receipt upserts, one inbox delete, admission and progress writes.
  These maxima share the same budget, not separate per-table allowances.
  There are no full-inventory arrays or unbudgeted bulk marker inserts. Scope
  survives parent removal in indexed ID-only markers. Final checks explicitly
  cover snapshots and both realtime resource kinds through durable scope;
  validated forward FKs prove the remaining children absent when their roots
  are absent. Queries remain subject to configured lock/statement/transaction
  timeouts; this is a mutation bound, not a constant-cost query/latency promise.
  The coordinator retries rounds; restart-safety and
  exactly-once receipts are proven by adversarial tests, including as the real
  runtime DB role.
- **Enforced hold admission.** Purge and the hold table's SECURITY DEFINER
  trigger lock the same durable `purge_admission` row under READ COMMITTED.
  A custodian's uncommitted hold makes purge wait, then return
  `BLOCKED_BY_RETENTION` after the hold commits. A purge admission that wins
  first persists `begun=true`; subsequent new/updated holds fail explicitly
  because preservation can no longer be promised, including between rounds
  and after completion. An admission rollback permits a later hold. Runtime
  cannot insert/update/delete holds, alter admission, or SET ROLE to custodian.
  INSERT/UPDATE triggers take compatible shared locks for ordinary scope-bearing
  business writers; fence admission, holds and purge take exclusive locks on that row
  and reject fenced scopes, including stale realtime IDs after parent deletion;
  a forged purge GUC gives no UPDATE authority. Deadlocks/timeouts roll back and
  remain retryable; no operational DDL or disabled integrity triggers are used.
- **Verification.** PostgreSQL 17 tests execute real Flyway fresh installs and a
  non-superuser V10→V11 upgrade/validate/replay with lock and statement timeouts.
  Adversarial runtime sessions test TEMP shadowing, forged GUCs, role escalation,
  raw DML and read-only holds/receipts. Budget-1 rounds use fresh connections and
  explicit COMMIT with real submitted grades and confirmed closure notices;
  physical table counts assert the per-round bound and final zero without
  parent joins. Tests also cover rollback, service-instance restart, concurrent
  duplicates, unrelated FKs/quizzes, and both ordered hold-admission races.
- **Legacy dependents (H5).** `GET /internal/security/quiz-revocations/{quizId}/dependents`
  (exact Quiz deletion client + dedicated `quiz.deletion.purge` scope) returns
  the complete publication/attempt id inventory for the quiz so the coordinator
  can resolve pre-V4 Proctoring sessions without any cross-service database
  join. Ids only - never titles, content or PII.
- **Stale replay (H7).** Id-only `purged_publication_markers`/`purged_attempt_markers`
  (written in the purge transaction) keep post-purge START/SUBMIT replays on the
  stable non-disclosing 404 `ASSESSMENT_REMOVED` path - no distinguishable
  NOT_FOUND, no data resurrection.
