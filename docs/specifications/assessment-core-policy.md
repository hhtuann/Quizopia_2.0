# Wave 3 — Assessment Core and Grading Policy

Status: **APPROVED product policy; W3-B/C engineering direction APPROVED WITH CONDITIONS (EC79-01..04), runtime UNIMPLEMENTED**

Recorded: **2026-10-09**, from the Leader's approved Wave 3 decision.

Effective milestone: Wave 3 begins after **Wave 2 CLOSED at PR #71**, as
confirmed by the Leader. Local and fetched `origin/develop` were inspected at
`836194688316360a28e7c6a4335033890a4805f6`.

This specification records the accepted Wave 3 policy. It supersedes older
assessment/grading TBD entries and illustrative configurable-policy examples
for this milestone. That initial policy record did not establish engineering
freeze, implementation, CI success or Wave 3 completion; the current conditional
engineering freeze is recorded separately in ADR-015. Unresolved choices below
must be resolved before dependent implementation.

The subsequent [approved W3-A Leader decision](w3-a-publication-scheduling-monitoring-policy.md)
expands Wave 3 to CLASS/PUBLIC scheduled Publications, positive bounded Attempt
duration, and optional eligible CLASS Activity Evidence plus LiveKit camera
monitoring. It supersedes the earlier blanket Proctoring exclusion and
public-only/unbounded recommendations, while preserving ASSESS/GRADE policy.
AI behavior analysis remains accepted future design, deferred beyond Wave 3.
The later Leader-approved LD-01..05 follow-up is recorded on 2026-10-11 in
[W3-A section 16](w3-a-publication-scheduling-monitoring-policy.md#16-follow-up-leader-decisions-ld-01-through-ld-05),
after PR #72/#73; it is not part of their historical approval. Product approval
does not freeze the remaining API, privacy or security implementation contracts.

## Current W3-B/W3-C amendment

Recorded 2026-10-13: [ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md)
is the later Leader-approved authority for maxAttempts 0/unlimited or 1..10,
explicit retakes, duration 0/null deadline, ABANDON, strict four-character Student
numeric input, exact decimal string scores/display to 2 places HALF_UP, timeout and C1..C10
delayed closure. The [contract pack](../architecture/w3b-w3c-contract-review-evidence.md)
records exact API/schema/locks/inbox direction now approved with conditions
EC79-01..04. Future artifacts remain PROPOSED/UNIMPLEMENTED; independent read-only
amendment review, security/runtime acceptance and Leader implementation merge
gates remain. Production activation is not authorized. W3-A PR #78 implements Publication/snapshot/
entry, no Attempts/CLOSE/grader/results/inbox. Historical audit sections below
describe earlier checkpoints and do not override current amendments.

## Objective and ownership

The complete journey is:

`Teacher selects immutable QuizVersion → creates/opens Publication → authorized Student starts Attempt → saves answers → submits → Assessment grades → Student/Teacher view authorized results`

Quiz Service owns QuizDraft and immutable QuizVersion. Assessment Service owns
Publication lifecycle/access, delivery snapshots, Attempts, submitted answers,
grading, and results. Identity owns identities, roles, and sessions. Classroom
owns class authority, membership and assignment eligibility, required for the
Wave 3 CLASS journey. Proctoring owns monitoring sessions/evidence and LiveKit
room/token orchestration; AI owns future monitoring integrations without becoming
a Wave 3 dependency.

Assessment Core stays separate from Quiz Authoring, Classroom orchestration,
AI, Community, and Proctoring as service boundaries. Required Classroom
authorization and optional CLASS monitoring integrate through approved service
contracts, never shared databases. No additional grading service is introduced.

### Accepted W3-A scope and remaining contracts

Wave 3 requires `CLASS` and `PUBLIC` Publication flows. Under accepted LD-01,
new starts require OPEN Publication, eligible Student and
`startAt <= serverNow < endAt` (inclusive start, exclusive end). Duration is an
integer 0 (unlimited) or 5–240 minutes inclusive with one-minute granularity;
positive duration pins `attemptDeadlineAt = authoritativeStartedAt + configuredDuration`;
unlimited duration pins null. Window end
does not shorten an already-started Attempt, close Publication or finalize
Attempts. OPEN, schedule eligibility, individual deadlines and durable CLOSED
remain distinct. Store/compare timezone-safe authoritative instants and convert
frontend-local times unambiguously. Configuration changes must never
retroactively shorten authoritative Attempt deadlines. Existing accepted
mutation rules apply; no blanket post-OPEN timing ban is added.

LD-03 requires authenticated USER with authoritative STUDENT role for PUBLIC,
including TEACHER + STUDENT participation through STUDENT under the same rules.
Identity account/token-validity/revocation rules apply; no Classroom membership
is required. Wave 3 sharing uses a direct link with a suitably non-enumerable
locator; no directory, anonymous guests, passwords or anonymous-session identity
is required. Link possession bypasses no eligibility rule. Teacher management
stays owner-authorized and Student APIs expose safe projections. This does not
grant or deny an owner's permission to attempt their own Publication. CLASS
requires authoritative Teacher authorization and Student membership, including
shared-URL entry. Exact locator/route/error and CLASS verification remain
engineering contracts.

LD-02 requires an explicit CLASS Teacher monitoring choice before OPEN;
unconfigured CLASS DRAFT has no implicit ON/OFF default. Choice becomes immutable
upon OPEN; PUBLIC must have monitoring disabled. Enabled monitoring is only the
disclosed Browser Activity Evidence plus LiveKit Camera bundle. No additional
Activity-only, AI-enabled or A/B modes are approved. Persisted representation/UI
terminology remain engineering work. Runtime capture requires eligible active
Attempts and W3-M1/M2 authorization, privacy and failure contracts.

LD-04 rejects OPEN → CLOSED while any Attempt is authoritatively active, with a
documented conflict response. Teacher may close once all Attempts are terminal.
CLOSE must handle overdue Attempts and start/finalization races transactionally;
concurrent start and CLOSE cannot leave an unauthorized active Attempt in CLOSED.
W3-B must ensure expired Attempts eventually become terminal, with authoritative
checks independent of timely background processing. Only durable CLOSED enables
eligible submitted-owner answer/explanation review. No automatic closure is introduced. ADR-015 additionally approves the narrow
C1..C10 delayed Teacher closure workflow with real notices and individual 48-hour
cutoffs; ordinary CLOSE still requires all Attempts terminal.

Expiration, cutoff, saved-only timeout, atomic submit, resumption and replay
policy are now accepted in ADR-015, with proposed engineering contracts in the
pack. Unlimited individual Assessment duration is approved; Practice remains deferred. W3-M1/M2 remain required Wave 3
outcomes with privacy/security release gates: camera refusal/loss/accommodations,
scoped viewer access, retention/deletion and deployment review. AI behavior
monitoring, media/audio recording, mandatory screen sharing, remote device
control and automatic facial identification remain excluded.

## Accepted Publication and visibility policy

| Decision  | Accepted MVP behavior                                                                                                                                                                                                                                                  |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ASSESS-01 | Assessment-owned Publication references one immutable published QuizVersion. Lifecycle is `DRAFT → OPEN → CLOSED`.                                                                                                                                                     |
| ASSESS-02 | The self-contained delivery snapshot is finalized atomically with `DRAFT → OPEN`; all Attempts use that finalized snapshot.                                                                                                                                            |
| ASSESS-03 | An authenticated Attempt owner may immediately see their score after successful submission **and completed grading**. Submission alone does not prove grading completion. Teachers may view results for owned Publications under repository authorization conventions. |
| ASSESS-04 | Correct answers and explanations are available only after the Publication is `CLOSED`, to eligible submitted Students reviewing their own Attempts. Backend enforcement is mandatory.                                                                                  |

`DRAFT` is teacher preparation; `OPEN` permits starts only when audience and
participation-window eligibility also pass; `CLOSED`
prevents new Attempts. After opening, the QuizVersion reference cannot change
and the Publication cannot return to `DRAFT`. Reopening is outside MVP.
Publishing a QuizVersion does not open a Publication automatically.

Participation-window end alone is neither durable `CLOSED` nor evidence that
existing Attempts have completed; it never releases answer keys/explanations.

The snapshot preserves exact source QuizVersion identity, question definitions
and delivery order, protected correct-answer data, grading policy version, and
the metadata needed for stable delivery and grading. Question/option order must
remain stable for an Attempt. Later draft edits or newly published versions
cannot change the opened Publication or its Attempts.

Before review is permitted, student representations must omit answer keys,
correct-answer markers, explanations, hidden grading metadata, and teacher
source snapshots. Frontend hiding is insufficient. The client must not infer
answers from source fields or claim a client-calculated authoritative result.
Students cannot access another Student's Attempt or result.

## Accepted grading policy

All questions have equal maximum weight. Teacher-configurable question weights,
negative marking, and multiple-choice partial credit are outside Wave 3 MVP.

| Decision | Type / concern                 | Accepted behavior                                                                                                                                                                                                                                        |
| -------- | ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GRADE-01 | `SINGLE_CHOICE`                | Exactly the one correct option earns full weight. Incorrect, unanswered, or invalid answers earn zero.                                                                                                                                                   |
| GRADE-02 | `MULTIPLE_CHOICE`              | Selected-option set must exactly equal the correct-option set. Selection order is irrelevant; missing or extra selections earn zero.                                                                                                                     |
| GRADE-03 | Multiple-choice partial credit | None in MVP; no incorrect-choice penalty or negative score. A future partial-credit algorithm requires an explicit future policy version.                                                                                                                |
| GRADE-04 | `TRUE_FALSE_MATRIX`            | With `N` statements, each correct judgment earns `1/N` of the question weight. Incorrect or unanswered judgments earn zero. All-false correct-answer matrices remain valid.                                                                              |
| GRADE-05 | `NUMERIC_FILL`                 | Exact decimal numerical equality, using exact decimal arithmetic such as Java `BigDecimal`; no binary floating-point comparison or tolerance. Invalid or unanswered submissions earn zero under the final accepted validation contract.                  |
| GRADE-06 | Policy version                 | Initial policy is version 1; its exact identifier follows the forthcoming contract. Each finalized snapshot pins it, and grading uses that pinned policy. Future policy changes cannot silently change stored results. No automatic regrading migration. |

The engine must produce deterministic per-question results, total earned weight,
and total possible weight. There is no rounding during the underlying
calculation. For example, earned weights `1`, `0`, `0.75`, and `1` produce
`2.75 / 4`, equivalent to `68.75%`.

### Numeric authoring compatibility

The published correct-answer token retains the accepted Quiz Markdown and
[ADR-013](../decisions/ADR-013-numeric-fill-format.md) contract: exactly four
characters after outer trimming, with the existing constrained ASCII numeric
grammar. This decision does not relax or normalize that authoring grammar.

Student input now follows accepted GRADE-07 in
[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md): exactly four characters
after outer ASCII-whitespace trim, preserving ADR-013 grammar/spelling. Blank/null
unanswered; malformed nonblank wire input returns 400 without mutation. Exact decimal
comparison does not relax the length rule. See the
[grading contract](w3-c-grading-results-contract.md) for typed answers and examples.

### Exact score representation

Accepted GRADE-08 preserves exact earned/possible weights without intermediate
rounding; Student-visible numbers are exact decimal strings. Percentage display
is two decimal places HALF_UP for presentation only. The proposed v1 identifier,
storage/serialization and aggregates are in the grading contract. Current four-
statement matrices give exact quarter weights; future policies with rational
nonterminating weights need a versioned compatibility contract before activation.

## Normative grading examples

These are grading-engine examples with one maximum weight per question. They
define numerical outcomes, not wire DTOs or permission to disclose grading keys through a student endpoint.
Values below are interpreted answers; current wire validation is in ADR-015
and the proposed grading contract. Malformed HTTP requests may be rejected by that contract;
these examples do not require accepting malformed requests for persistence.

### SINGLE_CHOICE

With correct option `B`:

| Student selection                                        | Earned weight | Reason                                  |
| -------------------------------------------------------- | ------------- | --------------------------------------- |
| `B`                                                      | `1`           | Exactly the one correct option.         |
| `A`                                                      | `0`           | Wrong option.                           |
| Unanswered                                               | `0`           | No selection.                           |
| `B` and `C`, if supplied to grading as an invalid answer | `0`           | More than one selection is not correct. |

### MULTIPLE_CHOICE

With correct set `{A, C}`:

| Student selections     | Earned weight | Reason                                   |
| ---------------------- | ------------- | ---------------------------------------- |
| `{A, C}`               | `1`           | Exact set.                               |
| `{C, A}`               | `1`           | Selection order is irrelevant.           |
| `{A}`                  | `0`           | Missing `C`; no partial credit.          |
| `{A, B, C}`            | `0`           | Extra `B`; no partial credit or penalty. |
| Unanswered / empty set | `0`           | No selected correct set.                 |

The proposed submission contract rejects duplicate/unknown labels with 400;
set equality does not authorize silent repair.

### TRUE_FALSE_MATRIX

The accepted authoring contract currently requires four A-D statements. Each
correct judgment therefore earns `0.25`, with unanswered judgments distinct
from an explicit `FALSE` judgment.

| Correct A-D judgments | Student A-D judgments          | Correct count | Earned weight |
| --------------------- | ------------------------------ | ------------- | ------------- |
| `T, F, T, F`          | `T, F, T, F`                   | `4`           | `1`           |
| `T, F, T, F`          | `T, F, T, T`                   | `3`           | `0.75`        |
| `T, F, T, F`          | `T, unanswered, unanswered, F` | `2`           | `0.5`         |
| `T, F, T, F`          | `F, T, F, T`                   | `0`           | `0`           |
| `F, F, F, F`          | `F, F, F, F`                   | `4`           | `1`           |
| `F, F, F, F`          | All unanswered                 | `0`           | `0`           |

Do not default unanswered statements to `FALSE`; that would incorrectly award
credit on an all-false key. No negative score is assigned.

### NUMERIC_FILL

These rows compare **parsed decimal values**. In particular, the spelling
`2.5` below illustrates arithmetic only and is rejected by current four-character
GRADE-07 wire validation. Accepted `0001` and `1.00` illustrate equal wire values.

| Published correct-answer token | Student decimal value                                  | Earned weight | Reason                                            |
| ------------------------------ | ------------------------------------------------------ | ------------- | ------------------------------------------------- |
| `2.50`                         | `2.50`                                                 | `1`           | Equal numeric values.                             |
| `2.50`                         | `2.5`                                                  | `1`           | Decimal scale does not change numerical equality. |
| `0001`                         | `1`                                                    | `1`           | Leading zeros do not change numerical equality.   |
| `-3.5`                         | `-3.5`                                                 | `1`           | Equal negative values.                            |
| `2.50`                         | `2.49`                                                 | `0`           | Unequal; no tolerance.                            |
| `2.50`                         | Unanswered                                             | `0`           | No numeric answer.                                |
| `2.50`                         | Invalid/non-numeric answer, if represented for grading | `0`           | No valid equal numeric value.                     |

Never parse through a binary floating-point value before decimal comparison.
The source token must still satisfy the existing four-character grammar.

### Equal-weight total

For four questions earning `1`, `0`, `0.75`, and `1`, total earned weight is
`2.75`, total possible weight is `4`, and the equivalent percentage is `68.75%`.
The denominator includes unanswered questions. This is a numerical worked
example, not an API response shape or a mandated percentage display format.
No intermediate calculation is rounded; final presentation now follows accepted
GRADE-08 (two places HALF_UP only).

## Attempt, autosave, and submit invariants

- Authenticated Student identity is authoritative; backend authorization derives
  the user from the accepted principal contract, not a client-supplied owner ID.
- Each Attempt binds to exactly one finalized Publication snapshot; answers and
  results are isolated by Attempt ownership.
- Autosave rejects stale writes and cannot mutate a submitted Attempt.
- Final submission is idempotent and safe under retries and concurrency.
- Server time/deadlines are authoritative.
- Submit, grading, and authoritative result persistence remain transactionally
  coherent; results refer to submitted answers and the pinned grading policy.
- Active Attempts do not require Quiz Service availability or mutable drafts.

CLASS requires authenticated authoritative membership; PUBLIC follows accepted
LD-03 authenticated STUDENT link-only participation. Allowed Attempt count, exact
autosave revision/conflict semantics, submission cutoff, saved/unsaved answer
treatment and terminalization are accepted in ADR-015 with proposed exact engineering contracts in the pack.
LD-04 ordinary close and its C1..C10 amendment are both retained.
Do not infer timeout/finalization semantics from baseline diagrams.

## Security and integration requirements

Browser business HTTP calls use Gateway; Gateway and each protected service
independently validate user JWTs. Teacher operations enforce owned Publications;
Student operations enforce owned Attempts/results and accepted eligibility.
Apply [ADR-014](../decisions/ADR-014-access-token-principal-and-authority-contract.md):
user endpoints require `TOKEN_USER` and the appropriate role, while service
endpoints require `TOKEN_SERVICE` and least-privilege `SCOPE_*` authorities.

Assessment cannot read Quiz tables or create cross-service database foreign
keys. Immediate QuizVersion retrieval uses direct internal REST authenticated
with short-lived OAuth2 Client Credentials JWTs, per
[ADR-008](../decisions/ADR-008-service-to-service-authentication.md). The exact handoff endpoint/scope/source authorization are implemented in
[ASSESS-15](assess-15-publication-contract.md) at W3-A PR #78. Active
delivery/grading uses Assessment's snapshot.

If critical integration events are introduced, use RabbitMQ, transactional
outbox or equivalent recovery, and idempotent consumers. This policy does not
freeze an event schema or authorize Classroom gradebook integration implicitly.

## Historical repository inspection and contract gates

**Historical PR #71/early Wave 3 audit:** the source inventories, A–J gap table
and preparation gates below are retained as an earlier checkpoint. W3-A PR #78
and ADR-015 supersede their unimplemented/source-identity and W3-B/C open-policy
statements. For current availability/review status use the integrated pack and
open-question register. This historical section does not reopen accepted policy.

At the inspected PR #71 checkpoint:

- Assessment has only scaffold application/security configuration and context/
  architecture tests; it has no business endpoints, persistence, or migrations.
  Its current JWT setup does not implement the ADR-014 principal converter.
- Quiz's `QuizController` and `QuizVersionQueryService` provide teacher-owned
  published-version history/detail reads. `QuizVersionDetailResponse` contains
  source and structured answer-bearing content; it is not a student delivery DTO.
  No Assessment-to-Quiz service snapshot endpoint is implemented.
- `shared/api-contracts/README.md` publishes reviewed artifacts from implemented,
  accepted APIs. Pre-implementation freezes belong in specification documents;
  speculative generated API artifacts must not be presented as implemented.

### Existing Assessment inventory

The inspected tracked source consists of
[AssessmentServiceApplication](../../services/assessment-service/src/main/java/com/quizopia/assessment/AssessmentServiceApplication.java),
[SecurityConfiguration](../../services/assessment-service/src/main/java/com/quizopia/assessment/configuration/SecurityConfiguration.java),
configuration, build/wrapper/container files, and two test classes. There are no
Assessment controllers, domain models, entities, repositories, event classes,
business DTOs, or SQL migrations. There is no implemented start/resume/autosave/
submit/grade/result/review endpoint.

| Surface                | Inspected source truth                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Technical endpoints    | [application.yml](../../services/assessment-service/src/main/resources/application.yml) exposes Actuator health (including probes), info, and Prometheus. Springdoc API docs/UI are enabled by default. These are framework endpoints, not business routes.                                                                                                                                                                                                                                                                                                  |
| Security               | `SecurityConfiguration` permits health/info/API docs/UI; all other requests require authentication. It uses the default JWT resource-server configuration, without the repository's explicit USER/SERVICE role converter or business ownership rules. Prometheus is not in its permit-all list.                                                                                                                                                                                                                                                              |
| Persistence foundation | [pom.xml](../../services/assessment-service/pom.xml) includes PostgreSQL, JPA, and Flyway dependencies; application configuration uses service-owned `assessment_db` and Hibernate `validate`. Dependencies are not evidence of Publication tables or migrations.                                                                                                                                                                                                                                                                                            |
| Existing tests         | [context test](../../services/assessment-service/src/test/java/com/quizopia/assessment/AssessmentServiceApplicationTests.java) only loads the test context; [architecture test](../../services/assessment-service/src/test/java/com/quizopia/assessment/ArchitectureTest.java) prevents scaffold types named `User`, `Quiz`, or `Attempt`. The test profile disables datasource/JPA/Flyway auto-configuration. Neither test proves business persistence/security. Adapt the scaffold-only architecture rule deliberately when adding an owned Attempt model. |
| Public edge            | [Gateway configuration](../../gateway/src/main/resources/application.yml) already reserves `Path=/api/assessments/**` to Assessment's internal URL. This is a route prefix, not an implemented Assessment API contract.                                                                                                                                                                                                                                                                                                                                      |

### Existing QuizVersion contract

[QuizController](../../services/quiz-service/src/main/java/com/quizopia/quiz/api/QuizController.java)
implements:

- `POST /api/quizzes/{quizId}/versions`: `201` for a new immutable version,
  `200` when the unchanged current draft reuses the latest version;
- `GET /api/quizzes/{quizId}/versions`: newest-first, opaque cursor pagination,
  default limit `20`, accepted range `1..100`, metadata-only history rows;
- `GET /api/quizzes/{quizId}/versions/{versionNumber}`: one exact historical
  snapshot, after owning-teacher checks, independent of the current draft.

[Quiz security configuration](../../services/quiz-service/src/main/java/com/quizopia/quiz/configuration/SecurityConfiguration.java)
requires `TOKEN_USER` and `ROLE_TEACHER` for these endpoints.
[QuizVersionQueryService](../../services/quiz-service/src/main/java/com/quizopia/quiz/application/QuizVersionQueryService.java)
checks Quiz ownership before querying version data. It has no Assessment
service-principal snapshot endpoint.

[QuizVersion](../../services/quiz-service/src/main/java/com/quizopia/quiz/domain/QuizVersion.java)
contains its UUID, Quiz UUID, version number, title/description/source snapshots,
structured content, content schema version (currently `1`), and creation time.
[QuizVersionDetailResponse](../../services/quiz-service/src/main/java/com/quizopia/quiz/api/QuizVersionDetailResponse.java)
includes the source and structured content. Structured
[QuizQuestion](../../services/quiz-service/src/main/java/com/quizopia/quiz/domain/markdown/QuizQuestion.java)
contains numeric answers and explanations; options contain correctness flags.
None of these answer-bearing teacher representations is a student delivery DTO.
Source question numbers and A-D labels are not an approved Assessment item-ID
scheme. Quiz content schema version `1` and grading policy version `1` identify
different contracts and must not be conflated.

Existing tests inspected, but not rerun for this documentation change:
[QuizApplicationServiceTest](../../services/quiz-service/src/test/java/com/quizopia/quiz/QuizApplicationServiceTest.java)
(publish/reuse/immutable historical content),
[QuizVersionQueryServiceTest](../../services/quiz-service/src/test/java/com/quizopia/quiz/QuizVersionQueryServiceTest.java)
(history/ownership/exact version), and
[QuizApiSecurityIntegrationTest](../../services/quiz-service/src/test/java/com/quizopia/quiz/QuizApiSecurityIntegrationTest.java)
(teacher USER and owner-authorized HTTP reads/publish). Their existence does
not prove an Assessment integration or service-token handoff.

### A–J contract-gap audit

`ACCEPTED` refers to source policy/invariants, not implemented Assessment code.
`OPEN — LEADER` requires a product/security decision. `OPEN — ENGINEERING`
requires a reviewed engineering contract consistent with accepted policy, not
permission for routine class/repository/component/locking choices. The
[open-question register](../open-questions.md) records dependencies and explicitly
non-binding recommendations.

| Audit                           | Accepted source truth                                                                                                                                                                                                                                                                                                                                                            | Remaining gap and dependency                                                                                                                                                                                                                                                                      |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A. Student eligibility/access   | W3-A LD-03 requires authenticated USER with authoritative STUDENT for PUBLIC, Identity validity/revocation, no membership and a shared direct link with a suitably non-enumerable locator. TEACHER + STUDENT participates through STUDENT. CLASS requires exactly one Classroom and authoritative Teacher/member checks. Link possession bypasses no eligibility rule.           | **PARTIALLY RESOLVED, ASSESS-14:** product policy accepted; exact locator/route/error, safe projection and CLASS verification contracts OPEN — ENGINEERING. W3-A access and W3-B start depend on those contracts; guests/full Classroom orchestration are not inferred.                           |
| B. Attempt count                | [Assessment product: Assessment publication](../product/assessment.md#assessment-publication) mentions maximum attempts but specifies no value. The approved Wave 3 guardrails explicitly leave count open.                                                                                                                                                                      | **OPEN — LEADER, ASSESS-10:** count/retry entitlement; W3-B start/resume. Concurrent-start locking is an engineering detail after entitlement is chosen.                                                                                                                                          |
| C. Closure with active Attempts | LD-04 rejects CLOSE while any Attempt is authoritatively active; only terminal Attempts permit Teacher closure. Window end is not closure/finalization. Durable CLOSED gates eligible submitted-owner review.                                                                                                                                                                    | **PARTIALLY RESOLVED, ASSESS-13:** conflict wire response, overdue handling and transactional start/finalization races OPEN — ENGINEERING; W3-B deadline terminalization and saved/unsaved answer/submit contracts remain. No force-close or automatic closure.                                   |
| D. Autosave concurrency         | [AGENTS.md](../../AGENTS.md), [business rules 29–32](business-rules.md#attempt-correctness), and [assessment Attempts](../product/assessment.md#attempts) require stable order, stale-write prevention, and immutable submitted answers.                                                                                                                                         | **OPEN — ENGINEERING, ASSESS-11:** revision scope, duplicate retries, conflicts, reconciliation; W3-B API/client freeze. The invariant is already accepted.                                                                                                                                       |
| E. Submit retry/finalization    | [AGENTS.md](../../AGENTS.md) and [business rules 31–32](business-rules.md#attempt-correctness) require idempotency and coherent submit/grading/result persistence. ASSESS-03 requires completed grading for score visibility; LD-04 accepts reject-CLOSE while active.                                                                                                           | **OPEN — ENGINEERING, ASSESS-16:** request/response replay, concurrent save/submit, persisted finalization identity, validation and retry recovery. W3-B/results depend on this; cutoff and saved/unsaved answer treatment remain unresolved.                                                     |
| F. Student numeric input        | GRADE-05 accepts exact decimal equality. [ADR-013](../decisions/ADR-013-numeric-fill-format.md) and [Quiz Markdown](quiz-markdown-spec.md#four-character-answer) constrain the published correct-answer token.                                                                                                                                                                   | **OPEN — LEADER/CONTRACT, GRADE-07:** Student grammar, normalization, unanswered/invalid input behavior. Blocks W3-B numeric submission and grading integration, not preservation of published keys.                                                                                              |
| G. Score representation         | Equal maximum weights, deterministic totals, and no intermediate rounding are accepted. [Coding standards](../development/coding-standards.md#persistence) require decimal score arithmetic.                                                                                                                                                                                     | **OPEN — ENGINEERING, GRADE-08:** exact weights/storage/API and policy identifier. **OPEN — LEADER/CONTRACT:** rounding/display convention if a percentage is shown. Blocks grading/result API freeze; no re-vote on equal weights.                                                               |
| H. Time limits/deadlines        | LD-01 accepts OPEN + eligible Student + `startAt <= serverNow < endAt`, integer 5–240-minute duration and deadline = authoritative start + duration. Timezone-safe instants, unambiguous local conversion and no retroactive deadline shortening are required. Window end, deadlines and CLOSED remain distinct.                                                                 | **PARTIALLY RESOLVED, ASSESS-17/12:** timing policy accepted; wire fields/validation/conversion remain engineering work. W3-B expiry, cutoff, timeout finalization, resumption and response ordering remain OPEN. Unbounded/Practice timing deferred; baseline diagrams do not freeze semantics.  |
| I. Quiz acquisition/integration | [ADR-003](../decisions/ADR-003-quiz-publication-model.md), [ADR-008](../decisions/ADR-008-service-to-service-authentication.md), and [ADR-014](../decisions/ADR-014-access-token-principal-and-authority-contract.md) accept self-contained snapshots, direct internal REST with Client Credentials, and USER/SERVICE separation. Existing Quiz endpoints are teacher USER only. | **OPEN — ENGINEERING, ASSESS-15:** internal route/scope/schema, service registration/config, trusted source-owner enforcement, errors, and snapshot atomicity. W3-A opening depends on this. Broader source sharing would need a separate product decision.                                       |
| J. Results/teacher review       | ASSESS-03/04 and [roles/personas](../product/roles-and-personas.md) permit Teachers to view owned Publication results, Students to view only their own results, and post-closure own submitted answer review. [API conventions](api-conventions.md) require backend authorization and explicit DTOs.                                                                             | **OPEN — ENGINEERING:** result/list/review DTOs, errors, pagination, and backend projection tests under ASSESS-15/16. No permission is added for other Teachers, Student cross-owner reads, Admin bypass, or pre-closure learner answer review. Classroom gradebook integration remains separate. |

**ID-05 is PARTIALLY RESOLVED by LD-05:** affected protected Assessment,
Classroom and Proctoring operations fail closed until required revocation/account
authority is trustworthy. Signature-valid, unexpired JWTs alone do not implement
near-immediate revocation. No automatic Identity fallback or degraded authenticated
lower-risk reads are approved for initial Wave 3. Redis connectivity/health alone
is insufficient for recovery; trustworthy coverage or reconstruction from
authoritative durable state is required. Do not invalidate refresh credentials,
silently log out or cause refresh loops solely due to the outage. Sanitized
service-unavailable responses are preferred where appropriate, subject to API
review. Independent truly public nonprivileged infrastructure may remain available.
Implementation, authoritative recovery mechanism and security verification remain
outstanding under the bounded contract below, not a new scoring-policy question.

### LD-05 — Bounded security implementation and verification contract

**Historical gate: Dev1 preparation required before dependent wiring.**
The subsequently approved Candidate B architecture and bounded Identity
prerequisite are recorded in the [LD-05 addendum](../architecture/identity-authority-admission.md).
That approval supersedes the recovery-design preparation statement below;
Gateway/downstream enforcement, verification and activation remain incomplete.

This is the required scope for the next security contract, not approval of a
recovery design or a claim that checks exist. Preserve Identity's durable
account/revocation authority, Redis distribution, Gateway plus service validation,
USER/SERVICE separation and current refresh lifecycle. No auth subsystem rewrite.
Freeze, review and document:

1. Affected protected operations and enforcement points at Gateway and owning
   services, required revocation/account authority, and the conditions under which
   coverage can be trusted. PUBLIC Assessment participation is protected.
2. Outage/untrustworthy-state detection and authorization denial; no JWT-only,
   automatic Identity fallback or degraded authenticated read path. Define safe
   behavior for concurrent requests and independently available public infrastructure.
3. Trustworthy recovery/coverage or reconstruction from Identity-owned durable
   authority, including revocations/account changes during outage and recovery,
   restart/data-loss cases and a reviewed criterion for safely resuming operations.
   Connectivity or a healthy but incomplete Redis store cannot satisfy that gate.
   Exact mechanism and cross-service contracts remain open; no cross-service SQL.
4. Sanitized unavailable/error response and browser retry/session handling under
   API review, preserving refresh credentials and avoiding outage-induced logout
   or refresh loops. Do not invent wire errors/statuses in this policy record.
5. Bounded runtime configuration/secret/observability impacts and security test
   evidence, without credential/token/OTP/PII logging or unrelated authentication work.

Minimum verification evidence for that future implementation must cover:

- Otherwise-valid JWTs denied through Gateway and protected Assessment/Classroom/
  Proctoring enforcement when required authority is unavailable/untrustworthy.
- No lower-risk authenticated read or automatic Identity fallback bypass; an
  independent public infrastructure operation does not authorize business access.
- Redis disconnect, restart or data loss followed by healthy connectivity but
  incomplete coverage still denies affected operations.
- Durable revocation/account changes before and during outage/reconstruction are
  covered before recovery; concurrent recovery/authorization cannot admit a
  disabled/revoked user through a stale or incomplete authority view.
- Recovery resumes eligible operations only after the reviewed trust criterion;
  disabled/revoked identities remain denied despite signature-valid JWTs.
- Sanitized error behavior and browser handling preserve refresh credentials,
  avoid silent logout/refresh loops, and do not misreport outage as invalid
  credentials where the reviewed service-unavailable contract applies.

These checks are future acceptance criteria. This documentation change adds no
revocation implementation or security test execution evidence.

### W3-A — Publication API/data contract before coding

Document and accept:

1. Routes, commands, DTOs, error/status behavior, ownership rules, pagination,
   lifecycle transition retries/concurrency, and allowed draft edits, including
   CLASS/PUBLIC audience, exactly one Classroom for CLASS, participation schedule,
   LD-01 integer 5–240-minute duration and LD-02 explicit monitoring choice/OPEN
   immutability, plus LD-04 conflict and transactional close semantics.
2. Authoritative CLASS Teacher authorization/membership verification, fail-closed
   privileged checks, and LD-03 authenticated STUDENT link-only PUBLIC policy;
   freeze exact locator/route/errors and safe projections without guest flows.
3. Exact QuizVersion identity, protected internal handoff scope/DTO and source
   access enforcement, failure behavior, and the atomic opening transaction.
4. Assessment-owned Publication/snapshot schema, constraints, stable item IDs/
   order, protected grading representation, schema/policy identifiers, and Flyway
   changes. Failed opening must not leave an `OPEN` Publication without a snapshot.
5. Gateway mapping, Assessment JWT/revocation integration, service-client runtime
   configuration/secrets, and applicable event impacts. Only required secrets
   belong to each service.

#### PROPOSED endpoint inventory — no endpoint/schema approval

The existing Gateway prefix is retained in the following **PROPOSED** inventory.
No path, request field, response schema, or database schema in this inventory is
frozen by this documentation PR. Public API versioning remains API-03; this is
not permission to introduce a new runtime versioning convention.

| Proposed operation / route                                 | Precise contract still required                                                                                                                                                                                                                                                               |
| ---------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `POST /api/assessments/publications`                       | Teacher-owned creation in `DRAFT`; exact immutable source-version reference, metadata/access fields and bounds; authenticated owner derivation; creation status/body/location and retry behavior.                                                                                             |
| `GET /api/assessments/publications`                        | Owner-scoped Teacher list; cursor ordering/defaults/bounds, filters, summary fields, and no other owner's records.                                                                                                                                                                            |
| `GET /api/assessments/publications/{publicationId}`        | Owner Teacher view; define separately the eligible Student entry projection/path and its discovery locator. A safe role-specific DTO cannot reuse teacher source/grading fields.                                                                                                              |
| `PUT /api/assessments/publications/{publicationId}`        | Allowed `DRAFT` edits/replacement semantics, concurrency token, immutable fields, and disallowed transitions; any post-open metadata edit must have explicit contract support.                                                                                                                |
| `POST /api/assessments/publications/{publicationId}/open`  | Owner-authorized transition, upstream retrieval failures, idempotent retry outcome, source access, policy/schema pinning, atomic local snapshot/state persistence, and concurrent open/edit/close behavior.                                                                                   |
| `POST /api/assessments/publications/{publicationId}/close` | Owner-authorized transition/retry rules implementing LD-04 reject-CLOSE while authoritatively active; freeze conflict response, overdue handling and transactional start/finalization races under ASSESS-13. No automatic closure, force-close or reopen in MVP.                              |
| Internal QuizVersion snapshot read, exact route **TBD**    | Client Credentials service scope, explicit trusted initiating-owner context derived by Assessment, source ownership enforcement in Quiz, exact version identity/schema, and bounded failures. Existing teacher USER endpoints cannot be treated as a service endpoint or exposed to Students. |

All prospective responses need explicit safe DTOs and repository-consistent
error/status behavior. UUID resource IDs, version numbers, database column types,
indexes, and optimistic/pessimistic locks are engineering design choices to
document and review; they do not each require a new Leader policy decision.
No speculative OpenAPI/generated artifact is added under `shared/`.

#### Dev1 / Dev2 handoff

Dev1 owns the W3-A backend contract proposal and implementation after the relevant
gates are resolved: Assessment Publication/snapshot persistence, state commands,
safe projections, authorization, and authenticated QuizVersion acquisition with
the Quiz owner. Quiz changes remain limited to the accepted handoff; no source
ownership moves. Dev1 must inventory Flyway/API/config/event impacts and adapt
the scaffold-only architecture rule. Proposed schema must separately identify
Publication ownership/state/source reference and finalized snapshot delivery/
protected grading/policy data, without cross-service foreign keys.

Dev2 owns Teacher create/configure/open/close UI and safe Student entry/delivery
consumption once those backend contracts are accepted. Use the real Gateway
request/session infrastructure and generated/derived accepted contract types
where practical. Apply [DESIGN.md](../../DESIGN.md) sections 9, 15, 21, and 25.5:
workspace UX does not grant roles, states/errors remain clear, and assessment
controls are accessible and stable. Dev2 must not invent scoring, credentials,
membership checks, or hidden grading fields in frontend models. Dev1 and Dev2
use separate bounded branches targeting `develop`, with one primary writer per
branch; integration starts from accepted contracts rather than duplicate DTOs.

Minimum W3-A verification before implementation merge:

- USER/SERVICE separation; missing roles/scopes, other-owner, disabled/revoked,
  unauthenticated, and ineligible Student denials through Gateway and service.
- Quiz owner/version checks and protected handoff; forged initiating-owner
  context, missing/wrong scope, missing version, and Quiz/Identity outage cases.
- PostgreSQL Testcontainers: atomic opening rollback, immutable snapshot/source
  reference after opening, concurrent/retried transitions, and no return to
  draft or reopen. Failed acquisition leaves no usable `OPEN` Publication.
- Student payload assertions omit correctness flags, numeric keys, source,
  explanations, and grading metadata. Teacher management DTOs cannot be reused
  for Student delivery. Later result tests enforce owner score/review gates.
- Browser → Gateway → Identity/Quiz/Assessment E2E for Teacher selection/create/
  open/close and accepted entry states; verify delivery stays self-contained
  during Quiz outage when W3-B is integrated. Distinguish mocks from real topology.
- CLASS ownership/membership verification and denial/outage cases, including
  shared-URL bypass attempts; PUBLIC entry according to its approved contract.
- Timezone-safe instants and unambiguous local conversion; inclusive start and
  exclusive end; integer 5/240-minute bounds and rejection outside them/fractional
  minutes; start-plus-duration deadlines unaffected by window end or retroactive
  shortening. Distinguish OPEN, window eligibility, Attempt deadline and CLOSED.
- LD-04 active-Attempt close conflicts, overdue terminalization integration and
  transactional start/close/finalization races under the accepted W3-B contract.
- LD-03 authenticated STUDENT link access, TEACHER + STUDENT participation,
  Identity validity/revocation, no membership for PUBLIC and no link bypass.
- LD-05 outage/recovery/session tests under the bounded security contract above.
- Monitoring configuration requires explicit CLASS choice before OPEN, has no
  implicit default, becomes immutable upon OPEN, requires PUBLIC monitoring
  disabled and exposes the accepted Student notice. W3-M1/M2 separately verify evidence, real camera permissions,
  Teacher isolation, prohibited peer subscriptions, failure handling and release gates.

**W3-A readiness:** policy reconciliation is recorded; ASSESS-15 remains
**OPEN — ENGINEERING CONTRACT**, not permission for feature coding. LD-01..05
settle timing bounds/formula, monitoring choice, PUBLIC participation/sharing,
reject-CLOSE and Wave 3 outage/recovery policy. ASSESS-13/14/17 engineering,
CLASS authority verification, PROCTOR-10 representation/UI and ID-05 security
implementation/recovery/verification still need review. W3-B count/numeric/
autosave/cutoff/terminalization/submit, W3-C score/result and W3-M1/M2 evidence/
privacy/failure contracts remain gates before dependent implementation/release;
they need not block unrelated W3-A design work.

### W3-B — Attempt/autosave/submit contract before coding

Resolve and record [open questions](../open-questions.md) for attempt count,
autosave revisions/conflicts, submission cutoff, and closure handling. Freeze
start/resume/answer/submit request and response semantics, retries/concurrency,
validation for each question type, persistence/transaction boundaries, and
student-safe delivery. Resolve Student numeric input compatibility explicitly.

### W3-C — Grading/results contract before coding

Freeze exact scoring/storage/API/display representation and the version 1 policy
identifier, then result/review DTOs and authorization. Keep accepted scoring and
visibility fixed; a contract must not weaken policy for implementation convenience.

## Sequence and evidence required

1. Reconcile documentation and accepted decisions.
2. Freeze W3-A Publication API/data contracts.
3. Implement Publication backend/frontend.
4. Freeze W3-B Attempt/autosave/submit contracts.
5. Implement Attempt backend/frontend.
6. Freeze W3-C contracts and implement grading, results and visibility.
7. Freeze and deliver W3-M1 Activity Monitoring and W3-M2 LiveKit camera monitoring
   in separate bounded workstreams, satisfying privacy/security release gates.
8. Run complete real-topology E2E for CLASS/PUBLIC, timing and enabled/disabled
   eligible CLASS monitoring; include real browser camera permissions and failures.
9. Close Wave 3 only after the W3-A Leader decision's Definition of Done is met.

Verification must cover immutable snapshot isolation, continued delivery during
Quiz outage, ownership/role/service-principal denials, absence of hidden answers
before closure, owner review after closure, all four scoring policies (including
all-false and fractional matrices), stale autosave rejection, concurrent/retried
submit, and coherent persisted submitted answers/results. API/component tests
and real Browser → Gateway → owning-service E2E provide complementary evidence.

Every workstream targets `develop` and becomes merge-ready only after matching
the approved contract, required tests and CI passing, verified security
boundaries, no remaining Blocker/High findings, and accurately reported real
integration evidence. The Leader performs final review/merge. Product approval
alone does not satisfy those gates.

## MVP exclusions

AI/manual grading, AI behavior inference/detectors/risk scoring/alerts, negative
marking, advanced scoring, randomized question banks, media/audio recording,
mandatory/strict screen sharing, remote device control, automatic facial
identification, advanced Classroom orchestration, rich analytics, answer review
before closure and retrospective automatic regrading are excluded. Required
Activity Evidence and LiveKit camera monitoring for optional eligible CLASS
Publications are included under the subsequent W3-A decision. Preserve future AI
monitoring documentation and extension points without making AI a closure gate.
