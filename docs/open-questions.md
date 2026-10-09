# Open Questions

Status: **Active after accepted Wave 3 Assessment and W3-A scope policy — v0.4**

The infrastructure/architecture questions required to scaffold are now largely accepted. The remaining items are feature-level, policy-level, or deployment-vendor choices unless explicitly marked otherwise.

Agents MUST NOT silently resolve these items.

The [approved W3-A Leader decision](specifications/w3-a-publication-scheduling-monitoring-policy.md)
accepts CLASS/PUBLIC, bounded participation windows, positive Attempt duration
and optional eligible CLASS Activity Evidence plus LiveKit monitoring. It
supersedes public-only/unbounded recommendations and blanket Proctoring deferral.
Only explicitly resolved portions below are closed; PUBLIC access, detailed
timing/closure, monitoring contracts and privacy/security gates remain open.
AI behavior monitoring remains future design, deferred after Wave 3.

## Identity

**ID-03.** Exact account-link conflict/recovery UX?

**ID-04.** Exact user access-token TTL? The initial browser refresh-family
lifetime is accepted as an absolute seven days and future rotation must not
extend that original expiry.

**ID-05.** If Redis revocation lookup is unavailable, what is the required fail-open/fail-closed/degraded behavior for Gateway/services?

Status: **OPEN — security decision**. Conditional Wave 3 protected-service
dependency; near-immediate revocation is already accepted. W3-A now requires
privileged security checks to fail closed when authoritative verification is
unavailable. Detailed Redis outage/recovery/degraded contracts across Gateway and
services remain OPEN within that constraint; this does not settle every global
outage behavior or authorize an unverified privileged operation.

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

ASSESS-05 through ASSESS-09 are **OPEN — conditional/deferred**, not automatic
Wave 3 blockers. Passwords/guest identity are deferred unless explicitly added;
a public-link format is needed if the ASSESS-14 access/discovery contract selects
that path, and ASSESS-09 matters only if post-start time editing is included.
CLASS authorization/membership integration is now required; full Classroom
orchestration and guest flows are not inferred from that approval.

**ASSESS-10.** Allowed Attempt count and start/resume/concurrent-start behavior?

Status: **OPEN — Leader product decision**; blocks **W3-B start/resume**.
Recommendation: one Attempt per authenticated Student per Publication in MVP,
with resumable active work and retry-safe start. Count/retake entitlement needs
approval; the uniqueness/locking implementation is an engineering choice.

**ASSESS-11.** Exact autosave revision/sequence, retry, and conflict contract?
Stale writes must be rejected and submitted Attempts must be immutable.

Status: **OPEN — engineering contract**; blocks **W3-B autosave API/client**.
Recommendation: Attempt-wide monotonically increasing revision with conditional
save, explicit stale-conflict response and authoritative re-read reconciliation.
Freeze retry/duplicate handling and concurrent submit behavior in that contract;
do not escalate a choice of Java classes or database lock mechanism.

**ASSESS-12.** Submission cutoff/deadline and timeout-finalization behavior?

Status: **OPEN — Leader product decision**; blocks **W3-B submit/finalization**
and dependent **W3-A configuration**. Bounded start windows and positive bounded
Attempt duration are accepted. Exact deadline formula, cutoff, expiry/automatic
submission, unsaved-work treatment, resumption and response ordering remain OPEN.
Start-plus-duration is recommended pending contract freeze; participation-window
end must not silently shorten a valid Attempt or be equated with durable closure.
Server enforcement must not depend solely on background jobs. No client clock
authority or undocumented grace period is approved.

**ASSESS-13.** Treatment of in-progress Attempts when a Publication closes?
Closure prevents new Attempts but does not itself settle existing Attempt behavior.

