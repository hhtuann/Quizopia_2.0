package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.AttemptFinalization.*;
import com.quizopia.assessment.domain.*;
import com.quizopia.assessment.domain.attempt.AttemptRecord.Reason;
import com.quizopia.assessment.infrastructure.GradingDocuments;
import com.quizopia.assessment.persistence.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.node.*;

/** Production core, real PG17 commits/constraints and independent connections; no public API fixture. */
@Testcontainers
class AtomicFinalizationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    String schema;
    DriverManagerDataSource source;
    JdbcTemplate db;
    JdbcTransactionManager manager;
    JdbcAttemptFinalization core;
    UUID student = UUID.randomUUID();
    String key = "1".repeat(64);

    @BeforeEach
    void setup() {
        schema = "c1_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        source = source("main");
        db = new JdbcTemplate(source);
        manager = new JdbcTransactionManager(source);
        core = new JdbcAttemptFinalization(db, manager);
    }

    DriverManagerDataSource source(String app) {
        var ds = new DriverManagerDataSource(
                DB.getJdbcUrl() + "&currentSchema=" + schema, DB.getUsername(), DB.getPassword());
        var props = new Properties();
        props.setProperty("ApplicationName", schema + "_" + app);
        ds.setConnectionProperties(props);
        return ds;
    }

    Instant now() {
        return db.queryForObject(
                "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    record Seed(UUID publication, PublicationConfiguration configuration) {}

    Seed publication(int duration) {
        return publication(duration, null);
    }

    Seed publication(int duration, java.util.function.Consumer<ObjectNode> mutate) {
        Instant time = now().truncatedTo(ChronoUnit.MICROS);
        var cfg = new PublicationConfiguration(
                "Title",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                PublicationConfiguration.Audience.PUBLIC,
                null,
                time.minusSeconds(86400),
                time.plusSeconds(86400),
                duration,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                0);
        var store = new JdbcPublicationStore(db, manager, Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        var p = store.create(owner, UUID.randomUUID().toString(), cfg);
        var root = (ObjectNode) SnapshotFixtures.JSON.readTree(SnapshotFixtures.snapshot(cfg, "private source marker"));
        if (mutate != null) mutate.accept(root);
        store.open(owner, p.id(), 0, root.toString());
        return new Seed(p.id(), cfg);
    }

    UUID attempt(Seed seed, int duration, Instant started) {
        UUID id = UUID.randomUUID();
        long ordinal = db.queryForObject(
                "SELECT coalesce(max(ordinal),0)+1 FROM attempts WHERE publication_id=? AND student_id=?",
                Long.class,
                seed.publication(),
                student);
        db.update(
                "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,?,?,1,1,'ACTIVE',?,?,?)",
                id,
                seed.publication(),
                student,
                ordinal,
                seed.publication(),
                duration,
                Timestamp.from(started),
                duration == 0 ? null : Timestamp.from(started.plusSeconds(duration * 60L)));
        return id;
    }

    UUID active(Seed seed, int duration) {
        return attempt(seed, duration, now().minusSeconds(1));
    }

    Manual manual(UUID id, long revision, String body) {
        return new Manual(new SelectedAttempt(id, student), revision, key, body);
    }

    String scalar(String query, Object... args) {
        return db.queryForObject(query, String.class, args);
    }

    void failure(String code, Runnable operation) {
        var f = assertThrows(PublicationFailure.class, operation::run);
        assertEquals(code, f.code());
        assertEquals(code, f.getMessage());
        assertNull(f.getCause());
    }

    void unfinalized(UUID id) {
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", id));
        for (String table :
                List.of("submitted_answers", "grading_results", "attempt_finalization_audit", "attempt_operations"))
            assertEquals("0", scalar("SELECT count(*) FROM " + table + " WHERE attempt_id=?", id));
    }

    void saved(UUID id, int question, String answer, long revision) {
        db.update(
                "INSERT INTO attempt_answers VALUES(?,?,?::jsonb,?,?) ON CONFLICT(attempt_id,question_number) DO UPDATE SET answer=excluded.answer,saved_revision=excluded.saved_revision,saved_at=excluded.saved_at",
                id,
                question,
                answer,
                revision,
                Timestamp.from(now()));
        db.update("UPDATE attempts SET revision=? WHERE id=?", revision, id);
    }

    @Test
    void productionManualCompleteReplacementExactAtomicIdentityAndReplay() {
        var p = publication(5);
        UUID id = active(p, 5);
        var result = core.submit(manual(id, 0, GradingDocumentsTest.replacement()))
                .result()
                .orElseThrow();
        assertEquals("3.75", result.earnedWeight());
        assertEquals("4", result.possibleWeight());
        assertEquals("93.75", result.percentageDisplay());
        assertEquals(1, result.revision());
        assertEquals("4", scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", id));
        assertEquals("4", scalar("SELECT count(*) FROM grading_question_results WHERE result_id=?", result.resultId()));
        assertEquals(
                "1.00",
                scalar("SELECT answer->>'value' FROM attempt_answers WHERE attempt_id=? AND question_number=4", id));
        assertEquals(
                "t",
                scalar(
                        "SELECT a.finalized_at=s.created_at AND a.finalized_at=r.graded_at AND a.finalized_at=f.finalized_at AND a.finalized_at=o.acknowledged_at AND a.result_id=r.id AND a.submitted_answers_id=s.id FROM attempts a JOIN submitted_answers s ON s.attempt_id=a.id JOIN grading_results r ON r.attempt_id=a.id JOIN attempt_finalization_audit f ON f.attempt_id=a.id JOIN attempt_operations o ON o.attempt_id=a.id WHERE a.id=?",
                        id));
        assertEquals(
                student.toString(), scalar("SELECT actor_id FROM attempt_finalization_audit WHERE attempt_id=?", id));
        assertEquals(
                "f",
                scalar(
                        "SELECT jsonb_exists(response_body,'percentageDisplay') FROM attempt_operations WHERE attempt_id=?",
                        id));
        assertEquals("USER", scalar("SELECT actor_kind FROM attempt_finalization_audit WHERE attempt_id=?", id));
        assertEquals(
                "t",
                scalar(
                        "SELECT answer_digest=encode(sha256(convert_to(assessment_canonical_json(canonical_answers),'UTF8')),'hex') FROM submitted_answers WHERE attempt_id=?",
                        id));
        assertEquals(
                result,
                core.submit(manual(id, 0, GradingDocumentsTest.replacement().replace("[\"C\",\"A\"]", "[\"A\",\"C\"]")))
                        .result()
                        .orElseThrow());
        assertEquals(
                Kind.REPLAY,
                new JdbcAttemptFinalization(new JdbcTemplate(source), new JdbcTransactionManager(source))
                        .submit(manual(id, 0, GradingDocumentsTest.replacement()))
                        .kind());
        failure(
                "IDEMPOTENCY_CONFLICT",
                () -> core.submit(
                        manual(id, 0, GradingDocumentsTest.replacement().replace("1.00", "0001"))));
        failure(
                "ATTEMPT_TERMINAL",
                () -> core.submit(new Manual(new SelectedAttempt(id, student), 0, "2".repeat(64), null)));
        assertTrue(core.result(new SelectedAttempt(id, UUID.randomUUID())).isEmpty());
        assertEquals(result, core.result(new SelectedAttempt(id, student)).orElseThrow());
        assertThrows(
                RuntimeException.class,
                () -> db.update("UPDATE grading_results SET earned_weight=0 WHERE id=?", result.resultId()));
        assertFalse(result.toString().contains("3.75"));
        String wire = SnapshotFixtures.JSON.writeValueAsString(result);
        for (String protectedField :
                List.of("correct", "numericAnswer", "explanationMarkdown", "structuredContent", "questionWeights"))
            assertFalse(wire.contains(protectedField));
    }

    @Test
    void savedAnswersAndMissingQuestionsFreezeWithoutInventingSaves() {
        var p = publication(0);
        UUID id = active(p, 0);
        new TransactionTemplate(manager).execute(status -> {
            saved(id, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", 1);
            return null;
        });
        var result = core.submit(manual(id, 1, null)).result().orElseThrow();
        assertEquals("1", result.earnedWeight());
        assertEquals(2, result.revision());
        assertEquals(
                "4",
                scalar("SELECT jsonb_array_length(canonical_answers) FROM submitted_answers WHERE attempt_id=?", id));
        assertEquals("1", scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", id));
        assertEquals("1", scalar("SELECT saved_revision FROM attempt_answers WHERE attempt_id=?", id));
    }

    @Test
    void invalidRequestsAndStaleRevisionAreMutationFreeEvenWhenOverdue() {
        var p = publication(5);
        UUID id = attempt(p, 5, now().minusSeconds(301));
        for (String invalid : List.of(
                "[]",
                GradingDocumentsTest.replacement().replace("1.00", "2.5"),
                GradingDocumentsTest.replacement().replace("\"questionNumber\":4", "\"questionNumber\":5"))) {
            failure("INVALID_REQUEST", () -> core.submit(manual(id, 0, invalid)));
            unfinalized(id);
        }
        UUID fresh = active(publication(5), 5);
        failure("REVISION_CONFLICT", () -> core.submit(manual(fresh, 1, GradingDocumentsTest.replacement())));
        unfinalized(fresh);
        failure(
                "ATTEMPT_NOT_FOUND",
                () -> core.submit(new Manual(new SelectedAttempt(fresh, UUID.randomUUID()), 0, key, null)));
        unfinalized(fresh);
    }

    @Test
    void corruptSnapshotFailsWithoutPlaceholderGradeAndVersionGuardsRemain() {
        var p = publication(0, root -> ((ObjectNode)
                        root.path("structuredContent").path("questions").get(3))
                .put("numericAnswer", "private-invalid-key"));
        UUID id = active(p, 0);
        failure("GRADING_UNAVAILABLE", () -> core.submit(manual(id, 0, null)));
        unfinalized(id);
        assertThrows(
                RuntimeException.class,
                () -> db.update("UPDATE attempts SET grading_policy_version=2,revision=1 WHERE id=?", id));
        unfinalized(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"result", "receipt", "deferred", "constraint", "foreign"})
    void injectedPersistenceOrCommitFailureRollsBackReplacementAndAllArtifacts(String step) {
        var p = publication(0);
        UUID id = active(p, 0);
        JdbcTemplate failing = new JdbcTemplate(source) {
            @Override
            public int update(String sql, Object... args) {
                if (step.equals("result") && sql.startsWith("INSERT INTO grading_results"))
                    throw new DataIntegrityViolationException("private-answer-key-marker");
                if (step.equals("constraint") && sql.startsWith("INSERT INTO grading_results"))
                    args[5] = new java.math.BigDecimal("99");
                if (step.equals("foreign") && sql.startsWith("UPDATE attempts SET state='SUBMITTED'"))
                    args[4] = UUID.randomUUID();
                return super.update(sql, args);
            }
        };
        if (step.equals("receipt"))
            db.execute(
                    "CREATE FUNCTION reject_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private-answer-key-marker'; END $$; CREATE TRIGGER reject_receipt BEFORE INSERT ON attempt_operations FOR EACH ROW EXECUTE FUNCTION reject_receipt()");
        if (step.equals("deferred"))
            db.execute(
                    "CREATE FUNCTION reject_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private-answer-key-marker'; END $$; CREATE CONSTRAINT TRIGGER reject_commit AFTER UPDATE ON attempts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_commit()");
        failure("PERSISTENCE_UNAVAILABLE", () -> new JdbcAttemptFinalization(failing, manager)
                .submit(manual(id, 0, GradingDocumentsTest.replacement())));
        unfinalized(id);
        assertEquals("0", scalar("SELECT revision FROM attempts WHERE id=?", id));
        assertEquals("0", scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", id));
        if (step.equals("receipt")) db.execute("DROP TRIGGER reject_receipt ON attempt_operations");
        if (step.equals("deferred")) db.execute("DROP TRIGGER reject_commit ON attempts");
        assertEquals(
                Kind.SUBMITTED,
                core.submit(manual(id, 0, GradingDocumentsTest.replacement())).kind());
    }

    @Test
    void timeoutUsesSavedOnlyIsIdempotentAndNeverCreatesUserReceipt() {
        var p = publication(5);
        UUID id = attempt(p, 5, now().minusSeconds(301));
        // A committed historical save uses an actually pre-deadline timestamp, not finalizer time.
        new TransactionTemplate(manager).execute(status -> {
            db.update(
                    "INSERT INTO attempt_answers SELECT id,4,'{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}'::jsonb,1,started_at+interval '1 second' FROM attempts WHERE id=?",
                    id);
            db.update("UPDATE attempts SET revision=1 WHERE id=?", id);
            return null;
        });
        var result = core.systemFinalize(id, Reason.BOUNDED_TIMEOUT).result().orElseThrow();
        assertEquals("1", result.earnedWeight());
        assertEquals(Reason.BOUNDED_TIMEOUT, result.reason());
        assertEquals(2, result.revision());
        assertEquals("SYSTEM", scalar("SELECT actor_kind FROM attempt_finalization_audit WHERE attempt_id=?", id));
        assertEquals("0", scalar("SELECT count(*) FROM attempt_operations WHERE attempt_id=?", id));
        assertEquals(
                "4",
                scalar("SELECT jsonb_array_length(canonical_answers) FROM submitted_answers WHERE attempt_id=?", id));
        assertEquals(
                result, core.systemFinalize(id, Reason.BOUNDED_TIMEOUT).result().orElseThrow());
        failure("ATTEMPT_TERMINAL", () -> core.submit(manual(id, 1, null)));
        assertEquals("1", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", id));
    }

    @Test
    void timeoutCanonicalizesEquivalentSavedSelectionsWithoutFabricatingSaveTime() {
        var p = publication(5);
        UUID id = attempt(p, 5, now().minusSeconds(301));
        new TransactionTemplate(manager).execute(status -> {
            db.update(
                    "INSERT INTO attempt_answers SELECT id,2,'{\"type\":\"MULTIPLE_CHOICE\",\"optionLabels\":[\"C\",\"A\"]}'::jsonb,1,started_at+interval '1 second' FROM attempts WHERE id=?",
                    id);
            db.update("UPDATE attempts SET revision=1 WHERE id=?", id);
            return null;
        });
        String savedAt = scalar("SELECT saved_at::text FROM attempt_answers WHERE attempt_id=?", id);
        var result = core.systemFinalize(id, Reason.BOUNDED_TIMEOUT).result().orElseThrow();
        assertEquals("1", result.earnedWeight());
        assertEquals(
                "[\"A\", \"C\"]",
                scalar("SELECT (answer->'optionLabels')::text FROM attempt_answers WHERE attempt_id=?", id));
        assertEquals(savedAt, scalar("SELECT saved_at::text FROM attempt_answers WHERE attempt_id=?", id));
        assertEquals("2", scalar("SELECT saved_revision FROM attempt_answers WHERE attempt_id=?", id));
    }

    @Test
    void timeoutNotDueUnlimitedAndClosureRemainUnavailable() {
        UUID fresh = active(publication(5), 5), unlimited = active(publication(0), 0);
        assertEquals(
                Kind.NOT_DUE, core.systemFinalize(fresh, Reason.BOUNDED_TIMEOUT).kind());
        assertEquals(
                Kind.NOT_APPLICABLE,
                core.systemFinalize(unlimited, Reason.BOUNDED_TIMEOUT).kind());
        failure("FINALIZATION_NOT_AVAILABLE", () -> core.systemFinalize(unlimited, Reason.TEACHER_NOTICE_CUTOFF));
        unfinalized(fresh);
        unfinalized(unlimited);
    }

    @Test
    void lateManualCommitsTimeoutOutcomeInsteadOfRollingItBack() {
        UUID id = attempt(publication(5), 5, now().minusSeconds(301));
        var outcome = core.submit(manual(id, 0, GradingDocumentsTest.replacement()));
        assertEquals(Kind.DEADLINE_REACHED, outcome.kind());
        assertEquals("0", outcome.result().orElseThrow().earnedWeight());
        assertEquals("SUBMITTED", scalar("SELECT state FROM attempts WHERE id=?", id));
        assertEquals("0", scalar("SELECT count(*) FROM attempt_operations WHERE attempt_id=?", id));
    }

    @Test
    void bestResultTieUsesOrdinalAndActiveOrAbandonedDoesNotHideGrade() {
        var p = publication(0);
        assertTrue(core.best(p.publication(), student).isEmpty());
        UUID first = active(p, 0);
        var initial = core.submit(manual(first, 0, GradingDocumentsTest.replacement()))
                .result()
                .orElseThrow();
        UUID second = active(p, 0);
        core.submit(manual(second, 0, GradingDocumentsTest.replacement()));
        assertEquals(initial, core.best(p.publication(), student).orElseThrow());
        UUID third = active(p, 0);
        assertEquals(initial, core.best(p.publication(), student).orElseThrow());
        new TransactionTemplate(manager).execute(status -> {
            db.update(
                    "INSERT INTO attempt_finalization_audit(attempt_id,publication_id,reason,actor_kind,actor_id,finalized_at,grading_policy_version) VALUES(?,?,'STUDENT_ABANDON','USER',?,?,1)",
                    third,
                    p.publication(),
                    student.toString(),
                    Timestamp.from(now()));
            db.update(
                    "UPDATE attempts SET state='ABANDONED',terminal_reason='STUDENT_ABANDON',finalized_at=(SELECT finalized_at FROM attempt_finalization_audit WHERE attempt_id=?),revision=1 WHERE id=?",
                    third,
                    third);
            return null;
        });
        assertEquals(initial, core.best(p.publication(), student).orElseThrow());
        assertEquals(
                Kind.ALREADY_TERMINAL,
                core.systemFinalize(third, Reason.BOUNDED_TIMEOUT).kind());
        assertTrue(core.result(new SelectedAttempt(third, student)).isEmpty());
        assertTrue(core.best(p.publication(), UUID.randomUUID()).isEmpty());
    }

    void waitLock(String app) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end) {
            if ("1"
                    .equals(scalar(
                            "SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
                            schema + "_" + app))) return;
            Thread.onSpinWait();
        }
        fail("Contender never waited on PostgreSQL lock");
    }

    JdbcAttemptFinalization other(String app) {
        var ds = source(app);
        return new JdbcAttemptFinalization(new JdbcTemplate(ds), new JdbcTransactionManager(ds));
    }

    @ParameterizedTest
    @ValueSource(strings = {"same", "different", "timeout"})
    void concurrentFinalizersHaveOneCoherentWinnerAndNoPartialVisibility(String contender) throws Exception {
        var p = publication(0);
        UUID id = active(p, 0);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var inserts = new AtomicInteger();
        var blocker = new JdbcTemplate(source) {
            @Override
            public int update(String sql, Object... args) {
                int n = super.update(sql, args);
                if (sql.startsWith("INSERT INTO grading_results")) {
                    inserts.incrementAndGet();
                    held.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException();
                    }
                }
                return n;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var winner = pool.submit(() -> new JdbcAttemptFinalization(blocker, manager)
                    .submit(manual(id, 0, GradingDocumentsTest.replacement())));
            assertTrue(held.await(2, TimeUnit.SECONDS));
            assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", id));
            assertEquals("0", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", id));
            var loser = pool.submit(() -> {
                var next = other("race");
                if (contender.equals("timeout")) return next.systemFinalize(id, Reason.BOUNDED_TIMEOUT);
                var request = new Manual(
                        new SelectedAttempt(id, student),
                        0,
                        contender.equals("same") ? key : "2".repeat(64),
                        GradingDocumentsTest.replacement());
                try {
                    return next.submit(request);
                } catch (PublicationFailure e) {
                    assertEquals("ATTEMPT_TERMINAL", e.code());
                    return null;
                }
            });
            waitLock("race");
            release.countDown();
            var result = winner.get(4, TimeUnit.SECONDS).result().orElseThrow();
            var competing = loser.get(4, TimeUnit.SECONDS);
            if (!contender.equals("different"))
                assertEquals(result, competing.result().orElseThrow());
            assertEquals(1, inserts.get());
            for (String table :
                    List.of("submitted_answers", "grading_results", "attempt_finalization_audit", "attempt_operations"))
                assertEquals("1", scalar("SELECT count(*) FROM " + table + " WHERE attempt_id=?", id));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void saveCommitVersusSubmitSerializesAndRechecksRevision(boolean currentRevision) throws Exception {
        var p = publication(0);
        UUID id = active(p, 0);
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            AttemptPersistenceIntegrationTest.sql(
                    connection, "SELECT id FROM publications WHERE id=? FOR UPDATE", p.publication());
            AttemptPersistenceIntegrationTest.sql(
                    connection,
                    "INSERT INTO attempt_answers VALUES(?,4,'{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}',1,clock_timestamp())",
                    id);
            AttemptPersistenceIntegrationTest.sql(connection, "UPDATE attempts SET revision=1 WHERE id=?", id);
            var future = pool.submit(() -> {
                try {
                    return other("save").submit(manual(id, currentRevision ? 1 : 0, null));
                } catch (PublicationFailure e) {
                    assertEquals("REVISION_CONFLICT", e.code());
                    return null;
                }
            });
            waitLock("save");
            connection.commit();
            var outcome = future.get(4, TimeUnit.SECONDS);
            if (currentRevision)
                assertEquals("1", outcome.result().orElseThrow().earnedWeight());
            else unfinalized(id);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void publicationWaitCrossesDeadlineAndManualReplayStillWorksAfterDeadline() throws Exception {
        var p = publication(5);
        UUID id = attempt(p, 5, now().minusSeconds(298));
        var pool = Executors.newSingleThreadExecutor();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            AttemptPersistenceIntegrationTest.sql(
                    connection, "SELECT id FROM publications WHERE id=? FOR UPDATE", p.publication());
            Instant deadline = db.queryForObject(
                    "SELECT deadline_at FROM attempts WHERE id=?",
                    (r, n) -> r.getTimestamp(1).toInstant(),
                    id);
            assertTrue(now().isBefore(deadline));
            var future = pool.submit(() -> other("deadline").submit(manual(id, 0, GradingDocumentsTest.replacement())));
            waitLock("deadline");
            // The contender waiting for Publication must not already hold the Attempt lock.
            assertEquals(id, db.queryForObject("SELECT id FROM attempts WHERE id=? FOR UPDATE NOWAIT", UUID.class, id));
            while (now().isBefore(deadline)) Thread.onSpinWait();
            connection.commit();
            assertEquals(Kind.DEADLINE_REACHED, future.get(4, TimeUnit.SECONDS).kind());
        } finally {
            pool.shutdownNow();
        }
        var p2 = publication(5);
        UUID replay = attempt(p2, 5, now().minusSeconds(298));
        var request = manual(replay, 0, null);
        var original = core.submit(request).result().orElseThrow();
        Instant deadline = db.queryForObject(
                "SELECT deadline_at FROM attempts WHERE id=?",
                (r, n) -> r.getTimestamp(1).toInstant(),
                replay);
        while (now().isBefore(deadline)) Thread.onSpinWait();
        assertEquals(original, other("retry").submit(request).result().orElseThrow());
    }

    @Test
    void completeReplacementOverridesSavedStateAndActionReplayScopeIsShared() {
        var p = publication(0);
        UUID id = active(p, 0);
        new TransactionTemplate(manager).execute(status -> {
            saved(id, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"0000\"}", 1);
            return null;
        });
        assertEquals(
                "3.75",
                core.submit(manual(id, 1, GradingDocumentsTest.replacement()))
                        .result()
                        .orElseThrow()
                        .earnedWeight());
        assertEquals(
                "1.00",
                scalar("SELECT answer->>'value' FROM attempt_answers WHERE attempt_id=? AND question_number=4", id));
        assertEquals("2", scalar("SELECT revision FROM attempts WHERE id=?", id));
        failure("IDEMPOTENCY_CONFLICT", () -> core.submit(manual(id, 0, GradingDocumentsTest.replacement())));
        UUID other = active(publication(0), 0);
        new TransactionTemplate(manager).execute(status -> {
            saved(other, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", 1);
            db.update(
                    "INSERT INTO attempt_operations(attempt_id,key_digest,action,request_digest,question_number,accepted_revision,acknowledged_at,response_status,response_body) VALUES(?,?,'SAVE',?,4,1,clock_timestamp(),200,'{}')",
                    other,
                    key,
                    key);
            return null;
        });
        failure("IDEMPOTENCY_CONFLICT", () -> core.submit(manual(other, 1, null)));
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", other));
        assertEquals("0", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", other));
    }

    @Test
    void higherLaterScoreWinsAndRepresentativeFullSetIsNotTruncated() {
        var p = publication(0, root -> {
            var questions = (ArrayNode) root.path("structuredContent").path("questions");
            var single = questions.get(0).deepCopy();
            questions.removeAll();
            for (int i = 1; i <= 128; i++) {
                var q = (ObjectNode) single.deepCopy();
                q.put("number", i);
                questions.add(q);
            }
        });
        UUID a = active(p, 0);
        var first = core.submit(manual(a, 0, null)).result().orElseThrow();
        assertEquals("0", first.earnedWeight());
        UUID b = active(p, 0);
        var entries = new ArrayList<Map<String, Object>>();
        for (int i = 1; i <= 128; i++)
            entries.add(Map.of("questionNumber", i, "answer", Map.of("type", "SINGLE_CHOICE", "optionLabel", "B")));
        String full = SnapshotFixtures.JSON.writeValueAsString(entries);
        var best = core.submit(manual(b, 0, full)).result().orElseThrow();
        assertEquals("128", best.earnedWeight());
        assertEquals(best, core.best(p.publication(), student).orElseThrow());
        assertEquals(
                "128",
                scalar("SELECT jsonb_array_length(canonical_answers) FROM submitted_answers WHERE attempt_id=?", b));
        assertEquals("128", scalar("SELECT count(*) FROM grading_question_results WHERE result_id=?", best.resultId()));
    }

    @Test
    void boundedManualVersusTimeoutAndFailedContenderLeaveOneSystemResult() throws Exception {
        UUID id = attempt(publication(5), 5, now().minusSeconds(301));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failing = new JdbcTemplate(source) {
            @Override
            public int update(String sql, Object... args) {
                int n = super.update(sql, args);
                if (sql.startsWith("INSERT INTO grading_results")) {
                    held.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new DataIntegrityViolationException("private-failure");
                }
                return n;
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                failure("PERSISTENCE_UNAVAILABLE", () -> new JdbcAttemptFinalization(failing, manager)
                        .submit(manual(id, 0, GradingDocumentsTest.replacement())));
            });
            assertTrue(held.await(2, TimeUnit.SECONDS));
            var second = pool.submit(() -> other("failure").systemFinalize(id, Reason.BOUNDED_TIMEOUT));
            waitLock("failure");
            assertEquals("0", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", id));
            release.countDown();
            first.get(4, TimeUnit.SECONDS);
            var winner = second.get(4, TimeUnit.SECONDS).result().orElseThrow();
            assertEquals(Reason.BOUNDED_TIMEOUT, winner.reason());
            assertEquals("0", winner.earnedWeight());
            assertEquals("1", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", id));
            assertEquals("0", scalar("SELECT count(*) FROM attempt_operations WHERE attempt_id=?", id));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void canonicalJavaDigestMatchesPostgresAndNestedTransactionIsRejected() {
        var d = new GradingDocuments();
        var p = publication(0);
        var snapshot = d.snapshot(SnapshotFixtures.snapshot(p.configuration(), "source"), p.configuration(), 1, 1);
        String canonical = d.canonical(d.replacement(GradingDocumentsTest.replacement(), snapshot));
        assertEquals(canonical, scalar("SELECT assessment_canonical_json(?::jsonb)", canonical));
        assertEquals(
                JdbcPublicationStore.digest(canonical),
                scalar(
                        "SELECT encode(sha256(convert_to(assessment_canonical_json(?::jsonb),'UTF8')),'hex')",
                        canonical));
        UUID id = active(p, 0);
        new TransactionTemplate(manager).execute(status -> {
            failure("FINALIZATION_TRANSACTION_REQUIRED", () -> core.submit(manual(id, 0, null)));
            return null;
        });
        unfinalized(id);
    }
}
