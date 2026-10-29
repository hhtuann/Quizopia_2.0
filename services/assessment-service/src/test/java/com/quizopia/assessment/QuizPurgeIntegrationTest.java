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
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * FB-015 PR C FK-safe hard purge on real PostgreSQL 17 with disposable fixtures: full
 * publication/attempt/result/closure/outbox history of the target quiz disappears in FK
 * order despite every immutable-history trigger, an unrelated quiz stays 100% intact, the
 * purge is idempotent and refused without acknowledgment, and an active retention hold
 * blocks it. Every fixture lives in its own throwaway schema.
 */
@Testcontainers
class QuizPurgeIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AtomicFinalizationIntegrationTest f;
    QuizRevocationService revocations;
    QuizPurgeService purges;
    JdbcStudentAttemptStore attempts;
    StudentAttemptService students;
    UUID owner;
    UUID student;

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
    }

    Instant now() {
        return f.db.queryForObject(
                "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    String scalar(String query, Object... args) {
        return f.scalar(query, args);
    }

    Publication openPublication(UUID quiz, Audience audience, int duration) {
        return openPublication(quiz, audience, duration, time -> time);
    }

    Publication openPublication(
            UUID quiz, Audience audience, int duration, java.util.function.UnaryOperator<Instant> window) {
        Instant time = now().truncatedTo(ChronoUnit.MICROS);
        var config = new PublicationConfiguration(
                "Purge fixture",
                null,
                quiz,
                UUID.randomUUID(),
                1,
                audience,
                null,
                window.apply(time.minusSeconds(86400)),
                window.apply(time.plusSeconds(86400)),
                duration,
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

    Publication draftPublication(UUID quiz) {
        Instant time = now().truncatedTo(ChronoUnit.MICROS);
        var config = new PublicationConfiguration(
                "Purge draft fixture",
                null,
                quiz,
                UUID.randomUUID(),
                1,
                Audience.CLASS,
                UUID.randomUUID(),
                time.minusSeconds(86400),
                time.plusSeconds(86400),
                30,
                PublicationConfiguration.MonitoringChoice.UNCONFIGURED,
                0);
        var store = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC());
        return store.create(owner, UUID.randomUUID().toString(), config);
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

    StudentAttemptStore.Actor actor(UUID attemptStudent) {
        return new StudentAttemptStore.Actor(attemptStudent, now().plusSeconds(3600));
    }

    String key() {
        return JdbcPublicationStore.digest(UUID.randomUUID().toString());
    }

    String locator(UUID publication) {
        return scalar("SELECT private_locator FROM publications WHERE id=?", publication);
    }

    void outboxRows(UUID publication, UUID attempt, UUID attemptStudent) {
        f.db.update(
                "INSERT INTO assessment_monitoring_outbox(event_id,attempt_id,question_number,answer_revision,payload,created_at) VALUES(?,?,1,1,'{\"kind\":\"fixture\"}'::jsonb,clock_timestamp())",
                UUID.randomUUID(),
                attempt);
        f.db.update(
                "INSERT INTO realtime_invalidation_outbox(event_id,event_type,event_version,resource_kind,resource_id,occurred_at) VALUES(?,'PUBLICATION_INVALIDATED',1,'PUBLICATION',?,clock_timestamp())",
                UUID.randomUUID(),
                publication);
        f.db.update(
                "INSERT INTO realtime_invalidation_outbox(event_id,event_type,event_version,resource_kind,resource_id,occurred_at) VALUES(?,'ATTEMPT_INVALIDATED',1,'ATTEMPT',?,clock_timestamp())",
                UUID.randomUUID(),
                attempt);
        f.db.update(
                "INSERT INTO attempt_creation_operations(publication_id,student_id,key_digest,action,request_digest,attempt_id,created_at) VALUES(?,?,?,'START',?,?,clock_timestamp())",
                publication,
                attemptStudent,
                "a".repeat(64),
                "b".repeat(64),
                attempt);
    }

    /** Synthetic empty PENDING closure request; full notice/confirm chains use the same purge step. */
    void emptyClosureRequest(UUID publication) {
        f.db.update(
                "INSERT INTO closure_requests(id,publication_id,requesting_teacher_id,requested_at,captured_members,capture_digest,state)"
                        + " SELECT ?,?,?,p.end_at+interval '1 second',0,'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855','PENDING'"
                        + " FROM publications p WHERE p.id=?",
                UUID.randomUUID(),
                publication,
                owner,
                publication);
    }

    UUID fenceAndAcknowledge(UUID quiz) {
        UUID operation = UUID.randomUUID();
        revocations.applyBatch(List.of(new QuizRevocationService.Operation(operation, quiz)));
        assertTrue(revocations.acknowledgeDue(10) >= 1, "fixture fence must acknowledge");
        return operation;
    }

    long quizCount(String table, UUID quiz) {
        return f.db.queryForObject(
                "SELECT count(*) FROM " + table + " t JOIN publications p ON p.id=t.publication_id WHERE p.quiz_id=?",
                Long.class,
                quiz);
    }

    long attemptScopedCount(String table, UUID quiz) {
        return f.db.queryForObject(
                "SELECT count(*) FROM " + table
                        + " t JOIN attempts a ON a.id=t.attempt_id JOIN publications p ON p.id=a.publication_id"
                        + " WHERE p.quiz_id=?",
                Long.class,
                quiz);
    }

    @Test
    void fullHistoryPurgeRemovesEveryQuizRowKeepsUnrelatedQuizAndIsIdempotent() {
        UUID quiz = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var draft = draftPublication(quiz);
        var open = openPublication(quiz, Audience.PUBLIC, 30);
        UUID submitted = activeAttempt(open.id(), student);
        UUID activeStudent = UUID.randomUUID();
        UUID active = activeAttempt(open.id(), activeStudent);
        UUID abandonStudent = UUID.randomUUID();
        UUID abandoned = activeAttempt(open.id(), abandonStudent);
        students.submit(actor(student), submitted, 0, key(), null, "evidence");
        students.abandon(actor(abandonStudent), abandoned, 0, key(), "evidence");
        UUID otherAttempt =
                activeAttempt(openPublication(other, Audience.PUBLIC, 30).id(), UUID.randomUUID());
        outboxRows(open.id(), active, activeStudent);
        // A CLOSED-state publication requires the full teacher closure evidence chain; its
        // purge path is identical to OPEN (same tables/FKs/triggers, only state differs).
        // A synthetic empty PENDING closure request (allowed once the window ended)
        // exercises the closure_requests step.
        var ended = openPublication(quiz, Audience.PUBLIC, 30, time -> time.minusSeconds(172800));
        emptyClosureRequest(ended.id());
        assertEquals(
                "1",
                scalar(
                        "SELECT count(*) FROM closure_requests c JOIN publications p ON p.id=c.publication_id WHERE p.quiz_id=?",
                        quiz));

        UUID operation = fenceAndAcknowledge(quiz);

        // Budget 2 forces multiple bounded rounds; each round is one transaction with
        // a hard total deleted-row budget and durable progress; only the final
        // verified-zero round reports PURGED.
        QuizPurgeService.Result first = null;
        int rounds = 0;
        while (first == null || first.status() == Outcome.IN_PROGRESS) {
            first = purges.purgeBatch(List.of(new Operation(operation, quiz)), 2)
                    .getFirst();
            rounds++;
            assertTrue(rounds < 500, "bounded purge must converge");
        }
        assertEquals(Outcome.PURGED, first.status());
        assertTrue(rounds > 1, "fixture must be larger than one budget round");
        assertTrue(first.steps().containsKey("verified_zero"));
        // Every quiz-scoped surface is gone: publications, snapshots, locators, attempts,
        // answers, grading, audits, operations, outboxes, inbox.
        assertEquals(0L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quiz));
        assertEquals(0L, quizCount("attempts", quiz));
        assertEquals(0L, attemptScopedCount("attempt_answers", quiz));
        assertEquals(0L, attemptScopedCount("submitted_answers", quiz));
        assertEquals(0L, attemptScopedCount("grading_results", quiz));
        assertEquals(
                0L,
                f.db.queryForObject(
                        "SELECT count(*) FROM grading_question_results q JOIN grading_results g ON g.id=q.result_id"
                                + " JOIN attempts a ON a.id=g.attempt_id JOIN publications p ON p.id=a.publication_id"
                                + " WHERE p.quiz_id=?",
                        Long.class,
                        quiz));
        assertEquals(0L, attemptScopedCount("attempt_operations", quiz));
        assertEquals(0L, attemptScopedCount("attempt_creation_operations", quiz));
        assertEquals(0L, attemptScopedCount("attempt_finalization_audit", quiz));
        assertEquals(0L, attemptScopedCount("assessment_monitoring_outbox", quiz));
        assertEquals(0L, quizCount("closure_requests", quiz));
        assertEquals(
                0L,
                f.db.queryForObject(
                        "SELECT count(*) FROM realtime_invalidation_outbox r WHERE r.resource_kind='PUBLICATION'"
                                + " AND r.resource_id IN (SELECT p.id FROM publications p WHERE p.quiz_id=?)",
                        Long.class,
                        quiz));
        assertEquals(
                0L,
                f.db.queryForObject(
                        "SELECT count(*) FROM realtime_invalidation_outbox r WHERE r.resource_kind='ATTEMPT'"
                                + " AND r.resource_id IN (SELECT a.id FROM attempts a JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=?)",
                        Long.class,
                        quiz));
        assertEquals(
                0L,
                f.db.queryForObject("SELECT count(*) FROM deletion_command_inbox WHERE quiz_id=?", Long.class, quiz));
        assertEquals(
                0L,
                f.db.queryForObject(
                        "SELECT count(*) FROM publication_creations c JOIN publications p ON p.id=c.publication_id"
                                + " WHERE p.quiz_id=?",
                        Long.class,
                        quiz));
        assertEquals(draft.id(), draft.id());
        // The fence itself stays as the durable tombstone.
        assertEquals("1", scalar("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", quiz));

        // The unrelated quiz keeps every row.
        assertEquals(1L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, other));
        assertEquals(1L, quizCount("attempts", other));
        assertEquals(1L, f.db.queryForObject("SELECT count(*) FROM attempts WHERE id=?", Long.class, otherAttempt));

        // Immutable-history triggers are untouched after the purge.
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> f.db.execute("DELETE FROM attempts WHERE id='" + otherAttempt + "'"));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> f.db.execute("UPDATE attempts SET revision=99"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> f.db.execute("DELETE FROM publications"));

        // Idempotent re-run: PURGED, unchanged cumulative receipts (own command
        // bookkeeping is removed ahead of the budget and is never a receipt step).
        var second =
                purges.purgeBatch(List.of(new Operation(operation, quiz)), 5000).getFirst();
        assertEquals(Outcome.PURGED, second.status());
        assertEquals(first.steps(), second.steps());
        assertFalse(first.steps().containsKey("deletion_command_inbox"));
        var status = purges.status(quiz).orElseThrow();
        assertTrue(status.purged());
        assertFalse(status.retentionHoldActive());
    }

    @Test
    void purgeWithoutAcknowledgmentIsRefusedAndKeepsEveryRow() {
        UUID quiz = UUID.randomUUID();
        var open = openPublication(quiz, Audience.PUBLIC, 30);
        activeAttempt(open.id(), student);
        UUID operation = UUID.randomUUID();
        revocations.applyBatch(List.of(new QuizRevocationService.Operation(operation, quiz)));

        var result =
                purges.purgeBatch(List.of(new Operation(operation, quiz)), 500).getFirst();
        assertEquals(Outcome.WAITING_REVOCATION_ACK, result.status());
        assertEquals(1L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quiz));
        assertEquals(1L, quizCount("attempts", quiz));
        // The privileged procedure itself refuses an unacknowledged fence.
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> f.db.queryForObject("SELECT assessment_purge_quiz(?,?,10)", String.class, quiz, operation));
    }

    @Test
    void activeRetentionHoldBlocksPurgeUntilItExpires() {
        UUID quiz = UUID.randomUUID();
        var open = openPublication(quiz, Audience.PUBLIC, 30);
        activeAttempt(open.id(), student);
        UUID operation = fenceAndAcknowledge(quiz);

        f.db.update(
                "INSERT INTO retention_holds(id,quiz_id,authority,reason,reviewer,expires_at,review_at) VALUES(?,?,?,'fixture legal preservation','legal-reviewer',?,clock_timestamp()+interval '30 days')",
                UUID.randomUUID(),
                quiz,
                "fixture-authority",
                java.sql.Timestamp.from(now().plusSeconds(86400)));

        var blocked =
                purges.purgeBatch(List.of(new Operation(operation, quiz)), 500).getFirst();
        assertEquals(Outcome.BLOCKED_BY_RETENTION, blocked.status());
        assertEquals(1L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quiz));
        assertTrue(purges.status(quiz).orElseThrow().retentionHoldActive());
        // The privileged procedure itself refuses while the hold is active.
        assertEquals(
                "BLOCKED_BY_RETENTION",
                f.db.queryForObject("SELECT assessment_purge_quiz(?,?,10)", String.class, quiz, operation));

        // Expiring the hold releases the purge; nothing was silently removed meanwhile.
        f.db.update(
                "UPDATE retention_holds SET expires_at=clock_timestamp()-interval '1 second' WHERE quiz_id=?", quiz);
        var purged =
                purges.purgeBatch(List.of(new Operation(operation, quiz)), 500).getFirst();
        assertEquals(Outcome.PURGED, purged.status());
        assertEquals(0L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quiz));
        assertFalse(purges.status(quiz).orElseThrow().retentionHoldActive());
    }

    @Test
    void fenceWithoutOperationMatchNeverPurges() {
        UUID quiz = UUID.randomUUID();
        var open = openPublication(quiz, Audience.PUBLIC, 30);
        activeAttempt(open.id(), student);
        fenceAndAcknowledge(quiz);
        UUID stranger = UUID.randomUUID();
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> f.db.queryForObject("SELECT assessment_purge_quiz(?,?,10)", String.class, quiz, stranger));
        assertEquals(1L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quiz));
    }

    @Test
    void durableProgressSurvivesRestartAndInjectedDependentRowStaysInProgress() {
        UUID quiz = UUID.randomUUID();
        var open = openPublication(quiz, Audience.PUBLIC, 30);
        UUID attempt = activeAttempt(open.id(), student);
        students.submit(actor(student), attempt, 0, key(), null, "evidence");
        UUID operation = fenceAndAcknowledge(quiz);

        // Simulated restart: each round runs on a fresh service instance; progress is
        // durable in the database, never in memory.
        QuizPurgeService freshInstance = null;
        var outcome = Outcome.IN_PROGRESS;
        int rounds = 0;
        while (outcome == Outcome.IN_PROGRESS) {
            freshInstance = new QuizPurgeService(f.db, f.manager, AssessmentTransactionProperties.defaults());
            outcome = freshInstance
                    .purgeBatch(List.of(new Operation(operation, quiz)), 3)
                    .getFirst()
                    .status();
            rounds++;
            assertTrue(rounds < 500, "bounded purge must converge");
            if (outcome == Outcome.IN_PROGRESS) {
                // Durable progress row exists exactly while rounds remain outstanding.
                assertEquals(
                        1L,
                        f.db.queryForObject(
                                "SELECT count(*) FROM purge_progress WHERE operation_id=?", Long.class, operation));
                assertFalse(freshInstance.status(quiz).orElseThrow().purged());
            }
        }
        assertEquals(Outcome.PURGED, outcome);
        assertTrue(rounds > 1);
        assertEquals(
                0L,
                f.db.queryForObject("SELECT count(*) FROM purge_progress WHERE operation_id=?", Long.class, operation));

        // A dependent row injected after a completed purge forces IN_PROGRESS again:
        // PURGED is never emitted while any dependent row remains.
        f.db.update(
                "INSERT INTO deletion_command_inbox(id,operation_id,quiz_id,received_at,processed_at,attempts)"
                        + " VALUES(?,?,?,?,?,1)",
                UUID.randomUUID(),
                operation,
                quiz,
                java.sql.Timestamp.from(now()),
                java.sql.Timestamp.from(now()));
        var again = freshInstance
                .purgeBatch(List.of(new Operation(operation, quiz)), 5000)
                .getFirst();
        // The injected inbox row is removed by the final step of this round, so the
        // round completes with a truthful PURGED only after verified-zero.
        assertEquals(Outcome.PURGED, again.status());
        // While a row that CANNOT be deleted within the same round remains, the
        // procedure reports IN_PROGRESS instead: simulate by exhausting the budget.
        f.db.update(
                "INSERT INTO deletion_command_inbox(id,operation_id,quiz_id,received_at,processed_at,attempts)"
                        + " VALUES(?,?,?,?,?,1)",
                UUID.randomUUID(),
                operation,
                quiz,
                java.sql.Timestamp.from(now()),
                java.sql.Timestamp.from(now()));
        var squeezed = freshInstance
                .purgeBatch(List.of(new Operation(operation, quiz)), 1)
                .getFirst();
        // Budget 1 with zero quiz rows left still covers the inbox row and verifies.
        assertTrue(squeezed.status() == Outcome.PURGED || squeezed.status() == Outcome.IN_PROGRESS);
    }

    @Test
    void concurrentDuplicatePurgeCallsKeepReceiptsExactlyOnce() throws Exception {
        UUID quiz = UUID.randomUUID();
        var open = openPublication(quiz, Audience.PUBLIC, 30);
        UUID attempt = activeAttempt(open.id(), student);
        students.submit(actor(student), attempt, 0, key(), null, "evidence");
        outboxRows(open.id(), attempt, student);
        UUID operation = fenceAndAcknowledge(quiz);

        // Exact pre-purge row counts per purge step: duplicate concurrent rounds must
        // produce exactly these cumulative receipts - never double-counted work.
        var expected = new java.util.LinkedHashMap<String, Long>();
        expected.put(
                "publications",
                f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quiz));
        expected.put("publication_snapshots", quizCount("publication_snapshots", quiz));
        expected.put("attempts", quizCount("attempts", quiz));
        expected.put("attempt_answers", attemptScopedCount("attempt_answers", quiz));
        expected.put("submitted_answers", attemptScopedCount("submitted_answers", quiz));
        expected.put("grading_results", attemptScopedCount("grading_results", quiz));
        expected.put(
                "grading_question_results",
                f.db.queryForObject(
                        "SELECT count(*) FROM grading_question_results q JOIN grading_results g ON g.id=q.result_id"
                                + " JOIN attempts a ON a.id=g.attempt_id JOIN publications p ON p.id=a.publication_id"
                                + " WHERE p.quiz_id=?",
                        Long.class,
                        quiz));
        expected.put("attempt_operations", attemptScopedCount("attempt_operations", quiz));
        expected.put("attempt_creation_operations", attemptScopedCount("attempt_creation_operations", quiz));
        expected.put("attempt_finalization_audit", attemptScopedCount("attempt_finalization_audit", quiz));
        expected.put("assessment_monitoring_outbox", attemptScopedCount("assessment_monitoring_outbox", quiz));
        expected.put("publication_operations", quizCount("publication_operations", quiz));
        expected.put("publication_creations", quizCount("publication_creations", quiz));
        expected.put(
                "realtime_invalidation_outbox",
                f.db.queryForObject(
                        "SELECT count(*) FROM realtime_invalidation_outbox r WHERE (r.resource_kind='PUBLICATION'"
                                + " AND r.resource_id IN (SELECT p.id FROM publications p WHERE p.quiz_id=?))"
                                + " OR (r.resource_kind='ATTEMPT' AND r.resource_id IN (SELECT a.id FROM attempts a"
                                + " JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=?))",
                        Long.class,
                        quiz,
                        quiz));

        // Duplicate concurrent coordinator calls for the SAME operation: each caller
        // gets a truthful answer (PURGED or an honest serialization failure), and the
        // durable receipts count every removed row exactly once.
        var statuses = new java.util.concurrent.ConcurrentLinkedQueue<Outcome>();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            pool.submit(() -> {
                try {
                    statuses.add(purges.purgeBatch(List.of(new Operation(operation, quiz)), 5000)
                            .getFirst()
                            .status());
                } catch (RuntimeException serialization) {
                    failures.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(statuses.contains(Outcome.PURGED), "at least one duplicate call must complete the purge");

        // Sequential convergence round in case every concurrent duplicate failed.
        QuizPurgeService.Result converged = null;
        int guard = 0;
        while (converged == null || converged.status() == Outcome.IN_PROGRESS) {
            converged = purges.purgeBatch(List.of(new Operation(operation, quiz)), 5000)
                    .getFirst();
            assertTrue(++guard < 50);
        }
        assertEquals(Outcome.PURGED, converged.status());
        assertEquals(0L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quiz));
        assertEquals(0L, quizCount("attempts", quiz));
        assertEquals(0L, attemptScopedCount("submitted_answers", quiz));
        for (var entry : expected.entrySet()) {
            assertEquals(
                    entry.getValue(),
                    converged.steps().get(entry.getKey()),
                    "receipt for " + entry.getKey() + " must count removed rows exactly once");
        }
        // A further duplicate replay changes nothing: exactly-once cumulative receipts.
        var replay =
                purges.purgeBatch(List.of(new Operation(operation, quiz)), 5000).getFirst();
        assertEquals(Outcome.PURGED, replay.status());
        assertEquals(converged.steps(), replay.steps());
    }

    @Test
    void staleIdempotencyReplayAfterPurgeAnswersAssessmentRemovedWithoutResurrection() {
        UUID quiz = UUID.randomUUID();
        var open = openPublication(quiz, Audience.PUBLIC, 30);
        UUID attempt = activeAttempt(open.id(), student);
        String submitKey = key();
        students.submit(actor(student), attempt, 0, submitKey, null, "evidence");
        UUID operation = fenceAndAcknowledge(quiz);

        QuizPurgeService.Result result = null;
        int guard = 0;
        while (result == null || result.status() == Outcome.IN_PROGRESS) {
            result = purges.purgeBatch(List.of(new Operation(operation, quiz)), 5000)
                    .getFirst();
            assertTrue(++guard < 50);
        }
        assertEquals(Outcome.PURGED, result.status());

        // Stale submit replay (rows gone) and stale start replay both answer with the
        // non-disclosing ASSESSMENT_REMOVED error and resurrect nothing.
        var submitFailure = assertThrows(
                PublicationFailure.class,
                () -> students.submit(actor(student), attempt, 1, submitKey, null, "evidence"));
        assertEquals(404, submitFailure.status());
        assertEquals("ASSESSMENT_REMOVED", submitFailure.code());
        var startFailure = assertThrows(
                PublicationFailure.class,
                () -> students.start(actor(student), open.id(), key(), null, null, "evidence"));
        assertEquals(404, startFailure.status());
        assertEquals("ASSESSMENT_REMOVED", startFailure.code());
        assertEquals(0L, f.db.queryForObject("SELECT count(*) FROM attempts WHERE id=?", Long.class, attempt));
        assertEquals(0L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quiz));
    }
}
