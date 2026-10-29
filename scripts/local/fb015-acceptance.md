# FB015 disposable real-browser deletion acceptance

## Run

Requirements: Java 21, Docker/Testcontainers, Node, installed frontend dependencies
and the Playwright Chromium binary (`pnpm exec playwright install chromium` from
`frontend/` if needed). No frontend dev server is required.

From `gateway/`, PowerShell:

```powershell
.\mvnw.cmd -B "-Dtest=QuizDeletionTopologyTest" "-Dfb015.browser=true" test
```

Allow about 6–8 minutes; an external runner should allow at least 10 minutes so
it does not kill Maven before cleanup. On POSIX use `bash mvnw` with the same
arguments. Run alone, not alongside `PublicationTopologyTest`: the bounded
harness reuses that class's startup/authentication/build utilities and static
state rather than copying its Identity fixture. The existing topology helpers are
not modified by this harness.

The class is explicitly opt-in: standard backend CI skips it without requiring
Node or Chromium. An acceptance CI job must install frontend dependencies with
`pnpm install --frozen-lockfile`, run `pnpm exec playwright install --with-deps chromium`
from `frontend/`, then run the Maven command above with Java 21 and Docker.
Once opted in, missing Node/browser dependencies fail the test rather than skip it.

## Topology and evidence

- Five disposable PostgreSQL 17 containers (Identity, Classroom, Quiz, Assessment,
  Proctoring) and disposable Redis. No existing database URL/configuration input.
- Real separate service JVMs, Flyway migrations, actual Gateway and actual
  Identity login/client-credentials issuer. Assessment and Proctoring use their
  restricted runtime DB roles after migration; migration/fixture/custodian
  connections use only their own disposable databases.
- Real headless Chromium navigates to Gateway, logs in through Gateway and runs
  **browser `fetch`**, same-origin through Gateway, for every public business
  request. No `page.route`, HTTP route doubles, APIRequestContext, fabricated JWT,
  persisted token, HAR or trace. Internal coordinator/admission calls retain the
  accepted direct authenticated service-to-service topology.
- Identity/roles/OAuth registrations, Redis admission projection, Classroom
  membership and pre-V4 monitoring/media rows are explicit synthetic fixtures.
  QuizVersions, Publications, attempts and submitted grading are created through
  browser HTTP. The dual-role test account is the existing topology fixture.
- Each run prints a unique `gateway/target/fb015-acceptance/<run-id>/` directory.
  `checks.json` records only completed checkpoints; `result.json` distinguishes
  PASS/FAIL. Sanitized access logs contain method/path/status, never headers or
  request bodies. Maven's `gateway/target/surefire-reports/` is authoritative for
  the assertion result. Existing startup helpers put service/build logs under
  `gateway/target/publication-topology/` and artifacts under each service's
  `target/publication-topology-build/`.
- Teardown closes browser/processes/keys/containers on normal test failures.
  Do not kill the JVM externally. No `.env`, persistent local runtime, existing
  Docker Compose container or non-disposable data is read or changed.

## Assertions

1. Two victim versions, PUBLIC submitted/graded attempt, CLASS active attempt,
   legacy `quiz_id IS NULL` monitoring/evidence/audit/media dependents and an
   unrelated quiz/publication/attempt/session.
2. Actual Proctoring process outage yields `WAITING_DEPENDENCY`; Quiz survives.
3. A synthetic custodian retention hold yields `BLOCKED_BY_RETENTION` while
   post-fence stale Assessment/Proctoring browser routes deny access.
4. Hold expires by wall clock, without trigger bypasses or backdated clocks.
   Provider pending preserves local media bindings and Assessment inventory.
5. Quiz and Proctoring restart, recover the durable operation, and retry.
6. A **synthetic custodian provider receipt** allows the primary purge path;
   this does not perform or certify real LiveKit cleanup.
7. `PRIMARY_PURGED` and `COPY_EXPIRY_PENDING`; latest/operation status discovery,
   same-key replay and mismatched replay after ledger scrub.
8. Zero victim quiz/draft/version/publication/snapshot/attempt/answer/result/
   operation/finalization and legacy monitoring/media rows; ledger confirmation
   title NULL, inventory reduced to version count. Unrelated quiz/attempt/session,
   Identity account and Classroom membership survive.

## Verification evidence (2026-10-29)

The Maven browser command above completed in **4:33**, with
**1 test, 0 failures, 0 errors, 0 skipped**. All eight checkpoints passed.
Run evidence:
`gateway/target/fb015-acceptance/f3ef97cd-a7a7-478a-b6ff-ee698902e4f6/`.
`result.json` records PASS plus the completed checkpoints and explicit limitations.

The initial run exposed a genuine Assessment security-route gap: the coordinator's
dependents inventory request returned 403. The narrowly authorized correction adds
only canonical GET `/internal/security/quiz-revocations/{quizId}/dependents` to
`SecurityPurgeRoutes.matches`. The existing exact SERVICE caller and
`quiz.deletion.purge` scope remain mandatory; no handler is changed.
`QuizRevocationHttpIntegrationTest` now verifies pre-fence 404, post-fence exact
dependent ids without content/unrelated ids, no-store, and denial of revoke-only,
wrong-client and USER principals, wrong methods, noncanonical paths and malformed
transport. The HTTP test fixture follows the existing signed-test-key pattern;
the browser topology uses the real Identity issuer.

From `services/assessment-service/`:

```powershell
.\mvnw.cmd -B "-Dtest=QuizRevocationHttpIntegrationTest" test
.\mvnw.cmd -B verify
```

Results: focused HTTP **8 tests passed**; full Assessment verify **489 tests,
0 failures/errors/skips**, package and full-service Spotless check passed in **6:47**.
The default non-opted-in Gateway invocation was also checked: BUILD SUCCESS,
one browser test skipped, without starting its Node/Chromium/container topology.

Separate checks passed: `node --check scripts/local/fb015-browser.cjs`,
`git diff --check`, and (from `gateway/`)
`.\mvnw.cmd -B "-DspotlessFiles=.*QuizDeletionTopologyTest.java" spotless:check`.

## Limitations

Frontend #125 dialog/navigation/accessibility is not checked out and is not
exercised here. The fetch driver can later be replaced by UI interactions while
retaining the disposable topology and SQL assertions. Backup/CDN/export expiry,
actual LiveKit provider erasure, production TLS, race saturation, every lifecycle,
closure/outbox permutation and exhaustive role/ownership denials remain separate
acceptance/service-suite work. `ALL_COPIES_ERASED` is never claimed.
