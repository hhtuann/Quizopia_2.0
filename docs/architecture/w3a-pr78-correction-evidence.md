# PR #78 — LD78 correction evidence

This is Dev 1 self-review evidence for the Coordinator's independent read-only
review. It records the four decisions in the Leader's continuation assignment,
without inventing an approval date, broader contract sign-off or production release.
The accepted scope and remaining engineering review are in
[ASSESS-15](../specifications/assess-15-publication-contract.md); deployment gates
are in the [runbook](w3a-publication-backend.md).

## Changed implementation

The only production change in this correction is Assessment
`V2__enforce_snapshot_publication_association.sql`. V1 and all Java production code,
public API contracts, clients/scopes, default-disabled flags and service ownership
are preserved. Test changes are Assessment persistence/transport/identity/locator
proof, Quiz compatibility/fidelity, and one additional real five-service topology
case. No other backend agent or worktree was created.

## Database proof

V2 preserves V1 checksums and first locks the parent and child tables and rejects
preexisting invalid rows without repair/deletion. A deferred AFTER INSERT ROW
constraint trigger validates the final parent with FOR UPDATE. Its parent lookup
uses the trigger's actual schema with a catalog-first function search path, so a
caller-created temporary table cannot substitute authority. Existing FK/state
checks enforce the other direction; primary/unique keys prove cardinality;
immutable snapshot and finalized parent guards prevent later invalidation.

`SnapshotAssociationIntegrationTest` uses PostgreSQL 17, real Flyway migrations,
independent JDBC connections, explicit commit/rollback, and verified READ COMMITTED:

| Method                                                           | Assertion point / expected result                                                                                                             |
| ---------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| nonexistentParentFailsAtCommitAndRollsBack                       | INSERT succeeds provisionally; COMMIT fails 23514; a fresh session sees zero snapshots.                                                       |
| temporaryParentShadowCannotAuthorizeAnOrphan                     | Fake OPEN parent in pg_temp cannot satisfy real association; COMMIT 23514.                                                                    |
| draftParentWithoutReferenceFailsAtCommit                         | DRAFT + child insertion without parent OPEN/reference fails COMMIT 23514; DRAFT and zero snapshots remain.                                    |
| normalInsertThenOpenCommitsAndGuardsRemain                       | Insert then OPEN commits; child UPDATE/DELETE, finalized parent DELETE and association change fail P0001; one snapshot remains.               |
| openWithoutSnapshotOrWithWrongIdentityFailsAndRollsBack          | Missing child fails immediate FK 23503; wrong identity or NULL reference fails CHECK 23514; rollback retains DRAFT/zero snapshots.            |
| immediateValidationRejectsIncompleteSequenceButAcceptsFinalState | SET CONSTRAINTS IMMEDIATE rejects unfinished sequence 23514; final OPEN sequence passes and commits.                                          |
| failureAfterSnapshotInsertionRollsBackBothWrites                 | Snapshot inserted, later invalid OPEN update fails CHECK 23514; rollback removes child and retains DRAFT.                                     |
| concurrentParentDeleteMakesDeferredInsertFailAfterLockWait       | Separate sessions; pg_stat_activity confirms child COMMIT waits on parent deletion lock; after deletion commits child fails 23514, no orphan. |
| concurrentDraftEditCannotLeaveFinalizedSnapshot                  | Same controlled lock-wait proof for DRAFT revision edit; child COMMIT fails 23514.                                                            |
| openingParentPreventsConcurrentDeletionAfterCommit               | Reverse winning order: delete waits for OPEN row lock; after OPEN commits delete fails P0001; both parent/child survive.                      |
| validV1UpgradePreservesRowsAndChecksum                           | Applied V1 with valid OPEN data upgrades exactly once, keeps data and V1 checksum; next migrate is no-op.                                     |
| invalidV1UpgradeStopsWithoutDeletingOrRepairingData              | Applied V1 orphan makes V2 migration fail; orphan remains available for restricted operator investigation.                                    |

