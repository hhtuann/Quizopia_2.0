# FB-015 — Quiz & Dependent-Data Deletion: Architecture Decision Package

> **Status: DESIGN READY / APPROVAL PENDING — no destructive behavior
> implemented.** Cross-service destructive deletion requires explicit Leader
> approval of this package before any backend migration, purge job, privileged
> API or frontend delete control ships. Product request (Issue #115) is NOT
> blanket approval.
>
> **Branch:** `feature/ux-round3-assessment-workspace` (docs only).
> Authoritative constraints: AGENTS.md service boundaries, Assessment Core
> policy (immutable snapshots, result retention), LD-05, V4 realtime outbox
> contract.

## 1. Current ownership map (source-verified)

| Data                                         | Owning service                                             | Delete today?                                        |
| -------------------------------------------- | ---------------------------------------------------------- | ---------------------------------------------------- |
| Quiz draft + QuizVersions                    | Quiz (`quiz`, `quiz_version`; immutable once published)    | **No delete endpoint exists**                        |
| Publications + snapshot + attempts + results | Assessment (`publication`, attempts, results)              | No delete; CLOSE is terminal lifecycle, not deletion |
| Attempt monitoring/proctoring evidence       | Proctoring (per-attempt sessions/evidence)                 | None                                                 |
| Closure requests + recipient notices         | Assessment (inbox records reference publications)          | None                                                 |
| Classroom association                        | Classroom (membership independent of quiz)                 | None (publications reference classroomId only)       |
| Realtime invalidation                        | Gateway/Assessment outbox events reference publication ids | N/A (ephemeral)                                      |
| Identity accounts                            | Identity — **never in scope** (students outlive quizzes)   | —                                                    |

Cross-service hard references: Assessment stores `quiz_id`/`quiz_version_id`

- a **self-contained snapshot** (active attempts do NOT depend on Quiz
  service — AGENTS.md correctness rule), and optional `classroom_id`.
  Quiz deletion therefore does not break in-flight attempts technically — but
  revocation semantics are product decisions, not mechanics.

## 2. Core conflicts with accepted rules

1. **Published QuizVersion immutability** (AGENTS.md, ADR-003): versions are
   deliberately immutable; deleting them retroactively breaks the audit story
   of every already-graded attempt whose snapshot cites them.
2. **Result retention / student rights**: students own results of submitted
   attempts. Acceptance checklist requires "Student access to deleted
   assessments" handling; unilaterally destroying graded results likely
   conflicts with retention obligations (grade disputes, appeals).
3. **Active attempts**: an in-flight student submission must never be
   destroyed mid-save; server deadlines are authoritative and submit is
   idempotent — deletion must not race them.

## 3. Proposed contract (for approval, not implemented)

### Decision 1 — Tombstone-first, purge-later (recommended)

Phase 1 **REVOKE** (synchronous, safe, reversible-by-support):

- `DELETE /quizzes/{quizId}` (Quiz service, TEACHER owner + LD-05) marks the
  Quiz + all its QuizVersions `REVOKED` (soft tombstone), publishes
  `quiz.revoked.v1` integration event via the existing transactional outbox.
- Assessment consumes the event: marks dependent Publications
  `ACCESS_REVOKED` (new terminal state; deny entry/start/retake with a
  clear "no longer available" error; private locator invalidated server-side),
  while **preserving attempts/results for already-submitted students and
  the owner** (read-only history).
- Proctoring/Classroom: no destructive action; revoked publication id simply
  yields deny.
- Idempotent (revoke twice = same state), saga-safe: a missed event is
  healed by Quiz re-publishing + Assessment reconciliation sweep on read.

Phase 2 **PURGE** (asynchronous, optional, separately approved):

- Only for publications with zero non-replaceable records after a retention
  window (proposal: 90 days post-revocation, configurable).
- Coordinator = Quiz orchestrates per-dependency purge commands with the
  same outbox pattern; partial failures retry idempotently; every purge is
  append-only audited (`quiz_purge_log`).
- Hard-deletes Quiz draft/versions only after Assessment acks purge of
  its dependent records; Classroom untouched; Identity never touched.

### Decision 2 — scope matrix (Leader must check every row)

| Record                        | On revoke                                                                                                                                | On purge                                                | Rationale                                                                                                  |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| Quiz draft                    | tombstone                                                                                                                                | hard delete                                             | owner-only, no student impact                                                                              |
| QuizVersions                  | tombstone                                                                                                                                | hard delete                                             | immutability preserved until purge window closes                                                           |
| Publications (no attempts)    | ACCESS_REVOKED                                                                                                                           | hard delete                                             | nothing student-owned                                                                                      |
| Publications (attempts exist) | ACCESS_REVOKED                                                                                                                           | **retain unless Leader opts to destroy graded results** | conflicts with retention/appeals — needs explicit product sign-off                                         |
| Submitted attempts/results    | retained read-only                                                                                                                       | default retain (destroy only with explicit approval)    | student-owned evidence                                                                                     |
| ACTIVE attempts               | deny new saves? — **decision needed**: propose allow-graceful-finish until deadline, then auto-terminate to SUBMITTED-with-saved-answers | as above                                                | mid-save destruction is unacceptable; graceful-finish honors "existing ACTIVE work may continue" precedent |
| Closure requests/notices      | link dead (render revoked)                                                                                                               | hard delete                                             | derived UX records                                                                                         |
| Proctoring evidence           | deny new capture                                                                                                                         | purge                                                   | references revoked ids                                                                                     |
| Audit/purge log               | —                                                                                                                                        | append-only forever                                     | accountability                                                                                             |

### Decision 3 — API & events

- `DELETE /quizzes/{quizId}` → `204`; `409 QUIZ_HAS_ACTIVE_DEPENDENTS` only
  if Leader rejects graceful-finish; body option `{"purge": "revoked-only"}`.
- Event `quiz.revoked.v1 {quizId, revokedAt, ownerTeacherUserId}`; consumer
  idempotency required (AGENTS.md); no cross-service DB writes.
- Recovery: `POST /quizzes/{quizId}/restore` (support/admin only) reverts
  tombstone + publishes `quiz.restored.v1`.

### Decision 4 — UX

- Confirm dialog types the quiz title; copy states clearly: "Students can no
  longer start or open this assessment. Submitted results are kept
  [or: will be deleted]." — the retention row decision is user-visible.
- Frontend delete ships ONLY after revoke phase exists; never claims data
  deletion while purge is pending (button label "Revoke & remove access").

### Decision 5 — test obligations (post-approval)

Real-stack: revoked publication denies entry/start for new + existing
students even while backend cleanup pending; in-flight submit completes;
locator 404; duplicate DELETE idempotent; consumer-outage reconciliation;
purge partial-failure retry; audit rows exist.

## 4. Explicit Leader decisions requested

1. Approve tombstone-first two-phase design (vs direct hard delete).
2. Retention ruling: are graded results/attempt evidence purgable with the
   quiz, or retained? (recommended: retain).
3. Active-attempt ruling: graceful-finish-then-terminate vs immediate deny.
4. Retention window length before purge eligibility.
5. Approve new `quiz.revoked.v1` event + `ACCESS_REVOKED` publication state
   (cross-service contract changes).
6. Confirm Identity accounts are never touched.

Until approval: no deletion UI, no migrations, no purge jobs ship.
