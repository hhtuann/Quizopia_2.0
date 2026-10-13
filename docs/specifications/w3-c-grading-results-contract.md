# W3-C — Deterministic grading, results and review

Status: **ENGINEERING DIRECTION APPROVED WITH CONDITIONS (EC79-01..04); product policy APPROVED; internal W3-C1 implementation FOR REVIEW; Student/results/review HTTP runtime NOT YET IMPLEMENTED; production activation NOT AUTHORIZED.**

[ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md) records Leader policy.
W3-A pins grading_policy_version=1 on an immutable snapshot; merged W3-B1 supplies
V4 storage. The [W3-C1 internal implementation](../architecture/w3c1-grading-finalization-implementation.md)
now supplies actual grading/manual/timeout finalization for independent review.
Student submission, results and detailed review HTTP APIs remain unavailable.
The future HTTP portions of these contracts accompany the
[lifecycle](w3-b-attempt-lifecycle-contract.md),
[persistence](../architecture/w3b-w3c-persistence-concurrency-design.md) and
[proposed OpenAPI](proposed/w3b-w3c.openapi.yaml).
Deferred: manual/AI grading, negative marking, configurable weights, new policies,
retrospective regrading and gradebook events. Conditional engineering sign-off
is in ADR-015; independent read-only amendment review, scoped implementation
review/Leader merge approval, exactness/security/topology tests and result/answer
retention review remain required.

## Pinned policy and total computation

The engineering identifier is `QUIZOPIA_GRADING_V1`; stored integer version 1 is
its immutable mapping. Snapshot schema/content version 1 are independent version
namespaces. Attempt and result pin the same snapshot identity and grading version.
Grade entirely from Assessment's protected snapshot in the authoritative local
transaction. No Quiz call, network grader or intermediate rounded percentage.

Each of Q snapshot questions has possible weight exactly 1; Q must be positive.
All questions, including unanswered, contribute to possible total Q. Current
content v1 matrices have exactly four A-D statements, so per-question earned
values are 0, 0.25, 0.5, 0.75 or 1. Accumulate with exact BigDecimal (or integer
quarter units), never double. Persist unconstrained PostgreSQL `numeric` earned
and possible totals/per-question weights, with v1 finite/range/quarter checks;
avoid a scale-constrained column that silently rounds a future value. Store exact
per-question outcomes privately for deterministic audit/review. Results are
write-once, with random UUID and immutable submitted-answer UUID/digest.

| Question type     | Canonical answered value                   | v1 earned weight                                                          |
| ----------------- | ------------------------------------------ | ------------------------------------------------------------------------- |
| SINGLE_CHOICE     | One known A-D label                        | 1 iff that option is correct, else 0                                      |
| MULTIPLE_CHOICE   | Unique known A-D label set                 | 1 iff exact correct set, else 0; no partial/negative credit               |
| TRUE_FALSE_MATRIX | A-D mapped individually to boolean or null | Count of non-null judgments equal to statement truth divided exactly by 4 |
| NUMERIC_FILL      | Four-character token                       | 1 iff `new BigDecimal(token).compareTo(new BigDecimal(key)) == 0`, else 0 |

Null single choice/numeric, empty multiple-choice set and all-null matrix are
unanswered and earn zero. Matrix null is distinct from false; all-false keys and
all-false Student judgments can earn full credit. Invalid domain answers in pure
grading fixtures earn zero under GRADE-01/05; malformed HTTP answers are rejected
before storage and never silently accepted as zero. A corrupt/unsupported pinned
snapshot is 503 GRADING_UNAVAILABLE with no partial terminalization; do not
substitute a placeholder grade or choose another policy.

## Exact wire validation

Answer object is a tagged union with no unknown fields. Tag must equal the
snapshot question type:

```text
{"type":"SINGLE_CHOICE","optionLabel":null}
{"type":"MULTIPLE_CHOICE","optionLabels":["A","C"]}
{"type":"TRUE_FALSE_MATRIX","judgments":{"A":true,"B":null,"C":false,"D":null}}
{"type":"NUMERIC_FILL","value":"2.50"}
```

The block is four separate examples, not one JSON document. Matrix always carries
all four keys; selection duplicates, unknown labels, mismatched tags, arrays in
single choice, string booleans, numeric JSON values for NUMERIC_FILL and duplicate
JSON keys are 400 INVALID_REQUEST. Question references are only local integer
number and A-D labels. No client-selected question types/IDs can redirect grading.

GRADE-07 trimming removes only outer ASCII space, HT, LF, VT, FF and CR
(U+0020, U+0009..U+000D). Null or a string empty after this trim becomes unanswered
null. Nonblank token must have length 4 and match
`(?:-?[0-9]+(?:[.][0-9]+)?|-[.][0-9]+)` as a full string. This preserves ADR-013's
actual grammar, including `-.25` (dot is not the first character); do not silently
add a digits-before-dot restriction absent from that ADR and existing validator.
No plus, comma, exponent, Unicode digit/space conversion, internal whitespace,
padding, truncation, floating-point conversion or spelling normalization.
The input string has a proposed defensive 64-character wire cap before trim;
that bound is engineering input protection, not expanded accepted numeric syntax.

