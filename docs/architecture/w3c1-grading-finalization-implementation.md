# W3-C1 atomic grading and finalization implementation

Status: **Merged foundation at PR #81; production activation remains unauthorized.** Base: merged W3-B1 PR #80,
`22c7ff3104ca1230e601b3ee615e928d87a13a76`. ADR-015 and EC79-01..04 remain
authoritative. The [grading contract](../specifications/w3-c-grading-results-contract.md)
and [B1 implementation](w3b1-persistence-implementation.md) provide policy/schema
context. This document records the original C1 increment boundary. The later
[B2 implementation](w3b2-student-attempt-implementation.md) supplies Student HTTP,
current authorization and the explicitly authorized additive V4.1 capacity/budget
correction; see that amendment for current runtime limits. The contract pack now
records availability per operation; [B3 closure/inbox integration](w3b3-closure-notification-implementation.md)
now supplies the default-disabled workflow extension for independent review.

## Production boundary

`GradingPolicy` implements pure `QUIZOPIA_GRADING_V1` (stored version 1), with
immutable typed answers and redacted protected models. SINGLE_CHOICE earns 1 only
for the correct label; MULTIPLE_CHOICE requires exact set equality, rejecting
duplicates and sorting accepted selections. A matrix earns one exact quarter per
correct non-null judgment, including all-false keys. NUMERIC_FILL trims only ASCII
space/HT/LF/VT/FF/CR, treats null/blank as unanswered, requires the accepted exact
four-character grammar (including `-.25`), preserves spelling and compares
BigDecimal values without tolerance. Invalid structure is rejected, not scored zero.

All Q questions contribute possible weight Q. Integer quarter accumulation and
BigDecimal conversion avoid intermediate rounding. Exact score strings use plain
notation/zero `"0"`. Percentage is presentation only, two digits HALF_UP; neither
grading rows nor operation receipts persist rounded percentages.

`GradingDocuments` strictly parses typed answers, rejects duplicate JSON keys,
trailing documents, unknown fields, duplicate/missing/extra question references,
wrong tags and invalid matrix/label/numeric values. Complete replacements are
ordered by pinned question number. Canonical document keys have fixed ASCII
lexical ordering, selection sets are sorted, arrays retain question order and
numeric strings retain spelling. Java SHA-256 input is verified against V4's
PostgreSQL `assessment_canonical_json`/SHA-256 output.

The existing `SnapshotValidator` now exposes its validated tree for reuse, so the
full protected snapshot is parsed once per operation, not per question. Source
identity, snapshot/content schema 1, question/option ordering and complete answer
keys are validated before grading. The adapter also verifies the Attempt's pinned
policy/answer schema and the relational snapshot policy. Corruption/unsupported
policy fails as sanitized `GRADING_UNAVAILABLE`, without placeholder results or
parser causes. No Quiz, Identity, Classroom, Gateway or Redis call supplies grades.

## Internal transaction API

`AttemptFinalization` is an internal port, implemented by `JdbcAttemptFinalization`.
It is **not an authorization service**. W3-B2 must establish fresh USER admission,
Student ownership and applicable current membership before every call, replay or
read. The core independently binds selected Student UUID to the stored Attempt;
a caller-supplied UUID alone grants no public authority. No controller, scheduler
or debug endpoint invokes this port in C1.

Each mutation owns a root READ COMMITTED transaction. An already active calling
transaction is rejected before acquiring locks, avoiding joined transactions that
return apparent success before commit or suspend locks held by the caller. W3-B2
must call outside its own database transaction and reconcile/recheck subsequent
operations separately. Normal lock/statement limits are 3 seconds; transaction
limit is 5 seconds. No automatic uncertain-commit retry occurs. Retry with the same
durable operation identity after re-establishing current authority.

A nonlocking identity lookup selects Publication; then lock Publication, then
Attempt, then child rows. After full replacement validation, sample PostgreSQL
`clock_timestamp()` immediately before new mutation/deadline eligibility. One
sample is stored across terminal Attempt, submitted answers, result, audit and
manual receipt acknowledgment. It is not transaction-start NOW or request time.

Manual submission checks durable receipt before terminal/revision/deadline checks.
The request digest binds SUBMIT, Attempt ID, decimal-string expected revision and
canonical complete replacement (or null for omitted replacement). `keyDigest` is
an already hashed, validated 64-lowercase-hex operation identity; B2 owns raw header
validation/hashing. Same key/action/request returns the committed result without
grading or revision changes, even after deadline/restart. Changed action, revision
or canonical content gives `IDEMPOTENCY_CONFLICT`; distinct new terminal mutation
gives `ATTEMPT_TERMINAL`. Malformed input is mutation-free even when overdue.

Before deadline, expected revision must match. Full replacement upserts all
answers with one resulting revision and the sampled time. Omission freezes latest
committed answers; never-saved questions become explicit typed unanswered entries
in the complete immutable submitted document. Existing autosave timestamps remain
historical: C1 does not fabricate autosave rows/times for missing answers.

V4 permits equivalent multiple-selection array orders. Finalization canonicalizes
such existing rows in the same terminal transaction, advances their saved revision
to the resulting Attempt revision and **preserves the original saved_at**. Selection
meaning is unchanged; no late answer is introduced. This supports saved-only timeout
without altering V4's pre-deadline answer guard. Invalid stored answers still fail
closed. Deferred revision/current-transaction and submitted-document checks remain
fully enabled.

