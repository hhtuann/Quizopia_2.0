# W3-B1 persistence and configuration implementation

Status: **Merged foundation at PR #80; production activation remains unauthorized.** Base: merged PR #79,
`e7b2ec247da1b28ff6155c253653faeb06208236`. ADR-015 and EC79-01..04 remain
authoritative. This document describes executable B1 code; the frozen
[persistence design](w3b-w3c-persistence-concurrency-design.md) and
[HTTP contract pack](../specifications/proposed/w3b-w3c.openapi.yaml) record frozen design.
This document preserves the original B1 increment boundary. The later
[B2 implementation](w3b2-student-attempt-implementation.md) supplies default-disabled
Student APIs and the authorized additive V4.1 capacity correction. V1–V4 remain unchanged;
B3 closure/inbox workflows and V5 remain deferred.

## Implemented boundary

Assessment adds Flyway V3/V4, internal immutable persistence projections and a
read-only JDBC port. The existing seven Publication operations accept the new
configuration. There are no new HTTP routes, workers, grading algorithms, inbox,
Identity changes, V5 or frontend changes. The implemented
[Publication OpenAPI](../../services/assessment-service/contracts/publications.openapi.yaml)
is version 1.1.0; ownership, revision, idempotency, LD-05 and error/no-store
boundaries remain in force.

`maxAttempts` is an integer 0..10, default 1; 0 means unlimited. Omission defaults
to 1; explicit null, strings, fractions and out-of-range values are rejected.
DRAFT may replace the value; OPEN freezes it. New writes explicitly persist it in
JSON and `max_attempts`. Legacy missing JSON keys are valid only with relational

1. Legacy creation receipt hashes without the key remain replayable only for
   effective 1. OPEN schedule updates modify only endAt/durationMinutes JSON keys,
   so they never add maxAttempts to legacy OPEN JSON.

Duration must be explicitly supplied as integer 0 or 5..240. Missing/null does
not silently become unlimited. PUBLIC monitoring remains DISABLED; unlimited
CLASS rejects ENABLED, permits UNCONFIGURED in DRAFT and requires DISABLED to
OPEN. OPEN bounded duration/endAt may extend; unlimited duration remains 0.
Neither mode switching nor reducing bounded duration is allowed. Pinned existing
Attempt duration/deadline is unaffected by Publication schedule extensions.

`ATTEMPTS_ENABLED`, `RESULTS_ENABLED`, `CLOSURE_ENABLED` bind to
`quizopia.assessment.features` and default false. They introduce no routes even
when true. Existing Publication/source/admission flags remain separately default
closed. These flags are configuration foundation, not runtime deadline switches.

## V3 migration and compatibility

`V3__attempt_configuration_compatibility.sql` adds NOT NULL DEFAULT 1
`max_attempts`, range/JSON consistency checks, nullable `closed_at` constrained to
NULL in B1, and duration/unlimited-monitoring constraints. It identifies the
single-column duration CHECK through PostgreSQL catalog identity rather than an
auto-generated name; unexpected catalog state fails for operator review. V1/V2
files and applied checksums remain unchanged.

The replaced Publication guard preserves DRAFT→OPEN, revisions, immutable
identity/source/snapshot/audience/monitoring/locator and safe extensions. It also
freezes OPEN maxAttempts/duration mode and continues to prohibit CLOSED. V2's
bidirectional deferred snapshot association remains installed. No legacy OPEN
JSON, snapshot or invalid historical row is rewritten or repaired.

Drain/block all old Publication writers before migration. V3 locks publications;
V4 locks publications then publication_snapshots with ACCESS EXCLUSIVE. Validate
representative database copies and lock/statement budgets before deployment.
Invalid existing rows or unexpected constraints abort rather than silently repair.
Flyway commits each successful migration separately: V4 failure can leave V3
successfully applied; keep traffic blocked and investigate, without Flyway repair
or destructive down-migration. Old binaries cannot be assumed compatible with
explicit new JSON fields; mixed-version/rollback procedures need independent
operational validation. Use separate restricted migration and ordinary runtime
privileges; this implementation does not provision deployment roles or fencing.

## V4 executable foundation

`V4__attempts_and_atomic_results.sql` installs exactly these eight tables:

| Table                       | Durable purpose                                                                                                                |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| attempts                    | Publication/Student identity, ordinal, snapshot/policy/schema/duration pins, nullable deadline, revision and terminal identity |
| attempt_answers             | Latest typed question answer and saved revision/time                                                                           |
| attempt_creation_operations | Publication/Student/key-scoped START/RETAKE replay selection                                                                   |
| submitted_answers           | Write-once complete answer document and canonical SHA-256 identity                                                             |
| grading_results             | One exact earned/possible result per Attempt                                                                                   |
| grading_question_results    | Exact per-question weights linked to a result                                                                                  |
| attempt_operations          | Immutable successful operation acknowledgment/replay payload                                                                   |
| attempt_finalization_audit  | One terminal reason/actor/time/result audit per Attempt                                                                        |

Local FKs explicitly RESTRICT deletion/key updates. Composite FKs bind results to
the same Attempt, snapshot, policy and submitted-answer identity. Attempt-to-result
and Attempt-to-submitted-answer circular FKs are initially deferred so a complete
transaction can commit. There are no cross-service database FKs or reads.

Unique Publication/Student/ordinal, one ACTIVE partial uniqueness, due-bounded and
Publication paging indexes support future orchestration. The parent-first insert
guard serializes ordinal/finite-entitlement checks under the Publication lock;
ABANDONED Attempts consume entitlement. New Attempts must pin the OPEN snapshot,
current duration and policy, start inside the participation window, use the next
ordinal and begin ACTIVE/revision 0. Bounded deadline equals startedAt+duration;
unlimited deadline is NULL, with no fabricated maximum timestamp. Identity/pins
are immutable; each update advances revision exactly once and terminal rows cannot
be updated/deleted.

