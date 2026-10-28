package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.domain.attempt.AttemptRecord;
import com.quizopia.assessment.monitoring.*;
import com.quizopia.assessment.persistence.*;
import com.zaxxer.hikari.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class AssessmentMonitoringOutboxIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AtomicFinalizationIntegrationTest f;
    HikariDataSource pool;
    JdbcMonitoringOutbox outbox;

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
        config.setConnectionInitSql("SET quizopia.monitoring_capture='true'");
        pool = new HikariDataSource(config);
        f.db = new JdbcTemplate(pool);
        f.manager = new JdbcTransactionManager(pool);
        f.core = new JdbcAttemptFinalization(f.db, f.manager);
        outbox = new JdbcMonitoringOutbox(f.db, f.manager);
    }

    @AfterEach
    void cleanup() {
        if (pool != null) pool.close();
    }

    AtomicFinalizationIntegrationTest.Seed monitored(int duration) {
        var now = f.now();
        var cfg = new PublicationConfiguration(
                "Monitored",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                PublicationConfiguration.Audience.CLASS,
                UUID.randomUUID(),
                now.minusSeconds(86400),
                now.plusSeconds(86400),
                duration,
                PublicationConfiguration.MonitoringChoice.ENABLED,
                0);
        var store = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC());
        var owner = UUID.randomUUID();
        var p = store.create(owner, UUID.randomUUID().toString(), cfg);
        store.open(
                owner,
                p.id(),
                0,
                new String(SnapshotFixtures.snapshot(cfg, "private source marker"), StandardCharsets.UTF_8));
        return new AtomicFinalizationIntegrationTest.Seed(p.id(), cfg);
    }

    void save(UUID id, int q, String answer, long revision) {
        new TransactionTemplate(f.manager).execute(s -> {
            f.saved(id, q, answer, revision);
            return null;
        });
    }

    String answer(String value) {
        return "{\"type\":\"NUMERIC_FILL\",\"value\":\"" + value + "\"}";
    }

    long count() {
        return Long.parseLong(f.scalar("SELECT count(*) FROM assessment_monitoring_outbox"));
    }

    @Test
    void defaultDisabledConnectionWritesCompleteAnswersWithoutAnyCapture() {
        var p = monitored(5);
        var a = f.active(p, 5);
        var original = f.source("off");
        var db = new JdbcTemplate(original);
        var manager = new JdbcTransactionManager(original);
        var core = new JdbcAttemptFinalization(db, manager);
        assertNull(db.queryForObject("SELECT current_setting('quizopia.monitoring_capture',true)", String.class));
        core.submit(f.manual(a, 0, GradingDocumentsTest.replacement()));
        assertEquals("4", f.scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", a));
        assertEquals(0, count());
    }

    @Test
    void freshPoolForcesOffInheritedConnectionSettingsIncludingDisabledProductionProfile() {
        for (String profile : List.of("test", "prod")) {
            var inherited = f.source("inherited");
            var options = new Properties();
            options.setProperty("options", "-c quizopia.monitoring_capture=true");
            inherited.setConnectionProperties(options);
            assertEquals(
                    "true",
                    new JdbcTemplate(inherited)
                            .queryForObject(
                                    "SELECT current_setting('quizopia.monitoring_capture',true)", String.class));
            var a = f.active(monitored(5), 5);
            new ApplicationContextRunner()
                    .withUserConfiguration(MonitoringPublisherConfiguration.class)
                    .withInitializer(c -> c.getEnvironment().setActiveProfiles(profile))
                    .withBean(HikariDataSource.class, () -> {
                        var data = new HikariDataSource();
                        data.setJdbcUrl(DB.getJdbcUrl() + "&currentSchema=" + f.schema);
                        data.setUsername(DB.getUsername());
                        data.setPassword(DB.getPassword());
                        data.addDataSourceProperty("options", "-c quizopia.monitoring_capture=true");
                        data.setConnectionInitSql("SET application_name='unrelated-init' -- trailing comment");
                        data.setMaximumPoolSize(2);
                        return data;
                    })
                    .run(c -> {
                        assertNull(c.getStartupFailure());
                        var data = c.getBean(HikariDataSource.class);
                        var jdbc = new JdbcTemplate(data);
                        assertEquals(
                                "unrelated-init",
                                jdbc.queryForObject("SELECT current_setting('application_name')", String.class));
                        assertEquals(
                                "false",
                                jdbc.queryForObject(
                                        "SELECT current_setting('quizopia.monitoring_capture',true)", String.class));
                        new JdbcAttemptFinalization(jdbc, new JdbcTransactionManager(data))
                                .submit(f.manual(a, 0, GradingDocumentsTest.replacement()));
                    });
            assertEquals(0, count());
        }
    }

    @Test
    void changedAnswersCommitWithMinimalMetadataIdenticalSaveDoesNotCreateAnotherFact() {
        var p = monitored(5);
        var a = f.active(p, 5);
        save(a, 4, answer("0001"), 1);
        save(a, 4, answer("0001"), 2);
        save(a, 4, answer("0002"), 3);
        assertEquals(2, count());
        var rows = outbox.claim(UUID.randomUUID(), 5);
        for (var row : rows) {
            String body = new String(row.payload(), StandardCharsets.UTF_8);
            var json = SnapshotFixtures.JSON.readTree(body);
            assertEquals(
                    Set.of(
                            "eventId",
                            "eventType",
                            "eventVersion",
                            "occurredAt",
                            "attemptId",
                            "publicationId",
                            "classroomId",
                            "studentId",
                            "attemptStartedAt",
                            "deadlineAt",
                            "questionNumber",
                            "revision"),
                    json.propertyNames());
            assertEquals("ASSESSMENT_ANSWER_PERSISTED", json.path("eventType").asString());
            assertEquals(1, json.path("eventVersion").asInt());
            assertTrue(json.path("revision").isString());
            assertEquals(a.toString(), json.path("attemptId").asString());
            assertEquals(
                    p.configuration().classroomId().toString(),
                    json.path("classroomId").asString());
            assertEquals(f.student.toString(), json.path("studentId").asString());
            assertEquals(row.id().toString(), json.path("eventId").asString());
            assertFalse(body.contains("0001"));
            assertFalse(body.contains("0002"));
            assertFalse(body.contains("private source marker"));
            assertFalse(body.contains("answerDigest"));
        }
    }

    @Test
    void rollbackInvalidAnswerOrRevisionProducesNoEvidenceAndNoPartialWrite() {
        var a = f.active(monitored(5), 5);
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(f.manager).execute(s -> {
            f.saved(a, 4, answer("0001"), 1);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, count());
        assertEquals("0", f.scalar("SELECT revision FROM attempts WHERE id=?", a));
        assertThrows(RuntimeException.class, () -> save(a, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"bad\"}", 1));
        assertEquals(0, count());
    }

    @Test
    void outboxInsertFailureRollsBackTheAnswerTransactionAndRecoveryIsAuditable() {
        var a = f.active(monitored(5), 5);
        f.db.execute(
                "CREATE FUNCTION monitoring_fixture_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture failure'; END $$");
        f.db.execute(
                "CREATE TRIGGER monitoring_fixture_fail BEFORE INSERT ON assessment_monitoring_outbox FOR EACH ROW EXECUTE FUNCTION monitoring_fixture_fail()");
        assertThrows(RuntimeException.class, () -> save(a, 4, answer("0001"), 1));
        assertEquals("0", f.scalar("SELECT revision FROM attempts WHERE id=?", a));
        assertEquals("0", f.scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", a));
        assertEquals(0, count());
        f.db.execute("DROP TRIGGER monitoring_fixture_fail ON assessment_monitoring_outbox");
        save(a, 4, answer("0001"), 1);
        assertEquals(1, count());
    }

    @Test
    void preinitializedPoolCannotBypassDisabledCaptureInitialization() {
        assertTrue(pool.isRunning());
        new ApplicationContextRunner()
                .withUserConfiguration(MonitoringPublisherConfiguration.class)
                .withBean(HikariDataSource.class, () -> pool)
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void publicAndUnlimitedUnmonitoredAttemptsNeverEmit() {
        for (int duration : List.of(0, 5)) {
            var p = f.publication(duration);
            var a = f.active(p, duration);
            save(a, 4, answer("0001"), 1);
        }
        assertEquals(0, count());
    }

    @Test
    void realFinalizationFullAnswersAreAtomicAndPostDeadlineFactsNeverExtendAttempt() {
        var p = monitored(5);
        var a = f.attempt(p, 5, f.now().minusSeconds(301));
        // Persist the pre-deadline saved fixture with its original valid save time. The real
        // timeout normalizer later updates ordering while retaining that save time.
        new TransactionTemplate(f.manager).execute(s -> {
            f.db.update(
                    "INSERT INTO attempt_answers SELECT id,2,'{\"type\":\"MULTIPLE_CHOICE\",\"optionLabels\":[\"C\",\"A\"]}'::jsonb,1,started_at+interval '1 second' FROM attempts WHERE id=?",
                    a);
            f.db.update("UPDATE attempts SET revision=1 WHERE id=?", a);
            return null;
        });
        String deadline = f.scalar("SELECT deadline_at::text FROM attempts WHERE id=?", a);
        var result = f.core.systemFinalize(a, AttemptRecord.Reason.BOUNDED_TIMEOUT);
        assertTrue(result.result().isPresent());
        assertEquals("SUBMITTED", f.scalar("SELECT state FROM attempts WHERE id=?", a));
        assertEquals(deadline, f.scalar("SELECT deadline_at::text FROM attempts WHERE id=?", a));
        assertEquals("1", f.scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", a));
        assertEquals(2, count());
        assertEquals(
                "2",
                f.scalar(
                        "SELECT count(*) FROM assessment_monitoring_outbox WHERE (payload->>'occurredAt')::timestamptz>(payload->>'deadlineAt')::timestamptz"));
        assertEquals(
                "0", f.scalar("SELECT count(*) FROM assessment_monitoring_outbox WHERE payload::text LIKE '%answer%'"));
        assertEquals(
                0,
                outbox.claim(UUID.randomUUID(), 5).stream()
                        .filter(e -> e.payload().length > 4096)
                        .count());
    }

    @Test
    void manualSubmitCapturesEveryCompleteAnswerInItsExistingAtomicFinalization() {
        var a = f.active(monitored(5), 5);
        var result = f.core.submit(f.manual(a, 0, GradingDocumentsTest.replacement()));
        assertTrue(result.result().isPresent());
        assertEquals("SUBMITTED", f.scalar("SELECT state FROM attempts WHERE id=?", a));
        assertEquals("4", f.scalar("SELECT count(*) FROM attempt_answers WHERE attempt_id=?", a));
        assertEquals(4, count());
        assertEquals(
                "4",
                f.scalar(
                        "SELECT count(DISTINCT question_number) FROM assessment_monitoring_outbox WHERE attempt_id=?",
                        a));
    }

    @Test
    void independentClaimersSkipLockedLeasesAndRetryPreservesExactIdentity() throws Exception {
        var a = f.active(monitored(5), 5);
        for (int n = 1; n <= 4; n++) save(a, 4, answer(String.format("%04d", n)), n);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> outbox.claim(UUID.randomUUID(), 2));
            var second = executor.submit(() -> new JdbcMonitoringOutbox(f.db, f.manager).claim(UUID.randomUUID(), 2));
            var all = new ArrayList<MonitoringOutbox.Event>();
            all.addAll(first.get(5, TimeUnit.SECONDS));
            all.addAll(second.get(5, TimeUnit.SECONDS));
            assertEquals(4, all.size());
            assertEquals(
                    4, all.stream().map(MonitoringOutbox.Event::id).distinct().count());
            assertTrue(outbox.claim(UUID.randomUUID(), 5).isEmpty());
            var event = all.getFirst();
            outbox.retry(event);
            var reclaimed = outbox.claim(UUID.randomUUID(), 1).getFirst();
            assertEquals(event.id(), reclaimed.id());
            assertArrayEquals(event.payload(), reclaimed.payload());
            assertThrows(IllegalStateException.class, () -> outbox.published(event));
            outbox.published(reclaimed);
            assertEquals(
                    "PUBLISHED",
                    f.scalar("SELECT state FROM assessment_monitoring_outbox WHERE event_id=?", event.id()));
            assertThrows(
                    RuntimeException.class,
                    () -> f.db.update(
                            "UPDATE assessment_monitoring_outbox SET payload='{}'::jsonb WHERE event_id=?",
                            event.id()));
            assertThrows(
                    RuntimeException.class,
                    () -> f.db.update("DELETE FROM assessment_monitoring_outbox WHERE event_id=?", event.id()));
        }
    }

    @Test
    void expiredLeaseRecoveryNeverLetsOldPublisherAcknowledgeNewOwner() {
        var a = f.active(monitored(5), 5);
        save(a, 4, answer("0001"), 1);
        var old = outbox.claim(UUID.randomUUID(), 1).getFirst();
        f.db.update(
                "UPDATE assessment_monitoring_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=?",
                old.id());
        var recovered = new JdbcMonitoringOutbox(f.db, f.manager)
                .claim(UUID.randomUUID(), 1)
                .getFirst();
        assertEquals(old.id(), recovered.id());
        assertArrayEquals(old.payload(), recovered.payload());
        assertThrows(IllegalStateException.class, () -> outbox.published(old));
        outbox.retry(old);
        assertTrue(outbox.claim(UUID.randomUUID(), 1).isEmpty());
        outbox.published(recovered);
        assertEquals(0, outbox.claim(UUID.randomUUID(), 5).size());
    }

    @Test
    void heldDeliveryRowIsSkippedAndRetriesDoNotStarveFreshFacts() throws Exception {
        var a = f.active(monitored(5), 5);
        save(a, 4, answer("0001"), 1);
        save(a, 4, answer("0002"), 2);
        try (var connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            UUID locked;
            try (var statement = connection.createStatement();
                    var row = statement.executeQuery(
                            "SELECT event_id FROM assessment_monitoring_outbox ORDER BY created_at,event_id LIMIT 1 FOR UPDATE")) {
                assertTrue(row.next());
                locked = row.getObject(1, UUID.class);
            }
            var available = outbox.claim(UUID.randomUUID(), 5);
            assertEquals(1, available.size());
            assertNotEquals(locked, available.getFirst().id());
            connection.rollback();
            outbox.retry(available.getFirst());
            var fresh = outbox.claim(UUID.randomUUID(), 1).getFirst();
            assertEquals(locked, fresh.id());
            outbox.published(fresh);
        }
    }

    @Test
    void additiveUpgradePreservesEveryEarlierChecksumAndReapplicationIsNoop() {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var base = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target("5")
                .load();
        base.migrate();
        var db = new JdbcTemplate(f.source("upgrade"));
        var old = db.queryForList("SELECT version,checksum FROM " + schema
                + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        var upgraded = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .load();
        assertEquals(5, upgraded.migrate().migrationsExecuted);
        assertEquals(
                old,
                db.queryForList("SELECT version,checksum FROM " + schema
                        + ".flyway_schema_history WHERE version IS NOT NULL AND version<>'6' AND version<>'7'"
                        + " AND version<>'8' AND version<>'9' AND version<>'10' ORDER BY installed_rank"));
        assertEquals(0, upgraded.migrate().migrationsExecuted);
        upgraded.validate();
    }
}
