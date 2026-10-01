# Quizopia 2.0 — MVP Plan

> **Status:** MVP delivery baseline v0.1
>
> **Accepted for planning:** 2026-09-30
>
> **Purpose:** define the shortest accepted path from the current repository
> state to a usable Quizopia 2.0 MVP.
>
> This file answers: **what must ship for MVP, what may wait, in what order should
> work happen, and what proves the MVP is complete?**
>
> It is a delivery plan, not a replacement for product specifications,
> architecture docs, ADRs, or `docs/open-questions.md`.

## 1. MVP product cut

The MVP is the smallest end-to-end product that proves Quizopia's core value:

1. a person can create and verify a real account;
2. that person can enable teacher capability;
3. a teacher can create and author a quiz;
4. quiz content can be validated and published as an immutable version;
5. the teacher can configure a scored assessment;
6. the assessment can be shared publicly to authenticated users and/or assigned
   to a classroom;
7. an eligible learner can start an attempt, answer questions, autosave, and
   submit exactly once;
8. the server grades and persists an authoritative result;
9. the learner can view the result according to the MVP review policy;
10. the teacher can see results for the assessment/classroom context.

The MVP therefore proves this value chain:

`account → teacher → author → publish → assign/share → attempt → submit → grade → results`

Anything not required to prove that loop is a candidate for post-MVP scope.

## 2. MVP completion statement

Quizopia 2.0 is **MVP COMPLETE** only when all of the following are true:

- all MUST-HAVE capabilities in section 3 are implemented;
- all required product decisions in section 6 are recorded in authoritative
  docs;
- the full MVP journeys in section 8 pass through the real supported topology;
- service/database/security boundaries remain intact;
- migrations and configuration are production-safe;
- CI/repository validation is green;
- no unresolved Blocker/High finding remains from final review;
- release/run documentation is sufficient for another developer to start,
  verify, and exercise the MVP without relying on chat history.

MVP completion does **not** require completion of every long-term product
feature in the product overview.

## 3. Scope tiers

### 3.1 MUST HAVE for MVP

#### Identity and access

- local Gmail registration;
- OTP verification;
- login;
- `/me`;
- rotating HttpOnly refresh session;
- logout;
- memory-only browser access token;
- teacher self-enablement for an authenticated verified user;
- USER/SERVICE token separation;
- real browser traffic through Gateway.

Current state:

- local authentication/session path: **VERIFIED**
- teacher self-enablement end-user HTTP/UI path: **NOT COMPLETE**

#### Quiz authoring

- stable Quiz identity;
- mutable current QuizDraft;
- teacher ownership;
- Quiz Library/Draft frontend;
- accepted Markdown grammar for MVP question types;
- parser + validator;
- structured validated question representation;
- immutable `QuizVersion`;
- publishing a draft creates an immutable version;
- later editing does not mutate older published versions.

Required MVP question types are the already accepted product types:

- `SINGLE_CHOICE`
- `MULTIPLE_CHOICE`
- `TRUE_FALSE_MATRIX`
- `NUMERIC_FILL`

Current state:

- stable Quiz + mutable draft backend: **MERGED / VERIFIED**
- Quiz Markdown MVP grammar/editor contract: **DECIDED**
- frontend authoring: **IN PROGRESS — REAL QUIZ LIBRARY + AUTHORING FLOW IMPLEMENTED ON FEATURE BRANCH; FINAL REVIEW/MERGE PENDING**
- parser/validator/version publishing: **MERGED / VERIFIED**

#### Assessment delivery

MVP requires the `ASSESSMENT` mode.

Minimum delivery capability:

- create/configure an assessment from an immutable `QuizVersion`;
- support an authenticated public-link audience and classroom audience;
- availability/deadline rules required by the accepted MVP policy;
- attempt duration if enabled by the accepted MVP policy;
- stable question/option ordering per attempt;
- self-contained attempt/delivery snapshot so active attempts do not depend on
  mutable Quiz Service content or Quiz Service availability;
- server-authoritative time;
- authorization checks for public/class eligibility.

The MVP does not require full long-term Publication configurability if a smaller
accepted policy model can satisfy the journeys above without contradicting
business rules.

Current state: **NOT STARTED / POLICY DECISIONS REQUIRED**

#### Attempt and answer persistence

- start attempt;
- resume active attempt;
- stable question/option order;
- answer mutation API;
- autosave;
- stale-sequence protection;
- server deadline enforcement;
- idempotent submit;
- submit/grading/result persistence remains transactionally coherent;
- active attempts are independent from mutable Quiz content.