Status: **OPEN — Leader product decision**; blocks **W3-A close** and **W3-B
save/submit/finalization**. Recommendation: close finalizes active Attempts using
their latest authoritative saved answers and prevents further mutation. The
Leader must approve treatment of unsaved work and cutoff before implementation;
transaction/recovery design is then engineering. Review opens after closure, so
allowing other Attempts to continue needs an explicit disclosure-risk decision.

**ASSESS-14.** Wave 3 audience/eligibility/access and discovery contracts?

Status: **PARTIALLY RESOLVED — CLASS/PUBLIC accepted; remaining product and
engineering contracts OPEN**. Both audiences ship; CLASS references exactly one
Classroom and requires authoritative Teacher assignment authorization and Student
membership, including shared-URL access. Classroom Service owns that authority.
PUBLIC has no membership requirement and cannot enable Classroom Proctoring.
Whether PUBLIC strictly requires an authenticated STUDENT, its precise
authorization, discovery and sharing remain **OPEN — Leader product decision**;
CLASS verification mechanism remains an **OPEN — engineering contract**. These
block dependent **W3-A access/discovery** and **W3-B start**. Do not infer guests,
close this whole item, or postpone required CLASS authorization to MVP-C.

**ASSESS-15.** W3-A Publication API/data and QuizVersion handoff contract?
Freeze routes/DTOs/errors, CLASS/PUBLIC fields, authoritative Classroom checks,
schedule instants/boundaries, positive duration/limits, monitoring configuration,
draft mutation/transition concurrency, source-version ownership, internal service
endpoint/scope, snapshot schema/atomic opening and security/configuration impacts
before coding. Accepted scope, ownership and atomic opening are not reopened.

Status: **OPEN — engineering contract**, with unresolved ASSESS-14/13/17 dependencies;
blocks **W3-A contract freeze/opening**. Recommendation: preserve the existing
Gateway `/api/assessments/**` prefix, derive a trusted initiating Teacher from
the USER principal, acquire the exact QuizVersion through a least-privilege
Client Credentials internal Quiz endpoint that enforces source ownership, and
commit the snapshot plus `OPEN` state atomically in Assessment. Exact paths,
scopes, DTOs, schema, locks and failure/retry semantics require review, not a
new Leader vote for every implementation detail. The policy specification's
endpoint inventory remains explicitly **PROPOSED**.

**ASSESS-16.** Exact submission idempotency/finalization and result/review API
contract, including concurrent autosave/submit, validation, replay responses,
persisted submitted-answer/result identity, authorization projections, and errors?

Status: **OPEN — engineering contract**; blocks **W3-B submit** and
**grading/results API freeze**. Idempotency, transactional coherence, owner-only
Student results, owned-Publication Teacher results, and the review gate are
already accepted. Recommendation: one durable finalization/result per Attempt,
atomic submitted-answer/grade/result persistence, and retries returning the same
authoritative result without another grade. Decide whether request identity is
the Attempt or an explicit replay key, and document save/submit races and
malformed/unknown-option handling. Do not invent a separate grading-completion
lifecycle merely to satisfy a DTO; expose completed grading truthfully.

**ASSESS-17.** Detailed timing contract and future unbounded/practice scope?

Status: **PARTIALLY RESOLVED — bounded Wave 3 scope accepted; details and future
scope OPEN**. Both CLASS/PUBLIC require bounded participation availability for new
starts and positive bounded Attempt duration. Opening is distinct from scheduling;
retain `DRAFT → OPEN → CLOSED`. Exact fields, timezone-safe storage/display,
chronological validation, inclusive/exclusive start boundaries, duration limits,
before-window/after-window behavior and interactions with ASSESS-12/13 remain
OPEN; they block dependent **W3-A timing** and **W3-B deadline** contracts.
Start-plus-duration is recommended, not frozen. The broader non-proctored
unbounded baseline and no-retroactive-shortening rule remain documented; future
unbounded/practice support is **OPEN — Leader product decision**, outside this
Wave 3 cut. Do not claim the entire question is resolved or retain the superseded
recommendation to ship unbounded Wave 3 timing.