| Input                                                                       | Outcome                            |
| --------------------------------------------------------------------------- | ---------------------------------- |
| `2.50`, `-2.5`, `1234`, `0001`, `-0.5`, `-.25`                              | Accepted, exact spelling preserved |
| ASCII-trimmed `2.50`                                                        | Store `2.50`                       |
| null, empty, ASCII-whitespace-only                                          | Unanswered null                    |
| `2.5`, `1`, `12345`, `+2.5`, `2,50`, `.250`, `250.`, `1e03`, Unicode digits | 400, no mutation                   |

Numerical equality remains independent from spelling: accepted `0001` and `1.00`
are equal, and `-.25` and `-0.25` illustrate equal decimal values but the latter
is not a permitted five-character Student token. Historical examples using `2.5`
or `1` are arithmetic illustrations, not accepted wire inputs.

## Finalization transaction

For MANUAL_SUBMIT, BOUNDED_TIMEOUT and TEACHER_NOTICE_CUTOFF:

1. Acquire Publication/workflow/Attempt locks in the global order, validate time
   and ACTIVE status; successful manual receipt replay precedes new mutations.
2. Canonicalize a complete ordered answer set from submitted full replacement or
   latest committed answers, including explicit unanswered for every question.
3. Persist write-once submitted_answers row (answer schema v1, SHA-256 digest of
   canonical UTF-8 answer document), deterministic per-question grade and exact
   totals, result row and finalization audit.
4. Update Attempt to SUBMITTED with result/submitted-answer references, terminal
   reason, finalization timestamp and revision+1; append manual replay receipt
   or closure membership progress in the same transaction. Return only after commit.

All changes roll back together on constraint/grader/persistence failure. Unique
Attempt result/submitted-answer constraints and terminal immutability forbid
second grading. A matching manual retry reads the stored result rather than
recomputing, even across deploy/restart. Distinct reasons remain MANUAL_SUBMIT,
BOUNDED_TIMEOUT and TEACHER_NOTICE_CUTOFF, with the Student owner unchanged.
ABANDON has no grade/result/submitted-answer row.

## Serialization and presentation

Earned/possible JSON values are exact strings using `stripTrailingZeros().toPlainString()`;
zero is `"0"`, never `-0`, exponent or JSON number. Per-question review follows the
same representation. Persisted numeric values need no trailing-zero significance;
Student numeric answer tokens do. Totals `"2.75"` and `"4"` remain authoritative.

`percentageDisplay = earned * 100 / possible`, rounded **only here** to scale 2,
HALF_UP, plain decimal string with exactly two fractional digits. Examples:
2.75/4 → `"68.75"`; 1/3 → `"33.33"`; 2/3 → `"66.67"`; 0/Q → `"0.00"`.
Do not persist a rounded percentage as authoritative score or compare aggregates
using it. Result DTO is total-only regardless of OPEN/CLOSED; details require
explicit review API. No correct counts, per-question weights, timing side channel
with correctness fields, explanations or answer keys in result/save/error DTOs.

## Aggregation

Query this authorized Student/Publication's SUBMITTED Attempts joined to immutable
results. All share one snapshot/possible weight; choose highest earned, tie by
lowest ordinal then result UUID for deterministic display. Return selected
result/Attempt identity and totals. If none graded, `bestResult=null`; ABANDON is
excluded, never counted as zero. Count/read results from PostgreSQL, not a
Redis scoreboard or cached rounded score. An active retake does not hide the
previous best result. No automatic regrading of history or overwrite of prior results.

Result and aggregate reads use coherent PostgreSQL statement snapshots; a read
concurrent with commit can observe the prior complete aggregate or the newly
committed complete result. It cannot expose an uncommitted/partial grade.
Teacher results paginate Attempts (including ACTIVE and ABANDONED with null
result), and expose selected Student IDs and totals only after owned-Publication
checks. Aggregate per Student can be composed from these authorized records;
no public Student directory, global leaderboard or Student cross-owner list.

## Review boundary

GET owner review requires current STUDENT admission, Attempt ownership and current
CLASS membership where applicable, **durably CLOSED** Publication and SUBMITTED
Attempt with a complete result. OPEN gives 409 REVIEW_NOT_AVAILABLE regardless
of window/deadline/cutoff. ABANDONED gives the same conflict even after CLOSED;
wrong owner/missing Attempt is 404. A submitted Attempt does not grant access to
an abandoned sibling or another Student's work.

Review projects question stem, A-D markdown, owner's answer, protected correct
answer in the same typed shape, per-question earned/possible weights and optional
explanationMarkdown. Never serialize raw sourceSnapshot or whole protected
snapshot. Teacher result list supplies total scores; a Teacher answer-review or
analytics endpoint is deferred rather than implicitly authorized here.

Golden grading fixtures cover all four types, empty/partial/all-false matrices,
exact set equality, numeric spellings/zeros/signs, exact fractional totals,
HALF_UP boundary presentation and unchanged result replay. Compatibility of a
future policy with nonterminating rational per-question weights requires a new
versioned rational storage/API representation and migration before activation;
unconstrained numeric alone is not permission to approximate 1/3 or silently
reinterpret stored v1 results. Unknown versions fail closed.