Current state: **NOT STARTED**

#### Grading and results

- accepted scoring rule for every MVP question type;
- deterministic server-side grading;
- authoritative score/result persistence;
- learner result view;
- answer/correct-answer visibility follows an accepted MVP review policy;
- teacher can read assessment/classroom results relevant to owned content.

Current state: **BLOCKED ON GRADING / REVIEW-POLICY DECISIONS**

#### Minimal Classroom integration

MVP Classroom scope is deliberately smaller than the full Classroom product.

Required:

- teacher can create a classroom;
- teacher can add/invite a learner using the accepted membership model;
- verified learner can become a classroom member through the supported MVP
  path;
- teacher can assign an assessment to a classroom;
- classroom member can discover/open the assignment;
- teacher can see the learner's authoritative assessment result in the
  classroom/assignment context.

Existing core membership/invitation foundation should be extended rather than
replaced.

Current state:

- Classroom core: **MERGED**
- assignment/product integration: **NOT STARTED**

#### Frontend MVP experience

Required frontend surfaces:

- authentication;
- teacher enablement;
- Quiz Library;
- Quiz Draft editor;
- validation/publish flow;
- minimal assessment configuration;
- classroom assignment flow;
- learner assessment-taking UI;
- autosave/submission state;
- learner results;
- teacher results;
- loading/empty/error/unauthorized states;
- responsive and keyboard-accessible critical paths.

Current state:

- auth/application shell: **VERIFIED**
- Quiz/Assessment/Classroom product UI: **NOT COMPLETE**

#### MVP release hardening

Before MVP release:

- full real-topology E2E;
- final security/architecture review;
- schema/migration verification from clean databases;
- no committed secrets;
- production-safe required configuration;
- health checks;
- CI green;
- frontend production build;
- backend verify for affected services;
- error/logging audit for credentials, tokens, OTPs, answers, and unnecessary
  PII;
- minimum runbook/setup documentation;
- update `project-status.md` to mark MVP release checkpoint.

### 3.2 SHOULD HAVE if time allows

These improve the MVP but do not block the first MVP release unless later
promoted explicitly:

- guest participation in public assessments;
- assessment password protection;
- additional public-link controls;
- classroom join-by-code/link beyond the minimum supported membership path;
- richer teacher result summaries;
- basic announcements if low-cost after classroom UI exists;
- basic Quiz folders if the final authoring UI benefits materially;
- basic public quiz discovery rather than link-only access;
- small usability improvements around authoring preview/history.

A SHOULD item must not silently delay MVP.

### 3.3 POST-MVP

The following are not required to declare the first MVP complete:

#### Identity

- Google browser login/account-linking HTTP UX;
- password reset;
- MFA;
- logout-all;
- device/session-management UI;
- advanced teacher-enablement anti-abuse controls beyond the accepted MVP
  policy.

#### Quiz authoring/import/export

- Excel import;
- DOCX import;
- offline DOCX exam generation;
- shuffled paper generation;
- AI-assisted quiz generation;
- advanced rich-content support that is not accepted into the MVP Markdown
  grammar.

#### Practice

- dedicated Practice mode;
- flashcards;
- spaced repetition;
- AI tutor.

#### Community

- posts/contributions;
- comments;
- reactions;
- ratings;
- public copy/fork community workflows;
- moderation workflow.

#### Realtime and Proctoring

- WebSocket product realtime beyond what is strictly required for correctness;
- LiveKit/WebRTC proctoring;
- camera monitoring;
- suspicious-event AI;
- full recording;
- strict screen-share workflows.

#### Advanced product/admin

- advanced analytics;
- advanced moderation/admin UI;
- broad notification system;
- complex reporting beyond MVP teacher/learner results.

## 4. Delivery sequence

The MVP dependency path is:

```text
FOUNDATION + LOCAL AUTH                 DONE
        │
        ├── Teacher self-enablement
        │
        ▼
QUIZ AUTHORING
Draft → Markdown validation → QuizVersion
        │
        ▼
ASSESSMENT PUBLICATION / DELIVERY SNAPSHOT
        │
        ▼
ATTEMPT CORE
start → autosave → resume → submit
        │
        ▼
GRADING + RESULTS
        │
        ▼
MINIMAL CLASSROOM ASSIGNMENT INTEGRATION
        │
        ▼
FULL MVP E2E
        │
        ▼
MVP HARDENING / RELEASE GATE
        │
        ▼
MVP COMPLETE
```

