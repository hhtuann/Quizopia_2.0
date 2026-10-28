package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.ClosureStore.Command;
import com.quizopia.assessment.application.ClosureStore.Teacher;
import com.quizopia.assessment.application.ClosureStore.Work;
import com.quizopia.assessment.application.QuizRevocationService.Batch;
import com.quizopia.assessment.application.QuizRevocationService.Operation;
import com.quizopia.assessment.configuration.AssessmentTransactionProperties;
import com.quizopia.assessment.configuration.ClosureProperties;
import com.quizopia.assessment.domain.Publication;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.domain.PublicationConfiguration.Audience;
import com.quizopia.assessment.domain.PublicationConfiguration.MonitoringChoice;
import com.quizopia.assessment.infrastructure.ClosureCursor;
import com.quizopia.assessment.monitoring.JdbcMonitoringOutbox;
import com.quizopia.assessment.persistence.*;
import com.quizopia.assessment.realtime.JdbcRealtimeInvalidationOutbox;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real PostgreSQL 17 with full V1..V9 migrations: durable fence installation, ADR-009 inbox
 * idempotency, and server-side denial of every quiz-scoped route with the stable non-disclosing
 * ASSESSMENT_REMOVED error. Unrelated quizzes stay byte-identical.
 */
@Testcontainers
class QuizRevocationFenceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AtomicFinalizationIntegrationTest f;
    QuizRevocationService revocations;
    QuizRevocationGate gate;
    JdbcPublicationStore publications;
    StudentAttemptService students;
    PublicationService publicationApi;
    JdbcClosureStore closure;
    ClosureService closureService;
    ClosureDependencies closureDeps;
    JdbcProctoringAuthorityStore proctoring;
    JdbcRealtimeAuthorityStore realtime;
    UUID owner;
    UUID student;
    UUID classroom;

    @BeforeEach
    void setup() {
        f = new AtomicFinalizationIntegrationTest();
        f.setup();
        revocations = new QuizRevocationService(f.db, f.manager, AssessmentTransactionProperties.defaults());
        gate = new QuizRevocationGate(f.db);
        publications = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC());
        proctoring = new JdbcProctoringAuthorityStore(f.db, gate);
        realtime = new JdbcRealtimeAuthorityStore(f.db, gate);
        var sources = mock(PublicationSources.class);
        var attempts = new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core);
        students = new StudentAttemptService(attempts, sources, f.core);
        publicationApi = new PublicationService(publications, sources);
        var properties = new ClosureProperties(
                true,
                true,
                true,
                true,
                50,
                50,
                "v1",
                Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        closure = new JdbcClosureStore(
                f.db, f.manager, AssessmentTransactionProperties.defaults(), new ClosureCursor(properties), gate);
        var dependencies = new ClosureDependencies() {
            public void requireInbox() {}

            public Eligibility eligible(UUID id) {
                return new Eligibility(id, true, dbNow());
            }

            public void teacher(Teacher actor) {}
        };
        closureDeps = dependencies;
        closureService = new ClosureService(closure, dependencies, f.core, properties, profiles -> Map.of());
        owner = UUID.randomUUID();
        student = UUID.randomUUID();
        classroom = UUID.randomUUID();
    }

    // ---- fixtures -------------------------------------------------------------

    Instant now() {
        return f.db.queryForObject(
                "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    Instant dbNow() {
        return f.db.queryForObject(
                "SELECT assessment_time()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    void advance(long seconds) {
        f.db.execute(
                "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE SET search_path=pg_catalog,pg_temp AS $$ SELECT clock_timestamp()+interval '"
                        + seconds + " seconds' $$");
    }

    String scalar(String query, Object... args) {
        return f.scalar(query, args);
    }

    PublicationConfiguration config(UUID quiz, Audience audience, int duration) {
        Instant time = now().truncatedTo(ChronoUnit.MICROS);
        return new PublicationConfiguration(
                "Revocation fixture title",
                null,
                quiz,
                UUID.randomUUID(),
                1,
                audience,
                audience == Audience.CLASS ? classroom : null,
                time.minusSeconds(86400),
                time.plusSeconds(86400),
                duration,
                audience == Audience.CLASS ? MonitoringChoice.ENABLED : MonitoringChoice.DISABLED,
                0);
    }

    Publication openPublication(UUID quiz, Audience audience, int duration) {
        var created = publications.create(owner, UUID.randomUUID().toString(), config(quiz, audience, duration));
        publications.open(
                owner,
                created.id(),
                0,
                new String(
                        SnapshotFixtures.snapshot(created.configuration(), "private source marker"),
                        StandardCharsets.UTF_8));
        return publications.owned(owner, created.id());
    }

    UUID activeAttempt(UUID publication, int duration, UUID actor) {
        UUID id = UUID.randomUUID();
        long ordinal = f.db.queryForObject(
                "SELECT coalesce(max(ordinal),0)+1 FROM attempts WHERE publication_id=? AND student_id=?",
                Long.class,
                publication,
                actor);
        Instant started = now().minusSeconds(1);
        f.db.update(
                "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,?,?,1,1,'ACTIVE',?,?,?)",
                id,
                publication,
                actor,
                ordinal,
                publication,
                duration,
                java.sql.Timestamp.from(started),
                duration == 0 ? null : java.sql.Timestamp.from(started.plusSeconds(duration * 60L)));
        return id;
    }

    String locator(UUID publication) {
        return scalar("SELECT private_locator FROM publications WHERE id=?", publication);
    }

    String key() {
        return JdbcPublicationStore.digest(UUID.randomUUID().toString());
    }

    StudentAttemptStore.Actor actor() {
        return new StudentAttemptStore.Actor(student, now().plusSeconds(3600));
    }

    Batch fence(UUID quiz) {
        var result = revocations.applyBatch(List.of(new QuizRevocationService.Operation(UUID.randomUUID(), quiz)));
        assertEquals(1, result.accepted().size());
        assertEquals(0, result.alreadyRevoked().size());
        return result;
    }

    void removed(Runnable operation) {
        var failure = assertThrows(PublicationFailure.class, operation::run);
        assertEquals("ASSESSMENT_REMOVED", failure.code());
        assertEquals(404, failure.status());
    }

    // ---- installation and inbox -----------------------------------------------

    @Test
    void fenceInstallationIsIdempotentAndRedeliverySafe() {
        UUID quiz = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        var first = revocations.applyBatch(List.of(new QuizRevocationService.Operation(operation, quiz)));
        assertEquals(0, first.alreadyRevoked().size());
        Instant revokedAt = first.accepted().getFirst().revokedAt();

        var redelivery = revocations.applyBatch(List.of(new QuizRevocationService.Operation(operation, quiz)));
        assertEquals(0, redelivery.accepted().size());
        assertEquals(quiz, redelivery.alreadyRevoked().getFirst().quizId());
        assertEquals(operation, redelivery.alreadyRevoked().getFirst().operationId());
        assertEquals(revokedAt, redelivery.alreadyRevoked().getFirst().revokedAt());
        assertEquals("1", scalar("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", quiz));
        assertEquals("1", scalar("SELECT count(*) FROM deletion_command_inbox WHERE operation_id=?", operation));
        assertEquals(
                "t",
                scalar("SELECT processed_at IS NOT NULL FROM deletion_command_inbox WHERE operation_id=?", operation));

        // A different operation for the same quiz keeps the original fence and new inbox evidence.
        UUID successor = UUID.randomUUID();
        var replay = revocations.applyBatch(List.of(new QuizRevocationService.Operation(successor, quiz)));
        assertEquals(0, replay.accepted().size());
        assertEquals(revokedAt, replay.alreadyRevoked().getFirst().revokedAt());
        assertEquals("1", scalar("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", quiz));
        assertEquals("2", scalar("SELECT count(*) FROM deletion_command_inbox WHERE quiz_id=?", quiz));

        // Batching several quizzes keeps durable uniqueness per quiz and operation.
        UUID second = UUID.randomUUID();
        var batch = revocations.applyBatch(List.of(
                new QuizRevocationService.Operation(UUID.randomUUID(), second),
                new QuizRevocationService.Operation(UUID.randomUUID(), UUID.randomUUID())));
        assertEquals(2, batch.accepted().size());
        assertEquals("1", scalar("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", second));
    }

    @Test
    void batchRejectsEmptyAndOversizedOperationLists() {
        assertThrows(PublicationFailure.class, () -> revocations.applyBatch(List.of()));
        var tooMany = new ArrayList<Operation>(51);
        for (int i = 0; i < 51; i++)
            tooMany.add(new QuizRevocationService.Operation(UUID.randomUUID(), UUID.randomUUID()));
        assertThrows(PublicationFailure.class, () -> revocations.applyBatch(tooMany));
        assertEquals("0", scalar("SELECT count(*) FROM quiz_revocation_fences"));
    }

    // ---- owner publication surface --------------------------------------------

    @Test
    void ownerDirectoryDetailAndMutationsDenyForRevokedQuiz() {
        UUID quiz = UUID.randomUUID();
        var open = openPublication(quiz, Audience.CLASS, 30);
        var publicOpen = openPublication(quiz, Audience.PUBLIC, 30);
        String draftKey = UUID.randomUUID().toString();
        var draftConfig = config(quiz, Audience.PUBLIC, 30);
        var draft = publications.create(owner, draftKey, draftConfig);
        UUID unaffected = UUID.randomUUID();
        var other = openPublication(unaffected, Audience.CLASS, 30);
        fence(quiz);

        removed(() -> publications.owned(owner, open.id()));
        removed(() -> publications.owned(owner, draft.id()));
        removed(() -> publications.update(owner, draft.id(), 0, config(quiz, Audience.PUBLIC, 30)));
        removed(() -> publications.open(owner, draft.id(), 0, "{}"));
        removed(() -> publications.extend(owner, open.id(), 1, now().plusSeconds(90000), 30));
        removed(() -> publications.publicEntry(locator(publicOpen.id())));
        removed(() -> publications.classEntry(open.id()));
        // New publications cannot reference the revoked quiz, not even as an idempotent replay
        // of a recorded creation receipt.
        removed(() -> publications.create(owner, UUID.randomUUID().toString(), config(quiz, Audience.CLASS, 30)));
        removed(() -> publications.replay(owner, draftKey, draftConfig));

        var page = publications.listOwned(owner, new PublicationDirectory.Query(100, null, "", null));
        assertTrue(page.items().stream().noneMatch(item -> item.quizId().equals(quiz)));
        assertTrue(page.items().stream().anyMatch(item -> item.publicationId().equals(other.id())));
        assertEquals("1", scalar("SELECT count(*) FROM publications WHERE quiz_id=?", unaffected));
        assertEquals(other.id(), publications.owned(owner, other.id()).id());
    }

    // ---- student attempt surface ----------------------------------------------

    @Test
    void studentEntryStartSaveSubmitAbandonResultReviewAndAggregateAllDeny() {
        UUID quiz = UUID.randomUUID();
        var publication = openPublication(quiz, Audience.PUBLIC, 30);
        UUID attempt = activeAttempt(publication.id(), 30, student);
        fence(quiz);

        removed(() -> publicationApi.publicEntry(locator(publication.id())));
        removed(() -> students.start(actor(), publication.id(), "START", locator(publication.id()), key(), "evidence"));
        removed(() -> students.resume(actor(), attempt, "evidence"));
        removed(() -> students.save(
                actor(), attempt, 1, 0, key(), "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}", "evidence"));
        removed(() -> students.abandon(actor(), attempt, 0, key(), "evidence"));
        removed(() -> students.submit(actor(), attempt, 0, key(), null, "evidence"));
        removed(() -> students.result(actor(), attempt, "evidence"));
        removed(() -> students.review(actor(), attempt, "evidence"));
        removed(() -> students.aggregate(actor(), publication.id(), "evidence"));
        // The attempt stays untouched for the later purge; no new grading after the fence.
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
        assertEquals("0", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", attempt));
    }

    @Test
    void idempotentReplayResponsesDenyForRevokedQuiz() {
        UUID quiz = UUID.randomUUID();
        var publication = openPublication(quiz, Audience.PUBLIC, 30);
        UUID attempt = activeAttempt(publication.id(), 30, student);
        String startKey = key();
        var view = students.start(actor(), publication.id(), "START", locator(publication.id()), startKey, "evidence")
                .value();
        String saveKey = key();
        students.save(
                actor(),
                view.attemptId(),
                1,
                0,
                saveKey,
                "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}",
                "evidence");
        String submitKey = key();
        var result = students.submit(actor(), view.attemptId(), 1, submitKey, null, "evidence");
        assertEquals("4", result.possibleWeight());
        fence(quiz);

        // Exact-key replays would leak deleted content: they must deny.
        removed(() ->
                students.start(actor(), publication.id(), "START", locator(publication.id()), startKey, "evidence"));
        removed(() -> students.save(
                actor(),
                view.attemptId(),
                1,
                0,
                saveKey,
                "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}",
                "evidence"));
        removed(() -> students.submit(actor(), view.attemptId(), 1, submitKey, null, "evidence"));
        removed(() -> students.result(actor(), view.attemptId(), "evidence"));
        // Committed-before-fence history remains durable in the database for later purge.
        assertEquals("SUBMITTED", scalar("SELECT state FROM attempts WHERE id=?", view.attemptId()));
        assertEquals("1", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", view.attemptId()));
    }

    @Test
    void teacherClosureCommandsResultsAndNotificationsDenyOrHideRevokedQuiz() {
        UUID quiz = UUID.randomUUID();
        var publication = openPublication(quiz, Audience.PUBLIC, 0);
        UUID attempt = activeAttempt(publication.id(), 0, student);
        Teacher teacher = new Teacher(owner, now().plusSeconds(8640000), "fixture");
        advance(86401);
        var worker = new com.quizopia.assessment.infrastructure.ClosureWorker(
                closure,
                closureDeps,
                f.core,
                new ClosureProperties(
                        true,
                        true,
                        true,
                        true,
                        50,
                        50,
                        "v1",
                        Map.of("v1", Base64.getEncoder().encodeToString(new byte[32]))),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var request = closureService.command(
                teacher,
                new Command(
                        publication.id(),
                        null,
                        1,
                        JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                        "REQUEST"));
        assertEquals(201, request.status());
        UUID requestId = ((ClosureViews.Closure) request.body()).closureRequestId();
        assertFalse(closure.candidates(50).isEmpty());
        worker.process(new Work(publication.id(), requestId, attempt, student, "NOTICE_PENDING"));
        var notification =
                closure.published(new Work(publication.id(), requestId, attempt, student, "NOTICE_PUBLISHED"));
        assertNotNull(notification.notificationId());
        assertFalse(closure.inbox(student, 20, null).items().isEmpty());
        fence(quiz);

        removed(() -> closureService.command(
                teacher,
                new Command(
                        publication.id(),
                        null,
                        2,
                        JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                        "REQUEST")));
        removed(() -> closureService.command(
                teacher,
                new Command(
                        publication.id(),
                        requestId,
                        2,
                        JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                        "CANCEL")));
        removed(() -> closureService.command(
                teacher,
                new Command(
                        publication.id(),
                        null,
                        2,
                        JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                        "CLOSE")));
        removed(() -> closureService.get(teacher, publication.id(), requestId));
        removed(() -> closureService.results(teacher, publication.id(), 20, null));
        // Recipient inbox and single-notification reads hide revoked closure evidence.
        assertTrue(closure.inbox(student, 20, null).items().isEmpty());
        assertThrows(PublicationFailure.class, () -> closure.notification(student, notification.notificationId()));
        // Background closure work skips revoked publications entirely.
        assertTrue(closure.candidates(50).isEmpty());
    }

    // ---- private authority reads ----------------------------------------------

    @Test
    void proctoringAndRealtimeAuthorityReadsDenyForRevokedQuiz() {
        UUID quiz = UUID.randomUUID();
        var monitored = openPublication(quiz, Audience.CLASS, 30);
        UUID attempt = activeAttempt(monitored.id(), 30, student);
        assertNotNull(proctoring.find(attempt));
        assertNotNull(realtime.attempt(attempt));
        assertNotNull(realtime.publication(monitored.id()));
        fence(quiz);

        removed(() -> proctoring.find(attempt));
        removed(() -> realtime.attempt(attempt));
        removed(() -> realtime.publication(monitored.id()));
    }

    // ---- background outbox publishers ------------------------------------------

    @Test
    void monitoringAndRealtimeInvalidationClaimsSkipRevokedPublications() {
        UUID quiz = UUID.randomUUID(), other = UUID.randomUUID();
        var revoked = openPublication(quiz, Audience.CLASS, 30);
        var kept = openPublication(other, Audience.CLASS, 30);
        UUID revokedAttempt = activeAttempt(revoked.id(), 30, student);
        UUID keptAttempt = activeAttempt(kept.id(), 30, student);
        var monitoringOutbox = new JdbcMonitoringOutbox(f.db, f.manager);
        var invalidationOutbox = new JdbcRealtimeInvalidationOutbox(f.db, f.manager);
        UUID revokedMonitoring = seedMonitoring(revokedAttempt);
        UUID keptMonitoring = seedMonitoring(keptAttempt);
        UUID revokedInvalidation = seedInvalidation(revoked.id());
        UUID keptInvalidation = seedInvalidation(kept.id());
        fence(quiz);

        var monitoring = monitoringOutbox.claim(UUID.randomUUID(), 5);
        assertEquals(1, monitoring.size());
        assertEquals(keptMonitoring, monitoring.getFirst().id());
        var invalidation = invalidationOutbox.claim(UUID.randomUUID(), 5);
        assertEquals(1, invalidation.size());
        assertEquals(keptInvalidation, invalidation.getFirst().id());
        assertEquals(kept.id(), invalidation.getFirst().resourceId());
        assertEquals(
                "1",
                scalar(
                        "SELECT count(*) FROM assessment_monitoring_outbox WHERE event_id=? AND lease_owner IS NULL",
                        revokedMonitoring));
        assertEquals(
                "1",
                scalar(
                        "SELECT count(*) FROM realtime_invalidation_outbox WHERE event_id=? AND lease_owner IS NULL",
                        revokedInvalidation));
    }

    private UUID seedMonitoring(UUID attempt) {
        UUID id = UUID.randomUUID();
        f.db.update(
                "INSERT INTO assessment_monitoring_outbox(event_id,attempt_id,question_number,answer_revision,payload,created_at) VALUES(?,?,1,1,'{\"kind\":\"fixture\"}'::jsonb,clock_timestamp())",
                id,
                attempt);
        return id;
    }

    private UUID seedInvalidation(UUID publication) {
        UUID id = UUID.randomUUID();
        f.db.update(
                "INSERT INTO realtime_invalidation_outbox(event_id,event_type,event_version,resource_kind,resource_id,occurred_at,created_at) VALUES(?,'PUBLICATION_INVALIDATED',1,'PUBLICATION',?,clock_timestamp(),clock_timestamp())",
                id,
                publication);
        return id;
    }

    // ---- unrelated quizzes ------------------------------------------------------

    @Test
    void unrelatedQuizKeepsFullLifecycleAfterFencing() {
        UUID quiz = UUID.randomUUID(), other = UUID.randomUUID();
        var revokedPublication = openPublication(quiz, Audience.PUBLIC, 30);
        activeAttempt(revokedPublication.id(), 30, student);
        fence(quiz);

        var kept = openPublication(other, Audience.PUBLIC, 30);
        var view = students.start(actor(), kept.id(), "START", locator(kept.id()), key(), "evidence")
                .value();
        students.save(
                actor(),
                view.attemptId(),
                1,
                0,
                key(),
                "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"B\"}",
                "evidence");
        var result = students.submit(actor(), view.attemptId(), 1, key(), null, "evidence");
        assertEquals("1", result.earnedWeight());
        assertEquals(kept.id(), publications.owned(owner, kept.id()).id());
        assertEquals("1", scalar("SELECT count(*) FROM publications WHERE quiz_id=?", other));
        // The fenced quiz denies while the sibling quiz stays fully live.
        removed(() -> publications.owned(owner, revokedPublication.id()));
    }

    // ---- acknowledgment ----------------------------------------------------------

    @Test
    void acknowledgmentDrainsOnlyWhenNoMutationPostdatesTheFence() throws Exception {
        UUID quiz = UUID.randomUUID(), violated = UUID.randomUUID();
        var publication = openPublication(quiz, Audience.PUBLIC, 30);
        UUID attempt = activeAttempt(publication.id(), 30, student);
        students.save(actor(), attempt, 1, 0, key(), "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}", "evidence");
        var drained = fence(quiz).accepted().getFirst().revokedAt();
        Thread.sleep(10);

        var status = revocations.status(quiz).orElseThrow();
        assertEquals(quiz, status.quizId());
        assertEquals(drained, status.revokedAt());
        assertNull(status.acknowledgedAt());
        assertEquals(1, status.publicationCount());
        assertEquals(1, status.attemptCount());

        assertTrue(revocations.acknowledgeDue(10) >= 1);
        assertNotNull(revocations.status(quiz).orElseThrow().acknowledgedAt());
        assertEquals("t", scalar("SELECT ack_at>=revoked_at FROM quiz_revocation_fences WHERE quiz_id=?", quiz));

        // A mutation timestamp after revoked_at keeps the fence unacknowledged.
        var late = openPublication(violated, Audience.PUBLIC, 30);
        UUID lateAttempt = activeAttempt(late.id(), 30, student);
        f.db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,revoked_at,operation_id,source,ack_at) VALUES(?,clock_timestamp()-interval '60 seconds',?,'RECONCILE',NULL)",
                violated,
                UUID.randomUUID());
        new org.springframework.transaction.support.TransactionTemplate(f.manager).execute(ignored -> {
            f.saved(lateAttempt, 1, "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}", 1);
            return null;
        });
        revocations.acknowledgeDue(10);
        assertNull(revocations.status(violated).orElseThrow().acknowledgedAt());
    }

    // ---- flag independence --------------------------------------------------------

    @Test
    void reconciliationInstalledFenceEnforcesWithoutAnyFlag() {
        UUID quiz = UUID.randomUUID();
        var publication = openPublication(quiz, Audience.PUBLIC, 30);
        // No service/flag involved: a RECONCILE fence row alone must deny every route.
        f.db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,revoked_at,operation_id,source,ack_at) VALUES(?,clock_timestamp(),?,'RECONCILE',NULL)",
                quiz,
                UUID.randomUUID());
        removed(() -> publications.owned(owner, publication.id()));
        removed(() -> publicationApi.publicEntry(locator(publication.id())));
        assertEquals("RECONCILE", scalar("SELECT source FROM quiz_revocation_fences WHERE quiz_id=?", quiz));
    }

    @Test
    void closedPublicationOfRevokedQuizDeniesReads() {
        UUID quiz = UUID.randomUUID();
        var publication = openPublication(quiz, Audience.PUBLIC, 0);
        UUID attempt = activeAttempt(publication.id(), 0, student);
        Teacher teacher = new Teacher(owner, now().plusSeconds(8640000), "fixture");
        advance(86401);
        var worker = new com.quizopia.assessment.infrastructure.ClosureWorker(
                closure,
                closureDeps,
                f.core,
                new ClosureProperties(
                        true,
                        true,
                        true,
                        true,
                        50,
                        50,
                        "v1",
                        Map.of("v1", Base64.getEncoder().encodeToString(new byte[32]))),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var request = closureService.command(
                teacher,
                new Command(
                        publication.id(),
                        null,
                        1,
                        JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                        "REQUEST"));
        UUID requestId = ((ClosureViews.Closure) request.body()).closureRequestId();
        worker.process(new Work(publication.id(), requestId, attempt, student, "NOTICE_PENDING"));
        worker.process(new Work(publication.id(), requestId, attempt, student, "NOTICE_PUBLISHED"));
        advance(259202);
        assertEquals(
                200,
                closureService
                        .command(
                                teacher,
                                new Command(
                                        publication.id(),
                                        requestId,
                                        2,
                                        JdbcPublicationStore.digest(
                                                UUID.randomUUID().toString()),
                                        "COMPLETE"))
                        .status());
        assertEquals("CLOSED", scalar("SELECT state FROM publications WHERE id=?", publication.id()));
        var reader = new StudentAttemptStore.Actor(student, now().plusSeconds(8640000));
        assertNotNull(students.review(reader, attempt, "evidence"));
        fence(quiz);

        removed(() -> publications.owned(owner, publication.id()));
        removed(() -> students.review(reader, attempt, "evidence"));
        removed(() -> closureService.results(teacher, publication.id(), 20, null));
    }
}
