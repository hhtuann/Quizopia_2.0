package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.persistence.JdbcAttemptPersistence;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.node.ObjectNode;

/** Real PostgreSQL17 DML/commit tests. These are storage fixtures, not future HTTP/grading workflows. */
@Testcontainers
class AttemptPersistenceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    String schema;
    final Instant started = Instant.parse("2026-01-01T00:01:00Z"), finalized = started.plusSeconds(2);
    final UUID student = UUID.randomUUID();
    final String digest = "0".repeat(64);

    @BeforeEach
    void migrate() {
        schema = "b1_" + UUID.randomUUID().toString().replace("-", "");
        flyway(null).migrate();
    }

    Flyway flyway(String target) {
        var f = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas(schema)
                .defaultSchema(schema);
        if (target != null) f.target(target);
        return f.load();
    }

    Connection connection() throws SQLException {
        var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        c.setSchema(schema);
        sql(c, "SET statement_timeout='5s'");
        sql(c, "SET lock_timeout='3s'");
        return c;
    }

    static void sql(Connection c, String query, Object... args) throws SQLException {
        try (var s = c.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            s.execute();
        }
    }

    static String scalar(Connection c, String query, Object... args) throws SQLException {
        try (var s = c.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }

    record PublicationSeed(UUID id, PublicationConfiguration configuration) {}

    PublicationSeed publication(
            Connection c, int duration, int max, boolean classAudience, boolean legacy, boolean open) throws Exception {
        var original = SnapshotFixtures.configuration();
        UUID id = UUID.randomUUID();
        var cfg = new PublicationConfiguration(
                "Title",
                null,
                original.quizId(),
                original.quizVersionId(),
                1,
                classAudience ? PublicationConfiguration.Audience.CLASS : PublicationConfiguration.Audience.PUBLIC,
                classAudience ? UUID.randomUUID() : null,
                original.startAt(),
                original.endAt(),
                duration,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                max);
        var config = (ObjectNode) SnapshotFixtures.JSON.valueToTree(cfg);
        if (legacy) config.remove("maxAttempts");
        String columns = legacy ? "" : " ,max_attempts";
        String values = legacy ? "" : " ," + max;
        sql(
                c,
                "INSERT INTO publications(id,owner_id,configuration,quiz_id,quiz_version_id,version_number,audience,classroom_id,start_at,end_at,duration_minutes,monitoring_choice,state,revision,private_locator,locator_digest,created_at"
                        + columns
                        + ") VALUES(?,?,?::jsonb,?,?,1,?,?,?::timestamptz,?::timestamptz,?,'DISABLED','DRAFT',0,?,?,?::timestamptz"
                        + values + ")",
                id,
                UUID.randomUUID(),
                config.toString(),
                cfg.quizId(),
                cfg.quizVersionId(),
                cfg.audience().name(),
                cfg.classroomId(),
                cfg.startAt().toString(),
                cfg.endAt().toString(),
                duration,
                classAudience ? null : "a".repeat(43),
                classAudience ? null : UUID.randomUUID().toString().replace("-", "") + "0".repeat(32),
                cfg.startAt().toString());
        if (open) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            sql(
                    c,
                    "INSERT INTO publication_snapshots VALUES(?,1,1,?::jsonb,?::timestamptz)",
                    id,
                    new String(
                            SnapshotFixtures.snapshot(cfg, "exact source 0001"),
                            java.nio.charset.StandardCharsets.UTF_8),
                    cfg.startAt().toString());
            sql(
                    c,
                    "UPDATE publications SET state='OPEN',snapshot_id=id,opened_at=?::timestamptz,opened_from_revision=revision,revision=revision+1 WHERE id=?",
                    cfg.startAt().toString(),
                    id);
            c.commit();
            c.setAutoCommit(auto);
        }
        return new PublicationSeed(id, cfg);
    }

    UUID attempt(Connection c, UUID publication, UUID owner, long ordinal, int duration) throws SQLException {
        UUID id = UUID.randomUUID();
        sql(
                c,
                "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,?,?,1,1,'ACTIVE',?,?::timestamptz,?::timestamptz)",
                id,
                publication,
                owner,
                ordinal,
                publication,
                duration,
                started.toString(),
                duration == 0 ? null : started.plusSeconds(duration * 60L).toString());
        return id;
    }

    void abandon(Connection c, UUID attempt, UUID pub, UUID owner) throws SQLException {
        sql(
                c,
                "INSERT INTO attempt_finalization_audit(attempt_id,publication_id,reason,actor_kind,actor_id,finalized_at,grading_policy_version) VALUES(?,?,'STUDENT_ABANDON','USER',?,?::timestamptz,1)",
                attempt,
                pub,
                owner.toString(),
                finalized.toString());
        sql(
                c,
                "UPDATE attempts SET state='ABANDONED',terminal_reason='STUDENT_ABANDON',finalized_at=?::timestamptz,revision=revision+1 WHERE id=?",
                finalized.toString(),
                attempt);
    }

    UUID submitted(Connection c, UUID a, UUID pub, BigDecimal earned) throws SQLException {
        UUID answers = UUID.randomUUID(), result = UUID.randomUUID();
        sql(
                c,
                "INSERT INTO attempt_answers VALUES(?,3,?::jsonb,1,?::timestamptz)",
                a,
                "{\"type\":\"TRUE_FALSE_MATRIX\",\"judgments\":{\"A\":false,\"B\":false,\"C\":false,\"D\":null}}",
                started.plusSeconds(1).toString());
        sql(
                c,
                "INSERT INTO attempt_answers VALUES(?,4,?::jsonb,1,?::timestamptz)",
                a,
                "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}",
                started.plusSeconds(1).toString());
        String document =
                "[{\"questionNumber\":1,\"answer\":{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":null}},{\"questionNumber\":2,\"answer\":{\"type\":\"MULTIPLE_CHOICE\",\"optionLabels\":[]}},{\"questionNumber\":3,\"answer\":{\"type\":\"TRUE_FALSE_MATRIX\",\"judgments\":{\"A\":false,\"B\":false,\"C\":false,\"D\":null}}},{\"questionNumber\":4,\"answer\":{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}}]";
        sql(
                c,
                "INSERT INTO submitted_answers VALUES(?,?,1,?::jsonb,encode(sha256(convert_to(assessment_canonical_json(?::jsonb),'UTF8')),'hex'),?::timestamptz)",
                answers,
                a,
                document,
                document,
                finalized.toString());
        sql(
                c,
                "INSERT INTO grading_results VALUES(?,?,?,1,?,?,4,?::timestamptz)",
                result,
                a,
                pub,
                answers,
                earned,
                finalized.toString());
        for (int q = 1; q <= 4; q++)
            sql(
                    c,
                    "INSERT INTO grading_question_results VALUES(?,?,?,1)",
                    result,
                    q,
                    q < 3 ? BigDecimal.ZERO : q == 3 ? new BigDecimal("0.75") : BigDecimal.ONE);
        sql(
                c,
                "INSERT INTO attempt_finalization_audit(attempt_id,publication_id,reason,actor_kind,actor_id,finalized_at,grading_policy_version,result_id) VALUES(?,?,'MANUAL_SUBMIT','USER',?,?::timestamptz,1,?)",
                a,
                pub,
                student.toString(),
                finalized.toString(),
                result);
        sql(
                c,
                "INSERT INTO attempt_operations(attempt_id,key_digest,action,request_digest,accepted_revision,acknowledged_at,response_status,response_body) VALUES(?,?,'SUBMIT',?,1,?::timestamptz,200,?::jsonb)",
                a,
                digest,
                digest,
                finalized.toString(),
                "{\"resultId\":\"" + result + "\"}");
        sql(
                c,
                "UPDATE attempts SET state='SUBMITTED',terminal_reason='MANUAL_SUBMIT',finalized_at=?::timestamptz,revision=1,submitted_answers_id=?,result_id=? WHERE id=?",
                finalized.toString(),
                answers,
                result,
                a);
        return result;
    }

    @Test
    void freshChainInstalledAndReapplicationDoesNothing() throws Exception {
        assertEquals(0, flyway(null).migrate().migrationsExecuted);
        try (var c = connection()) {
            assertEquals(
                    "2",
                    scalar(
                            c,
                            "SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname IN ('publications_owner_created_idx','publications_owner_state_created_idx')",
                            schema));
            assertEquals(
                    "8",
                    scalar(c, "SELECT max(version::numeric) FROM flyway_schema_history WHERE version IS NOT NULL"));
            assertEquals(
                    "8",
                    scalar(
                            c,
                            "SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name IN('attempts','attempt_answers','attempt_creation_operations','submitted_answers','grading_results','grading_question_results','attempt_operations','attempt_finalization_audit')",
                            schema));
            assertEquals(
                    "3",
                    scalar(
                            c,
                            "SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name IN('closure_requests','closure_request_members','assessment_notifications')",
                            schema));
            assertEquals(
                    "t",
                    scalar(
                            c,
                            "SELECT indisunique AND indpred IS NOT NULL FROM pg_index WHERE indexrelid='one_active_attempt_per_student'::regclass"));
            assertEquals(
                    "t",
                    scalar(
                            c,
                            "SELECT bool_and(tgdeferrable AND tginitdeferred) FROM pg_trigger WHERE tgname='terminal_consistency' AND tgrelid='attempts'::regclass"));
        }
    }

    @Test
    void legacyV2DraftAndOpenUpgradeWithoutJsonOrSnapshotRewrite() throws Exception {
        schema = "legacy_" + UUID.randomUUID().toString().replace("-", "");
        flyway("2").migrate();
        Map<UUID, String> configs = new LinkedHashMap<>(), snapshots = new LinkedHashMap<>();
        String checksums;
        try (var c = connection()) {
            for (boolean cls : new boolean[] {false, true})
                for (boolean open : new boolean[] {false, true}) {
                    var p = publication(c, 5, 1, cls, true, open);
                    configs.put(p.id(), scalar(c, "SELECT configuration::text FROM publications WHERE id=?", p.id()));
                    if (open)
                        snapshots.put(
                                p.id(),
                                scalar(
                                        c,
                                        "SELECT payload::text FROM publication_snapshots WHERE publication_id=?",
                                        p.id()));
                }
            checksums = scalar(
                    c,
                    "SELECT string_agg(checksum::text,',' ORDER BY version) FROM flyway_schema_history WHERE version IN('1','2')");
        }
        assertEquals(7, flyway(null).migrate().migrationsExecuted);
        try (var c = connection()) {
            assertEquals(
                    checksums,
                    scalar(
                            c,
                            "SELECT string_agg(checksum::text,',' ORDER BY version) FROM flyway_schema_history WHERE version IN('1','2')"));
            for (var e : configs.entrySet()) {
                assertEquals(
                        e.getValue(), scalar(c, "SELECT configuration::text FROM publications WHERE id=?", e.getKey()));
                assertEquals("1", scalar(c, "SELECT max_attempts FROM publications WHERE id=?", e.getKey()));
            }
            for (var e : snapshots.entrySet()) {
                assertEquals(
                        e.getValue(),
                        scalar(
                                c,
                                "SELECT payload::text FROM publication_snapshots WHERE publication_id=?",
                                e.getKey()));
                sql(
                        c,
                        "UPDATE publications SET end_at=end_at+interval '1 hour',configuration=jsonb_set(configuration,'{endAt}',to_jsonb((end_at+interval '1 hour')::text)),revision=revision+1 WHERE id=?",
                        e.getKey());
                assertEquals(
                        "f",
                        scalar(
                                c,
                                "SELECT jsonb_exists(configuration,'maxAttempts') FROM publications WHERE id=?",
                                e.getKey()));
                assertEquals(
                        e.getValue(),
                        scalar(
                                c,
                                "SELECT payload::text FROM publication_snapshots WHERE publication_id=?",
                                e.getKey()));
            }
        }
    }

    @Test
    void configurationAndOpenGuardsRejectRawDmlBypasses() throws Exception {
        try (var c = connection()) {
            for (int count : new int[] {0, 1, 10})
                for (int duration : new int[] {0, 5, 240}) publication(c, duration, count, false, false, true);
            var p = publication(c, 0, 0, true, false, true);
            for (String change : List.of(
                    "max_attempts=1,configuration=jsonb_set(configuration,'{maxAttempts}','1')",
                    "duration_minutes=5,configuration=jsonb_set(configuration,'{durationMinutes}','5')",
                    "state='CLOSED'",
                    "closed_at=now()"))
                assertThrows(
                        SQLException.class,
                        () -> sql(c, "UPDATE publications SET " + change + ",revision=revision+1 WHERE id=?", p.id()));
            var bounded = publication(c, 5, 1, false, false, true);
            for (int duration : new int[] {0, 4})
                assertThrows(
                        SQLException.class,
                        () -> sql(
                                c,
                                "UPDATE publications SET duration_minutes=?,configuration=jsonb_set(configuration,'{durationMinutes}',to_jsonb(?::integer)),revision=revision+1 WHERE id=?",
                                duration,
                                duration,
                                bounded.id()));
            var draft = publication(c, 5, 1, true, false, false);
            for (String value : List.of("null", "-1", "11", "1.5", "\"1\""))
                assertThrows(
                        SQLException.class,
                        () -> sql(
                                c,
                                "UPDATE publications SET configuration=jsonb_set(configuration,'{maxAttempts}',?::jsonb),revision=revision+1 WHERE id=?",
                                value,
                                draft.id()));
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "UPDATE publications SET duration_minutes=0,monitoring_choice='ENABLED',configuration=jsonb_set(jsonb_set(configuration,'{durationMinutes}','0'),'{monitoringChoice}','\"ENABLED\"'),revision=revision+1 WHERE id=?",
                            draft.id()));
            sql(
                    c,
                    "UPDATE publications SET duration_minutes=0,monitoring_choice='UNCONFIGURED',configuration=jsonb_set(jsonb_set(configuration,'{durationMinutes}','0'),'{monitoringChoice}','\"UNCONFIGURED\"'),revision=revision+1 WHERE id=?",
                    draft.id());
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "UPDATE publications SET state='OPEN',snapshot_id=id,opened_at=now(),opened_from_revision=revision,revision=revision+1 WHERE id=?",
                            draft.id()));
        }
    }

    @Test
    void nullableDeadlineAndSnapshotPinsAndLocalReferences() throws Exception {
        try (var c = connection()) {
            var p = publication(c, 0, 0, false, false, true);
            UUID a = attempt(c, p.id(), student, 1, 0);
            assertEquals("t", scalar(c, "SELECT deadline_at IS NULL FROM attempts WHERE id=?", a));
            assertThrows(SQLException.class, () -> attempt(c, p.id(), UUID.randomUUID(), 1, 5));
            assertThrows(SQLException.class, () -> attempt(c, p.id(), student, 1, 0));
            assertThrows(SQLException.class, () -> attempt(c, p.id(), student, 2, 0));
            assertThrows(SQLException.class, () -> sql(c, "UPDATE attempts SET revision=2 WHERE id=?", a));
            assertThrows(
                    SQLException.class,
                    () -> sql(c, "UPDATE attempts SET student_id=?,revision=1 WHERE id=?", UUID.randomUUID(), a));
            var other = publication(c, 5, 1, false, false, true);
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,1,?,1,1,'ACTIVE',5,?::timestamptz,?::timestamptz)",
                            UUID.randomUUID(),
                            other.id(),
                            student,
                            p.id(),
                            started.toString(),
                            started.plusSeconds(300).toString()));
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at) VALUES(?,?,?,1,?,2,1,'ACTIVE',0,?::timestamptz)",
                            UUID.randomUUID(),
                            p.id(),
                            UUID.randomUUID(),
                            p.id(),
                            started.toString()));
            assertThrows(
                    SQLException.class, () -> sql(c, "UPDATE attempts SET deadline_at=now(),revision=1 WHERE id=?", a));
        }
    }

    @Test
    void boundedDeadlineMustEqualPinnedStartPlusDuration() throws Exception {
        try (var c = connection()) {
            var p = publication(c, 5, 0, false, false, true);
            for (String deadline : Arrays.asList(null, started.plusSeconds(299).toString(), "infinity")) {
                assertThrows(
                        SQLException.class,
                        () -> sql(
                                c,
                                "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,1,?,1,1,'ACTIVE',5,?::timestamptz,?::timestamptz)",
                                UUID.randomUUID(),
                                p.id(),
                                student,
                                p.id(),
                                started.toString(),
                                deadline));
            }
            UUID a = attempt(c, p.id(), student, 1, 5);
            assertEquals(
                    "t", scalar(c, "SELECT deadline_at=started_at+interval '5 minutes' FROM attempts WHERE id=?", a));
            sql(
                    c,
                    "UPDATE publications SET duration_minutes=10,configuration=jsonb_set(configuration,'{durationMinutes}','10'),revision=revision+1 WHERE id=?",
                    p.id());
            assertEquals("5", scalar(c, "SELECT duration_minutes FROM attempts WHERE id=?", a));
            assertEquals(
                    "t", scalar(c, "SELECT deadline_at=started_at+interval '5 minutes' FROM attempts WHERE id=?", a));
        }
    }

    @Test
    void resultReferencesCannotCrossAttemptsOrPublications() throws Exception {
        try (var c = connection()) {
            var p = publication(c, 5, 0, false, false, true);
            UUID a = attempt(c, p.id(), student, 1, 5);
            c.setAutoCommit(false);
            UUID r = submitted(c, a, p.id(), new BigDecimal("1.75"));
            c.commit();
            c.setAutoCommit(true);
            UUID answers = UUID.fromString(scalar(c, "SELECT submitted_answers_id FROM attempts WHERE id=?", a));
            var other = publication(c, 5, 0, false, false, true);
            UUID b = attempt(c, other.id(), student, 1, 5);
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "INSERT INTO grading_results VALUES(?,?,?,1,?,0,4,?::timestamptz)",
                            UUID.randomUUID(),
                            b,
                            other.id(),
                            answers,
                            finalized.toString()));
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "INSERT INTO grading_results VALUES(?,?,?,1,?,0,4,?::timestamptz)",
                            UUID.randomUUID(),
                            b,
                            p.id(),
                            answers,
                            finalized.toString()));
            c.setAutoCommit(false);
            sql(
                    c,
                    "UPDATE attempts SET state='SUBMITTED',terminal_reason='MANUAL_SUBMIT',finalized_at=?::timestamptz,revision=1,submitted_answers_id=?,result_id=? WHERE id=?",
                    finalized.toString(),
                    answers,
                    r,
                    b);
            assertThrows(SQLException.class, c::commit);
            c.rollback();
            assertEquals("ACTIVE", scalar(c, "SELECT state FROM attempts WHERE id=?", b));
            assertEquals("1", scalar(c, "SELECT count(*) FROM grading_results"));
        }
    }

    @Test
    void validAtomicSubmissionAndReadAdapterAndImmutableHistory() throws Exception {
        try (var c = connection()) {
            var p = publication(c, 5, 0, false, false, true);
            UUID a = attempt(c, p.id(), student, 1, 5);
            sql(
                    c,
                    "INSERT INTO attempt_creation_operations VALUES(?,?,?,'START',?,?,?::timestamptz)",
                    p.id(),
                    student,
                    digest,
                    digest,
                    a,
                    started.toString());
            c.setAutoCommit(false);
            UUID result = submitted(c, a, p.id(), new BigDecimal("1.75"));
            c.commit();
            c.setAutoCommit(true);
            var store = new JdbcAttemptPersistence(new JdbcTemplate(new SingleConnectionDataSource(c, true)));
            assertEquals("SUBMITTED", store.find(a).orElseThrow().state().name());
            assertEquals(
                    0,
                    new BigDecimal("1.75")
                            .compareTo(store.result(a).orElseThrow().earnedWeight()));
            assertEquals(result, store.result(a).orElseThrow().id());
            assertEquals(1, store.receipt(a, digest).orElseThrow().acceptedRevision());
            assertFalse(store.find(a).orElseThrow().toString().contains(student.toString()));
            assertFalse(store.receipt(a, digest).orElseThrow().toString().contains(result.toString()));
            assertTrue(store.find(UUID.randomUUID()).isEmpty());
            for (String table : List.of(
                    "attempts",
                    "submitted_answers",
                    "grading_results",
                    "grading_question_results",
                    "attempt_operations",
                    "attempt_creation_operations",
                    "attempt_finalization_audit")) {
                assertThrows(SQLException.class, () -> sql(c, "DELETE FROM " + table));
                assertThrows(SQLException.class, () -> sql(c, "TRUNCATE " + table + " CASCADE"));
            }
            assertThrows(SQLException.class, () -> sql(c, "UPDATE attempts SET revision=revision+1 WHERE id=?", a));
            assertThrows(
                    SQLException.class, () -> sql(c, "UPDATE attempt_answers SET answer='{}' WHERE attempt_id=?", a));
            assertThrows(
                    SQLException.class, () -> sql(c, "UPDATE grading_results SET earned_weight=0 WHERE id=?", result));
            assertThrows(
                    SQLException.class,
                    () -> sql(c, "UPDATE submitted_answers SET answer_digest=? WHERE attempt_id=?", digest, a));
        }
    }

    @Test
    void rejectedPartialOrInconsistentFinalizationRollsBackEveryChild() throws Exception {
        try (var c = connection()) {
            var p = publication(c, 5, 0, false, false, true);
            UUID a = attempt(c, p.id(), student, 1, 5);
            c.setAutoCommit(false);
            submitted(c, a, p.id(), BigDecimal.ZERO);
            assertThrows(SQLException.class, c::commit);
            c.rollback();
            c.setAutoCommit(true);
            assertEquals("ACTIVE", scalar(c, "SELECT state FROM attempts WHERE id=?", a));
            assertEquals("0", scalar(c, "SELECT count(*) FROM grading_results WHERE attempt_id=?", a));
            assertEquals("0", scalar(c, "SELECT count(*) FROM attempt_answers WHERE attempt_id=?", a));
            c.setAutoCommit(false);
            sql(
                    c,
                    "UPDATE attempts SET state='ABANDONED',terminal_reason='STUDENT_ABANDON',finalized_at=?::timestamptz,revision=1 WHERE id=?",
                    finalized.toString(),
                    a);
            assertThrows(SQLException.class, c::commit);
            c.rollback();
            c.setAutoCommit(true);
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "INSERT INTO grading_results VALUES(?,?,?,1,?,?,4,now())",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            p.id(),
                            UUID.randomUUID(),
                            BigDecimal.ZERO));
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "UPDATE attempts SET state='SUBMITTED',terminal_reason='TEACHER_NOTICE_CUTOFF',finalized_at=now(),revision=1,submitted_answers_id=?,result_id=? WHERE id=?",
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            a));
        }
    }

    @Test
    void typedAnswersCannotEscapeSnapshotOrRevisionValidation() throws Exception {
        try (var c = connection()) {
            var p = publication(c, 5, 0, false, false, true);
            UUID a = attempt(c, p.id(), student, 1, 5);
            for (String invalid : List.of(
                    "{\"type\":\"NUMERIC_FILL\",\"value\":\"2.5\"}",
                    "{\"type\":\"NUMERIC_FILL\",\"value\":25}",
                    "{\"type\":\"NUMERIC_FILL\",\"value\":\"2.50\",\"correct\":true}",
                    "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}"))
                assertThrows(
                        SQLException.class,
                        () -> sql(
                                c,
                                "INSERT INTO attempt_answers VALUES(?,4,?::jsonb,1,?::timestamptz)",
                                a,
                                invalid,
                                started.plusSeconds(1).toString()));
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "INSERT INTO attempt_answers VALUES(?,5,?::jsonb,1,?::timestamptz)",
                            a,
                            "{\"type\":\"NUMERIC_FILL\",\"value\":null}",
                            started.toString()));
            // Valid answer without the same transaction's revision increment must not commit.
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "INSERT INTO attempt_answers VALUES(?,4,?::jsonb,1,?::timestamptz)",
                            a,
                            "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}",
                            started.plusSeconds(1).toString()));
            assertEquals("0", scalar(c, "SELECT count(*) FROM attempt_answers WHERE attempt_id=?", a));
        }
    }

    @Test
    void answerAndReceiptCannotReuseAnAlreadyCommittedRevision() throws Exception {
        try (var c = connection()) {
            var p = publication(c, 5, 0, false, false, true);
            UUID a = attempt(c, p.id(), student, 1, 5);
            c.setAutoCommit(false);
            sql(c, "UPDATE attempts SET revision=1 WHERE id=?", a);
            sql(
                    c,
                    "INSERT INTO attempt_answers VALUES(?,4,?::jsonb,1,?::timestamptz)",
                    a,
                    "{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}",
                    started.plusSeconds(1).toString());
            c.commit();
            c.setAutoCommit(true);
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "UPDATE attempt_answers SET answer=?::jsonb WHERE attempt_id=? AND question_number=4",
                            "{\"type\":\"NUMERIC_FILL\",\"value\":\"0002\"}",
                            a));
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "INSERT INTO attempt_answers VALUES(?,1,?::jsonb,1,?::timestamptz)",
                            a,
                            "{\"type\":\"SINGLE_CHOICE\",\"optionLabel\":\"A\"}",
                            started.plusSeconds(1).toString()));
            assertThrows(
                    SQLException.class,
                    () -> sql(
                            c,
                            "INSERT INTO attempt_operations(attempt_id,key_digest,action,request_digest,question_number,accepted_revision,acknowledged_at,response_status,response_body) VALUES(?,?,'SAVE',?,4,1,?::timestamptz,200,'{}')",
                            a,
                            digest,
                            digest,
                            started.plusSeconds(1).toString()));
            assertEquals("0001", scalar(c, "SELECT answer->>'value' FROM attempt_answers WHERE attempt_id=?", a));
            assertEquals("1", scalar(c, "SELECT revision FROM attempts WHERE id=?", a));
            c.setAutoCommit(false);
            sql(
                    c,
                    "UPDATE attempt_answers SET answer=?::jsonb,saved_revision=2 WHERE attempt_id=? AND question_number=4",
                    "{\"type\":\"NUMERIC_FILL\",\"value\":\"0002\"}",
                    a);
            sql(c, "UPDATE attempts SET revision=2 WHERE id=?", a);
            c.commit();
            assertEquals("0002", scalar(c, "SELECT answer->>'value' FROM attempt_answers WHERE attempt_id=?", a));
        }
    }

    @Test
    void invalidLegacyUpgradeFailsAtomicallyWithoutRepair() throws Exception {
        schema = "bad_" + UUID.randomUUID().toString().replace("-", "");
        flyway("2").migrate();
        UUID id;
        try (var c = connection()) {
            id = publication(c, 5, 1, false, true, false).id();
            String constraint = scalar(
                    c,
                    "SELECT conname FROM pg_constraint WHERE conrelid='publications'::regclass AND conkey=ARRAY[(SELECT attnum FROM pg_attribute WHERE attrelid='publications'::regclass AND attname='duration_minutes')]::smallint[]");
            sql(c, "ALTER TABLE publications DROP CONSTRAINT " + constraint);
            sql(
                    c,
                    "UPDATE publications SET duration_minutes=4,configuration=jsonb_set(configuration,'{durationMinutes}','4'),revision=revision+1 WHERE id=?",
                    id);
        }
        assertThrows(
                org.flywaydb.core.api.FlywayException.class, () -> flyway(null).migrate());
        try (var c = connection()) {
            assertEquals("4", scalar(c, "SELECT duration_minutes FROM publications WHERE id=?", id));
            assertEquals(
                    "0",
                    scalar(
                            c,
                            "SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='publications' AND column_name='max_attempts'",
                            schema));
            assertEquals(
                    "2", scalar(c, "SELECT count(*) FROM flyway_schema_history WHERE version IN('1','2') AND success"));
        }
    }

    void awaitParentWait(Connection observer, String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if ("1"
                    .equals(scalar(
                            observer,
                            "SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
                            name))) return;
            Thread.onSpinWait();
        }
        fail("Second connection never waited on the parent lock");
    }

    @Test
    void concurrentActiveAndOrdinalCreationSerializesOnPublication() throws Exception {
        try (var first = connection();
                var observer = connection()) {
            var p = publication(first, 0, 0, false, false, true);
            first.setAutoCommit(false);
            attempt(first, p.id(), student, 1, 0);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            CountDownLatch entered = new CountDownLatch(1);
            String name = "active_" + UUID.randomUUID();
            try {
                Future<String> losing = pool.submit(() -> {
                    try (var second = connection()) {
                        sql(second, "SET application_name='" + name + "'");
                        entered.countDown();
                        try {
                            attempt(second, p.id(), student, 1, 0);
                            return "COMMITTED";
                        } catch (SQLException e) {
                            return e.getSQLState();
                        }
                    }
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                awaitParentWait(observer, name);
                first.commit();
                assertEquals("23514", losing.get(4, TimeUnit.SECONDS));
                assertEquals("1", scalar(observer, "SELECT count(*) FROM attempts WHERE publication_id=?", p.id()));
            } finally {
                first.rollback();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void finiteEntitlementIncludesAbandonedAndCannotRacePastMaximum() throws Exception {
        try (var first = connection();
                var observer = connection()) {
            var p = publication(first, 5, 1, false, false, true);
            first.setAutoCommit(false);
            UUID a = attempt(first, p.id(), student, 1, 5);
            abandon(first, a, p.id(), student);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            CountDownLatch entered = new CountDownLatch(1);
            String name = "finite_" + UUID.randomUUID();
            try {
                Future<String> losing = pool.submit(() -> {
                    try (var second = connection()) {
                        sql(second, "SET application_name='" + name + "'");
                        entered.countDown();
                        try {
                            attempt(second, p.id(), student, 2, 5);
                            return "COMMITTED";
                        } catch (SQLException e) {
                            return e.getSQLState();
                        }
                    }
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                awaitParentWait(observer, name);
                first.commit();
                assertEquals("23514", losing.get(4, TimeUnit.SECONDS));
                assertEquals("1", scalar(observer, "SELECT count(*) FROM attempts WHERE publication_id=?", p.id()));
                assertEquals("ABANDONED", scalar(observer, "SELECT state FROM attempts WHERE id=?", a));
            } finally {
                first.rollback();
                pool.shutdownNow();
            }
        }
    }

    @Test
    void unlimitedOrdinalsAdvanceWithoutReusingTerminalIdentity() throws Exception {
        try (var c = connection()) {
            var p = publication(c, 0, 0, false, false, true);
            UUID a = attempt(c, p.id(), student, 1, 0);
            c.setAutoCommit(false);
            abandon(c, a, p.id(), student);
            c.commit();
            c.setAutoCommit(true);
            UUID b = attempt(c, p.id(), student, 2, 0);
            assertNotEquals(a, b);
            assertEquals("2", scalar(c, "SELECT ordinal FROM attempts WHERE id=?", b));
        }
    }
}
