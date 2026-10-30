# FB-015 deletion operations runbook

Date: 2026-10-30. Authority: [ADR-018](../decisions/ADR-018-fb015-permanent-quiz-erasure.md), [correction regression](fb015-correction-regression.md). Scope: operator guidance for the guarded FB-015 deletion implementation.

**Production activation is NO-GO; BR-01 PENDING.** This runbook describes guarded behavior, not permission to enable deletion or erase non-disposable data. Unknown retention periods are marked explicitly; none are invented.

## 1. Safe enablement prerequisites

1. **Role provisioning (Assessment and Proctoring only).** `infrastructure/postgres/01-init-databases.sh` provisions, per purge service: `quizopia_<service>_owner` (NOLOGIN table/procedure owner), `quizopia_<service>_flyway` (LOGIN migration principal, member of the owner, used as `spring.flyway.user`), the runtime role `<service>` (`DB_USERNAME`), and `quizopia_<service>_custodian` (NOLOGIN; grant only to the independent custodian identity). Pre-FB-015 local volumes must be recreated (`docker compose down -v && docker compose up -d postgres`) or the roles provisioned by hand; V11/V5 fail loudly when they are missing. Quiz/Identity/Classroom keep legacy single-role provisioning.
2. **Non-superuser Flyway.** Run Flyway as the flyway role, not as superuser; the non-superuser upgrade path (including custodian roles provisioned first, Flyway history ownership and checksum validation) is the tested migration mode. The owner role is a trusted migration boundary, never an operational login; no runtime owner/custodian membership is permitted.
3. **Flags default OFF.** `quizopia.deletion.enabled` via `QUIZ_DELETION_ENABLED` (Quiz), `ASSESSMENT_DELETION_ENABLED` (Assessment), `PROCTORING_DELETION_ENABLED` (Proctoring). Collection/media/event flags stay independently OFF. Purge work-unit budgets: `ASSESSMENT_DELETION_PURGE_BUDGET` / `PROCTORING_DELETION_PURGE_BUDGET` (default 5000, bounded 100..50000).
4. **Identity service client and scopes.** Register the Quiz deletion SERVICE client (client credentials; default id `quiz-service`, pinned via `ASSESSMENT_DELETION_QUIZ_CLIENT_ID` and the Proctoring equivalent) with the dedicated `quiz.deletion.revoke` and `quiz.deletion.purge` scopes. Private endpoints verify the exact client, RS256 + iat/exp + TOKEN_SERVICE; wrong client/scope/principal is denied.
5. **TLS.** Private deletion endpoints require HTTPS outside explicit loopback. `quizopia.deletion.allow-insecure-loopback` (default false) is refused under the `prod` profile — it must never be set in production.
6. **Retention classification and backup policy.** Approved retention/legal classification and documented backup expiry evidence (exact period taken from actual deployment configuration — currently **unknown**, no evidence exists yet) are prerequisites; unknown classification or missing backup policy blocks irreversible purge by design.

## 2. Emergency stop

Turn the deletion flags off (per service, normal config change/rollout).

What keeps happening:

- Quiz public deletion routes disappear (standard 404 envelope, route not disclosed); the scheduled outbox dispatcher and saga reconciliation stop with the flag, so **purge halts**.
- Assessment and Proctoring private revoke/purge endpoints answer `503 AUTHORITY_UNAVAILABLE`.
- **Fences and tombstones persist and keep denying access**: installed revocation fences are enforced even while the flag is off; post-fence routes keep returning the non-disclosing `ASSESSMENT_REMOVED`; purged markers keep rejecting stale replays.
- `PURGING` operations stay durable: operation rows, `purge_progress`, receipts and inventories survive; work resumes by bounded idempotent rounds once flags return.
- Disabling flags does not undo fences or restore already-erased content. There is no restore path.

What operators must NOT do:

- Do not delete or "clean" ledger rows (`quiz_deletion_operations`), fence rows (`quiz_revocation_fences`), purged markers or tombstones — they are the anti-resurrection barrier.
- Do not bypass or "recreate" holds, grant custodian roles to runtime identities, or run ad-hoc DELETE/TRUNCATE against protected tables (the runtime role has neither; keep it that way).
- Do not disable triggers, drop constraints, or attempt cross-database CASCADE.

## 3. Recovery from partial purge

All nonterminal states are durable and owner-queryable; no false terminal success. The coordinator (Quiz `DeletionSagaService`) retries bounded rounds.