Answer guards validate all four pinned question types, authoritative time bounds,
and prevent answer deletion. Deferred answer/receipt validation requires the
resulting revision and an Attempt tuple written by the same transaction (checked
with PostgreSQL xmin/current transaction identity). Merely reusing a previously
committed revision fails. Both answer-first and Attempt-first ordering are tested.
This transaction identity check occurs at mutation commit, not on later reads or
as a persistent business timestamp. Normal future callers must still lock
Publication before Attempt/children; arbitrary reverse-order SQL may deadlock and
abort safely. No Java monitor or Redis counter substitutes for PostgreSQL.

Deferred cross-table validation rejects partial grading/finalization. SUBMITTED
requires complete submitted answers, result, all question weights and audit, with
matching IDs, policy, exact sums, timestamps and canonical digest. The submitted
document must contain latest saved answers plus typed unanswered values in pinned
snapshot order. ABANDONED requires audit and no submitted answers/result. ACTIVE
cannot retain terminal artifacts. SQL validates consistency, not the correctness
of a grading algorithm: deterministic grading and application canonicalization
are W3-C1 work. Canonical storage hashing uses compact JSON, C-ordered object keys,
preserved array order and exact strings; future Java code needs shared vectors.

Artifacts and receipts reject UPDATE/DELETE; all authoritative tables reject
TRUNCATE. Trusted schema qualification/search_path prevents temporary-table lookup
substitution in cross-table guards. Protection assumes ordinary enabled database
integrity; privileged operators must not disable triggers or alter schema.

V4 retains nullable closure audit columns but constrains them to NULL and rejects
TEACHER_NOTICE_CUTOFF. V5 must install real closure/notice FKs and C1 evidence checks
before removing those guards. No trigger references a nonexistent V5 table.

## Executed local verification

Environment: Java 21, Maven wrapper, Docker and real `postgres:17-alpine`
(PostgreSQL 17.11 observed). No H2 substitute or new dependency was introduced.

```sh
MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven \
MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' \
bash services/assessment-service/mvnw -B -f services/assessment-service/pom.xml \
  -Dquizopia.build.directory=target/w3b1-verification spotless:apply verify \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'

MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven \
MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2' \
bash gateway/mvnw -B -f gateway/pom.xml \
  -Dquizopia.build.directory=target/w3b1-topology test \
  -Dtest=PublicationTopologyTest \
  -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

Final Assessment `verify`: **152 tests, zero failures/errors/skips**, executable
JAR packaging and Spotless PASS; ArchUnit's four rules PASS. The suite includes
67 unit/client tests, 71 PostgreSQL-backed cases (14 new B1, 24 Publication, 12
snapshot association, 21 source transport), nine closed-boundary tests and one
application context test. These are disjoint suite counts; the migration,
constraint and concurrency checks below are subsets, not additional totals.
`PublicationTopologyTest`: **9 tests, zero failures/errors/skips**, PASS against
the final production sources. No CI result is inferred from local success.
Assessment tests cover fresh V1→V4/reapplication, V1/V2 legacy upgrades and unchanged
checksums, invalid historical-data rollback, raw constraint/immutability checks,
complete finalization versus partial rollback, cross-Attempt result references,
pinned deadline compatibility, transaction revision binding and JDBC read/replay
projections. Two new deterministic separate-connection races verify active/ordinal
and finite-entitlement serialization using latches and observed PostgreSQL lock
waits. Retained V2 tests cover concurrent OPEN association. Actual Spring startup
with PostgreSQL exercises Flyway/Hibernate validation; Attempt storage itself is
JDBC, with no duplicate JPA entity mapping.

Publication tests exercise omitted maxAttempts and explicit 0/1/10, malformed
values, explicit unlimited duration, CLASS monitoring, OPEN freeze/extensions,
legacy JSON and receipt replay. Existing authentication, redaction, body-size,
rollback and source fidelity tests remain. Boundary tests set all future flags
true and still reject unknown/future routes. The Gateway topology test starts real
Identity, Quiz, Classroom, Assessment and Gateway with PostgreSQL/Redis and real
USER/SERVICE tokens. Loopback test transport is not production HTTPS evidence.

## Self-review and remaining gates

Fixed during self-review: missing maxAttempts versus explicit null handling,
omitted duration becoming zero, and raw writes reusing a committed revision.
Runtime SQL tests also verify valid atomic write ordering and rollback. Final
self-review is author review, not independent approval.

Before merge: independent code/migration review and Leader merge approval. Before
activation: representative upgrade/traffic drain, restricted roles, mixed-version
rollback, realistic full-answer/canonicalization sizing and trigger execution cost,
lock/statement budgets, multi-instance recovery, uncertain commits, failover and
acknowledged-write durability, TLS/redaction and recovery authorization. No
benchmark or failover result is claimed. The existing 2 MiB W3-A source handoff
compatibility limitation remains; B1 introduces no permanent Quiz product cap.

Next increments remain separate PRs: W3-C1 actual deterministic grading/shared
atomic finalization; W3-B2 complete authenticated Attempt APIs/deadline behavior;
W3-B3 CLOSE, atomic complete membership capture, recoverable batches, real inbox,
individual accessible-notice cutoffs and narrow Identity eligibility. EC79-01
independent security review and real integration acceptance remain; EC79-02's
potentially indefinite blocked closure and EC79-03 capacity trade-off remain
accepted limitations. This increment supplies no remediation bypass or activation
authority and needs no new Leader product decision.
