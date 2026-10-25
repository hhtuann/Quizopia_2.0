# T3 — Teacher Assessment Management & Classroom Experience

> **Status:** KICKOFF / CONTRACT AND IMPLEMENTATION IN PROGRESS — not a completed feature.
> **Baseline:** `develop` `9d16d0ed20fffb0eca6ef4a05b9b3cd820866519`, merge PR #110.
> **Branch:** `feature/teacher-assessment-classroom-management`
> **Design source:** `DESIGN.md`; repo instructions: `AGENTS.md`.
> **Authoritative rules:** `docs/specifications/api-conventions.md`, `docs/specifications/assessment-core-policy.md`, `docs/product/assessment.md`, `docs/product/classroom.md`, `docs/product/roles-and-personas.md`, existing OpenAPI and LD-05 security contracts.

## Approved delivery scope (implementation can proceed)

### FE-01 — Owner-scoped My Assessments

- Add a bounded, cursor-paginated **GET `/api/assessments/publications`** for the authenticated Teacher's own Publications.
- New collection projection should contain only the metadata actually needed to list and navigate Publications (id/title/audience/state/schedule/revision/relevant Quiz references); **do not include private PUBLIC locator, answer-bearing snapshot, other teachers' data, learner identity, attempts or grading keys**. Full existing owner details remain on the guarded detail endpoint.
- Derive actor from authoritative validated USER JWT. Keep TEACHER admission, service-side access control, LD-05 fail-closed checks and existing feature flag behavior.
- Apply owner filtering **in the database query**, not by fetching all records and filtering the response. Use deterministic cursor pagination, bounded `limit`, reject invalid/foreign cursors; ensure stable pagination under ties and updates. Follow existing repo conventions.
- Expand the **existing** `/app/assessments` teacher entry into a My Assessments directory: states DRAFT/OPEN/CLOSED, search/filter, loading/empty/error/pagination states, appropriate actions to open/manage/results. Keep the immutable QuizVersion creation workflow. Remove manual UUID entry from primary UX (optional advanced fallback only if justified).
- Integrate discovery/navigation from the TEACHING workspace without conflating workspace with JWT role. No new results or answer-key entitlement.

### FE-02 — Owned Classroom listing + visual CLASS selector

- Add a bounded, cursor-paginated **GET `/classrooms`** through Gateway and Classroom Service with TEACHER USER authorization, LD-05 admission and persisted owner enforcement. Do not bypass or weaken existing `POST /classrooms` and `GET /classrooms/{classroomId}` rules.
- Classroom already stores id, ownerTeacherUserId, name, subject, grade and optional description; build a safe compact list projection.
- Add a responsive, accessible, keyboard-operable Classroom card/radio selector for CLASS Publication create and DRAFT edit; use actual id in existing `classroomId` wire field.
- Support loading, empty/zero-class, fetch/authorization errors, pagination, and expired/unowned previously selected Classroom references. Never auto-select the first Classroom.
- Preserve approved rule: **1 CLASS Publication references exactly 1 Classroom**. PUBLIC must clear classroomId; CLASS monitoring choice remains explicit before OPEN.
- Continue backend Classroom-owned Teacher assignment authorization when creating/editing/opening; do not substitute visual selection for server authority.
- Include a meaningful no-classroom path to existing creation capability if appropriate, without broadening this task into unapproved invitation/join flows.

### Cross-cutting acceptance

- Update canonical backend OpenAPI, frontend generated/derived types and clients, documentation, Gateway routing and test fixtures consistently.
- Backend tests for other-owner leakage, invalid/foreign cursors, unavailable admission, gateway path/role enforcement, stable pagination, and allowed field projection.
- Frontend unit/RTL + Playwright at desktop 1920/1440, tablet 768, mobile 390 for My Assessments and CLASS selector. Run real Browser → Gateway → Classroom/Assessment smoke journeys if provisioned runtime allows.
- Keep untouched the T1/T2 UX, workspace transition, unsaved-work guards, DateTimeField and existing STUDENT/TEACHER result/review rules.
- Preserve all local Windows runtime helpers and ignored secrets; no destructive Git operations. No production feature flag enablement.
- One implementation branch and one PR for T3; independently review before merge.

