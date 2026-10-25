# T3 — Teacher Assessment Management & Classroom Experience

> **Status:** FE-01/FE-02 implemented on branch; local verification recorded below. Pending independent implementation/CI review and Leader merge approval; not merged. BR-01 remains pending product approval.
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

Permitted now: inspect and document existing semantics, threat model, alternative Preview/Test designs, response/error implications, and affected Student START/RETAKE/resume/CLASS/PUBLIC paths. The [BR-01 decision memo](../decisions/teacher-self-attempt-br01-decision-memo.md) is **PENDING LEADER PRODUCT APPROVAL**. Keep FE-01/FE-02 independent. Do not silently alter policy in backend or frontend.

Proposed decision options for Leader (not chosen):
1. Preserve current STUDENT eligibility even on owned Publication; add a distinct read-only preview later.
2. Block owner on official START/RETAKE for owned Publication and implement an isolated, non-scored, non-persisting Teacher preview with explicit authorization and audit/privacy details.

## Repository inspection findings on kickoff (historical, source-verified)

This table records the starting point; the delivery record below supersedes its
“absent” findings for the current branch.

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

## T3 branch delivery record — 2026-10-26

### Actual contracts, security and persistence

Assessment owns FE-01 and Classroom owns FE-02; Gateway supplies the exact public
edge routes. Canonical contracts are
`services/assessment-service/contracts/publications.openapi.yaml` (1.4.0) and
`services/classroom-service/contracts/classroom-directory.openapi.yaml` (1.0.0).

| Contract | Implemented wire behavior |
| --- | --- |
| `GET /api/assessments/publications` | `limit` default 20, range 1–100; optional `state=DRAFT\|OPEN\|CLOSED`, `search` (max 300 before normalization), `cursor` (1–1024 chars). Rejects unknown/duplicate query parameters, including actor/owner overrides. Search is stripped/lowercased with Locale.ROOT and uses literal case-insensitive title substring matching; `%`, `_` and backslash are not SQL wildcards. |
| Publication page | `{items, nextCursor}`; each item has exactly `publicationId`, `title`, `audience`, `state`, `startAt`, `endAt`, `createdAt`, `revision`, `quizId`, `quizVersionId`, `versionNumber`. No configuration, private PUBLIC locator, snapshot, answer key, learner, attempt or owner identity. Empty items and null continuation are valid. |
| `GET /classrooms` | Through Gateway without path/query rewriting; `limit` default 20, range 1–100; optional opaque `cursor` (max 512 chars). No directory search/state filter is introduced. Existing create/detail and internal authority contracts remain applicable. |
| Classroom page | `{items, nextCursor}`; item fields `id`, `name`, `subject`, `grade`, nullable `description`, `createdAt`. No owner ID, membership, invitation or Student data. No total count on either directory. |

Both queries filter persisted ownership by the validated USER actor **in the
database**, fetch at most `limit + 1` safe projections, and order by immutable
`createdAt DESC, id DESC` with PostgreSQL UUID tie-breaking. Metadata edits do not
move creation order. Newer inserts appear on refresh; current state/search
membership can change between pages. This is live keyset traversal, not a
multi-request transaction snapshot.

Publication continuations reuse `ClosureCursor` HMAC-SHA256, bound to actor,
`publications` route, normalized state/search digest and timestamp/UUID position.
Changing limit is allowed; malformed/tampered/foreign/filter-mismatched or
other-route cursors return `400 INVALID_REQUEST`. Existing
`ASSESSMENT_CURSOR_ACTIVE_KEY` and configured `quizopia.assessment.closure.cursor-keys` map
(`ASSESSMENT_CURSOR_KEY_V1` for the default v1 key) supply signing/verification
keys, even if inbox/results are disabled. Missing active
signing material when issuing a continuation fails closed with sanitized
`503 PERSISTENCE_UNAVAILABLE`; retained verification keys support rotation.

Classroom continuations use a purpose-separated HMAC-SHA256 key derived from its
existing `USER_ADMISSION_CLIENT_SECRET`, bound to owner and timestamp/UUID.
Instances with the same credential interoperate; credential rotation invalidates
old cursors (`400`, restart paging). Missing credential prevents a directory
response (`503`); no new secret or node-local random key is introduced. Cursors
are authenticated, not a promise of encrypted payloads; clients treat them as opaque.

