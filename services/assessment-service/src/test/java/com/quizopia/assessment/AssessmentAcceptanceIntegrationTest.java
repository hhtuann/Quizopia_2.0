package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.persistence.JdbcAttemptFinalization;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Isolated PG17 acceptance drills, never an operator-provided database or production dump. */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssessmentAcceptanceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    static void awaitPublicationWaiters(AtomicFinalizationIntegrationTest f, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (f.db.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
                            Integer.class,
                            f.schema + "_main")
                    >= count) return;
            Thread.onSpinWait();
        }
        fail("Expected contenders did not reach PostgreSQL locks");
    }

    @Test
    @Order(1)
    void requestVersusRetakeCannotCreateOrOmitMembershipAfterWindowEnd() throws Exception {
        var c = new ClosureIntegrationTest();
        c.setup();
        UUID student = c.f.student;
        c.f.core.submit(c.f.manual(c.attempt, 0, null));
        c.f.student = UUID.randomUUID();
        UUID captured = c.f.active(new AtomicFinalizationIntegrationTest.Seed(c.publication, null), 0);
        var students = new com.quizopia.assessment.persistence.JdbcStudentAttemptStore(
                c.f.db,
                c.f.manager,
                com.quizopia.assessment.configuration.AssessmentTransactionProperties.defaults(),
                c.f.core);
        String locator = c.scalar("SELECT private_locator FROM publications WHERE id=?", c.publication);
        try (var held = c.f.source.getConnection();
                var pool = Executors.newFixedThreadPool(2)) {
            held.setAutoCommit(false);
            held.createStatement().execute("SELECT id FROM publications FOR UPDATE");
            var request = pool.submit(() -> c.service.command(c.teacher, c.command("REQUEST", null, 1)));
            var retake = pool.submit(() -> {
                try {
                    students.start(
                            new StudentAttemptStore.Actor(student, c.teacher.expiresAt()),
                            c.publication,
                            "RETAKE",
                            locator,
                            com.quizopia.assessment.persistence.JdbcPublicationStore.digest("acceptance-retake"));
                    return "UNEXPECTED_CREATED";
                } catch (PublicationFailure rejected) {
                    return rejected.code();
                }
            });
            try {
                awaitPublicationWaiters(c.f, 2);
            } finally {
                held.commit();
            }
            assertEquals(201, request.get(5, TimeUnit.SECONDS).status());
            assertTrue(Set.of("WINDOW_ENDED", "CLOSURE_PENDING").contains(retake.get(5, TimeUnit.SECONDS)));
        }
        assertEquals("2", c.scalar("SELECT count(*) FROM attempts"));
        assertEquals(captured.toString(), c.scalar("SELECT attempt_id FROM closure_request_members"));
    }

    @Test
    @Order(1)
    void ordinaryCloseVersusActiveCutoffWorkerHasOneResultAndTeacherOnlyClose() throws Exception {
        var c = new ClosureIntegrationTest();
        c.setup();
        UUID request = c.request();
        c.confirmed(request);
        c.advance(86401 + 172801);
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocked = new JdbcTemplate(c.f.source) {
            @Override
            public int update(String sql, Object... args) {
                int result = super.update(sql, args);
                if (sql.startsWith("INSERT INTO grading_results")) {
                    reached.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return result;
            }
        };
        var worker = new com.quizopia.assessment.infrastructure.ClosureWorker(
                c.store,
                c.dependencies,
                new JdbcAttemptFinalization(blocked, c.f.manager),
                c.properties,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var command = c.command("CLOSE", null, 2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var pending = pool.submit(() -> worker.process(c.work(request, "NOTICE_CONFIRMED")));
            try {
                assertTrue(reached.await(5, TimeUnit.SECONDS));
                assertEquals("OPEN", c.scalar("SELECT state FROM publications"));
                assertEquals("0", c.scalar("SELECT count(*) FROM grading_results"));
                var close = pool.submit(() -> c.service.command(c.teacher, command));
                try {
                    awaitPublicationWaiters(c.f, 1);
                } finally {
                    release.countDown();
                }
                pending.get(5, TimeUnit.SECONDS);
                assertEquals(200, close.get(5, TimeUnit.SECONDS).status());
            } finally {
                release.countDown();
            }
        }
        assertEquals("CLOSED", c.scalar("SELECT state FROM publications"));
        assertEquals("1", c.scalar("SELECT count(*) FROM attempt_finalization_audit"));
        c.worker.process(c.work(request, "NOTICE_CONFIRMED"));
        assertEquals("1", c.scalar("SELECT count(*) FROM grading_results"));
    }

    @Test
    @Order(1)
    void operationalMetricsDistinguishBlockedRecipientsWithoutIdentityLabels() {
        var c = new ClosureIntegrationTest();
        c.setup();
        UUID request = c.request();
        c.published(request);
        var unavailable = new AtomicBoolean(false);
        var dependency = new ClosureDependencies() {
            public void requireInbox() {}

            public Eligibility eligible(UUID student) {
                if (unavailable.get()) throw new PublicationFailure(503, "DEPENDENCY_UNAVAILABLE");
                return new Eligibility(student, false, c.now());
            }

            public void teacher(ClosureStore.Teacher actor) {}
        };
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var worker = new com.quizopia.assessment.infrastructure.ClosureWorker(
                c.store, dependency, c.f.core, c.properties, registry);
        worker.process(c.work(request, "NOTICE_PUBLISHED"));
        unavailable.set(true);
        worker.process(c.work(request, "NOTICE_PUBLISHED"));
        worker.tick();
        assertEquals(
                1,
                registry.get("assessment.closure.work")
                        .tag("outcome", "ineligible")
                        .counter()
                        .count());
        assertEquals(
                1,
                registry.get("assessment.closure.work")
                        .tag("outcome", "authority_unavailable")
                        .counter()
                        .count());
        assertEquals(1, registry.get("assessment.closure.unconfirmed").gauge().value());
        for (var meter : registry.getMeters()) {
            for (var tag : meter.getId().getTags()) {
                assertEquals("outcome", tag.getKey());
                assertTrue(Set.of("ineligible", "authority_unavailable").contains(tag.getValue()));
            }
        }
        assertEquals(
                "2",
                c.scalar(
                        "SELECT count(*) FROM closure_work_audit WHERE outcome IN ('INELIGIBLE','AUTHORITY_UNAVAILABLE')"));
        assertNull(c.store.published(c.work(request, "NOTICE_PUBLISHED")).closureCutoffAt());
        assertEquals("ACTIVE", c.scalar("SELECT state FROM attempts WHERE id=?", c.attempt));
    }

    @Test
    @Order(2)
    void committedClosureHistorySurvivesRealDumpRestoreAndPostgresRestart() throws Exception {
        var c = new ClosureIntegrationTest();
        c.setup();
        new TransactionTemplate(c.f.manager).execute(status -> {
            c.f.saved(c.attempt, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", 1);
            return null;
        });
        UUID request = c.request();
        c.confirmed(request);
        c.advance(86401 + 172801);
        assertEquals(
                200,
                c.service.command(c.teacher, c.command("COMPLETE", request, 2)).status());
        c.advance(0);
        // Retain both finalized closure evidence and independent ACTIVE saved work.
        var open = c.f.publication(5);
        UUID active = c.f.active(open, 5);
        new TransactionTemplate(c.f.manager).execute(status -> {
            c.f.saved(active, 4, "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}", 1);
            return null;
        });
        var expected = fingerprints(c.f.db, c.f.schema);
        String database = "restore_" + UUID.randomUUID().toString().replace("-", "");
        String archive = "/tmp/" + database + ".dump";
        assertTrue(DB.isRunning());
        assertTrue(DB.getJdbcUrl().contains(DB.getMappedPort(5432).toString()));
        try {
            command(
                    "pg_dump",
                    "-U",
                    DB.getUsername(),
                    "-d",
                    DB.getDatabaseName(),
                    "-n",
                    c.f.schema,
                    "-Fc",
                    "-f",
                    archive);
            command("createdb", "-U", DB.getUsername(), database);
            command("pg_restore", "-U", DB.getUsername(), "--exit-on-error", "-d", database, archive);
            String restoredUrl = "jdbc:postgresql://" + DB.getHost() + ":" + DB.getMappedPort(5432) + "/" + database;
            var source = new DriverManagerDataSource(
                    restoredUrl + "?currentSchema=" + c.f.schema, DB.getUsername(), DB.getPassword());
            var restored = new JdbcTemplate(source);
            assertEquals(expected, fingerprints(restored, c.f.schema));
            Flyway.configure()
                    .dataSource(restoredUrl, DB.getUsername(), DB.getPassword())
                    .schemas(c.f.schema)
                    .defaultSchema(c.f.schema)
                    .load()
                    .validate();
            assertThrows(
                    RuntimeException.class,
                    () -> restored.update(
                            "UPDATE closure_request_members SET closure_cutoff_at=closure_cutoff_at+interval '1 second' WHERE closure_request_id=?",
                            request));
            DB.getDockerClient().restartContainerCmd(DB.getContainerId()).exec();
            // Docker may reassign ephemeral host ports across restart. Inspect the owned
            // container rather than reusing Testcontainers' cached pre-restart mapping.
            var bindings = DB.getDockerClient()
                    .inspectContainerCmd(DB.getContainerId())
                    .exec()
                    .getNetworkSettings()
                    .getPorts()
                    .getBindings();
            String mapped = bindings.get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0].getHostPortSpec();
            String restarted = "jdbc:postgresql://" + DB.getHost() + ":" + mapped + "/";
            source.setUrl(restarted + database + "?currentSchema=" + c.f.schema);
            c.f.source.setUrl(restarted + DB.getDatabaseName() + "?currentSchema=" + c.f.schema);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            boolean ready = false;
            while (System.nanoTime() < deadline) {
                try {
                    ready = restored.queryForObject("SELECT 1", Integer.class) == 1;
                    break;
                } catch (RuntimeException unavailable) {
                    Thread.sleep(50);
                }
            }
            assertTrue(ready, "Isolated PostgreSQL did not restart");
            assertEquals(expected, fingerprints(restored, c.f.schema));
            assertEquals(expected, fingerprints(c.f.db, c.f.schema));
            System.out.println(
                    "W3FINAL backup_restore_restart tables=" + expected.size() + " identities_and_content=equal");
        } finally {
            command("dropdb", "-U", DB.getUsername(), "--if-exists", database);
            command("rm", "-f", archive);
        }
    }

    static void command(String... args) throws Exception {
        assertTrue(DB.isRunning(), "Only the owned ephemeral container may be modified");
        var result = DB.execInContainer(args);
        assertEquals(0, result.getExitCode(), "Isolated database drill command failed: " + args[0]);
    }

    static Map<String, String> fingerprints(JdbcTemplate db, String schema) {
        var result = new TreeMap<String, String>();
        for (String table : db.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname=? ORDER BY tablename", String.class, schema)) {
            assertTrue(table.matches("[a-z_]+"));
            result.put(
                    table,
                    db.queryForObject(
                            "SELECT encode(sha256(convert_to(coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text,'UTF8')),'hex') FROM "
                                    + table + " t",
                            String.class));
        }
        return result;
    }

    @Test
    @Order(1)
    void v5WaitsForWriterDrainAndPreservesHistoricalChecksums() throws Exception {
        String schema = "accept_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target("4.1")
                .load()
                .migrate();
        var source = new DriverManagerDataSource(
                DB.getJdbcUrl() + "&currentSchema=" + schema, DB.getUsername(), DB.getPassword());
        var db = new JdbcTemplate(source);
        String checksums = db.queryForObject(
                "SELECT string_agg(checksum::text,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE success",
                String.class);
        String lsn = db.queryForObject("SELECT pg_current_wal_insert_lsn()::text", String.class);
        long bytes = db.queryForObject("SELECT pg_database_size(current_database())", Long.class);
        try (var held = source.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            held.setAutoCommit(false);
            held.createStatement().execute("SELECT 1 FROM publications");
            long start = System.nanoTime();
            var migration = pool.submit(() -> Flyway.configure()
                    .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("5")
                    .load()
                    .migrate());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean observed = false;
            while (System.nanoTime() < deadline) {
                observed = db.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE relation='publications'::regclass AND mode='AccessExclusiveLock' AND NOT granted)",
                        Boolean.class);
                if (observed) break;
                Thread.onSpinWait();
            }
            assertTrue(observed, "Did not observe V5's real table-lock wait");
            assertFalse(migration.isDone());
            long waited = System.nanoTime() - start;
            held.commit();
            assertEquals(1, migration.get(10, TimeUnit.SECONDS).migrationsExecuted);
            assertEquals(
                    checksums,
                    db.queryForObject(
                            "SELECT string_agg(checksum::text,',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version::numeric<=4.1",
                            String.class));
            System.out.printf(
                    "W3FINAL migration total_ms=%.3f observed_lock_wait_ms=%.3f wal_bytes=%s database_growth_bytes=%d%n",
                    (System.nanoTime() - start) / 1e6,
                    waited / 1e6,
                    db.queryForObject(
                            "SELECT pg_wal_lsn_diff(pg_current_wal_insert_lsn(),?::pg_lsn)::text", String.class, lsn),
                    db.queryForObject("SELECT pg_database_size(current_database())", Long.class) - bytes);
        }
    }

    @Test
    @Order(1)
    void nonOwnerRuntimeRoleCanFinalizeButCannotRewriteGuardsOrHistory() throws Exception {
        var f = new AtomicFinalizationIntegrationTest();
        f.setup();
        UUID attempt = f.active(f.publication(0), 0);
        String role = "runtime_" + UUID.randomUUID().toString().replace("-", "");
        f.db.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION");
        f.db.execute("GRANT USAGE ON SCHEMA " + f.schema + " TO " + role);
        f.db.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA " + f.schema + " TO " + role);
        var source = new DriverManagerDataSource(f.source.getUrl(), DB.getUsername(), DB.getPassword()) {
            @Override
            public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                connection.createStatement().execute("SET ROLE " + role);
                return connection;
            }
        };
        var db = new JdbcTemplate(source);
        assertEquals(role, db.queryForObject("SELECT current_user", String.class));
        var finalizer = new JdbcAttemptFinalization(db, new JdbcTransactionManager(source));
        var outcome = finalizer.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()));
        assertEquals("3.75", outcome.result().orElseThrow().earnedWeight());
        for (String attack : List.of(
                "ALTER TABLE attempts DISABLE TRIGGER ALL",
                "CREATE OR REPLACE FUNCTION assessment_time() RETURNS timestamptz LANGUAGE sql AS 'SELECT now()'",
                "TRUNCATE attempt_finalization_audit",
                "UPDATE grading_results SET earned_weight=0"))
            assertThrows(RuntimeException.class, () -> db.execute(attack));
        assertEquals(
                AttemptFinalization.Kind.REPLAY,
                finalizer
                        .submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()))
                        .kind());
    }

    @Test
    @Order(1)
    void actualBackendTerminationRollsBackPartialWorkAndSameKeyRecovers() throws Exception {
        var f = new AtomicFinalizationIntegrationTest();
        f.setup();
        UUID attempt = f.active(f.publication(0), 0);
        var source = f.source("terminate");
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var db = new JdbcTemplate(source) {
            @Override
            public int update(String sql, Object... args) {
                int count = super.update(sql, args);
                if (sql.startsWith("INSERT INTO grading_results")) {
                    reached.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return count;
            }
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> new JdbcAttemptFinalization(db, new JdbcTransactionManager(source))
                    .submit(f.manual(attempt, 0, GradingDocumentsTest.replacement())));
            try {
                assertTrue(reached.await(5, TimeUnit.SECONDS));
                assertEquals(
                        Boolean.TRUE,
                        f.db.queryForObject(
                                "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name=?",
                                Boolean.class,
                                f.schema + "_terminate"));
            } finally {
                release.countDown();
            }
            var failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(PublicationFailure.class, failure.getCause());
            assertEquals("PERSISTENCE_UNAVAILABLE", ((PublicationFailure) failure.getCause()).code());
        }
        f.unfinalized(attempt);
        assertEquals(
                AttemptFinalization.Kind.SUBMITTED,
                f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()))
                        .kind());
        assertEquals("1", f.scalar("SELECT count(*) FROM grading_results WHERE attempt_id=?", attempt));
    }

    @Test
    @Order(1)
    void lostCommitAcknowledgmentReturnsUnavailableThenReplaysCommittedIdentity() {
        var f = new AtomicFinalizationIntegrationTest();
        f.setup();
        UUID attempt = f.active(f.publication(0), 0);
        var lose = new AtomicBoolean(true);
        var source = new DriverManagerDataSource(f.source.getUrl(), DB.getUsername(), DB.getPassword()) {
            @Override
            public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                            try {
                                Object result = method.invoke(connection, args);
                                if (method.getName().equals("commit") && lose.compareAndSet(true, false))
                                    throw new SQLException("Isolated lost commit acknowledgment", "08006");
                                return result;
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };
        var core = new JdbcAttemptFinalization(new JdbcTemplate(source), new JdbcTransactionManager(source));
        f.failure(
                "PERSISTENCE_UNAVAILABLE", () -> core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement())));
        assertFalse(lose.get());
        assertEquals("SUBMITTED", f.scalar("SELECT state FROM attempts WHERE id=?", attempt));
        var replay = f.core.submit(f.manual(attempt, 0, GradingDocumentsTest.replacement()));
        assertEquals(AttemptFinalization.Kind.REPLAY, replay.kind());
        assertEquals(
                replay.result().orElseThrow().resultId().toString(),
                f.scalar("SELECT result_id FROM attempts WHERE id=?", attempt));
        assertEquals("1", f.scalar("SELECT count(*) FROM attempt_operations WHERE attempt_id=?", attempt));
    }
}