- `IN_PROGRESS` — a round exhausted its work budget and durably persisted `purge_progress`; the operation parks in `PURGING` (reason `purge-in-progress`) and later rounds resume from the persisted inventory/progress. Rounds are idempotent, restart-safe and bounded; service restart mid-purge recovers the durable operation and retries (verified by restart checkpoints in the disposable acceptance).
- `BLOCKED_BY_RETENTION` — an approved custodian hold is active, or classification is unknown (fails closed). Holds are admitted only by the independent custodian role; there is no runtime hold override.
- `WAITING_DEPENDENCY` — a dependency acknowledgment is missing (e.g. real service outage), or reason `legacy_dependents_unresolved`: Proctoring answered `RECONCILIATION_BLOCKED` because pre-V4 sessions (`quiz_id NULL`) exist and the Assessment dependents-id mapping is unavailable. Never a false `PURGED`.
- `PENDING_PROVIDER` — media bindings exist; local media tables are preserved for retry until provider erasure is acknowledged (see section 5).
- `INVENTORY_MISMATCH` — a retry changed the input id arrays; retry must reuse the coordinator's frozen original inventory.

Inspection (counts only, no content export): owner GET endpoints `GET /api/quizzes/{quizId}/deletion-requests/{operationId}` and `/latest` (Quiz, owner-only, no PII, `no-store`); private `GET /internal/security/quiz-revocations/{quizId}/status` and `GET /internal/security/quiz-purgings/{quizId}/status` on Assessment and Proctoring (aggregate counts, `retentionHoldActive`). In databases: `deletion_purge_receipts`, `purge_progress`, `deletion_command_inbox`, `quiz_revocation_fences` (both owners), Proctoring `purge_inventory`/`proctoring_retention_holds`/`proctoring_provider_cleanup`, Quiz `quiz_deletion_operations` ledger. `PURGED` receipts carry `verified_zero` only from a final round whose zero-verification passed in that same transaction; completed-receipt replays re-verify absence against the durable inventory.

## 4. Backup restoration (tombstone-first)

ADR-018 step 9: a restored old backup must restore revocation/tombstone enforcement **before** exposing any service. The risk being prevented: a backup taken before deletion restores publications, attempts, answers and grades that would become accessible again to students/teachers.

Concrete operator order (restore path only; purge itself is irreversible):

1. Stop/keep stopped the public edge in front of the restored stack.
2. Restore service databases from the chosen backup.
3. Re-apply the deletion barriers to the restored state: for every ledgered operation (`quiz_deletion_operations`), restore the Quiz fence/tombstone/ledger rows and the Assessment/Proctoring `quiz_revocation_fences` plus purged markers so fenced scopes are denied before traffic returns.
4. Verify denial on affected routes (post-fence reads must answer `ASSESSMENT_REMOVED`/denial, not pre-deletion content).
5. Only then expose services through the Gateway.

**Verification drill for this order: NOT TESTED — pending.** Also note restored backups re-introduce deleted content by definition; until backup expiry is evidenced, status remains `PRIMARY_PURGED` + `COPY_EXPIRY_PENDING` and `ALL_COPIES_ERASED` is unreachable. Backup expiry period: **unknown** until read from actual deployment configuration.

## 5. Provider erasure verification

Current implementation: when media bindings exist, Proctoring records an erasure request (`media_erasure_requests`) and answers `PENDING_PROVIDER` **before** deleting content. Local SQL purge completes only after an independent `quizopia_proctoring_custodian` appends an operation-bound receipt to `proctoring_provider_cleanup`, after verifying actual provider cleanup against the complete frozen inventory; runtime cannot write that table. Status then reports `ACKNOWLEDGED`.

**The real LiveKit erasure procedure and its receipts are NOT implemented.** The receipts used in tests and the disposable acceptance are synthetic independent-custody records — they do not perform or certify real LiveKit cleanup. Removing a participant, changing grant state, or marking a room deleted is not a provider-erasure receipt. Do not claim provider erasure until a real procedure and receipts exist.

## 6. Staging rehearsal

The rehearsal template is the disposable real-stack acceptance ([scripts/local/fb015-acceptance.md](../../scripts/local/fb015-acceptance.md)):

```powershell
# from gateway/
.\mvnw.cmd -B "-Dtest=QuizDeletionTopologyTest" "-Dfb015.browser=true" test
```

Opt-in (`-Dfb015.browser=true`; standard CI skips it). Requires Java 21, Docker/Testcontainers, Node and the Playwright Chromium binary; allow 6–8 minutes (external runners at least 10); run alone, never alongside `PublicationTopologyTest`. Topology: five disposable PostgreSQL 17 databases, disposable Redis, real separate service JVMs with restricted runtime DB roles, real Gateway/Identity, real headless Chromium through Gateway. Asserts outage → `WAITING_DEPENDENCY`, hold → `BLOCKED_BY_RETENTION`, restart recovery, zero victim rows, ledger scrub/replay, and survival of unrelated data/accounts/membership.