Gateway and each owning service independently verify RS256 USER JWT, issued
TEACHER and fresh authoritative LD-05 TEACHER admission. ADMIN alone, service
principals and workspace selection confer no Teacher directory access. Required
authority disabled/unavailable/untrusted fails closed with sanitized
`503 AUTHORITY_UNAVAILABLE`, no cached verdict/JWT-only fallback, refresh loop or
outage logout. Responses use `Cache-Control: no-store`; anonymous/invalid user
and missing role retain 401/403 semantics. Exact GET collection inventories are
extended at both boundaries; aliases/future methods remain closed. Existing
Classroom detail ownership denial remains 403; foreign Publication detail is 404.

- Assessment Flyway **V8 `V8__owner_publication_directory.sql`** adds
  `(owner_id, created_at DESC, id DESC)` and
  `(owner_id, state, created_at DESC, id DESC)` indexes. Existing Publication
  timestamps and immutable creation semantics are reused.
- Classroom Flyway **V2 `V2__owned_classroom_directory.sql`** adds non-null
  database-default `created_at` (JPA read-only) and
  `(owner_teacher_user_id, created_at DESC, id DESC)` index. **Historical creation
  times were never recorded**: pre-V2 rows receive migration time, not reconstructed
  original creation time; UUID breaks ties deterministically. New rows use DB time.
- These additive migrations introduce no cross-service SQL, attempt/result schema,
  integration event or new service. Live evidence confirms both migrations ran.

### Generated contracts, UI and gates

`scripts/local/generate-publication-types.py` and
`scripts/local/generate-classroom-types.py` derive frontend contracts from the
canonical OpenAPI, write deterministic LF and support `--check`.
Outputs are `frontend/features/assessments/api/publication-contract.generated.ts`
and `frontend/features/classrooms/api/classroom-contract.generated.ts`; API clients
use the authenticated Gateway transport.

- `/app/assessments` now provides My Assessments with server title search,
  DRAFT/OPEN/CLOSED filters, cursor Load more, safe rows/cards, manage/results
  navigation and loading/empty/error/retry states. Creation leads to Quiz Library's
  exact immutable published-version selection. Teaching shell/home expose discovery;
  manual Publication UUID entry is no longer the primary journey.
- CLASS create and DRAFT edit use owned native-radio Classroom cards with paging,
  keyboard interaction, no automatic selection and zero-class guidance. Previously
  selected references outside loaded pages resolve through guarded detail; an
  inaccessible selection blocks review. PUBLIC clears `classroomId` and disables
  monitoring. Existing confirmation/unsaved-work guards and explicit eligible CLASS
  monitoring choice remain; backend Teacher assignment authority remains decisive.
- Teacher role and Teaching workspace gate management UI; they do not mutate JWT
  roles or confer resource authority. Results/review policy remains unchanged.
- `PUBLICATION_ENABLED=false` still gates FE-01 at Gateway/Assessment;
  `USER_ADMISSION_ENABLED=false` remains the default authority control. Classroom
  listing has no new feature switch or admission bypass. Existing attempts,
  results, closure, inbox, realtime and monitoring controls are not enabled by T3.
  No production flags/secrets or runtime provisioning changes are authorized here.

### Verification evidence and limits

The following records implementation-agent verification and the supplied isolated
and live reports; documentation finalization does not claim a fresh full-suite run.

| Check | Recorded outcome |
| --- | --- |
| Assessment Java 21 Maven verification (`target/t3-fe01`) | 421 tests, zero failures/errors/skips. |
| Classroom Maven `verify` (`target/t3-directory-verification`) | 133 tests passed; owner/keyset/UUID ties, cursor integrity, safe projections, admission and existing authority regressions. |
| Gateway Maven `verify` (`target/t3-directory-verification`) | 181 passed, zero failures/errors; one additional opt-in W4 browser journey skipped (requires `-Dw4.browser=true`). |
| Gateway strengthened targeted rerun | All 36 passed, no skips: `GatewayAdmissionIntegrationTest`, `GatewayProductionRoutesTest`, `OwnerDirectoryInventoryTest`, `RealIdentityAdmissionTopologyTest`. |
| Generated-contract checks | Both generators' `--check` passed. |
| Isolated frontend | 779 unit tests in 70 files; 109 intercepted Playwright E2E passed, zero failures/skips/not-run; typecheck, lint, final format check and production build all passed. |
| Real local T3 integration | 63 successful assertions (49 main + 2 results recheck + 12 supplement), eight screenshots, zero browser page errors in those three runs; initial main-run final-stage timeout retained. Assertions are not independent test-case counts. |

#### Isolated frontend provenance