The shared persistence path atomically writes submitted-answer UUID/document/digest,
exact aggregate and per-question results, actor/reason audit, terminal Attempt and
manual receipt. All artifacts are immutable. Any validation/grading/write/receipt
or deferred commit failure rolls back replacements and every terminal artifact.
The method returns only after the transaction manager successfully commits; a
commit failure is sanitized persistence failure, never apparent success.

## Timeout, results and future extension

System timeout requires a positive pinned deadline and sampled time at/after it;
manual mutation is strictly before it. Timeout uses saved answers only, SYSTEM
actor `assessment-timeout`, and no Student receipt. Terminal retries observe the
same result or an ungraded terminal state. Unlimited duration returns NOT_APPLICABLE;
Publication endAt never finalizes work. There is no scheduler or public timeout route.

A well-formed overdue manual request returns **DEADLINE_REACHED with a committed
timeout outcome**. The supplied replacement is not used and no manual receipt is
created. B2 maps the conflict only after this return; throwing inside the transaction
would wrongly undo reconciliation. Exact matching prior receipt replay still wins
before the deadline decision.

Result/best queries return total-only identity, exact score strings and presentation
percentage, without keys, question correctness, explanations or snapshot content.
Best selects SUBMITTED results by earned score descending, then ordinal, then
result UUID. ACTIVE/ABANDONED cannot hide an earlier grade; no grade means empty.
These internal queries still require B2's current authorization before disclosure.
Detailed Student review and Teacher HTTP results remain unavailable. Internal
revision values remain Java long; B2 must map them to the reviewed decimal-string
wire representation rather than expose this internal projection as an HTTP DTO.

The same grader/storage path is the future B3 extension point. Its currently
executable system API rejects TEACHER_NOTICE_CUTOFF with FINALIZATION_NOT_AVAILABLE.
V4 closure guards remain intact. B3 must add genuine V5 membership/notice evidence
and verify it under Publication→workflow→Attempt locking before reusing storage.
No synthetic cutoff, inbox, Identity endpoint, event or closure write is supplied.

## Executed verification

Java 21, Maven wrapper, Docker and PostgreSQL 17 Testcontainers were used.

```sh
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven \
MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' \
bash services/assessment-service/mvnw -B -f services/assessment-service/pom.xml \
  -Dquizopia.build.directory=target/w3c1-verification spotless:check verify \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

The Gateway regression command was also executed:

```sh
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven \
MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' \
bash gateway/mvnw -B -f gateway/pom.xml \
  -Dquizopia.build.directory=target/w3c1-topology test \
  -Dtest=PublicationTopologyTest \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

It passed **9 tests, zero failures/errors/skips**, rebuilding actual Identity,
Quiz, Classroom, Assessment and Gateway with PostgreSQL/Redis and real tokens.
This is existing Publication security/topology regression, not Student HTTP
submission or production HTTPS acceptance.

After the receipt self-review correction, `spotless:apply verify` also passed.
The complete Assessment suite has **227 tests, zero failures/errors/skips**:
51 new pure grading/document cases, 24 new PostgreSQL finalization cases and
152 retained B1/W3-A/security/architecture cases. ArchUnit's four rules and Spotless
pass. The final PostgreSQL-focused rerun adds receipt-display exclusion and
explicit proof that a contender waiting for Publication holds no Attempt lock.

New PostgreSQL cases use production finalization with the unchanged V1→V4 chain.
They inspect complete replacement, saved/missing answers, exact weights/IDs/times,
Java/SQL digests, best-result ordering, owner binding, terminal immutability and
retry through a new core instance. Five failure variants cover injected write,
receipt, deferred commit, numeric CHECK and deferred result-reference rejection;
all inspect rollback and successful retry. Separate connections/latches plus
observed lock waits exercise same/different-key submit, timeout, save/submit,
failed contender recovery, invisibility before commit and waiting across a deadline.

The pure eligibility test covers deadline−1µs, exactly deadline and deadline+1µs;
real PostgreSQL tests prove the post-lock sample crosses an actual deadline and
that exact replay remains valid afterwards. Native PostgreSQL wall time is not
frozen or replaced by a test clock. Capacity evidence includes a 2,048-question
pure snapshot/complete document and an actual 128-question full replacement/grade
commit, without truncation or a new product question-count limit. These fixtures
are not a production throughput benchmark or exhaustive 2 MiB compatibility proof.

## Self-review and remaining gates

Fixed during author review: equivalent persisted selection ordering under V4,
preventing rounded percentage storage in receipts, and suppressing JDBC parameter
TRACE logging of protected answers/receipts. Expected PostgreSQL Boolean text in
the test assertion was corrected; final runs pass. Author review is not independent
security or Leader approval.

No V1–V4 migration, implemented/proposed OpenAPI, Gateway route, Identity or frontend
change is made. New flags retain OFF defaults; all future routes remain denied even
when flags are true. JDBC core logging is WARN to suppress protected bind values.

Before merge: independent review, successful CI and Leader approval. Before release:
W3-B2 real authorization/lazy reconciliation/Student APIs, W3-B3 real closure/inbox/
Identity integration, full accepted-snapshot/answer expansion sizing, lock/statement
budgets and V4 deferred-trigger cost, multi-instance recovery, uncertain commits,
failover/acknowledged-write durability, clock behavior, restricted roles, TLS,
redaction, retention and rollout/rollback acceptance. Existing W3-A's 2 MiB source
handoff limitation remains. EC79-01 security acceptance and EC79-02/03 accepted
operational limitations are preserved. No new Leader product decision is requested.
