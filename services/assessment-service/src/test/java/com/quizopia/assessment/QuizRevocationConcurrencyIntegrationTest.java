package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.QuizRevocationService.Operation;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * START/SAVE/SUBMIT versus fence-installation races on real PostgreSQL 17 with independent
 * connections. The fence transaction takes the same publications row locks every Assessment
 * writer takes first: writers committing before the fence keep their rows for later purge, and
 * every writer after the fence is denied with ASSESSMENT_REMOVED (ADR-018 Phase A step 3).
 */
@Testcontainers
class QuizRevocationConcurrencyIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AtomicFinalizationIntegrationTest f;
    QuizRevocationService revocations;
    JdbcStudentAttemptStore attempts;
    StudentAttemptService students;
    UUID owner;
    UUID student;
    String key = "2".repeat(64);

    @BeforeEach
    void setup() {
        f = new AtomicFinalizationIntegrationTest();
        f.setup();
        revocations = new QuizRevocationService(f.db, f.manager, AssessmentTransactionProperties.defaults());
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

    String locator(UUID publication) {
        return scalar("SELECT private_locator FROM publications WHERE id=?", publication);
    }

    Publication openPublication(UUID quiz) {
        Instant time = now().truncatedTo(ChronoUnit.MICROS);
        var config = new PublicationConfiguration(
                "Race fixture",
                null,
                quiz,
                UUID.randomUUID(),
                1,
                Audience.PUBLIC,
                null,
                time.minusSeconds(86400),
                time.plusSeconds(86400),
                30,
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

    UUID activeAttempt(UUID publication, int startedMinutesAgo) {
        UUID id = UUID.randomUUID();
        Instant started = now().minusSeconds(startedMinutesAgo * 60L);
        f.db.update(
                "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,1,?,1,1,'ACTIVE',30,?,?)",
                id,
                publication,
                student,
                publication,
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(started.plusSeconds(1800)));
        return id;
    }

    UUID activeAttempt(UUID publication) {
        return activeAttempt(publication, 0);
    }

    StudentAttemptStore.Actor actor() {
        return new StudentAttemptStore.Actor(student, now().plusSeconds(3600));
    }

    String key() {
        return JdbcPublicationStore.digest(UUID.randomUUID().toString());
    }

    void removed(Runnable operation) {
        var failure = assertThrows(PublicationFailure.class, operation::run);
        assertEquals("ASSESSMENT_REMOVED", failure.code());
        assertEquals(404, failure.status());
    }

    @Test
    void fenceWaitsForInFlightPublicationWriterThenDeniesLaterMutations() throws Exception {
        UUID quiz = UUID.randomUUID();
        var publication = openPublication(quiz);
        UUID attempt = activeAttempt(publication.id());
        var hold = new TransactionTemplate(f.manager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var holder = CompletableFuture.runAsync(() -> hold.execute(status -> {
            f.db.query(
                    "SELECT id FROM publications WHERE id=? FOR UPDATE",
                    (r, n) -> r.getObject(1, UUID.class),
                    publication.id());
            locked.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertTrue(locked.await(10, TimeUnit.SECONDS));

        AtomicReference<Exception> installation = new AtomicReference<>();
        var fencing = CompletableFuture.runAsync(() -> {
            try {
                revocations.applyBatch(List.of(new Operation(UUID.randomUUID(), quiz)));
            } catch (Exception failure) {
                installation.set(failure);
            }
        });
        // While the in-flight writer holds the publication row, the fence must not commit.
        Thread.sleep(500);
        assertEquals("0", scalar("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", quiz));
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);
        fencing.get(15, TimeUnit.SECONDS);
        assertNull(installation.get());
        assertEquals("1", scalar("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", quiz));

        // Writers starting after the fence committed are denied, including finalization.
        removed(() -> students.save(
                actor(), attempt, 1, 0, key(), "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}", "evidence"));
        removed(() -> students.submit(actor(), attempt, 0, key(), null, "evidence"));
        removed(() -> students.start(actor(), publication.id(), "START", locator(publication.id()), key(), "evidence"));
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
    }

    @Test
    void submitCommittingBeforeFenceSucceedsAndReplayIsDeniedAfterwards() {
        UUID quiz = UUID.randomUUID();
        var publication = openPublication(quiz);
        UUID attempt = activeAttempt(publication.id());
        String submitKey = key();
        var result = students.submit(actor(), attempt, 0, submitKey, null, "evidence");
        assertEquals("4", result.possibleWeight());
        var batch = revocations.applyBatch(List.of(new Operation(UUID.randomUUID(), quiz)));
        assertEquals(1, batch.accepted().size());

        // Committed-before-fence history remains for the later purge scope...
        assertEquals("SUBMITTED", scalar("SELECT state FROM attempts WHERE id=?", attempt));
        assertEquals("1", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", attempt));
        // ...but no route, including exact-key replay, may return it.
        removed(() -> students.submit(actor(), attempt, 0, submitKey, null, "evidence"));
        removed(() -> students.result(actor(), attempt, "evidence"));
        removed(() -> students.review(actor(), attempt, "evidence"));
    }

    @Test
    void systemFinalizationNeverGradesAfterTheFence() {
        UUID quiz = UUID.randomUUID();
        var publication = openPublication(quiz);
        // Started 31 minutes ago with a 30-minute bound: already due for timeout finalization.
        UUID attempt = activeAttempt(publication.id(), 31);
        revocations.applyBatch(List.of(new Operation(UUID.randomUUID(), quiz)));
        removed(() -> f.core.systemFinalize(
                attempt, com.quizopia.assessment.domain.attempt.AttemptRecord.Reason.BOUNDED_TIMEOUT));
        assertEquals("ACTIVE", scalar("SELECT state FROM attempts WHERE id=?", attempt));
        assertEquals("0", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", attempt));
    }

    @Test
    void racingStartSaveAndSubmitEachLinearizeAgainstTheFence() throws Exception {
        for (String action : List.of("START", "SAVE", "SUBMIT")) {
            setup();
            UUID quiz = UUID.randomUUID();
            var publication = openPublication(quiz);
            UUID attempt = action.equals("START") ? null : activeAttempt(publication.id());
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<PublicationFailure> denial = new AtomicReference<>();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            AtomicReference<Object> committed = new AtomicReference<>();
            var writer = CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                    if (action.equals("START"))
                        committed.set(students.start(
                                        actor(),
                                        publication.id(),
                                        "START",
                                        locator(publication.id()),
                                        key(),
                                        "evidence")
                                .value());
                    else if (action.equals("SAVE"))
                        committed.set(students.save(
                                actor(),
                                attempt,
                                1,
                                0,
                                key(),
                                "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}",
                                "evidence"));
                    else committed.set(students.submit(actor(), attempt, 0, key(), null, "evidence"));
                } catch (PublicationFailure failure) {
                    denial.set(failure);
                } catch (Throwable failure) {
                    unexpected.set(failure);
                }
            });
            AtomicReference<Throwable> installer = new AtomicReference<>();
            var fence = CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                    revocations.applyBatch(List.of(new Operation(UUID.randomUUID(), quiz)));
                } catch (Throwable failure) {
                    installer.set(failure);
                }
            });
            start.countDown();
            writer.get(20, TimeUnit.SECONDS);
            fence.get(20, TimeUnit.SECONDS);
            assertNull(unexpected.get(), action + " writer failed unexpectedly");
            assertNull(installer.get(), action + " fence failed");
            assertEquals("1", scalar("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", quiz));
            // Exactly one of the two linearization outcomes: committed before, or denied after.
            if (denial.get() != null) {
                assertNull(committed.get(), action + " both denied and committed");
                assertEquals("ASSESSMENT_REMOVED", denial.get().code());
            } else {
                assertNotNull(committed.get(), action + " neither denied nor committed");
            }
            if (action.equals("SUBMIT") && denial.get() != null) {
                // Either ordering is legal: nothing committed, or committed but the response
                // read was itself denied by the already-committed fence.
                String state = scalar("SELECT state FROM attempts WHERE id=?", attempt);
                assertTrue(state.equals("ACTIVE") || state.equals("SUBMITTED"));
                if (state.equals("SUBMITTED"))
                    assertEquals("1", scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", attempt));
            }
            if (action.equals("SUBMIT") && committed.get() != null)
                assertEquals("SUBMITTED", scalar("SELECT state FROM attempts WHERE id=?", attempt));
            // After the race, every later mutation denies.
            UUID target = attempt == null
                    ? f
                            .db
                            .query(
                                    "SELECT id FROM attempts WHERE publication_id=?",
                                    (r, n) -> r.getObject(1, UUID.class),
                                    publication.id())
                            .stream()
                            .findFirst()
                            .orElse(null)
                    : attempt;
            if (target != null)
                removed(() -> attempts.save(
                        actor(), target, 2, 0, key(), "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}"));
        }
    }
}
