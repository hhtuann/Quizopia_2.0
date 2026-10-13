# State Machines

Status: **Baseline v0.1 with explicit TBD sections**

## User registration

```mermaid
stateDiagram-v2
    [*] --> PENDING_EMAIL_VERIFICATION
    PENDING_EMAIL_VERIFICATION --> ACTIVE: OTP verified
    PENDING_EMAIL_VERIFICATION --> PENDING_EMAIL_VERIFICATION: OTP resend
    ACTIVE --> DISABLED: admin/security action
    DISABLED --> ACTIVE: permitted re-enable
```

Account lockout/session states may be modeled separately.

## Classroom manual invitation

```mermaid
stateDiagram-v2
    [*] --> PENDING_ACCOUNT: Gmail has no account
    [*] --> ACTIVE_MEMBERSHIP: matching verified user exists
    PENDING_ACCOUNT --> ACTIVE_MEMBERSHIP: matching Gmail verified later
    PENDING_ACCOUNT --> CANCELLED: teacher cancels invitation
    ACTIVE_MEMBERSHIP --> REMOVED: teacher removes / member leaves per policy
```

Join-code approval states are TBD.

## Quiz content

Conceptual lifecycle:

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> PUBLISHED_VERSION: publish immutable snapshot
    PUBLISHED_VERSION --> DRAFT: create/edit later draft
    DRAFT --> PUBLISHED_VERSION: publish next immutable version
```

A published version itself is immutable; the arrow back to DRAFT represents creation of a later draft, not mutation of the published row.

## Publication

Wave 3 Assessment Publication lifecycle is accepted:

```mermaid
stateDiagram-v2
    [*] --> DRAFT: teacher prepares
    DRAFT --> OPEN: finalize snapshot atomically
    OPEN --> CLOSED: owner closes only when all Attempts terminal
```

The QuizVersion reference cannot change after opening. No return to `DRAFT` or
reopening `CLOSED` is in MVP. QuizVersion publication does not automatically open
an Assessment Publication. All Attempts use the snapshot pinned on opening.

This resolves ASSESS-01/02 for Assessment Core; it does not define Practice
publication states or freeze detailed timing/cutoff behavior. W3-A accepts bounded
scheduling separately from lifecycle. See [accepted policy](assessment-core-policy.md)
and [W3-A scope](w3-a-publication-scheduling-monitoring-policy.md).

Do not create read-triggered hidden Publication transitions like the legacy
session lifecycle. The proposed Attempt resume explicitly reconciles due
terminalization; it never closes Publication or releases review early.

For Wave 3, represent scheduling as separate eligibility conditions, not additional
Publication states. Opening and participation start are distinct; participation
window end alone is neither durable closure nor automatic Attempt finalization.
LD-01, amended by ADR-015, accepts inclusive-start/exclusive-end new creation
and positive start-plus-duration deadlines or null duration 0 deadlines. LD-04 rejects CLOSE
while any Attempt is authoritatively active, with a documented conflict response;
only terminal Attempts permit Teacher closure. Overdue handling and start/
finalization races require transactional contracts; no unauthorized active
Attempt may remain in CLOSED after concurrent start/CLOSE. Explicit lifecycle
commands remain retry-safe; expiry/finalization and concurrency require W3-B and
engineering contracts. No automatic closure is introduced. ADR-015 adds only the narrow C1..C10
Teacher-notice cutoff finalization, retaining the ordinary all-terminal close rule.

## Assessment attempt

Current accepted product is [ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md);
state names/transaction details below are **proposed engineering**, not runtime.
W3-A has no Attempt implementation.

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: START or explicit RETAKE
    ACTIVE --> ACTIVE: revision-controlled SAVE
    ACTIVE --> SUBMITTED: atomic manual submit and grade
    ACTIVE --> SUBMITTED: saved-only bounded timeout
    ACTIVE --> SUBMITTED: TEACHER_NOTICE_CUTOFF
    ACTIVE --> ABANDONED: explicit Student abandon, ungraded
```

SUBMITTED already includes exact result/final-answer persistence. ABANDONED is
terminal/ungraded, consumes finite entitlement, excluded from aggregate and own
submitted-review eligibility. Timeout/closure grade only committed answers. New
mutations require strictly pre-deadline/pre-confirmed-cutoff serialized server time.
The [lifecycle](w3-b-attempt-lifecycle-contract.md) and
[closure](w3-b-closure-notification-contract.md) specify durable replay/recovery.
Request/cancel/complete closure workflow is separate from Publication state:
PENDING→FINALIZING→COMPLETED; PENDING may CANCEL or resolve early when all terminal.
Workers never transition Publication to CLOSED; owning Teacher COMPLETE/ordinary
CLOSE may do so only after authoritative all-terminal checks. Cancelled request
never becomes live again; replacement establishes new notices and 48-hour cutoffs.

Do not add unused `GRADED`/`RELEASED` states unless a real separate lifecycle requires them.

Result visibility is controlled by publication policy rather than by placeholder states with no behavior.

Wave 3 score visibility requires successful submission and completed grading;
submission alone is insufficient. Correct answers/explanations require a `CLOSED`
Publication and an eligible submitted owner. Backend response authorization must
enforce both gates without inventing a separate persisted lifecycle prematurely.

## Proctoring session

Wave 3 includes optional eligible CLASS Activity Evidence and LiveKit camera
monitoring under the [W3-A Leader decision](w3-a-publication-scheduling-monitoring-policy.md).
The session below is distinct from Publication lifecycle and new-start scheduling.
Participation-window end alone is not Attempt end or a review-release transition.
LD-02 requires an explicit CLASS monitoring choice before OPEN, immutable upon
OPEN; runtime capture requires eligible active Attempts and W3-M1/M2 contracts.
Detailed expiry/finalization, transactional close handling, token revocation and
camera-failure behavior remain OPEN; no new mandatory session state is approved
by this diagram.

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: proctored attempt starts
    ACTIVE --> ENDED: submit or deadline
    ENDED --> RETAINED: evidence retained
    RETAINED --> PURGED: retention expires
    PURGED --> [*]
```

Exact storage-cleanup retry states are implementation details.

## Community post

Baseline conceptual lifecycle:

```mermaid
stateDiagram-v2
    [*] --> PUBLISHED
    PUBLISHED --> HIDDEN: moderation
    PUBLISHED --> DELETED: author/admin deletion policy
    HIDDEN --> PUBLISHED: moderation restore
```

Draft-post behavior is TBD.
