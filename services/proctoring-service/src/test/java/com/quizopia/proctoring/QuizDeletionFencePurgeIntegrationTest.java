package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.domain.Monitoring.*;
import com.quizopia.proctoring.media.persistence.JdbcMediaStore;
import com.quizopia.proctoring.persistence.JdbcMonitoringLookup;
import com.quizopia.proctoring.persistence.JdbcMonitoringStore;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * FB-015 PR C fence + purge on real PostgreSQL 17 with disposable fixtures only: the fence
 * denies new sessions/capture/media/reads for the revoked quiz while an unrelated quiz keeps
 * full behavior, the privileged purge removes every quiz-scoped row without disabling any
 * trigger, is idempotent, and stays refused until the fence is acknowledged.
 */
@Testcontainers
class QuizDeletionFencePurgeIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("fb015_proctoring_roles.sql");

    static DriverManagerDataSource source;
    static JdbcTemplate db;
    JdbcMonitoringStore store;
    JdbcMediaStore media;
    JdbcMonitoringLookup lookup;
    QuizRevocationService revocations;
    QuizPurgeService purges;
    UUID student;
    UUID teacher;
    UUID quiz;
    UUID otherQuiz;
    UUID otherStudent;
    Instant deadline;
    AtomicLong sequences = new AtomicLong();

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        db = new JdbcTemplate(source);
    }

    @BeforeEach
    void fixture() {
        db.execute("TRUNCATE quiz_revocation_fences,deletion_command_inbox,deletion_purge_receipts,"
                + "media_erasure_requests,monitoring_access_audit,monitoring_evidence,"
                + "monitoring_media_grants,monitoring_media_rooms,monitoring_sessions RESTART IDENTITY CASCADE");
        var manager = new DataSourceTransactionManager(source);
        store = new JdbcMonitoringStore(db, manager, new MonitoringProperties());
        media = new JdbcMediaStore(db, manager);
        lookup = new JdbcMonitoringLookup(db);
        revocations = new QuizRevocationService(db, manager);
        purges = new QuizPurgeService(db, manager, "livekit");
        student = UUID.randomUUID();
        teacher = UUID.randomUUID();
        quiz = UUID.randomUUID();
        otherQuiz = UUID.randomUUID();
        otherStudent = UUID.randomUUID();
        deadline = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
    }

    MonitoringAuthority.Actor actor(UUID id, String role) {
        return new MonitoringAuthority.Actor(
                id, Set.of(role), "synthetic-user-evidence", Instant.now().plusSeconds(600));
    }

    /** Stable per-session identity: the store revalidates the whole snapshot on every call. */
    record Bound(UUID quizId, UUID attemptId, UUID publicationId, UUID classroomId, UUID studentId) {}

    MonitoringAuthority.Snapshot authority(Bound bound, UUID id, String role, boolean active) {
        return new MonitoringAuthority.Snapshot(
                id,
                role,
                bound.attemptId(),
                bound.publicationId(),
                bound.classroomId(),
                bound.studentId(),
                active,
                deadline,
                Instant.now(),
                bound.quizId());
    }

    record Fixture(Bound bound, Session session) {}

    Fixture monitored(UUID quizId, UUID sessionStudent) {
        var bound = new Bound(quizId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sessionStudent);
        return new Fixture(
                bound,
                store.create(actor(sessionStudent, "STUDENT"), authority(bound, sessionStudent, "STUDENT", true)));
    }

    Fixture monitored(UUID quizId) {
        return monitored(quizId, student);
    }

    Acknowledgment append(Fixture fixture) {
        return store.append(
                actor(fixture.session().studentId(), "STUDENT"),
                authority(fixture.bound(), fixture.session().studentId(), "STUDENT", true),
                fixture.session().id(),
                new Event(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        sequences.incrementAndGet(),
                        EventType.WINDOW_FOCUS,
                        Instant.now(),
                        null,
                        1));
    }

    void fence(UUID operation) {
        var batch = revocations.applyBatch(List.of(new QuizRevocationService.Operation(operation, quiz)));
        assertEquals(1, batch.accepted().size());
    }

    MonitoringFailure failure(int status, String code, org.junit.jupiter.api.function.Executable operation) {
        var failure = assertThrows(MonitoringFailure.class, operation);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
        return failure;
    }

    @Test
    void fenceDeniesNewSessionsCaptureReadsLookupAndMediaForRevokedQuizOnly() {
        var existing = monitored(quiz);
        append(existing);
        store.reconcile(
                actor(student, "STUDENT"),
                authority(existing.bound(), student, "STUDENT", true),
                existing.session().id(),
                true);
        var grant = media.prepare(
                actor(student, "STUDENT"),
                authority(existing.bound(), student, "STUDENT", true),
                existing.session().id(),
                Duration.ofSeconds(10));
        media.activate(actor(student, "STUDENT"), authority(existing.bound(), student, "STUDENT", true), grant.id());

        UUID operation = UUID.randomUUID();
        fence(operation);

        // New monitoring sessions for the revoked quiz are denied.
        var fresh = new Bound(quiz, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), otherStudent);
        failure(
                404,
                QuizRevocationGate.REMOVED,
                () -> store.create(actor(otherStudent, "STUDENT"), authority(fresh, otherStudent, "STUDENT", true)));
        // Existing session: every capture/append/read path denies before any work.
        failure(404, QuizRevocationGate.REMOVED, () -> append(existing));
        failure(
                404,
                QuizRevocationGate.REMOVED,
                () -> store.find(existing.session().id()));
        failure(
                404,
                QuizRevocationGate.REMOVED,
                () -> store.reconcile(
                        actor(student, "STUDENT"),
                        authority(existing.bound(), student, "STUDENT", true),
                        existing.session().id(),
                        true));
        failure(
                404,
                QuizRevocationGate.REMOVED,
                () -> store.timeline(
                        actor(teacher, "TEACHER"),
                        authority(existing.bound(), teacher, "TEACHER", true),
                        existing.session().id(),
                        0,
                        10));
        failure(
                404,
                QuizRevocationGate.REMOVED,
                () -> lookup.existingSession(existing.bound().attemptId()));
        failure(
                404,
                QuizRevocationGate.REMOVED,
                () -> media.prepare(
                        actor(student, "STUDENT"),
                        authority(existing.bound(), student, "STUDENT", true),
                        existing.session().id(),
                        Duration.ofSeconds(10)));
        failure(
                404,
                QuizRevocationGate.REMOVED,
                () -> media.renew(
                        actor(student, "STUDENT"),
                        authority(existing.bound(), student, "STUDENT", true),
                        grant.id(),
                        Duration.ofSeconds(10)));
        // Live media grants were fenced by the revocation itself.
        assertEquals(
                "REVOKE_PENDING",
                db.queryForObject("SELECT state FROM monitoring_media_grants WHERE id=?", String.class, grant.id()));

        // The unrelated quiz keeps full behavior through its own session.
        var kept = monitored(otherQuiz, otherStudent);
        assertNotNull(append(kept));
        assertNotNull(store.find(kept.session().id()));
        assertNotNull(store.timeline(
                actor(teacher, "TEACHER"),
                authority(kept.bound(), teacher, "TEACHER", true),
                kept.session().id(),
                0,
                10));
        assertNotNull(media.prepare(
                actor(otherStudent, "STUDENT"),
                authority(kept.bound(), otherStudent, "STUDENT", true),
                kept.session().id(),
                Duration.ofSeconds(10)));
    }

    @Test
    void fenceInstallationIsIdempotentAndRedeliverySafe() {
        UUID operation = UUID.randomUUID();
        fence(operation);
        var replay = revocations.applyBatch(List.of(new QuizRevocationService.Operation(operation, quiz)));
        assertEquals(0, replay.accepted().size());
        assertEquals(1, replay.alreadyRevoked().size());
        assertEquals("1", db.queryForObject("SELECT count(*) FROM quiz_revocation_fences", String.class));
        assertEquals(
                "1",
                db.queryForObject(
                        "SELECT count(*) FROM deletion_command_inbox WHERE operation_id=?", String.class, operation));
        var status = revocations.status(quiz).orElseThrow();
        assertEquals(quiz, status.quizId());
        assertNotNull(status.revokedAt());
        assertNull(status.acknowledgedAt());
        assertTrue(revocations.status(UUID.randomUUID()).isEmpty());
    }

    @Test
    void acknowledgmentWaitsUntilEvidenceAndAuditTimestampsDrain() {
        var existing = monitored(quiz);
        append(existing);
        fence(UUID.randomUUID());
        // Evidence committed after revoked_at (the fence race loser) blocks the ack...
        db.update(
                "UPDATE quiz_revocation_fences SET revoked_at=clock_timestamp()-interval '2 hours' WHERE quiz_id=?",
                quiz);
        assertEquals(0, revocations.acknowledgeDue(10));
        assertNull(db.queryForObject(
                "SELECT ack_at FROM quiz_revocation_fences WHERE quiz_id=?", java.sql.Timestamp.class, quiz));
        // ...and drains once no post-fence mutation remains.
        db.update(
                "UPDATE quiz_revocation_fences SET revoked_at=(SELECT max(received_at) FROM monitoring_evidence e"
                        + " JOIN monitoring_sessions s ON s.id=e.session_id WHERE s.quiz_id=?) WHERE quiz_id=?",
                quiz,
                quiz);
        assertTrue(revocations.acknowledgeDue(10) >= 1);
        assertNotNull(db.queryForObject(
                "SELECT ack_at FROM quiz_revocation_fences WHERE quiz_id=?", java.sql.Timestamp.class, quiz));
    }

    @Test
    void purgeRefusedWithoutAcknowledgmentAndKeepsEveryRow() {
        var existing = monitored(quiz);
        append(existing);
        store.reconcile(
                actor(student, "STUDENT"),
                authority(existing.bound(), student, "STUDENT", true),
                existing.session().id(),
                true);
        UUID operation = UUID.randomUUID();
        fence(operation);
        var result = purges.purgeBatch(List.of(new QuizPurgeService.PurgeOperation(operation, quiz)), 100);
        assertEquals(
                QuizPurgeService.Outcome.WAITING_REVOCATION_ACK,
                result.getFirst().status());
        assertEquals(1, count("monitoring_sessions"));
        assertTrue(purges.status(quiz).orElseThrow().steps().isEmpty());
        // The privileged procedure itself refuses an unacknowledged fence.
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.queryForObject(
                        "SELECT monitoring_purge_quiz(?,?,NULL::uuid[],NULL::uuid[],10)",
                        String.class,
                        quiz,
                        operation));
    }

    @Test
    void purgeRemovesEveryQuizRowKeepsUnrelatedQuizAndStaysIdempotent() {
        var revoked = monitored(quiz);
        append(revoked);
        append(revoked);
        store.reconcile(
                actor(student, "STUDENT"),
                authority(revoked.bound(), student, "STUDENT", true),
                revoked.session().id(),
                true);
        var grant = media.prepare(
                actor(student, "STUDENT"),
                authority(revoked.bound(), student, "STUDENT", true),
                revoked.session().id(),
                Duration.ofSeconds(10));
        media.activate(actor(student, "STUDENT"), authority(revoked.bound(), student, "STUDENT", true), grant.id());
        var kept = monitored(otherQuiz, otherStudent);
        append(kept);
        store.reconcile(
                actor(otherStudent, "STUDENT"),
                authority(kept.bound(), otherStudent, "STUDENT", true),
                kept.session().id(),
                true);
        var keptGrant = media.prepare(
                actor(otherStudent, "STUDENT"),
                authority(kept.bound(), otherStudent, "STUDENT", true),
                kept.session().id(),
                Duration.ofSeconds(10));

        UUID operation = UUID.randomUUID();
        fence(operation);
        assertTrue(revocations.acknowledgeDue(10) >= 1);

        // Budget 1 proves bounded rounds: each call removes at most one row total and
        db.update(
                "INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES(?,'synthetic-provider-receipt')",
                operation);
        // reports IN_PROGRESS until the final verified-zero round reports PURGED.
        QuizPurgeService.Result first = null;
        int rounds = 0;
        while (first == null || first.status() == QuizPurgeService.Outcome.IN_PROGRESS) {
            first = purges.purgeBatch(List.of(new QuizPurgeService.PurgeOperation(operation, quiz)), 1)
                    .getFirst();
            rounds++;
            assertTrue(rounds < 100, "bounded purge must converge");
        }
        assertEquals(QuizPurgeService.Outcome.PURGED, first.status());
        assertTrue(rounds > 1, "fixture must be larger than one budget round");
        assertTrue(first.steps().containsKey("verified_zero"));
        assertEquals(1L, first.steps().get("monitoring_sessions"));
        assertEquals(0, count("monitoring_sessions WHERE quiz_id='" + quiz + "'"));
        assertEquals(0, quizScoped("monitoring_evidence", quiz));
        assertEquals(0, quizScoped("monitoring_access_audit", quiz));
        assertEquals(0, quizScoped("monitoring_media_rooms", quiz));
        assertEquals(0, quizScoped("monitoring_media_grants", quiz));
        // The fence itself stays as the durable tombstone.
        assertEquals(
                "1",
                db.queryForObject("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", String.class, quiz));

        // Unrelated quiz is 100% intact.
        assertEquals(1, count("monitoring_sessions WHERE quiz_id='" + otherQuiz + "'"));
        assertEquals(2, quizScoped("monitoring_evidence", otherQuiz));
        assertEquals(1, quizScoped("monitoring_access_audit", otherQuiz));
        assertEquals(1, quizScoped("monitoring_media_rooms", otherQuiz));
        assertEquals(1, quizScoped("monitoring_media_grants", otherQuiz));
        assertEquals(
                "PREPARED",
                db.queryForObject(
                        "SELECT state FROM monitoring_media_grants WHERE id=?", String.class, keptGrant.id()));

        // Triggers are untouched: raw deletes are still rejected after the purge.
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.execute("DELETE FROM monitoring_evidence WHERE session_id='"
                        + kept.session().id() + "'"));
        assertThrows(
                org.springframework.dao.DataAccessException.class, () -> db.execute("DELETE FROM monitoring_sessions"));

        // Idempotent re-run: PURGED with unchanged cumulative receipts and no error rows.
        var second = purges.purgeBatch(List.of(new QuizPurgeService.PurgeOperation(operation, quiz)), 100)
                .getFirst();
        assertEquals(QuizPurgeService.Outcome.PURGED, second.status());
        assertEquals(first.steps(), second.steps());
        assertEquals(0, count("deletion_command_inbox WHERE operation_id='" + operation + "'"));
        var status = purges.status(quiz).orElseThrow();
        assertTrue(status.purged());
        assertEquals("ACKNOWLEDGED", status.providerErasure());
        assertEquals(
                "PENDING_PROVIDER",
                db.queryForObject("SELECT status FROM media_erasure_requests WHERE quiz_id=?", String.class, quiz));
    }

    @Test
    void purgeCannotTouchSessionsOfADifferentQuizEvenWithAuthorizationActive() {
        var revoked = monitored(quiz);
        var kept = monitored(otherQuiz, otherStudent);
        UUID operation = UUID.randomUUID();
        fence(operation);
        assertTrue(revocations.acknowledgeDue(10) >= 1);
        assertEquals(
                QuizPurgeService.Outcome.PURGED,
                purges.purgeBatch(List.of(new QuizPurgeService.PurgeOperation(operation, quiz)), 100)
                        .getFirst()
                        .status());
        assertEquals(
                "1",
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_sessions WHERE id=?",
                        String.class,
                        kept.session().id()));
        assertEquals(
                "1",
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_evidence WHERE session_id=?",
                        String.class,
                        kept.session().id()));
        assertEquals(
                "0",
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_evidence WHERE session_id=?",
                        String.class,
                        revoked.session().id()));
    }

    @Test
    void concurrentDuplicatePurgeCallsKeepReceiptsExactlyOnce() throws Exception {
        var revoked = monitored(quiz);
        append(revoked);
        append(revoked);
        store.reconcile(
                actor(student, "STUDENT"),
                authority(revoked.bound(), student, "STUDENT", true),
                revoked.session().id(),
                true);
        var grant = media.prepare(
                actor(student, "STUDENT"),
                authority(revoked.bound(), student, "STUDENT", true),
                revoked.session().id(),
                Duration.ofSeconds(10));
        media.activate(actor(student, "STUDENT"), authority(revoked.bound(), student, "STUDENT", true), grant.id());
        UUID operation = UUID.randomUUID();
        fence(operation);
        assertTrue(revocations.acknowledgeDue(10) >= 1);

        // Duplicate concurrent coordinator calls for the SAME operation: each caller
        db.update(
                "INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES(?,'synthetic-provider-receipt')",
                operation);
        // gets a truthful answer (PURGED or an honest serialization failure), and the
        // durable receipts count every removed row exactly once.
        var statuses = new java.util.concurrent.ConcurrentLinkedQueue<QuizPurgeService.Outcome>();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            pool.submit(() -> {
                try {
                    statuses.add(purges.purgeBatch(List.of(new QuizPurgeService.PurgeOperation(operation, quiz)), 100)
                            .getFirst()
                            .status());
                } catch (RuntimeException serialization) {
                    failures.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(statuses.contains(QuizPurgeService.Outcome.PURGED), "at least one duplicate call must finish");

        // Sequential convergence round in case every concurrent duplicate failed.
        QuizPurgeService.Result converged = null;
        int guard = 0;
        while (converged == null || converged.status() == QuizPurgeService.Outcome.IN_PROGRESS) {
            converged = purges.purgeBatch(List.of(new QuizPurgeService.PurgeOperation(operation, quiz)), 100)
                    .getFirst();
            assertTrue(++guard < 50);
        }
        assertEquals(QuizPurgeService.Outcome.PURGED, converged.status());
        assertEquals(0, count("monitoring_sessions WHERE quiz_id='" + quiz + "'"));
        assertEquals(0, quizScoped("monitoring_evidence", quiz));
        assertEquals(0, quizScoped("monitoring_access_audit", quiz));
        assertEquals(0, quizScoped("monitoring_media_rooms", quiz));
        assertEquals(0, quizScoped("monitoring_media_grants", quiz));
        // Exactly-once cumulative receipts against the known fixture shape.
        assertEquals(1L, converged.steps().get("monitoring_sessions"));
        assertEquals(3L, converged.steps().get("monitoring_evidence"));
        assertEquals(1L, converged.steps().get("monitoring_access_audit"));
        assertEquals(1L, converged.steps().get("monitoring_media_rooms"));
        assertEquals(1L, converged.steps().get("monitoring_media_grants"));
        // A further duplicate replay changes nothing.
        var replay = purges.purgeBatch(List.of(new QuizPurgeService.PurgeOperation(operation, quiz)), 100)
                .getFirst();
        assertEquals(QuizPurgeService.Outcome.PURGED, replay.status());
        assertEquals(converged.steps(), replay.steps());
    }

    int count(String tableAndFilter) {
        return db.queryForObject("SELECT count(*) FROM " + tableAndFilter, Integer.class);
    }

    int quizScoped(String table, UUID quizId) {
        return db.queryForObject(
                "SELECT count(*) FROM " + table
                        + " t JOIN monitoring_sessions s ON s.id=t.session_id WHERE s.quiz_id=?",
                Integer.class,
                quizId);
    }
}