Parallel work is encouraged only where public contracts and ownership boundaries
are stable.

## 5. Workstream roadmap from the current checkpoint

### MVP-A — Finish Wave 2: Quiz Authoring

#### A1. Product decisions: Quiz Markdown

**Status: COMPLETE**

The Leader accepted the MVP Quiz Markdown and editor UX contract on 2026-09-30.

Resolved:

- QM-01: explicit `Câu <n> [TYPE]:` question headers;
- QM-02: `Đáp án: <token>` for `NUMERIC_FILL`;
- QM-03: exact four-character ASCII numeric token with constrained `-` / `.`
  and surrounding-whitespace trim only;
- QM-04: multiline Markdown content with plain text, bold, italic, inline code,
  and backtick fenced code blocks; optional multiline `Lời giải:` is supported;
- QM-05: manually authored source is preserved on save rather than
  canonicalized/re-rendered;
- grammar-aware editor completion is accepted, including direct four-type
  question snippets from line-start `C`/`Câ`/`Câu`, context-aware option/
  answer/explanation suggestions, keyboard/mouse selection, and suppression
  inside fenced code blocks.

Authoritative details:

- `docs/specifications/quiz-markdown-spec.md`
- `docs/product/quiz-authoring.md`
- `docs/decisions/ADR-013-numeric-fill-format.md`

QM-06 remains open for future AI/import canonical rendering and does not block
manual MVP authoring/publishing.

#### A2. Backend Quiz Markdown + immutable publishing

Planned branch:

`feature/quiz-markdown-publishing`

Primary owner: Dev 1.

Deliver:

- parser;
- validator;
- structured representation;
- validation errors;
- immutable `QuizVersion`;
- publish use case/API;
- persistence/Flyway;
- ownership/security;
- tests.

Must not create Assessment `Publication` lifecycle semantics.

#### A3. Frontend Quiz Library / Draft authoring

Planned branch:

`feature/frontend-quiz-library`

Primary owner: Dev 2.

Deliver:

- teacher Quiz Library;
- create/open/update draft;
- Markdown authoring/editor experience;
- loading/empty/error states;
- real Gateway/Quiz API integration;
- validation/publish UI only against real accepted backend contracts;
- Playwright coverage.

#### A4. Teacher self-enablement closure

Bounded Identity/frontend workstream.

Deliver:

- authenticated verified user can grant their own `TEACHER` capability under
  the accepted MVP policy;
- authoritative role update;
- correct token/session behavior after role grant;
- browser UI entry point;
- audit/security behavior required by the accepted policy;
- E2E proof that a fresh verified STUDENT can become a TEACHER and enter the
  teacher authoring journey.

Dependency:

- resolve the MVP-relevant part of ID-06 before exposing the public action.

Wave 2 exit:

`verified user → enable TEACHER → create/edit/validate/publish immutable QuizVersion`

works through the real topology.

### MVP-B — Wave 3: Assessment Core

Before implementation, resolve the minimum policy set needed by the MVP.

#### B1. Publication/delivery decisions

At minimum resolve:

- ASSESS-01 persisted Publication/status model;
- ASSESS-02 delivery-snapshot finalization point;
- ASSESS-03 score visibility for MVP;
- ASSESS-04 answer-review visibility for MVP.

Other Assessment open questions remain deferred unless the selected MVP journey
requires them.

#### B2. Grading decisions

Resolve scoring for all four MVP question types:

- GRADE-01;
- GRADE-02;
- GRADE-03 if partial credit is selected;
- GRADE-04;
- GRADE-05;
- GRADE-06 if grading policy must be pinned/versioned with immutable delivery.

#### B3. Publication + delivery snapshot backend

Deliver:

- Assessment-owned publication/delivery configuration;
- immutable/self-contained attempt delivery snapshot;
- public authenticated and classroom audience checks;
- accepted availability/duration policies;
- QuizVersion handoff without cross-service DB access;
- service authentication if synchronous Quiz snapshot retrieval is required;
- durable ownership/reference model.

#### B4. Attempt backend

Deliver:

- start/resume;
- deadline enforcement;
- stable shuffle/order;
- answer mutation;
- autosave sequence protection;
- idempotent submit;
- transactional submission/grading/result persistence.

#### B5. Grading + result backend

Deliver deterministic grading and authorized result APIs according to accepted
MVP policies.

