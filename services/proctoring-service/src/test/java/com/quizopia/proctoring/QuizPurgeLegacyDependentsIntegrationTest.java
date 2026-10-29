package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.application.QuizPurgeService.Outcome;
import com.quizopia.proctoring.application.QuizPurgeService.PurgeOperation;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.integration.AssessmentAnswerEvent;
import com.quizopia.proctoring.integration.AssessmentEventInbox;
import com.quizopia.proctoring.persistence.JdbcMonitoringStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * FB-015 corrections H1+H5 on real PostgreSQL 17 with disposable fixtures: legacy
 * (pre-V4, quiz_id NULL) monitoring sessions are purged ONLY through the
 * Assessment-owned dependents inventory supplied by the coordinator; unmapped legacy
 * dependents block the purge truthfully (RECONCILIATION_BLOCKED, never PURGED), and
 * the runtime DB role can neither forge purge markers into deletion nor escalate to
 * the privileged owner - every destructive attempt runs on a second connection
 * authenticated as the real runtime role.
 */
@Testcontainers
class QuizPurgeLegacyDependentsIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");

    static final String APP_ROLE = "proctoring";
    static final String APP_PASSWORD = "proctoring_local_only";

    static DriverManagerDataSource source;
    static JdbcTemplate db;
    JdbcMonitoringStore store;
    QuizRevocationService revocations;
    QuizPurgeService purges;
    UUID student;
    UUID teacher;
    UUID quiz;
    UUID otherQuiz;

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        db = new JdbcTemplate(source);
    }

    @BeforeEach
    void fixture() {
        db.execute("TRUNCATE quiz_revocation_fences,deletion_command_inbox,deletion_purge_receipts,"
                + "purge_inventory,proctoring_retention_holds,proctoring_provider_cleanup,assessment_monitoring_inbox,"
                + "purge_progress,media_erasure_requests,monitoring_access_audit,monitoring_evidence,"
                + "monitoring_media_grants,monitoring_media_rooms,monitoring_sessions RESTART IDENTITY CASCADE");
        var manager = new DataSourceTransactionManager(source);
        store = new JdbcMonitoringStore(db, manager, new MonitoringProperties());
        revocations = new QuizRevocationService(db, manager);
        purges = new QuizPurgeService(db, manager, "livekit");
        student = UUID.randomUUID();
        teacher = UUID.randomUUID();
        quiz = UUID.randomUUID();
        otherQuiz = UUID.randomUUID();
    }

    /** A legacy pre-V4 session: quiz_id stays NULL, ids reference assessment rows. */
    UUID legacySession(UUID attemptId, UUID publicationId, UUID sessionStudent, boolean withEvidenceAndMedia) {
        UUID id = UUID.randomUUID();
        Instant deadline = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        db.update(
                "INSERT INTO monitoring_sessions(id,attempt_id,publication_id,classroom_id,student_id,deadline_at,status,created_at)"
                        + " VALUES(?,?,?,?,?,?,'ACTIVE',clock_timestamp())",
                id,
                attemptId,
                publicationId,
                UUID.randomUUID(),
                sessionStudent,
                java.sql.Timestamp.from(deadline));
        if (withEvidenceAndMedia) {
            db.update(
                    "INSERT INTO monitoring_evidence(id,session_id,actor_id,event_type,received_at,integrity_sha256,source)"
                            + " VALUES(?,?,?,'SESSION_ENDED',clock_timestamp(),'"
                            + "a".repeat(64) + "','SERVER_AUTHORITY')",
                    UUID.randomUUID(),
                    id,
                    sessionStudent);
            db.update(
                    "INSERT INTO monitoring_access_audit(session_id,actor_id,viewer_role,action,accessed_at)"
                            + " VALUES(?,?,'STUDENT','SESSION_READ',clock_timestamp())",
                    id,
                    sessionStudent);
            db.update(
                    "INSERT INTO monitoring_media_rooms(session_id,room_name) VALUES(?,?)",
                    id,
                    "m-" + id.toString().substring(0, 16));
            db.update(
                    "INSERT INTO monitoring_media_grants(id,session_id,actor_id,viewer_role,participant_identity,lease_until,state)"
                            + " VALUES(?,?,?,'STUDENT',?,clock_timestamp()+interval '10 minutes','PREPARED')",
                    UUID.randomUUID(),
                    id,
                    sessionStudent,
                    "p-" + id.toString().substring(0, 16));
        }
        return id;
    }

    UUID fenceAndAcknowledge(UUID targetQuiz) {
        UUID operation = UUID.randomUUID();
        revocations.applyBatch(List.of(new QuizRevocationService.Operation(operation, targetQuiz)));
        assertTrue(revocations.acknowledgeDue(10) >= 1, "fixture fence must acknowledge");
        return operation;
    }

    Connection appConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }

    JdbcTemplate app() {
        return new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), APP_ROLE, APP_PASSWORD));
    }

    Outcome round(UUID operation, UUID attempt, int budget) {
        return purges.purgeBatch(List.of(new PurgeOperation(operation, quiz, List.of(), List.of(attempt))), budget)
                .getFirst()
                .status();
    }

    void hold(JdbcTemplate connection, UUID hold, UUID session) {
        connection.queryForObject(
                "SELECT proctoring_set_hold(?,?,?,'institution','appeal','reviewer',"
                        + "clock_timestamp()+interval '1 day',clock_timestamp()+interval '1 hour')",
                Object.class,
                hold,
                quiz,
                session);
    }

    @Test
    void shrinkingReplayCannotLoseLegacyScopeAndRetriesSurviveReconstruction() {
        UUID attempt = UUID.randomUUID();
        UUID session = legacySession(attempt, UUID.randomUUID(), student, true);
        UUID operation = fenceAndAcknowledge(quiz);
        assertEquals(Outcome.IN_PROGRESS, round(operation, attempt, 1)); // capture budget
        assertEquals(Outcome.PENDING_PROVIDER, round(operation, attempt, 1));
        assertEquals(
                Outcome.INVENTORY_MISMATCH,
                purges.purgeBatch(List.of(new PurgeOperation(operation, quiz, List.of(), List.of())), 1)
                        .getFirst()
                        .status());
        assertEquals(
                Outcome.INVENTORY_MISMATCH,
                purges.purgeBatch(List.of(new PurgeOperation(operation, quiz)), 1)
                        .getFirst()
                        .status());
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM monitoring_sessions WHERE id=?", Long.class, session));
        db.update(
                "INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES(?,'synthetic-ack')",
                operation);
        var runtime = app();
        purges = new QuizPurgeService(runtime, new DataSourceTransactionManager(runtime.getDataSource()), "livekit");
        Outcome state;
        int rounds = 0;
        do {
            long before = db.queryForObject(
                    "SELECT (SELECT count(*) FROM monitoring_sessions)+(SELECT count(*) FROM monitoring_evidence)"
                            + "+(SELECT count(*) FROM monitoring_access_audit)+(SELECT count(*) FROM monitoring_media_rooms)"
                            + "+(SELECT count(*) FROM monitoring_media_grants)",
                    Long.class);
            state = round(operation, attempt, 1);
            long after = db.queryForObject(
                    "SELECT (SELECT count(*) FROM monitoring_sessions)+(SELECT count(*) FROM monitoring_evidence)"
                            + "+(SELECT count(*) FROM monitoring_access_audit)+(SELECT count(*) FROM monitoring_media_rooms)"
                            + "+(SELECT count(*) FROM monitoring_media_grants)",
                    Long.class);
            assertTrue(before - after <= 1, "total content budget applies across steps");
            assertTrue(++rounds < 20);
        } while (state == Outcome.IN_PROGRESS);
        assertEquals(Outcome.PURGED, state);
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM monitoring_sessions WHERE id=?", Long.class, session));
        assertEquals(
                Outcome.INVENTORY_MISMATCH,
                purges.purgeBatch(List.of(new PurgeOperation(operation, quiz, List.of(), List.of())), 1)
                        .getFirst()
                        .status());
    }

    @Test
    void providerUnavailablePreservesEveryLocatorUntilIndependentAcknowledgment() throws Exception {
        UUID attempt = UUID.randomUUID();
        UUID session = legacySession(attempt, UUID.randomUUID(), student, true);
        UUID operation = fenceAndAcknowledge(quiz);
        for (int i = 0; i < 3; i++) {
            assertEquals(Outcome.PENDING_PROVIDER, round(operation, attempt, 100));
            assertFalse(purges.status(quiz).orElseThrow().purged());
            assertEquals("PENDING_PROVIDER", purges.status(quiz).orElseThrow().providerErasure());
            assertNotNull(db.queryForObject(
                    "SELECT room_name FROM monitoring_media_rooms WHERE session_id=?", String.class, session));
            assertNotNull(db.queryForObject(
                    "SELECT participant_identity FROM monitoring_media_grants WHERE session_id=?",
                    String.class,
                    session));
        }
        assertThrows(org.springframework.dao.DataAccessException.class, () -> app().update(
                        "INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES(?,'forged')",
                        operation));
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE quizopia_proctoring_custodian");
            statement.execute("INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES('"
                    + operation + "','synthetic-provider-ack')");
        }
        assertEquals(Outcome.PURGED, round(operation, attempt, 100));
        assertEquals("ACKNOWLEDGED", purges.status(quiz).orElseThrow().providerErasure());
    }

    AssessmentAnswerEvent event(UUID session, UUID attempt, UUID publication, String revision) {
        Instant time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID classroom = session == null
                ? UUID.randomUUID()
                : db.queryForObject("SELECT classroom_id FROM monitoring_sessions WHERE id=?", UUID.class, session);
        Instant deadline = session == null
                ? time.plusSeconds(3600)
                : db.queryForObject(
                        "SELECT deadline_at FROM monitoring_sessions WHERE id=?",
                        (r, n) -> r.getTimestamp(1).toInstant(),
                        session);
        return new AssessmentAnswerEvent(
                UUID.randomUUID(),
                AssessmentAnswerEvent.TYPE,
                1,
                time,
                attempt,
                publication,
                classroom,
                student,
                time.minusSeconds(60),
                deadline,
                1,
                revision);
    }

    @Test
    void appliedPendingAndPreSessionInboxCopiesArePurgedAndStaleDeliveryCannotRefill() {
        UUID attempt = UUID.randomUUID(), publication = UUID.randomUUID();
        UUID session = legacySession(attempt, publication, student, false);
        var inbox = new AssessmentEventInbox(db, new DataSourceTransactionManager(source), new MonitoringProperties());
        var applied = event(session, attempt, publication, "1");
        inbox.accept(applied);
        assertEquals(1, inbox.drain());
        var pending = event(session, attempt, publication, "2");
        inbox.accept(pending);
        var preSession = event(null, UUID.randomUUID(), publication, "1");
        inbox.accept(preSession);
        UUID operation = fenceAndAcknowledge(quiz);
        var command =
                new PurgeOperation(operation, quiz, List.of(publication), List.of(attempt, preSession.attemptId()));
        Outcome state = purges.purgeBatch(List.of(command), 2).getFirst().status();
        assertEquals(Outcome.IN_PROGRESS, state); // captured session + one inbox deletion
        assertEquals(0, inbox.drain());
        inbox.accept(event(session, attempt, publication, "3"));
        assertEquals(
                1L,
                db.queryForObject("SELECT count(*) FROM monitoring_evidence WHERE session_id=?", Long.class, session));
        int guard = 0;
        while (state == Outcome.IN_PROGRESS) {
            state = purges.purgeBatch(List.of(command), 2).getFirst().status();
            assertTrue(++guard < 20);
        }
        assertEquals(Outcome.PURGED, state);
        inbox.accept(applied);
        inbox.accept(pending);
        inbox.accept(preSession);
        assertEquals(0, inbox.drain());
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM assessment_monitoring_inbox", Long.class));
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM monitoring_evidence", Long.class));
        assertTrue(purges.status(quiz).orElseThrow().steps().containsKey("assessment_monitoring_inbox"));
    }

    @Test
    void holdsRequireCustodianAndSerializeWithIrreversiblePurge() throws Exception {
        UUID attempt = UUID.randomUUID();
        UUID session = legacySession(attempt, UUID.randomUUID(), student, true);
        UUID operation = fenceAndAcknowledge(quiz);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> hold(app(), UUID.randomUUID(), session));
        // Hold is uncommitted on one connection while purge starts on another.
        try (Connection custodian = source.getConnection()) {
            custodian.setAutoCommit(false);
            try (Statement statement = custodian.createStatement()) {
                statement.execute("SET ROLE quizopia_proctoring_custodian");
                statement.execute(
                        "SELECT proctoring_set_hold('" + UUID.randomUUID() + "','" + quiz + "','" + session
                                + "','institution','appeal','reviewer',clock_timestamp()+interval '1 day',clock_timestamp()+interval '1 hour')");
            }
            var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var started = new java.util.concurrent.CountDownLatch(1);
                var pending = worker.submit(() -> {
                    started.countDown();
                    return round(operation, attempt, 100);
                });
                assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
                Thread.sleep(100);
                assertFalse(pending.isDone());
                custodian.commit();
                assertEquals(Outcome.BLOCKED_BY_RETENTION, pending.get(5, java.util.concurrent.TimeUnit.SECONDS));
            } finally {
                worker.shutdownNow();
            }
        }
        assertEquals(
                1L,
                db.queryForObject("SELECT count(*) FROM monitoring_evidence WHERE session_id=?", Long.class, session));
        assertFalse(purges.status(quiz).orElseThrow().purged());
        assertThrows(org.springframework.dao.DataAccessException.class, () -> app().update(
                        "UPDATE proctoring_retention_holds SET expires_at=clock_timestamp()"));
        // Disposable test simulates an expired hold; no runtime release/override exists.
        db.update(
                "UPDATE proctoring_retention_holds SET review_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 hour'");
        db.update(
                "INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES(?,'synthetic-ack')",
                operation);
        assertEquals(Outcome.IN_PROGRESS, round(operation, attempt, 1));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> hold(db, UUID.randomUUID(), session));
        assertEquals(Outcome.PURGED, round(operation, attempt, 100));
    }

    @Test
    void securityDefinerUsesRealTablesAndHasNonSuperuserOwner() throws Exception {
        assertEquals(
                "quizopia_proctoring_owner",
                db.queryForObject(
                        "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid="
                                + "'monitoring_purge_quiz(uuid,uuid,uuid[],uuid[],integer)'::regprocedure",
                        String.class));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                                + " WHERE n.nspname='public' AND c.relkind='r' AND c.relname<>'flyway_schema_history'"
                                + " AND (has_table_privilege('proctoring',c.oid,'DELETE') OR has_table_privilege('proctoring',c.oid,'TRUNCATE'))",
                        Long.class));
        UUID attempt = UUID.randomUUID();
        UUID session = legacySession(attempt, UUID.randomUUID(), student, false);
        UUID operation = fenceAndAcknowledge(quiz);
        try (Connection connection = appConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TEMP TABLE quiz_revocation_fences (LIKE public.quiz_revocation_fences INCLUDING ALL)");
            statement.execute(
                    "CREATE TEMP TABLE deletion_purge_receipts (LIKE public.deletion_purge_receipts INCLUDING ALL)");
            statement.execute("CREATE TEMP TABLE monitoring_sessions (LIKE public.monitoring_sessions INCLUDING ALL)");
            statement.execute("CREATE TEMP TABLE purge_inventory(operation_id uuid)");
            try (var rows = statement.executeQuery("SELECT public.monitoring_purge_quiz('" + quiz + "','" + operation
                    + "','{}',ARRAY['" + attempt + "']::uuid[],100)")) {
                assertTrue(rows.next());
                assertEquals("PURGED", rows.getString(1));
            }
            assertThrows(
                    SQLException.class,
                    () -> statement.execute("INSERT INTO public.deletion_purge_receipts VALUES('" + UUID.randomUUID()
                            + "','" + quiz + "','verified_zero',0,clock_timestamp())"));
            assertThrows(SQLException.class, () -> statement.execute("SELECT public.monitoring_purge_expired(100)"));
        }
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM monitoring_sessions WHERE id=?", Long.class, session));
        assertTrue(purges.status(quiz).orElseThrow().purged());
    }

    @Test
    void actualFlywayUpgradeWorksAsNonSuperuserWithoutTouchingHistoryOwnership() {
        db.execute("CREATE ROLE proctoring_upgrade LOGIN PASSWORD 'synthetic_migration_only'");
        db.execute("GRANT quizopia_proctoring_owner TO proctoring_upgrade");
        db.execute("CREATE DATABASE proctoring_upgrade OWNER proctoring_upgrade");
        String url = DATABASE.getJdbcUrl().replace("/" + DATABASE.getDatabaseName() + "?", "/proctoring_upgrade?");
        var migrationSource = new DriverManagerDataSource(url, "proctoring_upgrade", "synthetic_migration_only");
        var migrationDb = new JdbcTemplate(migrationSource);
        migrationDb.execute("GRANT USAGE,CREATE ON SCHEMA public TO quizopia_proctoring_owner");
        Flyway.configure().dataSource(migrationSource).target("4").load().migrate();
        assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(20),
                () -> Flyway.configure().dataSource(migrationSource).load().migrate());
        assertEquals(
                "proctoring_upgrade",
                migrationDb.queryForObject(
                        "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='flyway_schema_history'::regclass",
                        String.class));
        assertEquals(
                "quizopia_proctoring_owner",
                migrationDb.queryForObject(
                        "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid="
                                + "'monitoring_purge_quiz(uuid,uuid,uuid[],uuid[],integer)'::regprocedure",
                        String.class));
        assertTrue(Flyway.configure().dataSource(migrationSource).load().validateWithResult().validationSuccessful);
    }

    @Test
    void custodianHoldAlsoBlocksTheOlderExpiredEvidenceProcedure() throws Exception {
        UUID session = legacySession(UUID.randomUUID(), UUID.randomUUID(), student, false);
        db.update(
                "INSERT INTO monitoring_evidence(id,session_id,actor_id,event_type,received_at,integrity_sha256,source,expires_at,retention_policy_reference)"
                        + " VALUES(?,?,?,'SESSION_ENDED',clock_timestamp()-interval '2 days',?,'SERVER_AUTHORITY',clock_timestamp()-interval '1 day','synthetic-policy')",
                UUID.randomUUID(),
                session,
                student,
                "a".repeat(64));
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE quizopia_proctoring_custodian");
            var custodian = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            hold(custodian, UUID.randomUUID(), session);
        }
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.queryForObject("SELECT monitoring_purge_expired(100)", Integer.class));
        assertEquals(
                1L,
                db.queryForObject("SELECT count(*) FROM monitoring_evidence WHERE session_id=?", Long.class, session));
        assertThrows(MonitoringFailure.class, () -> store.find(session));
    }

    @Test
    void resolvedQuizBoundIdsFenceStaleEventsEvenWhenRequestArraysWereOmitted() {
        UUID session = UUID.randomUUID(), attempt = UUID.randomUUID(), publication = UUID.randomUUID();
        db.update(
                "INSERT INTO monitoring_sessions(id,attempt_id,publication_id,classroom_id,student_id,deadline_at,status,created_at,quiz_id)"
                        + " VALUES(?,?,?,?,?,clock_timestamp()+interval '1 hour','ACTIVE',clock_timestamp(),?)",
                session,
                attempt,
                publication,
                UUID.randomUUID(),
                student,
                quiz);
        var stale = event(session, attempt, publication, "1");
        UUID operation = fenceAndAcknowledge(quiz);
        assertEquals(
                Outcome.PURGED,
                purges.purgeBatch(List.of(new PurgeOperation(operation, quiz)), 100)
                        .getFirst()
                        .status());
        var inbox = new AssessmentEventInbox(db, new DataSourceTransactionManager(source), new MonitoringProperties());
        inbox.accept(stale);
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM assessment_monitoring_inbox", Long.class));
        assertEquals(0, inbox.drain());
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update(
                        "INSERT INTO monitoring_sessions(id,attempt_id,publication_id,classroom_id,student_id,deadline_at,status,created_at)"
                                + " VALUES(?,?,?,?,?,clock_timestamp()+interval '1 hour','ACTIVE',clock_timestamp())",
                        UUID.randomUUID(),
                        attempt,
                        publication,
                        UUID.randomUUID(),
                        student));
    }

    String purgeSql(UUID operation, UUID publication, UUID attempt) {
        return "SELECT monitoring_purge_quiz('" + quiz + "','" + operation + "',ARRAY['" + publication
                + "']::uuid[],ARRAY['" + attempt + "']::uuid[],100)";
    }

    void oldSnapshot(Connection connection) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM pg_class")) {
            assertTrue(result.next());
        }
    }

    @Test
    void runtimeOldSnapshotCannotPurgeEvidenceAfterCustodianCommitsHold() throws Exception {
        UUID attempt = UUID.randomUUID(), publication = UUID.randomUUID();
        UUID session = legacySession(attempt, publication, student, true);
        UUID operation = fenceAndAcknowledge(quiz);
        db.update(
                "INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES(?,'synthetic-ack')",
                operation);
        try (Connection runtime = appConnection();
                Statement statement = runtime.createStatement()) {
            oldSnapshot(runtime);
            try (Connection separate = source.getConnection();
                    Statement custody = separate.createStatement()) {
                custody.execute("SET ROLE quizopia_proctoring_custodian");
                hold(new JdbcTemplate(new SingleConnectionDataSource(separate, true)), UUID.randomUUID(), session);
            }
            var denied = assertThrows(
                    SQLException.class, () -> statement.execute(purgeSql(operation, publication, attempt)));
            assertEquals("25001", denied.getSQLState());
            runtime.rollback();
        }
        assertEquals(
                1L,
                db.queryForObject("SELECT count(*) FROM monitoring_evidence WHERE session_id=?", Long.class, session));
        assertFalse(purges.status(quiz).orElseThrow().purged());
        var runtimeDb = app();
        assertTrue(
                new QuizPurgeService(runtimeDb, new DataSourceTransactionManager(runtimeDb.getDataSource()), "livekit")
                        .status(quiz)
                        .orElseThrow()
                        .retentionHoldActive());
        assertEquals(
                Outcome.BLOCKED_BY_RETENTION,
                purges.purgeBatch(
                                List.of(new PurgeOperation(operation, quiz, List.of(publication), List.of(attempt))),
                                100)
                        .getFirst()
                        .status());
    }

    @Test
    void oldInboxSnapshotBeforeInventoryCannotInsertAfterPurge() throws Exception {
        UUID attempt = UUID.randomUUID(), publication = UUID.randomUUID();
        UUID session = legacySession(attempt, publication, student, false);
        var stale = event(session, attempt, publication, "1");
        UUID operation = fenceAndAcknowledge(quiz);
        try (Connection runtime = appConnection()) {
            oldSnapshot(runtime);
            assertEquals(
                    Outcome.PURGED,
                    purges.purgeBatch(
                                    List.of(new PurgeOperation(
                                            operation, quiz, List.of(publication), List.of(attempt))),
                                    100)
                            .getFirst()
                            .status());
            try (var insert = runtime.prepareStatement(
                    "INSERT INTO assessment_monitoring_inbox(event_id,attempt_id,payload,payload_sha256) VALUES(?,?,?::jsonb,?)")) {
                insert.setObject(1, stale.eventId());
                insert.setObject(2, attempt);
                insert.setString(3, new String(stale.bytes(), java.nio.charset.StandardCharsets.UTF_8));
                insert.setString(4, "a".repeat(64));
                assertEquals(
                        "25001",
                        assertThrows(SQLException.class, insert::executeUpdate).getSQLState());
                runtime.rollback();
                runtime.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                runtime.setAutoCommit(true);
                assertEquals(
                        0, insert.executeUpdate(), "fresh READ COMMITTED delivery is fenced before retaining payload");
            }
        }
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM assessment_monitoring_inbox", Long.class));
        assertEquals(
                Outcome.PURGED,
                purges.purgeBatch(
                                List.of(new PurgeOperation(operation, quiz, List.of(publication), List.of(attempt))),
                                100)
                        .getFirst()
                        .status());
    }

    @Test
    void oldCustodianSnapshotCannotInstallHoldAfterPurgeOrForgeCleanupUnderOldIsolation() throws Exception {
        UUID attempt = UUID.randomUUID(), publication = UUID.randomUUID();
        UUID session = legacySession(attempt, publication, student, false);
        UUID operation = fenceAndAcknowledge(quiz);
        try (Connection custodian = source.getConnection();
                Statement statement = custodian.createStatement()) {
            statement.execute("SET ROLE quizopia_proctoring_custodian");
            oldSnapshot(custodian);
            assertEquals(
                    Outcome.PURGED,
                    purges.purgeBatch(
                                    List.of(new PurgeOperation(
                                            operation, quiz, List.of(publication), List.of(attempt))),
                                    100)
                            .getFirst()
                            .status());
            var denied = assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> hold(
                            new JdbcTemplate(new SingleConnectionDataSource(custodian, true)),
                            UUID.randomUUID(),
                            session));
            assertEquals("25001", ((SQLException) denied.getMostSpecificCause()).getSQLState());
            custodian.rollback();
            assertEquals(
                    "25001",
                    assertThrows(
                                    SQLException.class,
                                    () -> statement.execute(
                                            "INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES('"
                                                    + operation + "','stale')"))
                            .getSQLState());
            custodian.rollback();
        }
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM proctoring_retention_holds", Long.class));
    }

    @Test
    void everyPrivilegedProbeAndExistingWriterRejectsRepeatableReadAtDatabaseBoundary() throws Exception {
        UUID attempt = UUID.randomUUID(), publication = UUID.randomUUID();
        UUID session = legacySession(attempt, publication, student, true);
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            oldSnapshot(connection);
            var statements = List.of(
                    "SELECT monitoring_purge_expired(1)",
                    "SELECT proctoring_attempt_fenced('" + attempt + "')",
                    "SELECT proctoring_session_fenced('" + session + "')",
                    "SELECT proctoring_retention_hold_active('" + quiz + "')",
                    "SELECT proctoring_purge_absent('" + quiz + "','" + UUID.randomUUID() + "')",
                    "SELECT proctoring_purge_authorized('" + quiz + "')",
                    "SELECT proctoring_purge_covers_session('" + session + "')",
                    "UPDATE monitoring_sessions SET status=status WHERE id='" + session + "'",
                    "UPDATE monitoring_media_grants SET lease_until=lease_until WHERE session_id='" + session + "'",
                    "UPDATE monitoring_media_rooms SET room_name=room_name WHERE session_id='" + session + "'",
                    "UPDATE monitoring_evidence SET source=source WHERE session_id='" + session + "'",
                    "INSERT INTO monitoring_access_audit(session_id,actor_id,viewer_role,action,accessed_at) VALUES('"
                            + session + "','" + student + "','STUDENT','SESSION_READ',clock_timestamp())");
            for (String sql : statements) {
                assertEquals(
                        "25001",
                        assertThrows(SQLException.class, () -> statement.execute(sql), sql)
                                .getSQLState(),
                        sql);
                connection.rollback();
            }
        }
    }

    @Test
    void receiptReplayRechecksCurrentAbsenceAndInvalidatesFalseCompletion() {
        UUID attempt = UUID.randomUUID(), publication = UUID.randomUUID();
        // Pre-session copies plus a deliberately stale receipt written by the disposable
        // migration principal model the reviewed inconsistent state with every trigger enabled.
        for (int i = 0; i < 2; i++) {
            db.update(
                    "INSERT INTO assessment_monitoring_inbox(event_id,attempt_id,payload,payload_sha256) VALUES(?,?,?::jsonb,?)",
                    UUID.randomUUID(),
                    attempt,
                    "{\"publicationId\":\"" + publication + "\"}",
                    "a".repeat(64));
        }
        UUID operation = fenceAndAcknowledge(quiz);
        var command = new PurgeOperation(operation, quiz, List.of(publication), List.of(attempt));
        assertEquals(
                Outcome.IN_PROGRESS,
                purges.purgeBatch(List.of(command), 1).getFirst().status());
        db.update(
                "INSERT INTO deletion_purge_receipts(operation_id,quiz_id,step,rows_removed,completed_at) VALUES(?,?,'verified_zero',0,clock_timestamp())",
                operation,
                quiz);
        assertFalse(purges.status(quiz).orElseThrow().purged());
        assertEquals(
                Outcome.RECONCILIATION_BLOCKED,
                purges.purgeBatch(List.of(command), 100).getFirst().status());
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM deletion_purge_receipts WHERE operation_id=? AND step='verified_zero'",
                        Long.class,
                        operation));
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM assessment_monitoring_inbox", Long.class));
        assertEquals(
                Outcome.PURGED,
                purges.purgeBatch(List.of(command), 100).getFirst().status());
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM assessment_monitoring_inbox", Long.class));
    }

    DriverManagerDataSource isolatedDatabase(String name) {
        db.execute("CREATE DATABASE " + name);
        return new DriverManagerDataSource(
                DATABASE.getJdbcUrl().replace("/" + DATABASE.getDatabaseName() + "?", "/" + name + "?"),
                DATABASE.getUsername(),
                DATABASE.getPassword());
    }

    @Test
    void maliciousPublicDefaultAclIsRevokedAndRealRuntimeCannotForgeProviderOrCustodyWrites() {
        String name = "public_acl_" + UUID.randomUUID().toString().replace("-", "");
        var isolated = isolatedDatabase(name);
        var admin = new JdbcTemplate(isolated);
        try {
            Flyway.configure().dataSource(isolated).target("4").load().migrate();
            admin.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT INSERT,UPDATE ON TABLES TO PUBLIC");
            Flyway.configure().dataSource(isolated).load().migrate();
            var runtime = new JdbcTemplate(new DriverManagerDataSource(isolated.getUrl(), APP_ROLE, APP_PASSWORD));
            for (String table : List.of(
                    "proctoring_provider_cleanup",
                    "proctoring_retention_holds",
                    "purge_inventory",
                    "purge_progress",
                    "deletion_purge_receipts",
                    "media_erasure_requests")) {
                assertFalse(runtime.queryForObject(
                        "SELECT has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE')",
                        Boolean.class,
                        table));
                String column = table.equals("proctoring_retention_holds") ? "hold_id" : "operation_id";
                assertThrows(
                        org.springframework.dao.DataAccessException.class,
                        () -> runtime.update("UPDATE " + table + " SET " + column + "=" + column));
            }
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> runtime.update(
                            "INSERT INTO proctoring_provider_cleanup VALUES(?,'forged',clock_timestamp())",
                            UUID.randomUUID()));
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> runtime.update("UPDATE proctoring_retention_holds SET reason='forged'"));
            assertFalse(runtime.queryForObject(
                    "SELECT has_function_privilege(current_user,'monitoring_purge_expired(integer)','EXECUTE')",
                    Boolean.class));
            assertFalse(runtime.queryForObject(
                    "SELECT has_function_privilege(current_user,'proctoring_set_hold(uuid,uuid,uuid,text,text,text,timestamptz,timestamptz)','EXECUTE')",
                    Boolean.class));
        } finally {
            db.execute("DROP DATABASE " + name + " WITH (FORCE)");
        }
    }

    @Test
    void inheritedProtectedWritesAndOperatorExecutionFailMigrationEvenThroughSetRoleOnlyMembership() {
        for (boolean inherit : List.of(true, false)) {
            for (String attack : List.of("tables", "function_defaults", "expiry_execution")) {
                String suffix = UUID.randomUUID().toString().replace("-", "");
                String name = "inherited_" + suffix, role = "acl_" + suffix;
                var isolated = isolatedDatabase(name);
                var admin = new JdbcTemplate(isolated);
                db.execute("CREATE ROLE " + role + " NOLOGIN");
                db.execute("GRANT " + role + " TO proctoring WITH INHERIT " + inherit + ", SET TRUE");
                try {
                    Flyway.configure().dataSource(isolated).target("4").load().migrate();
                    if (attack.equals("tables"))
                        admin.execute(
                                "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT INSERT,UPDATE ON TABLES TO " + role);
                    else if (attack.equals("function_defaults"))
                        admin.execute(
                                "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO " + role);
                    else admin.execute("GRANT EXECUTE ON FUNCTION monitoring_purge_expired(integer) TO " + role);
                    var denied = assertThrows(
                            org.flywaydb.core.api.FlywayException.class,
                            () -> Flyway.configure().dataSource(isolated).load().migrate(),
                            attack);
                    String message = denied.getMessage();
                    assertTrue(
                            message.contains(
                                    attack.equals("tables") ? "effective protected writes" : "operator-only function"),
                            message);
                } finally {
                    db.execute("REVOKE " + role + " FROM proctoring");
                    db.execute("DROP DATABASE " + name + " WITH (FORCE)");
                    db.execute("DROP ROLE " + role);
                }
            }
        }
    }

    @Test
    void legacyDependentsMatchingTheInventoryArePurgedInTheSamePrivilegedPath() {
        UUID mappedAttempt = UUID.randomUUID();
        UUID mappedPublication = UUID.randomUUID();
        UUID legacyMapped = legacySession(mappedAttempt, mappedPublication, student, true);
        UUID unrelatedAttempt = UUID.randomUUID();
        UUID legacyOtherQuiz = legacySession(unrelatedAttempt, UUID.randomUUID(), UUID.randomUUID(), true);

        UUID operation = fenceAndAcknowledge(quiz);
        db.update(
                "INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES(?,'synthetic-provider-receipt')",
                operation);
        var result = purges.purgeBatch(
                        List.of(new PurgeOperation(
                                operation, quiz, List.of(mappedPublication), List.of(mappedAttempt))),
                        5000)
                .getFirst();
        assertEquals(Outcome.PURGED, result.status());
        assertTrue(result.steps().containsKey("verified_zero"));
        // The mapped legacy session and ALL of its evidence/media are gone.
        assertEquals(
                0L, db.queryForObject("SELECT count(*) FROM monitoring_sessions WHERE id=?", Long.class, legacyMapped));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_evidence WHERE session_id=?", Long.class, legacyMapped));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_media_rooms WHERE session_id=?", Long.class, legacyMapped));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_media_grants WHERE session_id=?", Long.class, legacyMapped));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_access_audit WHERE session_id=?", Long.class, legacyMapped));
        // The legacy session of a DIFFERENT quiz (ids not in the inventory) survives.
        assertEquals(
                1L,
                db.queryForObject("SELECT count(*) FROM monitoring_sessions WHERE id=?", Long.class, legacyOtherQuiz));
        var status = purges.status(quiz).orElseThrow();
        assertTrue(status.purged());
    }

    @Test
    void unmappedLegacyDependentsBlockThePurgeTruthfully() {
        UUID orphanAttempt = UUID.randomUUID();
        UUID orphan = legacySession(orphanAttempt, UUID.randomUUID(), student, true);

        UUID operation = fenceAndAcknowledge(quiz);
        // Mapping unavailable: no dependents inventory reaches the purge request.
        var blocked = purges.purgeBatch(List.of(new PurgeOperation(operation, quiz)), 5000)
                .getFirst();
        assertEquals(Outcome.RECONCILIATION_BLOCKED, blocked.status());
        assertEquals(Map.of(), blocked.steps());
        // Nothing was removed and no PURGED receipt exists.
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM monitoring_sessions WHERE id=?", Long.class, orphan));
        assertFalse(purges.status(quiz).orElseThrow().purged());
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM deletion_purge_receipts WHERE operation_id=?", Long.class, operation));

        // The block clears only when the coordinator supplies the full mapping and the
        // legacy row belongs to this quiz's inventory.
        db.update(
                "INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES(?,'synthetic-provider-receipt')",
                operation);
        var resolved = purges.purgeBatch(
                        List.of(new PurgeOperation(operation, quiz, List.of(), List.of(orphanAttempt))), 5000)
                .getFirst();
        assertEquals(Outcome.PURGED, resolved.status());
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM monitoring_sessions WHERE id=?", Long.class, orphan));
    }

    @Test
    void runtimeRoleCannotForgeMarkersDeleteHistoryOrEscalate() throws Exception {
        UUID mappedAttempt = UUID.randomUUID();
        UUID legacy = legacySession(mappedAttempt, UUID.randomUUID(), student, false);
        UUID operation = fenceAndAcknowledge(quiz);

        try (Connection connection = appConnection();
                Statement statement = connection.createStatement()) {
            // Forged quiz marker + raw DELETE: no DELETE privilege on any monitoring
            // table, so the permission layer denies before any trigger is consulted.
            statement.execute("SET quizopia.proctoring_purge_quiz='" + quiz + "'");
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM monitoring_sessions WHERE id='" + legacy + "'"));
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM monitoring_evidence WHERE session_id='" + legacy + "'"));

            // Forged session-scope marker (legacy path) is equally powerless.
            statement.execute("SET quizopia.proctoring_purge_sessions='" + legacy + "'");
            assertThrows(
                    SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM monitoring_evidence WHERE session_id='" + legacy + "'"));

            assertThrows(SQLException.class, () -> statement.execute("TRUNCATE monitoring_sessions"));

            // No escalation to the privileged owner role.
            assertThrows(SQLException.class, () -> statement.execute("SET ROLE quizopia_proctoring_owner"));

            // The bounded purge procedure stays executable BY the runtime role, and
            // removes only rows bound to the provided dependents inventory.
            try (var purged = statement.executeQuery("SELECT monitoring_purge_quiz('" + quiz + "','" + operation
                    + "','{}'::uuid[]," + "ARRAY['" + mappedAttempt + "']::uuid[],5000)")) {
                assertTrue(purged.next());
                assertEquals("PURGED", purged.getString(1));
            }
        }
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM monitoring_sessions WHERE id=?", Long.class, legacy));

        // An unrelated quiz's session survives the whole adversarial session.
        UUID kept = legacySession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true);
        assertEquals(1L, db.queryForObject("SELECT count(*) FROM monitoring_sessions WHERE id=?", Long.class, kept));
        // Receipts stay read-only for the runtime role (tampering via its own
        // credentials, not the migration superuser).
        var app = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), APP_ROLE, APP_PASSWORD));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> app.update(
                        "UPDATE deletion_purge_receipts SET rows_removed=999 WHERE operation_id=?", operation));
    }
}
