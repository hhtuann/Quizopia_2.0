package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.audit.*;
import com.quizopia.assessment.configuration.*;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.persistence.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class AcademicDemoAuditIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AtomicFinalizationIntegrationTest f;
    AtomicFinalizationIntegrationTest.Seed p;
    JdbcStudentAttemptStore students;
    StudentAttemptStore.Actor student;
    ClosureStore.Teacher teacher;
    SensitiveAssessmentPolicy policy;
    AuditEncryption crypto;
    AnswerAuditService audit;
    AnswerAuditStore store;
    UUID attempt;
    Instant epoch;

    MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("server.address", "127.0.0.1")
                .withProperty("spring.datasource.url", DB.getJdbcUrl())
                .withProperty("quizopia.assessment.demo.synthetic-only", "true")
                .withProperty("quizopia.assessment.demo.current-key", "fixture")
                .withProperty(
                        "quizopia.assessment.demo.keys.fixture",
                        Base64.getEncoder().encodeToString(new byte[32]));
    }

    @BeforeEach
    void setup() {
        f = new AtomicFinalizationIntegrationTest();
        f.setup();
        var connectionProperties = new Properties();
        connectionProperties.putAll(f.source.getConnectionProperties());
        connectionProperties.setProperty("options", "-c quizopia.academic_demo=true");
        f.source.setConnectionProperties(connectionProperties);
        p = f.publication(0);
        epoch = f.now();
        UUID owner = UUID.fromString(f.scalar("SELECT owner_id FROM publications WHERE id=?", p.publication()));
        f.db.update(
                "INSERT INTO assessment_demo_subjects VALUES(?,'FICTIONAL_ACADEMIC_DEMO_V1'),(?,'FICTIONAL_ACADEMIC_DEMO_V1')",
                owner,
                f.student);
        var cfg = new AcademicDemoConfiguration();
        policy = cfg.academicDemoPolicy(environment(), f.db);
        crypto = cfg.academicDemoEncryption(environment());
        store = new AnswerAuditStore(f.db, f.manager, policy, crypto, AssessmentTransactionProperties.defaults());
        audit = new AnswerAuditService(store, mock(PublicationSources.class));
        students = new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core);
        student = new StudentAttemptStore.Actor(f.student, epoch.plus(Duration.ofDays(365)));
        teacher = new ClosureStore.Teacher(owner, student.expiresAt(), "synthetic");
        attempt = students.start(
                        student,
                        p.publication(),
                        "START",
                        f.scalar("SELECT private_locator FROM publications WHERE id=?", p.publication()),
                        "a".repeat(64))
                .value()
                .attemptId();
    }

    AnswerAuditStore.ObservationChoice choice(Boolean enabled) {
        return audit.choice(student.id(), student.expiresAt(), "synthetic", p.publication(), attempt, enabled);
    }

    byte[] body(UUID stream, long seq, String from, String to) {
        return ("{\"schemaVersion\":1,\"eventId\":\"" + UUID.randomUUID() + "\",\"streamId\":\"" + stream
                        + "\",\"sequence\":" + seq + ",\"type\":\""
                        + (from.equals("[]") ? "ANSWER_SELECTED" : "ANSWER_CHANGED")
                        + "\",\"questionNumber\":1,\"transition\":{\"fromOptionLabels\":" + from
                        + ",\"toOptionLabels\":" + to + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    AnswerAuditStore.Ack append(byte[] bytes) {
        return audit.append(student.id(), student.expiresAt(), "synthetic", p.publication(), attempt, bytes);
    }

    AnswerAuditStore.Timeline timeline() {
        return assertDoesNotThrow(() -> audit.timeline(teacher, p.publication(), attempt, 0, 100), "timeline");
    }

    void day(int n) {
        f.db.execute(
                "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE AS $$ SELECT timestamptz '"
                        + epoch.plus(Duration.ofDays(n)) + "' $$");
    }

    void publish() {
        new TransactionTemplate(f.manager)
                .executeWithoutResult(s -> new ServerAuditPublisher(f.db, crypto).publish(attempt));
    }

    @Test
    void realStartSaveSubmitAndUnsavedChoicesAreSeparateStableCommittedFacts() {
        assertFalse(choice(null).enabled());
        f.failure("OBSERVATION_NOT_ENABLED", () -> append(body(UUID.randomUUID(), 1, "[]", "[\"A\"]")));
        var opted = choice(true);
        assertNotNull(opted.streamId());
        assertEquals(opted, choice(true));
        var selected = body(opted.streamId(), 1, "[]", "[\"A\"]");
        var changed = body(opted.streamId(), 2, "[\"A\"]", "[\"C\"]");
        assertEquals(append(selected), append(selected));
        append(changed);
        assertEquals("0", f.scalar("SELECT count(*) FROM attempt_answers"));
        String answer = "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"C\"}";
        var saved = students.save(student, attempt, 1, 0, "b".repeat(64), answer);
        assertEquals(saved, students.save(student, attempt, 1, 0, "b".repeat(64), answer));
        f.core.submit(f.manual(attempt, 1, null));
        var view = timeline();
        assertEquals(
                List.of(
                        "ATTEMPT_STARTED",
                        "ANSWER_SELECTED",
                        "ANSWER_CHANGED",
                        "ANSWER_PERSISTED",
                        "ATTEMPT_SUBMITTED",
                        "RESULT_GRADED"),
                view.items().stream().map(AnswerAuditStore.Item::type).toList());
        assertEquals("SAVE", view.items().get(3).operationKind());
        assertEquals("MANUAL_SUBMIT", view.items().get(4).terminalReason());
        assertNotNull(view.items().get(5).resultId());
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        assertEquals("1", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts"));
        assertEquals(
                "t",
                f.scalar(
                        "SELECT bool_and(expires_at=received_at+CASE source WHEN 'SERVER_AUTHORITY' THEN interval '90 days' ELSE interval '30 days' END) FROM assessment_answer_audit"));
        assertEquals(
                "t",
                f.scalar(
                        "SELECT bool_and(expires_at=accessed_at+interval '180 days') FROM assessment_sensitive_read_receipts"));
        assertEquals(6, timeline().items().size());
    }

    @Test
    void authoritativeRollbackAndConcurrentReplayNeverManufactureFacts() throws Exception {
        f.db.execute(
                "CREATE FUNCTION fail_new_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='ANSWER_PERSISTED' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        f.db.execute(
                "CREATE TRIGGER fail_new_audit BEFORE INSERT ON assessment_audit_pending FOR EACH ROW EXECUTE FUNCTION fail_new_audit()");
        String answer = "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}";
        f.failure("PERSISTENCE_UNAVAILABLE", () -> students.save(student, attempt, 1, 0, "b".repeat(64), answer));
        assertEquals("0", f.scalar("SELECT count(*) FROM attempt_answers"));
        assertEquals("0", f.scalar("SELECT revision FROM attempts WHERE id=?", attempt));
        assertEquals("1", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        f.db.execute("DROP TRIGGER fail_new_audit ON assessment_audit_pending");
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Object> save = () -> students.save(student, attempt, 1, 0, "b".repeat(64), answer);
            var a = pool.submit(save);
            var b = pool.submit(save);
            assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
        assertEquals("2", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        assertThrows(PublicationFailure.class, () -> new TransactionTemplate(f.manager)
                .executeWithoutResult(
                        s -> new ServerAuditPublisher(f.db, new AuditEncryption.Pending()).publish(attempt)));
        assertEquals("2", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        publish();
        publish();
        assertEquals("2", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
    }

    @Test
    void withdrawalAndExpiredStreamsCannotResurrectButAcademicSubmissionStillWorks() {
        var first = choice(true);
        var event = body(first.streamId(), 1, "[]", "[\"A\"]");
        append(event);
        choice(false);
        f.failure("OBSERVATION_NOT_ENABLED", () -> append(event));
        var second = choice(true);
        assertNotEquals(first.streamId(), second.streamId());
        f.failure("OBSERVATION_NOT_ENABLED", () -> append(event));
        choice(false);
        students.save(student, attempt, 1, 0, "b".repeat(64), "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"C\"}");
        f.core.submit(f.manual(attempt, 1, null));
        assertNotNull(timeline());
        assertTrue(timeline().coverage().reasons().contains("COLLECTION_OFF"));
    }

    JdbcTemplate cachedCustodian;

    JdbcTemplate custodian() {
        if (cachedCustodian != null) return cachedCustodian;
        String role = "cust_" + f.schema.substring(3);
        f.db.execute("DROP ROLE IF EXISTS " + role);
        f.db.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'disposable-fixture' NOSUPERUSER NOINHERIT");
        f.db.execute("GRANT quizopia_assessment_custodian TO " + role);
        f.db.execute("GRANT USAGE ON SCHEMA " + f.schema + " TO " + role);
        // NOINHERIT requires an explicit role selection, while session_user remains the distinct custodian.
        var ds =
                new DriverManagerDataSource(DB.getJdbcUrl() + "&currentSchema=" + f.schema, role, "disposable-fixture");
        ds.setConnectionProperties(new Properties() {
            {
                setProperty("options", "-c role=quizopia_assessment_custodian");
            }
        });
        f.db.execute("GRANT USAGE ON SCHEMA " + f.schema + " TO quizopia_assessment_custodian");
        cachedCustodian = new JdbcTemplate(ds);
        return cachedCustodian;
    }

    @Test
    void reviewedBoundedHoldsDelayDestructionNotReadsAndCleanupLeavesGradesUntouched() {
        var opted = choice(true);
        append(body(opted.streamId(), 1, "[]", "[\"A\"]"));
        f.core.submit(f.manual(attempt, 0, null));
        timeline();
        day(29);
        var custody = custodian();
        assertThrows(RuntimeException.class, () -> custody.update("DELETE FROM assessment_answer_audit"));
        assertThrows(
                RuntimeException.class,
                () -> custody.queryForObject(
                        "SELECT assessment_request_audit_hold(?,'INCIDENT_REVIEW',assessment_time()+interval '31 days')",
                        UUID.class,
                        attempt));
        UUID hold = custody.queryForObject(
                "SELECT assessment_request_audit_hold(?,'INCIDENT_REVIEW',assessment_time()+interval '30 days')",
                UUID.class,
                attempt);
        assertThrows(
                RuntimeException.class, () -> custody.queryForList("SELECT assessment_review_audit_hold(?)", hold));
        f.db.queryForList("SELECT assessment_review_audit_hold(?)", hold);
        day(31);
        assertTrue(timeline().items().stream().noneMatch(i -> i.source().equals("CLIENT_OBSERVATION")));
        assertEquals(0, custody.queryForObject("SELECT assessment_expire_audit(1)", Integer.class));
        assertEquals("1", f.scalar("SELECT count(*) FROM assessment_answer_audit WHERE source='CLIENT_OBSERVATION'"));
        day(60);
        int rounds = 0;
        while (custody.queryForObject("SELECT assessment_expire_audit(1)", Integer.class) > 0 && rounds++ < 20) {}
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_answer_audit WHERE source='CLIENT_OBSERVATION'"));
        assertTrue(timeline().coverage().reasons().contains("EXPIRED"));
        day(241);
        custody.queryForObject("SELECT assessment_expire_audit(5000)", Integer.class);
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts"));
        assertEquals("1", f.scalar("SELECT count(*) FROM grading_results"));
        assertEquals("SUBMITTED", f.scalar("SELECT state FROM attempts WHERE id=?", attempt));
        assertEquals(0, custody.queryForObject("SELECT assessment_expire_audit(5000)", Integer.class));
    }

    @Test
    void productionAndNonFictionalSubjectsStayClosedAndOldKeysDecryptOnlyWhenRetained() {
        var env = environment();
        env.setActiveProfiles("academic-demo", "production");
        assertThrows(IllegalStateException.class, () -> AcademicDemoConfiguration.validate(env));
        assertThrows(
                IllegalStateException.class,
                () -> AcademicDemoConfiguration.validate(environment().withProperty("server.address", "0.0.0.0")));
        f.failure("DEMO_ACCOUNT_REQUIRED", () -> policy.requireActor(UUID.randomUUID()));
        var secret = crypto.encrypt("test-context", new byte[] {1, 2, 3});
        var next = environment()
                .withProperty("quizopia.assessment.demo.current-key", "next")
                .withProperty(
                        "quizopia.assessment.demo.keys.next",
                        Base64.getEncoder().encodeToString(new byte[32]));
        var rotated = new AcademicDemoConfiguration().academicDemoEncryption(next);
        assertArrayEquals(new byte[] {1, 2, 3}, rotated.decrypt(secret.keyId(), "test-context", secret.bytes()));
        f.failure("AUDIT_UNAVAILABLE", () -> rotated.decrypt(secret.keyId(), "wrong-context", secret.bytes()));
        f.failure("AUDIT_UNAVAILABLE", () -> rotated.decrypt("revoked", "test-context", secret.bytes()));
    }

    @Test
    void abandonTimeoutAndConfirmedClosureUseCommittedTypesNotClientGuesses() {
        students.abandon(student, attempt, 0, "b".repeat(64));
        assertEquals(
                "1",
                f.scalar(
                        "SELECT count(*) FROM assessment_audit_pending WHERE event_type='ATTEMPT_ABANDONED' AND terminal_reason='STUDENT_ABANDON'"));
        var bounded = f.publication(5);
        f.db.update(
                "INSERT INTO assessment_demo_subjects SELECT owner_id,'FICTIONAL_ACADEMIC_DEMO_V1' FROM publications WHERE id=? ON CONFLICT DO NOTHING",
                bounded.publication());
        var due = f.attempt(bounded, 5, f.now().minusSeconds(301));
        var reason = com.quizopia.assessment.domain.attempt.AttemptRecord.Reason.BOUNDED_TIMEOUT;
        f.core.systemFinalize(due, reason);
        f.core.systemFinalize(due, reason);
        assertEquals(
                "2",
                f.scalar(
                        "SELECT count(*) FROM assessment_audit_pending WHERE attempt_id=? AND terminal_reason='BOUNDED_TIMEOUT'",
                        due));
        var closure = new ClosureIntegrationTest();
        closure.setup();
        var props = new Properties();
        props.putAll(closure.f.source.getConnectionProperties());
        props.setProperty("options", "-c quizopia.academic_demo=true");
        closure.f.source.setConnectionProperties(props);
        closure.f.db.update(
                "INSERT INTO assessment_demo_subjects VALUES(?,'FICTIONAL_ACADEMIC_DEMO_V1'),(?,'FICTIONAL_ACADEMIC_DEMO_V1')",
                closure.teacher.id(),
                closure.f.student);
        var request = closure.request();
        closure.confirmed(request);
        closure.confirmed(request);
        assertEquals(
                "1",
                closure.scalar(
                        "SELECT count(*) FROM assessment_audit_pending WHERE event_type='CLOSURE_NOTICE_CONFIRMED' AND notice_id IS NOT NULL"));
        assertEquals(
                "0",
                closure.scalar(
                        "SELECT count(*) FROM assessment_audit_pending WHERE event_type='ATTEMPT_STARTED'")); // no
        // legacy
        // backfill
    }

    @Test
    void scopedFencedPurgeDrainsNewDependentsAndRejectsRestoredPendingFacts() {
        choice(true);
        f.db.execute("CREATE TABLE stale_pending AS SELECT * FROM assessment_audit_pending");
        UUID operation = UUID.randomUUID(), quiz = p.configuration().quizId();
        f.db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,operation_id,revoked_at,source,ack_at) VALUES(?,?,assessment_time(),'COMMAND',assessment_time())",
                quiz,
                operation);
        f.failure("ASSESSMENT_REMOVED", () -> choice(false));
        String stage = "IN_PROGRESS";
        int rounds = 0;
        while (stage.equals("IN_PROGRESS") && rounds++ < 100)
            stage = f.db.queryForObject("SELECT assessment_purge_quiz(?,?,1)", String.class, quiz, operation);
        assertEquals("PURGED", stage);
        for (String table : List.of(
                "assessment_audit_pending",
                "assessment_observation_choices",
                "assessment_audit_positions",
                "assessment_audit_holds",
                "assessment_answer_audit")) assertEquals("0", f.scalar("SELECT count(*) FROM " + table));
        assertThrows(
                RuntimeException.class,
                () -> f.db.update("INSERT INTO assessment_audit_pending SELECT * FROM stale_pending"));
        assertEquals(
                "PURGED", f.db.queryForObject("SELECT assessment_purge_quiz(?,?,1)", String.class, quiz, operation));
    }

    @Test
    void clientDecryptedCanonicalMustMatchRowEvenWithAnAuthenticButMismatchedPayload() {
        UUID stream = choice(true).streamId();
        var observation = AnswerObservation.parse(body(stream, 1, "[]", "[\"A\"]"));
        var different = new AnswerObservation(
                observation.eventId(), stream, 1, observation.type(), 1, epoch, List.of(), List.of("A"));
        var sealed = crypto.encrypt(observation.context(p.publication(), attempt, student.id()), different.canonical());
        f.db.update(
                "INSERT INTO assessment_answer_audit(attempt_id,publication_id,event_id,ordinal,actor_id,source,event_type,stream_id,sequence,question_number,received_at,expires_at,key_id,protected_input) VALUES(?,?,?,?,?,'CLIENT_OBSERVATION',?,?,1,1,assessment_time(),assessment_time()+interval '30 days',?,?)",
                attempt,
                p.publication(),
                observation.eventId(),
                2,
                student.id(),
                observation.type(),
                stream,
                sealed.keyId(),
                sealed.bytes());
        f.core.submit(f.manual(attempt, 0, null));
        f.failure("AUDIT_UNAVAILABLE", () -> audit.timeline(teacher, p.publication(), attempt, 0, 100));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts"));
    }

    @Test
    void failedGradingFactRollsBackWholeSubmissionAndRetryCreatesOneFinalization() {
        f.db.execute(
                "CREATE FUNCTION fail_grade_fact() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='RESULT_GRADED' THEN RAISE EXCEPTION 'synthetic failure'; END IF; RETURN NEW; END $$");
        f.db.execute(
                "CREATE TRIGGER fail_grade_fact BEFORE INSERT ON assessment_audit_pending FOR EACH ROW EXECUTE FUNCTION fail_grade_fact()");
        f.failure(
                "PERSISTENCE_UNAVAILABLE",
                () -> f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement())));
        assertEquals("ACTIVE", f.scalar("SELECT state FROM attempts WHERE id=?", attempt));
        assertEquals("0", f.scalar("SELECT count(*) FROM grading_results"));
        assertEquals("0", f.scalar("SELECT count(*) FROM submitted_answers"));
        assertEquals("1", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        f.db.execute("DROP TRIGGER fail_grade_fact ON assessment_audit_pending");
        f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()));
        f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()));
        assertEquals("3", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
    }

    @Test
    void expiredHeldPendingFactsStayInCustodyAndCannotBeRecoveredIntoOrdinaryReads() {
        f.core.submit(f.manual(attempt, 0, null));
        day(89);
        var custody = custodian();
        UUID hold = custody.queryForObject(
                "SELECT assessment_request_audit_hold(?,'INCIDENT_REVIEW',assessment_time()+interval '30 days')",
                UUID.class,
                attempt);
        f.db.queryForList("SELECT assessment_review_audit_hold(?)", hold);
        day(91);
        publish();
        assertEquals("3", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
        var expired = timeline();
        assertTrue(expired.items().isEmpty());
        assertEquals("PARTIAL", expired.coverage().status());
        assertTrue(expired.coverage().reasons().contains("EXPIRED"));
        day(120);
        custody.queryForObject("SELECT assessment_expire_audit(5000)", Integer.class);
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
    }

    @Test
    void expiredHeldBacklogCannotStarveNewerSubmissionFacts() {
        String answer = "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}";
        for (int n = 0; n < 200; n++)
            students.save(
                    student,
                    attempt,
                    1,
                    n,
                    com.quizopia.assessment.persistence.JdbcPublicationStore.digest("starve-" + n),
                    answer);
        day(89);
        var custody = custodian();
        UUID hold = custody.queryForObject(
                "SELECT assessment_request_audit_hold(?,'INCIDENT_REVIEW',assessment_time()+interval '30 days')",
                UUID.class,
                attempt);
        f.db.queryForList("SELECT assessment_review_audit_hold(?)", hold);
        day(91);
        students.save(
                student,
                attempt,
                1,
                200,
                com.quizopia.assessment.persistence.JdbcPublicationStore.digest("new-save"),
                answer);
        f.core.submit(f.manual(attempt, 201, null));
        var current = timeline();
        assertEquals(
                List.of("ANSWER_PERSISTED", "ATTEMPT_SUBMITTED", "RESULT_GRADED"),
                current.items().stream().map(AnswerAuditStore.Item::type).toList());
        assertTrue(current.coverage().reasons().contains("EXPIRED"));
        assertEquals(
                "201", f.scalar("SELECT count(*) FROM assessment_audit_pending WHERE expires_at<=assessment_time()"));
        assertEquals("3", f.scalar("SELECT count(*) FROM assessment_audit_pending WHERE expires_at>assessment_time()"));
    }

    @Test
    void reviewedHoldRequestedBeforeFenceStillPreservesCustodyFromPurge() {
        var custody = custodian();
        UUID hold = custody.queryForObject(
                "SELECT assessment_request_audit_hold(?,'INCIDENT_REVIEW',assessment_time()+interval '30 days')",
                UUID.class,
                attempt);
        UUID operation = UUID.randomUUID(), quiz = p.configuration().quizId();
        f.db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,operation_id,revoked_at,source,ack_at) VALUES(?,?,assessment_time(),'COMMAND',assessment_time())",
                quiz,
                operation);
        f.db.queryForList("SELECT assessment_review_audit_hold(?)", hold);
        assertEquals(
                "BLOCKED_BY_RETENTION",
                f.db.queryForObject("SELECT assessment_purge_quiz(?,?,1)", String.class, quiz, operation));
        f.failure("ASSESSMENT_REMOVED", () -> choice(false));
    }

    @Test
    void genericRetentionHoldCommittedBeforeOrAfterExpiryIsNeverSurprisedByDeletes() {
        UUID quiz = p.configuration().quizId();
        f.core.submit(f.manual(attempt, 0, null));
        day(91);
        // Order 1: the hold commits first, then expiry runs -> eligible rows survive.
        f.db.update(
                "INSERT INTO retention_holds(id,quiz_id,authority,reason,reviewer,review_at) VALUES(?,?,'fixture custodian','deterministic order one','separate fixture reviewer',assessment_time()+interval '1 day')",
                UUID.randomUUID(),
                quiz);
        assertEquals(0, custodian().queryForObject("SELECT assessment_expire_audit(5000)", Integer.class));
        assertEquals("3", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        f.db.update("DELETE FROM retention_holds WHERE quiz_id=?", quiz);
        // Order 2: expiry commits first on unfenced content; a hold recorded afterwards
        // cannot resurrect TTL-destroyed history, and none appears in reads.
        assertEquals(3, custodian().queryForObject("SELECT assessment_expire_audit(5000)", Integer.class));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
        f.db.update(
                "INSERT INTO retention_holds(id,quiz_id,authority,reason,reviewer,review_at) VALUES(?,?,'fixture custodian','deterministic order two','separate fixture reviewer',assessment_time()+interval '1 day')",
                UUID.randomUUID(),
                quiz);
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_answer_audit"));
        var destroyed = timeline();
        assertTrue(destroyed.items().isEmpty());
    }

    // ------------------------------------------------------------------
    // H1 regressions: audit expiry must share the V11 quiz-keyed purge
    // admission boundary with genuinely overlapping transactions on
    // independent connections. Synchronization is deterministic (row locks
    // held across open transactions); every wait is bounded by an explicit
    // lock/statement timeout so a deadlock fails loudly instead of hanging.
    // ------------------------------------------------------------------

    /** Second Publication on the SAME Quiz, owned by the same seeded demo Teacher. */
    AtomicFinalizationIntegrationTest.Seed secondPublicationOnSameQuiz() {
        Instant time = f.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var cfg = new PublicationConfiguration(
                "Second same-quiz publication",
                null,
                p.configuration().quizId(),
                p.configuration().quizVersionId(),
                p.configuration().versionNumber(),
                PublicationConfiguration.Audience.PUBLIC,
                null,
                time.minusSeconds(86400),
                time.plusSeconds(86400),
                0,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                0);
        var store = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC());
        UUID owner = UUID.fromString(f.scalar("SELECT owner_id FROM publications WHERE id=?", p.publication()));
        var created = store.create(owner, UUID.randomUUID().toString(), cfg);
        store.open(
                owner,
                created.id(),
                0,
                new String(SnapshotFixtures.snapshot(cfg, "second private source marker"), StandardCharsets.UTF_8));
        return new AtomicFinalizationIntegrationTest.Seed(created.id(), cfg);
    }

    /** Independent READ COMMITTED session with hard bounds so contention fails fast. */
    Connection openSession() throws SQLException {
        var connection = f.source.getConnection();
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL READ COMMITTED");
            statement.execute("SET SESSION lock_timeout='5000ms'");
            statement.execute("SET SESSION statement_timeout='10000ms'");
        }
        return connection;
    }

    private static final String PHANTOM_ADMISSION =
            "SELECT count(*) FROM purge_admission pa JOIN publications x ON pa.quiz_id=x.id";

    @Test
    void expirySharesQuizAdmissionWithInFlightRetentionHoldAcrossSameQuizPublications() throws Exception {
        var second = secondPublicationOnSameQuiz();
        f.active(second, 0);
        UUID quiz = p.configuration().quizId();
        assertEquals("2", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        day(91);
        // Commit the real admission row first so the overlapping holder acquires a
        // committed tuple; this mirrors an in-flight quiz hold transaction exactly
        // where the V11 hold trigger leaves it (admission FOR UPDATE, hold uncommitted).
        f.db.update("INSERT INTO purge_admission(quiz_id) VALUES(?) ON CONFLICT DO NOTHING", quiz);
        try (var holder = openSession()) {
            try (var statement = holder.createStatement()) {
                try (var locked = statement.executeQuery(
                        "SELECT begun FROM purge_admission WHERE quiz_id='" + quiz + "' FOR UPDATE")) {
                    assertTrue(locked.next());
                    assertFalse(locked.getBoolean(1));
                }
                statement.executeUpdate(
                        "INSERT INTO retention_holds(id,quiz_id,authority,reason,reviewer,review_at) VALUES('"
                                + UUID.randomUUID()
                                + "','"
                                + quiz
                                + "','fixture custodian','in-flight overlap','separate fixture reviewer',assessment_time()+interval '1 day')");
            }
            // Overlapping expiry on an independent custodian connection: the quiz-keyed
            // admission FOR SHARE NOWAIT cannot be acquired -> both same-quiz
            // publications are skipped, nothing eligible is destroyed.
            assertEquals(
                    0,
                    custodian()
                            .queryForObject("SELECT assessment_expire_audit(5000)", Integer.class)
                            .intValue());
            assertEquals("2", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
            holder.commit();
        }
        // Committed hold still wins after the overlap resolves.
        assertEquals(
                0,
                custodian()
                        .queryForObject("SELECT assessment_expire_audit(5000)", Integer.class)
                        .intValue());
        assertEquals("2", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        // Correct keying: expiry never creates Publication-UUID admission rows.
        assertEquals("0", f.scalar(PHANTOM_ADMISSION));
        f.db.update("DELETE FROM retention_holds WHERE quiz_id=?", quiz);
        assertEquals(
                2,
                custodian()
                        .queryForObject("SELECT assessment_expire_audit(5000)", Integer.class)
                        .intValue());
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        assertEquals("0", f.scalar(PHANTOM_ADMISSION));
    }

    @Test
    void expiryYieldsToInFlightPurgeLockFootprintAndFenceBlocksResurrection() throws Exception {
        UUID quiz = p.configuration().quizId(), operation = UUID.randomUUID();
        f.db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,operation_id,revoked_at,source,ack_at) VALUES(?,?,assessment_time(),'COMMAND',assessment_time())",
                quiz,
                operation);
        day(91);
        f.db.execute("CREATE TABLE stale_pending AS SELECT * FROM assessment_audit_pending");
        f.db.update("INSERT INTO purge_admission(quiz_id) VALUES(?) ON CONFLICT DO NOTHING", quiz);
        try (var purger = openSession()) {
            try (var statement = purger.createStatement()) {
                try (var locked = statement.executeQuery(
                        "SELECT begun FROM purge_admission WHERE quiz_id='" + quiz + "' FOR UPDATE")) {
                    assertTrue(locked.next());
                    assertFalse(locked.getBoolean(1));
                }
                // Purge's post-admission publication lock footprint (V11 order).
                try (var locked = statement.executeQuery(
                        "SELECT id FROM publications WHERE id='" + p.publication() + "' ORDER BY id FOR UPDATE")) {
                    assertTrue(locked.next());
                }
            }
            // Overlapping expiry: SKIP LOCKED yields the publication and the quiz-keyed
            // admission NOWAIT yields the quiz; bounded, no deadlock, no deletion.
            assertEquals(
                    0,
                    custodian()
                            .queryForObject("SELECT assessment_expire_audit(5000)", Integer.class)
                            .intValue());
            assertEquals("1", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
            purger.rollback();
        }
        assertEquals(
                1,
                custodian()
                        .queryForObject("SELECT assessment_expire_audit(5000)", Integer.class)
                        .intValue());
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        assertEquals("0", f.scalar(PHANTOM_ADMISSION));
        String stage = "IN_PROGRESS";
        int rounds = 0;
        while (stage.equals("IN_PROGRESS") && rounds++ < 100)
            stage = f.db.queryForObject("SELECT assessment_purge_quiz(?,?,1)", String.class, quiz, operation);
        assertEquals("PURGED", stage);
        assertThrows(
                RuntimeException.class,
                () -> f.db.update("INSERT INTO assessment_audit_pending SELECT * FROM stale_pending"));
    }

    @Test
    void auditHoldAdmissionFailsFastAgainstExpiryAdmissionShareWithoutDeadlock() throws Exception {
        UUID quiz = p.configuration().quizId();
        day(89);
        f.db.update("INSERT INTO purge_admission(quiz_id) VALUES(?) ON CONFLICT DO NOTHING", quiz);
        try (var expiryFootprint = openSession()) {
            try (var statement = expiryFootprint.createStatement()) {
                // An in-flight expiry holds the quiz admission FOR SHARE.
                try (var shared = statement.executeQuery(
                        "SELECT 1 FROM purge_admission WHERE quiz_id='" + quiz + "' FOR SHARE")) {
                    assertTrue(shared.next());
                }
            }
            // Demo hold admission takes the same row FOR UPDATE NOWAIT: it must fail
            // fast (retryable) rather than block behind the expiry footprint.
            assertThrows(RuntimeException.class, () -> custodian()
                    .queryForObject(
                            "SELECT assessment_request_audit_hold(?,'INCIDENT_REVIEW',assessment_time()+interval '720 hours')",
                            UUID.class,
                            attempt));
            expiryFootprint.rollback();
        }
        UUID hold = custodian()
                .queryForObject(
                        "SELECT assessment_request_audit_hold(?,'INCIDENT_REVIEW',assessment_time()+interval '720 hours')",
                        UUID.class,
                        attempt);
        f.db.queryForList("SELECT assessment_review_audit_hold(?)", hold);
        day(91);
        assertEquals(
                0,
                custodian()
                        .queryForObject("SELECT assessment_expire_audit(5000)", Integer.class)
                        .intValue());
        assertEquals("1", f.scalar("SELECT count(*) FROM assessment_audit_pending"));
        assertEquals("0", f.scalar(PHANTOM_ADMISSION));
    }
}
