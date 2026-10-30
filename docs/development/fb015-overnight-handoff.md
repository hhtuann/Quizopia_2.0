# FB-015 Morning Handoff — Correction Completion & Review Package

Date: 2026-10-30 (overnight correction + review session; supersedes the 2026-10-29 handoff below in this file's history)
Author: primary correction/engineering agent (session-continuous)
Baseline verified `origin/develop`: `276a4a8214d71e06925308658c1c8b0e8e9b4d10` (PR #121 ADR-018, #122 PR A, #123 PR B merged)
ADR: `docs/decisions/ADR-018-fb015-permanent-quiz-erasure.md` (accepted for guarded implementation)
Tracking: Issue #120. Production **NO-GO**. Deletion flags **default OFF**. BR-01 **RESOLVED 2026-10-29 as policy-only** (#126, memo PR #127, status-sync PR #128).

## 1. Open PRs and exact final HEADs

| PR | Title | Branch (base) | Final HEAD | CI on HEAD |
|---|---|---|---|---|
| [#124](https://github.com/hhtuann/Quizopia_2.0/pull/124) | PR C — dependent purge, least privilege, saga, contracts, observability, runbook | `feature/fb015c-dependent-purge` (develop) | `669588a` | Backend CI all 8 jobs + Repository validation on `d703d23` (code HEAD before docs-only `669588a`); see §7 |
| [#125](https://github.com/hhtuann/Quizopia_2.0/pull/125) | PR D — frontend deletion UX + real-UI acceptance harness | `feature/fb015d-frontend-delete-ux` (#124) | `53bd635` | Frontend/Backend/Repository all green on `916713d` (code HEAD before docs-only merges); see §7 |
| [#127](https://github.com/hhtuann/Quizopia_2.0/pull/127) | BR-01 policy memo (docs-only) | `docs/br01-student-access-policy-resolution` (develop) | `0875556` | docs-only |
| [#128](https://github.com/hhtuann/Quizopia_2.0/pull/128) (new) | BR-01 resolved-status sync across checkpoint records (docs-only) | `docs/br01-status-sync` (develop) | `e8848bb` | docs-only |

No PR merged, no force push, no deploy, no flag activation, no non-disposable data touched.

## 2. Correction history on #124 (this session)

1. `d24fdea` original PR C (Leader review: CHANGES REQUIRED).
2. `5c92a35` develop reconciliation (ADR #121 + #122/#123).
3. `ac4a792` correction of Leader findings **H1–H5** plus independently reproduced gaps: Flyway history-table ownership deadlock; SECURITY DEFINER ownership + `pg_temp`-last search paths (temp-table shadowing); cyclic-FK partial-batch commit failures; publication/snapshot attribution loss across rounds (false PURGED); saga destroying the legacy mapping before Proctoring finished; missing `assessment_monitoring_inbox` purge scope + stale-delivery resurrection; media-locator destruction before provider cleanup; runtime-forgeable custodian surfaces (inherited/default ACLs); stale REPEATABLE READ snapshot bypasses of holds/admission; custodian/owner/flyway role provisioning.
4. `d42f280` Assessment stale-snapshot adversarial test (purge/fence/hold admission reject non-READ-COMMITTED; hold committed after the stale snapshot still blocks a fresh caller).
5. `09e49ad` internal-contract accuracy (429/400/401/403 everywhere actually emitted; new proctoring `quiz-revocations` OpenAPI).
6. `7d23baf` saga observability (`quizopia.quiz.deletion.transition{to,reason}` counter, `…nonterminal.operations{status}` gauge, closed reason vocabulary, bounded failure WARNs; inert while flags OFF; unit-tested).
7. `d703d23` operations runbook (`docs/development/fb015-deletion-operations-runbook.md`).
8. `669588a` docs: BR-01 status sync + overnight verification addendum in `fb015-correction-regression.md`.

## 3. Leader findings H1–H5 — evidence map

Full table with file:line and test names: `docs/development/fb015-correction-regression.md` (+ addendum). Summary:

- **H1 purge authorization — VERIFIED.** Assessment V11 + Proctoring V5 least privilege: NOLOGIN owners, restricted runtime roles (no DELETE/TRUNCATE anywhere protected), independent custodians, effective-ACL/membership validation that fails migration closed, EXECUTE-only SECURITY DEFINER purge, purge/fence/hold admission require READ COMMITTED, row binding through durable quiz-scoped inventory. Adversarial tests run **as the real runtime DB role**: forged GUC cross-quiz, TRUNCATE, SET ROLE escalation, temp-table shadowing, inherited/public-ACL forging, stale snapshots — all denied (QuizPurgeLeastPrivilegeIntegrationTest, QuizDeletionFencePurgeIntegrationTest, QuizPurgeLegacyDependentsIntegrationTest).
- **H2 FK integrity — VERIFIED.** Exactly the five audited cyclic FKs converted at migration time (drift-checked); zero runtime DDL in the purge; unrelated deferrable FK proven to survive; orphan still rejected at COMMIT; concurrent-writer test.
- **H3 bounded purge — VERIFIED.** Hard per-transaction budget (units ≤3 content deletes), durable `purge_progress` restart resume, `IN_PROGRESS` until drained, same-transaction `verified_zero`, cumulative receipts, duplicate-call exactly-once, receipt replay re-verifies absence.
- **H4 erasure — VERIFIED.** V6 ledger scrub (`confirmed_title` NULL, inventory → count) inside the same transaction as quiz-row deletion and `PRIMARY_PURGED` advance; V7 request digest keeps replay safe without title comparison; minimal ledger retains ids/status/digest only; post-purge title recovery proven absent.
- **H5 legacy dependents — VERIFIED.** Assessment private dependents inventory (ids only, exact purge-scope caller); Proctoring persists canonical inventory; `RECONCILIATION_BLOCKED` when unmapped legacy rows exist, `INVENTORY_MISMATCH` on shrinking replays; inbox included in purge + zero verification with stale-delivery fencing; provider cleanup must be acknowledged before SQL deletion (`PENDING_PROVIDER` preserves locators).
- **Stage-0 independent re-review (this session): PASS at `ac4a792`, follow-ups `d42f280`; no remaining HIGH finding in reviewed scope.** Not human merge approval.

## 4. Test evidence (local, exact artifacts)

| Suite | Tests | Result |
|---|---:|---|
| Assessment `mvnw verify` | **490** | 0 failures/errors/skips |
| Proctoring `mvnw verify` | **259** | 0/0/0 |
| Quiz `mvnw verify` | **224** | 0/0/0 (incl. observability + saga-order tests) |
| Gateway `mvnw verify` | **181** | 0/0/1 (existing opt-in W4 Chromium skip) |
| FB-015 real-browser topology (`-Dfb015.browser=true`) | 1 journey, 8 checkpoints | PASS (evidence `gateway/target/fb015-acceptance/f3ef97cd…`) |
| FB-015 real-**UI** topology (`-Dfb015.ui=true`) | 1 journey, 16 checkpoints (8 backend + 8 UI) | PASS (evidence `4abc7e06-f69e-4584-ba08-7e7298b95656`) |
| Frontend vitest | **969** | all green |
| Frontend Playwright (intercepted) | **160** | all green |
| Frontend typecheck/ESLint/Prettier/build | — | green |

Real vs mocked separation: the only mocked surface is the frontend Playwright suite (documented in spec headers); the two topology journeys use real Chromium → real Gateway → real service JVMs → five disposable PostgreSQL 17 DBs + Redis, real Identity login/client-credentials, no route interception, no fabricated JWTs.

## 5. Real-UI acceptance coverage (new this session)

Named-quiz deletion (wrong-case title leaves confirm disabled; 202 + Idempotency-Key format), historical untitled quiz (`confirmTitle:""` + explicit checkbox), reload recovery with `sessionStorage` cleared (latest-status rediscovered via UI, exactly one POST per quiz), `PRIMARY_PURGED` wording shows copy-pending (never "fully deleted"), survivor quiz preserved, victim invisible after purge. Two topology gaps found and fixed inside the test only (UI-origin allow-lists for Gateway/Identity cookie-refresh + real JWKS issuer). Frontend production code needed no change.

## 6. Production readiness audit (Stage 6 — audit only)

| Item | Class | Note |
|---|---|---|
| Feature flags default OFF | **VERIFIED** | all three `quizopia.deletion.enabled` etc.; flag-off = indistinguishable 404 / no scheduler / inert observer |
| DB migration safety | VERIFIED (code) | fresh + non-superuser upgrade migration tests incl. provisioning validation; rollback = transaction rollback only (no reverse migration) |
| Actual backup expiry configuration | **BLOCKED** | no approved backup policy evidence; `COPY_EXPIRY_PENDING` stays |
| Media provider (LiveKit) deletion capability | **BLOCKED** | real erasure API + receipts not implemented; synthetic custodian fixtures only |
| Legal/privacy retention classification | **BLOCKED** | Vietnam Law 91/2025 QH15 Art.14/19 + Decree 356/2025 review required before any activation |
| Deletion-receipt/ledger TTL | **BLOCKED** | awaits privacy/ops approval (ADR-018 §2) |
| Disaster recovery / backup restore with tombstone-first | **NOT TESTED** | runbook procedure written; restore drill not executed |
| Cross-service authorization | VERIFIED | exact SERVICE caller + scopes both services; adversarial denial tests |
| Monitoring/alerting | VERIFIED (base) | new saga metrics + existing ack counters; dashboards/alert thresholds NOT TESTED |
| Staging rehearsal (non-disposable) | **BLOCKED** | requires owner/ops approval + schema backups (ADR-018 gate 2) |
| Rollback/emergency stop | VERIFIED (procedure) | runbook §2; emergency-stop drill NOT TESTED |
| Production operational gates | **NO-GO** | unchanged |

## 7. Exact CI evidence

- #124 `d703d23`: Backend CI all 8 jobs SUCCESS — <https://github.com/hhtuann/Quizopia_2.0/actions/runs/40979719577>; Repository validation SUCCESS — <https://github.com/hhtuann/Quizopia_2.0/actions/runs/40979719500>. (`669588a` is docs-only on top; CI re-triggered.)
- #125 `916713d`: Frontend CI SUCCESS — <https://github.com/hhtuann/Quizopia_2.0/actions/runs/40979741007>; Backend CI SUCCESS — <https://github.com/hhtuann/Quizopia_2.0/actions/runs/40979740912>; Repository validation SUCCESS — <https://github.com/hhtuann/Quizopia_2.0/actions/runs/40979740917>. (`53bd635` docs-only merge on top.)
- Transient infra artifacts this session (all re-run green, no code cause): Maven Central 429/502 on two jobs; one Testcontainers/Redis PublicationTopologyTest HTTP timeout — analysis in `fb015-correction-regression.md` addendum.
- Gateway flake investigation (Stage 4) closed: `gateway/BROKER_RECOVERY_VERIFICATION.md` + addendum; three distinct proven causes (contract drift — fixed; test sync race — fixed; environmental timeout — no code defect).

## 8. Remaining blockers before any production deletion

1. Legal/privacy retention classification signoff (VN Law 91/2025, Decree 356/2025).
2. Actual backup expiry/erasure evidence (no invented durations).
3. Real LiveKit provider erasure capability + receipts.
4. Receipt/ledger TTL approval.
5. Staging rehearsal on classified non-disposable data + restore drill (tombstone-first).
6. Explicit Leader production authorization; observability dashboards/alert review.

`ALL_COPIES_ERASED` stays unreachable; `PRIMARY_PURGED + COPY_EXPIRY_PENDING` is the honest terminal for now.

## 9. Leader decisions requested

1. Merge review for #124 (code HEAD `d703d23`, docs `669588a`) and then #125 (`53bd635`); both stacked, all green.
2. Docs-only #127 and #128 (BR-01 record + status sync) — merge in either order.
3. The six production blockers above (approval/evidence owners).
4. Hold custodianship operational model (who holds `quizopia_*_custodian` membership in each environment).

## 10. Safe recommended merge order

`#127` → `#128` (docs-only, independent) → `#124` → `#125` (stacked; #125 already contains #124 content). After #124 merges, retarget/verify #125 against develop if GitHub does not auto-update. No auto-merge performed.

## 11. BR-01 documentation status

Resolved 2026-10-29 as policy-only (any authorized STUDENT may take an eligible eligible PUBLIC Assessment including their own; CLASS requires actual membership; **no owner-exclusion code, no Teacher Preview**). #127 carries the authoritative memo; #128 syncs all stale "PENDING" checkpoints; ADR-018/ux11/this branch updated. Nothing in the codebase implements owner denial or Preview; issue #126 closed as corrected.

## 12. Agent hygiene

User's untracked local-runtime files (`scripts/local/windows_*`, `docs/development/windows-local-runtime.md`, `scripts/local/tests/test_windows_runtime.py`) and `.local-secrets/` were preserved untouched and excluded from every commit. No WSL/persistent containers/volumes modified; all acceptance runs used disposable Testcontainers cleaned up on exit.
