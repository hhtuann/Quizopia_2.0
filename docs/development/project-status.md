# Quizopia 2.0 — Project Status

> **Purpose:** living project checkpoint for humans and AI agents.
>
> **Last updated:** 2026-10-09
>
> **Checkpoint baseline:** `develop @ 836194688316360a28e7c6a4335033890a4805f6` (PR #71)
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

**Wave 2 delivery is complete. Wave 3 — Assessment Core is the next current
wave.**

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

### Wave 3 — Assessment Core

**Status: CURRENT — PRODUCT POLICY APPROVED; CONTRACT DOCUMENTATION FIRST.**

[Accepted Assessment Core policy](../specifications/assessment-core-policy.md)
records ASSESS-01 through ASSESS-04 and GRADE-01 through GRADE-06. Publication
opening atomically pins a self-contained snapshot and policy version 1; owner
score requires successful submit and completed grading, while answer/explanation
review requires Publication closure and an eligible submitted owner.

Assessment remains a scaffold: no Publication/Attempt/grading business
implementation or contracts are frozen. Next: freeze W3-A Publication API/data,
audience/access, and authenticated QuizVersion handoff contracts, then implement
Publication backend/frontend. W3-B attempt count, autosave revisions, submission
cutoff, and closure handling remain undecided. Student numeric input and exact
score representation/display also require accepted contracts before dependent
implementation. Product policy approval is not implementation/CI/E2E evidence.

The policy specification now includes the A–J source audit, normative grading
examples, exact existing-route/model inventory, and Dev1/Dev2 W3-A handoff. Its
endpoint inventory is **PROPOSED**. Closure affects W3-A close as well as W3-B;
timing scope and conditional ID-05 revocation outage handling also need resolution
before dependent work. Engineering contract gaps are distinct from Leader
product/security decisions; non-binding recommendations are in open questions.

## 3. Current accepted `develop` checkpoint

Current accepted checkpoint:

`develop = 836194688316360a28e7c6a4335033890a4805f6`

This checkpoint includes the earlier Quiz/Identity foundation and PR #71
(FE-05–FE-09 editor UX, math, navigation, and registration OTP; the merge came
from the frontend corporate trust redesign branch). Wave 2 closure here is the
Leader's milestone decision. Local and freshly fetched `origin/develop` match;
this documentation task does not claim a new CI or real-topology E2E run.

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

Frontend work in `feature/frontend-quiz-library` implements the persistent
teacher Quiz Library plus grammar-aware editor completion and real
create/read/update/publish integration. Remaining product gaps:

- import/export;
- Assessment publication/delivery lifecycle.

### 4.3 Classroom

**Core foundation merged**

Current foundation includes:

- teacher-owned classrooms;
- memberships;
- pending email invitation model;
- invitation claim readiness;
- no fake Identity users;
- local Classroom database ownership.

Broader Classroom product flows remain later work.

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
- unresolved Redis outage policy;
- production SMTP vendor selection.

Quiz/import:

- Excel import template;
- DOCX import template;
- offline exam DOCX format;
- AI generation.

Assessment Core is now the current Wave 3 scope, governed by the accepted policy
and contract gates in section 2. Guest assessment identity/access remains outside
that approval and must not be inferred from the broader baseline.

Later product areas:

- realtime/proctoring;
- Community;
- Practice/flashcards;
- AI tutor/generation.

## 10. Macro roadmap

This is long-term coordination guidance and may be updated as implementation
reality changes. It is not a replacement for product specifications or the MVP
cut defined in `docs/development/mvp-plan.md`.

- **Wave 1 — Foundation:** CLOSED
- **Wave 2 — Auth + Quiz Authoring Core:** CLOSED at PR #71
- **Wave 3 — Assessment Core:** CURRENT; Publication/delivery, Attempt, autosave, submit, grading, results, and visibility under accepted policy
- **Minimal Classroom product integration:** separate MVP-C workstream; not implicitly included in the Wave 3 policy
- **MVP hardening / release gate:** after the required Wave 3.x MVP journeys pass
- **Wave 4 — Realtime + Proctoring:** post-MVP unless explicitly promoted
- **Wave 5 — Community + Practice + AI:** post-MVP unless explicitly promoted
- **Wave 6 — broader product hardening / release evolution**

The first MVP does **not** require completion of Waves 4–5. The authoritative
MVP delivery path, scope tiers, blockers, and release journeys are maintained in
`docs/development/mvp-plan.md`.

Before dependent Wave 3 implementation, freeze the relevant W3-A/W3-B and
grading/result contracts. ASSESS-01 through ASSESS-04 and GRADE-01 through
GRADE-06 product policy is resolved; remaining contract/Attempt-policy gates are
tracked separately in `docs/open-questions.md`.

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
