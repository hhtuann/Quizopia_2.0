# BR-01 — Teacher self-attempt and isolated Preview/Test decision memo

> **Status: PENDING LEADER PRODUCT APPROVAL**
> Recorded: 2026-10-26. Branch: `feature/teacher-assessment-classroom-management`.
> Scope: decision support only. No enforcement, endpoint, schema or permission change is approved by this memo.

## Authority and question

[Assessment Core](../specifications/assessment-core-policy.md#accepted-w3-a-scope-and-remaining-contracts)
explicitly neither grants nor denies an owner's permission to attempt their own
Publication. [Roles/personas](../product/roles-and-personas.md) permits simultaneous
STUDENT + TEACHER; Teaching/ Learning is navigation context, not authorization.
[ADR-015](ADR-015-w3b-w3c-leader-policy.md) governs official attempts, results and
closure. [T3 kickoff](../development/teacher-assessment-t3-kickoff.md) authorizes
FE-01/FE-02 independently and expressly withholds BR-01 implementation approval.

Leader must decide whether an otherwise eligible owner may create an **official
scored attempt**, and whether a separate Teacher Preview/Test experience should
exist. Current source behavior is evidence, not a new product entitlement.

## Current authorization paths — source trace

Paths below are relative to repository root; Assessment Java files are under
`services/assessment-service/src/main/java/com/quizopia/assessment/`. Gateway
admission inventories are under
`gateway/src/main/java/com/quizopia/gateway/security/admission/`.

| Path | Current trace and boundary |
| --- | --- |
| Edge/downstream | Gateway `PublicationRoutes.java` delegates attempt paths to `StudentAttemptRoutes` as STUDENT; Assessment's matching inventories and `security/admission/UserAdmissionFilter.java` enforce independent JWT/current admission and feature gates. USER + issued/current STUDENT is required even for a dual-role Teacher. Workspace does not change this. |
| PUBLIC entry | `POST /api/assessments/publications/entry` → `api/PublicationController.publicEntry` → `application/PublicationService.publicEntry` → `persistence/JdbcPublicationStore.publicEntry`. Body carries the private 43-character locator; safe Student metadata is returned, not the snapshot or answer keys. LD-05 still applies; PUBLIC means audience, not anonymous access. |
| CLASS entry | `POST /api/assessments/publications/{id}/entry` → `PublicationService.classEntry` → store lookup and `PublicationSources.member`. Classroom is authoritative for current membership; owning/teaching the class is not itself membership. Shared URLs do not bypass it. |
| START | `POST /api/assessments/publications/{id}/attempts/start` → `api/StudentAttemptController.creation` → `application/StudentAttemptService.start` → `persistence/JdbcStudentAttemptStore.start`. Controller derives actor from JWT and hashes Idempotency-Key. Service checks current CLASS membership before the local transaction. Store locks Publication/closure requests; checks locator and actor expiry; replays a matching receipt or selects latest history. START resumes ACTIVE or returns latest terminal; only absent history creates the first attempt. New creation requires OPEN, no pending closure, entitlement and server-time window. |
| RETAKE | `POST /api/assessments/publications/{id}/attempts/retake` uses the same controller/service/store with action RETAKE. New creation requires terminal predecessor, no ACTIVE attempt, remaining entitlement, OPEN/window and no pending closure. It is explicit and retry-safe, never an implicit START retry. |
| PUBLIC creation/selection | `JdbcStudentAttemptStore.locator` requires a matching locator for START/RETAKE, including existing selection/replay. CLASS rejects a supplied locator. No Publication-owner exclusion is present in these paths. |
| Resume and ongoing work | `GET /api/assessments/attempts/{attemptId}` → `StudentAttemptService.resume` → `store.owned` (`id` + `student_id`), current CLASS membership, then locked `store.resume`. PUBLIC owned-attempt operations need no locator. Existing ACTIVE work is not a new start and may outlive endAt; due deadline/notice-cutoff reconciliation may finalize it. Resume is therefore not a side-effect-free preview. SAVE/SUBMIT/ABANDON use the same owned-attempt/current-membership boundary. |
| Results/review | Student result/aggregate/review remain owner-scoped in `StudentAttemptService` / `JdbcStudentAttemptStore`; review requires eligible submitted work and durable CLOSED. Teacher results remain Publication-owner-scoped in `ClosureService` / `JdbcClosureStore`. T3 directory/manage/results links add no answer-key entitlement. |

`PublicationService.prepare` separately requires Classroom Teacher authority for
CLASS create/edit/open and source QuizVersion ownership. That management authority
does not replace Student membership. The private `PublicationSources` integration
uses service credentials and original USER evidence, not cross-service SQL.

## Risk: Teacher-owned official scored attempts

An eligible dual-role owner can reach the ordinary scored path under the current
implementation. Source authors can know the keys; test submissions can contaminate
official results/aggregates and any future reporting/gradebook consumers. Official
attempts consume entitlement, create durable history and can become ACTIVE blockers
to CLOSE or require the accepted notice workflow. Where enabled, official writes
can produce realtime invalidations and answer-persistence monitoring facts; eligible
CLASS attempts can enter monitoring flows. An official “try it” is not harmless.

This is not evidence that Teacher role alone bypasses CLASS membership, that keys
are currently leaked by learner projections, or that a final gradebook event is
already implemented. Changing only a button or workspace cannot secure the paths.

## Recommendation — not enforcement

Recommend a future, separately approved **owner exclusion for new official
START/RETAKE creation**, paired with isolated Preview/Test. Keep Teachers eligible
as Students on other owners' Publications under ordinary rules. Preserve current
behavior until Leader decides, including explicit treatment of existing owner
attempts, receipt replays, START selection and resume. Do not silently reject or
delete existing work, regrade history, or introduce a global TEACHER prohibition.

Alternative: explicitly accept eligible owner participation and its scored-history
consequences, with a clearly separate read-only preview later. Either choice needs
written approval; FE-01/FE-02 do not choose it.

## Isolated Preview/Test — conceptual design only

- Assessment owns Publication preview orchestration and its finalized delivery
  snapshot; Quiz owns immutable source QuizVersion. OPEN/CLOSED preview should use
  the exact Assessment snapshot. A DRAFT preview could acquire the exact immutable
  version through the existing owner-authorized private Quiz integration, without
  opening the Publication or committing an official snapshot. State coverage and
  source-unavailable behavior require approval.
- Use a distinct owner-only Teacher operation and distinct Preview DTO/context,
  never an official attempt with a flag. Bind every request to validated USER,
  current TEACHER and persisted Publication ownership; retain independent Gateway
  and downstream LD-05, default-closed inventory and sanitized denials. Source
  QuizVersion access must independently satisfy source ownership. Possession of a
  UUID, public locator, cursor or preview identifier is not authorization.
- Default concept: read-only delivery rendering, with optional answer interaction
  held only in page memory and discarded on exit. No official Attempt/answer/
  receipt/grade/result/aggregate row, entitlement consumption, Publication mutation,
  closure notice, outbox/integration/realtime event, monitoring session/evidence,
  camera permission or LiveKit token. Do not invoke START, resume, SAVE, SUBMIT or
  finalizers as a shortcut; do not use real students or impersonation.
- Snapshot confidentiality: project only required delivery fields. Do not return
  the raw answer-bearing snapshot, keys/explanations, private locator, other owners'
  content or learner records. Any answer reveal or ephemeral scoring is a separate
  explicit approval item, with its own minimal owner-only projection; hiding raw
  keys in the UI is insufficient. Use no-store, bounded responses, safe Markdown,
  no token/content logging and no persistent browser cache/localStorage.
- Preview isolation includes telemetry: ordinary redacted operational security
  logging may be retained under approved policy, but must not become official
  assessment facts or capture simulated answers/content. Any future server session
  requires separate non-official storage, TTL/deletion and audit approval; no reuse
  of official tables or triggers. Stateless preview is the recommended baseline.

## Implementation implications if approved

| Area | Required follow-up |
| --- | --- |
| Backend | Centralize any new-creation owner comparison inside the locked authoritative operation; define selection/replay/resume compatibility first. Separate preview read/projection code from official writes and lazy finalization. Keep Assessment/Quiz/Classroom ownership unchanged. |
| API | Freeze distinct preview request/response and exact route, state/version binding, limits and stable error codes before coding; update service OpenAPI and generated clients. Decide conflict vs masked denial for eligible self-start without disclosing foreign resources. No route/error code is allocated here. |
| Security | Test dual-role actors, other owners, service principals, revoked/stale roles, direct downstream calls, LD-05 outage, CLASS membership, locator replay and snapshot/answer confidentiality. Preview must not confer official attempt or monitoring authority. |
| Storage/events | A stateless projection needs no new business table. If session/audit persistence is chosen, define separate ownership, retention and additive Flyway work. Prove zero official rows, receipts, grades, results, outbox rows and monitoring side effects; do not migrate/delete existing attempts by inference. |
| Frontend | Distinct “Preview/Test — not an official submission” entry and banner, no official timer/submit/result/realtime/monitoring hooks, no auto-start on navigation. Exit discards local answers. Guards are UX only; generated API contracts and backend authority remain mandatory. |

## Explicit Leader decisions required

1. Accept owner scored participation, or exclude **Publication owner** from new
   official attempts? Confirm both PUBLIC/CLASS and dual-role users, without a
   blanket ban on Teachers taking other owners' assessments.
2. Define existing ACTIVE/terminal owner history, START selection, RETAKE, durable
   replay, resume/SAVE/SUBMIT/ABANDON and cutoff rights. Approve compatibility and
   rollout; decide any historical reporting exclusion separately, without deletion
   or automatic regrading.
3. Approve Preview vs interactive Test, supported DRAFT/OPEN/CLOSED states, exact
   source snapshot/version and whether feedback, keys or explanations are ever
   permitted. Read-only/no-key rendering is the recommended starting point.
4. Approve owner-only authorization, source ownership, confidentiality/error
   semantics, stateless discard and zero official/event/monitoring effects; decide
   any separate preview storage/audit retention explicitly.
5. Approve implementation scope, default-off rollout and independent contract,
   security, isolation and regression acceptance before activation.

**Decision record:** pending. Approver/date/rationale: pending Leader product
decision. This memo changes no accepted policy or inherited release **NO-GO**.
