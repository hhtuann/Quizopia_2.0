# Quizopia 2.0 — Project Status

> **Purpose:** living project checkpoint for humans and AI agents.
>
> **Last updated:** 2026-09-30
>
> **Checkpoint baseline:** `develop @ 0f2e480e71860afeaa4f9be3a04dd53b8a8c1a67`
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
4. relevant `docs/product/**`
5. relevant `docs/architecture/**`
6. relevant `docs/specifications/**`
7. relevant accepted ADRs under `docs/decisions/**`
8. `docs/open-questions.md`
9. root `DESIGN.md` for frontend/UI work

Then:

1. fetch/inspect the latest `origin/develop`;
2. compare it with the checkpoint SHA recorded above;
3. report any drift before planning;
4. inspect the actual implementation/tests for the workstream;
5. do not reconstruct project state from chat memory alone.

Suggested new-chat instruction:

> Read `AGENTS.md`, `CLAUDE.md`, and
> `docs/development/project-status.md`, then inspect the latest
> `origin/develop`. Tell me the current project state, any drift from the
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

**Status: CURRENT**

Current phase:

**Identity local-auth milestone is CLOSED. Quiz Authoring is next.**

Wave 2 already has:

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
- teacher-owned Quiz create/read/update backend foundation.

Wave 2 still needs:

- final accepted Quiz Markdown grammar decisions;
- Markdown parser/validator;
- immutable `QuizVersion` publishing boundary;
- frontend Quiz Library/Draft authoring integration;
- final teacher authoring E2E;
- Wave 2 final independent review and merge verification.

## 3. Current accepted `develop` checkpoint

At the time of this update:

`develop = 0f2e480e71860afeaa4f9be3a04dd53b8a8c1a67`

This is the merge commit for PR #57.

Important merged checkpoints:

| PR | Merge commit | Result |
| --- | --- | --- |
| #49 | `91b68ea` | Wave 1A Identity/Auth core |
| #51 | `eb0112f` | USER/SERVICE access-token principal contract |
| #52 | `c5fb0bf` | Classroom core |
| #53 | `6a3f15f` | Frontend auth/application-shell foundation |
| #54 | `d697e40` | Quiz Library/Draft backend core |
| #55 | `de7e164` | Identity Auth HTTP orchestration |
| #56 | `7aeb079` | Gateway auth/browser transport hardening |
| #57 | `0f2e480` | Frontend Identity integration + real auth E2E |

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
- PostgreSQL/Flyway persistence;
- ownership enforcement;
- USER/SERVICE JWT separation;
- explicit `TEACHER` authorization.

Not yet implemented:

- Markdown grammar/parser;
- validation into final structured question representation;
- immutable `QuizVersion`;
- publish operation;
- frontend Quiz Library/editor workflow;
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

## 5. Next workstreams

### Dev 1 — Quiz Markdown + immutable version publishing

Planned branch:

`feature/quiz-markdown-publishing`

Mission after required Leader decisions are recorded:

- define implementation against the accepted Markdown grammar;
- parse and validate current opaque draft authoring source;
- produce the accepted structured Quiz representation;
- create immutable `QuizVersion` persistence;
- publish a current draft into an immutable version;
- preserve ownership/security/service boundaries;
- add Flyway migrations and comprehensive tests;
- do not invent Assessment Publication lifecycle semantics.

**Current state: BLOCKED ON PRODUCT DECISIONS in section 6.**

### Dev 2 — Frontend Quiz Library / Draft authoring

Planned branch:

`feature/frontend-quiz-library`

Mission:

- consume the already-merged Quiz create/read/update draft API through Gateway;
- build the teacher Quiz Library/Draft authoring workflow using `DESIGN.md`;
- use real authenticated session infrastructure from PR #57;
- preserve `TEACHER` backend authorization as authoritative;
- handle loading/empty/error/unauthorized states;
- keep authoring source opaque until the accepted parser/publish contract lands;
- integrate parser/publish UI only after the backend contract is real and merged;
- add unit/component/Playwright coverage.

Dev 2 does not need to wait for Dev 1 to begin library/draft UI work, but must
not invent Markdown or publish APIs.

## 6. Decisions required before Dev 1 Quiz parser work

The following existing open questions must be resolved explicitly before
implementing the final Quiz Markdown parser/publisher:

- **QM-01:** final explicit question-type syntax;
- **QM-02:** final `NUMERIC_FILL` Markdown answer syntax;
- **QM-03:** final allowed four-character `NUMERIC_FILL` character set and normalization;
- **QM-05:** whether canonical Markdown preserves teacher formatting exactly or normalizes it.

Also remain open and must not be silently invented when encountered:

- **QM-04:** LaTeX/code/images/explanations/rich-content support;
- **QM-06:** structured-question → canonical-Markdown AI/import strategy.

The source of truth for unresolved items is `docs/open-questions.md`.

## 7. Important known gap: teacher self-enablement

The accepted product rules say:

- every verified account receives `STUDENT`;
- a verified user may additionally enable `TEACHER`;
- no academic-admin approval/institution verification is required for that product baseline.

Identity owns teacher-role enablement.

The completed local-auth milestone does **not** currently establish a complete
end-user teacher self-enablement HTTP/UI journey in this checkpoint.

`docs/open-questions.md` still contains **ID-06** for exact anti-abuse/audit/
rate-limit policy.

Therefore:

- do not pretend a new STUDENT user can already self-enable TEACHER through the
  current browser product unless the actual implementation has advanced beyond
  this checkpoint;
- teacher-authoring tests may use accepted controlled test setup/fixtures where
  appropriate;
- before claiming a full self-service user → teacher → authoring product journey,
  inspect and close the teacher-enablement gap under an explicitly bounded
  Identity workstream/decision.

This does not reopen the completed **local authentication** milestone
(register/verify/login/refresh/logout/me); it records a separate accepted product
capability that remains outstanding.

## 8. Wave 2 Definition of Done

Wave 2 can close when all of the following are true:

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
- frontend teacher can create/read/update a draft through the real topology;
- frontend can use the real publish contract once available;
- final Quiz authoring E2E passes;
- no unresolved Blocker/High finding remains.

**Current status: IN PROGRESS.**

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

Assessment:

- Publication lifecycle/status model;
- assessment delivery snapshots;
- attempt/autosave/submit;
- per-question grading policy;
- score/review visibility policies;
- guest assessment identity/access.

Later product areas:

- realtime/proctoring;
- Community;
- Practice/flashcards;
- AI tutor/generation.

## 10. Macro roadmap

This is coordination guidance and may be updated as implementation reality
changes. It is not a replacement for product specifications.

- **Wave 1 — Foundation:** CLOSED
- **Wave 2 — Auth + Quiz Authoring Core:** CURRENT
- **Wave 3 — Assessment Core:** Publication/delivery, Attempt, autosave, submit, grading, results, Classroom UI integration
- **Wave 4 — Realtime + Proctoring**
- **Wave 5 — Community + Practice + AI**
- **Wave 6 — Product hardening / release**

Before deep Wave 3 implementation, unresolved Assessment/Publication and grading
policy items in `docs/open-questions.md` must be resolved.

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

## 12. Status-file maintenance rule

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
