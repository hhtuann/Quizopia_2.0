package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.domain.Monitoring.*;
import com.quizopia.proctoring.persistence.JdbcMonitoringStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Actual PostgreSQL persistence/integrity tests; authority snapshots are explicit synthetic inputs. */
@Testcontainers
class JdbcMonitoringStoreIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");

    static DriverManagerDataSource source;
    static JdbcTemplate db;
    JdbcMonitoringStore store;
    MonitoringProperties properties;
    UUID student;
    UUID teacher;
    UUID attempt;
    UUID publication;
    UUID classroom;
    Instant deadline;

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        db = new JdbcTemplate(source);
    }

    @BeforeEach
    void fixture() {
        db.execute(
                "TRUNCATE monitoring_access_audit, monitoring_evidence, monitoring_sessions RESTART IDENTITY CASCADE");
        properties = new MonitoringProperties();
        store = new JdbcMonitoringStore(db, new DataSourceTransactionManager(source), properties);
        student = UUID.randomUUID();
        teacher = UUID.randomUUID();
        attempt = UUID.randomUUID();
        publication = UUID.randomUUID();
        classroom = UUID.randomUUID();
        deadline = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
    }

    MonitoringAuthority.Actor actor(UUID id, String role) {
        return new MonitoringAuthority.Actor(
                id, Set.of(role), "synthetic-user-evidence", Instant.now().plusSeconds(600));
    }

    MonitoringAuthority.Snapshot authority(UUID id, String role, boolean active) {
        return new MonitoringAuthority.Snapshot(
                id, role, attempt, publication, classroom, student, active, deadline, Instant.now());
    }

    Session session() {
        return store.create(actor(student, "STUDENT"), authority(student, "STUDENT", true));
    }

    Event eventEnvelope(
            UUID eventId, UUID streamId, Long sequence, EventType type, Instant observedAt, Integer questionNumber) {
        return new Event(eventId, streamId, sequence, type, observedAt, questionNumber, 1);
    }

    Event event(UUID stream, long sequence) {
        return eventEnvelope(UUID.randomUUID(), stream, sequence, EventType.WINDOW_FOCUS, Instant.now(), null);
    }

    Acknowledgment append(Session session, Event event) {
        return store.append(actor(student, "STUDENT"), authority(student, "STUDENT", true), session.id(), event);
    }

    int count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    MonitoringFailure failure(int status, String code, org.junit.jupiter.api.function.Executable operation) {
        var failure = assertThrows(MonitoringFailure.class, operation);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
        return failure;
    }

    @Test
    void repeatedAndConcurrentCreationHasOneSessionAndOneServerStart() throws Exception {
        Session first = session();
        assertEquals(first, session());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(this::session);
            var b = executor.submit(this::session);
            assertEquals(first.id(), a.get(10, TimeUnit.SECONDS).id());
            assertEquals(first.id(), b.get(10, TimeUnit.SECONDS).id());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("monitoring_sessions"));
        assertEquals(1, count("monitoring_evidence"));
        assertEquals("SERVER_AUTHORITY", db.queryForObject("SELECT source FROM monitoring_evidence", String.class));
        assertEquals("SESSION_STARTED", db.queryForObject("SELECT event_type FROM monitoring_evidence", String.class));
    }

    @Test
    void exactEventReplayPreservesOriginalReceiptAndServerTime() {
        var session = session();
        var event = event(UUID.randomUUID(), 1);
        var first = append(session, event);
        assertEquals(first, append(session, event));
        assertEquals(2, count("monitoring_evidence"));
        assertTrue(first.receivedAt().isAfter(session.createdAt())
                || first.receivedAt().equals(session.createdAt()));
    }

    @Test
    void eventIdOrSequencePayloadConflictIsMutationFree() {
        var session = session();
        var event = event(UUID.randomUUID(), 7);
        append(session, event);
        var sameId = eventEnvelope(
                event.eventId(), event.streamId(), event.sequence(), EventType.WINDOW_BLUR, event.observedAt(), null);
        var sameSequence = eventEnvelope(
                UUID.randomUUID(), event.streamId(), event.sequence(), event.type(), event.observedAt(), null);
        for (var conflict : List.of(sameId, sameSequence)) {
            failure(409, "EVENT_CONFLICT", () -> append(session, conflict));
            assertEquals(2, count("monitoring_evidence"));
        }
    }

    @Test
    void outOfOrderSequencesAndDistinctStreamsPreserveServerReceiptOrder() {
        var session = session();
        var stream = UUID.randomUUID();
        var high = append(session, event(stream, 20));
        var low = append(session, event(stream, 2));
        var other = append(session, event(UUID.randomUUID(), 2));
        assertTrue(high.ordinal() < low.ordinal() && low.ordinal() < other.ordinal());
        assertEquals(4, count("monitoring_evidence"));
    }

    @Test
    void simultaneousDuplicateIngestionCommitsOneReceipt() throws Exception {
        var session = session();
        var event = event(UUID.randomUUID(), 1);
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Acknowledgment> action = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return append(session, event);
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(2, count("monitoring_evidence"));
    }

    @Test
    void terminalReconciliationHasOneServerEndAndOriginalReceiptRemainsReplayable() {
        var session = session();
        var event = event(UUID.randomUUID(), 1);
        var ack = append(session, event);
        var inactive = authority(student, "STUDENT", false);
        var ended = store.reconcile(actor(student, "STUDENT"), inactive, session.id(), true);
        assertEquals("ENDED", ended.status());
        assertNotNull(ended.endedAt());
        assertEquals(
                ended,
                store.reconcile(actor(student, "STUDENT"), authority(student, "STUDENT", false), session.id(), false));
        assertEquals(
                ack,
                store.append(actor(student, "STUDENT"), authority(student, "STUDENT", false), session.id(), event));
        failure(
                409,
                "SESSION_ENDED",
                () -> store.append(
                        actor(student, "STUDENT"),
                        authority(student, "STUDENT", false),
                        session.id(),
                        event(UUID.randomUUID(), 2)));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_evidence WHERE event_type='SESSION_ENDED'", Integer.class));
        assertEquals(1, count("monitoring_access_audit"));
    }

    @Test
    void staleFutureExpiredAndWrongActorAuthorityNeverWritesEvidence() {
        var session = session();
        var original = authority(student, "STUDENT", true);
        for (Instant checked :
                List.of(Instant.now().minusSeconds(10), Instant.now().plusSeconds(10))) {
            var invalid = new MonitoringAuthority.Snapshot(
                    original.actorId(),
                    original.viewerRole(),
                    original.attemptId(),
                    original.publicationId(),
                    original.classroomId(),
                    original.studentId(),
                    true,
                    deadline,
                    checked);
            failure(
                    503,
                    "AUTHORITY_UNAVAILABLE",
                    () -> store.append(actor(student, "STUDENT"), invalid, session.id(), event(UUID.randomUUID(), 1)));
        }
        var expired = new MonitoringAuthority.Actor(
                student, Set.of("STUDENT"), "synthetic", Instant.now().minusSeconds(1));
        failure(
                503,
                "AUTHORITY_UNAVAILABLE",
                () -> store.append(
                        expired, authority(student, "STUDENT", true), session.id(), event(UUID.randomUUID(), 1)));
        failure(
                503,
                "AUTHORITY_UNAVAILABLE",
                () -> store.append(
                        actor(UUID.randomUUID(), "STUDENT"), original, session.id(), event(UUID.randomUUID(), 1)));
        assertEquals(1, count("monitoring_evidence"));
    }

    @Test
    void differentPublicationClassroomStudentOrDeadlineCannotRebindSession() {
        var session = session();
        var a = authority(student, "STUDENT", true);
        var variants = List.of(
                new MonitoringAuthority.Snapshot(
                        student,
                        "STUDENT",
                        UUID.randomUUID(),
                        publication,
                        classroom,
                        student,
                        true,
                        deadline,
                        Instant.now()),
                new MonitoringAuthority.Snapshot(
                        student,
                        "STUDENT",
                        attempt,
                        UUID.randomUUID(),
                        classroom,
                        student,
                        true,
                        deadline,
                        Instant.now()),
                new MonitoringAuthority.Snapshot(
                        student,
                        "STUDENT",
                        attempt,
                        publication,
                        UUID.randomUUID(),
                        student,
                        true,
                        deadline,
                        Instant.now()),
                new MonitoringAuthority.Snapshot(
                        student,
                        "STUDENT",
                        attempt,
                        publication,
                        classroom,
                        UUID.randomUUID(),
                        true,
                        deadline,
                        Instant.now()),
                new MonitoringAuthority.Snapshot(
                        student,
                        "STUDENT",
                        attempt,
                        publication,
                        classroom,
                        student,
                        true,
                        deadline.plusSeconds(1),
                        Instant.now()));
        for (var value : variants)
            failure(
                    503,
                    "AUTHORITY_UNAVAILABLE",
                    () -> store.reconcile(actor(student, "STUDENT"), value, session.id(), true));
        assertEquals(a.attemptId(), store.find(session.id()).attemptId());
        assertEquals(0, count("monitoring_access_audit"));
    }

    @Test
    void teacherTimelineIsPagedOrderedAndAuditedWithoutAnswers() {
        var session = session();
        append(session, event(UUID.randomUUID(), 9));
        append(
                session,
                eventEnvelope(UUID.randomUUID(), UUID.randomUUID(), 1L, EventType.ANSWER_CHANGED, Instant.now(), 3));
        var first = store.timeline(actor(teacher, "TEACHER"), authority(teacher, "TEACHER", true), session.id(), 0, 2);
        assertEquals(2, first.items().size());
        assertNotNull(first.nextAfter());
        var second = store.timeline(
                actor(teacher, "TEACHER"), authority(teacher, "TEACHER", true), session.id(), first.nextAfter(), 2);
        assertEquals(1, second.items().size());
        assertNull(second.nextAfter());
        assertEquals(EventType.ANSWER_CHANGED, second.items().getFirst().type());
        assertEquals(3, second.items().getFirst().questionNumber());
        assertEquals(2, count("monitoring_access_audit"));
        assertFalse(Arrays.stream(Evidence.class.getRecordComponents())
                .anyMatch(field -> field.getName().equals("answer")));
    }

    @Test
    void studentCannotReadTeacherTimelineAndTeacherCannotAppendStudentSignals() {
        var session = session();
        failure(
                403,
                "ACCESS_DENIED",
                () -> store.timeline(
                        actor(student, "STUDENT"), authority(student, "STUDENT", true), session.id(), 0, 10));
        failure(
                403,
                "ACCESS_DENIED",
                () -> store.append(
                        actor(teacher, "TEACHER"),
                        authority(teacher, "TEACHER", true),
                        session.id(),
                        event(UUID.randomUUID(), 1)));
        assertEquals(1, count("monitoring_evidence"));
        assertEquals(0, count("monitoring_access_audit"));
    }

    @Test
    void realLockTimeoutIsBoundedAndMutationFreeThenRetryRecovers() throws Exception {
        var session = session();
        var event = event(UUID.randomUUID(), 1);
        try (Connection blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM monitoring_sessions WHERE id=? FOR UPDATE")) {
                statement.setObject(1, session.id());
                statement.executeQuery();
            }
            long start = System.nanoTime();
            failure(503, "PERSISTENCE_UNAVAILABLE", () -> append(session, event));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000);
            assertEquals(1, count("monitoring_evidence"));
            blocker.rollback();
        }
        assertNotNull(append(session, event));
    }

    @Test
    void createWaitingOnActualRowLockCannotReturnActiveAfterDeadline() throws Exception {
        deadline = Instant.now().plusMillis(750).truncatedTo(ChronoUnit.MICROS);
        var session = session();
        var executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = source.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM monitoring_sessions WHERE id=? FOR UPDATE")) {
                statement.setObject(1, session.id());
                statement.executeQuery();
            }
            var result = executor.submit(() -> {
                try {
                    return store.create(actor(student, "STUDENT"), authority(student, "STUDENT", true));
                } catch (MonitoringFailure error) {
                    return error;
                }
            });
            boolean observedLock = false;
            for (int poll = 0; poll < 50; poll++) {
                observedLock = db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%monitoring_sessions%')",
                        Boolean.class);
                if (observedLock) break;
                Thread.sleep(5);
            }
            assertTrue(observedLock, "The competing transaction must actually wait on PostgreSQL");
            while (Instant.now().isBefore(deadline.plusMillis(20))) Thread.sleep(5);
            blocker.rollback();
            var outcome = result.get(5, TimeUnit.SECONDS);
            assertInstanceOf(MonitoringFailure.class, outcome);
            assertEquals(409, ((MonitoringFailure) outcome).status());
            assertEquals("SESSION_ENDED", ((MonitoringFailure) outcome).code());
            assertEquals(1, count("monitoring_evidence"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void auditInsertFailureRollsBackSessionEndAndServerEvidenceTogether() {
        var session = session();
        db.execute(
                "CREATE FUNCTION test_reject_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture audit failure'; END $$");
        db.execute(
                "CREATE TRIGGER test_reject_audit BEFORE INSERT ON monitoring_access_audit FOR EACH ROW EXECUTE FUNCTION test_reject_audit()");
        try {
            failure(
                    503,
                    "PERSISTENCE_UNAVAILABLE",
                    () -> store.reconcile(
                            actor(student, "STUDENT"), authority(student, "STUDENT", false), session.id(), true));
            assertEquals("ACTIVE", store.find(session.id()).status());
            assertEquals(1, count("monitoring_evidence"));
        } finally {
            db.execute("DROP TRIGGER test_reject_audit ON monitoring_access_audit");
            db.execute("DROP FUNCTION test_reject_audit()");
        }
    }

    @Test
    void rawDmlCannotRewriteOrDeleteEvidenceAuditOrSessionIdentity() {
        var session = session();
        append(session, event(UUID.randomUUID(), 1));
        store.reconcile(actor(student, "STUDENT"), authority(student, "STUDENT", true), session.id(), true);
        for (String sql : List.of(
                "UPDATE monitoring_evidence SET event_type='WINDOW_BLUR'",
                "DELETE FROM monitoring_evidence",
                "UPDATE monitoring_access_audit SET action='TIMELINE_READ'",
                "DELETE FROM monitoring_access_audit",
                "UPDATE monitoring_sessions SET student_id=gen_random_uuid()",
                "DELETE FROM monitoring_sessions")) {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(sql));
        }
        assertEquals(2, count("monitoring_evidence"));
        assertEquals(1, count("monitoring_access_audit"));
        assertEquals(student, store.find(session.id()).studentId());
    }

    @Test
    void integrityTamperingByMigrationOwnerFailsClosedWithoutAudit() {
        var session = session();
        db.execute("ALTER TABLE monitoring_evidence DISABLE TRIGGER monitoring_evidence_guard");
        try {
            db.update("UPDATE monitoring_evidence SET integrity_sha256=?", "0".repeat(64));
        } finally {
            db.execute("ALTER TABLE monitoring_evidence ENABLE TRIGGER monitoring_evidence_guard");
        }
        failure(
                503,
                "EVIDENCE_INTEGRITY_UNAVAILABLE",
                () -> store.timeline(
                        actor(teacher, "TEACHER"), authority(teacher, "TEACHER", true), session.id(), 0, 20));
        assertEquals(0, count("monitoring_access_audit"));
    }

    @Test
    void defaultRetentionHasNoExpiryAndPrivilegedPurgeCannotDeleteIt() {
        var session = session();
        store.reconcile(actor(student, "STUDENT"), authority(student, "STUDENT", true), session.id(), true);
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_evidence WHERE expires_at IS NOT NULL OR retention_policy_reference IS NOT NULL",
                        Integer.class));
        assertEquals(0, db.queryForObject("SELECT monitoring_purge_expired(10)", Integer.class));
        assertEquals(1, count("monitoring_evidence"));
        assertEquals(1, count("monitoring_access_audit"));
    }

    @Test
    void restrictedRuntimeDmlWorksButPurgeTruncateAndGuardChangesAreDenied() {
        db.execute("CREATE ROLE monitoring_runtime_test NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
        db.execute("GRANT USAGE ON SCHEMA public TO monitoring_runtime_test");
        db.execute("GRANT SELECT,INSERT,UPDATE ON monitoring_sessions TO monitoring_runtime_test");
        db.execute("GRANT SELECT,INSERT ON monitoring_evidence,monitoring_access_audit TO monitoring_runtime_test");
        db.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO monitoring_runtime_test");
        try (var connection = source.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("SET ROLE monitoring_runtime_test");
            var runtimeSource = new SingleConnectionDataSource(connection, true);
            var runtimeStore = new JdbcMonitoringStore(
                    new JdbcTemplate(runtimeSource), new DataSourceTransactionManager(runtimeSource), properties);
            var session = runtimeStore.create(actor(student, "STUDENT"), authority(student, "STUDENT", true));
            assertNotNull(runtimeStore.append(
                    actor(student, "STUDENT"),
                    authority(student, "STUDENT", true),
                    session.id(),
                    event(UUID.randomUUID(), 1)));
            assertEquals(2, count("monitoring_evidence"));
            assertThrows(java.sql.SQLException.class, () -> statement.execute("SELECT monitoring_purge_expired(1)"));
            assertThrows(java.sql.SQLException.class, () -> statement.execute("TRUNCATE monitoring_evidence"));
            assertThrows(
                    java.sql.SQLException.class,
                    () -> statement.execute(
                            "ALTER TABLE monitoring_evidence DISABLE TRIGGER monitoring_evidence_guard"));
            assertFalse(db.queryForObject(
                    "SELECT has_function_privilege('monitoring_runtime_test','monitoring_purge_expired(integer)','EXECUTE')",
                    Boolean.class));
        } catch (java.sql.SQLException error) {
            throw new AssertionError(error);
        } finally {
            db.execute("DROP OWNED BY monitoring_runtime_test");
            db.execute("DROP ROLE monitoring_runtime_test");
        }
    }

    @Test
    void unsupportedServerSignalAndMissingSequenceNeverPersist() {
        var session = session();
        for (var invalid : List.of(
                eventEnvelope(UUID.randomUUID(), UUID.randomUUID(), null, EventType.WINDOW_FOCUS, null, null),
                eventEnvelope(UUID.randomUUID(), UUID.randomUUID(), 1L, EventType.SESSION_ENDED, null, null),
                eventEnvelope(UUID.randomUUID(), UUID.randomUUID(), -1L, EventType.WINDOW_FOCUS, null, null),
                eventEnvelope(UUID.randomUUID(), UUID.randomUUID(), 1L, EventType.ANSWER_CHANGED, null, null))) {
            assertThrows(IllegalArgumentException.class, () -> append(session, invalid));
        }
        assertEquals(1, count("monitoring_evidence"));
    }

    @Test
    void lostCommitAcknowledgmentReplaysActualCommittedReceiptWithoutDuplicate() {
        var session = session();
        var event = event(UUID.randomUUID(), 1);
        var failOnce = new AtomicBoolean(true);
        var uncertain = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws java.sql.SQLException {
                var delegate = source.getConnection();
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, arguments) -> {
                            try {
                                Object result = method.invoke(delegate, arguments);
                                if (method.getName().equals("commit") && failOnce.compareAndSet(true, false))
                                    throw new java.sql.SQLException(
                                            "Synthetic lost acknowledgment after actual PostgreSQL commit");
                                return result;
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
            }

            @Override
            public Connection getConnection(String username, String password) throws java.sql.SQLException {
                return getConnection();
            }
        };
        var uncertainStore = new JdbcMonitoringStore(
                new JdbcTemplate(uncertain), new DataSourceTransactionManager(uncertain), properties);
        failure(
                503,
                "PERSISTENCE_UNAVAILABLE",
                () -> uncertainStore.append(
                        actor(student, "STUDENT"), authority(student, "STUDENT", true), session.id(), event));
        assertFalse(failOnce.get(), "The actual commit must occur before the injected acknowledgment loss");
        assertEquals(2, count("monitoring_evidence"));
        var receipt = append(session, event);
        assertEquals(event.eventId(), receipt.id());
        assertEquals(
                receipt.ordinal(),
                db.queryForObject("SELECT ordinal FROM monitoring_evidence WHERE id=?", Long.class, event.eventId()));
        assertEquals(2, count("monitoring_evidence"));
    }

    @Test
    void concurrentAppendAndTerminalReconcileHaveOneCoherentFence() throws Exception {
        var session = session();
        var event = event(UUID.randomUUID(), 1);
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var writer = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return (Object) append(session, event);
                } catch (MonitoringFailure failure) {
                    return failure;
                }
            });
            var terminal = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return store.reconcile(
                        actor(teacher, "TEACHER"), authority(teacher, "TEACHER", false), session.id(), true);
            });
            Object outcome = writer.get(10, TimeUnit.SECONDS);
            assertEquals("ENDED", terminal.get(10, TimeUnit.SECONDS).status());
            assertEquals("ENDED", store.find(session.id()).status());
            if (outcome instanceof MonitoringFailure error) {
                assertEquals(409, error.status());
                assertEquals("SESSION_ENDED", error.code());
                assertEquals(2, count("monitoring_evidence"));
            } else {
                assertInstanceOf(Acknowledgment.class, outcome);
                assertEquals(3, count("monitoring_evidence"));
            }
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM monitoring_evidence WHERE event_type='SESSION_ENDED'",
                            Integer.class));
            assertEquals(
                    teacher,
                    db.queryForObject(
                            "SELECT actor_id FROM monitoring_evidence WHERE event_type='SESSION_ENDED'", UUID.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void ownerRetentionFunctionIsBoundedAndDeletesOnlyExplicitlyExpiredSyntheticHistory() {
        var sessionId = UUID.randomUUID();
        var received = Instant.now().minusSeconds(172800).truncatedTo(ChronoUnit.MICROS);
        db.update(
                "INSERT INTO monitoring_sessions(id,attempt_id,publication_id,classroom_id,student_id,deadline_at,status,created_at) VALUES(?,?,?,?,?,?,'ACTIVE',?)",
                sessionId,
                attempt,
                publication,
                classroom,
                student,
                java.sql.Timestamp.from(deadline),
                java.sql.Timestamp.from(received.minusSeconds(60)));
        for (int index = 0; index < 6; index++) {
            var event = event(UUID.randomUUID(), index);
            Instant expiry = index < 3
                    ? Instant.now().minusSeconds(60)
                    : index < 5 ? Instant.now().plusSeconds(600) : null;
            db.update(
                    "INSERT INTO monitoring_evidence(id,session_id,actor_id,stream_id,client_sequence,event_type,observed_at,received_at,source,integrity_sha256,expires_at,retention_policy_reference) VALUES(?,?,?,?,?,'WINDOW_FOCUS',?,?,'CLIENT_OBSERVATION',?,?,?)",
                    event.eventId(),
                    sessionId,
                    student,
                    event.streamId(),
                    event.sequence(),
                    java.sql.Timestamp.from(event.observedAt()),
                    java.sql.Timestamp.from(received),
                    JdbcMonitoringStore.hash(sessionId, event, received, "CLIENT_OBSERVATION", student),
                    expiry == null ? null : java.sql.Timestamp.from(expiry),
                    expiry == null ? null : "synthetic-reviewed-policy-fixture");
        }
        assertEquals(2, db.queryForObject("SELECT monitoring_purge_expired(2)", Integer.class));
        assertEquals(4, count("monitoring_evidence"));
        assertEquals(1, db.queryForObject("SELECT monitoring_purge_expired(2)", Integer.class));
        assertEquals(3, count("monitoring_evidence"));
        assertEquals(0, db.queryForObject("SELECT monitoring_purge_expired(2)", Integer.class));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.queryForObject("SELECT monitoring_purge_expired(1001)", Integer.class));
        assertEquals(3, count("monitoring_evidence"));
    }
}
