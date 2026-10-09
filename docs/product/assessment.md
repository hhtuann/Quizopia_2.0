# Assessment

Status: **Baseline v0.1 with accepted Wave 3 Assessment and W3-A scope policy**

## Wave 3 Assessment Core

The [accepted Assessment Core policy](../specifications/assessment-core-policy.md)
governs Wave 3 after Wave 2 closure at PR #71. Assessment owns Publication
`DRAFT → OPEN → CLOSED`, finalizes a self-contained immutable snapshot atomically
on opening, and pins grading policy version 1. Opened Publications cannot return
to draft or change QuizVersion; closed Publications cannot reopen in MVP.

The subsequent [W3-A Leader decision](../specifications/w3-a-publication-scheduling-monitoring-policy.md)
requires both `CLASS` and `PUBLIC`, a bounded participation window for new
Attempt starts, and a positive bounded Attempt duration. CLASS references exactly
one Classroom, with authoritative Teacher authorization and Student membership.
PUBLIC authentication/eligibility and discovery remain open. Opening is distinct
from schedule eligibility; participation-window end is not Publication closure
or automatic finalization of existing Attempts.

Eligible CLASS Publications may require a disclosed bundle of Browser Activity
Evidence and LiveKit camera monitoring. Those are required Wave 3 deliverables
(W3-M1/W3-M2), with monitoring optional per eligible Publication. PUBLIC
proctoring is prohibited. AI behavior analysis is accepted future design,
deferred after Wave 3. See [Proctoring](proctoring.md) for release gates.

Authenticated Students see their own score after successful submission and
completed grading. Correct answers and explanations are available only after
Publication closure to eligible submitted Students reviewing their own Attempts.
Teachers view results for owned Publications. Backend authorization and safe
response representations enforce these rules.

All questions have equal maximum weight: single choice requires the one correct
option, multiple choice requires exact set equality without partial credit,
true/false matrices award equal credit per statement, and numeric fill uses exact
decimal numerical equality. No negative marking. The published four-character
numeric answer grammar is unchanged; Student input syntax remains a contract gate.

The sections below describe the broader product baseline. Guest access,
configurable visibility, advanced Classroom orchestration, unbounded/practice
timing, attempt limits and shuffle examples are not approvals of Wave 3 contracts.
Precise schedule boundaries, duration limits, deadline/expiry/resumption/early
closure and monitoring configuration remain contract gates. The recommended
start-plus-duration deadline formula is not yet frozen. Window end alone must
never disclose correct answers or explanations. Remaining gates and sequence are
in the accepted policies and `docs/open-questions.md`.

## Separation of concepts

Quizopia 2.0 separates reusable quiz content from delivery.

```text
Quiz Draft
    -> immutable QuizVersion
        -> Publication
            -> Attempt
                -> Grade / Result
```

A `QuizVersion` is immutable content.

A `Publication` configures how that immutable version is delivered.

## Assessment publication

Assessment mode is intended for exams, tests, homework, competitions, or other scored submissions.

Configuration includes:

- title/display metadata as needed;
- audience;
- public link identifier when public;
- availability time window (optional for non-proctored assessment);
- attempt duration (optional for non-proctored assessment);
- maximum attempts;
- optional password;
- question shuffle;
- option shuffle;
- score visibility policy;
- answer review policy;
- wrong-answer/correct-answer visibility policy;
- optional proctoring when eligibility rules are satisfied.

## Audience

Baseline audiences:

- `PUBLIC`
- `CLASS`

A public publication may allow guest attempts.

This broader possibility does not approve Wave 3 guest access. Its PUBLIC
authentication/eligibility, sharing and discovery contract remains OPEN under
ASSESS-14. CLASS membership is checked authoritatively, including shared-URL entry.

A class publication is discoverable through the learner's classroom UI and does not require the learner to save/share the public link.

## Time configuration

For ordinary, non-proctored assessment:

- availability may be unbounded;
- attempt duration may be unbounded.

This broader baseline remains future scope; Wave 3 instead requires a bounded
start window and positive bounded duration for both CLASS and PUBLIC. Exact
instant fields, timezone-safe display/storage and inclusive/exclusive start
boundaries require contract freeze. Attempt deadlines are server-authoritative;
window end must not be silently treated as an existing Attempt deadline or durable
Publication closure. Detailed cutoff and manual-close semantics remain OPEN.

After the first learner has started an attempt, time changes must not make an existing attempt unfairly shorter or invalidate already-started work.

Baseline invariant:

- configured availability end may only be extended, not moved earlier;
- attempt duration may only be extended, not reduced.

Any additional time fields with similar semantics must follow the same "no retroactive shortening after attempts start" rule.

## Attempts

Attempt behavior inherits the strongest correctness principles from Quizopia 1.x:

- server-authoritative start/deadline;
- stable question/option ordering snapshot;
- sequence-aware autosave;
- idempotent submit;
- transactional submit + grading;
- no dependence on mutable quiz content during an active attempt.

## Public guest attempts

This section describes broader future behavior. Wave 3 PUBLIC authentication,
guest eligibility and discovery/sharing remain OPEN under ASSESS-14.

A guest may take an eligible public assessment without an account.

Guest identity is not equivalent to a verified user identity.

Therefore:

- guest max-attempt enforcement is best-effort;
- clearing cookies/changing devices can bypass anonymous identity controls;
- strict identity requirements must use authenticated or class-restricted delivery.

## Result/review policies

Use coherent policy values instead of incompatible boolean combinations.

Baseline concepts:

### Score visibility

Examples:

- immediately after submission;
- after publication closes;
- never.

### Review visibility

Examples:

- none;
- learner answers only;
- answers and correctness;
- full explanation.

### Wrong-answer answer-key policy

Examples:

- show correct answer;
- hide correct answer.

Exact enum names are implementation details, but the policy model must prevent contradictory combinations.

## Shuffle

Question/option shuffle must be resolved into a stable attempt snapshot.

A page refresh or reconnect must not reshuffle an active attempt.
