# FB-015 backend correction regression report

Date: 2026-10-29. Tracking: backend [#120](https://github.com/hhtuann/Quizopia_2.0/issues/120), frontend [#125](https://github.com/hhtuann/Quizopia_2.0/issues/125).

**Scoped backend corrections and disposable browser regression verified; overall implementation acceptance PENDING. BR-01 resolved 2026-10-29 as policy-only and no longer gates FB-015; production NO-GO.**

Authority: [ADR-018](../decisions/ADR-018-fb015-permanent-quiz-erasure.md), which supersedes the earlier retention-first proposal. This report records implementation evidence, not permission to enable deletion or erase non-disposable data. Deletion state remains separate from Publication `DRAFT → OPEN → CLOSED`.

## Correction summary and owners

| Finding | Correction and regression boundary |
| --- | --- |
| H1 — least privilege | Assessment V11 and Proctoring V5 separate NOLOGIN object owners, Flyway migration identities, restricted runtime roles and independent custodians. Runtime has no direct protected-history DELETE/TRUNCATE, hold override or provider-receipt write authority. Scoped SECURITY DEFINER procedures, restricted EXECUTE and hardened search paths replace marker-only privilege. Forged GUCs, TEMP shadowing and role escalation are tested. |
| H2 — runtime DDL | Assessment converts exactly five audited cyclic FKs at migration time to `NO ACTION DEFERRABLE INITIALLY DEFERRED`. Purge neither scans/drops constraints nor disables triggers; invalid orphans still abort COMMIT. |
| H3 — bounded purge | Both owners persist inventory/progress across budgeted, restart-safe transactions. `IN_PROGRESS` is retried; `PURGED` requires same-transaction zero verification. Quiz orders Proctoring completion before Assessment purge and requires acknowledged fences and explicit `retentionHoldActive: false` from both owners. Unknown/malformed/mismatched receipts fail closed. |
| H4 — ledger content | Quiz V6 atomically removes quiz/draft/version content and scrubs `confirmed_title` to NULL and version inventory to `{"versionCount":n}` when advancing to `PRIMARY_PURGED`. V7 preserves a SHA-256 canonical-request digest: original replay succeeds, changed-title replay conflicts. Already-scrubbed pre-V7 receipts cannot reconstruct the digest and reject replay; owner status/latest discovery remains available. |
| H5 — legacy dependents | Assessment supplies IDs only through its private dependents inventory. Quiz freezes/passes that mapping before Assessment deletion; Proctoring resolves pre-V4 `quiz_id NULL` sessions and their evidence/audits/media/inbox copies without cross-service SQL. Missing mapping with legacy rows yields `RECONCILIATION_BLOCKED` / `WAITING_DEPENDENCY`, never false completion. |

Additional corrections:

- **Stale snapshots/replays:** Proctoring enforces READ COMMITTED in privileged functions, admission and write triggers; REPEATABLE READ/SERIALIZABLE receive SQLSTATE `25001` and require a fresh transaction. Advisory locking alone cannot refresh MVCC snapshots. Completed receipt replay/status reverify absence against durable inventory; late/restored payload invalidates `verified_zero`. Assessment ID-only purged markers keep stale START/SUBMIT replays at non-disclosing `ASSESSMENT_REMOVED` without resurrection.
- **ACL/provisioning:** Proctoring revokes PUBLIC table/default grants and validates effective table/column and operator-function privileges through every reachable role, including SET ROLE-only membership; unsafe inherited ACLs fail migration. Flyway history retains migration ownership and is excluded from grant/ownership sweeps. Local provisioning supplies owner/Flyway/custodian separation for Assessment and Proctoring only; existing volumes need separately managed role provisioning or disposable recreation. Future migrations must repeat ownership/grant sanitation. No runtime owner/custodian membership is permitted.
- **Inventory route admission:** browser acceptance found a real 403 gap. Assessment now admits only canonical GET `/internal/security/quiz-revocations/{quizId}/dependents` in `SecurityPurgeRoutes`, retaining the exact Quiz SERVICE caller and `quiz.deletion.purge` scope. HTTP tests cover IDs-only/no-store success and wrong client, scope, principal, method, path and transport denial.
- **Gateway broker regression:** the test waits for the actual replacement consumer queue/bindings/consume readiness before publishing its fresh probe. Outbox drain alone was not consumer readiness. Product recovery behavior and REST authority are unchanged; see [broker verification](../../gateway/BROKER_RECOVERY_VERIFICATION.md).

### Service scope, holds and work bounds

- **Quiz** owns Teacher/LD-05/ownership checks, request identity, fencing, saga/receipts, status discovery and final quiz-owned purge. **Assessment** owns publication/snapshot/attempt/answer/grade/result/closure/notification/outbox cleanup. **Proctoring** owns monitoring/evidence/access audits/inbox/media bindings and provider-cleanup custody. Gateway owns edge routing/admission, not purge authority. Identity accounts/profiles, Classroom membership and unrelated quizzes remain intact. Private calls use direct authenticated service-to-service transport, never cross-service database access.
- **Defaults OFF:** `QUIZ_DELETION_ENABLED`, `ASSESSMENT_DELETION_ENABLED`, `PROCTORING_DELETION_ENABLED`; collection/media/event flags remain independently OFF. Installed revocation fences still enforce denial with deletion dispatch disabled; disabling flags does not undo fences or restore erased content.
- **Hold scope is conservative:** ADR-018 calls for minimum necessary record-scoped preservation; this implementation blocks the whole quiz deletion operation for an active scoped hold rather than claiming selective partial erasure. Assessment serializes hold/purge admission on a durable quiz row. Proctoring uses a **service-wide** advisory transaction lock (`18015`) across ordinary writers, inbox, holds and purge, deliberately serializing even unrelated quizzes. This is a conservative service-wide protocol, not a cross-service atomic transaction or qualified throughput guarantee. Custodian holds require independent authority; once irreversible admission wins, a new hold is rejected rather than promising restoration. Unknown hold classification blocks progression.
- **Bounded work, not fixed latency:** both budgets default to 5000, configurable 100..50000. Assessment shares one work-unit budget across inventory capture and deletion; an indivisible unit may remove up to three content rows. Bounds include at most `budget` inventory inserts, `3*budget` content deletes, `3*budget+18` receipt upserts, one inbox delete plus admission/progress bookkeeping (shared budget, not additive per-table allowances). Proctoring capture/deletion share its budget, with one command-inbox row and fixed progress/receipt bookkeeping outside it. No full-inventory arrays in Assessment rounds; query/lock cost still depends on data and configured timeouts. Contention/capacity qualification remains open.

## Verification evidence

Recorded local runs, not a new suite execution by this documentation task. Full-verification totals below **precede the new opt-in FB-015 browser test**; its separate result must not be folded into the historical Gateway total.

| Verification | Tests | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| Assessment `mvnw.cmd verify` | 489 | 0 | 0 | 0 | PASS |
| Proctoring `mvnw.cmd verify` | 259 | 0 | 0 | 0 | PASS |
| Quiz `mvnw.cmd verify` | 216 | 0 | 0 | 0 | PASS |
| Gateway `mvnw.cmd verify` | 181 | 0 | 0 | 1 | PASS; existing opt-in W4 Chromium skip |
| FB-015 browser `QuizDeletionTopologyTest`, `-Dfb015.browser=true` | 1 | 0 | 0 | 0 | PASS; all eight checkpoints, 4:33 |

Full verifies include service quality gates such as Spotless, ArchUnit and packaging. Focused evidence includes Assessment HTTP **8 passed** and Proctoring purge/HTTP **30 passed**, plus real PostgreSQL fresh/upgrade migrations, restricted runtime credentials, hold races, stale snapshots, budget/restart/duplicate handling, raw absence counts and unrelated-data preservation. **Migration rollback means failed transaction rollback and integrity preservation, not reverse Flyway migration, schema downgrade or restoration after purge.**

Browser evidence: `gateway/target/fb015-acceptance/f3ef97cd-a7a7-478a-b6ff-ee698902e4f6/` (`checks.json`, `result.json` PASS); Maven assertions: `gateway/target/surefire-reports/`. See [runbook and exact limitations](../../scripts/local/fb015-acceptance.md). The disposable topology uses Chromium same-origin browser fetch through real Gateway, real Identity login/client credentials, separate service JVMs, five PostgreSQL 17 databases and Redis; no mocked public HTTP routes or fabricated browser JWTs.

The journey verifies multiple versions, PUBLIC graded and CLASS active attempts, legacy monitoring/media dependents, outage → `WAITING_DEPENDENCY`, wall-clock hold expiry, denied stale links, restart recovery, primary-store zero counts, ledger scrub/replay/conflict, and survival of unrelated data/accounts/membership. `PENDING_PROVIDER` preserves local media bindings and Assessment inventory. A **synthetic independent-custodian provider receipt** then permits local primary purge; it is not a real LiveKit erasure receipt.

## Acceptance register and erasure limits

| Gate | Status / required evidence |
| --- | --- |
| Independent scoped correction review | Reported no remaining HIGH findings in the reviewed scope; does not approve the entire FB-015 acceptance surface. |
| Backend exact-artifact CI | **PENDING** — commit SHA: **PENDING**; run URL/result: **PENDING**. Local verifies are not CI receipts. |
| Frontend #125 | **PENDING** — commit SHA: **PENDING**; CI/UI evidence: **PENDING**. Dialog/navigation, vi/en, keyboard/accessibility and actual deletion UI are not exercised by the fetch driver. |
| BR-01 | **RESOLVED 2026-10-29 as policy-only** (no owner-exclusion or Teacher-Preview implementation; see the BR-01 decision memo and #126). It no longer gates FB-015; broader ADR-018 production acceptance remains separate. |
| Production activation | **NO-GO** — separate Leader authorization, legal/privacy and operations signoff, actual retention/backup/provider policy, recovery/replay, observability/emergency-stop and deployment evidence required. |

`PRIMARY_PURGED` with `COPY_EXPIRY_PENDING` is the honest implemented outcome. `ALL_COPIES_ERASED` remains unreachable. Backup/replica/CDN/export/object-storage/log/telemetry expiry and actual provider erasure are unverified; no invented retention duration or all-copy guarantee applies. The remaining ledger includes IDs, owner authorization reference, digest, statuses/timestamps/reasons and minimal receipts: it is not anonymous or proof of universal erasure, and its bounded TTL still needs privacy/operations approval. Exhaustive lifecycle/closure/outbox permutations, ownership/role denials, race saturation and production TLS/capacity remain separate acceptance work.

Sources: [Assessment README](../../services/assessment-service/README.md), [Proctoring README](../../services/proctoring-service/README.md), [Quiz README](../../services/quiz-service/README.md), [Gateway README](../../gateway/README.md), [open policy/release questions](../open-questions.md). No merge, deployment or production approval is inferred from this report.

## Overnight verification addendum (2026-10-30)

Stage-0 re-review plus release-readiness hardening on top of the 2026-10-29 report above:

- **Stage-0 independent re-review of `ac4a792`:** all five Leader findings verified with code + adversarial-test evidence; no new HIGH defect found. Follow-ups applied: Assessment stale-snapshot adversarial test added (`d42f280`: purge/fence/hold admission each reject a REPEATABLE READ snapshot taken before hold commit); stale PR description rewritten to match the corrected design.
- **Contracts (Priority A):** every internal deletion endpoint now documents its real 400/401/403/429 error codes (assessment purge batch+status, revocation status+dependents; proctoring purge batch+status with `DELETION_RATE_LIMITED`), and the missing proctoring `quiz-revocations` OpenAPI contract was added with the actual Status DTO shape (`09e49ad`). Frontend contract-parity test covers the public surface.
- **Saga observability (Priority B):** `quizopia.quiz.deletion.transition{to,reason}` counter and `quizopia.quiz.deletion.nonterminal.operations{status}` gauge with a closed reason vocabulary (unknown → `other`), refreshed once per reconcile round; bounded WARN on swallowed reconcile failures carrying operation id, durable stage and failure class only; inert no-op observer while deletion flags are OFF (`7d23baf`, unit-tested).
- **Operations runbook (Priority C):** [fb015-deletion-operations-runbook.md](fb015-deletion-operations-runbook.md) covers enablement prerequisites, emergency stop, partial-purge recovery, tombstone-first backup restoration (drill NOT TESTED), provider-erasure verification status, staging rehearsal, incident investigation and the human-approval checklist (`d703d23`).
- **Gateway topology stability (Stage 4):** three historically observed failures all have proven resolutions — ProctoringTopologyTest 10-vs-11 was real contract drift (fixed with an exact 11-field allowlist assertion), W4 broker recovery was a test synchronization race (fixed against actual consumer readiness; product unchanged), and a PublicationTopologyTest HTTP timeout on one run was Testcontainers/Redis environmental noise (passed on every other head; transient Maven Central 429/502 artifacts also observed and re-run green). Evidence: [BROKER_RECOVERY_VERIFICATION.md](../../gateway/BROKER_RECOVERY_VERIFICATION.md).
- **Exact-artifact CI:** Backend CI on #124 `d703d23` — all 8 jobs SUCCESS ([run 40979719577](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40979719577)); Repository validation SUCCESS ([run 40979719500](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40979719500)). #125 `916713d` Frontend CI SUCCESS ([run 40979741007](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40979741007)), Backend CI all-green ([run 40979740912](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40979740912)), Repository validation SUCCESS ([run 40979740917](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40979740917)).
- **Updated local totals:** Assessment **490/490**, Quiz **224/224** (adds `MicrometerDeletionSagaObserverTest` and the stale-snapshot test); Proctoring 259/259 and Gateway 181/181 (+1 opt-in skip) unchanged. Real-browser/real-UI disposable acceptance PASS evidence: backend-fetch `f3ef97cd…`, real Next.js UI `4abc7e06-f69e-4584-ba08-7e7298b95656` (named + untitled deletion, reload recovery without session storage, `PRIMARY_PURGED` copy-pending wording, survivor preservation).
- **BR-01:** resolved 2026-10-29 as policy-only (#126, PR #127; status sync in PR #128). No owner-exclusion or Teacher-Preview code exists or is planned. Production NO-GO unchanged.