## Product decision gate — BR-01 (NOT IMPLEMENTATION-APPROVED)

The existing product specification explicitly says that the Wave 3 contract **neither grants nor denies an owner's permission to attempt their own Publication**. The currently accepted dual-role USER may have both STUDENT and TEACHER.

**Do not introduce a blanket self-owned official attempt prohibition, an alternate Teacher Preview/Test attempt execution flow, or a new result/grading/persistence mode in this T3 implementation without a separate written Leader product decision.**

Permitted now: inspect and document existing semantics, threat model, alternative Preview/Test designs, response/error implications, and affected Student START/RETAKE/resume/CLASS/PUBLIC paths. Create a clear decision note marked `PENDING LEADER APPROVAL`. Keep FE-01/FE-02 code moving independently. Do not silently alter policy in backend or frontend.

Proposed decision options for Leader (not chosen):
1. Preserve current STUDENT eligibility even on owned Publication; add a distinct read-only preview later.
2. Block owner on official START/RETAKE for owned Publication and implement an isolated, non-scored, non-persisting Teacher preview with explicit authorization and audit/privacy details.

## Repository inspection findings on kickoff (source-verified)

| Area | Existing reality | Integration requirement |
|---|---|---|
| Assessment `PublicationController` | `POST /api/assessments/publications` and `GET /api/assessments/publications/{id}` exist; collection GET is absent | Add owner-scoped collection GET, separate safe summary DTO |
| Assessment `PublicationStore` / `JdbcPublicationStore` | `owned(actor,id)` exists and SQL persists `owner_id`, `created_at`, `state`, `configuration` | Add efficient index/keyset query, support cursor and owner filter |
| Assessment admission | `PublicationRoutes.requiredRole` closed inventory enumerates paths | Add GET collection requiring TEACHER; preserve LD-05 and feature gating |
| Classroom `ClassroomController` | `POST /classrooms`, `GET /classrooms/{id}`; collection GET absent | Add protected owned collection GET |
| Classroom persistence | JPA `ClassroomEntity` already has persisted `ownerTeacherUserId`, name, subject, grade, description | Repository owner-scoped pagination, deterministic tie-breaking |
| Classroom admission/Gateway | `POST /classrooms` routing and explicit policy; `GET /classrooms/{id}` existing; no `GET /classrooms` edge route | Add exact Gateway GET route and TEACHER admission; retain existing authorization/fail-closed behavior |
| Frontend | `PublicationEntry` in `publication-pages.tsx` still asks teacher to paste Publication UUID | Replace with owner directory as primary journey; preserve deep-link detail and QuizVersion-based create |
| Frontend CLASS creation | `publication-configuration-form.tsx` asks user for manual Classroom UUID | Replace with owned Classroom card/radio selector, preserving existing form state and guard |

## Work sequence for the implementation agent

1. Re-read authoritative docs, inventories and latest Git; verify branch baseline and no local clobber.
2. Freeze bounded endpoint contracts and projection fields; identify migrations/index/feature gate impacts and add tests.
3. Implement FE-01 backend and frontend end-to-end.
4. Implement FE-02 backend/Gateway and frontend end-to-end.
5. Add independent BR-01 policy options memo **without changing enforcement**.
6. Run relevant Maven frontend, Gateway config, Vitest, Playwright and available real-stack tests; inspect screenshots.
7. Update this document with actual API shapes, changed paths, test evidence, full scope audit, CI, PR link and remaining risks; label any work not finished.
8. Commit/push the scoped branch and open **one PR** to `develop`; leave it unmerged.

**Security invariant:** No cross-teacher resource disclosure. Results and answer release stay controlled by accepted Assessment policies. Owner collection responses must not leak PUBLIC private locator or answer keys.