#### B6. Assessment frontend

Deliver:

- teacher minimal assessment configuration;
- learner attempt UI;
- autosave UX;
- timer/state UX where relevant;
- submit/retry-safe UX;
- learner result view;
- teacher result view.

Wave 3 exit:

`published QuizVersion → assessment → learner attempt → submit → authoritative result`

works end-to-end.

### MVP-C — Minimal Classroom/Product integration

Extend, do not rewrite, the merged Classroom core.

Deliver only what the MVP value chain requires:

- usable teacher classroom creation UI/API;
- usable supported student add/invite/claim path;
- assignment referencing an Assessment publication;
- learner assignment discovery/access;
- Assessment membership enforcement;
- teacher result/gradebook read path for assigned MVP assessments.

Resolve only the Classroom open questions that block this minimal journey.

MVP-C exit:

`teacher class → learner member → assign assessment → learner submits → teacher sees result`

works end-to-end.

### MVP-D — Final MVP hardening

No new major features.

Deliver:

- clean-environment database migration verification;
- comprehensive real-topology E2E for section 8;
- final security review;
- authorization matrix review;
- cross-service DB isolation audit;
- token/cookie/PII/answer logging audit;
- public error-envelope audit;
- accessibility pass on critical browser journeys;
- frontend production build;
- backend full affected-service verifies;
- comprehensive CI;
- configuration/secrets review;
- minimum operations/run documentation;
- dependency/vulnerability review appropriate to the repository;
- final `project-status.md` update.

Exit criterion:

**MVP COMPLETE — READY FOR RELEASE**

## 6. Required decisions and blocking map

Agents must not silently invent these decisions.

### Blocks MVP-A

Quiz Markdown QM-01 through QM-05 are resolved and no longer block MVP-A.

Remaining blocker:

- MVP-relevant resolution of ID-06 for teacher self-enablement

### Blocks MVP-B

- ASSESS-01
- ASSESS-02
- ASSESS-03
- ASSESS-04
- GRADE-01
- GRADE-02
- GRADE-03 if applicable
- GRADE-04
- GRADE-05
- GRADE-06 if required by the selected grading model

### Potentially blocks MVP-C

- CLASS-01 only if join-by-code/link is selected as the MVP membership path;
- CLASS-04 only if due/late submission is part of the MVP assignment policy.

Prefer the smallest accepted policy sufficient for the MVP rather than solving
all future variants.

## 7. Status matrix

Status vocabulary:

- `VERIFIED` — implemented, merged, and proven;
- `MERGED` — implemented/merged but not yet proven as part of the final MVP
  journey;
- `DECIDED` — required product/contract semantics are accepted and recorded;
- `IN PROGRESS` — active branch/workstream;
- `BLOCKED` — cannot safely implement without a recorded decision/dependency;
- `NOT STARTED`.

| Capability                            | Status      | Notes                                                                                                        |
| ------------------------------------- | ----------- | ------------------------------------------------------------------------------------------------------------ |
| Scaffold / service isolation          | VERIFIED    | Wave 1                                                                                                       |
| Local account auth                    | VERIFIED    | Browser → Gateway → Identity                                                                                 |
| Frontend auth/session                 | VERIFIED    | refresh/bootstrap/logout E2E                                                                                 |
| Teacher self-enablement               | BLOCKED     | MVP policy / ID-06 + HTTP/UI gap                                                                             |
| Classroom core                        | MERGED      | assignment/product integration missing                                                                       |
| Quiz stable identity + draft backend  | VERIFIED    | create/read/update foundation                                                                                |
| Quiz frontend library/editor          | IN PROGRESS | real persistent library + create/edit/save/publish implemented on feature branch; final review/merge pending |
| Quiz Markdown grammar/editor contract | DECIDED     | QM-01 through QM-05 accepted                                                                                 |
| Quiz parser/validator                 | VERIFIED    | merged in PR #60                                                                                             |
| Immutable QuizVersion/publish         | VERIFIED    | merged in PR #60                                                                                             |
| Assessment Publication                | BLOCKED     | ASSESS decisions                                                                                             |
| Delivery snapshot                     | BLOCKED     | ASSESS decisions + QuizVersion                                                                               |
| Attempt core                          | NOT STARTED | depends on delivery snapshot                                                                                 |
| Autosave/stale-write protection       | NOT STARTED | Attempt core                                                                                                 |
| Submit/idempotency                    | NOT STARTED | Attempt core                                                                                                 |
| Grading                               | BLOCKED     | GRADE decisions                                                                                              |
| Learner result                        | NOT STARTED | grading/result model                                                                                         |
| Teacher result                        | NOT STARTED | grading/result model                                                                                         |
| Classroom assignment integration      | NOT STARTED | Assessment publication                                                                                       |
| Full MVP E2E                          | NOT STARTED | all MUST capabilities                                                                                        |
| MVP hardening/release                 | NOT STARTED | full MVP E2E first                                                                                           |

