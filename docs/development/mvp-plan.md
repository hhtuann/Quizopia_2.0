# Quizopia 2.0 — MVP Plan

> **Status:** MVP delivery baseline v0.1
>
> **Accepted for planning:** 2026-09-30
>
> **Wave 3 policy reconciliation:** 2026-10-09; Wave 2 CLOSED at PR #71 per
> Leader. [Assessment Core policy](../specifications/assessment-core-policy.md)
> governs Wave 3. Minimal Classroom product integration remains a separate MVP-C
> gate; it is not automatically added to Assessment Core.
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
- teacher self-enablement for an authenticated verified `STUDENT`;
- USER/SERVICE token separation;
- real browser traffic through Gateway.

Current state:

- local authentication/session path: **VERIFIED**
- teacher self-enablement end-user HTTP/UI path: **VERIFIED — backend (PR #66)
  and frontend entry point merged/proven with real-topology E2E**

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
- frontend authoring: **MERGED (PR #63 persistent library, PR #64 authoring UX,
  PR #65 editor/branding polish)**
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

Current state: **NOT STARTED / W3-A CONTRACT FREEZE REQUIRED**. ASSESS-01 through
ASSESS-04 product policy is approved; audience/access, API/data, and handoff
contracts still require explicit documentation before implementation.

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

Current state: **NOT STARTED / CONTRACT DETAILS REQUIRED**. GRADE-01 through
GRADE-06 and review visibility are approved. Student numeric validation, exact
score representation/display, and the policy identifier remain contract gates.

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

**Status: COMPLETE**

Bounded Identity/frontend workstream.

Delivered:

- authenticated verified user who retains the authoritative `STUDENT` role can
  grant their own `TEACHER` capability under the accepted MVP policy;
- authoritative role update;
- correct token/session behavior after role grant;
- browser UI entry point;
- audit/security behavior required by the accepted policy;
- E2E proof that a fresh verified STUDENT can become a TEACHER and enter the
  teacher authoring journey.

Evidence: backend merged as PR #66; frontend entry point (user menu +
authoring boundary) drives `POST /api/auth/teacher-enablement` → 204 →
existing refresh coordinator → replacement JWT → authoritative `/me` with
`STUDENT` + `TEACHER`; the real-topology E2E completes the journey through
Quiz create/edit/save/reload/publish of an immutable `QuizVersion`.

Dependency:

- ID-06 is resolved for MVP.

Wave 2 exit:

`verified user → enable TEACHER → create/edit/validate/publish immutable QuizVersion`

works through the real topology.

### MVP-B — Wave 3: Assessment Core

Wave 2 is CLOSED at PR #71 as confirmed by the Leader. The
[accepted Wave 3 policy](../specifications/assessment-core-policy.md) fixes the
Assessment Core product rules. Product approval does not freeze API/schema
contracts or satisfy implementation/testing gates.

#### B1. Publication/delivery decisions

Accepted ASSESS-01 through ASSESS-04:

- Assessment-owned Publication lifecycle `DRAFT → OPEN → CLOSED`, without
  return to draft, source-version changes after opening, or reopening;
- atomic delivery-snapshot finalization on opening, shared by all Attempts;
- authenticated owner score after successful submission and completed grading;
- correct answers/explanations only after closure for eligible submitted owners.

#### B2. Grading decisions

Accepted GRADE-01 through GRADE-06:

- single-choice exact correctness;
- multiple-choice exact set match without partial credit;
- true/false equal per-statement credit, including valid all-false matrices;
- exact decimal numeric equality without tolerance;
- equal question weights and no negative marking;
- snapshot-pinned grading policy version 1, without automatic regrading.

Student numeric syntax/validation, exact score storage/API/display, and the policy
identifier still require accepted contracts. The four-character published-answer
grammar is preserved; it does not itself constrain Student input length.

#### B3. W3-A — Freeze Publication contracts, then implement backend/frontend

First freeze Publication API/data contracts and the QuizVersion handoff,
including eligibility, source ownership, transition concurrency, atomic snapshot
opening, protected grading data, Gateway/security, and configuration impacts.
Then deliver:

- Assessment-owned publication/delivery configuration;
- immutable/self-contained attempt delivery snapshot;
- the explicitly accepted audience/access checks for W3-A;
- only availability/duration behavior covered by an accepted contract;
- QuizVersion handoff without cross-service DB access;
- service authentication if synchronous Quiz snapshot retrieval is required;
- durable ownership/reference model;
- teacher Publication create/open/close frontend through Gateway.

#### B4. W3-B — Freeze Attempt contracts, then implement backend/frontend

Resolve attempt count, autosave revision/conflicts, submission cutoff, and
in-progress Attempt handling at closure before dependent implementation. Freeze
Student input validation and retry/concurrency/transaction semantics.

Deliver:

- start/resume;
- server-authoritative deadlines under the accepted cutoff contract;
- stable question/option order; do not infer a randomization contract;
- answer mutation;
- autosave sequence protection;
- idempotent submit;
- transactional submission/grading/result persistence;
- Student Attempt/autosave/submit frontend through Gateway.

#### B5. Grading + result backend

Freeze grading/result representation and visibility DTOs, then deliver
deterministic grading and authorized result APIs according to the accepted
policies. Score requires completed grading; answer/explanation review requires
closure and an eligible submitted owner. No pre-review key/explanation leakage.

#### B6. Grading/results frontend and real-topology verification

Deliver learner and Teacher result/review views against accepted contracts.
Publication UI ships with W3-A; Attempt/autosave/submit and any accepted timer
UI ship with W3-B. Verify the combined real-topology journey below.

Wave 3 exit:

`published QuizVersion → assessment → learner attempt → submit → authoritative result`

works end-to-end.

Run complete real-topology E2E after the contract/implementation sequence. Close
Wave 3 only when its Definition of Done is met. Each workstream targets
`develop`, matches the approved contract, passes required tests and CI, verifies
security boundaries, has no remaining Blocker/High finding, and reports accurate
real integration evidence. Leader performs final review/merge.

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

ID-06 is resolved. Teacher self-enablement is complete end to end (backend
PR #66 + frontend + real-topology E2E) and no longer blocks MVP-A.

### Blocks MVP-B

ASSESS-01 through ASSESS-04 and GRADE-01 through GRADE-06 are accepted product
policy and no longer open. The remaining implementation gates are:

- W3-A Publication API/data/internal handoff freeze and audience/access decision
  (ASSESS-14/15);
- W3-A close behavior (ASSESS-13) and any timing configuration (ASSESS-17);
- W3-B attempt count, autosave revision/conflicts, submission cutoff, closure,
  and submit/finalization contract (ASSESS-10 through ASSESS-13, ASSESS-16/17);
- Student numeric input/submission validation (GRADE-07);
- exact score representation/rounding/display and version 1 identifier (GRADE-08).

ID-05 remains a conditional security dependency for revocation outage handling.
The [Assessment Core specification](../specifications/assessment-core-policy.md)
contains the A–J source audit, normative grading examples, proposed W3-A endpoint
inventory, and Dev1/Dev2 handoff. `docs/open-questions.md` distinguishes Leader
product/security choices from engineering contracts, with recommendations that
are explicitly not accepted policy. Routine class/lock/component choices do not
require separate product decisions.

Other open questions block only a workstream that explicitly depends on them;
passwords, guests, and broader Classroom integration are not implicitly required
by this Wave 3 policy.

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

| Capability                            | Status      | Notes                                                                                 |
| ------------------------------------- | ----------- | ------------------------------------------------------------------------------------- |
| Scaffold / service isolation          | VERIFIED    | Wave 1                                                                                |
| Local account auth                    | VERIFIED    | Browser → Gateway → Identity                                                          |
| Frontend auth/session                 | VERIFIED    | refresh/bootstrap/logout E2E                                                          |
| Teacher self-enablement               | VERIFIED    | backend PR #66 + frontend; real user → teacher → publish E2E proven                   |
| Classroom core                        | MERGED      | assignment/product integration missing                                                |
| Quiz stable identity + draft backend  | VERIFIED    | create/read/update foundation                                                         |
| Quiz frontend library/editor          | MERGED      | PR #63 persistent library, PR #64 authoring UX, PR #65 editor/branding polish         |
| Quiz Markdown grammar/editor contract | DECIDED     | QM-01 through QM-05 accepted                                                          |
| Quiz parser/validator                 | VERIFIED    | merged in PR #60                                                                      |
| Immutable QuizVersion/publish         | VERIFIED    | merged in PR #60                                                                      |
| Assessment Publication                | NOT STARTED | product policy accepted; W3-A API/data/access contract freeze required                |
| Delivery snapshot                     | NOT STARTED | atomic opening accepted; internal QuizVersion handoff contract required               |
| Attempt core                          | NOT STARTED | finalized snapshot + W3-B contract/attempt policy gates                               |
| Autosave/stale-write protection       | NOT STARTED | stale-write rejection required; exact revision/conflict contract open                 |
| Submit/idempotency                    | NOT STARTED | idempotency/coherence required; cutoff/closure/concurrency contract open              |
| Grading                               | NOT STARTED | product policy accepted; numeric input + score representation/policy identifier gates |
| Learner result                        | NOT STARTED | grading/result model                                                                  |
| Teacher result                        | NOT STARTED | grading/result model                                                                  |
| Classroom assignment integration      | NOT STARTED | Assessment publication                                                                |
| Full MVP E2E                          | NOT STARTED | all MUST capabilities                                                                 |
| MVP hardening/release                 | NOT STARTED | full MVP E2E first                                                                    |

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
