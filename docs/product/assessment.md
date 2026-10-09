# Assessment

Status: **Baseline v0.1 with accepted Wave 3 MVP policy**

## Wave 3 Assessment Core

The [accepted Assessment Core policy](../specifications/assessment-core-policy.md)
governs Wave 3 after Wave 2 closure at PR #71. Assessment owns Publication
`DRAFT → OPEN → CLOSED`, finalizes a self-contained immutable snapshot atomically
on opening, and pins grading policy version 1. Opened Publications cannot return
to draft or change QuizVersion; closed Publications cannot reopen in MVP.

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

The sections below describe the broader product baseline. Configurable visibility,
guest access, Classroom orchestration, timing, attempt limits, and shuffle examples
are not approvals of Wave 3 contracts. Remaining contract gates and implementation
sequence are in the accepted policy and `docs/open-questions.md`.

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

A class publication is discoverable through the learner's classroom UI and does not require the learner to save/share the public link.

## Time configuration

For ordinary, non-proctored assessment:

- availability may be unbounded;
- attempt duration may be unbounded.

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
