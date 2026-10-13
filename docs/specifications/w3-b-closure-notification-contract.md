# W3-B — Durable notices and delayed Teacher closure

Status: **ENGINEERING DIRECTION APPROVED WITH CONDITIONS (EC79-01..04); C1..C10 product policy APPROVED; runtime PROPOSED/UNIMPLEMENTED, production activation NOT AUTHORIZED.**

W3-A implements OPEN and schedule extension, no CLOSE/workflow/inbox. This design
adds only Assessment closure notices under its existing Publication/Attempt
ownership. See [ADR-015](../decisions/ADR-015-w3b-w3c-leader-policy.md),
[HTTP/OpenAPI](w3b-w3c-http-contract.md),
[grading](w3-c-grading-results-contract.md) and
[persistence/concurrency](../architecture/w3b-w3c-persistence-concurrency-design.md).
Deferred: generic notifications, mandatory email, read acknowledgment, cross-service
inbox and automatic Publication closure. External gates: deploy and verify actual
authenticated inbox routes before request activation; durability/failover/clock,
capacity, monitoring/alerts, retention/deletion, independent security/runtime
acceptance and Leader implementation merge/release gates. Conditional engineering
sign-off is recorded in ADR-015; independent read-only amendment review is pending.

## Ownership and accessibility

Repository inspection finds only Identity verification-email infrastructure; it
has no authenticated durable Student inbox. Do not reuse OTP delivery state as
notice evidence. Proposed `assessment_notifications` lives in Assessment's own
PostgreSQL database, owned by the Attempt Student and joined to durable closure
membership. This is a projection of Assessment's workflow, not ownership of
Classroom announcements or a new service. A general inbox in another service
would require a reviewed ownership ADR plus durable cross-service delivery and
accessibility acknowledgments; it is unnecessary for this bounded mechanism.

Inbox/details require current USER + STUDENT at Gateway and Assessment, and exact
recipient equality. **Do not require live CLASS membership, PUBLIC locator,
OPEN Publication, ACTIVE Attempt, prior entry or a read receipt for inbox access.**
Thus a Student removed from a class can still access their own notice/status,
even though ordinary Attempt operations fail membership authorization. Keep
notification payload free of answers, keys, explanation, peer identity and private
PUBLIC locator. No client-submitted recipient or notification timestamp.

An accessible notice is a persisted PUBLISHED notification with owner ACL, warning
content version 1, reachable enabled authenticated read routes and no workflow
predicate that hides it after cancellation/terminalization. It remains retrievable
in history after it ceases to be actionable. Disabled/misconfigured inbox readiness
blocks notice confirmation and REQUEST activation; workers cannot manufacture a
success by enqueueing email or setting an in-memory flag. Confirm accessibility
using the same owner-scoped repository/projection used by the authenticated API,
without impersonating USER credentials. Route access/security integration is
verified in real topology before enablement; do not infer it from table existence.
Before confirming notice, the worker additionally requires a fresh Identity
recipient-eligibility check, conditionally approved but still unimplemented, as
described in the HTTP contract. It verifies
current ACTIVE/verified/STUDENT authority with complete Redis equality without
borrowing a Student bearer token. Missing/disabled/role-removed recipient is not
confirmed; outage/mismatch retries without a cutoff. This narrow SERVICE-only
Identity capability is a **new implementation dependency**, not an existing
admission endpoint or permission to impersonate USER. A later authority change
has the same documented in-flight snapshot limitation as ordinary admission.

Transient authentication infrastructure outages still fail closed under LD-05;
this design promises published authenticated accessibility, not that a disabled
account or an offline browser can read at every instant. No continuous-uptime
period-reset rule is introduced by the policy.

## EC79-02 — Accepted indefinite blocking and operational evidence

The Leader accepts that an ineligible, disabled or unverifiable Student recipient
can block delayed closure indefinitely. An unconfirmed notice has no valid cutoff;
there can be no closure-driven forced submission of that Attempt. This accepted
operational limitation is not a policy TBD and does not weaken C1 or the existing
Student action/ordinary all-terminal closure rules.

