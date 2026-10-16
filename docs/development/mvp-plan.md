# Quizopia 2.0 — MVP Plan

> **Status:** MVP delivery baseline v0.1
>
> **Accepted for planning:** 2026-09-30
>
> **Wave 3 policy reconciliation:** 2026-10-09; Wave 2 CLOSED at PR #71 per
> Leader; PR #72 preserves the [Assessment Core policy](../specifications/assessment-core-policy.md).
> The [W3-A Leader decision](../specifications/w3-a-publication-scheduling-monitoring-policy.md)
> adds CLASS/PUBLIC, bounded scheduling/duration and optional eligible CLASS
> monitoring. W3-A/B Classroom eligibility and W3-M1/M2 are required Wave 3 outcomes;
> broader Classroom creation/invites/gradebook remain MVP-C.
> LD-01..05 follow-up policy recorded 2026-10-11 after PR #72/#73 accepts timing
> boundaries/deadline/duration, explicit monitoring choice, authenticated link-only
> PUBLIC access, reject-CLOSE while active and Wave 3 revocation outage/recovery.
> Remaining engineering and W3-B/C/M1/M2 gates are preserved.
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
6. the assessment supports both CLASS and PUBLIC under approved access contracts;
7. an eligible learner can start an attempt, answer questions, autosave, and
   submit exactly once;
8. the server grades and persists an authoritative result;
9. the learner can view the result according to the MVP review policy;
10. the teacher can see results for the assessment/classroom context;
11. eligible CLASS Publications can enable disclosed Activity Evidence and LiveKit
    camera monitoring, with authorized Teacher access and release gates satisfied.

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

The LD-01 duration and LD-04 ordinary-CLOSE bullets below preserve the historical
W3-A policy. [ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md) explicitly
amends them with duration 0/unlimited and narrowly notified delayed closure;
accepted current code/contracts implement those amendments. Unlimited monitoring
remains prohibited. This historical list does not undo the amendments.

Minimum delivery capability:

- create/configure an assessment from an immutable `QuizVersion`;
- support `CLASS` (exactly one Classroom) and `PUBLIC`; LD-03 requires authenticated
  USER with authoritative STUDENT and Identity account/token-validity/revocation
  rules, including TEACHER + STUDENT through STUDENT. PUBLIC uses a shared direct
  link with a suitably non-enumerable locator and no membership; implemented locator/
  routes/errors are specified by the Assessment service OpenAPI. Link possession
  bypasses no eligibility;
- LD-01 new starts require OPEN, eligible Student and `startAt <= serverNow < endAt`;
  duration is integer 5–240 minutes inclusive with one-minute granularity and
  deadline = authoritative start + duration; use timezone-safe instants and
  unambiguous local conversion, without retroactive deadline shortening;
- LD-04 reject CLOSE while any Attempt is authoritatively active, with a documented
  conflict response; freeze overdue handling and transactional start/finalization
  races. Only terminal Attempts permit closure; no automatic closure or force-close;
- keep `DRAFT → OPEN → CLOSED`, start-window eligibility and individual deadlines
  distinct; server-enforce scheduling without relying solely on background jobs;
- stable question/option ordering per attempt;
- self-contained attempt/delivery snapshot so active attempts do not depend on
  mutable Quiz Service content or Quiz Service availability;
- server-authoritative time;
- authoritative Classroom Teacher assignment authorization and Student membership
  in W3-A/B, including shared URLs; PUBLIC follows its approved access contract.

The MVP does not require full long-term Publication configurability if a smaller
accepted policy model can satisfy the journeys above without contradicting
business rules.

Current state: **BACKEND MERGED — FRONTEND/EXTERNAL ACCEPTANCE SEPARATE**.
W3-A/B/C Publication/delivery, private Classroom authority, PUBLIC locator/errors,
server timing/configuration, closure concurrency and current admission are implemented.
Use the [implemented Assessment OpenAPI](../../services/assessment-service/contracts/publications.openapi.yaml)
and [integrated evidence](../architecture/w3-final-assessment-acceptance.md), retaining
external release gates and actual frontend acceptance. No owner self-attempt rule changes.

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

