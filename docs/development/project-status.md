# Quizopia 2.0 — Project Status

> **Purpose:** living project checkpoint for humans and AI agents.
>
> **Last updated:** 2026-10-30 — W4 engineering closure, FB-015 final integration,
> BR-01 policy resolved. Earlier branch/PR status sections below are chronological
> evidence, **not** today's active-state register.
>
> **BR-01 status correction (2026-10-30):** BR-01 is **RESOLVED as of 2026-10-29 — a
> policy-only decision with no owner-exclusion or Teacher-Preview implementation**
> (any currently authorized STUDENT may take an eligible PUBLIC Assessment including
> their own; CLASS requires actual Student membership). See the
> [BR-01 decision memo](../decisions/teacher-self-attempt-br01-decision-memo.md) and
> issue #126. Any older "BR-01 PENDING" statement below is a historical checkpoint,
> superseded by this note. Production NO-GO is unchanged by this resolution.

## Current verified project checkpoint — 2026-10-30

**Task2 completion increment (2026-10-31):** the PO's academic-demo acceptance
([6539532464](https://github.com/hhtuann/Quizopia_2.0/issues/134#issuecomment-6539532464))
authorized closing B1/B2/B4/C1–C4 within an isolated loopback synthetic tier.
Branch `feat/w4f-t2-completion` (stacked on CI-fix PR #142) adds the
`academic-demo` profile (fictional-account registry, disposable keyring,
separate custodian), trigger-coupled server audit facts with recoverable
projection, default-OFF explicit student observation opt-in with a memory-only
bounded queue, PO-selected 30/90/180-day retention with reviewed holds and
admission-ordered cleanup, the Teacher submitted-review screen, and a real
multi-service Chromium demo harness (`AcademicDemoTopologyTest` + `w4f-demo.yml`).
Verification: Assessment 563/0, Gateway 185 (1 environment port-exhaustion error,
isolated rerun 7/7), frontend 994/994 + 168/168 Playwright, real synthetic demo
journey PASS. Real-learner production remains NO-GO; #134 stays OPEN at 16/23
pending independent Leader verification; T3 remains blocked. Evidence:
[w4f-t2 delivery record](w4f-t2-delivery.md) §8.

**W4F-T2 authorization:** T1 PR #137 is merged at
`4c7803733eee53997c7c0cbce99af1136c86014a`; all three post-merge workflows,
including actual repository full-validation, passed. [Leader authorization](https://github.com/hhtuann/Quizopia_2.0/issues/134#issuecomment-6519641252)
permits Task2 only on `feat/w4f-t2-assessment-backend`. See the
[Task2 implementation/evidence and blocked-scope record](w4f-t2-delivery.md).
T1 is an approved implementation design baseline, not activation approval;
Task2 PR/security implementation review and all privacy/retention/release gates
remain pending. T3/T4 are not authorized.

**Historical program opening (superseded by the Task2 checkpoint above):** Wave 4 final product acceptance was reopened by issues
[#133](https://github.com/hhtuann/Quizopia_2.0/issues/133)–#136. Only T1 foundation/
configuration and documentation-only contract freeze is authorized, on baseline
`bdf5d116bb7258ced8471a3e4c24b1a3de21f4a2`. Historical engineering closure below
is preserved; it is not final product acceptance. See [T1 delivery/evidence](w4f-t1-delivery.md)
and [contract/approval matrix](../specifications/w4f-assessment-completion-contract.md).
T2 → T3 → T4 remain sequentially gated by independent Leader review/merge and
explicit authorization. Audit retention and final privacy/legal sign-off are
PENDING; real collection/production activation remain NOT AUTHORIZED.

- **Integration branch:** `develop`, verified at `cac7002e597643fd62239f5504df43a49570db54` (merge [PR #125](https://github.com/hhtuann/Quizopia_2.0/pull/125)). Do `git fetch` again before any new implementation; this SHA is a dated checkpoint.
- **Wave 1:** CLOSED; **Wave 2:** CLOSED; **Wave 3:** engineering/integration delivered, inherited production release gates remain open in [#97](https://github.com/hhtuann/Quizopia_2.0/issues/97).
- **Wave 4:** **CLOSED FOR ENGINEERING DELIVERY** — W4 realtime B1–B4/E + subsequent UX, BR-01, FB-012 and FB-015 completion PRs merged. [W4 final closure and evidence](w4-final-engineering-closure.md). A code merge is not production authorization.
- **FB-015:** ADR-018 + implementation PRs #121–#125 merged. Feature flags remain default OFF. `PRIMARY_PURGED + COPY_EXPIRY_PENDING` is not verified destruction of all copies. Real-provider erasure and backup/legal/operations gates are tracked in [#129](https://github.com/hhtuann/Quizopia_2.0/issues/129).
- **BR-01:** RESOLVED as policy-only (PRs #127/#128); current STUDENT can take their own eligible PUBLIC Publication with existing locator/admission checks, CLASS requires actual Student membership; no owner-exclusion or mandatory Teacher Preview.
- **Post-merge develop push CI:** [Frontend](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40997003301) **SUCCESS**; [Backend](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40997003210) **SUCCESS (8/8 jobs)** at exact `cac7002e...` SHA; [Repository full validation](https://github.com/hhtuann/Quizopia_2.0/actions/runs/40997003143) **IN PROGRESS** at this checkpoint. Do not infer its result until complete.
- **Production:** **NO-GO**, no flag activation or non-disposable data erasure authorized. [W4 release/operations gates #129](https://github.com/hhtuann/Quizopia_2.0/issues/129) and [W3 release gate register](w3-production-release-gates.md) remain independent.
- **Wave 5:** W5-A Design & Contract Freeze may begin in the separate Leader session. **Five** areas are now approved for **planning only**: Community, Practice, AI Authoring, AI Tutor and **W5-P AI Proctoring**. W5-P1 (design/privacy/contract freeze) is IN; W5-P2 detector/inference implementation is NOT authorized before explicit Product Owner, qualified Privacy/Security and contract approvals. Recording and screen sharing remain separately deferred. See the [2026-10-30 W5-P scope amendment and handoff](w5-ai-proctoring-scope-handoff.md) and [issue #131](https://github.com/hhtuann/Quizopia_2.0/issues/131).

The historical checkpoints below are retained for audit/provenance, not a new claim that merged PRs are still open. The next Wave 5 session should prioritize this current checkpoint and then verify actual GitHub state.

## Historical UI/UX Round 2 + amendment checkpoint — superseded by current closure

`feature/frontend-attempt-workspace-polish` (baseline merged PR #113
`0a64a12`) delivers UX-07 (focused Student Attempt workspace: dedicated
sticky header with LanguageMenu, vertical question list with stable
question-number identity, desktop sticky/mobile collapsible navigator,
unchanged submit/abandon/autosave/realtime/proctoring semantics), UX-08 (all
standalone language dropdowns removed; shared `LanguageMenu` popover on
landing/auth/editor + account menu in the app shell; `language-switcher.tsx`
deleted), UX-09 (NUMERIC_FILL `Đáp án:` autocomplete through the existing
grammar engine — empty-fragment offer enabled, no parser change), plus the
earlier amendment UX-10 (rendered-geometry dropdown chevron fix with
regression) and UX-11 (**BLOCKED FOR LEADER CONTRACT APPROVAL**; package at
`docs/decisions/ux11-student-names-architecture-package.md`). Verification:
850 Vitest / 142 Playwright (2 documented parallel-load flakes pass in
isolation) / typecheck / lint / format / build green, and **18/18 real-runtime
assertions** through the new attempt workspace (fresh OPEN publication, real
entry/start/autosave/locale-switch/submit/100.00% grading). Production
**NO-GO** unchanged; BR-01 resolved 2026-10-29 as policy-only (see the
top-of-file status correction). Full record:
[ux-round2-verification.md](ux-round2-verification.md). PR **#114** open.

## Historical UX feedback round 1 checkpoint — superseded by current closure

`feature/frontend-ux-feedback-round1` (baseline: merged T4 PR #112,
`9d5ce4d`) implements five approved user-feedback corrections:
language selection moved into the authenticated user menu (public pages keep
the standalone switcher), hero-card headers for My Assessments and the
Notifications inbox, select-padding fixes, a results-first unified
Assessment detail with embedded student results and keyboard-navigable
directory rows, and human-friendly Vietnam-timezone date/time formatting
across surfaces. UX-05 (student display names in Teacher Results) was
investigated and is **PAUSED for Leader architecture approval** of a new
least-privilege Identity internal batch-profile contract — proposed in the
record below; Teacher Results continue showing authoritative identifiers and
no names are fabricated. Verification: 829 Vitest / 133 Playwright /
typecheck / lint / format / build green, plus 14/14 real-runtime spot
assertions. Production **NO-GO** unchanged; BR-01 resolved 2026-10-29 as
policy-only (see the top-of-file status correction).
Full record: [ux-feedback-round1.md](ux-feedback-round1.md).

> **Checkpoint baseline:** merged `develop` `2d837e5f853cc2c929d8433e62b2da3f07a60cd2`
> (PR #107, W4-E integration & acceptance). W4 implementation and local-test
> acceptance are complete; production rollout is **HOLD / NOT AUTHORIZED**.
>
> This file answers: **where are we, what is done, what is next, and what is blocking us?**
>
> It does **not** replace product specifications, architecture documents, ADRs, or
> `docs/open-questions.md`. When this file conflicts with an authoritative
> accepted specification/ADR, the authoritative document wins and this status file
> must be updated.

## T4 branch checkpoint — Leader-corrected, awaiting re-review, not merged

`feature/frontend-i18n-final-ux-acceptance` (based on the reviewed T3 merge
PR #111, `f5a2196`) adds the bilingual vi-default/en presentation layer:
typed English-key catalogs (1069 keys across 5 domain catalogs), the
`quizopia-locale` preference cookie, server-resolved initial locale, `Intl`
formatting, the language switcher, and localized shells with
state-preservation guarantees (no `key={locale}`, no refetch on locale
change). Committed to **PR #112** (open, **not merged**); independent Leader
review returned three blocking findings, all resolved by the correction
commit: a complete real-stack user journey (Teacher login → publish
QuizVersion → create/OPEN PUBLIC assessment → Student join/answers/autosave/
submit → authoritative 100.00% grading → Student result in both locales →
Teacher results, **18/18 assertions**), a classified measurement-based visual
review (initial 6 minor touch-target findings corrected in code; re-audit
56/0), and documentation accuracy. Full scope, verified counts and evidence:
[T4 i18n verification record](t4-i18n-verification.md); developer reference:
[`frontend/lib/i18n/README.md`](../../frontend/lib/i18n/README.md).

Verification state: Vitest 804/804 (77 files), typecheck, lint, format and
production build clean after correction; Playwright 130 intercepted tests
(109 inherited + 2 state-preservation + 3 acceptance + 16 visual evidence)
passing locally and in CI on the implementation head (one pre-existing
teacher-route parallel-load flake documented since T2/T3 passes in isolation
and CI). Real-stack evidence totals **82 assertions**: the 64 J1–J9
assertions (byte-identical authored Markdown across locale switches,
mid-form retention, mobile no-overflow) plus the 18/18 full journey.
Production **NO-GO unchanged**; BR-01 was **PENDING at T4 time** and was
resolved 2026-10-29 as policy-only — T4 makes no
self-attempt policy change. Untracked `scripts/local/windows_*`
local-runtime files are not T4.

## T3 branch checkpoint — reviewed and merged as PR #111 (`f5a2196`)

> Update 2026-10-26 (T4 session): T3 was subsequently merged as PR #111
> (`f5a2196`), which is the base of the T4 branch above. The record below is
> retained as written at T3 handoff.

`feature/teacher-assessment-classroom-management`, based on reviewed T2 merge
PR #110 (`9d16d0ed20fffb0eca6ef4a05b9b3cd820866519`), implements owner-scoped
My Assessments and owned Classroom selection (FE-01/FE-02). **Not merged**;
independent source/security review and implementation CI passed; Leader review
and merge approval remain pending. Actual contracts,
V8/V2 migrations, LD-05 boundaries and evidence are in the
[T3 delivery record](teacher-assessment-t3-kickoff.md#t3-branch-delivery-record--2026-10-26).

Recorded local checks: Assessment 421 and Classroom 133 passed; Gateway 181 tests
with no failures/errors and one opt-in W4 browser skip, plus 36 targeted Gateway tests. Isolated
frontend: 779 unit / 109 intercepted E2E all green, with typecheck/lint/format/build.
The user's unrelated 2500→1500 ms overlay edit remains untouched and unstaged;
main-worktree baseline timer failures are not attributed to T3. Real-service T3
evidence: 63 successful assertions and eight screenshots; initial direct
Teacher-results timeout retained, normal menu/directory Results recheck passed.
Positive CLOSED live rows and >20-row UI paging were not exercised live.

[BR-01 memo](../decisions/teacher-self-attempt-br01-decision-memo.md): resolved
2026-10-29 as policy-only (no self-attempt enforcement change, no
Preview/Test implementation). Inherited release **NO-GO / production activation
NOT AUTHORIZED** remains. Implementation SHA:
`8d39379b8b2091c01879a8981195ce980c407cf9`, [PR #111](https://github.com/hhtuann/Quizopia_2.0/pull/111).
Frontend, all eight backend matrix jobs and compose-config passed on that SHA;
full-validation skipped by workflow configuration. Exact run links are recorded
in the T3 delivery record; the PR head includes the documentation-only handoff.

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

### Wave 4 — Realtime Implementation and Acceptance

**Status: CLOSED — ENGINEERING DELIVERY / PR INTEGRATION.** Realtime W4-B1–B4/E
and the later UX/FB-012/FB-015 completion workstreams are merged. Disposable
local-browser acceptance is documented; production is **NO-GO / NOT AUTHORIZED**.
See the authoritative [W4 engineering-closure record](w4-final-engineering-closure.md)
and separate [release-gate issue #129](https://github.com/hhtuann/Quizopia_2.0/issues/129).

All five Wave 4 milestones are merged:

| Milestone | PR   | Merge SHA                                  | Scope                                                                                                                     |
| --------- | ---- | ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------- |
| W4-B1     | #103 | `2466e20466ab39ca07cd9d47a043b796badb11c0` | Gateway Realtime Authentication (ticket, WS AUTH, Origin, admission)                                                      |
| W4-B2     | #104 | `2676ee80d18fc648e3139725f65d8962cfd05efd` | Assessment Subscription Authority (dedicated SERVICE client, frozen private authorization API)                            |
| W4-B3     | #105 | `d87a3b37b12d125d0586bd38a565ae0192337dca` | Outbox / RabbitMQ / Gateway Invalidation (V7 outbox, confirmed publisher, per-instance consumer, fanout)                  |
| W4-B4     | #106 | `cad2d25c73232653e65f037b5ebf71d076b2b42d` | Frontend Realtime & REST Resynchronization (typed WS client, bound ticket/JWT pair, coalesced resync, draft preservation) |
| W4-E      | #107 | `2d837e5f853cc2c929d8433e62b2da3f07a60cd2` | Integration & Acceptance (15/15 real-runtime journeys PASS; evidence + harness)                                           |

W4-E final head: `92929e127260d93ce6d25ab2fe01d59af3dc7830`.

#### Acceptance evidence summary

Full evidence: [W4 Realtime Acceptance Evidence](w4-acceptance-evidence.md).

- **15/15 local/test acceptance journeys PASS** (14 real-runtime protocol + 1
  real-Chromium browser journey).
- Real Chromium Playwright: **2/2 browser tests passed** (teacher publication
  WebSocket AUTH → real invalidation → authoritative REST resync; student attempt
  draft preservation across visibility-triggered and terminal resyncs).
- Real services: Identity, Classroom, Assessment (real OS processes, Maven-built
  JARs), Redis, RabbitMQ, PostgreSQL ×3 — plus **two real Gateway instances**
  (one in-JVM, one separate OS process, each with its own invalidation queue).
- Ticket/AUTH handshake secrecy, dedicated-client subscription authorization,
  event propagation through committed business transitions (outbox → broker →
  consumer → authorized WebSocket), authoritative REST reconciliation, broker
  outage/recovery, resource isolation, broker ACL separation, and credential
  redaction in all process logs — all demonstrated on real infrastructure.
- All applicable CI checks passed on PR #107; the `full-validation` workflow was
  SKIPPED by workflow configuration.

This is implementation acceptance on disposable local/test infrastructure.
**Production acceptance has NOT been performed and production deployment is NOT
authorized.**

#### Remaining production rollout gates

- Production TLS and certificate/hostname verification (all edges: gateway,
  inter-service, broker).
- Proxy/ingress WebSocket behavior under real network topology.
- Performance, load and scaling evidence for WebSocket fan-out, broker
  throughput and outbox drain.
- RabbitMQ production provisioning (vhost/exchange/queues/ACL) and operational
  monitoring.
- Outbox lag/retry/quarantine alerting.
- Assessment Prometheus exposure decision (currently security-gated behind
  authenticated business routes).
- Independent security review and release approval.

All production feature flags remain OFF unless separately approved.

### Wave 5 — Community, Practice, AI & AI Proctoring

**Status: PLANNING / DESIGN & CONTRACT FREEZE; CODING NOT AUTHORIZED.**

**Product Owner W5 scope amendment (2026-10-30):** AI Proctoring is the fifth Wave 5 planning area, tracked as **W5-P**. Its W5-P1 privacy/security/contract design is part of W5-A; implementation W5-P2 requires separate approval. See [W5-P Leader handoff](w5-ai-proctoring-scope-handoff.md) and [#131](https://github.com/hhtuann/Quizopia_2.0/issues/131). This does not expand the Wave 3 monitored CLASS eligibility, permit a new Assessment AI monitoring choice, or authorize real-learner inference.

Approved broad scope (see product documents and `docs/open-questions.md`):

- **Community:** contributions, discovery, comments, reactions, ratings,
  copy/fork and moderation.
- **Practice:** self-paced sessions, feedback and learning progress.
- **AI Authoring:** document-grounded assistance, structured generation,
  editable canonical Quiz Markdown.
- **AI Tutor:** policy-controlled learning assistance.
- **AI Proctoring (W5-P):** proposed AI-generated suspicious-event signals for human teacher review, subject to a separate privacy/security/contract freeze; no automated cheating verdicts, scoring or sanctions.

Proposed sequence (for planning only, not yet approved engineering contracts):

W5-A Product Decisions & Contract Freeze **including W5-P1 AI Proctoring design** → W5-B Community → W5-C Practice →
W5-D AI Authoring → W5-E AI Tutor → W5-F Integration & Acceptance.
**W5-P2 AI Proctoring implementation** is a separately gated parallel workstream and joins W5-F only if approved and ready; it must not silently block independent W5-B/C/D/E work.

Unresolved decisions are recorded in `docs/open-questions.md` under
Community (COMM-01..07), Practice (PRACTICE-01..04) and AI/Proctoring sections, including W5-P1 design gates; none of the detector/retention/privacy contracts is decided by this scope amendment.

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

The current merged baseline is PR #107 (W4-E) at
`2d837e5f853cc2c929d8433e62b2da3f07a60cd2`. The preceding W3 implementation
checkpoint was PR #101 at `77ede6171bb6279580f5db403eeb0e68853d862c`. Merge does
not confer production approval. The W4 acceptance evidence is in
[the W4 acceptance report](w4-acceptance-evidence.md); the W3 bounded evidence
remains in [the final evidence report](w3-acceptance-final-evidence.md). The
following records the earlier documentation checkpoint at
`87ff6b064b972400cd4903f7bc4fec9375e559a1` (2026-10-11), not current
implementation status:

PR #72 adds Assessment policy/contract documentation; PR #73 (`d699bd3`) adds
W3-A scope reconciliation. The latest checkpoint also includes the frontend
segmented-OTP E2E fix (`87ff6b0`). PR #71 (`8361946`) Wave 2 closure is preserved.
The LD-01..05 follow-up is recorded in this later documentation workstream;
none of those decisions is attributed to PR #72/#73. Product approval expands
contracts/planning, not implemented capabilities. No new product CI or
real-topology E2E run is claimed by this reconciliation.

Important merged checkpoints:

| PR   | Merge commit | Result                                                                    |
| ---- | ------------ | ------------------------------------------------------------------------- |
| #49  | `91b68ea`    | Wave 1A Identity/Auth core                                                |
| #51  | `eb0112f`    | USER/SERVICE access-token principal contract                              |
| #52  | `c5fb0bf`    | Classroom core                                                            |
| #53  | `6a3f15f`    | Frontend auth/application-shell foundation                                |
| #54  | `d697e40`    | Quiz Library/Draft backend core                                           |
| #55  | `de7e164`    | Identity Auth HTTP orchestration                                          |
| #56  | `7aeb079`    | Gateway auth/browser transport hardening                                  |
| #57  | `0f2e480`    | Frontend Identity integration + real auth E2E                             |
| #58  | `5a1083d`    | Project status + MVP delivery plan                                        |
| #59  | `06f1af7`    | Accepted Quiz Markdown contract                                           |
| #60  | `58fd7c2`    | Quiz Markdown parser + immutable publishing                               |
| #61  | `c6c81fc`    | Teacher Quiz Library cursor listing                                       |
| #62  | `f6664fb`    | Identity login-test expiry rollover hardening                             |
| #63  | `4d21b97`    | Persistent teacher Quiz Library frontend                                  |
| #64  | `7919aba`    | Focused Quiz authoring UX                                                 |
| #65  | `8a4a7cb`    | Branding/editor polish                                                    |
| #66  | `848ecf6`    | Identity teacher self-enablement backend                                  |
| #67  | `d39b76b`    | Frontend teacher self-enablement                                          |
| #68  | `c977d78`    | Teacher-owned immutable QuizVersion history/detail backend                |
| #69  | `3684ecb`    | Published QuizVersion history/preview frontend                            |
| #70  | `00265c3`    | Frontend navigation/authoring polish                                      |
| #71  | `8361946`    | FE-05–FE-09 editor UX, math, navigation, registration OTP; Wave 2 closure |
| #72  | `0290c6f`    | Wave 3 Assessment policy/contract documentation foundation                |
| #103 | `2466e20`    | W4-B1 Gateway Realtime Authentication                                     |
| #104 | `2676ee8`    | W4-B2 Assessment Subscription Authority                                   |
| #105 | `d87a3b3`    | W4-B3 Outbox / RabbitMQ / Gateway Invalidation                            |
| #106 | `cad2d25`    | W4-B4 Frontend Realtime & REST Resynchronization                          |
| #107 | `2d837e5`    | W4-E Integration & Acceptance (15/15 PASS)                                |

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

Community, Practice, AI Authoring, AI Tutor and **AI Proctoring W5-P** are **Wave 5 planning scope**
(see the W5-P scope amendment above); none is authorized for implementation merely by being scheduled.

The following remain **separately gated or deferred** and are **not authorized
Wave 5 implementation work**:

- Recording and Screen Sharing: **DEFERRED**
- AI Proctoring **implementation W5-P2** remains **BLOCKED pending W5-P1 design, policy, Privacy/Security and Leader contract approval**; **W5-P1 planning is now included in Wave 5**.
- Media/audio recording, automatic snapshot storage, mandatory/strict screen sharing, facial identification and remote device control remain **DEFERRED / NOT AUTHORIZED**; preserve future Proctoring extension points.

## 10. Macro roadmap

This is long-term coordination guidance and may be updated as implementation
reality changes. It is not a replacement for product specifications or the MVP
cut defined in `docs/development/mvp-plan.md`.

- **Wave 1 — Foundation:** CLOSED
- **Wave 2 — Auth + Quiz Authoring Core:** CLOSED at PR #71
- **Wave 3 — Assessment + eligible CLASS monitoring:** IMPLEMENTATION HANDED OFF; bounded real-runtime Assessment/B3 evidence delivered in PR #101, while production acceptance remains tracked as BLOCKED/NOT VERIFIED in issue #97
- **Broader minimal Classroom product integration:** MVP-C creation/invites/discovery/gradebook; W3-A/B mandatory eligibility is earlier
- **MVP hardening / release gate:** after the required Wave 3.x MVP journeys pass
- **Wave 4 — Realtime and completion workstreams:** **CLOSED — ENGINEERING DELIVERY** (W4 PRs #103–#107; UX/FB-012/FB-015 follow-up PRs through #125). Production gates **OPEN / NO-GO** in [W4 release readiness #129](https://github.com/hhtuann/Quizopia_2.0/issues/129).
- **Wave 5 — Community + Practice + AI Authoring + AI Tutor + AI Proctoring W5-P:** DESIGN & CONTRACT FREEZE (W5-P1 included); CODING NOT AUTHORIZED without subsequent approvals. [W5-P handoff](w5-ai-proctoring-scope-handoff.md).
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
