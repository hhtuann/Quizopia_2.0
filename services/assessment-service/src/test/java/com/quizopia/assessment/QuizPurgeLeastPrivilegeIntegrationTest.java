package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.QuizPurgeService.Operation;
import com.quizopia.assessment.application.QuizPurgeService.Outcome;
import com.quizopia.assessment.application.QuizRevocationService;
import com.quizopia.assessment.configuration.AssessmentTransactionProperties;
import com.quizopia.assessment.domain.Publication;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.domain.PublicationConfiguration.Audience;
import com.quizopia.assessment.persistence.JdbcPublicationStore;
import com.quizopia.assessment.persistence.JdbcStudentAttemptStore;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * FB-015 correction H1/H2 adversarial boundary tests on real PostgreSQL 17: every
 * destructive statement below runs on a SECOND connection authenticated as the REAL
 * application runtime DB role (the same role compose provisions for the service), not
 * as the migration superuser. The runtime role must be unable to forge the purge
 * marker into crossing a quiz boundary, unable to DELETE protected history even for
 * its own acknowledged quiz, unable to escalate to the owner role - while the bounded
 * SECURITY DEFINER purge procedure stays executable and strictly quiz-scoped.
 * Additional H2 proofs: the purge drops NO constraints at runtime (an unrelated
 * deferrable FK survives), deferred cyclic FKs still reject violating orphans at
 * COMMIT, and concurrent writers during purge behave sanely.
 */