Preserve durable per-Attempt notice evidence and auditable retry/recovery decisions
in restricted workflow audit. Proposed operational observability includes blocked
notice/closure counts, oldest unresolved notice age and retry/recovery outcomes,
using bounded categorical reasons (ineligible recipient, authority unavailable,
inbox inaccessible). Metrics/alerts are access-controlled and contain no Student,
Attempt or Publication identity, answer content or credentials; restricted durable
workflow audit retains only the IDs/times/outcomes needed to explain recovery.
Alert thresholds/capacity require implementation/operational review; no benchmark,
SLA or successful delivery is inferred from a retry or health check.

Never synthesize confirmedNoticeAt, auto-abandon, introduce administrative
force-close or add a silent remediation bypass. Any future remediation requires
a NEW Leader product/security decision. Missing notice keeps the relevant Attempt
unfinalizable by this workflow, regardless of request age or retry count.

## Durable notice protocol (C1, C10)

Use two committed stages so no precommit or merely queued timestamp shortens the
48 hours:

1. Under Publication → closure request → Attempt → member locks, publish the
   complete durable warning with recipient, Publication/Attempt identity,
   Teacher initiation, 48-hour guarantee, pre-cutoff rights, saved-only consequence
   and link to authenticated status. Commit PUBLISHED. Until confirmed, status
   explicitly says notice preparation, `confirmedNoticeAt=null` and
   `closureCutoffAt=null`; no countdown or forced finalization is allowed.
2. A new transaction acquires the same locks, observes the already committed
   notification through its owner-accessible read predicate, verifies readiness, fresh recipient eligibility
   and pending live request, then samples DB `clock_timestamp()`. Assign once
   `confirmedNoticeAt=sample`, `closureCutoffAt=sample + interval '48 hours'`,
   membership NOTICE_CONFIRMED and accessibility evidence version 1. Commit
   these fields atomically. The notice/status projection now includes the exact
   per-Attempt cutoff; never use request time, queued time, email time, read time
   or a timestamp from the first transaction for the period.

The warning was committed and accessible before the confirmation sample; this
conservative anchor guarantees at least 48 hours from accessibility. The exact
cutoff becomes visible with confirmation; the preparatory message never claims
an earlier cutoff. A crash after stage 1 leaves a published warning without a
cutoff and is retried safely; a crash after stage 2 leaves immutable evidence.
Same notification/member uniqueness and locks prevent duplicate notices/cutoffs.
Changing either confirmed timestamp in place is forbidden. Use UTC elapsed 48
hours, not two local calendar days/DST arithmetic.

At render time show an exact owner-specific timestamp, warning and status:
“Your Teacher requested closure of [Publication]. You have at least 48 hours
from notice confirmation, until [cutoff UTC and local display], to resume, save,
submit or abandon while eligible. Unsaved browser changes are excluded. At the
cutoff, your saved answers will be submitted and graded by the system.”
After cancellation show cancelled/nonactionable; after submission/abandonment show
that outcome; after cutoff finalization show reason and own result link. Review
link availability still requires durable CLOSED. There is no acknowledgment API
in the minimal contract and no seen/read dependency on period start.

Optional email can be added with an Assessment-owned transactional outbox and
idempotent delivery, separate encrypted payload/key/retention review and existing
provider-neutral email mechanisms only where appropriate. Email failures do not
block in-app notice or reset a valid cutoff. No email work is required by this pack.
WebSocket may signal inbox changes later; REST remains authoritative after reconnect.

## REQUEST CLOSURE (C1, C4, C6)

