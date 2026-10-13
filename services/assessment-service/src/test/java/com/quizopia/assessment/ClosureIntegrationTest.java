package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.ClosureStore.*;
import com.quizopia.assessment.configuration.*;
import com.quizopia.assessment.infrastructure.*;
import com.quizopia.assessment.persistence.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ClosureIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AtomicFinalizationIntegrationTest f;
    JdbcClosureStore store;
    ClosureService service;
    ClosureWorker worker;
    ClosureDependencies dependencies;
    ClosureProperties properties;
    Teacher teacher;
    UUID publication;
    UUID attempt;

    @BeforeEach
    void setup() {
        f = new AtomicFinalizationIntegrationTest();
        f.setup();
        var seed = f.publication(0);
        publication = seed.publication();
        attempt = f.active(seed, 0);
        teacher = new Teacher(
                UUID.fromString(f.scalar("SELECT owner_id FROM publications WHERE id=?", publication)),
                Instant.now().plusSeconds(8640000),
                "fixture");
        properties = new ClosureProperties(
                true,
                true,
                true,
                true,
                50,
                50,
                "v1",
                Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        store = new JdbcClosureStore(
                f.db, f.manager, AssessmentTransactionProperties.defaults(), new ClosureCursor(properties));
        ClosureDependencies deps = new ClosureDependencies() {
            public void requireInbox() {}

            public Eligibility eligible(UUID id) {
                return new Eligibility(id, true, now());
            }

            public void teacher(Teacher actor) {}
        };
        dependencies = deps;
        service = new ClosureService(store, deps, f.core, properties);
        worker = new ClosureWorker(store, deps, f.core, properties, new SimpleMeterRegistry());
        advance(86401);
    }

    Instant now() {
        return f.db.queryForObject(
                "SELECT assessment_time()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    void advance(long seconds) {
        f.db.execute(
                "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE SET search_path=pg_catalog,pg_temp AS $$ SELECT clock_timestamp()+interval '"
                        + seconds + " seconds' $$");
    }

    Command command(String action, UUID request, long revision) {
        return new Command(
                publication,
                request,
                revision,
                JdbcPublicationStore.digest(UUID.randomUUID().toString()),
                action);
    }

    UUID request() {
        var reply = service.command(teacher, command("REQUEST", null, 1));
        assertEquals(201, reply.status());
        return ((ClosureViews.Closure) reply.body()).closureRequestId();
    }

    Work work(UUID request, String stage) {
        return new Work(publication, request, attempt, f.student, stage);
    }

    void published(UUID request) {
        worker.process(work(request, "NOTICE_PENDING"));
    }

    void confirmed(UUID request) {
        published(request);
        worker.process(work(request, "NOTICE_PUBLISHED"));
        assertNotNull(store.published(work(request, "NOTICE_PUBLISHED")).confirmedNoticeAt());
    }

    void failure(String code, Runnable runnable) {
        f.failure(code, runnable);
    }

    String scalar(String query, Object... args) {
        return f.scalar(query, args);
    }

    @Test
    void publishCommitsBeforeConfirmationOwnerProjectionAndExact48Hours() {
        var request = request();
        assertNull(store.get(teacher, publication, request).finalizationStartedAt());
        published(request);
        var published = store.published(work(request, "NOTICE_PUBLISHED"));
        assertNull(published.confirmedNoticeAt());
        assertNull(published.closureCutoffAt());
        assertEquals(
                AttemptFinalization.Kind.NOT_DUE,
                f.core.noticeFinalize(attempt, request).kind());
        failure("NOTIFICATION_NOT_FOUND", () -> store.notification(UUID.randomUUID(), published.notificationId()));
        worker.process(work(request, "NOTICE_PUBLISHED"));
        var confirmed = store.notification(f.student, published.notificationId());
        assertEquals(
                Duration.ofHours(48), Duration.between(confirmed.confirmedNoticeAt(), confirmed.closureCutoffAt()));
        worker.process(work(request, "NOTICE_PUBLISHED"));
        assertEquals(
                confirmed.confirmedNoticeAt(),
                store.notification(f.student, published.notificationId()).confirmedNoticeAt());
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
    }

    @Test
    void confirmationInOriginalPublishTransactionRejected() {
        var request = request();
        var w = work(request, "NOTICE_PENDING");
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(f.manager).execute(s -> {
            var id = UUID.randomUUID();
            f.db.update(
                    "INSERT INTO assessment_notifications(id,closure_request_id,attempt_id,recipient_id,content_version,message,publication_title,state,published_at) VALUES(?,?,?,?,1,?,'Title','PUBLISHED',assessment_time())",
                    id,
                    request,
                    attempt,
                    f.student,
                    JdbcClosureStore.WARNING);
            f.db.update(
                    "UPDATE closure_request_members SET state='NOTICE_PUBLISHED',notification_id=? WHERE closure_request_id=? AND attempt_id=?",
                    id,
                    request,
                    attempt);
            f.db.update(
                    "UPDATE closure_request_members SET state='NOTICE_CONFIRMED',confirmed_notice_at=assessment_time(),closure_cutoff_at=assessment_time()+interval '48 hours',accessibility_version=1,recipient_checked_at=assessment_time() WHERE closure_request_id=? AND attempt_id=?",
                    request,
                    attempt);
            return null;
        }));
        assertEquals("0", scalar("SELECT count(*) FROM assessment_notifications"));
        assertNull(store.get(teacher, publication, request).finalizationStartedAt());
    }

    @Test
    void staleAndFutureAuthorityCannotConfirm() {
        var request = request();
        published(request);
        failure(
                "DEPENDENCY_UNAVAILABLE",
                () -> store.confirm(
                        work(request, "NOTICE_PUBLISHED"),
                        new ClosureDependencies.Eligibility(f.student, true, now().minusSeconds(6))));
        failure(
                "DEPENDENCY_UNAVAILABLE",
                () -> store.confirm(
                        work(request, "NOTICE_PUBLISHED"),
                        new ClosureDependencies.Eligibility(f.student, true, now().plusSeconds(5))));
        assertNull(store.published(work(request, "NOTICE_PUBLISHED")).closureCutoffAt());
    }

    @Test
    void cancelPreservesHistoryAndReplacementNoticeIsFresh() {
        var request = request();
        confirmed(request);
        var notice = store.published(work(request, "NOTICE_PUBLISHED"));
        var cancel = command("CANCEL", request, 2);
        assertEquals(200, service.command(teacher, cancel).status());
        assertEquals(200, service.command(teacher, cancel).status());
        assertEquals(
                "CANCELLED",
                store.notification(f.student, notice.notificationId()).noticeStatus());
        advance(86401 + 172801);
        assertEquals(
                AttemptFinalization.Kind.NOT_DUE,
                f.core.noticeFinalize(attempt, request).kind());
        var replacement = (ClosureViews.Closure)
                service.command(teacher, command("REQUEST", null, 3)).body();
        confirmed(replacement.closureRequestId());
        assertTrue(store.published(work(replacement.closureRequestId(), "NOTICE_PUBLISHED"))
                .closureCutoffAt()
                .isAfter(notice.closureCutoffAt()));
        assertEquals(
                AttemptFinalization.Kind.NOT_DUE,
                f.core.noticeFinalize(attempt, request).kind());
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
    }

    @Test
    void cutoffUsesSavedAnswersFenceAndRealClosedReview() {
        new TransactionTemplate(f.manager).execute(s -> {
            f.saved(attempt, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", 1);
            return null;
        });
        var request = request();
        confirmed(request);
        advance(86401 + 172801);
        var outcome = f.core.noticeFinalize(attempt, request);
        assertEquals(AttemptFinalization.Kind.SUBMITTED, outcome.kind());
        assertEquals("1", outcome.result().orElseThrow().earnedWeight());
        assertEquals("SYSTEM", scalar("SELECT actor_kind FROM attempt_finalization_audit WHERE attempt_id=?", attempt));
        assertEquals("FINALIZED", scalar("SELECT state FROM closure_request_members WHERE attempt_id=?", attempt));
        assertEquals(
                "0", scalar("SELECT count(*) FROM attempt_operations WHERE attempt_id=? AND action='SUBMIT'", attempt));
        failure("CLOSURE_FINALIZATION_STARTED", () -> service.command(teacher, command("CANCEL", request, 2)));
        var complete = command("COMPLETE", request, 2);
        assertEquals(200, service.command(teacher, complete).status());
        assertEquals(200, service.command(teacher, complete).status());
        assertEquals("CLOSED", scalar("SELECT state FROM publications WHERE id=?", publication));
        var attempts = new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core);
        var review = attempts.review(new StudentAttemptStore.Actor(f.student, teacher.expiresAt()), attempt)
                .value();
        assertNotNull(review);
        assertEquals(4, review.questions().size());
        assertEquals(1, store.results(teacher, publication, 20, null).items().size());
    }

    @Test
    void noConfirmationMeansNoCutoffAndNotReady() {
        var request = request();
        published(request);
        advance(400000);
        assertEquals(
                AttemptFinalization.Kind.NOT_DUE,
                f.core.noticeFinalize(attempt, request).kind());
        failure("CLOSURE_NOT_READY", () -> service.command(teacher, command("COMPLETE", request, 2)));
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
    }

    @Test
    void ordinaryCloseActiveConflictThenEarlyResolutionAfterManualSubmit() {
        var request = request();
        confirmed(request);
        failure("ACTIVE_ATTEMPTS", () -> service.command(teacher, command("CLOSE", null, 2)));
        f.core.submit(f.manual(attempt, 0, null));
        assertNull(store.get(teacher, publication, request).finalizationStartedAt());
        var close = command("CLOSE", null, 2);
        assertEquals(200, service.command(teacher, close).status());
        assertEquals(200, service.command(teacher, close).status());
        assertEquals("RESOLVED_EARLY", store.get(teacher, publication, request).state());
        worker.process(work(request, "NOTICE_CONFIRMED"));
        assertEquals("MANUAL_SUBMIT", scalar("SELECT terminal_reason FROM attempts WHERE id=?", attempt));
    }

    @Test
    void fullCaptureAndPendingCreationFreeze() {
        var seed = new AtomicFinalizationIntegrationTest.Seed(publication, null);
        f.student = UUID.randomUUID();
        f.active(seed, 0);
        var request = request();
        assertEquals("2", scalar("SELECT count(*) FROM closure_request_members WHERE closure_request_id=?", request));
        failure("CLOSURE_PENDING", () -> new JdbcStudentAttemptStore(
                        f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core)
                .start(
                        new StudentAttemptStore.Actor(UUID.randomUUID(), teacher.expiresAt()),
                        publication,
                        "START",
                        scalar("SELECT private_locator FROM publications WHERE id=?", publication),
                        JdbcPublicationStore.digest("new")));
        assertThrows(
                RuntimeException.class,
                () -> f.db.update(
                        "UPDATE publications SET end_at=end_at+interval '1 hour',configuration=jsonb_set(configuration,'{endAt}',to_jsonb(end_at+interval '1 hour')),revision=revision+1 WHERE id=?",
                        publication));
    }

    @Test
    void partialCaptureRollsBack() {
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(f.manager).execute(s -> {
            f.db.update(
                    "INSERT INTO closure_requests(id,publication_id,requesting_teacher_id,requested_at,state) VALUES(?,?,?,assessment_time(),'PENDING')",
                    UUID.randomUUID(),
                    publication,
                    teacher.id());
            return null;
        }));
        assertEquals("0", scalar("SELECT count(*) FROM closure_requests"));
    }

    @Test
    void closedWithoutTeacherReceiptOrWithActiveAttemptRejected() {
        assertThrows(
                RuntimeException.class,
                () -> f.db.update(
                        "UPDATE publications SET state='CLOSED',closed_at=assessment_time(),revision=revision+1 WHERE id=?",
                        publication));
        assertEquals("OPEN", scalar("SELECT state FROM publications WHERE id=?", publication));
    }

    @Test
    void immutableNoticeAndCutoffRejectRawDml() {
        var request = request();
        confirmed(request);
        assertThrows(
                RuntimeException.class,
                () -> f.db.update("UPDATE assessment_notifications SET recipient_id=?", UUID.randomUUID()));
        assertThrows(
                RuntimeException.class,
                () -> f.db.update(
                        "UPDATE closure_request_members SET closure_cutoff_at=closure_cutoff_at-interval '1 second'"));
        assertThrows(
                RuntimeException.class,
                () -> f.db.update(
                        "UPDATE closure_request_members SET confirmed_notice_at=NULL,closure_cutoff_at=NULL,recipient_checked_at=NULL,accessibility_version=NULL"));
    }

    @Test
    void inboxCursorIsActorBoundAndTamperRejected() {
        var request = request();
        confirmed(request);
        var notice = store.published(work(request, "NOTICE_PUBLISHED"));
        service.command(teacher, command("CANCEL", request, 2));
        var second = (ClosureViews.Closure)
                service.command(teacher, command("REQUEST", null, 3)).body();
        confirmed(second.closureRequestId());
        var page = store.inbox(f.student, 1, null);
        assertNotNull(page.nextCursor());
        assertEquals(
                notice.notificationId(),
                store.inbox(f.student, 1, page.nextCursor()).items().getFirst().notificationId());
        failure("INVALID_REQUEST", () -> store.inbox(UUID.randomUUID(), 1, page.nextCursor()));
        failure("INVALID_REQUEST", () -> store.inbox(f.student, 1, page.nextCursor() + "a"));
    }

    @Test
    void duplicateWorkersPublishAndConfirmOnce() throws Exception {
        var request = request();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 2; i++)
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await();
                        new ClosureWorker(
                                        new JdbcClosureStore(
                                                f.db,
                                                f.manager,
                                                AssessmentTransactionProperties.defaults(),
                                                new ClosureCursor(properties)),
                                        dependencies,
                                        new JdbcAttemptFinalization(f.db, f.manager),
                                        properties,
                                        new SimpleMeterRegistry())
                                .process(work(request, "NOTICE_PENDING"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        assertEquals("1", scalar("SELECT count(*) FROM assessment_notifications"));
        worker.process(work(request, "NOTICE_PUBLISHED"));
        worker.process(work(request, "NOTICE_PUBLISHED"));
        assertNotNull(store.published(work(request, "NOTICE_PUBLISHED")).confirmedNoticeAt());
    }

    @Test
    void fiftyUnitBudgetIsDurableAcrossCrashAnd202Replay() {
        var seed = new AtomicFinalizationIntegrationTest.Seed(publication, null);
        for (int i = 0; i < 50; i++) {
            f.student = UUID.randomUUID();
            f.active(seed, 0);
        }
        long start = System.nanoTime();
        var request = request();
        long capture = System.nanoTime() - start;
        for (var candidate : store.candidates(100)) worker.process(candidate);
        for (var candidate : store.candidates(100)) worker.process(candidate);
        assertEquals("51", scalar("SELECT count(*) FROM closure_request_members WHERE state='NOTICE_CONFIRMED'"));
        advance(86401 + 172801);
        var command = command("COMPLETE", request, 2);
        store.reserve(teacher, command, 50);
        // Simulate process loss after one committed unit, without a completion acknowledgment.
        var due = store.due(publication, request).orElseThrow();
        f.core.completeUnit(
                new AttemptFinalization.CompletionUnit(publication, request, teacher.id(), command.key(), due));
        assertEquals(
                "1", scalar("SELECT processed_units FROM publication_operations WHERE key_digest=?", command.key()));
        service = new ClosureService(
                new JdbcClosureStore(
                        f.db, f.manager, AssessmentTransactionProperties.defaults(), new ClosureCursor(properties)),
                dependencies,
                new JdbcAttemptFinalization(f.db, f.manager),
                properties);
        start = System.nanoTime();
        var reply = service.command(teacher, command);
        long batch = System.nanoTime() - start;
        assertEquals(202, reply.status());
        assertEquals(
                "50", scalar("SELECT processed_units FROM publication_operations WHERE key_digest=?", command.key()));
        assertEquals("50", scalar("SELECT count(*) FROM grading_results"));
        assertEquals(202, service.command(teacher, command).status());
        assertEquals("50", scalar("SELECT count(*) FROM grading_results"));
        assertEquals(
                200, service.command(teacher, command("COMPLETE", request, 2)).status());
        assertEquals("51", scalar("SELECT count(*) FROM grading_results"));
        System.out.printf(
                "B3 measured capture_members=51 capture_ms=%.3f remaining_49_finalizations_ms=%.3f%n",
                capture / 1e6, batch / 1e6);
    }

    @Test
    void firstFinalizationRollbackDoesNotLeaveFenceOrPartialResult() {
        var request = request();
        confirmed(request);
        advance(86401 + 172801);
        f.db.execute(
                "CREATE FUNCTION injected_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture failure'; END $$");
        f.db.execute(
                "CREATE TRIGGER injected_failure BEFORE INSERT ON grading_results FOR EACH ROW EXECUTE FUNCTION injected_failure()");
        failure("PERSISTENCE_UNAVAILABLE", () -> f.core.noticeFinalize(attempt, request));
        assertNull(store.get(teacher, publication, request).finalizationStartedAt());
        assertEquals("0", scalar("SELECT count(*) FROM grading_results"));
        f.db.execute("DROP TRIGGER injected_failure ON grading_results");
        assertEquals(
                200, service.command(teacher, command("CANCEL", request, 2)).status());
    }

    @Test
    void cutoffStudentMutationsCommitSystemGradeThenConflictAndMalformedInputDoesNotGrade() {
        var request = request();
        confirmed(request);
        advance(86401 + 172801);
        PublicationSources sources = org.mockito.Mockito.mock(PublicationSources.class);
        var attempts = new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core);
        var studentService = new StudentAttemptService(attempts, sources, f.core);
        var actor = new StudentAttemptStore.Actor(f.student, teacher.expiresAt());
        failure(
                "INVALID_REQUEST",
                () -> studentService.save(
                        actor,
                        attempt,
                        4,
                        0,
                        JdbcPublicationStore.digest("invalid"),
                        "{\"type\":\"NUMERIC_FILL\",\"value\":\"bad\"}",
                        "evidence"));
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
        failure(
                "CLOSURE_CUTOFF_REACHED",
                () -> studentService.abandon(actor, attempt, 0, JdbcPublicationStore.digest("abandon"), "evidence"));
        assertEquals("TEACHER_NOTICE_CUTOFF", scalar("SELECT terminal_reason FROM attempts WHERE id=?", attempt));
        assertEquals("0", scalar("SELECT count(*) FROM attempt_operations WHERE action='ABANDON'"));
    }

    @Test
    void cancelVersusCutoffFinalizerCommitsOneCoherentWinner() throws Exception {
        var request = request();
        confirmed(request);
        advance(86401 + 172801);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancel = pool.submit(() -> {
                barrier.await();
                try {
                    return service.command(teacher, command("CANCEL", request, 2))
                            .status();
                } catch (PublicationFailure e) {
                    return e.status();
                }
            });
            var finalize = pool.submit(() -> {
                barrier.await();
                return f.core.noticeFinalize(attempt, request).kind();
            });
            var status = cancel.get(20, TimeUnit.SECONDS);
            var outcome = finalize.get(20, TimeUnit.SECONDS);
            if (status == 200) {
                assertTrue(Set.of(AttemptFinalization.Kind.NOT_DUE, AttemptFinalization.Kind.NOT_APPLICABLE)
                        .contains(outcome));
                assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
            } else {
                assertEquals(409, status);
                assertEquals(AttemptFinalization.Kind.SUBMITTED, outcome);
                assertNotNull(store.get(teacher, publication, request).finalizationStartedAt());
            }
        }
    }

    @Test
    void completeVersusCompleteSharesOneBudgetAndOneResult() throws Exception {
        var request = request();
        confirmed(request);
        advance(86401 + 172801);
        var command = command("COMPLETE", request, 2);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                barrier.await();
                return service.command(teacher, command).status();
            });
            var two = pool.submit(() -> {
                barrier.await();
                return service.command(teacher, command).status();
            });
            assertEquals(200, one.get(20, TimeUnit.SECONDS));
            assertEquals(200, two.get(20, TimeUnit.SECONDS));
        }
        assertEquals("1", scalar("SELECT count(*) FROM grading_results"));
        assertEquals(
                "1", scalar("SELECT processed_units FROM publication_operations WHERE key_digest=?", command.key()));
    }

    @Test
    void wrongTeacherCannotReconcileOrListResults() {
        var other = new Teacher(UUID.randomUUID(), teacher.expiresAt(), "fixture");
        failure("PUBLICATION_NOT_FOUND", () -> service.command(other, command("CLOSE", null, 1)));
        failure("PUBLICATION_NOT_FOUND", () -> store.results(other, publication, 20, null));
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
    }

    @Test
    void closureCutoffStrictlyBeforeAndExactlyAtAuthoritativeTime() {
        var request = request();
        confirmed(request);
        var cutoff = store.published(work(request, "NOTICE_PUBLISHED")).closureCutoffAt();
        f.db.execute(
                "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE SET search_path=pg_catalog,pg_temp AS $$ SELECT '"
                        + cutoff.minusNanos(1000) + "'::timestamptz $$");
        assertEquals(
                AttemptFinalization.Kind.NOT_DUE,
                f.core.noticeFinalize(attempt, request).kind());
        f.db.execute(
                "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE SET search_path=pg_catalog,pg_temp AS $$ SELECT '"
                        + cutoff + "'::timestamptz $$");
        assertEquals(
                AttemptFinalization.Kind.SUBMITTED,
                f.core.noticeFinalize(attempt, request).kind());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SAVE", "SUBMIT", "ABANDON"})
    void studentMutationVersusCutoffFinalizationKeepsSavedAnswersAndOneResult(String action) throws Exception {
        new TransactionTemplate(f.manager).execute(t -> {
            f.saved(attempt, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", 1);
            return null;
        });
        var request = request();
        confirmed(request);
        advance(86401 + 172801);
        var barrier = new CyclicBarrier(2);
        var attempts = new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core);
        var students = new StudentAttemptService(attempts, org.mockito.Mockito.mock(PublicationSources.class), f.core);
        var actor = new StudentAttemptStore.Actor(f.student, teacher.expiresAt());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var mutation = pool.submit(() -> {
                barrier.await();
                try {
                    switch (action) {
                        case "SAVE" ->
                            students.save(
                                    actor,
                                    attempt,
                                    4,
                                    1,
                                    JdbcPublicationStore.digest(action),
                                    "{\"type\":\"NUMERIC_FILL\",\"value\":\"0000\"}",
                                    "fixture");
                        case "SUBMIT" ->
                            students.submit(
                                    actor,
                                    attempt,
                                    1,
                                    JdbcPublicationStore.digest(action),
                                    GradingDocumentsTest.replacement(),
                                    "fixture");
                        case "ABANDON" ->
                            students.abandon(actor, attempt, 1, JdbcPublicationStore.digest(action), "fixture");
                        default -> throw new IllegalArgumentException();
                    }
                    return "UNEXPECTED_SUCCESS";
                } catch (PublicationFailure e) {
                    return e.code();
                }
            });
            var finalization = pool.submit(() -> {
                barrier.await();
                return f.core.noticeFinalize(attempt, request);
            });
            assertEquals("CLOSURE_CUTOFF_REACHED", mutation.get(20, TimeUnit.SECONDS));
            finalization.get(20, TimeUnit.SECONDS);
        }
        assertEquals("1", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", attempt));
        assertEquals("1", scalar("SELECT earned_weight FROM grading_results WHERE attempt_id=?", attempt));
        assertEquals(
                "0001",
                scalar(
                        "SELECT answer->>'value' FROM attempt_answers WHERE attempt_id=? AND question_number=4",
                        attempt));
    }

    @Test
    void rejectedRecipientAndInboxReadinessNeverStartCountdownAndRetriesAreDurable() {
        var request = request();
        published(request);
        var metrics = new SimpleMeterRegistry();
        ClosureDependencies denied = new ClosureDependencies() {
            public void requireInbox() {}

            public Eligibility eligible(UUID id) {
                return new Eligibility(id, false, now());
            }

            public void teacher(Teacher actor) {}
        };
        new ClosureWorker(store, denied, f.core, properties, metrics).process(work(request, "NOTICE_PUBLISHED"));
        assertNull(store.published(work(request, "NOTICE_PUBLISHED")).closureCutoffAt());
        assertEquals("1", scalar("SELECT retry_count FROM closure_request_members WHERE attempt_id=?", attempt));
        assertEquals("INELIGIBLE", scalar("SELECT outcome FROM closure_work_audit ORDER BY id DESC LIMIT 1"));
        failure(
                "DEPENDENCY_UNAVAILABLE",
                () -> store.confirm(
                        work(request, "NOTICE_PUBLISHED"),
                        new ClosureDependencies.Eligibility(f.student, false, now())));
        failure(
                "DEPENDENCY_UNAVAILABLE",
                () -> store.confirm(
                        work(request, "NOTICE_PUBLISHED"),
                        new ClosureDependencies.Eligibility(f.student, true, now(), System.nanoTime() - 1)));
        assertNull(store.published(work(request, "NOTICE_PUBLISHED")).confirmedNoticeAt());
    }

    @Test
    void authoritativeCutoffStillEnforcedWithOptionalWorkerDisabled() {
        var request = request();
        confirmed(request);
        advance(86401 + 172801);
        var off = new ClosureProperties(true, false, true, true, 50, 50, "v1", properties.cursorKeys());
        new ClosureWorker(
                        store,
                        org.mockito.Mockito.mock(ClosureDependencies.class),
                        f.core,
                        off,
                        new SimpleMeterRegistry())
                .tick();
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
        var students = new StudentAttemptService(
                new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core),
                org.mockito.Mockito.mock(PublicationSources.class),
                f.core);
        failure(
                "CLOSURE_CUTOFF_REACHED",
                () -> students.abandon(
                        new StudentAttemptStore.Actor(f.student, teacher.expiresAt()),
                        attempt,
                        0,
                        JdbcPublicationStore.digest("cutoff"),
                        "fixture"));
        assertEquals("SUBMITTED", scalar("SELECT state FROM attempts WHERE id=?", attempt));
    }

    @Test
    void requestAndScheduleExtensionSerializeWithoutPartialWorkflow() throws Exception {
        var barrier = new CyclicBarrier(2);
        Instant end;
        // Read via JDBC avoids locale/string ambiguity of timestamptz display.
        end = f.db.queryForObject(
                "SELECT end_at FROM publications WHERE id=?",
                (r, n) -> r.getTimestamp(1).toInstant(),
                publication);
        final Instant extension = end.plusSeconds(3600);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var request = pool.submit(() -> {
                barrier.await();
                try {
                    return service.command(teacher, command("REQUEST", null, 1)).status();
                } catch (PublicationFailure e) {
                    return e.status();
                }
            });
            var schedule = pool.submit(() -> {
                barrier.await();
                try {
                    new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC())
                            .extend(teacher.id(), publication, 1, extension, 0);
                    return "EXTENDED";
                } catch (PublicationFailure e) {
                    return e.code();
                }
            });
            int outcome = request.get(20, TimeUnit.SECONDS);
            String changed = schedule.get(20, TimeUnit.SECONDS);
            if (outcome == 201) {
                assertEquals("CLOSURE_PENDING", changed);
                assertEquals("1", scalar("SELECT count(*) FROM closure_request_members"));
            } else {
                assertEquals(409, outcome);
                assertEquals("EXTENDED", changed);
                assertEquals("0", scalar("SELECT count(*) FROM closure_requests"));
            }
        }
    }

    @Test
    void requestAndStartSerializeAndNeverOmitACommittedEligibleAttempt() throws Exception {
        var barrier = new CyclicBarrier(2);
        var students = new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core);
        String locator = scalar("SELECT private_locator FROM publications WHERE id=?", publication);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var request = pool.submit(() -> {
                barrier.await();
                return service.command(teacher, command("REQUEST", null, 1));
            });
            var start = pool.submit(() -> {
                barrier.await();
                try {
                    students.start(
                            new StudentAttemptStore.Actor(UUID.randomUUID(), teacher.expiresAt()),
                            publication,
                            "START",
                            locator,
                            JdbcPublicationStore.digest("racing-start"));
                    return "UNEXPECTED_CREATED";
                } catch (PublicationFailure e) {
                    return e.code();
                }
            });
            assertEquals(201, request.get(20, TimeUnit.SECONDS).status());
            assertTrue(Set.of("CLOSURE_PENDING", "WINDOW_ENDED").contains(start.get(20, TimeUnit.SECONDS)));
        }
        assertEquals("1", scalar("SELECT count(*) FROM closure_request_members"));
        assertEquals("1", scalar("SELECT count(*) FROM attempts"));
    }

    @Test
    void cancelAgainstPublishOrConfirmCannotLeaveActionableEvidence() throws Exception {
        var request = request();
        published(request);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancel = pool.submit(() -> {
                barrier.await();
                return service.command(teacher, command("CANCEL", request, 2));
            });
            var confirm = pool.submit(() -> {
                barrier.await();
                worker.process(work(request, "NOTICE_PUBLISHED"));
                return true;
            });
            assertEquals(200, cancel.get(20, TimeUnit.SECONDS).status());
            confirm.get(20, TimeUnit.SECONDS);
        }
        advance(400000);
        assertEquals(
                AttemptFinalization.Kind.NOT_DUE,
                f.core.noticeFinalize(attempt, request).kind());
        assertEquals(
                "CANCELLED", store.published(work(request, "NOTICE_PUBLISHED")).noticeStatus());
    }

    @Test
    void concurrentWorkerAndCompleteCannotDuplicateResultOrCloseWithoutTeacher() throws Exception {
        var request = request();
        confirmed(request);
        advance(86401 + 172801);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var complete = pool.submit(() -> {
                barrier.await();
                return service.command(teacher, command("COMPLETE", request, 2));
            });
            var recover = pool.submit(() -> {
                barrier.await();
                worker.process(work(request, "NOTICE_CONFIRMED"));
                return true;
            });
            assertEquals(200, complete.get(20, TimeUnit.SECONDS).status());
            recover.get(20, TimeUnit.SECONDS);
        }
        assertEquals("1", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", attempt));
        assertEquals("1", scalar("SELECT count(*) FROM attempt_finalization_audit WHERE attempt_id=?", attempt));
        assertEquals("CLOSED", scalar("SELECT state FROM publications WHERE id=?", publication));
    }

    @Test
    void ordinaryCloseVersusBoundedTimeoutCommitsOneResult() throws Exception {
        advance(0);
        var seed = f.publication(5);
        var bounded = f.attempt(seed, 5, f.now().minusSeconds(600));
        UUID pub = seed.publication();
        var owner = new Teacher(
                UUID.fromString(scalar("SELECT owner_id FROM publications WHERE id=?", pub)),
                teacher.expiresAt(),
                "fixture");
        var command = new Command(pub, null, 1, JdbcPublicationStore.digest("bounded-close"), "CLOSE");
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var close = pool.submit(() -> {
                barrier.await();
                return service.command(owner, command);
            });
            var timeout = pool.submit(() -> {
                barrier.await();
                return f.core.systemFinalize(
                        bounded, com.quizopia.assessment.domain.attempt.AttemptRecord.Reason.BOUNDED_TIMEOUT);
            });
            assertEquals(200, close.get(20, TimeUnit.SECONDS).status());
            timeout.get(20, TimeUnit.SECONDS);
        }
        assertEquals("1", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", bounded));
        assertEquals("BOUNDED_TIMEOUT", scalar("SELECT terminal_reason FROM attempts WHERE id=?", bounded));
        assertEquals("CLOSED", scalar("SELECT state FROM publications WHERE id=?", pub));
    }

    @Test
    void pendingAllowsExistingStartSelectionButBlocksRetakeAfterManualSubmit() {
        var request = request();
        var students = new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core);
        var actor = new StudentAttemptStore.Actor(f.student, teacher.expiresAt());
        String locator = scalar("SELECT private_locator FROM publications WHERE id=?", publication);
        assertEquals(
                attempt,
                students.start(actor, publication, "START", locator, JdbcPublicationStore.digest("select-existing"))
                        .value()
                        .attemptId());
        f.core.submit(f.manual(attempt, 0, null));
        failure(
                "CLOSURE_PENDING",
                () -> students.start(
                        actor, publication, "RETAKE", locator, JdbcPublicationStore.digest("retake-pending")));
        assertEquals("1", scalar("SELECT count(*) FROM closure_request_members WHERE closure_request_id=?", request));
    }

    @Test
    void requestBeforeParticipationEndIsMutationFree() {
        advance(0);
        failure("CLOSURE_NOT_READY", () -> service.command(teacher, command("REQUEST", null, 1)));
        assertEquals("0", scalar("SELECT count(*) FROM closure_requests"));
        assertEquals("0", scalar("SELECT count(*) FROM publication_operations"));
    }

    @Test
    void backdatedRawAnswerAndStudentTerminalizationCannotBypassCutoff() {
        new TransactionTemplate(f.manager).execute(t -> {
            f.saved(attempt, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", 1);
            return null;
        });
        var request = request();
        confirmed(request);
        advance(86401 + 172801);
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(f.manager).execute(t -> {
            f.db.update(
                    "UPDATE attempt_answers SET answer='{\"type\":\"NUMERIC_FILL\",\"value\":\"0000\"}',saved_revision=2 WHERE attempt_id=? AND question_number=4",
                    attempt);
            f.db.update("UPDATE attempts SET revision=2 WHERE id=?", attempt);
            return null;
        }));
        assertThrows(
                RuntimeException.class,
                () -> f.db.update(
                        "UPDATE attempts SET state='ABANDONED',terminal_reason='STUDENT_ABANDON',revision=2,finalized_at=started_at+interval '1 second' WHERE id=?",
                        attempt));
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
        assertEquals(
                "0001",
                scalar(
                        "SELECT answer->>'value' FROM attempt_answers WHERE attempt_id=? AND question_number=4",
                        attempt));
    }

    @Test
    void crossingCutoffInsideSaveRollsBackThenCommitsSystemReconciliation() throws Exception {
        new TransactionTemplate(f.manager).execute(t -> {
            f.saved(attempt, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", 1);
            return null;
        });
        var request = request();
        confirmed(request);
        var cutoff = store.published(work(request, "NOTICE_PUBLISHED")).closureCutoffAt();
        f.db.execute(
                "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE SET search_path=pg_catalog,pg_temp AS $$ SELECT '"
                        + cutoff.minusNanos(1000) + "'::timestamptz $$");
        f.db.execute(
                "CREATE FUNCTION pause_answer_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(790031);RETURN NEW;END $$");
        f.db.execute(
                "CREATE TRIGGER a_pause_answer_fixture BEFORE UPDATE ON attempt_answers FOR EACH ROW EXECUTE FUNCTION pause_answer_fixture()");
        var ds = f.source("boundary");
        var db = new org.springframework.jdbc.core.JdbcTemplate(ds);
        var manager = new org.springframework.jdbc.support.JdbcTransactionManager(ds);
        var students = new StudentAttemptService(
                new JdbcStudentAttemptStore(db, manager, AssessmentTransactionProperties.defaults(), f.core),
                org.mockito.Mockito.mock(PublicationSources.class),
                f.core);
        try (var holder = f.source("holder").getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("SELECT pg_advisory_xact_lock(790031)");
            var mutation = pool.submit(() -> {
                try {
                    students.save(
                            new StudentAttemptStore.Actor(f.student, teacher.expiresAt()),
                            attempt,
                            4,
                            1,
                            JdbcPublicationStore.digest("boundary-save"),
                            "{\"type\":\"NUMERIC_FILL\",\"value\":\"0000\"}",
                            "fixture");
                    return "UNEXPECTED_SUCCESS";
                } catch (PublicationFailure e) {
                    return e.code();
                }
            });
            f.waitLock("boundary");
            f.db.execute(
                    "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql VOLATILE SET search_path=pg_catalog,pg_temp AS $$ SELECT '"
                            + cutoff + "'::timestamptz $$");
            holder.commit();
            assertEquals("CLOSURE_CUTOFF_REACHED", mutation.get(20, TimeUnit.SECONDS));
        }
        assertEquals("TEACHER_NOTICE_CUTOFF", scalar("SELECT terminal_reason FROM attempts WHERE id=?", attempt));
        assertEquals("1", scalar("SELECT earned_weight FROM grading_results WHERE attempt_id=?", attempt));
        assertEquals(
                "0", scalar("SELECT count(*) FROM attempt_operations WHERE attempt_id=? AND action='SAVE'", attempt));
    }

    @Test
    void requestDoesNotOmitTrustedIneligibleRecipients() {
        ClosureDependencies deps = new ClosureDependencies() {
            public void requireInbox() {}

            public Eligibility eligible(UUID id) {
                return new Eligibility(id, false, now());
            }

            public void teacher(Teacher actor) {}
        };
        var guarded = new ClosureService(store, deps, f.core, properties);
        var reply = guarded.command(teacher, command("REQUEST", null, 1));
        assertEquals(201, reply.status());
        assertEquals("1", scalar("SELECT count(*) FROM closure_request_members"));
        assertEquals("0", scalar("SELECT count(*) FROM closure_request_members WHERE confirmed_notice_at IS NOT NULL"));
    }

    @Test
    void requestCapabilityOutageIsMutationFree() {
        ClosureDependencies deps = new ClosureDependencies() {
            public void requireInbox() {}

            public Eligibility eligible(UUID id) {
                throw new PublicationFailure(503, "DEPENDENCY_UNAVAILABLE");
            }

            public void teacher(Teacher actor) {}
        };
        var guarded = new ClosureService(store, deps, f.core, properties);
        failure("DEPENDENCY_UNAVAILABLE", () -> guarded.command(teacher, command("REQUEST", null, 1)));
        assertEquals("0", scalar("SELECT count(*) FROM closure_requests"));
        assertEquals("0", scalar("SELECT count(*) FROM publication_operations"));
        assertEquals("1", scalar("SELECT revision FROM publications WHERE id=?", publication));
    }

    @Test
    void cutoffFinalizationSafelyNormalizesLegacySelectionOrder() {
        new TransactionTemplate(f.manager).execute(t -> {
            f.saved(attempt, 2, "{\"type\":\"MULTIPLE_CHOICE\",\"optionLabels\":[\"C\",\"A\"]}", 1);
            return null;
        });
        var request = request();
        confirmed(request);
        advance(86401 + 172801);
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(f.manager).execute(t -> {
            f.db.update(
                    "UPDATE attempt_answers SET answer='{\"type\":\"MULTIPLE_CHOICE\",\"optionLabels\":[\"A\",\"C\"]}',saved_revision=2 WHERE attempt_id=? AND question_number=2",
                    attempt);
            f.db.update("UPDATE attempts SET revision=2 WHERE id=?", attempt);
            return null;
        }));
        var result = f.core.noticeFinalize(attempt, request);
        assertEquals(AttemptFinalization.Kind.SUBMITTED, result.kind());
        assertEquals("1", result.result().orElseThrow().earnedWeight());
        assertEquals(
                "[\"A\", \"C\"]",
                scalar(
                        "SELECT answer->'optionLabels' FROM attempt_answers WHERE attempt_id=? AND question_number=2",
                        attempt));
    }

    @Test
    void teacherResultsRequireOwnershipWithoutAnExtraOpenStateRestriction() {
        var draft = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC())
                .create(teacher.id(), UUID.randomUUID().toString(), SnapshotFixtures.configuration());
        assertTrue(store.results(teacher, draft.id(), 20, null).items().isEmpty());
        failure(
                "PUBLICATION_CONFLICT",
                () -> service.command(
                        teacher,
                        new Command(draft.id(), null, 0, JdbcPublicationStore.digest("draft-close"), "CLOSE")));
    }
}
