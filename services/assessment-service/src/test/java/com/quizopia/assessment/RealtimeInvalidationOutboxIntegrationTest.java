package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.ClosureDependencies;
import com.quizopia.assessment.application.ClosureService;
import com.quizopia.assessment.application.ClosureStore;
import com.quizopia.assessment.configuration.AssessmentTransactionProperties;
import com.quizopia.assessment.configuration.ClosureProperties;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.infrastructure.ClosureCursor;
import com.quizopia.assessment.persistence.JdbcAttemptFinalization;
import com.quizopia.assessment.persistence.JdbcClosureStore;
import com.quizopia.assessment.persistence.JdbcPublicationStore;
import com.quizopia.assessment.realtime.*;
import com.zaxxer.hikari.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real PostgreSQL with the W4 capture GUC; only allowlisted invalidation metadata may be stored. */
@Testcontainers
class RealtimeInvalidationOutboxIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AtomicFinalizationIntegrationTest f;
    HikariDataSource pool;
    JdbcRealtimeInvalidationOutbox outbox;

    @BeforeEach
    void setup() {
        f = new AtomicFinalizationIntegrationTest();
        f.setup();
        var config = new HikariConfig();
        config.setJdbcUrl(DB.getJdbcUrl() + "&currentSchema=" + f.schema);
        config.setUsername(DB.getUsername());
        config.setPassword(DB.getPassword());
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(2000);
        config.setConnectionInitSql("SET quizopia.realtime_invalidation='true'");
        pool = new HikariDataSource(config);
        f.db = new JdbcTemplate(pool);
        f.manager = new JdbcTransactionManager(pool);
        f.core = new JdbcAttemptFinalization(f.db, f.manager);
        outbox = new JdbcRealtimeInvalidationOutbox(f.db, f.manager);
    }

    @AfterEach
    void cleanup() {
        if (pool != null) pool.close();
    }

    long events(UUID resource) {
        return Long.parseLong(
                f.scalar("SELECT count(*) FROM realtime_invalidation_outbox WHERE resource_id=?", resource));
    }

    String event(UUID resource, String column) {
        return f.scalar(
                "SELECT " + column
                        + " FROM realtime_invalidation_outbox WHERE resource_id=? ORDER BY created_at LIMIT 1",
                resource);
    }

    void revisionBump(UUID attempt) {
        f.db.update("UPDATE attempts SET revision=revision+1 WHERE id=? AND state='ACTIVE'", attempt);
    }

    @Test
    void defaultDisabledConnectionCapturesNothing() {
        var source = f.source("off");
        var plain = new JdbcTemplate(source);
        assertNull(plain.queryForObject("SELECT current_setting('quizopia.realtime_invalidation',true)", String.class));
        var store = new JdbcPublicationStore(plain, new JdbcTransactionManager(source), Clock.systemUTC());
        var cfg = configuration(0);
        var owner = UUID.randomUUID();
        var publication = store.create(owner, UUID.randomUUID().toString(), cfg);
        store.open(
                owner,
                publication.id(),
                0,
                new String(SnapshotFixtures.snapshot(cfg, "private source marker"), StandardCharsets.UTF_8));
        plain.update(
                "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,?,?,1,1,'ACTIVE',0,clock_timestamp(),NULL)",
                UUID.randomUUID(),
                publication.id(),
                f.student,
                1,
                publication.id());
        assertEquals(0L, Long.parseLong(f.scalar("SELECT count(*) FROM realtime_invalidation_outbox")));
    }

    PublicationConfiguration configuration(int duration) {
        var now = f.now().truncatedTo(ChronoUnit.MICROS);
        return new PublicationConfiguration(
                "Title",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                PublicationConfiguration.Audience.PUBLIC,
                null,
                now.minusSeconds(86400),
                now.plusSeconds(86400),
                duration,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                0);
    }

    @Test
    void publicationOpenIsCapturedExactlyOnceWithFrozenMetadata() {
        var seed = f.publication(0);
        assertEquals(1L, events(seed.publication()));
        assertEquals("PUBLICATION_INVALIDATED", event(seed.publication(), "event_type"));
        assertEquals("1", event(seed.publication(), "event_version"));
        assertEquals("PUBLICATION", event(seed.publication(), "resource_kind"));
        assertEquals("PENDING", event(seed.publication(), "state"));
    }

    @Test
    void draftCreationConfigurationUpdateAndNoOpRevisionBumpsAreExcluded() {
        var store = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC());
        var owner = UUID.randomUUID();
        var now = f.now();
        var cfg = new PublicationConfiguration(
                "Title",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                PublicationConfiguration.Audience.PUBLIC,
                null,
                now.minusSeconds(86400),
                now.plusSeconds(86400),
                30,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                0);
        var draft = store.create(owner, UUID.randomUUID().toString(), cfg);
        assertEquals(0L, events(draft.id()));
        store.update(
                owner,
                draft.id(),
                0,
                new PublicationConfiguration(
                        cfg.title(),
                        "Changed",
                        cfg.quizId(),
                        cfg.quizVersionId(),
                        cfg.versionNumber(),
                        cfg.audience(),
                        cfg.classroomId(),
                        cfg.startAt(),
                        cfg.endAt(),
                        cfg.durationMinutes(),
                        cfg.monitoringChoice(),
                        cfg.maxAttempts()));
        assertEquals(0L, events(draft.id()));
        var seed = f.publication(0);
        assertEquals(1L, events(seed.publication()));
        // Closure-path revision bumps and unrelated no-op updates never emit.
        f.db.update("UPDATE publications SET revision=revision+1 WHERE id=?", seed.publication());
        f.db.update("UPDATE publications SET revision=revision+1 WHERE id=?", seed.publication());
        assertEquals(1L, events(seed.publication()));
    }

    @Test
    void meaningfulScheduleExtensionIsCapturedWhileNoOpIsNot() {
        var seed = f.publication(0);
        var store = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC());
        var owner = UUID.fromString(f.scalar("SELECT owner_id FROM publications WHERE id=?", seed.publication()));
        long revision = Long.parseLong(f.scalar("SELECT revision FROM publications WHERE id=?", seed.publication()));
        Instant end = f.now().plusSeconds(90000).truncatedTo(ChronoUnit.MICROS);
        store.extend(owner, seed.publication(), revision, end, 0);
        assertEquals(2L, events(seed.publication()));
        f.db.update("UPDATE publications SET revision=revision+1 WHERE id=?", seed.publication());
        assertEquals(2L, events(seed.publication()));
    }

    @Test
    void attemptCreationSaveSubmitTimeoutAndAbandonTransitionsAreCaptured() {
        var seed = f.publication(5);
        UUID created = f.active(seed, 5);
        assertEquals(1L, events(created));
        // The real save path bumps the attempt revision and writes answers in one transaction;
        // neither the revision change nor the answer content may emit an event.
        new TransactionTemplate(f.manager).execute(status -> {
            f.db.update("UPDATE attempts SET revision=revision+1 WHERE id=? AND state='ACTIVE'", created);
            f.saved(created, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", 2);
            return null;
        });
        assertEquals(1L, events(created));
        long currentRevision = Long.parseLong(f.scalar("SELECT revision FROM attempts WHERE id=?", created));
        f.core.submit(f.manual(created, currentRevision, GradingDocumentsTest.replacement()));
        assertEquals(2L, events(created));
        assertEquals("SUBMITTED", f.scalar("SELECT state FROM attempts WHERE id=?", created));

        UUID overdue = f.attempt(seed, 5, f.now().minusSeconds(3600));
        assertEquals(1L, events(overdue));
        f.core.systemFinalize(overdue, com.quizopia.assessment.domain.attempt.AttemptRecord.Reason.BOUNDED_TIMEOUT);
        assertEquals(2L, events(overdue));

        UUID abandoned = f.active(seed, 5);
        assertEquals(1L, events(abandoned));
        var attemptStore = new com.quizopia.assessment.persistence.JdbcStudentAttemptStore(
                f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core);
        attemptStore.abandon(
                new com.quizopia.assessment.application.StudentAttemptStore.Actor(
                        f.student, Instant.now().plusSeconds(300)),
                abandoned,
                Long.parseLong(f.scalar("SELECT revision FROM attempts WHERE id=?", abandoned)),
                f.key);
        assertEquals(2L, events(abandoned));
        assertEquals("ATTEMPT", event(abandoned, "resource_kind"));
        assertEquals("ATTEMPT_INVALIDATED", event(abandoned, "event_type"));
    }

    @Test
    void rolledBackTransitionAndOutboxFailureNeverProduceEvents() {
        var seed = f.publication(5);
        UUID attempt = f.active(seed, 5);
        assertEquals(1L, events(attempt));
        new TransactionTemplate(f.manager).execute(status -> {
            f.db.update(
                    "UPDATE attempts SET state='ABANDONED',revision=revision+1,terminal_reason='STUDENT_ABANDON',finalized_at=clock_timestamp() WHERE id=? AND state='ACTIVE'",
                    attempt);
            status.setRollbackOnly();
            return null;
        });
        assertEquals(1L, events(attempt));
        assertEquals("ACTIVE", f.scalar("SELECT state FROM attempts WHERE id=?", attempt));

        f.db.execute(
                "CREATE FUNCTION reject_realtime_invalidation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'outbox insert must fail'; END $$");
        f.db.execute(
                "CREATE TRIGGER reject_realtime_invalidation BEFORE INSERT ON realtime_invalidation_outbox FOR EACH ROW EXECUTE FUNCTION reject_realtime_invalidation()");
        assertThrows(
                RuntimeException.class,
                () -> f.db.update(
                        "UPDATE attempts SET state='ABANDONED',revision=revision+1,terminal_reason='STUDENT_ABANDON',finalized_at=clock_timestamp() WHERE id=? AND state='ACTIVE'",
                        attempt));
        assertEquals("ACTIVE", f.scalar("SELECT state FROM attempts WHERE id=?", attempt));
        assertEquals(1L, events(attempt));
    }

    @Test
    void closedPublicationAndClosureFinalizedAttemptAreCaptured() {
        var seed = f.publication(0);
        var teacher = new ClosureStore.Teacher(
                UUID.fromString(f.scalar("SELECT owner_id FROM publications WHERE id=?", seed.publication())),
                Instant.now().plusSeconds(8640000),
                "fixture");
        var properties = new ClosureProperties(
                true,
                true,
                true,
                true,
                50,
                50,
                "v1",
                Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        var store = new JdbcClosureStore(
                f.db, f.manager, AssessmentTransactionProperties.defaults(), new ClosureCursor(properties));
        ClosureDependencies deps = new ClosureDependencies() {
            public void requireInbox() {}

            public Eligibility eligible(UUID id) {
                return new Eligibility(id, true, f.now());
            }

            public void teacher(ClosureStore.Teacher actor) {}
        };
        var service = new ClosureService(store, deps, f.core, properties, studentProfiles -> java.util.Map.of());
        UUID attempt = f.active(seed, 0);
        assertEquals(1L, events(attempt));
        f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()));
        assertEquals(2L, events(attempt));
        assertEquals(
                200,
                service.command(
                                teacher,
                                new ClosureStore.Command(
                                        seed.publication(),
                                        null,
                                        Long.parseLong(f.scalar(
                                                "SELECT revision FROM publications WHERE id=?", seed.publication())),
                                        JdbcPublicationStore.digest(
                                                UUID.randomUUID().toString()),
                                        "CLOSE"))
                        .status());
        assertEquals("CLOSED", f.scalar("SELECT state FROM publications WHERE id=?", seed.publication()));
        assertEquals(2L, events(seed.publication()));
    }

    @Test
    void claimsAreLeasedExclusiveBackedOffQuarantinedAndImmutable() {
        UUID resource = UUID.randomUUID();
        f.db.update(
                "INSERT INTO realtime_invalidation_outbox(event_id,event_type,event_version,resource_kind,resource_id,occurred_at) VALUES(gen_random_uuid(),'PUBLICATION_INVALIDATED',1,'PUBLICATION',?,clock_timestamp())",
                resource);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var claimed = outbox.claim(first, 5);
        assertEquals(1, claimed.size());
        assertEquals(1L, claimed.getFirst().deliveryAttempts());
        assertTrue(outbox.claim(second, 5).isEmpty(), "An active lease must exclude other claimers");
        outbox.retry(claimed.getFirst(), 30);
        assertTrue(outbox.claim(first, 5).isEmpty(), "Backoff must defer the next attempt");
        double deferred = Double.parseDouble(f.scalar(
                "SELECT extract(epoch FROM (next_attempt_at-clock_timestamp())) FROM realtime_invalidation_outbox WHERE resource_id=?",
                resource));
        assertTrue(deferred > 20, "expected backoff remaining, got " + deferred);
        f.db.update(
                "UPDATE realtime_invalidation_outbox SET next_attempt_at=clock_timestamp() WHERE resource_id=?",
                resource);
        var reclaimed = outbox.claim(first, 5);
        assertEquals(1, reclaimed.size());
        outbox.published(reclaimed.getFirst());
        assertEquals("PUBLISHED", event(resource, "state"));

        UUID poison = UUID.randomUUID();
        f.db.update(
                "INSERT INTO realtime_invalidation_outbox(event_id,event_type,event_version,resource_kind,resource_id,occurred_at) VALUES(gen_random_uuid(),'ATTEMPT_INVALIDATED',1,'ATTEMPT',?,clock_timestamp())",
                poison);
        var poisonClaim = outbox.claim(first, 5);
        assertEquals(1, poisonClaim.size());
        outbox.quarantined(poisonClaim.getFirst());
        assertEquals("QUARANTINED", event(poison, "state"));
        assertTrue(outbox.claim(first, 5).isEmpty());

        assertThrows(
                RuntimeException.class,
                () -> f.db.update(
                        "UPDATE realtime_invalidation_outbox SET resource_id=? WHERE resource_id=?",
                        UUID.randomUUID(),
                        resource));
        assertThrows(
                RuntimeException.class,
                () -> f.db.update("DELETE FROM realtime_invalidation_outbox WHERE resource_id=?", resource));
        assertEquals(0L, outbox.pending().count());
    }

    @Test
    void concurrentClaimersTakeDisjointBatches() {
        for (int i = 0; i < 2; i++)
            f.db.update(
                    "INSERT INTO realtime_invalidation_outbox(event_id,event_type,event_version,resource_kind,resource_id,occurred_at) VALUES(gen_random_uuid(),'ATTEMPT_INVALIDATED',1,'ATTEMPT',?,clock_timestamp())",
                    UUID.randomUUID());
        var one = outbox.claim(UUID.randomUUID(), 1);
        var two = outbox.claim(UUID.randomUUID(), 1);
        assertEquals(1, one.size());
        assertEquals(1, two.size());
        assertNotEquals(one.getFirst().id(), two.getFirst().id());
    }
}