The existing `PublicationIntegrationTest.concurrentOpenUsesRealPostgresRowSerialization`,
`editDuringOpenPrefetchInvalidatesPreparation`, creation replay/race and OPEN replay
cases pass with V2. Source/admission mocks control preparation; the database is real.
The five-service topology additionally races real HTTP OPEN transactions. Arbitrary
SQL lock-order deadlocks can abort a transaction; this never provides an inconsistent
commit. Privileged operators disabling constraints are outside normal DML guarantees.

## Transport and immutable source proof

`SnapshotTransportIntegrationTest` uses the real configured PublicationSourceClient,
real Reactor Netty HTTP/OAuth stubs and PostgreSQL 17/Flyway/JDBC persistence.
Stubs are not independently authenticated business services. Sixteen measured
boundary cases cover ASCII and multibyte UTF-8 for each transfer mode:

| Serialized JSON body bytes | Content-Length             | Chunked                    |
| -------------------------: | -------------------------- | -------------------------- |
|                  2,097,151 | Accepted complete document | Accepted complete document |
|                  2,097,152 | Accepted complete document | Accepted complete document |
|                  2,097,153 | Sanitized dependency 503   | Sanitized dependency 503   |
|                  2,162,688 | Sanitized dependency 503   | Sanitized dependency 503   |

This is the uncompressed body delivered to the byte-array decoder, including JSON
syntax and UTF-8 encoding, excluding headers/chunk framing. It is not character
count, total heap or expanded JSON-tree size. Fixtures have valid v1 structured
content; ASCII source padding assembles exact byte lengths even with a multibyte
prefix. No truncation is accepted. Malformed oversized, oversized error responses
and partial JSON also fail closed. Existing source-client tests cover timeout.

`oversizedOpenLeavesDraftAndZeroSnapshotsThenRetrySucceeds` proves both transfer
modes leave DRAFT with zero snapshots after rejection; a full in-limit retry commits
one exact JSON snapshot with grading_policy_version=1, then replays coherently.
Captured client output excludes answer markers and USER evidence.

Quiz `validPublishedVersionCanExceedTemporaryAssessmentTransportCap` uses actual
Quiz authoring/publishing, PostgreSQL and the secured private handoff with signed
RSA credentials (admission mocked). It measured **2,701,728 UTF-8 response bytes**,
returned the original source/content unchanged, and demonstrates the compatibility
gap without altering Quiz validity or limits.

`PublicationTopologyTest.oversizedRealPublishedSourceCannotPartiallyOpenSeededDraft`
uses actual Identity, Gateway, Quiz, Classroom and Assessment processes, four
PostgreSQL databases, Redis, real login/JWKS and Client Credentials. Its actual
published response measured **2,700,566 bytes**. Creation fails closed at the cap.
An explicitly JDBC-seeded existing DRAFT then rejects two real HTTP OPEN retries
with sanitized no-store 503 DEPENDENCY_UNAVAILABLE, unchanged revision/DRAFT and
zero snapshots. The immutable Quiz response remains identical afterwards. Seeding
is explicit because current creation already rejects that oversized source.

## Locator, item identity and authorization

`locatorDigestOwnerOnlyResponsesAndSanitizedErrors` checks decoded 32-byte locator,
SHA-256 digest correspondence, owner-only retrieval, nonowner 404, Student management
403, Teacher-without-STUDENT entry 403, invalid-token 401, malformed-locator 400,
no-store and locator-free errors/Student projections/DTO string rendering. Existing
DRAFT audience-transition tests check clearing/rotation and replay stability.
Static inspection finds no application locator logging, exception interpolation,
metric labels or trace attributes. Real source/Assessment process logs are checked
for issued USER credentials, private locators and answer markers. Infrastructure
proxy/APM/log exporters, backup encryption and operator access remain unverified.

Quiz's exact structured-content equality and Assessment's
`independentPublicationsPinIdenticalV1ItemsWithoutSyntheticIds` preserve four types,
question numbers/order, A-D labels/order, flags, all-false matrices, numeric spelling,
Markdown/explanations, exact source triple and schema/content/grading policy v1.
Two Publications store separate immutable rows; extending one does not affect the
other. Seven accepted numeric spellings, including 0001 and -.12, are checked by
`acceptedNumericSpellingAndSnapshotLocalIdentitiesStayExact`. No synthetic IDs or
cross-version/global question identity are claimed.

