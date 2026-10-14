# Assessment

Status: **Leader product policy APPROVED; engineering direction APPROVED WITH CONDITIONS (EC79-01..04); B1/C1/B2 merged and B3 implemented default disabled, pending independent security/runtime review and Leader merge approval; production activation NOT AUTHORIZED.**

Current runtime, exact routes, verification and rollout gates are recorded in the
[W3-B3 implementation evidence](../architecture/w3b3-closure-notification-implementation.md). Historical baseline inspection and
proposed SQL below describe the contract freeze; executable Flyway V1–V5 and the
implemented service OpenAPI govern the delivered adapters. Accepted product policy
and the indefinite blocked-recipient limitation are unchanged.

## Current Wave 3 Assessment Core

[Assessment Core](../specifications/assessment-core-policy.md), W3-A LD-01..05
and the later [ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md) govern
this milestone. W3-A PR #78 implements preparation/opening/snapshot/entry, not
Attempt/CLOSE/grading/inbox runtime. The
[W3-B/C engineering pack](../architecture/w3b-w3c-contract-review-evidence.md)
has Leader engineering approval with conditions EC79-01..04. Merged W3-B1 supplies real persistence/configuration;
[W3-C1](../architecture/w3c1-grading-finalization-implementation.md) adds internal
grading/finalization. Merged B2 supplies Student submission/results/review; B3 now
supplies closure/inbox and narrow Identity integration for independent review. Real security/runtime acceptance and Leader
implementation merge/release gates remain.
Production activation is not authorized.

Publication lifecycle is DRAFT→OPEN→CLOSED, immutable self-contained snapshot and
pinned policy 1 upon OPEN. Both CLASS/PUBLIC have bounded startAt<endAt; new
START/RETAKE requires OPEN, eligible current Student and inclusive start/exclusive
end. Individual duration is0/unlimited (null deadline) or integer5..240 minutes
(deadline=start+duration); existing ACTIVE work may continue after endAt subject
to deadline/valid closure cutoff. No post-OPEN bounded/unlimited mode switching.
maxAttempts 0/unlimited or 1..10 freezes OPEN; existing default 1. One ACTIVE Attempt
per Student/Publication; START resumes/returns latest terminal, explicit RETAKE
creates a separate entitled Attempt. ABANDON is explicit, irreversible/ungraded,
consumes finite entitlement and grants no aggregate zero or submitted-owner review.

CLASS requires current authoritative membership; Teacher assignment authority
remains Classroom-owned. PUBLIC current authenticated STUDENT via a private direct
locator, no membership/guest/directory/password. No new owner self-participation
permission or prohibition. LD-05 independent Gateway/downstream current admission
and fail-closed outage/recovery stay mandatory; no fallback or outage logout.

Ordinary CLOSE rejects persisted ACTIVE Attempts after bounded reconciliation with
sanitized 409. The C1..C10 narrow exception uses Teacher REQUEST after endAt,
committed owner-accessible in-app notice, individual confirmation+48h cutoffs and
Teacher COMPLETE. Workers may grade saved answers after cutoff but never close
Publication. Cancellation before first forced finalization, fresh replacement
notices, pending schedule/new-creation freeze and durable audit/recovery are required.
EndAt alone never finalizes/closes/releases answers. Real inbox is an implementation
dependency; Identity OTP email is insufficient.

SAVE uses question-scoped typed answer, Attempt-wide revision and durable replay
identity; SUBMIT/timeout/closure commit final answers/exact grade/result/terminal
state coherently. Student numeric input is exactly four characters after ASCII
outer trim under ADR-013, malformed nonblank 400, blank/null unanswered. All four
question types retain equal weights/exact v1 grading/no intermediate rounding;
score strings exact, display to 2 places HALF_UP only. Highest graded Attempt is aggregate.
Students see total-only score after grading; only own submitted Attempts get
keys/explanations after durable CLOSED. Teachers see owned-Publication results.

PUBLIC monitoring DISABLED; unlimited CLASS cannot ENABLE the bundle. Bounded
CLASS requires explicit monitoring choice before OPEN, immutable thereafter.
Browser Activity plus LiveKit camera remain required separate W3-M outcomes with
privacy/refusal/loss/retention/security gates; AI monitoring deferred after Wave 3.
The broader examples below do not add unsupported Wave 3 configuration/features.

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
expose safe projections. W3-A locator/entry contracts are implemented in ASSESS-15;
W3-B/C Attempt extensions are proposed for review. CLASS membership is authoritative,
including shared-URL entry.

A class publication is discoverable through the learner's classroom UI and does not require the learner to save/share the public link.

## Time configuration

For ordinary, non-proctored assessment:

- availability may be unbounded;
- attempt duration may be unbounded.

Wave 3 requires a bounded participation start window, while ADR-015 now permits
unlimited individual duration 0 or bounded5..240 minutes. Broader unbounded
availability and Practice remain deferred. Existing authoritative deadlines stay
pinned; extensions apply to future starts, mode switching after OPEN is not
approved, and pending closure freezes extensions. See the proposed pack for
exact cutoff/replay/terminalization contracts.

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
W3-B/C route extensions remain proposed for review; W3-A entry is implemented.

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