Update this table whenever a material workstream merges or becomes blocked.

## 8. Mandatory MVP E2E journeys

These journeys are release gates.

### Journey 1 — Become a teacher and publish

`register → verify Gmail → login → enable TEACHER → Teaching workspace → create Quiz → edit Markdown → validate → publish immutable QuizVersion`

Must prove:

- real account;
- authoritative role;
- real Gateway path;
- old immutable version cannot be mutated through draft editing.

### Journey 2 — Public authenticated assessment

`authenticated learner opens public assessment link → start → answer/autosave → reload/resume → submit → result`

Must prove:

- stable ordering;
- server-authoritative deadline;
- stale autosave protection;
- idempotent submit;
- no answer-key leakage before policy allows;
- attempt survives mutable Quiz changes/outage assumptions according to accepted
  architecture.

Guest participation is not required for the first MVP unless promoted from
SHOULD to MUST.

### Journey 3 — Classroom assessment

`teacher creates class → learner becomes member → teacher assigns assessment → learner opens assignment → attempts/submits → teacher sees result`

Must prove:

- Classroom never reads Assessment DB directly;
- Assessment enforces the accepted class eligibility boundary;
- no fake Identity users are created for pending invitations;
- authoritative result comes from Assessment.

### Journey 4 — Session resilience during real product use

During an authenticated teacher/learner product journey:

- access token expires;
- one refresh occurs;
- eligible request retries once;
- session identity cannot cross account boundaries;
- logout prevents restoration.

This reuses the verified auth architecture but must remain green in the full MVP
product topology.

## 9. Non-goals for MVP implementation agents

MVP workstreams must not expand into post-MVP scope simply because supporting
infrastructure already exists.

Examples:

- RabbitMQ existing locally does not require every MVP integration to become
  event-driven if synchronous ownership is the documented correct choice;
- LiveKit existing locally does not make Proctoring an MVP requirement;
- MinIO existing locally does not make DOCX/AI upload workflows an MVP
  requirement;
- Community service scaffold does not make Community an MVP requirement;
- AI service scaffold does not make AI generation/tutoring an MVP requirement.

Use existing infrastructure where the MVP design requires it; do not build
features to justify infrastructure.

## 10. Release-quality constraints

MVP means minimal product scope, not minimal engineering correctness.

The following remain non-negotiable:

- no cross-service database access;
- immutable published quiz content;
- self-contained active attempts;
- authoritative server time;
- stale autosave protection;
- idempotent submit;
- coherent submit/grading/result transaction;
- correct-answer secrecy until policy allows;
- browser access token memory-only;
- opaque rotating HttpOnly refresh credential;
- service-to-service authentication where needed;
- Flyway-owned schemas;
- Hibernate validation;
- no secrets in Git;
- no token/OTP/credential logging;
- accessibility on critical browser paths;
- relevant automated tests and E2E.

## 11. Workstream execution policy

Default implementation mode:

`one bounded feature branch → one Big Codex Prompt → implementation/test/self-review → one independent read-only review → Leader merge`

Return to GPT-coordinator/narrow-prompt mode when:

- product semantics are ambiguous;
- multiple services need a new contract simultaneously;
- Codex begins inventing API/product behavior;
- security/transaction correctness needs tighter staged review;
- a large workstream repeatedly fails verification.

A workstream is not complete merely because code exists. It must satisfy its
explicit exit criteria and relevant real-topology proof.

## 12. Keeping this plan current

Update this file when:

- the Leader changes MVP scope;
- a MUST/SHOULD/POST-MVP item changes tier;
- a blocking product decision is resolved;
- a workstream is added/removed/resequenced;
- a Wave exits;
- final MVP completion criteria change.

Update `docs/development/project-status.md` more frequently for current
checkpoint details.

Rule of thumb:

- `mvp-plan.md` = **where the project must go to reach MVP**
- `project-status.md` = **where the project is right now**