Current state: **BACKEND MERGED** through W3-B1/B2/B3. START/RESUME/RETAKE,
revision-safe SAVE, idempotent coherent finalization and protected results are
implemented; independent frontend/runtime-release acceptance remains separate.

#### Grading and results

- accepted scoring rule for every MVP question type;
- deterministic server-side grading;
- authoritative score/result persistence;
- learner result view;
- answer/correct-answer visibility follows an accepted MVP review policy;
- teacher can read assessment/classroom results relevant to owned content.

Current state: **BACKEND MERGED**. W3-C1/B2/B3 implement deterministic grading,
GRADE-07 numeric syntax, GRADE-08 exact representation/policy identity and protected
result/review visibility. This does not certify every frontend journey or production release.

#### Wave 3 CLASS eligibility and monitoring

- Classroom owns Teacher authorization/member truth; Assessment owns Attempt
  validity, deadlines, answers, submission and grades; Proctoring owns durable
  evidence, sessions and LiveKit room/token orchestration;
- optionally enable the disclosed Activity Evidence + LiveKit camera bundle only
  for eligible CLASS, authenticated members, bounded timing and active Attempts;
- explicit CLASS Teacher choice before OPEN, no implicit ON/OFF for unconfigured
  DRAFT and immutable choice upon OPEN; PUBLIC monitoring must be disabled;
- reject PUBLIC/unbounded/practice proctoring and additional Activity-only,
  AI-enabled or A/B modes; actual Assessment representation is
  `UNCONFIGURED`, `ENABLED`, `DISABLED`; UI terminology remains a separate UX concern;
- W3-M1 evidence API/timeline and Teacher dashboard; W3-M2 real camera capture,
  authorized Teacher grid, peer-view isolation, scoped tokens and session ending;
- truthful permissions/refusal/device-loss/disconnect/reconnect status, visible
  capture and REST reconciliation; monitoring failure cannot corrupt persistence,
  change grades or alter authoritative deadlines; required privileged checks fail closed;