@Testcontainers
class QuizPurgeLeastPrivilegeIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    static final String APP_ROLE = "assessment";
    static final String APP_PASSWORD = "assessment_local_only";

    AtomicFinalizationIntegrationTest f;
    QuizRevocationService revocations;
    QuizPurgeService purges;
    JdbcStudentAttemptStore attempts;
    StudentAttemptService students;
    UUID owner;
    UUID student;
    JdbcTemplate app; // second connection: the application runtime role

    @BeforeEach
    void setup() {
        f = new AtomicFinalizationIntegrationTest();
        f.setup();
        revocations = new QuizRevocationService(f.db, f.manager, AssessmentTransactionProperties.defaults());
        purges = new QuizPurgeService(f.db, f.manager, AssessmentTransactionProperties.defaults());
        attempts = new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core);
        students = new StudentAttemptService(attempts, org.mockito.Mockito.mock(PublicationSources.class), f.core);
        owner = UUID.randomUUID();
        student = UUID.randomUUID();
        var source =
                new DriverManagerDataSource(DB.getJdbcUrl() + "&currentSchema=" + f.schema, APP_ROLE, APP_PASSWORD);
        app = new JdbcTemplate(source);
    }

    /** One dedicated session as the runtime role: session state (GUC) stays visible. */
    Connection appConnection() throws SQLException {
        return DriverManager.getConnection(DB.getJdbcUrl() + "&currentSchema=" + f.schema, APP_ROLE, APP_PASSWORD);
    }

    Instant now() {
        return f.db.queryForObject(
                "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    Publication openPublication(UUID quiz) {
        Instant time = now().truncatedTo(ChronoUnit.MICROS);
        var config = new PublicationConfiguration(
                "Least privilege fixture",
                null,
                quiz,
                UUID.randomUUID(),
                1,
                Audience.PUBLIC,
                null,
                time.minusSeconds(86400),
                time.plusSeconds(86400),
                30,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                0);
        var store = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC());
        var created = store.create(owner, UUID.randomUUID().toString(), config);
        store.open(
                owner,
                created.id(),
                0,
                new String(SnapshotFixtures.snapshot(config, "private source marker"), StandardCharsets.UTF_8));
        return store.owned(owner, created.id());
    }

    UUID activeAttempt(UUID publication, UUID attemptStudent) {
        UUID id = UUID.randomUUID();
        Instant started = now().truncatedTo(ChronoUnit.MICROS);
        long ordinal = 1
                + f.db.queryForObject(
                        "SELECT coalesce(max(ordinal),0) FROM attempts WHERE publication_id=? AND student_id=?",
                        Long.class,
                        publication,
                        attemptStudent);
        f.db.update(
                "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,?,?,1,1,'ACTIVE',30,?,?)",
                id,
                publication,
                attemptStudent,
                ordinal,
                publication,
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(started.plusSeconds(1800)));
        return id;
    }

    UUID fenceAndAcknowledge(UUID quiz) {
        UUID operation = UUID.randomUUID();
        revocations.applyBatch(List.of(new QuizRevocationService.Operation(operation, quiz)));
        assertTrue(revocations.acknowledgeDue(10) >= 1, "fixture fence must acknowledge");
        return operation;
    }

    @Test
    void runtimeRoleCannotForgeMarkerCrossQuizOrDeleteHistoryOrEscalate() throws Exception {
        UUID quizA = UUID.randomUUID();
        UUID quizB = UUID.randomUUID();
        var openA = openPublication(quizA);
        UUID attemptA = activeAttempt(openA.id(), student);
        var openB = openPublication(quizB);
        UUID attemptB = activeAttempt(openB.id(), UUID.randomUUID());
        var runtimeManager = new org.springframework.jdbc.support.JdbcTransactionManager(app.getDataSource());
        var runtimeCore = new com.quizopia.assessment.persistence.JdbcAttemptFinalization(app, runtimeManager);
        UUID studentB = f.db.queryForObject("SELECT student_id FROM attempts WHERE id=?", UUID.class, attemptB);
        runtimeCore.submit(new AttemptFinalization.Manual(
                new AttemptFinalization.SelectedAttempt(attemptB, studentB),
                0,
                JdbcPublicationStore.digest("runtime-submit"),
                GradingDocumentsTest.replacement()));
        UUID operationA = fenceAndAcknowledge(quizA);

        try (Connection connection = appConnection();
                Statement statement = connection.createStatement()) {
            // (a) forged marker + raw DELETE against ANOTHER quiz's rows: denied.
            statement.execute("SET quizopia.assessment_purge_quiz='" + quizB + "'");
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM attempts WHERE id='" + attemptB + "'"));
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM attempt_answers WHERE attempt_id='" + attemptB + "'"));

            // (b) forged marker + raw DELETE on the ACKNOWLEDGED quiz's own history: the
            // runtime role holds no DELETE anywhere (publications included), so the
            // permission layer denies it before any trigger could even be consulted.
            statement.execute("SET quizopia.assessment_purge_quiz='" + quizA + "'");
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate(
                            "UPDATE submitted_answers SET canonical_answers='[]' WHERE attempt_id='" + attemptB + "'"));
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate(
                            "UPDATE grading_results SET earned_weight=0 WHERE attempt_id='" + attemptB + "'"));
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM attempts WHERE id='" + attemptA + "'"));
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM publications WHERE id='" + openA.id() + "'"));

            // TRUNCATE is equally out of reach.
            assertThrows(SQLException.class, () -> statement.execute("TRUNCATE attempts"));

            // (d) no escalation to the privileged owner role.
            assertThrows(SQLException.class, () -> statement.execute("SET ROLE quizopia_assessment_owner"));

            // (c) the bounded purge procedure stays executable BY the runtime role for
            // exactly its acknowledged fence, in this very session.
            try (var purged =
                    statement.executeQuery("SELECT assessment_purge_quiz('" + quizA + "','" + operationA + "',5000)")) {
                assertTrue(purged.next());
                assertEquals("PURGED", purged.getString(1));
            }
        }
        assertEquals(0L, f.db.queryForObject("SELECT count(*) FROM attempts WHERE id=?", Long.class, attemptA));
        assertEquals(0L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quizA));
        // Quiz B stays 100% intact.
        assertEquals(1L, f.db.queryForObject("SELECT count(*) FROM attempts WHERE id=?", Long.class, attemptB));
        assertEquals(1L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quizB));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> app.update(
                        "INSERT INTO realtime_invalidation_outbox(event_id,event_type,event_version,resource_kind,resource_id,occurred_at) VALUES(?,'ATTEMPT_INVALIDATED',1,'ATTEMPT',?,clock_timestamp())",
                        UUID.randomUUID(),
                        attemptA));

        // The runtime role cannot invoke the procedure for quiz B with quiz A's
        // acknowledged operation id.
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> app.queryForObject("SELECT assessment_purge_quiz(?,?,?)", String.class, quizB, operationA, 5000));

        // Retention holds and receipts stay read-only for the runtime role: operator
        // authority only, exactly as documented.
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> app.update(
                        "INSERT INTO retention_holds(id,quiz_id,authority,reason,reviewer,review_at)"
                                + " VALUES(?,?,?,?,?,clock_timestamp())",
                        UUID.randomUUID(),
                        quizA,
                        "forged-authority",
                        "forged hold",
                        "forged-reviewer"));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> app.update(
                        "UPDATE deletion_purge_receipts SET rows_removed=999 WHERE operation_id=?", operationA));
    }

    @Test
    void unrelatedDeferrableForeignKeySurvivesPurgeAndDeferredCyclesStillRejectOrphans() {
        UUID quiz = UUID.randomUUID();
        var open = openPublication(quiz);
        activeAttempt(open.id(), student);
        // An UNRELATED deferrable FK created after migrations: the operational purge
        // must never touch it (no runtime pg_constraint scanning/dropping).
        f.db.execute("CREATE TABLE unrelated_anchor(id integer PRIMARY KEY)");
        f.db.execute("CREATE TABLE unrelated_child(id integer PRIMARY KEY REFERENCES unrelated_anchor(id)"
                + " DEFERRABLE INITIALLY DEFERRED)");
        long constraintsBefore = f.db.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE connamespace='" + f.schema + "'::regnamespace"
                        + " AND contype='f'",
                Long.class);
        assertTrue(constraintsBefore > 0);

        UUID operation = fenceAndAcknowledge(quiz);
        assertEquals(
                "PURGED",
                f.db.queryForObject("SELECT assessment_purge_quiz(?,?,?)", String.class, quiz, operation, 5000));
        assertEquals(
                constraintsBefore,
                f.db.queryForObject(
                        "SELECT count(*) FROM pg_constraint WHERE connamespace='" + f.schema + "'::regnamespace"
                                + " AND contype='f'",
                        Long.class));
        assertEquals(
                1L,
                f.db.queryForObject(
                        "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid"
                                + " JOIN pg_namespace n ON n.oid=t.relnamespace"
                                + " WHERE n.nspname='"
                                + f.schema + "' AND t.relname='unrelated_child' AND c.contype='f'",
                        Long.class));

        // H2 proof: NO ACTION DEFERRED cyclic FKs reject a violating orphan at COMMIT.
        UUID otherQuiz = UUID.randomUUID();
        var other = openPublication(otherQuiz);
        UUID submitted = activeAttempt(other.id(), student);
        students.submit(
                new StudentAttemptStore.Actor(student, now().plusSeconds(3600)),
                submitted,
                0,
                JdbcPublicationStore.digest("deferred-orphan"),
                null,
                "fixture");
        fenceAndAcknowledge(otherQuiz);
        var reachedCommit = new java.util.concurrent.atomic.AtomicBoolean();
        var tx = new org.springframework.transaction.support.TransactionTemplate(f.manager);
        assertThrows(
                RuntimeException.class,
                () -> tx.executeWithoutResult(status -> {
                    f.db.execute("SET CONSTRAINTS ALL DEFERRED");
                    f.db.execute("SET LOCAL ROLE quizopia_assessment_owner");
                    f.db.queryForObject(
                            "SELECT set_config('quizopia.assessment_purge_quiz',?,true)",
                            String.class,
                            otherQuiz.toString());
                    f.db.update(
                            "DELETE FROM grading_question_results WHERE result_id IN (SELECT id FROM grading_results WHERE attempt_id=?)",
                            submitted);
                    f.db.update("DELETE FROM attempt_finalization_audit WHERE attempt_id=?", submitted);
                    assertEquals(1, f.db.update("DELETE FROM grading_results WHERE attempt_id=?", submitted));
                    reachedCommit.set(true); // SELECT/DELETE succeeded: only COMMIT rejects the orphan.
                }));
        assertTrue(reachedCommit.get());
        assertEquals(
                1L,
                f.db.queryForObject("SELECT count(*) FROM grading_results WHERE attempt_id=?", Long.class, submitted));
    }

    @Test
    void concurrentInsertDuringPurgeCompletesOrBlocksSanelyWithoutConstraintLoss() {
        UUID quiz = UUID.randomUUID();
        var open = openPublication(quiz);
        activeAttempt(open.id(), student);
        UUID operation = fenceAndAcknowledge(quiz);
        long constraintsBefore = f.db.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE connamespace='" + f.schema + "'::regnamespace"
                        + " AND contype='f'",
                Long.class);

        // Concurrent writer on tables being purged (inbox inserts are the runtime
        // role's legitimate concurrent traffic). Either blocked or completed - never
        // constraint loss.
        var writer = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    app.update(
                            "INSERT INTO deletion_command_inbox(id,operation_id,quiz_id,received_at,processed_at,attempts)"
                                    + " VALUES(?,?,?,?,?,1)",
                            UUID.randomUUID(),
                            operation,
                            quiz,
                            java.sql.Timestamp.from(Instant.now()),
                            java.sql.Timestamp.from(Instant.now()));
                } catch (org.springframework.dao.DataAccessException denied) {
                    // Blocking/serialization outcomes are acceptable; constraint loss is not.
                }
            }
        });
        writer.start();
        QuizPurgeService.Result result = null;
        int guard = 0;
        while (result == null || result.status() == Outcome.IN_PROGRESS) {
            result = purges.purgeBatch(List.of(new Operation(operation, quiz)), 5000)
                    .getFirst();
            assertTrue(++guard < 50);
        }
        assertEquals(Outcome.PURGED, result.status());
        try {
            writer.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("Concurrent writer join was interrupted");
        }
        assertEquals(
                constraintsBefore,
                f.db.queryForObject(
                        "SELECT count(*) FROM pg_constraint WHERE connamespace='" + f.schema + "'::regnamespace"
                                + " AND contype='f'",
                        Long.class));
        // The purge verified zero remaining rows for the quiz regardless of the
        // concurrent traffic: any late inbox row is a new command for a later round.
        assertTrue(result.steps().containsKey("verified_zero"));
    }

    @Test
    void realFlywayUpgradeAsNonSuperuserLeavesHistoryAndEveryDefinerCorrectlyOwned() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String migrator = "m_" + UUID.randomUUID().toString().replace("-", "");
        f.db.execute("CREATE ROLE " + migrator + " LOGIN PASSWORD 'fixture' INHERIT");
        f.db.execute("GRANT quizopia_assessment_owner TO " + migrator);
        f.db.execute("CREATE SCHEMA " + schema + " AUTHORIZATION " + migrator);
        var old = org.flywaydb.core.Flyway.configure()
                .dataSource(
                        DB.getJdbcUrl() + "&options=-c%20lock_timeout=3000%20-c%20statement_timeout=10000",
                        migrator,
                        "fixture")
                .schemas(schema)
                .defaultSchema(schema)
                .target("10")
                .load();
        old.migrate();
        var upgrade = org.flywaydb.core.Flyway.configure()
                .dataSource(
                        DB.getJdbcUrl() + "&options=-c%20lock_timeout=3000%20-c%20statement_timeout=10000",
                        migrator,
                        "fixture")
                .schemas(schema)
                .defaultSchema(schema)
                .load();
        assertEquals(1, upgrade.migrate().migrationsExecuted);
        upgrade.validate();
        assertEquals(0, upgrade.migrate().migrationsExecuted);
        assertEquals(
                migrator,
                f.db.queryForObject(
                        "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid=?::regclass",
                        String.class,
                        schema + ".flyway_schema_history"));
        assertEquals(
                0L,
                f.db.queryForObject(
                        "SELECT count(*) FROM pg_proc WHERE pronamespace=?::regnamespace AND prosecdef"
                                + " AND proowner<>'quizopia_assessment_owner'::regrole",
                        Long.class,
                        schema));
        assertEquals(
                0L,
                f.db.queryForObject(
                        "SELECT count(*) FROM pg_proc WHERE pronamespace=?::regnamespace AND prosecdef"
                                + " AND NOT ('search_path=pg_catalog, " + schema + ", pg_temp'=ANY(proconfig))",
                        Long.class,
                        schema));
    }

    @Test
    void tempShadowsAndForgedGucCannotBypassHoldsOrPermitUpdates() throws Exception {
        UUID quiz = UUID.randomUUID();
        var open = openPublication(quiz);
        UUID attempt = activeAttempt(open.id(), student);
        UUID operation = fenceAndAcknowledge(quiz);
        try (var custodian = f.source.getConnection();
                var sql = custodian.createStatement()) {
            sql.execute("SET ROLE quizopia_assessment_custodian");
            sql.execute("INSERT INTO retention_holds(id,quiz_id,authority,reason,reviewer,review_at) VALUES('"
                    + UUID.randomUUID() + "','" + quiz
                    + "','institution','appeal','reviewer',clock_timestamp()+interval '1 day')");
        }
        try (var connection = appConnection();
                var sql = connection.createStatement()) {
            sql.execute("CREATE TEMP TABLE retention_holds(quiz_id uuid,expires_at timestamptz)");
            sql.execute("CREATE TEMP TABLE quiz_revocation_fences(quiz_id uuid,operation_id uuid,ack_at timestamptz)");
            sql.execute("CREATE TEMP TABLE purge_admission(quiz_id uuid,begun boolean)");
            sql.execute("SET quizopia.assessment_purge_quiz='" + quiz + "'");
            try (var r = sql.executeQuery(
                    "SELECT " + f.schema + ".assessment_purge_quiz('" + quiz + "','" + operation + "',1)")) {
                assertTrue(r.next());
                assertEquals("BLOCKED_BY_RETENTION", r.getString(1));
            }
            assertThrows(
                    SQLException.class,
                    () -> sql.execute(
                            "UPDATE " + f.schema + ".attempts SET revision=revision+1 WHERE id='" + attempt + "'"));
            assertThrows(
                    SQLException.class,
                    () -> sql.execute("UPDATE " + f.schema + ".publication_snapshots SET payload='{}'"));
            assertThrows(
                    SQLException.class,
                    () -> sql.execute("UPDATE " + f.schema + ".retention_holds SET expires_at=clock_timestamp()"));
            assertThrows(SQLException.class, () -> sql.execute("DELETE FROM " + f.schema + ".retention_holds"));
            assertThrows(SQLException.class, () -> sql.execute("SET ROLE quizopia_assessment_custodian"));
        }
        assertEquals(1L, f.db.queryForObject("SELECT count(*) FROM attempts WHERE id=?", Long.class, attempt));
    }

    @Test
    void holdAndPurgeAdmissionSerializeInBothOrders() throws Exception {
        UUID quiz = UUID.randomUUID();
        openPublication(quiz);
        UUID operation = fenceAndAcknowledge(quiz);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var custodian = f.source.getConnection();
                var sql = custodian.createStatement()) {
            custodian.setAutoCommit(false);
            sql.execute("SET LOCAL ROLE quizopia_assessment_custodian");
            sql.execute("INSERT INTO retention_holds(id,quiz_id,authority,reason,reviewer,review_at) VALUES('"
                    + UUID.randomUUID() + "','" + quiz
                    + "','institution','appeal','reviewer',clock_timestamp()+interval '1 day')");
            var future = executor.submit(
                    () -> app.queryForObject("SELECT assessment_purge_quiz(?,?,1)", String.class, quiz, operation));
            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> future.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            custodian.commit();
            assertEquals("BLOCKED_BY_RETENTION", future.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        UUID quiz2 = UUID.randomUUID();
        openPublication(quiz2);
        UUID operation2 = fenceAndAcknowledge(quiz2);
        var executor2 = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var purge = appConnection();
                var sql = purge.createStatement()) {
            purge.setAutoCommit(false);
            sql.execute("SELECT assessment_purge_quiz('" + quiz2 + "','" + operation2 + "',1)");
            var future = executor2.submit(() -> {
                try (var c = f.source.getConnection();
                        var s = c.createStatement()) {
                    s.execute("SET ROLE quizopia_assessment_custodian");
                    return assertThrows(
                            SQLException.class,
                            () -> s.execute(
                                    "INSERT INTO retention_holds(id,quiz_id,authority,reason,reviewer,review_at) VALUES('"
                                            + UUID.randomUUID() + "','" + quiz2
                                            + "','institution','appeal','reviewer',clock_timestamp()+interval '1 day')"));
                }
            });
            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> future.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            purge.commit();
            assertTrue(future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    .getMessage()
                    .contains("purge already admitted"));
        } finally {
            executor2.shutdownNow();
        }
        assertEquals(
                0L, f.db.queryForObject("SELECT count(*) FROM retention_holds WHERE quiz_id=?", Long.class, quiz2));
    }

    @Test
    void budgetOneCommitsCyclesAndBoundedInventoryWithFreshConnectionsAndRollback() throws Exception {
        var closure = new ClosureIntegrationTest();
        closure.setup();
        closure.advance(0);
        UUID originalStudent = closure.f.student;
        closure.f.student = UUID.randomUUID();
        UUID submitted = closure.f.active(new AtomicFinalizationIntegrationTest.Seed(closure.publication, null), 0);
        closure.f.core.submit(closure.f.manual(submitted, 0, GradingDocumentsTest.replacement()));
        closure.f.student = originalStudent;
        closure.advance(86401);
        UUID request = closure.request();
        closure.confirmed(request);
        // A real committed member/notice cycle, and submitted result cycle in one quiz.
        UUID quiz = UUID.fromString(closure.scalar("SELECT quiz_id FROM publications WHERE id=?", closure.publication));
        var revoke =
                new QuizRevocationService(closure.f.db, closure.f.manager, AssessmentTransactionProperties.defaults());
        UUID operation = UUID.randomUUID();
        revoke.applyBatch(List.of(new QuizRevocationService.Operation(operation, quiz)));
        assertEquals(1, revoke.acknowledgeDue(10));
        String url = DB.getJdbcUrl() + "&currentSchema=" + closure.f.schema;
        // Rollback must undo admission, scope capture and progress, then retries converge.
        try (var c = DriverManager.getConnection(url, APP_ROLE, APP_PASSWORD);
                var s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("SELECT assessment_purge_quiz('" + quiz + "','" + operation + "',1)");
            c.rollback();
        }
        assertEquals(0L, closure.f.db.queryForObject("SELECT count(*) FROM purge_progress", Long.class));
        String outcome = "IN_PROGRESS";
        int rounds = 0;
        while (outcome.equals("IN_PROGRESS")) {
            long before = physicalRows(closure.f.db);
            long markersBefore = closure.f.db.queryForObject(
                    "SELECT (SELECT count(*) FROM purged_publication_markers)+(SELECT count(*) FROM purged_attempt_markers)",
                    Long.class);
            try (var c = DriverManager.getConnection(url, APP_ROLE, APP_PASSWORD);
                    var s = c.createStatement()) {
                c.setAutoCommit(false);
                try (var r = s.executeQuery("SELECT assessment_purge_quiz('" + quiz + "','" + operation + "',1)")) {
                    assertTrue(r.next());
                    outcome = r.getString(1);
                }
                c.commit(); // catches deferred FK errors, not merely successful SELECT
            }
            long removed = before - physicalRows(closure.f.db);
            long captured = closure.f.db.queryForObject(
                            "SELECT (SELECT count(*) FROM purged_publication_markers)+(SELECT count(*) FROM purged_attempt_markers)",
                            Long.class)
                    - markersBefore;
            assertTrue(removed >= 0 && removed <= 3, "at most one three-row cycle per round");
            assertTrue(captured >= 0 && captured <= 1, "inventory consumes budget too");
            assertFalse(removed > 0 && captured > 0, "inventory and deletion share the budget");
            assertTrue(++rounds < 100);
        }
        assertEquals("PURGED", outcome);
        assertEquals(0L, physicalRows(closure.f.db)); // direct physical counts; no parent joins
        assertEquals(1L, closure.f.db.queryForObject("SELECT count(*) FROM purged_publication_markers", Long.class));
        assertTrue(rounds > 5);
    }

    long physicalRows(JdbcTemplate db) {
        var tables = List.of(
                "publications",
                "publication_snapshots",
                "attempts",
                "attempt_answers",
                "submitted_answers",
                "grading_results",
                "grading_question_results",
                "attempt_finalization_audit",
                "attempt_operations",
                "attempt_creation_operations",
                "closure_requests",
                "closure_request_members",
                "assessment_notifications",
                "closure_work_audit",
                "publication_operations",
                "publication_creations",
                "assessment_monitoring_outbox",
                "realtime_invalidation_outbox");
        String query = "SELECT "
                + tables.stream()
                        .map(table -> "(SELECT count(*) FROM " + table + ")")
                        .collect(java.util.stream.Collectors.joining("+"));
        return db.queryForObject(query, Long.class);
    }
}