Authenticated owning Teacher, fresh TEACHER admission, OPEN and serverNow >= endAt.
Expected Publication revision plus required Idempotency-Key. Under Publication
lock, one pending workflow maximum. Atomic request transaction records request UUID,
Teacher ID, sampled time, increments Publication revision and captures **all then
ACTIVE unlimited Attempts** as closure members with Student identity and pending
notice state. This initial metadata-only INSERT SELECT is atomic; if capacity or
statement timeout prevents capture, roll back request rather than report partial
membership as complete. EC79-03 accepts this atomic-capture trade-off: no
successfully created request may omit a member of the authoritative capture set.
Capacity/transaction failure must roll back the whole REQUEST, not report partial
capture as successful. Actual answer/grading and notices use bounded batches.
Publication's pending workflow blocks all new creation and schedule extension,
so later notice batches cannot miss a newly created Attempt. Bounded Attempts
are not closure members; they retain deadline-based finalization.

Return 201 ClosureView for durable request creation, 200 matching replay. Replay
returns same request identity's current status, never replacement workflow.
Distinct request while another is live: 409 CLOSURE_PENDING. Reusing a key with
different payload: 409 IDEMPOTENCY_CONFLICT. Before endAt: 409 WINDOW_NOT_ENDED.
REQUEST does not grade valid unlimited work or close Publication. Notices may
remain preparing and progress independently; request existence is not proof of
48-hour eligibility. Zero relevant Attempts is allowed; Teacher may ordinary
CLOSE or COMPLETE after reconciling all state, no artificial wait for no recipients.

While PENDING or FINALIZING: no endAt extension, duration mode change, maxAttempts
change, reopening or new START/RETAKE. START selecting an already owned Attempt
for resume is not creation. Existing ACTIVE rights/deadlines remain unchanged.

## CANCEL and replacement (C5)

Cancel exact request with Idempotency-Key, expected Publication revision and
current Teacher owner/admission. Allowed only PENDING with `finalization_started_at`
null. Atomic request CANCELLED/resolved timestamp, member unresolved progress
RESOLVED_CANCELLED and Publication revision+1. Existing published notices stay
visible with cancellation status; they are never deleted to obscure evidence.
Queue entries/hints lose authority as soon as the request state commits CANCELLED.
Matching cancellation replay returns existing acknowledgment despite later
replacement; a different mutation key on a resolved workflow conflicts.

Before the **first closure-driven** finalization, the finalizer holds Publication
and request lock and changes request PENDING → FINALIZING with a durable
finalization_started_at in the same transaction as its first successful cutoff
submission/audit. This is the cancellation fence. If that transaction rolls back,
finalization did not durably begin and cancellation can still win. Once it commits,
CANCEL returns 409 CLOSURE_FINALIZATION_STARTED; it cannot undo terminalization.
Normal Student SUBMIT/ABANDON or bounded timeout does not set this fence.

After cancellation, replacement REQUEST is a new identity with freshly captured
ACTIVE unlimited members/new notifications/new confirmation and fresh 48 hours.
Do not reuse prior notice evidence or edit an old cutoff. Requests never return
from resolved states to live states.

## COMPLETE and ordinary CLOSE (C4, C7)

COMPLETE is a separate current owning-Teacher command against exact live request,
with expected Publication revision and Idempotency-Key. Per invocation:

1. Reconcile overdue bounded Attempts in bounded transactions; grade saved answers.
2. Process up to 50 due confirmed unlimited members (configurable down to 1), each
   in a short coherent finalization transaction. Recheck live request, ACTIVE
   unlimited state and serverNow >= individual cutoff. Missing notices skip
   finalization; not-yet-due members keep their remaining rights. Already terminal
   members become RESOLVED_TERMINAL, without a second grade.
3. Under final Publication/request locks, query authoritative persisted ACTIVE
   state and all unresolved members. If only notice/time eligibility is blocking,
   return sanitized 409 CLOSURE_NOT_READY. Completed batches remain committed.
   If more immediately eligible work remains beyond the batch budget, return 202
   ClosureCompletion with `closed=false`; record the operation acknowledgment.
4. Only if all Attempts are terminal and members resolved, this authenticated
   Teacher command commits OPEN → CLOSED, request COMPLETED and Publication
   revision+1, with durable completion receipt (the workflow action audit). Response 200,
   `closed=true`. No worker, read or timer may make this transition.

