# Assessment

Status: **Baseline v0.1 with accepted Wave 3 Assessment, W3-A and LD-01..05 policy**

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
The follow-up LD-01..05 decisions were recorded on 2026-10-11 after PR #72/#73.
PUBLIC requires authenticated USER with authoritative STUDENT role, Identity
account/token-validity/revocation rules and a shared direct link with a suitably
non-enumerable locator; no Classroom membership is required. TEACHER + STUDENT
may participate through STUDENT under the same rules. Link possession bypasses
no authentication, lifecycle, timing or Attempt eligibility rule. Exact locator/
route/error contracts remain engineering work. No new rule grants or denies an
owner permission to attempt their own Publication.

New starts require OPEN, eligible Student and `startAt <= serverNow < endAt`
(inclusive start, exclusive end). Duration is integer 5–240 minutes inclusive,
with one-minute granularity; deadline is authoritative start plus configured
duration. Opening, schedule eligibility, individual deadlines and CLOSED are
distinct. Window end does not shorten an already-started Attempt, close
Publication or automatically finalize Attempts. Store/compare timezone-safe
instants and convert frontend-local times unambiguously.

Reject OPEN → CLOSED while any Attempt is authoritatively active, with a
documented conflict response. Teacher may close once all Attempts are terminal.
CLOSE must handle overdue Attempts and start/finalization races transactionally;
no unauthorized active Attempt may remain in CLOSED after a concurrent start.
W3-B must ensure expired Attempts eventually become terminal independently of
timely background processing. Automatic closure and force-close are not introduced.

Eligible CLASS Publications may require a disclosed bundle of Browser Activity
Evidence and LiveKit camera monitoring. Those are required Wave 3 deliverables
(W3-M1/W3-M2), with monitoring optional per eligible Publication. PUBLIC
proctoring is prohibited and monitoring must be disabled. CLASS Teachers must
record an explicit choice before OPEN; an unconfigured DRAFT has no implicit
ON/OFF default. The choice is immutable upon OPEN. No additional Activity-only,
AI-enabled or A/B modes are approved. Runtime capture requires eligible active
Attempts and W3-M1/M2 authorization/privacy/failure contracts.
AI behavior analysis is accepted future design,
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
Expiration, cutoff, timeout finalization, saved/unsaved answer treatment, submit
semantics, resumption and response ordering remain W3-B contract gates. Monitoring
representation/UI terminology, timing wire/validation details and transactional
close handling remain engineering work. Window end alone never discloses answers
or explanations. LD-05 requires affected protected Assessment/Classroom/Proctoring
operations to fail closed until required revocation/account authority is
trustworthy; no automatic Identity fallback or degraded authenticated lower-risk
reads are approved. Outage alone must not invalidate refresh credentials, silently
log out or cause refresh loops. Recovery needs trustworthy coverage or durable
reconstruction, not merely Redis health. Security implementation/recovery/
verification and other gates remain in the accepted policies/open questions.

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

This broader future possibility does not approve Wave 3 guest access. LD-03
requires authenticated USER with authoritative STUDENT and a shared direct link;
no searchable directory, anonymous guests, passwords or anonymous-session
identity is required. Teacher management remains owner-authorized; Student APIs
expose safe projections. Exact locator/route/error contracts remain open under
ASSESS-14/15. CLASS membership is authoritative, including shared-URL entry.

A class publication is discoverable through the learner's classroom UI and does not require the learner to save/share the public link.

## Time configuration

For ordinary, non-proctored assessment:

- availability may be unbounded;
- attempt duration may be unbounded.

This broader baseline remains future scope; Wave 3 instead requires a bounded
start window and integer 5–240-minute duration for CLASS and PUBLIC. LD-01
accepts inclusive start/exclusive end and authoritative start-plus-duration
deadlines. Timezone-safe instants/unambiguous local conversion are required;
exact wire fields and validation remain engineering work. Configuration changes
must never retroactively shorten existing authoritative deadlines. LD-04 rejects
CLOSE with active Attempts; conflict/concurrency/overdue handling and W3-B cutoff,
expiry/finalization/resumption/response ordering remain open.

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

This section describes deferred future behavior. Wave 3 PUBLIC participation
requires authenticated USER with authoritative STUDENT under LD-03; anonymous
guest participation and anonymous-session identity are not required. Only exact
locator/route/error contracts remain open under ASSESS-14/15.

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
