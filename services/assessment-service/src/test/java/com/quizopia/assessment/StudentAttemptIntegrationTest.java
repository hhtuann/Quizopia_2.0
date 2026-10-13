package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.configuration.AssessmentTransactionProperties;
import com.quizopia.assessment.domain.*;
import com.quizopia.assessment.domain.attempt.AttemptRecord.Reason;
import com.quizopia.assessment.persistence.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** All local production adapters and the real C1 root transaction, on PostgreSQL 17. */
@Testcontainers
class StudentAttemptIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AtomicFinalizationIntegrationTest f;
    StudentAttemptStore store;
    StudentAttemptService service;
    PublicationSources sources;
    StudentAttemptStore.Actor actor;

    @BeforeEach
    void setup() {
        f = new AtomicFinalizationIntegrationTest();
        f.setup();
        actor = new StudentAttemptStore.Actor(f.student, f.now().plusSeconds(3600));
        store = new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core);
        sources = mock(PublicationSources.class);
        service = new StudentAttemptService(store, sources, f.core);
    }

    String key() {
        return JdbcPublicationStore.digest(UUID.randomUUID().toString());
    }

    String locator(UUID p) {
        return f.scalar("SELECT private_locator FROM publications WHERE id=?", p);
    }

    StudentAttemptStore.Step<AttemptViews.View> start(UUID p, String action, String key) {
        return service.start(actor, p, action, locator(p), key, "evidence");
    }

    AttemptViews.View first(UUID p) {
        return start(p, "START", key()).value();
    }

    void failure(String code, Runnable r) {
        f.failure(code, r);
    }

    String scalar(String sql, Object... args) {
        return f.scalar(sql, args);
    }

    @Test
    void firstStartResumeAndOriginalSelectionReplayAfterRetake() {
        var p = f.publication(0).publication();
        String k = key();
        var created = start(p, "START", k);
        assertTrue(created.created());
        var a = created.value();
        assertEquals("1", a.ordinal());
        assertNull(a.deadlineAt());
        assertEquals(
                a.attemptId(), service.resume(actor, a.attemptId(), "evidence").attemptId());
        var replay = start(p, "START", k);
        assertFalse(replay.created());
        assertEquals(a.attemptId(), replay.value().attemptId());
        String selection = key();
        assertEquals(a.attemptId(), start(p, "START", selection).value().attemptId());
        service.abandon(actor, a.attemptId(), 0, key(), "evidence");
        assertEquals("ABANDONED", first(p).state());
        var b = start(p, "RETAKE", key());
        assertTrue(b.created());
        assertEquals("2", b.value().ordinal());
        assertEquals(a.attemptId(), start(p, "START", k).value().attemptId());
        assertEquals(a.attemptId(), start(p, "START", selection).value().attemptId());
        assertEquals("2", scalar("SELECT count(*) FROM attempts WHERE publication_id=?", p));
    }

    @Test
    void startKeysShareRetakeNamespaceAndLocatorIsAlwaysValidated() {
        var p = f.publication(0).publication();
        String k = key();
        first(p);
        failure("ACTIVE_ATTEMPT_EXISTS", () -> start(p, "RETAKE", key()));
        var a = start(p, "START", k).value();
        service.abandon(actor, a.attemptId(), 0, key(), "evidence");
        failure("IDEMPOTENCY_CONFLICT", () -> start(p, "RETAKE", k));
        failure("INVALID_REQUEST", () -> service.start(actor, p, "START", null, key(), "evidence"));
        failure("PUBLICATION_NOT_FOUND", () -> service.start(actor, p, "START", "z".repeat(43), k, "evidence"));
        failure("RETAKE_NOT_AVAILABLE", () -> start(f.publication(0).publication(), "RETAKE", key()));
    }

    @Test
    void everyAnswerTypeCanonicalReplayAndSharedActionConflict() {
        var a = first(f.publication(0).publication());
        String[] answers = {
            "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"B\"}",
            "{\"type\":\"MULTIPLE_CHOICE\",\"optionLabels\":[\"C\",\"A\"]}",
            "{\"type\":\"TRUE_FALSE_MATRIX\",\"judgments\":{\"A\":false,\"B\":false,\"C\":false,\"D\":false}}",
            "{\"type\":\"NUMERIC_FILL\",\"value\":\" 0001 \"}"
        };
        for (int q = 1; q <= 4; q++) {
            int n = q;
            String k = key();
            var ack = service.save(actor, a.attemptId(), q, q - 1, k, answers[q - 1], "evidence");
            assertEquals(Integer.toString(q), ack.revision());
            assertEquals(ack, service.save(actor, a.attemptId(), q, q - 1, k, answers[q - 1], "evidence"));
            failure("IDEMPOTENCY_CONFLICT", () -> service.abandon(actor, a.attemptId(), n - 1, k, "evidence"));
        }
        var result = service.submit(actor, a.attemptId(), 4, key(), null, "evidence");
        assertEquals("4", result.earnedWeight());
        assertEquals("100.00", result.percentageDisplay());
        assertEquals("4", scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", a.attemptId()));
        assertEquals("1", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", a.attemptId()));
    }

    @Test
    void numericWireLimitPrecedesCanonicalTrimAndStaleRevision() {
        var a = first(f.publication(0).publication());
        String raw = "{\"type\":\"NUMERIC_FILL\",\"value\":\"" + " ".repeat(65) + "\"}";
        failure("INVALID_REQUEST", () -> service.save(actor, a.attemptId(), 4, 0, key(), raw, "evidence"));
        assertEquals("0", scalar("SELECT revision FROM attempts WHERE id=?", a.attemptId()));
        var empty = "{\"type\":\"NUMERIC_FILL\",\"value\":null}";
        service.save(actor, a.attemptId(), 4, 0, key(), empty, "evidence");
        failure(
                "REVISION_CONFLICT",
                () -> service.save(
                        actor,
                        a.attemptId(),
                        1,
                        0,
                        key(),
                        "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":null}",
                        "evidence"));
    }

    @Test
    void saveReplaySurvivesSubmitAndChangedPayloadCannotReplay() {
        var a = first(f.publication(0).publication());
        String k = key(), raw = "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}";
        var ack = service.save(actor, a.attemptId(), 4, 0, k, raw, "evidence");
        service.submit(actor, a.attemptId(), 1, key(), null, "evidence");
        assertEquals(ack, service.save(actor, a.attemptId(), 4, 0, k, raw, "evidence"));
        failure(
                "IDEMPOTENCY_CONFLICT",
                () -> service.save(actor, a.attemptId(), 4, 0, k, raw.replace("0001", "0000"), "evidence"));
        failure("ATTEMPT_TERMINAL", () -> service.save(actor, a.attemptId(), 4, 2, key(), raw, "evidence"));
    }

    @Test
    void abandonUngradedConsumesOrdinalAndRetakeReplay() {
        var p = f.publication(0).publication();
        var a = first(p);
        String k = key();
        var ack = service.abandon(actor, a.attemptId(), 0, k, "evidence");
        assertEquals(ack, service.abandon(actor, a.attemptId(), 0, k, "evidence"));
        assertEquals("0", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", a.attemptId()));
        failure("RESULT_NOT_AVAILABLE", () -> service.result(actor, a.attemptId(), "evidence"));
        String retake = key();
        var b = start(p, "RETAKE", retake);
        assertEquals("2", b.value().ordinal());
        assertEquals(b.value().attemptId(), start(p, "RETAKE", retake).value().attemptId());
        failure("ATTEMPT_TERMINAL", () -> service.abandon(actor, a.attemptId(), 1, key(), "evidence"));
    }

    @Test
    void submitExactResultLostResponseReplayAndBestSurvivesActiveOrAbandonedRetake() {
        var p = f.publication(0).publication();
        var a = first(p);
        String k = key();
        var result = service.submit(actor, a.attemptId(), 0, k, GradingDocumentsTest.replacement(), "evidence");
        assertEquals("3.75", result.earnedWeight());
        assertEquals(
                result, service.submit(actor, a.attemptId(), 0, k, GradingDocumentsTest.replacement(), "evidence"));
        failure("IDEMPOTENCY_CONFLICT", () -> service.submit(actor, a.attemptId(), 0, k, null, "evidence"));
        var b = start(p, "RETAKE", key()).value();
        assertEquals(result, service.aggregate(actor, p, "evidence").bestResult());
        service.abandon(actor, b.attemptId(), 0, key(), "evidence");
        assertEquals(result, service.aggregate(actor, p, "evidence").bestResult());
        failure("REVIEW_NOT_AVAILABLE", () -> service.review(actor, a.attemptId(), "evidence"));
    }

    @Test
    void allUnansweredAndNoResultOrAggregateBeforeOwnership() {
        var p = f.publication(0).publication();
        failure("PUBLICATION_NOT_FOUND", () -> service.aggregate(actor, p, "evidence"));
        var a = first(p);
        assertNull(service.aggregate(actor, p, "evidence").bestResult());
        failure("RESULT_NOT_AVAILABLE", () -> service.result(actor, a.attemptId(), "evidence"));
        var result = service.submit(actor, a.attemptId(), 0, key(), null, "evidence");
        assertEquals("0", result.earnedWeight());
        assertEquals("4", result.possibleWeight());
    }

    @ParameterizedTest
    @ValueSource(strings = {"save", "abandon", "submit", "resume", "result", "aggregate", "start", "retake"})
    void dueAttemptReconcilesInCommittedIndependentTransaction(String operation) {
        var p = f.publication(5);
        UUID id = f.attempt(p, 5, f.now().minusSeconds(301));
        Runnable work =
                switch (operation) {
                    case "save" ->
                        () -> service.save(
                                actor, id, 4, 0, key(), "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", "evidence");
                    case "abandon" -> () -> service.abandon(actor, id, 0, key(), "evidence");
                    case "submit" ->
                        () -> service.submit(actor, id, 0, key(), GradingDocumentsTest.replacement(), "evidence");
                    case "resume" -> () -> service.resume(actor, id, "evidence");
                    case "result" -> () -> service.result(actor, id, "evidence");
                    case "aggregate" -> () -> service.aggregate(actor, p.publication(), "evidence");
                    case "start" -> () -> start(p.publication(), "START", key());
                    default -> () -> start(p.publication(), "RETAKE", key());
                };
        if (Set.of("save", "abandon", "submit").contains(operation)) failure("ATTEMPT_DEADLINE_REACHED", work);
        else work.run();
        assertEquals("BOUNDED_TIMEOUT", scalar("SELECT terminal_reason FROM attempts WHERE id=?", id));
        assertEquals("0", scalar("SELECT earned_weight FROM grading_results WHERE attempt_id=?", id));
        assertEquals("1", scalar("SELECT count(*) FROM attempt_finalization_audit WHERE attempt_id=?", id));
    }

    @Test
    void invalidDueSaveOrSubmitNeverReconcilesAndUnlimitedNeverTimesOut() {
        var p = f.publication(5);
        UUID id = f.attempt(p, 5, f.now().minusSeconds(301));
        failure(
                "INVALID_REQUEST",
                () -> service.save(
                        actor, id, 4, 0, key(), "{\"type\":\"NUMERIC_FILL\",\"value\":\"bad\"}", "evidence"));
        failure("INVALID_REQUEST", () -> service.submit(actor, id, 0, key(), "[]", "evidence"));
        f.unfinalized(id);
        var unlimited = first(f.publication(0).publication());
        assertEquals(
                "ACTIVE",
                service.resume(actor, unlimited.attemptId(), "evidence").state());
    }

    @Test
    void ownerMaskingAndExpiryOnEveryReadReplayAndMutation() {
        var p = f.publication(0).publication();
        var a = first(p);
        var other = new StudentAttemptStore.Actor(UUID.randomUUID(), actor.expiresAt());
        failure("ATTEMPT_NOT_FOUND", () -> service.resume(other, a.attemptId(), "evidence"));
        failure("ATTEMPT_NOT_FOUND", () -> service.submit(other, a.attemptId(), 0, key(), null, "evidence"));
        var expired = new StudentAttemptStore.Actor(actor.id(), f.now().minusSeconds(1));
        failure("UNAUTHENTICATED", () -> service.resume(expired, a.attemptId(), "evidence"));
        failure("UNAUTHENTICATED", () -> service.submit(expired, a.attemptId(), 0, key(), null, "evidence"));
        f.unfinalized(a.attemptId());
    }

    @Test
    void classMembershipIsFreshOutsideLocalTransactions() {
        // Resource port fixture verifies orchestration; real authority is separately exercised in topology.
        var mockStore = mock(StudentAttemptStore.class);
        var cfg = f.publication(0).configuration();
        UUID classroom = UUID.randomUUID();
        var cls = new PublicationConfiguration(
                cfg.title(),
                cfg.description(),
                cfg.quizId(),
                cfg.quizVersionId(),
                1,
                PublicationConfiguration.Audience.CLASS,
                classroom,
                cfg.startAt(),
                cfg.endAt(),
                0,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                0);
        UUID id = UUID.randomUUID();
        when(mockStore.owned(actor, id)).thenReturn(new StudentAttemptStore.Resource(UUID.randomUUID(), cls));
        doThrow(new PublicationFailure(404, "CLASSROOM_NOT_ACCESSIBLE"))
                .when(sources)
                .member(classroom, actor.id(), "evidence");
        var s = new StudentAttemptService(mockStore, sources, f.core);
        failure("CLASSROOM_NOT_ACCESSIBLE", () -> s.resume(actor, id, "evidence"));
        verify(mockStore, never()).resume(any(), any());
    }

    List<Object> race(Supplier<?> a, Supplier<?> b) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            java.util.function.Function<Supplier<?>, Callable<Object>> task = work -> () -> {
                barrier.await();
                try {
                    return work.get();
                } catch (PublicationFailure e) {
                    return e.code();
                }
            };
            var x = pool.submit(task.apply(a));
            var y = pool.submit(task.apply(b));
            return List.of(x.get(25, TimeUnit.SECONDS), y.get(25, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentStartSameAndDifferentKeysSelectOneAttempt(boolean same) throws Exception {
        UUID p = f.publication(0).publication();
        String k = key(), second = same ? k : key();
        var results = race(() -> start(p, "START", k), () -> start(p, "START", second));
        assertTrue(results.stream().allMatch(StudentAttemptStore.Step.class::isInstance));
        assertEquals("1", scalar("SELECT count(*) FROM attempts WHERE publication_id=?", p));
        assertEquals(
                same ? "1" : "2", scalar("SELECT count(*) FROM attempt_creation_operations WHERE publication_id=?", p));
    }

    @Test
    void concurrentRetakeAndStartSerializeWithoutImplicitExtraAttempt() throws Exception {
        UUID p = f.publication(0).publication();
        var a = first(p);
        service.abandon(actor, a.attemptId(), 0, key(), "evidence");
        race(() -> start(p, "START", key()), () -> start(p, "RETAKE", key()));
        assertEquals("2", scalar("SELECT count(*) FROM attempts WHERE publication_id=?", p));
        assertEquals("1", scalar("SELECT count(*) FROM attempts WHERE publication_id=? AND state='ACTIVE'", p));
    }

    @Test
    void concurrentRetakesCannotCreateTwoActiveAttempts() throws Exception {
        UUID p = f.publication(0).publication();
        var a = first(p);
        service.abandon(actor, a.attemptId(), 0, key(), "evidence");
        var result = race(() -> start(p, "RETAKE", key()), () -> start(p, "RETAKE", key()));
        assertTrue(result.contains("ACTIVE_ATTEMPT_EXISTS"));
        assertEquals("2", scalar("SELECT count(*) FROM attempts WHERE publication_id=?", p));
    }

    @Test
    void concurrentQuestionSavesUseGlobalRevision() throws Exception {
        var a = first(f.publication(0).publication());
        String raw = "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}";
        var result = race(
                () -> service.save(actor, a.attemptId(), 4, 0, key(), raw, "evidence"),
                () -> service.save(
                        actor,
                        a.attemptId(),
                        1,
                        0,
                        key(),
                        "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"B\"}",
                        "evidence"));
        assertTrue(result.contains("REVISION_CONFLICT"));
        assertEquals("1", scalar("SELECT revision FROM attempts WHERE id=?", a.attemptId()));
        assertEquals("1", scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", a.attemptId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"save", "submit", "abandon", "timeout"})
    void submitRacesRemainTransactionallyCoherent(String opponent) throws Exception {
        var p = f.publication(opponent.equals("timeout") ? 5 : 0);
        UUID id = opponent.equals("timeout")
                ? f.attempt(p, 5, f.now().minusSeconds(301))
                : first(p.publication()).attemptId();
        String k = key();
        Supplier<?> second =
                switch (opponent) {
                    case "save" ->
                        () -> service.save(
                                actor, id, 4, 0, key(), "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", "evidence");
                    case "abandon" -> () -> service.abandon(actor, id, 0, key(), "evidence");
                    case "timeout" -> () -> f.core.systemFinalize(id, Reason.BOUNDED_TIMEOUT);
                    default -> () -> service.submit(actor, id, 0, k, null, "evidence");
                };
        race(() -> service.submit(actor, id, 0, k, null, "evidence"), second);
        var state = scalar("SELECT state FROM attempts WHERE id=?", id);
        if (state.equals("ACTIVE")) {
            assertEquals("save", opponent);
            assertEquals("1", scalar("SELECT revision FROM attempts WHERE id=?", id));
            service.submit(actor, id, 1, key(), null, "evidence");
            state = "SUBMITTED";
        }
        assertEquals("1", scalar("SELECT count(*) FROM attempt_finalization_audit WHERE attempt_id=?", id));
        assertEquals(
                state.equals("SUBMITTED") ? "1" : "0",
                scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", id));
        assertEquals(
                state.equals("SUBMITTED") ? "1" : "0",
                scalar("SELECT count(*) FROM submitted_answers WHERE attempt_id=?", id));
    }

    @Test
    void pureClosedReviewGuardRequiresAllThreeFacts() {
        assertDoesNotThrow(() -> JdbcStudentAttemptStore.requireReview("CLOSED", "SUBMITTED", true));
        for (String state : List.of("DRAFT", "OPEN"))
            failure("REVIEW_NOT_AVAILABLE", () -> JdbcStudentAttemptStore.requireReview(state, "SUBMITTED", true));
        failure("REVIEW_NOT_AVAILABLE", () -> JdbcStudentAttemptStore.requireReview("CLOSED", "ACTIVE", true));
        failure("REVIEW_NOT_AVAILABLE", () -> JdbcStudentAttemptStore.requireReview("CLOSED", "SUBMITTED", false));
    }

    UUID configured(int max, Instant start, Instant end) {
        var base = f.publication(0).configuration();
        var cfg = new PublicationConfiguration(
                base.title(),
                base.description(),
                base.quizId(),
                base.quizVersionId(),
                1,
                PublicationConfiguration.Audience.PUBLIC,
                null,
                start,
                end,
                0,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                max);
        var repository = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        var p = repository.create(owner, UUID.randomUUID().toString(), cfg);
        repository.open(
                owner,
                p.id(),
                0,
                new String(
                        SnapshotFixtures.snapshot(cfg, "private source marker"),
                        java.nio.charset.StandardCharsets.UTF_8));
        return p.id();
    }

    @Test
    void finiteEntitlementCountsAbandonAndRacingRetakesCannotOversubscribe() throws Exception {
        Instant time = f.now();
        UUID p = configured(2, time.minusSeconds(100), time.plusSeconds(100));
        var a = first(p);
        service.abandon(actor, a.attemptId(), 0, key(), "evidence");
        var result = race(() -> start(p, "RETAKE", key()), () -> start(p, "RETAKE", key()));
        assertEquals(
                1,
                result.stream()
                        .filter(StudentAttemptStore.Step.class::isInstance)
                        .count());
        var b = first(p);
        assertEquals("2", b.ordinal());
        service.abandon(actor, b.attemptId(), 0, key(), "evidence");
        failure("ATTEMPTS_EXHAUSTED", () -> start(p, "RETAKE", key()));
        assertEquals("2", scalar("SELECT count(*) FROM attempts WHERE publication_id=?", p));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void newStartRequiresWindowButExistingAttemptRemainsReadable(boolean before) {
        Instant time = f.now();
        UUID p = configured(
                1,
                before ? time.plusSeconds(60) : time.minusSeconds(120),
                before ? time.plusSeconds(120) : time.minusSeconds(60));
        failure(before ? "WINDOW_NOT_STARTED" : "WINDOW_ENDED", () -> first(p));
        assertEquals("0", scalar("SELECT count(*) FROM attempts WHERE publication_id=?", p));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deadline", "expiry"})
    void parentLockWaitRechecksAuthoritativeClock(String boundary) throws Exception {
        var p = f.publication(boundary.equals("deadline") ? 5 : 0);
        Instant cutoff = f.now().plusMillis(900);
        UUID id = boundary.equals("deadline")
                ? f.attempt(p, 5, cutoff.minusSeconds(300))
                : first(p.publication()).attemptId();
        var waitingActor = boundary.equals("expiry") ? new StudentAttemptStore.Actor(actor.id(), cutoff) : actor;
        var ds = f.source("student_wait");
        var db = new org.springframework.jdbc.core.JdbcTemplate(ds);
        var manager = new org.springframework.jdbc.support.JdbcTransactionManager(ds);
        var waiting = new JdbcStudentAttemptStore(
                db, manager, AssessmentTransactionProperties.defaults(), new JdbcAttemptFinalization(db, manager));
        try (var c = f.source.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            c.setAutoCommit(false);
            try (var statement = c.prepareStatement("SELECT id FROM publications WHERE id=? FOR UPDATE")) {
                statement.setObject(1, p.publication());
                statement.executeQuery().close();
            }
            var future = pool.submit(() -> {
                try {
                    return waiting.save(
                            waitingActor, id, 4, 0, key(), "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}");
                } catch (PublicationFailure e) {
                    return e.code();
                }
            });
            f.waitLock("student_wait");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (f.now().isBefore(cutoff) && System.nanoTime() < end) Thread.onSpinWait();
            assertFalse(f.now().isBefore(cutoff));
            c.commit();
            Object result = future.get(10, TimeUnit.SECONDS);
            if (boundary.equals("expiry")) assertEquals("UNAUTHENTICATED", result);
            else assertEquals(id, ((StudentAttemptStore.Step<?>) result).dueAttempt());
            assertEquals("0", scalar("SELECT revision FROM attempts WHERE id=?", id));
            assertEquals("0", scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", id));
        }
    }

    javax.sql.DataSource loseOneCommitAcknowledgment() {
        var once = new java.util.concurrent.atomic.AtomicBoolean(true);
        return new org.springframework.jdbc.datasource.AbstractDataSource() {
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = f.source.getConnection();
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {java.sql.Connection.class},
                        (proxy, method, args) -> {
                            try {
                                Object value = method.invoke(connection, args);
                                if (method.getName().equals("commit") && once.compareAndSet(true, false))
                                    throw new java.sql.SQLException("Injected lost commit acknowledgment", "08006");
                                return value;
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }

            public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException {
                return getConnection();
            }
        };
    }

    @Test
    void uncertainSubmitCommitReturnsNoSuccessAndSameKeyRecoversOneRealResult() {
        var a = first(f.publication(0).publication());
        String k = key();
        var ds = loseOneCommitAcknowledgment();
        var db = new org.springframework.jdbc.core.JdbcTemplate(ds);
        var manager = new org.springframework.jdbc.support.JdbcTransactionManager(ds);
        var fault = new StudentAttemptService(store, sources, new JdbcAttemptFinalization(db, manager));
        failure(
                "PERSISTENCE_UNAVAILABLE",
                () -> fault.submit(actor, a.attemptId(), 0, k, GradingDocumentsTest.replacement(), "evidence"));
        assertEquals("SUBMITTED", scalar("SELECT state FROM attempts WHERE id=?", a.attemptId()));
        var recovered = service.submit(actor, a.attemptId(), 0, k, GradingDocumentsTest.replacement(), "evidence");
        assertEquals("3.75", recovered.earnedWeight());
        assertEquals("1", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", a.attemptId()));
        assertEquals("1", scalar("SELECT count(*) FROM attempt_operations WHERE attempt_id=?", a.attemptId()));
    }

    @Test
    void uncertainStartCommitRecoversOriginalIdentityWithoutAnotherAttempt() {
        UUID p = f.publication(0).publication();
        String k = key();
        var ds = loseOneCommitAcknowledgment();
        var db = new org.springframework.jdbc.core.JdbcTemplate(ds);
        var manager = new org.springframework.jdbc.support.JdbcTransactionManager(ds);
        var faultStore = new JdbcStudentAttemptStore(db, manager, AssessmentTransactionProperties.defaults(), f.core);
        var fault = new StudentAttemptService(faultStore, sources, f.core);
        failure("PERSISTENCE_UNAVAILABLE", () -> fault.start(actor, p, "START", locator(p), k, "evidence"));
        var recovered = start(p, "START", k);
        assertFalse(recovered.created());
        assertEquals("1", scalar("SELECT count(*) FROM attempts WHERE publication_id=?", p));
        assertEquals(
                recovered.value().attemptId().toString(),
                scalar(
                        "SELECT attempt_id FROM attempt_creation_operations WHERE publication_id=? AND key_digest=?",
                        p,
                        k));
    }

    @Test
    void mutableDraftCannotSupplyStudentAudienceAuthorization() {
        var cfg = f.publication(0).configuration();
        var repository = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC());
        var draft = repository.create(UUID.randomUUID(), UUID.randomUUID().toString(), cfg);
        failure(
                "PUBLICATION_NOT_FOUND",
                () -> service.start(actor, draft.id(), "START", draft.privateLocator(), key(), "evidence"));
        verifyNoInteractions(sources);
        assertEquals("0", scalar("SELECT count(*) FROM attempts WHERE publication_id=?", draft.id()));
    }
}
