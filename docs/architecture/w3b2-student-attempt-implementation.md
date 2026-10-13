# W3-B2 Student Attempt implementation

Status: **Implemented, default disabled, pending independent implementation review and Leader merge approval.**
Baseline: `01885df185c202cec858d403e5b24b56768bfb7d` (merged PR #81).
Accepted product policy remains [ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md).
Engineering direction is APPROVED WITH CONDITIONS under EC79-01..04. This implementation
and its local tests do not authorize production activation.

Assessment owns Attempts, answers, receipts, grading, results and lazy deadline
reconciliation. Gateway owns edge admission/routing. Identity and Classroom remain
the independent account/role and current CLASS membership authorities. No service
reads another service's database in production code. The only schema extension is
the explicitly authorized additive [V4.1 capacity correction](w3b2-capacity-correction-evidence.md).
V1–V4 are unchanged; V5 remains reserved for B3. No event contract is added.

## Implemented HTTP inventory and frontend handoff

The [implemented OpenAPI](../../services/assessment-service/contracts/publications.openapi.yaml)
contains the seven retained W3-A operations and these nine B2 operations. The frozen
[contract pack](../specifications/proposed/w3b-w3c.openapi.yaml) records implementation
status per operation; Teacher/closure/inbox/recipient operations remain proposed.

Let P be `/api/assessments/publications/{publicationId}` and A be
`/api/assessments/attempts/{attemptId}`.

| Operation                                | Request                         | Response                             | Required switches                    |
| ---------------------------------------- | ------------------------------- | ------------------------------------ | ------------------------------------ |
| POST P/attempts/start                    | StartRequest, Idempotency-Key   | 201 new / 200 selected AttemptView   | ATTEMPTS_ENABLED                     |
| GET A                                    | no body/query                   | 200 AttemptView                      | ATTEMPTS_ENABLED                     |
| POST P/attempts/retake                   | StartRequest, Idempotency-Key   | 201 new / 200 replay AttemptView     | ATTEMPTS_ENABLED                     |
| POST A/questions/{questionNumber}/answer | SaveRequest, Idempotency-Key    | 200 SaveAck                          | ATTEMPTS_ENABLED                     |
| POST A/submit                            | SubmitRequest, Idempotency-Key  | 200 Result                           | ATTEMPTS_ENABLED and RESULTS_ENABLED |
| POST A/abandon                           | AbandonRequest, Idempotency-Key | 200 AbandonAck                       | ATTEMPTS_ENABLED                     |
| GET A/result                             | no body/query                   | 200 Result                           | RESULTS_ENABLED                      |
| GET P/aggregate-score                    | no body/query                   | 200 Aggregate                        | RESULTS_ENABLED                      |
| GET A/review                             | no body/query                   | 200 Review only after durable CLOSED | RESULTS_ENABLED                      |

All three new switches default false independently at Gateway and Assessment.
`PUBLICATION_ENABLED` controls the retained W3-A routes separately. Enabling
`CLOSURE_ENABLED` does not register or authorize B3 routes. Deployment must provide
both services' settings and private authority credentials/topology; this branch
neither deploys nor enables these operations. Dev 2 can derive clients from OpenAPI
for review, but cannot assume an enabled or deployed backend. Frontend PR #77 is untouched.

Each request requires a USER Bearer token with issued and current STUDENT authority.
POST requires one `[A-Za-z0-9_-]{16,128}` Idempotency-Key and JSON UTF-8. UUIDs are
canonical lowercase; question numbers are positive int32; revision and ordinal are
canonical signed-bigint decimal strings. Submit answers are optional; omission
uses saved answers, while presence requires every pinned question exactly once.
`answers:null` is invalid. Exact score values are decimal strings, display percentage
is two decimals HALF_UP, and response instants are UTC Z. Unlimited deadline is null.

Every success and error is `Cache-Control: no-store`. Errors contain only `code`.
Malformed input is 400 INVALID_REQUEST; ownership is masked with 404
ATTEMPT_NOT_FOUND/PUBLICATION_NOT_FOUND, and current CLASS denial may use the existing
CLASSROOM_NOT_ACCESSIBLE contract. State/revision/key/deadline conflicts are 409.
Authority, dependency, database and unsupported grading failures remain sanitized
503; they do not revoke refresh families or trigger automatic logout.

## Independent security and input boundary

Gateway and Assessment each verify RS256 JWT/issuer/timestamps, USER principal type,
issued STUDENT role and fresh LD-05 Identity admission. Existing private OAuth SERVICE
clients/scopes and bounded authority transport are reused. There is no positive
eligibility cache, generic introspection or JWT-only fallback. The response remains
bound to the exact USER token and required role. Direct Assessment requests undergo
its own complete admission, even after successful Gateway checks.

DRAFT Publications are masked before Student authorization; OPEN freezes audience and
Classroom identity, preventing an audience change between remote authority lookup and
the local locked operation.

For CLASS, Assessment calls the existing private Classroom membership authority on
every request/replay outside its local transaction. No membership result is cached.
For PUBLIC START/RETAKE, the 43-character private locator must match the path
Publication, including existing selection/replay; CLASS omits locator. Owned Attempt
operations subsequently need no locator. PUBLIC aggregate requires an owned Attempt.
Student identity comes from the verified token, never request JSON. Expiry is checked
again against PostgreSQL time after locks, including C1 manual replay/finalization.

The exact method/path inventories are duplicated technical edge/downstream allowlists,
with parity checks and negative neighbors. Unknown methods, future Teacher/closure/inbox
routes and `/internal/**` remain denied. Identity assertion headers are rejected on
B2 routes; retained W3-A edge header stripping remains unchanged. Duplicate Authorization,
ambiguous paths, duplicate operation headers, unknown query parameters, duplicate JSON
keys, trailing documents, unknown fields and numeric coercion are rejected.

The initial uncompressed request budget is 2,097,152 bytes. Gateway collects at most
that limit after admission and before proxying; Assessment independently reads at most
limit+1 bytes before application mutation, covering Content-Length and chunked input.
Compressed bodies are rejected. JSON depth is bounded at 16, number length at 19 and
string length at the request budget. Numeric answer wire length is capped at the
contract's 64 characters before ASCII trimming and exact four-character grammar.
No valid answer is truncated. See the capacity evidence for compatibility checks.

## Transactions, state and durable replay

All mutation/selection units lock Publication, then Attempt, then write child records.
PostgreSQL `clock_timestamp()` after locks is authoritative. Latest history selection
is bounded with LIMIT 1; finite entitlement uses its contiguous ordinal and V4 guards,
not a Redis count. Unlimited retakes have no invented finite cap.

START checks durable replay, reconciles an overdue bounded Attempt and selects ACTIVE
or latest terminal history. It creates ordinal 1 only with no history and valid OPEN
window/entitlement. It never implicitly retakes. Successful existing selection also
persists its original Attempt identity, so the same key selects that Attempt after
later retakes. RETAKE explicitly requires a terminal predecessor, no ACTIVE Attempt,
OPEN/window/entitlement and a new contiguous ordinal. Creation receipts share
Publication/Student/key scope across both actions. ABANDONED consumes entitlement.

SAVE validates against the pinned snapshot before any deadline mutation. It canonicalizes
multiple-choice set order, stable matrix labels and numeric ASCII outer trim while
preserving numeric token spelling. Each newly accepted save, including an unchanged
answer under a new key, increments the global Attempt revision. It commits answer,
revision and exact original SaveAck receipt together. SAVE/SUBMIT/ABANDON share one
durable key namespace. Changed action/question/revision/canonical answer conflicts;
matching replay returns the original acknowledgment even after terminalization.
Only key digests are persisted, never plaintext keys.

Manual SUBMIT invokes the existing C1 root finalizer outside every store transaction.
Complete replacement, deterministic grading, submitted identity/digest, exact weights,
result, audit, revision and SUBMIT receipt remain one transaction. Saved-only and
all-unanswered grading are real computations, not placeholders. ABANDON preserves saved
answers and atomically records an ungraded terminal Attempt, audit and receipt.
No result or zero score is fabricated for an abandoned Attempt.

Valid new SAVE/ABANDON at the deadline returns a due marker from a read-only unit;
application orchestration then calls the root C1 timeout finalizer and returns 409
only after that transaction commits. C1 SUBMIT's committed DEADLINE_REACHED outcome
is mapped outside its transaction. Invalid requests cause no reconciliation/mutation.
Matching committed operation replay precedes deadline checks. Reads, START, RETAKE,
result, aggregate and review lazily reconcile due Attempts; no worker is needed for
correctness. Due result/aggregate selection is checked under the same Publication
lock. Bounded retry handles concurrent reconciliation; unlimited Attempts have no
bounded timeout and remain ACTIVE after Publication endAt.

## Confidentiality, results and review

Explicit immutable response DTOs expose ordered stems/options and only the owner's
answers. Ordinary delivery never serializes the protected snapshot, source, option
correctness, numeric keys, explanations or per-question grades. SAVE/ABANDON acknowledgments
contain no grade. C1 result mapping exposes only approved total fields, not internal
submitted-answer identity or revision. Aggregate reuses C1's earned-weight descending,
ordinal ascending, result-UUID tie break; an ACTIVE or ABANDONED retake does not hide
a prior best result.

Review checks current authority/owner/membership, SUBMITTED state, a real result and
Publication CLOSED before loading/projection of review details. Pure CLOSED projection
and gate tests cover allowed shape and rejected states. Real PostgreSQL and topology
verify OPEN denial. V4 still forbids ordinary CLOSE: no forbidden database state or
fake notice is manufactured, so successful CLOSED HTTP integration remains a B3 gate.

## Validation and independent review boundary

The full Assessment/Gateway Maven verify commands include Spotless, ArchUnit, existing
W3-A/C1 suites, PostgreSQL 17 Testcontainers and the real topology suites. No suite is
excluded. New local PostgreSQL cases cover START/RETAKE races, finite entitlement,
SAVE/SUBMIT/ABANDON/TIMEOUT races, receipt retry, global revision, deadline/expiry after
observed lock wait, no partial artifacts and malformed-input nonmutation.
Injected acknowledgment loss after an actual PostgreSQL START/SUBMIT commit verifies
that the failed caller receives no success and same-key retry selects the one durable
Attempt/result. This is client fault injection, not a real network/failover experiment.
The populated V4→V4.1 test preserves checksums, snapshots and result identity.

The actual five-service test runs Gateway, Identity, Classroom, Quiz and Assessment
with four PostgreSQL databases and Redis. USER login and SERVICE client credentials
come from the actual Identity server. CLASS membership checks use real Classroom.
It covers both audiences' nine-route journeys, real C1 grades, current role removal,
disabled/unverified accounts, owner isolation, SERVICE/wrong issued role denial,
forged headers, default-off component boundaries, malformed input, body limits,
source limits and key redaction. Actual private transport uses isolated loopback
HTTP explicitly enabled only in test fixtures; production private HTTPS remains a gate.

Self-review is not independent approval. Independent review should inspect baseline
versus final branch, the additive integrity migration, bounded commit validation,
exact allowlists/flags, root-transaction finalizer calls, original receipt selection,
current admission/membership on replay, capacity and CLOSED-only projection.

Remaining gates: independent security/runtime review, Leader merge approval,
production topology/TLS/credentials, deployment-sized migration/WAL/disk sizing,
failover/durability and multi-instance operational recovery, real uncertain-commit
network recovery and release authorization. No benchmark or failover result is invented.

B3 must separately implement ordinary CLOSE, V5 closure evidence, live pending-closure
creation checks, real authenticated Assessment inbox/accessibility confirmation,
Teacher REQUEST/CANCEL/COMPLETE, individual 48-hour cutoffs, durable recoverable batches
and the narrow Identity recipient-eligibility endpoint. Accepted indefinite blocked
closure and EC79 fail-closed guarantees remain unchanged. No B3 behavior is activated here.

## Recorded local verification

Executed on 2026-10-13, Java 21 with PostgreSQL 17.11 Testcontainers and Docker available:

| Check                                                                 | Result                                                                   |
| --------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| Assessment Maven verify, Spotless and ArchUnit                        | PASS — 291 tests, zero failures/errors/skips                             |
| New Student PostgreSQL lifecycle/concurrency/recovery suite           | PASS — 37 cases                                                          |
| C1 atomic finalization regression plus V4.1 adversarial/upgrade cases | PASS — 27 cases                                                          |
| B1 raw PostgreSQL invariants / snapshot association                   | PASS — 14 / 12 cases                                                     |
| Strict Student HTTP parsing / CLOSED projection / capacity            | PASS — 19 / 1 / 2 cases                                                  |
| Gateway Maven verify, Spotless and ArchUnit                           | PASS — 92 tests, zero failures/errors/skips                              |
| Actual five-service Publication/Student topology                      | PASS — 14 cases, including retained W3-A cases                           |
| Actual Identity admission topology regression                         | PASS — 6 cases                                                           |
| Prettier changed Markdown/YAML, OpenAPI structure/local refs          | PASS                                                                     |
| Existing seven W3-A OpenAPI operations/schemas preserved              | PASS — structural comparison to baseline                                 |
| Nine B2 controller operations / edge-downstream inventory parity      | PASS                                                                     |
| Answer-schema fixtures                                                | PASS — 9 valid and 8 invalid fixtures; Java tests also enforce semantics |
| Local Markdown references, scope and credential-signature scan        | PASS                                                                     |
| V1–V4 unchanged / no V5 / git diff --check                            | PASS                                                                     |

Executed module commands (the environment selected the existing local Maven cache):

```sh
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven \
MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' \
bash services/assessment-service/mvnw -B -f services/assessment-service/pom.xml \
  -Dquizopia.build.directory=target/w3b2-verification spotless:apply spotless:check verify \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'

MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven \
MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' \
bash gateway/mvnw -B -f gateway/pom.xml \
  -Dquizopia.build.directory=target/w3b2-verification spotless:apply spotless:check verify \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

Local ignored evidence: each module's `target/w3b2-full-verification.log` and
`target/w3b2-verification/surefire-reports/`. OpenAPI/answer fixtures were validated
using the existing PyYAML/jsonschema/openapi-spec-validator verification environment;
no application dependency was added. No whole-repository/frontend validation or
production failover experiment is claimed. GitHub CI is separate from these local
results and must be checked on the pushed PR.

Focused self-review corrected a HIGH DRAFT audience-authorization race, the HIGH
baseline deferred-validation capacity defect (authorized V4.1), MEDIUM implicit path
parameter binding and unbounded history selection, and LOW security-error response
and diagnostic redaction inconsistencies. No known BLOCKER/HIGH defect remains within
B2 scope. Independent review, CI and the listed runtime/release gates remain required.
