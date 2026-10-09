# Wave 3 — Assessment Core and Grading Policy

Status: **APPROVED — PRODUCT POLICY; API/data contracts not yet frozen**

Recorded: **2026-10-09**, from the Leader's approved Wave 3 decision.

Effective milestone: Wave 3 begins after **Wave 2 CLOSED at PR #71**, as
confirmed by the Leader. Local and fetched `origin/develop` were inspected at
`836194688316360a28e7c6a4335033890a4805f6`.

This specification records the accepted Wave 3 policy. It supersedes older
assessment/grading TBD entries and illustrative configurable-policy examples
for this milestone. It does not claim implementation, contract freeze, CI
success, or Wave 3 completion. Unresolved contract and product choices below
must be resolved before dependent implementation.

## Objective and ownership

The complete journey is:

`Teacher selects immutable QuizVersion → creates/opens Publication → authorized Student starts Attempt → saves answers → submits → Assessment grades → Student/Teacher view authorized results`

Quiz Service owns QuizDraft and immutable QuizVersion. Assessment Service owns
Publication lifecycle/access, delivery snapshots, Attempts, submitted answers,
grading, and results. Identity owns identities, roles, and sessions. Classroom
continues to own membership/assignment concepts for later integration.

Assessment Core stays separate from Quiz Authoring, Classroom orchestration,
AI, Community, and Proctoring. No additional grading service is introduced.

## Accepted Publication and visibility policy

| Decision  | Accepted MVP behavior                                                                                                                                                                                                                                                  |
| --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ASSESS-01 | Assessment-owned Publication references one immutable published QuizVersion. Lifecycle is `DRAFT → OPEN → CLOSED`.                                                                                                                                                     |
| ASSESS-02 | The self-contained delivery snapshot is finalized atomically with `DRAFT → OPEN`; all Attempts use that finalized snapshot.                                                                                                                                            |
| ASSESS-03 | An authenticated Attempt owner may immediately see their score after successful submission **and completed grading**. Submission alone does not prove grading completion. Teachers may view results for owned Publications under repository authorization conventions. |
| ASSESS-04 | Correct answers and explanations are available only after the Publication is `CLOSED`, to eligible submitted Students reviewing their own Attempts. Backend enforcement is mandatory.                                                                                  |

`DRAFT` is teacher preparation; `OPEN` admits eligible Students; `CLOSED`
prevents new Attempts. After opening, the QuizVersion reference cannot change
and the Publication cannot return to `DRAFT`. Reopening is outside MVP.
Publishing a QuizVersion does not open a Publication automatically.

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

The Student input/submission grammar is **not yet approved**. Exact numerical
comparison does not mean Students must type four characters. The documentation
contract must explicitly settle allowed Student syntax, trimming/normalization,
length/size bounds, unanswered representation, and invalid-input handling,
including whether malformed payloads are rejected or persisted as zero-credit
answers. Do not reuse the authoring validator for Student input by assumption.

### Exact score representation

Score persistence, API representation, rounding, and display formatting must be
documented before the grading API is frozen. This is still a contract gate.

Inspection note: the current Quiz Markdown contract requires exactly four
statements per matrix, so current matrix credit is in exact quarters. A percentage
can still repeat, for example one earned weight out of three questions. The
contract must preserve earned/possible weights without intermediate rounding and
specify percentage/display formatting. Decimal weights or numerator/denominator
representations are candidates, not accepted contracts. Exact numeric-answer
comparison and score representation are separate concerns; choosing `BigDecimal`
for the former does not settle the latter. This policy does not change Quiz's
four-statement authoring grammar.

## Normative grading examples

These are grading-engine examples with one maximum weight per question. They
define numerical outcomes, not wire DTOs, input widgets, a percentage display
format, or permission to disclose grading keys through a student endpoint.
Values below are interpreted answers after the eventual accepted submission
validation contract. Malformed HTTP requests may be rejected by that contract;
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

Wire-level duplicate/unknown option handling remains part of submission
validation; set equality is not an instruction to silently repair a payload.

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
`2.5` below does not approve a Student token grammar or a non-four-character
input widget. That compatibility choice remains GRADE-07.

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
No intermediate calculation is rounded; final presentation remains GRADE-08.

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