- satisfy [canonical privacy/accessibility/security release gates](../specifications/w3-a-publication-scheduling-monitoring-policy.md#11-w3-a10--privacy-accessibility-and-security)
  before real-learner enablement; refusal/loss/accommodation outcomes remain OPEN.

Current state: **IN PROGRESS — DEFAULT DISABLED; ACCEPTANCE NOT READY**.
M1 core is under Draft PR #89 review; dependent M2 SDK/grant/recovery mechanics are
being verified. Authoritative answer-event integration, Teacher session discovery,
actual private topology, provider-enforced revocation/hard-outage limits, privacy/
refusal decisions and real-browser camera grid remain incomplete. See the
[M2 checkpoint](../architecture/w3m2-backend-implementation.md).

#### Broader minimal Classroom integration (MVP-C)

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
- monitoring disclosure, camera/session status, Teacher evidence/video views;
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

- additional public-link controls under a separately accepted contract;
- classroom join-by-code/link beyond the minimum supported membership path;
- richer teacher result summaries;
- basic announcements if low-cost after classroom UI exists;
- basic Quiz folders if the final authoring UI benefits materially;
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

#### Future public access variants

- anonymous guest participation and anonymous-session identity;
- assessment password protection;
- searchable public directory.

LD-03 requires authenticated link-only PUBLIC participation for Wave 3. These
broader options are deferred and need a separate accepted scope/contract before
implementation; they are not current Wave 3 gates.

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

#### Realtime and future Proctoring

- WebSocket product realtime beyond required W3-M1/M2 and correctness needs;
- AI behavior monitoring/inference, snapshots, risk scoring and AI alerts;
- media/audio recording;
- mandatory/strict screen sharing, remote device control, facial identification.

W3-M1 Activity Evidence and W3-M2 LiveKit camera monitoring are MUST outcomes.

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
W3-A CLASS/PUBLIC PUBLICATION / SNAPSHOT / SCHEDULE / CLASS AUTHORITY
        │
        ▼
W3-B ATTEMPT CORE / CLASS MEMBERSHIP
start → autosave → resume → submit
        │
        ▼
W3-C GRADING + RESULTS
        │
        ▼
W3-M1 ACTIVITY EVIDENCE + W3-M2 LIVEKIT CAMERA MONITORING
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

### MVP-A — Wave 2: Quiz Authoring (CLOSED at PR #71)

#### A1. Product decisions: Quiz Markdown

**COMPLETE:** QM-01..05 and grammar-aware editor UX accepted 2026-09-30.
Explicit typed headers, four-character ASCII numeric answer tokens, multiline
Markdown/fenced code, optional explanations and source-preserving saves remain
unchanged. See [Quiz Markdown](../specifications/quiz-markdown-spec.md),
[authoring](../product/quiz-authoring.md) and [ADR-013](../decisions/ADR-013-numeric-fill-format.md).
QM-06 future AI/import canonical rendering remains OPEN without blocking authoring.

#### A2/A3. Backend publishing and frontend authoring — COMPLETE

PR #60 delivered parser/validator, structured representation, immutable versions,
publishing, Flyway and ownership/security tests. PR #63 and subsequent authoring
work delivered the teacher Library/editor and real Gateway API integration.
Historical evidence is retained in project status; no new verification is claimed.

#### A4. Teacher self-enablement closure

**COMPLETE; ID-06 resolved:** PR #66 backend and merged frontend let an eligible
verified STUDENT add TEACHER with authoritative role/audit enforcement. The UI
calls `POST /api/auth/teacher-enablement` → 204 → existing refresh coordinator →
replacement JWT → authoritative `/me` exposing `STUDENT` + `TEACHER`.
Previously recorded real-topology E2E proves verified user → enable TEACHER →
Quiz create/edit/save/reload/publish. Wave 2 closure remains PR #71.

### MVP-B — Wave 3: Assessment Core

Wave 2 is CLOSED at PR #71. The
[Assessment Core policy](../specifications/assessment-core-policy.md) fixes grading
and visibility; the [W3-A decision](../specifications/w3-a-publication-scheduling-monitoring-policy.md)
adds audience, scheduling and monitoring scope. Approval is not contract freeze
or implementation/CI/E2E evidence.

#### B1. Publication/delivery decisions

Accepted ASSESS-01 through ASSESS-04:

- Assessment-owned Publication lifecycle `DRAFT → OPEN → CLOSED`, without
  return to draft, source-version changes after opening, or reopening;
- atomic delivery-snapshot finalization on opening, shared by all Attempts;
- authenticated owner score after successful submission and completed grading;
- correct answers/explanations only after closure for eligible submitted owners.

W3-A additionally requires CLASS/PUBLIC, bounded NEW START windows and positive
bounded duration. LD-01 accepts inclusive start/exclusive end, integer 5–240-minute
duration and authoritative start-plus-duration deadlines. Window end neither
shortens an already-started Attempt nor closes Publication/finalizes Attempts.
LD-04 rejects CLOSE while any Attempt is authoritatively active; only all-terminal
Attempts permit Teacher closure. Durable CLOSED alone enables eligible
submitted-owner review. Opening and participation start remain distinct.

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
- CLASS/PUBLIC and authoritative Classroom Teacher assignment checks in W3-A;
- bounded schedule/duration configuration with timezone-safe storage/display and
  chronological validation; apply accepted LD-01 boundaries/limits/deadline formula
  and no retroactive shortening, while freezing exact wire/validation details;
- QuizVersion handoff without cross-service DB access;
- authenticated QuizVersion handoff under the approved scoped service contract;
- durable ownership/reference model;
- teacher Publication create/open/close frontend through Gateway.

#### B4. W3-B — Freeze Attempt contracts, then implement backend/frontend

Resolve attempt count/resumption, autosave revision/conflicts, expiration/cutoff,
timeout terminalization, saved/unsaved answer treatment and response ordering
before dependent implementation. Apply accepted LD-01 deadline formula and LD-04
reject-CLOSE policy; freeze overdue/start/finalization transactional races. Freeze
Student input validation and retry/concurrency/transaction semantics.

Deliver:

- start/resume;
- authoritative CLASS membership checks, including shared-URL access;
- server-authoritative deadlines under the accepted cutoff contract;
- stable question/option order; do not infer a randomization contract;
- answer mutation;
- autosave sequence protection;
- idempotent submit;
- transactional submission/grading/result persistence;
- Student Attempt/autosave/submit frontend through Gateway.

#### B5. W3-C — Grading + result backend (policy unchanged)

Freeze grading/result representation and visibility DTOs, then deliver
deterministic grading and authorized result APIs according to the accepted
policies. Score requires completed grading; answer/explanation review requires
closure and an eligible submitted owner. No pre-review key/explanation leakage.

#### B6. W3-C — Grading/results frontend

Deliver learner and Teacher result/review views against accepted contracts.
Publication UI ships with W3-A; Attempt/autosave/submit and duration/deadline
display ship with W3-B. W3-C scoring and visibility policy remains unchanged.

#### B7. W3-M1/M2 — Required CLASS monitoring outcomes

Separate bounded workstreams deliver Activity Evidence/session API and Teacher
timeline/dashboard (M1), then scoped LiveKit room/tokens, real browser camera
capture, Teacher video grid and failure/reconnect handling (M2). Monitoring is
optional per eligible CLASS Publication; both capabilities are required to close
Wave 3. Apply LD-02 explicit choice/OPEN immutability and freeze persisted
representation/UI terminology, evidence schema/transport/retention,
sanitized answer-change integration, token/revocation and camera refusal/loss
contracts; preserve PROCTOR-01..09 where unresolved. No unrelated-tab URLs,
browsing history, applications, keystrokes, clipboard or answer content collection.
Signals may be incomplete/manipulated and never automatically convict, fail or
sanction. Preserve future AI extension points; AI is not a closure dependency.

Wave 3 exit:

`QuizVersion → CLASS/PUBLIC scheduled Publication → Attempt → submit → result`,
plus eligible CLASS monitoring enabled/disabled, Activity Evidence and LiveKit.

Verify server timing, Classroom authorization, score/review secrecy, real camera
permissions, isolation and failure handling. Run complete real-topology E2E. Close
Wave 3 only when its Definition of Done is met. Each workstream targets
`develop`, matches the approved contract, passes required tests and CI, verifies
security boundaries, has no remaining Blocker/High finding, and reports accurate
real integration evidence. Leader performs final review/merge.

### MVP-C — Minimal Classroom/Product integration

Extend the merged Classroom core. Mandatory Teacher/member eligibility is already
W3-A/B; MVP-C covers the broader creation/invites/discovery/gradebook experience.

Deliver only what the MVP value chain requires:

- usable teacher classroom creation UI/API;
- usable supported student add/invite/claim path;
- assignment referencing an Assessment publication;
- learner assignment discovery/access;
- reuse W3-A/B authoritative Assessment membership enforcement;
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

Assessment A/B/C, GRADE-07/08 and their engineering contracts are implemented and
merged; their old contract-freeze questions are not current coding blockers. Current
MVP-B blockers are:

- real frontend CLASS/PUBLIC/Attempt/results/closure/inbox acceptance;
- M1 authoritative Assessment answer-change adapter and remaining independent review;
- M2 SDK/grant/recovery completion, authorized Teacher session discovery and actual
  multi-service/browser camera-grid integration;
- monitoring retention, camera refusal/loss/accommodations, provider revocation/
  hard-outage enforcement and deployment/privacy/security review (PROCTOR-01/07/11/12);
- actual external release criteria/evidence and independent authorization; see the
  eleven-gate release register. Persisted `UNCONFIGURED`/`ENABLED`/`DISABLED` is
  already implemented; UI terminology does not authorize new monitoring modes.

LD-05 accepts fail-closed behavior for affected protected Assessment/Classroom/
Proctoring operations while required revocation/account authority is unavailable
or untrustworthy; valid unexpired JWTs cannot bypass checks. No automatic Identity
fallback or degraded authenticated lower-risk read policy is approved for initial
Wave 3. Outage alone must not invalidate refresh credentials, silently log out
or cause refresh loops. Prefer sanitized service-unavailable errors where
appropriate under API review. Redis health/connectivity is insufficient to resume:
trustworthy coverage or durable reconstruction is required. Independent public
nonprivileged infrastructure may remain available. Identity/admission implementation
exists; deployed authority reconstruction, failure-model verification and independent
release/security acceptance remain required. No authentication rewrite or fallback
is authorized.
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
- `NOT STARTED`;
- `POST-MVP` — accepted future scope outside the current release.

| Capability                            | Status         | Notes                                                                                                  |
| ------------------------------------- | -------------- | ------------------------------------------------------------------------------------------------------ |
| Scaffold / service isolation          | VERIFIED       | Wave 1                                                                                                 |
| Local account auth                    | VERIFIED       | Browser → Gateway → Identity                                                                           |
| Frontend auth/session                 | VERIFIED       | refresh/bootstrap/logout E2E                                                                           |
| Teacher self-enablement               | VERIFIED       | backend PR #66 + frontend; real user → teacher → publish E2E proven                                    |
| Classroom core                        | MERGED         | assignment/product integration missing                                                                 |
| Quiz stable identity + draft backend  | VERIFIED       | create/read/update foundation                                                                          |
| Quiz frontend library/editor          | MERGED         | PR #63 persistent library, PR #64 authoring UX, PR #65 editor/branding polish                          |
| Quiz Markdown grammar/editor contract | DECIDED        | QM-01 through QM-05 accepted                                                                           |
| Quiz parser/validator                 | VERIFIED       | merged in PR #60                                                                                       |
| Immutable QuizVersion/publish         | VERIFIED       | merged in PR #60                                                                                       |
| Assessment Publication                | BACKEND MERGED | PR #78; actual API, timing and publication persistence; frontend/release acceptance separate           |
| CLASS Teacher/member eligibility      | BACKEND MERGED | Private Classroom authority integrated in W3-A/B; current checks retained                              |
| Delivery snapshot                     | BACKEND MERGED | Atomic immutable Assessment snapshot on OPEN; source availability independent during attempts          |
| Attempt core                          | BACKEND MERGED | W3-B1/B2 persistence and Student lifecycle through PR #82                                              |
| Autosave/stale-write protection       | BACKEND MERGED | Question SAVE, revision conflicts and durable replay; frontend acceptance separate                     |
| Submit/idempotency                    | BACKEND MERGED | Coherent manual/timeout/closure finalization and retry receipts through PR #83                         |
| Grading                               | BACKEND MERGED | W3-C1 deterministic exact grading; integrated local acceptance through PR #84                          |
| Learner result                        | BACKEND MERGED | Protected numeric result/aggregate and post-CLOSED owner review                                        |
| Teacher result                        | BACKEND MERGED | Owned-Publication authorized results; frontend acceptance separate                                     |
| Activity Evidence (W3-M1)             | IN PROGRESS    | Draft #89 core implemented/tested; authoritative answer adapter and independent acceptance pending     |
| LiveKit camera monitoring (W3-M2)     | IN PROGRESS    | Dependent SDK/grant/recovery mechanics; strict provider enforcement/privacy/browser acceptance pending |
| AI behavior monitoring                | POST-MVP       | accepted future design; not a Wave 3 DoD dependency                                                    |
| Classroom assignment integration      | NOT STARTED    | Assessment publication                                                                                 |
| Full MVP E2E                          | NOT STARTED    | all MUST capabilities                                                                                  |
| MVP hardening/release                 | IN PROGRESS    | Assessment acceptance/governance merged; eleven external gates open, PRODUCTION NO-GO                  |

Status synchronization (2026-10-14): BACKEND MERGED means the implemented backend,
not full frontend/MVP or production acceptance. See the
[current M2 checkpoint](../architecture/w3m2-backend-implementation.md) for unmerged
dependencies and evidence limits. No new product scope or completion approval is
introduced. Update this table when a workstream merges or becomes blocked.

## 8. Mandatory MVP E2E journeys

These journeys are release gates.

### Journey 1 — Become a teacher and publish

`register → verify Gmail → login → enable TEACHER → Teaching workspace → create Quiz → edit Markdown → validate → publish immutable QuizVersion`

Must prove:

- real account;
- authoritative role;
- real Gateway path;
- old immutable version cannot be mutated through draft editing.

### Journey 2 — PUBLIC assessment under approved access contract

`eligible learner opens PUBLIC Publication → start → autosave → resume → submit → result`

LD-03 requires authenticated USER with authoritative STUDENT and a shared direct
link with a suitably non-enumerable locator. Freeze exact locator/route/error
contracts before E2E. Verify Identity validity/revocation, TEACHER + STUDENT
participation through STUDENT, no membership requirement and no link bypass.

Must prove:

- stable ordering;
- server-authoritative deadline;
- bounded NEW START window/positive duration, separate lifecycle/deadline checks;
- reject PUBLIC proctoring; window end alone never unlocks correct answers;
- stale autosave protection;
- idempotent submit;
- no answer-key leakage before policy allows;
- attempt survives mutable Quiz changes/outage assumptions according to accepted
  architecture.

Anonymous guest participation, passwords, anonymous-session identity and a
searchable public directory are not required for Wave 3; future scope needs
separate approval.

### Journey 3 — Classroom assessment

`teacher creates class → learner becomes member → teacher assigns assessment → learner opens assignment → attempts/submits → teacher sees result`

Must prove:

- Classroom never reads Assessment DB directly;
- Assessment enforces the accepted class eligibility boundary;
- Teacher assignment authorization and Student membership come from Classroom;
  nonmembers/shared URLs and unauthorized Teachers cannot bypass them (W3-A/B);
- no fake Identity users are created for pending invitations;
- authoritative result comes from Assessment.

### Journey 4 — Session resilience during real product use

During an authenticated teacher/learner product journey:

- access token expires;
- one refresh occurs;
- eligible request retries once;
- session identity cannot cross account boundaries;
- logout prevents restoration;
- LD-05 affected protected operations fail closed during untrustworthy revocation
  authority; healthy but incomplete Redis does not establish recovery, and outage
  alone causes no refresh-credential invalidation, silent logout or refresh loop.

This reuses the verified auth architecture but must remain green in the full MVP
product topology.

### Journey 5 — Eligible CLASS monitoring enabled and disabled

Prove enabled and disabled configurations with real services and LiveKit.
Disabled Proctoring must not activate its activity collection or camera capture.
For enabled monitoring verify:
pre-start disclosure, durable sanitized Activity Evidence and authorized Teacher
timeline/grid, real browser camera permissions and visible capture, peer-view and
unauthorized Teacher isolation, scoped access/revocation and session ending.
Exercise refusal/loss/device rejection, disconnect/reconnect and REST reconciliation
under the frozen contract; never show unavailable cameras as active. Monitoring
failure must preserve answers/submissions/results, grades and authoritative
deadlines. Verify fail-closed required privileged checks and canonical privacy,
accessibility/accommodation, retention/deletion/audit and no-recording gates before
real learners. AI inference, recording and strict screen sharing are not required.

## 9. Non-goals for MVP implementation agents

MVP workstreams must not expand into post-MVP scope simply because supporting
infrastructure already exists.

Examples:

- RabbitMQ existing locally does not require every MVP integration to become
  event-driven if synchronous ownership is the documented correct choice;
- LiveKit Compose presence proves infrastructure only; the W3-A decision requires
  W3-M2 product delivery and real permissions/isolation/failure verification;
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
- relevant automated tests and E2E;
- canonical monitoring privacy/accessibility/security gates and truthful status;
- monitoring failures cannot corrupt persistence, grades or authoritative deadlines.

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

Update this plan for accepted scope/tier/decision changes, resequencing, Wave exits
or changed release criteria. Update `project-status.md` more frequently for current
checkpoints: this plan records the path to MVP; status records where we are now.
