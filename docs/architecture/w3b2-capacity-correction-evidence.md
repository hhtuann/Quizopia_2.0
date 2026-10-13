# W3-B2 capacity correction evidence

Status: **Authorized engineering scope correction implemented; independent integrity/security review still required.**
Baseline: merged PR #81, `01885df185c202cec858d403e5b24b56768bfb7d`.
The user explicitly authorized: “Authorize the V4.1 correction within W3-B2”.
This permits an additive V4.1 migration and bounded finalization-budget correction,
with V1–V4 preserved and V5 reserved for W3-B3. It does not grant merge approval,
production activation or a new product rule.

## Confirmed incompatibility

A valid 1,024-question NUMERIC_FILL Publication snapshot is 168,084 UTF-8 bytes;
its complete answer array is 72,622 bytes. Both are far below the accepted initial
2,097,152-byte transport budget. The actual Quiz Markdown parser accepts the
source; the actual Assessment SnapshotValidator accepts the protected snapshot.
The source contains sequential questions, valid four-character keys, real
Vietnamese UTF-8 Markdown and no unsupported product configuration.

Using unchanged C1 production finalization on PostgreSQL 17.11:

| Test configuration                                          | Observed result                                                              |
| ----------------------------------------------------------- | ---------------------------------------------------------------------------- |
| Original integration-test datasource, without socketTimeout | 256 questions finalized in 3,548 ms; 1,024 in 49,239 ms                      |
| application.yml-equivalent socketTimeout=5/loginTimeout=3   | 1,024-question finalization failed as PERSISTENCE_UNAVAILABLE after 5,738 ms |

These are individual local observations, not production benchmarks or SLAs.
A live pg_stat_activity observation showed the original 1,024-question transaction
still executing COMMIT after 45 seconds. C1's configured five-second transaction
and three-second statement limits did not bound that deferred commit work.
A socket error during commit is an **uncertain outcome**, not proof of rollback;
this audit does not claim that no later database commit could occur.

V4's terminal_consistency trigger runs FOR EACH ROW on both attempt_answers and
grading_question_results, among other tables. Each event calls
validate_attempt_consistency, which reconstructs/canonicalizes/hashes the entire
answer set and aggregates all question grades. Thus a complete Q-question write
queues roughly 2Q child validations, each doing work over Q questions. In addition,
guard_answer_write repeatedly extracts question type from the full protected
snapshot. These are database-path costs, not a slow Java grading algorithm.

The relevant baseline files are:

- [V4 guards](../../services/assessment-service/src/main/resources/db/migration/V4__attempts_and_atomic_results.sql).
- [C1 finalizer](../../services/assessment-service/src/main/java/com/quizopia/assessment/persistence/JdbcAttemptFinalization.java).
- [Production datasource limits](../../services/assessment-service/src/main/resources/application.yml).

Exposing B2 while this accepted snapshot cannot complete under the current
production settings would violate assignment sections 19 and EC79-03. A smaller
question-count cap, answer truncation, disabled guards, split finalization or
quietly increased unbounded timeouts would not satisfy the contract.

## Adopted additive correction

The original proposal has been promoted to
[V4.1](../../services/assessment-service/src/main/resources/db/migration/V4_1__bound_attempt_consistency_validation.sql).
The unregistered exploratory SQL and candidate subclasses were replaced with real
Flyway-path tests. No V1–V4 file changed. Drain writers before applying the migration:
it acquires table locks and adds a stored generated column, which rewrites existing
snapshot rows. Assess migration duration and disk/WAL capacity on deployment-sized
data before release; no production-size migration benchmark is claimed.

- Every Attempt write retains the complete deferred cross-table validator.
- Child validation requires a parent tuple written by the same PostgreSQL transaction,
  which queues full parent validation. Existing BEFORE parent locks remain held until
  transaction end. Child state/revision/reference checks remain; no disabled trigger,
  caller-controlled validation cache or GUC is introduced.
- Late grade rows after an early constraint checkpoint are bounded by the validated
  question count and immutable result identity; uniqueness prevents duplicate grades.
- `question_type_codes` is GENERATED ALWAYS from the immutable protected snapshot.
  Callers cannot override it. It contains only type codes, is never a grading/key
  authority, and avoids repeated large JSON decompression in answer guards.
- Root finalization now executes `SET CONSTRAINTS ALL IMMEDIATE` before COMMIT under
  the actual statement timeout. Defaults: transaction 15 seconds, statement/lock
  3,000 ms; configurable transaction range 1..30 seconds, statement 1..4,000 ms,
  lock 1..statement. JDBC socket/login limits stay 5/3 seconds. No unbounded budget.
- Failure before COMMIT rolls back all artifacts. A transport failure during COMMIT
  remains uncertain; retry the identical durable operation key under fresh authority.
  No successful acknowledgment is synthesized after a database exception.

## Executed evidence

The first authorized Flyway regression run passed 54 PostgreSQL cases, including
existing B1/C1 guards and extra child-write/early-checkpoint attacks. At production
JDBC limits, a 12,613-question NUMERIC_FILL snapshot of **2,097,086 UTF-8 bytes** and
complete answer array of **909,644 bytes** finalized in **7,585 ms** in that run.
These are local observations, not production benchmarks or SLAs.

[Capacity integration tests](../../services/assessment-service/src/test/java/com/quizopia/assessment/StudentCapacityIntegrationTest.java)
now drive actual B2 START/delivery/SUBMIT adapters and C1 persistence with large
complete answer arrays. Ordinary delivery is checked for key/explanation/source
redaction. The real five-service topology additionally exercises exactly 2 MiB,
oversized Content-Length and oversized chunked requests at both edge and downstream.
Oversized requests return sanitized errors and leave durable receipt counts unchanged.

START computes the maximum complete canonical answer document for each pinned
snapshot and checks envelope capacity before creating an Attempt. This does not
truncate answers or impose a question-count rule. The 2 MiB transport limit remains
an initial engineering budget. Existing W3-A source-handoff limits remain unchanged;
this increment does not claim to resolve W3-A's separate oversized-snapshot gate.

[Atomic finalization tests](../../services/assessment-service/src/test/java/com/quizopia/assessment/AtomicFinalizationIntegrationTest.java)
include populated V4→V4.1 upgrade, unchanged V1–V4 installed checksums, unchanged
snapshot/result identity, generated-column override rejection, partial child-write
rejection and rejection of a late extra grade after constraint validation.
The existing raw DML and PostgreSQL concurrency suites run against the real chain.

Reproduce with repository wrappers:

```sh
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven \
MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' \
bash services/assessment-service/mvnw -B -f services/assessment-service/pom.xml \
  -Dquizopia.build.directory=target/w3b2-verification spotless:check verify \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

Ignored local audit logs retain the original failing production-limit reproducer
and exploratory measurements under `services/assessment-service/target/`:
`w3b2-capacity-audit.log`, `w3b2-capacity-production-limits.log`,
`w3b2-candidate-audit.log`, `w3b2-candidate-budget-audit.log`,
`w3b2-candidate-refined-audit.log`, `w3b2-full-audit.log`,
`w3b2-authorized-correction.log`. The final module verification logs and test XML
provide current results; earlier failures are not represented as passing runs.

## Remaining gates

Independent migration integrity/security review, deployed capacity sizing, uncertain
commit across real network failure, database failover/durability, multi-instance
operational recovery and production topology/TLS verification remain release gates.
W3-B2 tests do not constitute these deployment approvals. Successful CLOSED review
integration awaits B3; no closure state is forged. No CLOSE, V5, inbox, notice cutoff,
Identity recipient endpoint or production activation is provided here.
