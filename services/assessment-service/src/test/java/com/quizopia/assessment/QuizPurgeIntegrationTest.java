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

        var first =
                purges.purgeBatch(List.of(new Operation(operation, quiz)), 2).getFirst();
        assertEquals(Outcome.PURGED, first.status());
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

        // Idempotent re-run: PURGED, unchanged cumulative receipts. The only moving count
        // is the redelivered command inbox row itself (recorded then removed again).
        var second =
                purges.purgeBatch(List.of(new Operation(operation, quiz)), 500).getFirst();
        assertEquals(Outcome.PURGED, second.status());
        var stableFirst = new java.util.HashMap<>(first.steps());
        var stableSecond = new java.util.HashMap<>(second.steps());
        long inboxFirst = stableFirst.remove("deletion_command_inbox");
        long inboxSecond = stableSecond.remove("deletion_command_inbox");
        assertEquals(stableFirst, stableSecond);
        assertEquals(inboxFirst + 1, inboxSecond);
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
                () -> f.db.queryForObject("SELECT assessment_purge_quiz(?,?,10)", Boolean.class, quiz, operation));
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
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> f.db.queryForObject("SELECT assessment_purge_quiz(?,?,10)", Boolean.class, quiz, operation));

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
                () -> f.db.queryForObject("SELECT assessment_purge_quiz(?,?,10)", Boolean.class, quiz, stranger));
        assertEquals(1L, f.db.queryForObject("SELECT count(*) FROM publications WHERE quiz_id=?", Long.class, quiz));
    }
}