## Grading

GRADE-01 through GRADE-06 are resolved for Wave 3 by the
[accepted Assessment Core policy](specifications/assessment-core-policy.md):
single-choice exact correctness, multiple-choice exact set match without partial
credit, proportional true/false credit, exact decimal numeric equality without
tolerance, no negative marking, equal question weights, and snapshot-pinned
grading policy version 1. The following contract details remain open:

**GRADE-07.** Student numeric input/submission-validation format, including syntax,
trimming/normalization, bounds, unanswered representation, and invalid-payload
handling? The published four-character ASCII correct-answer grammar is unchanged;
do not assume the Student must type four characters.

Status: **OPEN — Leader product compatibility decision plus submission contract**;
blocks **W3-B numeric input/save/submit** and **numeric grading integration**.
Recommendation: variable-length ASCII decimal strings with optional leading
minus, digits before an optional decimal point, and digits after a present point;
outer trim only, no exponent/comma/plus/full-width normalization. Treat blank as
unanswered, reject malformed nonblank wire input, and give invalid answer values
zero if represented to grading. Set a defensive maximum size in the reviewed
engineering contract. This recommendation does not change the published
four-character correct-answer grammar or approve Student syntax yet.

**GRADE-08.** Exact per-question/total score persistence and API representation,
percentage rounding/display rules, and exact version 1 policy identifier?
Underlying calculations must not round. Current four-statement matrices yield
quarters; percentages may repeat when divided by the total question count.

Status: **OPEN — engineering score/API contract; Leader acceptance for visible
rounding/display policy**; blocks **grading/result API freeze and result UI**.
Recommendation: exact decimal earned/possible weights with `BigDecimal`, per-question
quarters for current matrices, integer question count for possible weight, and
stored pinned policy identity; derive percentage only for presentation. If a
percentage is shown, propose two decimal places with explicit `HALF_UP` rounding
for display only. Policy identifiers, decimal serialization/storage scale, and
the final display convention must be reviewed; no intermediate rounding or
automatic historic regrading is authorized.

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
Wave 3; it is not a closure dependency. No established A/B enum/default is implied.
PROCTOR-01..09 retain unresolved portions; scope approval is not contract freeze.

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

Status: **PARTIALLY RESOLVED — ownership accepted; evidence contract OPEN**.
Assessment owns authoritative answers/Attempt lifecycle/deadlines; Proctoring owns
monitoring evidence. No authoritative answer-state duplication. Freeze sanitized
answer-change metadata without answer content, supported browser events, timestamps,
durability/tamper resistance, schemas, transport, retry/ordering and access controls
before W3-M1 implementation. Browser observations are incomplete/untrusted evidence,
not proof of misconduct.

**PROCTOR-09.** Exact modeling of the "exactly one classroom" constraint between Publication/Assignment/Proctoring?

Status: **OPEN — engineering contract**. Exactly one Classroom, authoritative
Teacher/member checks and active eligible Attempts are accepted constraints.
Freeze references and verification without cross-service foreign keys/SQL.

**PROCTOR-10.** Persisted monitoring configuration, UI terminology and default?

Status: **OPEN — product/engineering contract**; blocks dependent W3-A/W3-M UI
and schema. The optional eligible CLASS bundle includes Activity Evidence and
LiveKit camera monitoring; do not invent a previously accepted A/B enum or
ACTIVITY_ONLY/ACTIVITY_AND_LIVE values/defaults.

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

**EVT-03.** Exact RabbitMQ exchange/queue/routing/retry/DLQ conventions?

## Realtime

**RT-01.** Exact horizontally scalable WebSocket/STOMP broker/relay topology?

**RT-02.** WebSocket event sequencing/gap-detection contract?

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
  monitoring; only these scope portions are resolved, not the remaining ASSESS-14/
  ASSESS-17 or PROCTOR contracts. AI behavior monitoring is deferred after Wave 3;

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
