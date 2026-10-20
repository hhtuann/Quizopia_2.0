# Open Questions

Status: **Active after ADR-015 and EC79-01..04 conditional engineering freeze; runtime/release gates remain — v1.0 (2026-10-14)**

The infrastructure/architecture questions required to scaffold are now largely accepted. The remaining items are feature-level, policy-level, or deployment-vendor choices unless explicitly marked otherwise.

Agents MUST NOT silently resolve these items.

The [approved W3-A Leader decision](specifications/w3-a-publication-scheduling-monitoring-policy.md)
accepts CLASS/PUBLIC, bounded participation windows, positive Attempt duration
and optional eligible CLASS Activity Evidence plus LiveKit monitoring. It
supersedes public-only/unbounded recommendations and blanket Proctoring deferral.
The Leader-approved LD-01..05 follow-up resolves timing boundaries/duration,
explicit monitoring choice, authenticated link-only PUBLIC participation,
reject-CLOSE policy and Wave 3 revocation outage/recovery policy. Only those
policy portions are closed; engineering, W3-B/C/M1/M2 and release gates below
remain open.
AI behavior monitoring remains future design, deferred after Wave 3.

The later [ADR-015](decisions/ADR-015-w3b-w3c-leader-policy.md) explicitly
resolves W3-B/C count/retake, unlimited individual duration, ABANDON, autosave,
submit/timeout, numeric/display and delayed closure C1..C10 product decisions.
[The integrated pack](architecture/w3b-w3c-contract-review-evidence.md) is
APPROVED WITH CONDITIONS for engineering direction under EC79-01..04; [B3 closure/inbox/Identity integration](architecture/w3b3-closure-notification-implementation.md)
is implemented, default disabled pending independent security/runtime review.
[W3-B2 Student API implementation](architecture/w3b2-student-attempt-implementation.md)
adds nine default-disabled Student operations, pending independent review and Leader
merge approval. Its explicitly authorized additive V4.1 capacity correction preserves
V1–V4; B3 adds V5 without changing V4.1; production activation remains unauthorized.
[W3-B1 implementation](architecture/w3b1-persistence-implementation.md) now supplies
V3/V4 and Publication configuration changes (merged PR #80).
[W3-C1 internal grading/finalization](architecture/w3c1-grading-finalization-implementation.md)
is merged at PR #81. Student HTTP is supplied by the default-disabled B2 increment;
B3 now supplies closure/inbox integration under review. The frozen proposed migration examples are design history.
Security/runtime acceptance and Leader implementation merge/release gates
remain. Production activation is NOT AUTHORIZED. Old
recommendations superseded below are not active TBDs; operational release gates
and unrelated product decisions remain open.

Current status amendment (2026-10-14): B2 and B3 are merged through PR #83 at
`4a75be8f446493ad3dd5025a3930b42639cafecb`. Earlier pending-merge statements above
are increment history. [W3-FINAL acceptance](architecture/w3-final-assessment-acceptance.md)
records fresh verification, scoped hardening and remaining external gates; neither
merge nor these tests authorize production activation.

Release workstream amendment (2026-10-14): W3-FINAL PR #84 is merged at
`e615764613b661f8503de5fb5494fc7ea5224ba3`. The separate
[production release acceptance overlay](development/w3-production-release-acceptance.md)
tracks the 11 inherited open gates without replacing the historical 59-gate matrix.
Named DRIs and release/security approvals remain unassigned or unprovided;
production remains **NO-GO**. Merged-SHA CI observations do not certify deployed
transport, durability, capacity, privacy or release authorization.

The Leader explicitly accepts the narrow Identity recipient dependency
conditionally (EC79-01), potentially indefinite delayed-closure blocking for
ineligible/disabled/unverifiable recipients (EC79-02), and initial capacity
budgets/atomic-capture trade-off (EC79-03). These three MEDIUM considerations are
not undecided product blockers. No confirmed accessible notice means no cutoff
or closure-driven forced submission. Future remediation needs a NEW Leader
product/security decision; no synthetic timestamp, auto-abandon or admin bypass.
EC79-04 approves B1→C1→B2→B3 with separately tested/reviewed implementation PRs
and Leader merge approval. The conditional freeze does not satisfy release gates.

## Identity

**ID-03.** Exact account-link conflict/recovery UX?

**ID-04.** Exact user access-token TTL? The initial browser refresh-family
lifetime is accepted as an absolute seven days and future rotation must not
extend that original expiry.

**ID-05.** If Redis revocation lookup is unavailable, what is the required fail-open/fail-closed/degraded behavior for Gateway/services?

Status: **PARTIALLY RESOLVED — LD-05 Wave 3 security policy accepted;
Candidate B architecture APPROVED; Identity prerequisite merged at PR #75;
end-to-end enforcement/operational activation/verification OPEN**.
The Leader-approved Candidate B A–E decision authorizes always-required primary
Identity admission, complete Redis equality, per-actor trustworthy recovery,
coherent PostgreSQL snapshot and narrow dual USER/SERVICE evidence, with mandatory
ADR/capacity/rollout/security gates. See the bounded
[Identity architecture/addendum](architecture/identity-authority-admission.md).
This records the approval from the implementation assignment without inventing
its original date. Gateway/downstream enforcement is proposed in the bounded
[enforcement inventory](architecture/ld05-enforcement.md); production activation
and future route coverage remain incomplete. Required revocation/account
authority must be trustworthy; signature-valid unexpired JWTs alone are
insufficient. Affected protected Assessment, Classroom and Proctoring operations
fail closed while required authority is unavailable or untrustworthy. Prefer
sanitized service-unavailable responses where appropriate, subject to API review.
Do not invalidate refresh credentials, silently log out or cause refresh loops
solely due to this outage. No automatic Identity fallback or degraded
authenticated lower-risk reads are approved for initial Wave 3. Redis health or
connectivity alone does not establish recovery: trustworthy coverage or
reconstruction from authoritative durable state is required before authorization
resumes. Independent truly public, nonprivileged infrastructure operations may
remain available. The bounded Identity prerequisite is merged; end-to-end
security enforcement, recovery verification and rollout gates remain required; see
[Assessment Core gate](specifications/assessment-core-policy.md#ld-05--bounded-security-implementation-and-verification-contract).
No authentication subsystem rewrite is authorized. Wider non-Wave-3 outage
contracts are not settled by this bounded decision.

**ID-07.** Which production SMTP service/deployment and sender identity should
Identity use for OTP delivery? The provider-neutral SMTP adapter, transactional
outbox, retry model, and encrypted-payload architecture are already accepted.

## Classroom

**CLASS-01.** Join-by-code/invite-link becomes active immediately or requires teacher approval?

**CLASS-02.** Can a student leave a class without teacher action?

**CLASS-03.** Final subject/grade taxonomy/catalog model?

**CLASS-04.** Late assignment/submission policy?

## Quiz Markdown

QM-01 through QM-05 are resolved by the accepted MVP grammar in
`docs/specifications/quiz-markdown-spec.md`.

**QM-06.** Accept/reject the proposed "structured question representation -> canonical Markdown renderer" AI/import strategy?

## Import/export

**IMP-01.** Exact Excel import template?

**IMP-02.** Exact DOCX import template?

**IMP-03.** Exact offline DOCX exam format?

**IMP-04.** Exact shuffled paper variants/answer-key requirements?

## Publication / Assessment

ASSESS-01 through ASSESS-04 are resolved for Wave 3 by the
[accepted Assessment Core policy](specifications/assessment-core-policy.md):
`DRAFT → OPEN → CLOSED`, atomic snapshot finalization on opening, owner score
after successful submit and completed grading, and owner answer/explanation
review only after closure for eligible submitted Students. Configurable policy
variants are outside that MVP decision; exact API/schema details are not frozen.

**ASSESS-05.** Publication password hashing/rate-limit/access policy?

**ASSESS-06.** Guest nickname required or optional?

**ASSESS-07.** Anonymous guest session/cookie identity design?

**ASSESS-08.** Exact public slug/opaque identifier format?

**ASSESS-09.** Policy for changing availability start after attempts have begun?

ASSESS-05/06/07 remain **OPEN — deferred future scope**: passwords, anonymous
guests and anonymous-session identity are not required in Wave 3 under LD-03.
ASSESS-08 has **IMPLEMENTED W3-A contract; final review/release gates remain**:
LD78-02 conditionally accepts private locator retention, ASSESS-15 implements
32 random bytes/43-character base64url body lookup and masked entry. No PUBLIC
directory or arbitrary UUID-only creation is proposed. ASSESS-09 remains **OPEN — conditional timing-mutation
contract** if post-start time editing is included. LD-01 forbids retroactive
shortening of authoritative Attempt deadlines; existing accepted mutation rules
still apply without a new blanket post-OPEN timing ban. Required CLASS authority
integration does not approve full Classroom orchestration or guest flows.

**ASSESS-10.** Allowed Attempt count and start/resume/concurrent-start behavior?

Status: **PRODUCT RESOLVED — ADR-015; ENGINEERING DIRECTION APPROVED WITH CONDITIONS (EC79-01..04)**.
maxAttempts 0/unlimited or 1..10, existing default 1 and OPEN freeze; one ACTIVE per
Student/Publication, separate identity/ordinal, ordinary START resumes/returns
latest terminal, explicit retry-safe RETAKE only while OPEN/window/eligible and
entitled. ABANDON consumes finite entitlement but contributes no zero grade.
W3-B1 implements partial uniqueness and Publication-row serialization in PostgreSQL;
START/RETAKE runtime remains deferred. The full workflow is documented in
the [lifecycle contract](specifications/w3-b-attempt-lifecycle-contract.md).

**ASSESS-11.** Exact autosave revision/sequence, retry, and conflict contract?

Status: **PRODUCT RESOLVED — ADR-015; ENGINEERING DIRECTION APPROVED WITH CONDITIONS (EC79-01..04)**.
Question-scoped SAVE, Attempt-wide revision, durable operation identity, same
payload replay acknowledgment, changed replay/stale 409 and terminal immutability.
Exact typed DTOs, hashes and transaction order are proposed in the pack.

**ASSESS-12.** Submission cutoff/deadline and timeout-finalization behavior?

Status: **PRODUCT RESOLVED — ADR-015; ENGINEERING DIRECTION APPROVED WITH CONDITIONS (EC79-01..04)**.
Duration0 gives null deadline;5..240 pins start-plus-duration. Manual eligibility
uses serialized server time, no grace. At bounded deadline grade committed saved
answers only, once, without timely-worker dependency; no window-end finalization.
Unlimited existing work may continue after endAt until a valid individual closure
cutoff/terminalization. Atomic submit/replay and explicit ungraded ABANDON accepted.

**ASSESS-13.** Treatment of in-progress Attempts when a Publication closes?

Status: **PRODUCT RESOLVED — LD-04 plus ADR-015 C1..C10; ENGINEERING DIRECTION APPROVED WITH CONDITIONS**.
Ordinary CLOSE reconciles bounded expiry, checks persisted ACTIVE states and gives
sanitized 409 ACTIVE_ATTEMPTS without counts/identities. Delayed owning-Teacher
REQUEST after endAt establishes real durable authenticated notices; each cutoff is
confirmed accessibility plus 48 hours. Only eligible unlimited work may then be forced
submitted/graded; Teacher COMPLETE alone may commit CLOSED once all terminal.
CANCEL before forced finalization, replacement fresh notices, pending freeze,
per-Attempt recovery/audit and owner-result/review rules are accepted. Real inbox
and deployed accessibility are implementation/release dependencies, not existing
Identity email capability. See [closure contract](specifications/w3-b-closure-notification-contract.md).

**ASSESS-14.** Wave 3 audience/eligibility/access and discovery contracts?

Status: **PRODUCT RESOLVED — LD-03; W3-A access implemented; W3-B/C engineering direction APPROVED WITH CONDITIONS; runtime UNIMPLEMENTED**.
CLASS requires current evidence-derived membership; PUBLIC current USER+STUDENT
and direct non-enumerable locator, no membership. No new owner self-participation
rule. W3-A exact entry/source authorization is in ASSESS-15. Future Attempt
owner/current-member checks and PUBLIC locator-bound new creation are in the
pack. Independent review, LD-05 topology/provisioning and release gates remain;
no anonymous access or postponed CLASS authorization.

**ASSESS-15.** W3-A Publication API/data and QuizVersion handoff contract?
Freeze routes/DTOs/errors, CLASS/PUBLIC fields, authoritative Classroom checks,
schedule instants/boundaries, positive duration/limits, monitoring configuration,
draft mutation/transition concurrency, source-version ownership, internal service
endpoint/scope, snapshot schema/atomic opening and security/configuration impacts
before coding. Accepted scope, ownership and atomic opening are not reopened.

Status: **ENGINEERING CONTRACT IMPLEMENTED — LD78-01..04 ACCEPTED; FINAL REVIEW PENDING**, applying approved LD-01..05 policy;
W3-B/C engineering direction is approved with conditions in the pack; PROCTOR-10 and ID-05 operational
gates remain. W3-A code is merged but independent review/production activation
is not inferred. Recommendation: preserve the existing
Gateway `/api/assessments/**` prefix, derive a trusted initiating Teacher from
the USER principal, acquire the exact QuizVersion through a least-privilege
Client Credentials internal Quiz endpoint that enforces source ownership, and
commit the snapshot plus `OPEN` state atomically in Assessment. Exact paths,
scopes, DTOs, schema, locks and failure/retry semantics require review, not a
new Leader vote for every implementation detail. The accelerated backend assignment
has authorized the bounded [ASSESS-15 engineering proposal](specifications/assess-15-publication-contract.md)
and [backend implementation](architecture/w3a-publication-backend.md). Routes,
scopes, DTOs and other bounded engineering details remain subject to final review.
The Leader's PR #78 continuation accepts LD78-01 database association integrity,
LD78-02 conditional plaintext locator retention, LD78-03 temporary 2 MiB transport
capacity and LD78-04 snapshot-local number/label identity v1. Their correction and
verification are recorded in the contract; this is not global document approval.
Backup/encryption/operator/proxy/APM controls and source-size capacity remain
external release gates. CLOSE/Attempt coordination and production
activation remain blocked by their separate gates. The policy specification's
endpoint inventory remains explicitly **PROPOSED**.

**ASSESS-16.** Exact submission idempotency/finalization and result/review API
contract, including concurrent autosave/submit, validation, replay responses,
persisted submitted-answer/result identity, authorization projections, and errors?

Status: **PRODUCT RESOLVED — ADR-015; ENGINEERING DIRECTION APPROVED WITH CONDITIONS (EC79-01..04)**.
Atomic final answers/pinned deterministic grade/result/terminalization, complete
optional final replacement with expectedRevision, successful identical retry from
durable receipt without regrading; changed submit409. Exact typed answers/results,
masking, pagination and review gates are in the proposed HTTP/grading contracts.

**ASSESS-17.** Detailed timing contract and future unbounded/practice scope?

Status: **ASSESSMENT POLICY RESOLVED — LD-01 amended by ADR-015; ENGINEERING DIRECTION APPROVED WITH CONDITIONS**.
CLASS/PUBLIC keep startAt<endAt; new creation uses inclusive start/exclusive end.
Individual duration 0 or 5..240, null or pinned positive deadline. Window end is not
CLOSED, timeout or review permission. Preserve bounded OPEN configuration/deadlines,
no post-OPEN mode switching; pending closure freezes extension/new creation.
Unlimited CLASS cannot ENABLE monitoring, PUBLIC always DISABLED. Practice timing
and broader unbounded availability remain **OPEN — deferred future scope**.

## Grading

GRADE-01..06 remain accepted exact equal-weight deterministic v1 policy.
W3-C1 now implements the internal v1 grader, exact totals and atomic manual/timeout
core for independent review. This does not implement Student submission/results
or CLOSED review HTTP APIs; runtime integration and release gates remain.

**GRADE-07.** Student numeric input/submission-validation format?

Status: **PRODUCT RESOLVED — ADR-015; ENGINEERING DIRECTION APPROVED WITH CONDITIONS (EC79-01..04)**.
Exactly four characters after outer ASCII-whitespace trim under ADR-013 grammar;
blank/null unanswered; malformed nonblank 400 with no mutation; preserve spelling
and exact decimal equality. The former variable-length recommendation is superseded.

**GRADE-08.** Exact persistence/API and visible percentage format?

Status: **PRODUCT RESOLVED — ADR-015; ENGINEERING DIRECTION APPROVED WITH CONDITIONS (EC79-01..04)**.
Exact earned/possible weights and no intermediate rounding. Student-visible
values are decimal strings; percentage display two places HALF_UP only. Proposed
QUIZOPIA_GRADING_V1 identifier, unconstrained finite numeric/quarter-v1 constraints,
immutable results and highest-graded aggregate are in the pack. No automatic
historic regrading. Future nonterminating rational weights need a new versioned
compatibility contract, not silent numeric rounding.

## Practice

**PRACTICE-01.** Does practice use Assessment-owned practice sessions, a separate learning-progress model, or another persistence model?

**PRACTICE-02.** Is spaced repetition in scope?

**PRACTICE-03.** Exact AI tutor policy/modes?

**PRACTICE-04.** How do classroom due dates/assignments interact with practice activities if class-distributed practice is supported?

## Community

**COMM-01.** Exact visibility names (`PRIVATE`/`UNLISTED`/`PUBLIC` or alternatives)?

**COMM-02.** Can students author community posts, or teachers only initially?

**COMM-03.** Rating scale?

**COMM-04.** Comment editing/deletion policy?

**COMM-05.** Moderation/report workflow?

**COMM-06.** Does a contribution post reference Quiz, QuizVersion, Publication, or a dedicated Community read-model ID?

**COMM-07.** How are visibility changes/deletions propagated to Community posts/ratings/read models?

## Proctoring

W3-M1 Activity Evidence and W3-M2 LiveKit camera monitoring are required Wave 3
outcomes, optional together per eligible CLASS Publication. PUBLIC proctoring is
prohibited. AI behavior monitoring remains accepted future design, deferred after
Wave 3; it is not a closure dependency. LD-02 requires explicit CLASS choice
before OPEN, with no implicit ON/OFF default; choice is immutable upon OPEN.
No additional Activity-only, AI-enabled or A/B mode is approved.
The [W3-M engineering contract](specifications/w3-m-proctoring-contract.md)
classifies derivable M1 mechanics separately from product/security and external
privacy/deployment gates. M1 is under implementation; M2 and real-learner activation
remain gated. Scope approval is not privacy approval or completed implementation.

**PROCTOR-01.** Evidence retention default: 7 days, 30 days, or another platform value?

Status: **OPEN — product/privacy contract**; blocks W3-M1 retention/deletion and
real-learner enablement. No indefinite retention or unapproved default.

**PROCTOR-02.** Suspicious snapshot trigger/frequency policy?

Status: **OPEN — deferred future scope**. Automatic AI snapshot extraction is
not required for Wave 3; approval does not establish snapshot collection policy.

**PROCTOR-03.** Which AI detectors are MVP vs later?

Status: **OPEN — future detector selection; deferred after Wave 3**. AI inference,
thresholds, risk scores and alerts are not Wave 3 implementation/closure gates.

**PROCTOR-04.** Is microphone ever required?

Status: **OPEN — future product decision**; W3-A does not require microphone
capture and excludes audio recording. Do not infer a microphone requirement.

**PROCTOR-05.** Is strict consent-based screen-sharing mode in roadmap?

Status: **OPEN — future scope**; mandatory/strict screen capture is outside Wave 3.

**PROCTOR-06.** Exact teacher violation-review workflow?

Status: **OPEN**. Wave 3 requires authorized activity/video monitoring, without
automatic penalties. AI-assisted disciplinary review is deferred; no final
violation workflow is approved by monitoring scope alone.

**PROCTOR-07.** Production LiveKit deployment: self-hosted or managed?

Status: **OPEN — deployment/privacy contract**; deployment location, scoped access
and production controls must be reviewed before real-learner enablement.

**PROCTOR-08.** Which service is authoritative for answer-change/question-navigation evidence in the proctor timeline, and what sanitized event is copied from Assessment to Proctoring?

Status: **PARTIALLY RESOLVED — ownership accepted; M1 browser-envelope engineering
contract specified; authoritative persistence adapter implemented and locally verified;
independent review pending**. Assessment owns authoritative answers/Attempt lifecycle/deadlines; Proctoring owns
monitoring evidence. No authoritative answer-state duplication. The M1 engineering
contract specifies strict browser metadata, timestamps, append/replay/server ordering
and access boundaries. Browser answer-change metadata is untrusted observation;
the initial increment did not include the authoritative adapter. The later
[producer-owned persistence fact contract](specifications/w3m-authoritative-answer-integration.md)
adds the transactional outbox/Rabbit/inbox workflow without copying answers or
claiming browser actions. Local PostgreSQL/Rabbit/five-service verification passed; independent acceptance remains pending. Browser observations are incomplete/untrusted evidence,
not proof of misconduct.

**PROCTOR-09.** Exact modeling of the "exactly one classroom" constraint between Publication/Assignment/Proctoring?

Status: **ENGINEERING CONTRACT SPECIFIED FOR M1 — IMPLEMENTATION/REVIEW PENDING**.
Exactly one authority-derived Classroom/Publication/Attempt/Student binding and
fresh private Assessment/Classroom checks are specified without cross-service
foreign keys/SQL. No new product policy is required for these derivable mechanics.

**PROCTOR-10.** Persisted monitoring configuration, UI terminology and default?

Status: **PERSISTED REPRESENTATION IMPLEMENTED IN ASSESSMENT; UI/monitoring runtime
review remains separate**. Actual `MonitoringChoice` is `UNCONFIGURED`, `ENABLED`,
`DISABLED`; the M1 contract reuses it.
CLASS Teachers explicitly choose whether monitoring is required. Unconfigured
CLASS DRAFT has no implicit ON or OFF default and cannot OPEN until an explicit
choice is recorded. Enabled means Browser Activity Evidence plus LiveKit Camera;
no additional Activity-only, AI-enabled or A/B modes are approved. PUBLIC must
have monitoring disabled and cannot enable Classroom Proctoring. Choice becomes
immutable upon OPEN. Engineering may propose persisted representation without
inventing modes. Runtime capture still requires eligible active Attempts and
W3-M1/M2 authorization/privacy/failure contracts; those gates remain open.

**PROCTOR-11.** Camera refusal/loss, unavailable devices, accommodations and
session/token lifetime/revocation/reconnect outcomes?

Status: **OPEN — product/security contract**; blocks dependent W3-M2 behavior and
real-learner enablement. No hidden capture or falsely active status. Teachers view
only authorized active participants; Students cannot subscribe to peers. Monitoring
failures cannot corrupt answers/results, change grading or silently extend time.

**PROCTOR-12.** Monitoring privacy/accessibility/security release-gate completion?

Status: **OPEN — required release review**, not a claim of legal compliance.
Finalize notice/viewers, lawful basis/consent/authorization, minors, accommodations,
refusal behavior, retention/deletion, access audits, deployment location, incident
and data-subject procedures, and no recording before real-learner enablement.
Review applicable Vietnamese and other deployment-jurisdiction requirements;
browser permission alone is insufficient. Required privileged checks fail closed
when authoritative verification is unavailable; remaining ID-05 details stay open.

## AI

**AI-01.** Model/provider(s)?

**AI-02.** Supported source-document types, file size/page/token limits?

**AI-03.** Source citation/provenance UX?

**AI-04.** Usage quotas/cost controls?

**AI-05.** Long-term vector-store change criteria beyond the accepted initial pgvector baseline?

**AI-06.** Exact AI -> Quiz authoring mediation contract (frontend-mediated structured output vs service-to-service draft integration)?

## Messaging/contracts

**EVT-01.** Final authoritative Assessment result event used to update Classroom gradebook, including event name/schema?

**EVT-02.** Exact outbox implementation strategy/library/polling/CDC mechanism? The durability requirement is already accepted.

Assessment-to-Proctoring answer-persistence workflow now specifies additive transactional
trigger/outbox, bounded lease polling, confirmed at-least-once publication and durable
consumer inbox. See the [workflow contract](specifications/w3m-authoritative-answer-integration.md).
This is a narrow engineering implementation, not a global event-platform decision.

**EVT-03.** Exact RabbitMQ exchange/queue/routing/retry/DLQ conventions?

The monitoring answer-persistence workflow fixes its exact dedicated direct exchange,
routing key, quorum queue, TLS/provenance/ACL and audited poison handling in the linked
contract. Other workflows/global retry/DLQ conventions remain open. EVT-01 final
result/gradebook events are not resolved by this metadata fact.

## Realtime

**RT-01 — RESOLVED by ADR-016.** Gateway-local native WebFlux hubs own their
sockets; no STOMP broker/relay and no new service. B3 may fan best-effort
RabbitMQ invalidations to instance-local sockets.

**RT-02 — RESOLVED by ADR-016.** Invalidation signals are best effort and may be
lost, duplicated or reordered. Browser clients use authoritative REST resync
after reconnect/gap detection plus bounded periodic resync. The frozen V1 schema
is in `shared/event-contracts`; B3 transport implementation remains pending.

## API

**API-02.** Standardize whether `422` is used or validation remains primarily `400`?

**API-03.** Exact API-versioning convention/path/header strategy?

## Development / Deployment (non-blocking for scaffold)

**DEV-01.** Dependabot vs Renovate for centralized dependency monitoring/alignment?

**DEV-02.** Exact local port map and helper script names?

**DEV-03.** Exact deployment target?

**DEV-04.** Production observability/log backend/vendor?

**DEV-05.** Production secret-manager vendor/platform?

**DEV-06.** Exact production Flyway deployment-job strategy?

## Explicitly resolved since v0.1

The following are no longer open:

- Wave 3 ASSESS-01 through ASSESS-04 and GRADE-01 through GRADE-06 product policy,
  as recorded in `docs/specifications/assessment-core-policy.md`; remaining
  contract and Attempt-policy gates are listed separately above;
- W3-A scope: CLASS/PUBLIC, authoritative CLASS eligibility, bounded participation
  windows, positive Attempt duration and optional eligible CLASS Activity/LiveKit
  monitoring. The later approved LD-01..05 follow-up records timing boundaries,
  deadline formula and 5–240-minute duration, explicit CLASS choice/OPEN immutability,
  authenticated link-only PUBLIC participation, reject-CLOSE with active Attempts,
  and fail-closed/no-fallback/no-degraded-read revocation outage/recovery policy.
  ASSESS-13/14/17, PROCTOR-10 and ID-05 remain partially resolved for engineering
  and later-wave dependencies; ASSESS-15 has specific LD78-01..04 acceptance; final independent review remains pending.
  AI behavior monitoring is deferred after Wave 3;

- ADR-015 resolves W3-B/C product decisions and explicitly amends historical
  LD-01/LD-04. EC79-01..04 conditionally approve engineering direction; executable
  workflow examples remain historical proposals; B3 supplies executable V5, and W3-B1 supplies real V3/V4
  persistence/configuration pending implementation review. Real inbox, review,
  security/runtime and operational gates remain;

- true monorepo;
- independent Maven project per service;
- one frontend;
- `com.quizopia` package root;
- limited shared contracts/test support;
- Spring Cloud Gateway;
- browser business API only through Gateway;
- Spring Authorization Server in Identity;
- RS256 short-lived access JWT + rotating opaque refresh;
- Gateway and every service validate JWT;
- Redis-backed near-immediate revocation;
- OAuth2 Client Credentials for synchronous service authentication;
- internal REST + events split;
- OpenAPI-derived frontend contracts;
- RabbitMQ;
- transactional outbox durability requirement;
- separate DB + credential per service;
- Redis baseline;
- MinIO local + S3 abstraction;
- PostgreSQL + pgvector initial vector baseline;
- Mailpit local/test;
- hybrid local development;
- no Eureka/Consul baseline;
- local/dev Flyway-on-startup + Hibernate validate;
- GitHub Actions;
- targeted PR / full develop-main CI;
- Spotless/ArchUnit/ESLint/Prettier/TS strict;
- structured logging;
- OpenTelemetry;
- Actuator/Micrometer;
- optional local observability profile;
- environment/config/secrets convention;
- Testcontainers;
- Playwright;
- local registration requires the exact stored `gmail.com` domain without
  provider-specific alias normalization;
- six-digit email OTP with 10-minute expiry (accepted baseline; FE-09 proposes a 60-second expiry subject to leader approval), 60-second resend cooldown, five
  failed attempts, replacement on successful issuance, and at most five
  successful issuances per exact email in a rolling hour;
- teacher self-enablement is available to an authenticated ACTIVE,
  email-verified USER who retains the authoritative persisted `STUDENT` role;
  TEACHER is additive and idempotent, the first grant is durably audited in the
  role-mutation transaction, no institution approval or dedicated MVP rate
  limit is required, and a normal refresh is required for a new JWT role claim;
- centrally monitored/aligned dependencies;
- Quiz Markdown question headers use `Câu <n> [TYPE]:` with explicit
  `SINGLE_CHOICE`, `MULTIPLE_CHOICE`, `TRUE_FALSE_MATRIX`, and
  `NUMERIC_FILL`;
- Quiz Markdown supports multiline stems/options/statements, optional multiline
  `Lời giải:`, and backtick fenced code blocks; structural markers are
  recognized only at column 1 outside code fences;
- `NUMERIC_FILL` uses `Đáp án: <token>` with an exact four-character ASCII
  token using digits plus constrained leading `-` / single `.`, with only
  surrounding whitespace trimmed;
- manually authored Quiz Markdown source is preserved on save rather than
  canonicalized/re-rendered;
- Quiz Markdown editor completion is grammar/context aware: line-start
  structural completion, direct four-type question snippets from `C`/`Câ`/
  `Câu`, type fallback after `Câu <n> [`, option/answer/explanation
  suggestions, and no structural suggestions inside fenced code blocks.
