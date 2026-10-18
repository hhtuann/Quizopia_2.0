# Quizopia 2.0 — Project Status

> **Purpose:** living project checkpoint for humans and AI agents.
>
> **Last updated:** 2026-10-18
>
> **Checkpoint baseline:** merged `develop` `77ede6171bb6279580f5db403eeb0e68853d862c`
> (PR #101); the bounded runtime used checkpoint
> `01e975d2c7639570986906e4cd2b2937a382198b`.
>
> This file answers: **where are we, what is done, what is next, and what is blocking us?**
>
> It does **not** replace product specifications, architecture documents, ADRs, or
> `docs/open-questions.md`. When this file conflicts with an authoritative
> accepted specification/ADR, the authoritative document wins and this status file
> must be updated.

## 1. Resume protocol

A new ChatGPT/Codex/Claude session should read, in this order:

1. `AGENTS.md`
2. `CLAUDE.md`
3. this file: `docs/development/project-status.md`
4. `docs/development/mvp-plan.md`
5. relevant `docs/product/**`
6. relevant `docs/architecture/**`
7. relevant `docs/specifications/**`
8. relevant accepted ADRs under `docs/decisions/**`
9. `docs/open-questions.md`
10. root `DESIGN.md` for frontend/UI work

Then:

1. fetch/inspect the latest `origin/develop`;
2. compare it with the checkpoint SHA recorded above;
3. report any drift before planning;
4. inspect the actual implementation/tests for the workstream;
5. do not reconstruct project state from chat memory alone.

Suggested new-chat instruction:

> Read `AGENTS.md`, `CLAUDE.md`, `docs/development/project-status.md`, and
> `docs/development/mvp-plan.md`, then inspect the latest `origin/develop`.
> Tell me the current project state, progress toward MVP, any drift from the
> checkpoint, active blockers, and the next workstreams before proposing code.

## 2. Current milestone

### Wave 1 — Foundation

**Status: CLOSED**

Completed foundation includes:

- monorepo/scaffold and hardening;
- independent Spring Boot services and Gateway;
- service-owned PostgreSQL databases and Flyway baseline;
- local Redis/RabbitMQ/MinIO/Mailpit/LiveKit infrastructure;
- CI/test/format/build foundations;
- Identity/Auth core;
- stable USER/SERVICE JWT principal contract;
- Classroom core;
- frontend auth/application-shell foundation;
- root `DESIGN.md`.

### Wave 2 — Auth + Quiz Authoring Core

**Status: CLOSED at PR #71, as confirmed by Leader.**

Current phase:

**Wave 2 delivery is complete. Wave 3 — Assessment Core and optional eligible
CLASS monitoring is current.**

Wave 2 has:

- real local registration;
- Gmail OTP verification;
- login;
- refresh rotation;
- logout;
- `/api/auth/me`;
- Gateway browser auth path;
- frontend auth integration;
- real Browser → Gateway → Identity E2E;
- stable Quiz identity;
- mutable current QuizDraft;
- teacher-owned Quiz create/read/update backend foundation;
- teacher-owned cursor-paginated Quiz Library listing API;
- accepted Quiz Markdown parser/validator;
- immutable `QuizVersion` persistence and publish API;
- persistent teacher Quiz Library frontend (PR #63);
- focused grammar-aware Quiz authoring UX (PR #64);
- branding/editor polish (PR #65);
- backend teacher self-enablement (PR #66);
- frontend teacher self-enablement with authoritative refresh + `/me` role
  transition;
- real Browser → Gateway → Identity → Quiz Service user → teacher → Quiz
  save/reload/publish E2E.

Wave 2 remaining: **none for milestone closure**.

### Wave 3 — Assessment Core + optional eligible CLASS monitoring

**Status: IMPLEMENTATION HANDED OFF — ASSESSMENT, M1/M2, FRONTEND INTEGRATION,
AND BOUNDED REAL-RUNTIME ACCEPTANCE MERGED OR DELIVERED; PRODUCTION NO-GO.**

Wave 3 implementation is delivered through PRs #88–#101. PR #101 packages the
final bounded acceptance evidence plus two acceptance-harness corrections: durable
sanitized browser receipts and a reusable-bind preflight fix. The evidence package
commit is `1f8499647f5c4c09c458673b03056d3b57ec00ba`; the executed runtime checkpoint
is `01e975d2c7639570986906e4cd2b2937a382198b`. Six real Gateway/Chromium
Assessment+B3 journeys passed, along with the local frontend, script, and release
register checks recorded in
[the final evidence report](w3-acceptance-final-evidence.md).

This is an implementation handoff, not production acceptance. Issue #97 remains
OPEN. The authoritative production register remains **6 BLOCKED / 5 NOT VERIFIED**;
the genuine ≥48-hour soak/post-cutoff COMPLETE, multi-node recovery, target
operations, LiveKit/provider qualification, and independent privacy/security/
operations/Leader decisions remain outstanding. Assessment, Inbox, and Proctoring
production defaults remain disabled.

The [historical M1 checkpoint](../architecture/w3m-backend-implementation.md) and
[historical M2 checkpoint](../architecture/w3m2-backend-implementation.md) preserve
executed evidence; the [current integration checkpoint](../architecture/w3m-integration-implementation.md) records bounded
source/dependency/resumption and actual verification limits. Assessment
W3-A/B/C and integrated acceptance are merged through PR #84; release governance
through PR #87 preserves eleven external gates (6 BLOCKED / 5 NOT VERIFIED).
[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md) explicitly amends historical
LD-01/LD-04 with unlimited Attempt duration and narrow notified delayed closure;
no monitoring for unlimited Attempts is approved. The following historical policy
chronology does not supersede those amendments or current implemented OpenAPI.

[Accepted Assessment Core policy](../specifications/assessment-core-policy.md)
records ASSESS-01 through ASSESS-04 and GRADE-01 through GRADE-06. Publication
opening atomically pins a self-contained snapshot and policy version 1; owner
score requires successful submit and completed grading, while answer/explanation
review requires Publication closure and an eligible submitted owner.

The subsequent [W3-A Leader decision](../specifications/w3-a-publication-scheduling-monitoring-policy.md)
requires CLASS and PUBLIC, bounded participation windows for NEW Attempt starts
and positive bounded duration. Publication lifecycle, start-window eligibility
and individual deadlines remain distinct: opening is not participation start,
and window end is neither closure nor all active Attempts finishing or review
authorization. CLASS references exactly one Classroom; authoritative Teacher
assignment authorization and Student membership are required in W3-A/B, including
shared URLs. Broader Classroom creation/invites/gradebook may remain MVP-C.

Eligible CLASS Publications may enable the disclosed Activity Evidence + LiveKit
camera bundle. W3-M1 and W3-M2 are required Wave 3 outcomes. PUBLIC, unbounded and
practice Proctoring are prohibited. LD-02 requires explicit CLASS Teacher choice
before OPEN, with no implicit ON/OFF default for unconfigured DRAFT; choice is
immutable upon OPEN and PUBLIC monitoring must be disabled. No additional
Activity-only, AI-enabled or A/B modes are approved.
Proctoring owns evidence/session/room/token orchestration; Assessment owns Attempt
validity, deadlines, answers and grades; Classroom owns class authority/membership.

Assessment is implemented; its Publication/Attempt/grading/closure/inbox OpenAPI
and runtime are authoritative. Proctoring M1 Activity Evidence, M2 LiveKit core,
Assessment integration, and frontend monitoring integration are merged through
PRs #89–#96. Local adapter/full verification and bounded real-service verification
have passed under the
[bounded M contract](../specifications/w3-m-proctoring-contract.md). Provider
revocation/hard-outage qualification, authorized real browser/provider execution,
and external privacy/security/operations acceptance remain separate production
gates. No Assessment A/B/C business reimplementation is needed.

The [release operator pack](w3-production-release-approval-operator-readiness.md)
keeps the selected Assessment nomination separate from new monitoring commits.
Retention, refusal/loss/accommodations, deployed LiveKit/privacy review and actual
independent approvals remain open; no synthetic/local test closes those gates.

Historical contract chronology (2026-10-11):

The Leader-approved LD-01..05 follow-up is recorded on 2026-10-11, after PR #72/#73:
PUBLIC requires authenticated USER with authoritative STUDENT and Identity
account/token-validity/revocation rules, including TEACHER + STUDENT through
STUDENT. Shared direct links use suitably non-enumerable locators; no membership,
directory, guests, passwords or anonymous-session identity is required. Link
possession bypasses no eligibility rule; no new owner self-attempt rule is added.
LD-01 accepts `startAt <= serverNow < endAt`, integer 5–240-minute duration in
one-minute steps and deadline = authoritative start + duration, without window-end
or retroactive configuration shortening. Instants must be timezone-safe and local
conversion unambiguous. LD-04 rejects CLOSE while any Attempt is authoritatively
active; close conflict/overdue handling/start-finalization races require
transactional contracts, with W3-B eventual terminalization independent of timely
jobs. No automatic closure or force-close is introduced. Exact wire/concurrency/
representation contracts remain OPEN, along with W3-B cutoff, expiration, timeout
finalization, saved/unsaved answer treatment, resumption and response ordering.
Count/autosave/submit, GRADE-07/08 and unresolved PROCTOR contracts remain gates
before dependent implementation. AI behavior monitoring, recording
and mandatory/strict screen sharing are deferred; functioning AI is not Wave 3 DoD.

Monitoring acceptance requires pre-start disclosure, real browser camera permission
and visible capture, truthful refusal/device/permission-loss/reconnect states,
authorized Teacher-only viewing and Student peer-subscription isolation, scoped
access/revocation and ending sessions with eligible Attempts. Monitoring failures
must not corrupt answers/submissions/results, change grades or alter authoritative
deadlines. Browser signals do not prove misconduct or automatically fail/sanction.
REST remains authoritative; LiveKit/DataChannels never persist authoritative answers.
Real-learner enablement requires the [canonical privacy/accessibility/security gates](../specifications/w3-a-publication-scheduling-monitoring-policy.md#11-w3-a10--privacy-accessibility-and-security),
including no recording; camera refusal/loss outcomes remain OPEN.

LD-05 accepts fail-closed behavior for affected protected Assessment/Classroom/
Proctoring operations until required revocation/account authority is trustworthy;
valid JWTs alone cannot bypass checks. No automatic Identity fallback or degraded
authenticated lower-risk reads are approved for initial Wave 3. Outage alone must
not invalidate refresh credentials, silently log out or cause refresh loops.
Prefer sanitized service-unavailable errors where appropriate, subject to API
review. Redis health alone is insufficient for recovery: trustworthy coverage or
durable reconstruction is required. Independent public nonprivileged infrastructure
may remain available. ID-05 implementation/recovery/security verification remain
OPEN under the bounded Dev1 contract gate; no auth subsystem rewrite is authorized.
The Assessment policy's A–J audit/examples and W3-A endpoint inventory are contract
inputs; proposed endpoints are not frozen APIs. Product approval and this docs-only
reconciliation are not new implementation, CI or E2E evidence.

## 3. Current merged checkpoint and historical chronology

The current merged baseline is PR #101 at
`77ede6171bb6279580f5db403eeb0e68853d862c`; it carries the final bounded evidence
package and status correction. Merge does not confer production approval.
Consult the [final evidence report](w3-acceptance-final-evidence.md) for exact
source/test receipts and remaining gates. The following records the earlier
documentation checkpoint at `87ff6b064b972400cd4903f7bc4fec9375e559a1`
(2026-10-11), not current implementation status:

PR #72 adds Assessment policy/contract documentation; PR #73 (`d699bd3`) adds
W3-A scope reconciliation. The latest checkpoint also includes the frontend
segmented-OTP E2E fix (`87ff6b0`). PR #71 (`8361946`) Wave 2 closure is preserved.
The LD-01..05 follow-up is recorded in this later documentation workstream;
none of those decisions is attributed to PR #72/#73. Product approval expands
contracts/planning, not implemented capabilities. No new product CI or
real-topology E2E run is claimed by this reconciliation.

Important merged checkpoints:

| PR  | Merge commit | Result                                                                    |
| --- | ------------ | ------------------------------------------------------------------------- |
| #49 | `91b68ea`    | Wave 1A Identity/Auth core                                                |
| #51 | `eb0112f`    | USER/SERVICE access-token principal contract                              |
| #52 | `c5fb0bf`    | Classroom core                                                            |
| #53 | `6a3f15f`    | Frontend auth/application-shell foundation                                |
| #54 | `d697e40`    | Quiz Library/Draft backend core                                           |
| #55 | `de7e164`    | Identity Auth HTTP orchestration                                          |
| #56 | `7aeb079`    | Gateway auth/browser transport hardening                                  |
| #57 | `0f2e480`    | Frontend Identity integration + real auth E2E                             |
| #58 | `5a1083d`    | Project status + MVP delivery plan                                        |
| #59 | `06f1af7`    | Accepted Quiz Markdown contract                                           |
| #60 | `58fd7c2`    | Quiz Markdown parser + immutable publishing                               |
| #61 | `c6c81fc`    | Teacher Quiz Library cursor listing                                       |
| #62 | `f6664fb`    | Identity login-test expiry rollover hardening                             |
| #63 | `4d21b97`    | Persistent teacher Quiz Library frontend                                  |
| #64 | `7919aba`    | Focused Quiz authoring UX                                                 |
| #65 | `8a4a7cb`    | Branding/editor polish                                                    |
| #66 | `848ecf6`    | Identity teacher self-enablement backend                                  |
| #67 | `d39b76b`    | Frontend teacher self-enablement                                          |
| #68 | `c977d78`    | Teacher-owned immutable QuizVersion history/detail backend                |
| #69 | `3684ecb`    | Published QuizVersion history/preview frontend                            |
| #70 | `00265c3`    | Frontend navigation/authoring polish                                      |
| #71 | `8361946`    | FE-05–FE-09 editor UX, math, navigation, registration OTP; Wave 2 closure |
| #72 | `0290c6f`    | Wave 3 Assessment policy/contract documentation foundation                |

PR #73 (`d699bd3`) is the subsequent merged W3-A documentation checkpoint;
LD-01..05 is a later approval record, not historical PR #73 content.

The SHA in this file is a checkpoint, not permission to skip `git fetch`.
Always inspect the current remote branch before starting work.

## 4. Working vertical slices

### 4.1 Local account authentication

**Working end-to-end**

Verified topology:

`Browser :3000 → Gateway :8080 → Identity :8081 → PostgreSQL / Redis / Mailpit`

Current real flow:

`register → OTP request/delivery → verify → login → /me → refresh/bootstrap → protected-request retry → logout`

Important security properties:

- access token is browser-memory only;
- refresh credential is browser-managed HttpOnly cookie only;
- refresh rotation is server-side;
- refresh family lifetime does not slide indefinitely;
- refresh reuse revokes the affected family;
- refresh/logout preserve trusted-Origin protection;
- `/me` is authoritative for current user identity/roles;
- frontend `LEARNING` / `TEACHING` are workspaces, not backend roles;
- stale frontend requests cannot replay across an account/session-generation change.

### 4.2 Quiz draft backend

**Working backend foundation**

Current Quiz Service supports the accepted teacher-owned foundation:

- stable Quiz identity;
- mutable current QuizDraft;
- create;
- read owned draft;
- update owned draft;
- list the authenticated teacher's quizzes with stable cursor pagination;
- PostgreSQL/Flyway persistence;
- ownership enforcement;
- USER/SERVICE JWT separation;
- explicit `TEACHER` authorization.

Implemented backend capability:

- Quiz Markdown MVP grammar is accepted in repository docs;
- parser/validator and structured question representation;
- immutable `QuizVersion`;
- publish operation.

Frontend work implements the persistent teacher Quiz Library plus grammar-aware
editor completion and real create/read/update/publish integration. Remaining Quiz
product gap:

- import/export.

Frontend Assessment integration is merged through PR #96. Its bounded real
Gateway/Chromium acceptance is recorded in PRs #99–#101.

### 4.3 Classroom

**Core foundation merged**

Current foundation includes:

- teacher-owned classrooms;
- memberships;
- pending email invitation model;
- invitation claim readiness;
- no fake Identity users;
- local Classroom database ownership.

Merged W3-A/B integrates authoritative Teacher assignment authorization and Student
membership through private service contracts. Broader creation/invites/discovery/gradebook UX remains MVP-C.

## 5. Completed Wave 2 workstreams (historical)

### Dev 1 — Quiz Markdown + immutable version publishing

Planned branch:

`feature/quiz-markdown-publishing`

Mission:

- implement against the accepted Markdown grammar;
- parse and validate current opaque draft authoring source;
- produce the accepted structured Quiz representation;
- create immutable `QuizVersion` persistence;
- publish a current draft into an immutable version;
- preserve ownership/security/service boundaries;
- add Flyway migrations and comprehensive tests;
- do not invent Assessment Publication lifecycle semantics.

**Current state: MERGED / VERIFIED — PR #60 added the accepted parser, validator,
immutable QuizVersion persistence, and publish API.**

### Dev 2 — Frontend Quiz Library / Draft authoring

Planned branch:

`feature/frontend-quiz-library`

Mission:

- consume the already-merged Quiz create/read/update draft API through Gateway;
- consume the merged teacher-owned `GET /api/quizzes` cursor listing contract;
- build the teacher Quiz Library/Draft authoring workflow using `DESIGN.md`;
- use real authenticated session infrastructure from PR #57;
- preserve `TEACHER` backend authorization as authoritative;
- handle loading/empty/error/unauthorized states;
- implement the accepted grammar-aware editor completion UX now;
- preserve authoring source exactly on save;
- integrate authoritative validation/publish API UI only after the backend
  parser/publish contract is real and merged;
- add unit/component/Playwright coverage.

Dev 2 does not need to wait for Dev 1 to begin library/draft UI work, but must
not invent Markdown or publish APIs.

**Current state: MERGED.** PR #63 delivered persistent teacher Quiz Library;
subsequent merged work added authoring/editor UX and publish integration. The
Library uses backend-owned state and opaque cursors rather than browser storage
or production fixtures as an authoritative store. These historical workstreams
are not the next Wave 3 assignments; see section 2 and the accepted policy.

## 6. Accepted Quiz Markdown contract

QM-01 through QM-05 were accepted by the Leader on 2026-09-30 and no longer
block Dev 1 or the grammar-aware portion of Dev 2.

Accepted MVP decisions:

- **QM-01:** `Câu <n> [TYPE]:`, four explicit case-sensitive question types,
  multiline question/option/statement content;
- **QM-02:** `Đáp án: <token>` on one line for `NUMERIC_FILL`;
- **QM-03:** exact four-character ASCII numeric token using digits plus
  constrained leading `-` / single `.`, with surrounding-whitespace trim only;
- **QM-04:** plain text, bold, italic, inline code, and backtick fenced code
  blocks; optional multiline `Lời giải:` is accepted; richer media/math/link
  features are deferred;
- **QM-05:** preserve manually authored source; do not canonicalize/re-render it
  on save.

The editor UX contract also accepts grammar-aware completion at structural
line-starts, including direct four-type snippets from `C`/`Câ`/`Câu`,
context-aware A-D / `Đáp án:` / `Lời giải:` suggestions, arrow navigation,
Tab/Enter/click acceptance, Escape dismissal, and no structural suggestions
inside fenced code blocks.

Authoritative details:

- `docs/specifications/quiz-markdown-spec.md`
- `docs/product/quiz-authoring.md`
- `docs/decisions/ADR-013-numeric-fill-format.md`

**QM-06 remains open** for future AI/import canonical rendering and does not
block the current manual-authoring/publishing workstream.

## 7. Completed teacher self-enablement

The accepted product rules say:

- every verified account receives `STUDENT`;
- a verified user may additionally enable `TEACHER`;
- no academic-admin approval/institution verification is required for that product baseline.

Identity owns teacher-role enablement.

Teacher self-enablement backend/frontend is part of the closed Wave 2 milestone.

The Leader accepted **ID-06** for MVP: an ACTIVE, email-verified USER who retains
the authoritative persisted `STUDENT` role may idempotently add TEACHER; the
first grant is durably audited with the role mutation; there is no dedicated MVP
rate limit; and a normal refresh is required for a new JWT role claim.

Therefore:

- the backend `POST /api/auth/teacher-enablement` operation is merged (PR #66)
  and the frontend entry point is merged: an authenticated STUDENT can
  self-enable TEACHER from the
  user menu or the authoring boundary, and the browser then uses the existing
  refresh coordinator so the replacement JWT and the authoritative `/me`
  expose `STUDENT` + `TEACHER` before the Teaching workspace unlocks;
- the previously recorded real-topology E2E proves the complete product journey
  (new verified account → self-enablement → Quiz create/edit/save/reload/
  publish) through Browser → Gateway → Identity → Quiz Service with real
  PostgreSQL/Redis/Mailpit and a durable `role_grant_audit` row — no direct
  database role insertion, mock role, or frontend role override is used;
- teacher-authoring tests may use accepted controlled test setup/fixtures where
  appropriate;

This does not reopen the completed **local authentication** milestone
(register/verify/login/refresh/logout/me); teacher self-enablement is a
separate accepted product capability that is now implemented end to end.

## 8. Wave 2 Definition of Done

Wave 2 is CLOSED by Leader at PR #71. The following are its retained exit
criteria and previously recorded evidence, not new verification in this
documentation change:

### Auth

- real register/verify/login works through Browser → Gateway → Identity;
- access token is memory-only;
- refresh cookie is HttpOnly/browser-managed;
- reload bootstrap works;
- single-flight refresh and bounded retry work;
- logout works;
- no Blocker/High auth finding remains.

**Current status: COMPLETE for the local-auth milestone.**

### Quiz

- stable teacher-owned Quiz + mutable draft foundation exists;
- accepted Markdown grammar is recorded;
- parser/validator exists with accepted error behavior;
- immutable `QuizVersion` exists;
- publishing a draft creates an immutable version;
- frontend branch consumes the real create/read/update/publish contracts through the existing authenticated request architecture;
- frontend branch consumes the real teacher-owned Quiz Library listing contract through the same authenticated request architecture;
- contract-level frontend Playwright covers library listing/open/pagination plus create/edit/save/reload/publish;
- real Browser → Gateway → Quiz Service teacher-authoring E2E is proven: a
  fresh verified STUDENT self-enables TEACHER through the real Identity
  endpoint, the refreshed session exposes the authoritative `STUDENT` +
  `TEACHER` roles, and the same browser creates, edits, saves, reloads, and
  publishes an immutable `QuizVersion` owned by that account;
- no unresolved Blocker/High finding remains.

**Current status: COMPLETE.**

## 9. Explicitly deferred scope

Do not expand the current Quiz workstream into these areas without a separate
accepted plan:

Identity:

- Google browser login/linking HTTP;
- password reset;
- MFA;
- logout-all;
- device/session-management UI;
- broader revocation outage policy outside the accepted initial Wave 3 LD-05 scope;
- production SMTP vendor selection.

Quiz/import:

- Excel import template;
- DOCX import template;
- offline exam DOCX format;
- AI generation.

Assessment plus optional eligible CLASS monitoring is current Wave 3 scope,
governed by section 2 and the W3-A follow-up decisions. LD-03 accepts
authenticated STUDENT direct-link PUBLIC participation; implemented locator/routes/
errors are described in the current Assessment service OpenAPI. Guest identity/access is deferred.

Later product areas:

- AI behavior monitoring/inference, media/audio recording and mandatory/strict
  screen sharing; preserve future Proctoring design and extension points;
- broader product realtime beyond required W3-M1/M2;
- Community;
- Practice/flashcards;
- AI tutor/generation.

## 10. Macro roadmap

This is long-term coordination guidance and may be updated as implementation
reality changes. It is not a replacement for product specifications or the MVP
cut defined in `docs/development/mvp-plan.md`.

- **Wave 1 — Foundation:** CLOSED
- **Wave 2 — Auth + Quiz Authoring Core:** CLOSED at PR #71
- **Wave 3 — Assessment + eligible CLASS monitoring:** IMPLEMENTATION HANDED OFF; bounded real-runtime Assessment/B3 evidence delivered in PR #101, while production acceptance remains tracked as BLOCKED/NOT VERIFIED in issue #97
- **Broader minimal Classroom product integration:** MVP-C creation/invites/discovery/gradebook; W3-A/B mandatory eligibility is earlier
- **MVP hardening / release gate:** after the required Wave 3.x MVP journeys pass
- **Wave 4 — broader Realtime + future Proctoring:** post-MVP; AI behavior monitoring and any separately approved recording/strict screen-sharing evolution, beyond required W3-M1/M2
- **Wave 5 — Community + Practice + AI:** post-MVP unless explicitly promoted
- **Wave 6 — broader product hardening / release evolution**

The first MVP does **not** require completion of Waves 4–5. The authoritative
MVP delivery path, scope tiers, blockers, and release journeys are maintained in
`docs/development/mvp-plan.md`.

Merged Assessment A/B/C and its implemented OpenAPI supersede the historical
contract-open observations above. M1/M2 implementation is merged; provider,
security, privacy, operations, and production release acceptance remain separately
gated. PROCTOR-10 reuses implemented
UNCONFIGURED/ENABLED/DISABLED; unresolved policy and deployment decisions remain
explicit in `docs/open-questions.md`. No monitoring or production approval follows
from these status corrections.

## 11. Current development workflow

Preferred workflow after the successful frontend-auth experiment:

1. one bounded feature/workstream;
2. one **Big Codex Prompt** for the primary implementation agent;
3. Codex performs inspect → plan → implement → test → fix → self-review;
4. Codex may stop only for a genuine public-contract/security/product/boundary blocker;
5. one independent read-only review near completion;
6. fix validated Blocker/High findings;
7. PR targets `develop`;
8. Leader performs final review/merge.

Fallback:

If Big Codex execution shows scope creep, invented contracts, weak verification,
or repeated architecture/security misses, return to:

`GPT coordinator → narrow Codex prompts → per-step review`

Git rules remain:

- feature branches target `develop`;
- no direct push to `develop`/`main`;
- no force push/history rewrite;
- prefer merging latest `develop` into an already-shared feature branch instead
  of rebasing it;
- Leader owns final merge.

## 12. Relationship to the MVP plan

- `project-status.md` answers **where the project is right now**.
- `mvp-plan.md` answers **where the project must go to reach the first MVP**.

Update this status file after material merges/checkpoints. Update the MVP plan
when scope tiers, sequencing, blockers, or MVP completion criteria change.

## 13. Status-file maintenance rule

Update this file after any material checkpoint, especially:

- a major workstream merge;
- a Wave/milestone closure;
- a new active blocker/decision;
- a changed next-workstream assignment;
- a material roadmap change;
- a development-workflow change.

The update should remain concise. Durable product/architecture decisions belong
in product/specification/ADR documents first; this file should link/summarize
their current impact instead of becoming a second competing specification.