The retained topology tests exercise wrong SERVICE client/scope, USER/SERVICE
separation, current role removal, stale/missing Redis, committed cutoff, primary/
Redis/Identity outages, no refresh-family invalidation and public internal-route
denial. Independent Gateway/Assessment/source admission and future-route closure
remain intact. Loopback HTTP tests do not prove production TLS.

## Verification commands and results

Java 21, Spotless, compilation, JUnit, ArchUnit, Flyway and packaging:

```sh
export MAVEN_USER_HOME=/home/hhtuann/.local/share/quizopia-dev/maven
export MAVEN_OPTS='-Xmx256m -XX:ActiveProcessorCount=2'
for ld78_project in services/assessment-service services/quiz-service services/classroom-service gateway; do
  bash "$ld78_project/mvnw" -B -f "$ld78_project/pom.xml" \
    -Dquizopia.build.directory=target/w3a-verification \
    spotless:apply verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
done
bash services/identity-service/mvnw -B -f services/identity-service/pom.xml \
  verify -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
bash gateway/mvnw -B -f gateway/pom.xml \
  -Dquizopia.build.directory=target/w3a-verification \
  test -Dtest=PublicationTopologyTest -DargLine='-Xmx384m -XX:ActiveProcessorCount=2'
```

Final local runs passed with zero failures, errors or skips:

| Project                | Tests |
| ---------------------- | ----: |
| Assessment             |   127 |
| Quiz                   |   167 |
| Classroom              |   113 |
| Gateway                |    85 |
| Identity compatibility |   423 |
| Total distinct tests   |   915 |

Gateway includes nine five-service Publication topology cases and six retained
Identity/Classroom topology cases. The final nine-case Publication topology repeat
also passed after the finalized V2 migration.

The projects were run serially, with Assessment and topology repeated after the
schema-qualification review fix. The Maven cache path is local only. Final suite
counts and CI links are recorded in the PR/final report; no unexecuted suite is
represented as passing. Local ignored evidence is under gateway/target/w3a-evidence.

Initial focused proof ran 30 tests with eight fixture errors caused by timezone-less
SQL literals conflicting with UTC configuration. Those fixture literals were fixed.
The first expanded Assessment verify ran 117 tests with one fixture error: an
extension test accidentally reduced its 30-minute duration to six. Corrected to an
extension; no production timing rule or timeout was relaxed. Expected migration
failure logs in invalid-V1 tests are successful negative assertions, not skipped
migrations or failing tests.

All three OpenAPI 3.1 documents are validated with openapi-spec-validator 0.7.2;
relevant Markdown/OpenAPI use repository Prettier. Working and full-PR diff checks
must pass before publication. No frontend/browser E2E completion is claimed.

## Self-review findings and release limits

| Severity     | Finding / disposition                                                                                                                      |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| HIGH         | Original reverse snapshot association gap: corrected in V2 and proven with direct SQL/concurrent commits.                                  |
| HIGH         | Review found caller search-path shadowing in the initial trigger: corrected by schema-qualified lookup and regression.                     |
| MEDIUM       | Valid published Quiz content may exceed the cap: documented temporary LD78-03 compatibility limit; capacity release gate remains.          |
| NOT A DEFECT | LD78-02 permits private plaintext locator storage conditionally; application controls verified, infrastructure confidentiality unverified. |
| NOT A DEFECT | Snapshot-local number/label identities are accepted by LD78-04; deferred Attempt/remapping/grading contracts remain open.                  |

No known remaining confirmed Blocker/High in this bounded correction. This is
self-review, not Coordinator approval. TLS, backups/encryption/key management,
proxy/APM redaction, fenced primary failover, acknowledged-commit durability,
mixed-version rollout, capacity and the earlier Netty diagnostic remain external
gates. Monitoring/privacy release gates remain. No merge, auto-merge, production
flags, CLOSE, Attempt, grading, monitoring or frontend work is authorized here.
Temporary topology processes are shut down by tests; no revision-verified persistent
browser fixture or Chromium LD-05 recovery/uncertain-rotation evidence is supplied.
Keep that separate Dev 2 integration request open.