Any rehearsal on **non-disposable staging data** requires explicit owner/operations approval per the ADR-018 gate 2 (after retention classification, schema backups, reversible rehearsal) — never real Student records.

## 7. Incident investigation

Evidence locations (counts/aggregates; responses are sanitized and `no-store`):

- Quiz: `quiz_deletion_operations` ledger (states, reason codes, receipts, scrubbed title NULL, `{"versionCount":n}`), outbox rows.
- Assessment/Proctoring: `deletion_purge_receipts`, `purge_progress`, `deletion_command_inbox`, `quiz_revocation_fences`, Proctoring `purge_inventory`, `proctoring_retention_holds`, `proctoring_provider_cleanup`.
- Sanitized access logs (method/path/status only — never headers or bodies) and per-run evidence dirs such as `gateway/target/fb015-acceptance/<run-id>/` (`checks.json`, `result.json`), `gateway/target/publication-topology/` logs, `gateway/target/surefire-reports/`.

Never log or paste into tickets: quiz titles, questions/answers, attempt payloads, student names/PII, tokens. Metrics (being added in this PR; not observable on deployments built from earlier SHAs): saga transition counters per state plus parked-reason class, a nonterminal-operations gauge, and revocation acknowledgment counters. Contention/capacity qualification remains open. Current state: **production NO-GO; BR-01 PENDING** — any incident response must not be read as activation authority.

## 8. Required human approvals checklist

| Item | Status |
| --- | --- |
| Legal/privacy retention classification (Vietnam Law 91/2025/QH15 etc., qualified review of this deployment) | Required before activation; **PENDING** |
| Backup expiry evidence (exact period from actual deployment configuration) | Required; **unknown — no evidence yet** |
| Provider (LiveKit) erasure procedure + real receipts | Required for `ALL_COPIES_ERASED`; **not implemented** |
| Deletion receipt/ledger TTL approval (privacy + operations) | Required; **unknown — not approved** |
| Leader production authorization (ADR-018 gate 3) plus legal/privacy + ops signoff, recovery/replay, observability, emergency stop evidence | **NO-GO** |
| BR-01 requested implementation acceptance | **PENDING** |

## 9. Appendix: gateway topology test stability

Three historically observed CI failures and their proven resolutions (do not re-litigate them when they recur in old runs):

1. **`ProctoringTopologyTest` 10-vs-11 field failure — contract drift.** The private authority contract `proctoring-authority.openapi.yaml` documented 10 fields while the actual response (and the strict exact-allowlist topology assertion, `assertAuthorityShape` — exactly 11 fields so no unrelated authority data can leak) carried 11 after FB-015 added `quizId`. Resolution: the specifically authorized Assessment contract correction added the required UUID `quizId` to the contract (matching the existing 11-field response), `swagger-cli validate` passed. Evidence: [BROKER_RECOVERY_VERIFICATION.md](../../gateway/BROKER_RECOVERY_VERIFICATION.md).
2. **W4 broker-recovery journey — test synchronization race, product unchanged.** The test published its probe after the Assessment outbox drained, but outbox drain proves publisher recovery, not Gateway consumer readiness (fresh exclusive auto-delete queue + bindings + `basicConsume`). Resolution: the test now waits for the real consumer to reconnect before publishing; `RealtimeInvalidationConsumer` exposes its connection only after declaration/bindings/consume. Product recovery code unchanged; ADR-016 permits lost hints, REST remains authoritative. Evidence: [BROKER_RECOVERY_VERIFICATION.md](../../gateway/BROKER_RECOVERY_VERIFICATION.md).
3. **`PublicationTopologyTest` HTTP timeout — environmental, not a regression.** CI runs intermittently timed out/errored in the unchanged suite (e.g. CI #89 Gateway job: 106 tests, zero failures, one error; a retry passed all eight backend jobs) under Testcontainers/Redis startup/readiness variance. Resolution: clean retry with no assertion, fixture or timeout relaxation; failures were recorded, not relabeled. Evidence and the run-URL recording pattern (link every GitHub Actions run id, e.g. `.../actions/runs/38447157054`): [w3m-correction-evidence.md](../architecture/w3m-correction-evidence.md), [w3b3-closure-notification-implementation.md](../architecture/w3b3-closure-notification-implementation.md).