Detailed report:
`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t3-validation-report-20261026.md`.
Detached checkout:
`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t3-isolated-20261026`.
It applied the intended tracked frontend patch and eleven explicit untracked
frontend files, excluding the user's unrelated overlay edit. Its starting HEAD
`0f331477a3dd0a4bf94b10e3a330e9c4fff7093a` identifies the documentation kickoff,
**not a final implementation SHA**. Dependencies were installed independently
with frozen lockfile; the temporary Playwright config changed server isolation
only (production server port 3317, no reuse, no test exclusions/retries/assertion
weakening). Intercepted responses are fixture coverage, not live backend evidence.

The main worktree's unrelated `workspace-transition-overlay.tsx` edit
`2_500 → 1_500` causes deterministic baseline overlay unit/browser assertion
conflicts and remains **untouched and unstaged**. Full main-worktree frontend
green is not claimed. The isolated run retains reviewed T2's 2500 ms minimum;
baseline fixture/generated CRLF normalization occurred only in isolation for the
passing format check, with no source/formatter fix. A prior T2 390px client
exception did not reproduce in the full isolated run; no original root cause or
permanent absence of flakiness is claimed. Directory/selector fixture coverage
includes CLOSED and UI paging, keyboard/unsaved-navigation and no-overflow checks
at widths 1920, 1440, 768 and 390; screenshots are under the isolated
`frontend/test-results/`.

#### Real-service evidence

Read report:
`C:\Users\GIGABYTE\AppData\Local\Temp\opencode\t3-live-20261026\REPORT.md`.
Real Chromium → Gateway → Identity/Classroom/Assessment used real Quiz immutable
versions, login and a second Teacher registered/verified/enabled through real APIs.
No request interception, auth injection or direct database provisioning was used.
Gateway/Classroom executed `target/t3-directory-verification` jars; Assessment
executed its `target/t3-fe01` jar. Packaged class/resource and artifact-hash audits
bind evidence to the T3 builds; Identity/Quiz used existing unchanged runners.
The isolated production frontend was rebuilt with process-only Gateway API target;
no env file was written. The report retains runtime manifest and preservation audit.

Live assertions cover reciprocal Teacher isolation; safe/no-store projections;
literal search; DRAFT/OPEN filtering; limit-1 API pagination and invalid/foreign/
filter/tampered cursor rejection; Student/anonymous denials; exact-version creation;
keyboard Classroom selection; cancelled unsaved navigation; CLASS DRAFT creation;
PUBLIC edit clearing class/monitoring; and read-only existing Student/Teacher result
regressions. Eight screenshots show directory/selector at 1920×1080, 1440×900,
768×1024 and 390×844, with horizontal-overflow checks and desktop/mobile visual
inspection. Sanitized report/JSON/screenshots are evidence; private logs and
synthetic credentials must not be published.

**Retained limits:** the main run remains `INCOMPLETE` because its final direct
Teacher-results full-document/workspace-switch score assertion timed out. Separate
normal menu/directory → Results navigation passed (HTTP 200, existing 100.00%
score); this does not prove the initial variant passed or establish its cause.
CLOSED live filtering returned an owner-safe empty result, **no positive CLOSED
row**. Real pagination used browser API `limit=1`, **not >20-row UI Load more**;
positive CLOSED and UI paging have mocked/test coverage. Existing submitted attempts
were only read, not resumed/answered/submitted/retaken. No outage/revocation,
production TLS, multi-instance or load acceptance is inferred. Synthetic fixtures
remain for inspection; no existing business records were changed.

### Review, handoff and release disposition

- FE-01/FE-02 implementation and local evidence are ready for **independent review**;
  branch is not merged. BR-01 is documentation only and **PENDING LEADER PRODUCT
  APPROVAL**, with no self-attempt enforcement or Preview/Test implementation.
- Inherited [release gates](w3-production-release-gates.md) and
  [release decision pack](w3-production-release-decision-pack.md) remain **NO-GO**;
  local green suites and smoke evidence do not authorize production activation.
- Final implementation SHA: **PENDING — primary to fill after scoped commit**.
  CI run URL/status and verified head SHA: **PENDING — primary to fill**.
  PR URL: **PENDING — primary to fill**. Independent review / Leader merge decision:
  **PENDING**. No final-SHA CI or merge success is claimed.
- Documentation finalization edits only this kickoff, the BR-01 memo and project
  status. Unrelated `docs/development/windows-local-runtime.md`, runtime helpers,
  secrets and the user's unstaged overlay change are outside this documentation edit.