The 202 acknowledgment is immutable per operation key; retry returns it without
reprocessing. Teacher checks GET ClosureView and issues a new operation key to
continue/complete. An IN_PROGRESS key/digest reservation and committed per-unit budget survive
crash/concurrent retries. Failed409 has no successful acknowledgment, but keeps
this reservation; same payload/key may continue later. Changed payload conflicts
even for a reserved key. Each committed unit increments the per-key budget under
Publication lock so duplicates cannot process more than 50 units. Long commands are never one transaction holding
thousands of Attempt locks; progress/result truth survives client timeout.
Successful COMPLETE replay returns original acknowledgment, not a new closure.

Ordinary CLOSE reconciles overdue bounded Attempts, then under Publication lock
checks persisted states. If any ACTIVE remains, return only
`{"code":"ACTIVE_ATTEMPTS"}` with 409, no identity, count or due-member detail.
It does not force-finalize unlimited work. If none ACTIVE, resolve pending workflow
and unresolved members as RESOLVED_EARLY/RESOLVED_TERMINAL and commit CLOSED together,
even before cutoff. Stale workers recheck parent/request state and cannot act.
No closure workflow is required for ordinary CLOSE. Closed replay with matching
successful key returns stored acknowledgment; other keys get PUBLICATION_CONFLICT.
Neither endAt nor 48-hour passage automatically changes Publication state.
All multi-transaction Teacher commands revalidate current Teacher admission/expiry
outside locks immediately before their final CLOSED transaction; a stored request
never substitutes for the currently authenticated Teacher action.

## Recovery, workers and audit (C7..C9)

Durable workflow states: PENDING, FINALIZING, CANCELLED, COMPLETED, RESOLVED_EARLY.
Members: NOTICE_PENDING, NOTICE_PUBLISHED, NOTICE_CONFIRMED, FINALIZED,
RESOLVED_TERMINAL, RESOLVED_CANCELLED. Progress is advisory only insofar as it must
be checked against the authoritative Attempt; required unresolved members prevent
closure. Student terminalization updates membership progress in its same transaction.

Workers use unlocked indexed candidate scans then try-lock Publication with SKIP
LOCKED, followed by request/Attempt/member locks. They never lock a member first.
No persistent lease is needed for short database transactions with no external
side effect; DB locks plus live request identity/state rechecks fence each unit.
Do not hold DB locks across email/network calls. A future external-delivery worker
needs durable outbox claim/lease fencing in its own reviewed design.

Every cutoff submission atomically writes final saved answers, deterministic grade,
result, Attempt terminal reason, member FINALIZED and immutable audit. Audit
contains IDs, requesting Teacher, confirmed notice/notification/accessibility
version/cutoff, finalizing SYSTEM or Teacher actor, authoritative finalization time,
pinned policy and result identity. Manual SUBMIT remains distinguishable; timeout
is not teacher-cutoff. No answer/key/token content in audit logs or metric labels.
Store audit in restricted Assessment database, with bounded sanitized operational
counts for missing notice, late due work, retries and failed batches.

Crash before commit leaves no partial grade; after commit replay finds terminal
result. Crash between batches retains each result and OPEN Publication. Another
instance resumes missing notices/due members by state scan. Long-offline workers
must re-read current request identity, never execute stale cached cutoff authority.
Student mutations at/after cutoff invoke the same finalizer, so no worker timing
can extend entitlement. A failed grade remains ACTIVE but immutable to late
Student writes, with 503 and alert; COMPLETE cannot pretend the work is resolved.

No cleanup TTL for active requests, evidence, notices, answer/results or receipts
is chosen here. Referential RESTRICT and append-only guards protect records until
a reviewed retention/deletion process explicitly covers historical requests,
cutoff evidence and retries. Production retention, privacy and backup controls
remain release gates, separate from Proctoring evidence retention; local SQL
retention is not a claim of permanent product retention or legal compliance.