Allowed attempt count, exact autosave revision/conflict semantics, submission
cutoff, and treatment of in-progress Attempts at Publication closure are **not
approved**. No dependent behavior may be inferred from `CLOSED` preventing new
Attempts or from baseline timeout diagrams.

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
[ADR-008](../decisions/ADR-008-service-to-service-authentication.md). The exact
handoff endpoint, service scope, and source-ownership authorization contract
still require documentation. Active delivery/grading uses Assessment's snapshot.

If critical integration events are introduced, use RabbitMQ, transactional
outbox or equivalent recovery, and idempotent consumers. This policy does not
freeze an event schema or authorize Classroom gradebook integration implicitly.

## Repository inspection and contract gates

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

| Audit                           | Accepted source truth                                                                                                                                                                                                                                                                                                                                                                                                   | Remaining gap and dependency                                                                                                                                                                                                                                                                      |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A. Student eligibility/access   | [Assessment product: Audience](../product/assessment.md#audience), [MVP plan: Assessment Publication / delivery](../development/mvp-plan.md), and [roles/personas](../product/roles-and-personas.md) accept authenticated public access and class-restricted concepts in the broader MVP. Class access requires membership; roles alone do not establish membership. Wave 3 requires an authenticated eligible Student. | **OPEN — LEADER, ASSESS-14:** choose the bounded Wave 3 eligibility/discovery/access path. W3-A access fields and W3-B start depend on it. Full Classroom orchestration and guests are not silently added.                                                                                        |
| B. Attempt count                | [Assessment product: Assessment publication](../product/assessment.md#assessment-publication) mentions maximum attempts but specifies no value. The approved Wave 3 guardrails explicitly leave count open.                                                                                                                                                                                                             | **OPEN — LEADER, ASSESS-10:** count/retry entitlement; W3-B start/resume. Concurrent-start locking is an engineering detail after entitlement is chosen.                                                                                                                                          |
| C. Closure with active Attempts | ASSESS-01 blocks new Attempts once `CLOSED`; ASSESS-04 permits eligible submitted owner review after closure. Neither decides ongoing work.                                                                                                                                                                                                                                                                             | **OPEN — LEADER, ASSESS-13:** continue, finalize, or another explicit treatment. Blocks W3-A close semantics and W3-B save/submit/cutoff; consider review disclosure while other Attempts remain active.                                                                                          |
| D. Autosave concurrency         | [AGENTS.md](../../AGENTS.md), [business rules 29–32](business-rules.md#attempt-correctness), and [assessment Attempts](../product/assessment.md#attempts) require stable order, stale-write prevention, and immutable submitted answers.                                                                                                                                                                                | **OPEN — ENGINEERING, ASSESS-11:** revision scope, duplicate retries, conflicts, reconciliation; W3-B API/client freeze. The invariant is already accepted.                                                                                                                                       |
| E. Submit retry/finalization    | [AGENTS.md](../../AGENTS.md) and [business rules 31–32](business-rules.md#attempt-correctness) require idempotency and coherent submit/grading/result persistence. ASSESS-03 requires completed grading for score visibility.                                                                                                                                                                                           | **OPEN — ENGINEERING, ASSESS-16:** request/response replay, concurrent save/submit, persisted finalization identity, validation and retry recovery. W3-B/results depend on this; cutoff and closure are separate Leader choices.                                                                  |
| F. Student numeric input        | GRADE-05 accepts exact decimal equality. [ADR-013](../decisions/ADR-013-numeric-fill-format.md) and [Quiz Markdown](quiz-markdown-spec.md#four-character-answer) constrain the published correct-answer token.                                                                                                                                                                                                          | **OPEN — LEADER/CONTRACT, GRADE-07:** Student grammar, normalization, unanswered/invalid input behavior. Blocks W3-B numeric submission and grading integration, not preservation of published keys.                                                                                              |
| G. Score representation         | Equal maximum weights, deterministic totals, and no intermediate rounding are accepted. [Coding standards](../development/coding-standards.md#persistence) require decimal score arithmetic.                                                                                                                                                                                                                            | **OPEN — ENGINEERING, GRADE-08:** exact weights/storage/API and policy identifier. **OPEN — LEADER/CONTRACT:** rounding/display convention if a percentage is shown. Blocks grading/result API freeze; no re-vote on equal weights.                                                               |
| H. Time limits/deadlines        | [Assessment: Time configuration](../product/assessment.md#time-configuration) permits unbounded ordinary non-proctored availability/duration and forbids retroactive shortening after Attempts begin; [business rules 24–28](business-rules.md#assessment-time-mutation) make server time authoritative.                                                                                                                | **OPEN — LEADER, ASSESS-17/12:** which allowed timing variant ships in Wave 3 and its submission cutoff/timeout rules. W3-A time fields and W3-B finalization depend on it. Baseline timeout diagrams are not approval of specific semantics.                                                     |
| I. Quiz acquisition/integration | [ADR-003](../decisions/ADR-003-quiz-publication-model.md), [ADR-008](../decisions/ADR-008-service-to-service-authentication.md), and [ADR-014](../decisions/ADR-014-access-token-principal-and-authority-contract.md) accept self-contained snapshots, direct internal REST with Client Credentials, and USER/SERVICE separation. Existing Quiz endpoints are teacher USER only.                                        | **OPEN — ENGINEERING, ASSESS-15:** internal route/scope/schema, service registration/config, trusted source-owner enforcement, errors, and snapshot atomicity. W3-A opening depends on this. Broader source sharing would need a separate product decision.                                       |
| J. Results/teacher review       | ASSESS-03/04 and [roles/personas](../product/roles-and-personas.md) permit Teachers to view owned Publication results, Students to view only their own results, and post-closure own submitted answer review. [API conventions](api-conventions.md) require backend authorization and explicit DTOs.                                                                                                                    | **OPEN — ENGINEERING:** result/list/review DTOs, errors, pagination, and backend projection tests under ASSESS-15/16. No permission is added for other Teachers, Student cross-owner reads, Admin bypass, or pre-closure learner answer review. Classroom gradebook integration remains separate. |

Existing **ID-05** (Redis revocation outage behavior) remains a conditional
security dependency for protected-service wiring. The near-immediate revocation
requirement is accepted by [ADR-007](../decisions/ADR-007-api-gateway-and-authentication-topology.md);
default JWT verification alone must not be reported as that implementation.
Do not silently settle ID-05 or make it a new scoring-policy question.

### W3-A — Publication API/data contract before coding

Document and accept:

1. Routes, commands, DTOs, error/status behavior, ownership rules, pagination,
   lifecycle transition retries/concurrency, and allowed draft edits.
2. The MVP audience/eligibility/access path; do not assume guest participation
   or classroom membership integration from broader product baseline examples.
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
| `POST /api/assessments/publications/{publicationId}/close` | Owner-authorized transition/retry rules and resolution of ASSESS-13; do not silently finalize or preserve active Attempts. No reopen operation in MVP.                                                                                                                                        |
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

**W3-A readiness:** inventory is ready for contract review, not feature coding.
ASSESS-14, ASSESS-13 for close, and ASSESS-17 if timing fields are included need
Leader choices. ASSESS-15 engineering contracts and conditional ID-05 security
handling need review. W3-B count/numeric/submit contracts need not block unrelated
W3-A design work, but must be resolved before their dependent implementation.

### W3-B — Attempt/autosave/submit contract before coding

Resolve and record [open questions](../open-questions.md) for attempt count,
autosave revisions/conflicts, submission cutoff, and closure handling. Freeze
start/resume/answer/submit request and response semantics, retries/concurrency,
validation for each question type, persistence/transaction boundaries, and
student-safe delivery. Resolve Student numeric input compatibility explicitly.

### Grading/results contract before coding

Freeze exact scoring/storage/API/display representation and the version 1 policy
identifier, then result/review DTOs and authorization. Keep accepted scoring and
visibility fixed; a contract must not weaken policy for implementation convenience.

## Sequence and evidence required

1. Reconcile documentation and accepted decisions.
2. Freeze W3-A Publication API/data contracts.
3. Implement Publication backend/frontend.
4. Freeze W3-B Attempt/autosave/submit contracts.
5. Implement Attempt backend/frontend.
6. Implement grading, results, and visibility against frozen contracts.
7. Run complete real-topology E2E.
8. Close Wave 3 only after its Definition of Done is met.

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

AI/manual grading, negative marking, advanced scoring, randomized question banks,
full realtime supervision, Proctoring/LiveKit, advanced Classroom orchestration,
rich analytics, answer review before closure, and retrospective automatic
regrading are excluded. Broader baseline features do not expand Wave 3 scope
without an explicit accepted decision.
