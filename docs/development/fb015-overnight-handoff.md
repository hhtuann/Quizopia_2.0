# FB-015 Overnight Handoff — Leader Review Package

Date: 2026-10-29 (overnight autonomous session)
Author: primary implementation agent (session-continuous)
Baseline `origin/develop`: `607dc5f` (PR #114 + PR #119 merged)
ADR: `docs/decisions/ADR-018-fb015-permanent-quiz-erasure.md` (accepted for guarded
implementation; architecture PR #121 still OPEN — merge #121 first or with #122).
Tracking: Issue #120. Production NO-GO. BR-01 PENDING.

## 1. FB-015 implementation PRs (sequential chain, do not merge out of order)

| PR | Title | Branch (base) | Final SHA | State |
|---|---|---|---|---|
| [#122](https://github.com/hhtuann/Quizopia_2.0/pull/122) | PR A — Quiz deletion operation foundation | `feature/fb015a-quiz-deletion-foundation` (develop) | `0b695c9` | READY FOR LEADER REVIEW (CI note below) |
| [#123](https://github.com/hhtuann/Quizopia_2.0/pull/123) | PR B — Assessment quiz-ID revocation fence | `feature/fb015b-assessment-revocation-fence` (#122) | `f5db29b` | READY FOR LEADER REVIEW |
| [#124](https://github.com/hhtuann/Quizopia_2.0/pull/124) | PR C — Proctoring cleanup, FK-safe purge, retention holds, saga | `feature/fb015c-dependent-purge` (#123) | `9dd7335` | READY FOR LEADER REVIEW (CI note below) |
| [#125](https://github.com/hhtuann/Quizopia_2.0/pull/125) | PR D — Frontend delete UX | `feature/fb015d-frontend-delete-ux` (#124) | `969656b` | READY FOR LEADER REVIEW |

All destructive capabilities default OFF (`QUIZ_DELETION_ENABLED`,
`ASSESSMENT_DELETION_ENABLED`, proctoring equivalent — all `false`).
Destructive tests ran only inside Testcontainers disposable fixtures.

## 2. What each PR delivers (evidence summary)

**PR A (quiz-service)**: `POST /api/quizzes/{quizId}/deletion-requests` +
`GET .../{operationId}` (TEACHER + server ownership + exact-title confirmation +
`Idempotency-Key` safe-replay/409-mismatch, `no-store`, anti-enumeration 404);
forward-only durable state machine (8 main + 4 side states, optimistic+pessimistic
locking, sticky audit reason); authoring/publishing fence; frozen version
inventory; ADR-009 outbox `quiz.deletion.requested.v1` (sanitized payload) with
bounded-retry dispatcher; snapshot-handoff denial for NEW publications only
(existing publications keep reading assessment-owned snapshots); flag-off =
indistinguishable 404, no rows, no scheduler. **192/192 tests** (25 new).
QuizVersions never mutated/deleted in this PR.

**PR B (assessment-service)**: `quiz_revocation_fences` + `deletion_command_inbox`
(UNIQUE operation_id, redelivery-safe); hardened internal
`/internal/security/quiz-revocations/batch` + `/{quizId}/status` (exact
quiz-service SERVICE JWT + scope, identity-style transport/body checks, rate
budget 429 no-store); fence gate enforced server-side in EVERY affected route
BEFORE work/replay/receipt (directory, PUBLIC+private locator, entry, START,
RETAKE, autosave, SUBMIT, ABANDON, result/review, teacher results, closure,
monitoring, background workers) → uniform `404 ASSESSMENT_REMOVED`;
active-attempt serialization: fence tx locks affected publication rows then
samples `revoked_at`, writers recheck inside their row-locked transaction
(lock-latch race tests prove before-fence commits survive for later purge,
after-fence denied, no post-fence finalization); ack worker sets
`acknowledged_at` only after in-flight mutation timestamps drain.
**469/469 tests** (25 new); empty fence table = byte-identical behavior
(all 444 pre-existing tests unchanged).

**PR C (quiz + assessment + proctoring)**: Proctoring fence (new
sessions/capture/media grants + monitoring reads denied; ack) + narrow privileged
purge (media_grants→rooms→access_audit→evidence→sessions) guarded by
acknowledged fence + transaction-local GUC branch inside existing guard trigger
functions — **no trigger disabled/dropped**, rows re-resolved to their quiz;
provider erasure honestly `PENDING_PROVIDER` only. Assessment V10: purge receipts
+ record-scoped `retention_holds`; hardened purge endpoint; FK-safe ordered
bounded purge (cyclic deferrable FKs handled via transactional drop/re-add inside
the single purge transaction; RESTRICT FKs keep child-before-parent); independent
18-table COUNT verification before `PURGED`; holds → `BLOCKED_BY_RETENTION`;
purge refused until fence acknowledged. Quiz saga: client-credentials dependency
client, advances `DEPENDENCIES_FENCING → ACCESS_REVOKED` (only after BOTH acks) →
purge → `PRIMARY_PURGED` (tombstone ledger survives, V5 drops ops→quiz FK);
outage → `WAITING_DEPENDENCY` retry; idempotent per operationId.
**ALL_COPIES_ERASED deliberately unreachable** — status note
`COPY_EXPIRY_PENDING` until verified backup/provider expiry (no false completion).
Tests: quiz **196/196**, assessment **476/476**, proctoring **239/239**
(21 new; unrelated-quiz + Identity/Classroom integrity asserted).

**PR D (frontend)**: destructive Delete Quiz action (Library + editor),
accessible exact-title confirmation dialog, stable-per-intent Idempotency-Key,
honest step mapping ("fully deleted" ONLY at `ALL_COPIES_ERASED`;
retention/copy-pending states honest), fenced disable states + 409 handling,
terminal navigation, vi/en (30 new keys), responsive per RTL patterns.
typecheck/lint/format clean; vitest **930/930** (+61); build 16/16 pages;
e2e **137/137** (delete spec is route-mocked — real deletion requires the
default-OFF flag; documented in spec header).

## 3. Stage 2 — independent self-review findings

- Gateway CI jobs on #122/#124 failed across three runs with **three different
  gateway tests** (`RealtimeInvalidationFanout…slowSocketOutboundOverflow`,
  `PublicationTopologyTest…ForgedHeaders`, `ProctoringTopologyTest…` 2 cases).
  The branches touch no gateway code; #123 (superset of #122) gateway **passed**.
  Classification: runner load flakiness, not regression. Failed jobs re-run;
  see final CI table in the morning report. If a gateway job fails again with
  yet another test, treat as infra and re-run/bisect on a quiet runner.
- Purge order verified against the actual V4 FK graph; cyclic deferrable FKs
  confirmed handled transactionally; `verified_zero` COUNT checks asserted in
  tests (real DB state, not mocks).
- Cross-service contract check: frontend client zod ↔ PR A controller DTO ↔
  PR C status `note` all aligned (`deletionStatus`, `note` normalization).
- Known honest gaps (also in PR bodies): no backup-copy inventory yet;
  provider receipts only requested; pre-V4 proctoring sessions (quiz_id NULL)
  denied via fence but not quiz-purgeable; no "latest operation by quiz"
  endpoint (frontend banner limitation); internal OpenAPI YAMLs for purge
  endpoints deferred (same shape as PR B contract).

## 4. Stage 3 — post-merge frontend integration (develop `607dc5f`)

develop CI (frontend job) is green after both #114 and #119 merges; earlier in
this session the same suites were verified locally on the merged tree
(vitest 869→899 through the session, e2e 147–150 with one known geometry flake
passing in isolation). No reproducible regression found in: fullscreen attempt
workspace, navbar consistency, flags/navigator states, NUMERIC_FILL autocomplete,
create-on-first-save, results-first layout, student display names, profile
settings, vi/en, auth/proctoring authority. **No action needed.**

## 5. Stage 4 — BR-01 readiness matrix

Authoritative definition: `docs/decisions/teacher-self-attempt-br01-decision-memo.md`
(PENDING LEADER PRODUCT APPROVAL). Requirement classification:

| Requirement | Class | Evidence/Reason |
|---|---|---|
| Leader decision: owner official scored attempts allowed? | **BLOCKED (Leader)** | Memo explicitly withholds implementation approval; no code may decide this |
| Leader decision: separate Teacher Preview/Test experience? | **BLOCKED (Leader)** | Same memo |
| Source-trace of current paths (entry/start/retake/resume/results) | VERIFIED | Memo tables verified against current code during PR B work (fence touches these exact paths) |
| No workspace-based authorization change | VERIFIED | Roles unchanged; workspace remains navigation-only |
| If approved: owner exclusion for new official attempts + preview isolation | NOT TESTED (not implemented — requires approval first) |
| Test coverage for the chosen policy | NOT TESTED (same) |

**BR-01 cannot be closed by engineering work; it awaits the Leader product
decision.** BR-01 stays PENDING.

## 6. Stage 5 — production readiness matrix (audit only; nothing deployed)

| Area | Code-ready | Staging-ready | Production-ready | Blocker |
|---|---|---|---|---|
| FB-015 deletion chain | Yes (flags OFF) | Rehearsal on classified staging data requires owner/ops approval + schema backup | **NO** | Privacy/legal signoff (Law 91/2025/QH15 Art.14/19 review), backup/provider erasure evidence, emergency-stop drill |
| Auth/Identity/Gateway admission | Yes | Yes | NO | Ops signoff, DR rehearsal (ADR-002/007 gaps tracked separately) |
| Migrations V4–V10 (quiz/assessment/proctoring) | Yes (isolated migration tests) | Requires staging rehearsal incl. legacy pre-V4 proctoring rows | NO | Same as above |
| Observability/rollback | Partial | Partial | NO | Deletion-run observability dashboards + restore-order runbook (restore must re-apply fences before exposure) not yet built |

Production stays **NO-GO**; no flags changed; no data touched.

## 7. Highest-priority Leader decisions

1. Merge order confirmation for #121 → #122 → #123 → #124 → #125.
2. BR-01 product decision (owner official attempts + preview isolation).
3. Approve retention-hold custodianship/authority model + audit TTL for
   deletion receipts (ADR-02 §2 leaves TTL open).
4. Approve actual backup-expiry/provider-erasure policy before any
   `ALL_COPIES_ERASED` can ever be emitted.
5. Privacy/legal review (Vietnam Law 91/2025, Decree 356/2025) for deletion
   activation.

## 8. Safe next implementation tasks (no approval needed)

- Gateway flake triage/quarantine on a quiet runner (infra, reproducible?).
- Internal OpenAPI YAMLs for purge/revocation endpoints (shape already fixed).
- "Latest deletion operation by quiz" endpoint (small, unblocks frontend banner).
- Deletion-run observability (metrics/log sanitation already enforced; add dashboards).
- E2E real-runtime delete journey behind local-only flags on disposable fixtures.
