package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.configuration.*;
import com.quizopia.assessment.infrastructure.*;
import com.quizopia.assessment.persistence.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class TeacherAssessmentIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AtomicFinalizationIntegrationTest f;
    TeacherAssessmentQueries queries;
    TeacherAssessmentService service;
    ClosureStore.Teacher teacher;
    PublicationSources sources;
    StudentProfiles profiles;
    SensitiveAssessmentPolicy permit = new SensitiveAssessmentPolicy() {
        public Instant expiresAt(Instant time) {
            return time.plusSeconds(3600);
        }

        public boolean disclosureAvailable() {
            return true;
        }
    };

    @BeforeEach
    void setup() {
        f = new AtomicFinalizationIntegrationTest();
        f.setup();
        queries = queries(permit);
        sources = mock(PublicationSources.class);
        profiles = mock(StudentProfiles.class);
        when(profiles.displayNames(any())).thenReturn(Map.of());
        service = new TeacherAssessmentService(queries, sources, profiles);
    }

    TeacherAssessmentQueries queries(SensitiveAssessmentPolicy policy) {
        return new TeacherAssessmentQueries(
                f.db,
                f.manager,
                AssessmentTransactionProperties.defaults(),
                new ClosureCursor(new ClosureProperties(
                        false,
                        false,
                        false,
                        false,
                        50,
                        50,
                        "v1",
                        Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])))),
                policy);
    }

    AtomicFinalizationIntegrationTest.Seed publication() {
        var p = f.publication(0);
        teacher = new ClosureStore.Teacher(
                UUID.fromString(f.scalar("SELECT owner_id FROM publications WHERE id=?", p.publication())),
                f.now().plusSeconds(3600),
                "synthetic-user-evidence");
        return p;
    }

    void failure(String code, Runnable work) {
        f.failure(code, work);
    }

    UUID submitted(AtomicFinalizationIntegrationTest.Seed p) {
        var a = f.active(p, 0);
        f.core.submit(f.manual(a, 0, GradingDocumentsTest.replacement()));
        return a;
    }

    @Test
    void emptyOverviewHasTwentyZerosAndNoInventedAverage() {
        var p = publication();
        var summary = service.overview(teacher, p.publication());
        assertEquals("0", summary.participatedStudentCount());
        assertNull(summary.bestScore());
        assertNull(summary.averageBestScore());
        assertEquals(20, summary.scoreBins().size());
        assertTrue(summary.scoreBins().stream().allMatch(b -> b.count().equals("0")));
        assertTrue(summary.scoreBins().getFirst().lowerInclusive());
        assertFalse(summary.scoreBins().get(1).lowerInclusive());
        assertTrue(summary.scoreBins().stream().allMatch(b -> b.upperInclusive()));
    }

    @Test
    void distinctOverlappingCountersSeparateBestLatestAndStudentAverage() {
        var p = publication();
        UUID student = f.student;
        UUID first = submitted(p); // 3.75 / 4
        var second = f.active(p, 0);
        f.core.submit(f.manual(second, 0, null)); // 0 / 4
        var active = f.active(p, 0);
        f.student = UUID.randomUUID();
        var abandoned = f.active(p, 0);
        new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core)
                .abandon(
                        new StudentAttemptStore.Actor(f.student, f.now().plusSeconds(3600)),
                        abandoned,
                        0,
                        "b".repeat(64));
        var overview = service.overview(teacher, p.publication());
        assertEquals("2", overview.participatedStudentCount());
        assertEquals("1", overview.activeStudentCount());
        assertEquals("1", overview.submittedStudentCount());
        assertEquals("1", overview.gradedStudentCount());
        assertEquals("9.38", overview.bestScore().scoreDisplay());
        assertEquals("1", overview.scoreBins().get(18).count());
        var page = service.students(
                teacher,
                p.publication(),
                new TeacherAssessmentQueries.Filter(20, null, student, null, "LATEST", "DESC"));
        var row = page.items().getFirst();
        assertEquals("3", row.attemptCount());
        assertEquals("2", row.gradedAttemptCount());
        assertEquals(active, row.latestAttempt().attemptId());
        assertNull(row.latestAttempt().result());
        assertEquals(second, row.latestGradedResult().attemptId());
        assertEquals(first, row.bestResult().attemptId());
        assertEquals("3.75", row.averageResult().sumEarnedWeight());
        assertEquals("8", row.averageResult().sumPossibleWeight());
        assertEquals("4.69", row.averageResult().scoreDisplay());
        var history = service.history(teacher, p.publication(), student, 2, null);
        assertEquals(
                List.of(active, second),
                history.items().stream()
                        .map(TeacherAssessmentViews.Attempt::attemptId)
                        .toList());
        assertNotNull(history.nextCursor());
        assertEquals(
                first,
                service.history(teacher, p.publication(), student, 2, history.nextCursor())
                        .items()
                        .getFirst()
                        .attemptId());
        assertFalse(SnapshotFixtures.JSON.writeValueAsString(page).contains("correctAnswer"));
        assertFalse(SnapshotFixtures.JSON.writeValueAsString(history).contains("sourceSnapshot"));
    }

    @Test
    void wholePopulationBinsAndCursorPagesCoverMoreThanOneHundredStudents() {
        var p = f.publication(0, root -> {
            var questions = SnapshotFixtures.JSON.createArrayNode();
            var template = root.path("structuredContent").path("questions").get(0);
            for (int n = 1; n <= 20; n++) {
                var q = (tools.jackson.databind.node.ObjectNode) template.deepCopy();
                q.put("number", n);
                questions.add(q);
            }
            ((tools.jackson.databind.node.ObjectNode) root.path("structuredContent")).set("questions", questions);
        });
        teacher = new ClosureStore.Teacher(
                UUID.fromString(f.scalar("SELECT owner_id FROM publications WHERE id=?", p.publication())),
                f.now().plusSeconds(3600),
                "synthetic");
        long[] expected = new long[20];
        Instant start = f.now().minusSeconds(2);
        for (int n = 0; n < 121; n++) {
            f.student = UUID.randomUUID();
            var attempt = f.attempt(p, 0, start);
            var answers = SnapshotFixtures.JSON.createArrayNode();
            int correct = n % 21;
            expected[Math.max(0, correct - 1)]++;
            for (int q = 1; q <= 20; q++)
                answers.add(SnapshotFixtures.JSON.valueToTree(Map.of(
                        "questionNumber",
                        q,
                        "answer",
                        Map.of("type", "SINGLE_CHOICE", "optionLabel", q <= correct ? "B" : "A"))));
            f.core.submit(f.manual(attempt, 0, answers.toString()));
        }
        var overview = service.overview(teacher, p.publication());
        assertEquals("121", overview.gradedStudentCount());
        for (int i = 0; i < 20; i++)
            assertEquals(Long.toString(expected[i]), overview.scoreBins().get(i).count(), "bin " + i);
        var seen = new HashSet<UUID>();
        String cursor = null;
        int calls = 0;
        do {
            var page = service.students(
                    teacher,
                    p.publication(),
                    new TeacherAssessmentQueries.Filter(50, cursor, null, "SUBMITTED", "BEST_SCORE", "ASC"));
            assertTrue(page.items().size() <= 50);
            for (var row : page.items()) assertTrue(seen.add(row.studentId()));
            cursor = page.nextCursor();
            calls++;
        } while (cursor != null);
        assertEquals(121, seen.size());
        assertEquals(3, calls);
        verify(profiles, times(3)).displayNames(argThat(ids -> ids.size() <= 50));
        assertEquals(
                overview.gradedStudentCount(),
                service.overview(teacher, p.publication()).gradedStudentCount());
    }

    @Test
    void cursorsCannotCrossActorPublicationFilterOrSort() {
        var p = publication();
        submitted(p);
        f.student = UUID.randomUUID();
        submitted(p);
        var page = service.students(
                teacher, p.publication(), new TeacherAssessmentQueries.Filter(1, null, null, null, "LATEST", "DESC"));
        assertNotNull(page.nextCursor());
        failure(
                "INVALID_REQUEST",
                () -> service.students(
                        teacher,
                        p.publication(),
                        new TeacherAssessmentQueries.Filter(1, page.nextCursor(), null, "ACTIVE", "LATEST", "DESC")));
        failure(
                "INVALID_REQUEST",
                () -> service.students(
                        teacher,
                        p.publication(),
                        new TeacherAssessmentQueries.Filter(1, page.nextCursor(), null, null, "BEST_SCORE", "DESC")));
        failure(
                "INVALID_REQUEST",
                () -> service.students(
                        teacher,
                        p.publication(),
                        new TeacherAssessmentQueries.Filter(1, page.nextCursor() + "x", null, null, "LATEST", "DESC")));
        var foreign = new ClosureStore.Teacher(UUID.randomUUID(), teacher.expiresAt(), "foreign");
        clearInvocations(profiles);
        failure("PUBLICATION_NOT_FOUND", () -> service.overview(foreign, p.publication()));
        verifyNoInteractions(profiles);
    }

    @Test
    void teacherReviewIsSubmittedOnlyOwnerScopedAndUsesImmutableAnswersWhileOpen() {
        var p = publication();
        var active = f.active(p, 0);
        failure("REVIEW_NOT_AVAILABLE", () -> service.review(teacher, p.publication(), active));
        f.core.submit(f.manual(active, 0, GradingDocumentsTest.replacement()));
        var view = service.review(teacher, p.publication(), active);
        assertEquals("SUBMITTED", view.state());
        assertEquals("93.75", view.result().percentageDisplay());
        assertEquals(4, view.questions().size());
        assertEquals("B", ((AttemptViews.Single) view.questions().getFirst().correctAnswer()).optionLabel());
        assertEquals("1.00", ((AttemptViews.Numeric) view.questions().get(3).answer()).value());
        assertEquals("1", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts"));
        assertFalse(SnapshotFixtures.JSON.writeValueAsString(view).contains("sourceSnapshot"));
        assertEquals("OPEN", f.scalar("SELECT state FROM publications WHERE id=?", p.publication()));
        JdbcStudentAttemptStore.requireReview("CLOSED", "SUBMITTED", true);
        failure("REVIEW_NOT_AVAILABLE", () -> JdbcStudentAttemptStore.requireReview("OPEN", "SUBMITTED", true));
        failure("ATTEMPT_NOT_FOUND", () -> service.review(teacher, p.publication(), UUID.randomUUID()));
        var other = publication();
        failure("ATTEMPT_NOT_FOUND", () -> service.review(teacher, other.publication(), active));
    }

    @Test
    void pendingPolicyAndReceiptFailureReturnNoSensitiveReview() {
        var p = publication();
        var a = submitted(p);
        var closed = new TeacherAssessmentService(queries(new SensitiveAssessmentPolicy.Pending()), sources, profiles);
        failure("SENSITIVE_POLICY_PENDING", () -> closed.review(teacher, p.publication(), a));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts"));
        f.db.execute(
                "CREATE FUNCTION fail_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'receipt unavailable'; END $$");
        f.db.execute(
                "CREATE TRIGGER fail_receipt BEFORE INSERT ON assessment_sensitive_read_receipts FOR EACH ROW EXECUTE FUNCTION fail_receipt()");
        failure("PERSISTENCE_UNAVAILABLE", () -> service.review(teacher, p.publication(), a));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts"));
    }

    @Test
    void revocationFenceAndExpiredActorDenyBeforeAnyDisclosure() {
        var p = publication();
        var a = submitted(p);
        var stale = new ClosureStore.Teacher(teacher.id(), f.now().minusSeconds(1), "stale");
        failure("UNAUTHENTICATED", () -> service.overview(stale, p.publication()));
        f.db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,operation_id,revoked_at,source) VALUES(?,?,clock_timestamp(),'COMMAND')",
                p.configuration().quizId(),
                UUID.randomUUID());
        failure("ASSESSMENT_REMOVED", () -> service.overview(teacher, p.publication()));
        failure("ASSESSMENT_REMOVED", () -> service.review(teacher, p.publication(), a));
        assertEquals("0", f.scalar("SELECT count(*) FROM assessment_sensitive_read_receipts"));
    }

    @Test
    void classAuthorityIsRequiredBeforeStatisticsHistoryReviewOrProfileLookup() {
        Instant time = f.now();
        UUID classroom = UUID.randomUUID(), owner = UUID.randomUUID();
        var config = new com.quizopia.assessment.domain.PublicationConfiguration(
                "CLASS authority",
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                com.quizopia.assessment.domain.PublicationConfiguration.Audience.CLASS,
                classroom,
                time.minusSeconds(60),
                time.plusSeconds(600),
                5,
                com.quizopia.assessment.domain.PublicationConfiguration.MonitoringChoice.DISABLED,
                0);
        var store = new JdbcPublicationStore(f.db, f.manager, Clock.systemUTC());
        var p = store.create(owner, UUID.randomUUID().toString(), config);
        store.open(
                owner,
                p.id(),
                0,
                new String(
                        SnapshotFixtures.snapshot(config, "synthetic class source"),
                        java.nio.charset.StandardCharsets.UTF_8));
        var actor = new ClosureStore.Teacher(owner, time.plusSeconds(600), "class-owner-evidence");
        doThrow(new PublicationFailure(404, "CLASSROOM_NOT_ACCESSIBLE"))
                .when(sources)
                .teacher(classroom, owner, "class-owner-evidence");
        failure("CLASSROOM_NOT_ACCESSIBLE", () -> service.overview(actor, p.id()));
        failure(
                "CLASSROOM_NOT_ACCESSIBLE",
                () -> service.students(
                        actor, p.id(), new TeacherAssessmentQueries.Filter(20, null, null, null, "LATEST", "DESC")));
        verifyNoInteractions(profiles);
        doNothing().when(sources).teacher(classroom, owner, "class-owner-evidence");
        assertEquals("0", service.overview(actor, p.id()).participatedStudentCount());
    }

    @Test
    void abandonedHasNoTeacherReviewAndPendingPolicyIsTruthfulInHistory() {
        var p = publication();
        UUID student = f.student;
        var a = f.active(p, 0);
        new JdbcStudentAttemptStore(f.db, f.manager, AssessmentTransactionProperties.defaults(), f.core)
                .abandon(new StudentAttemptStore.Actor(student, f.now().plusSeconds(60)), a, 0, "c".repeat(64));
        failure("REVIEW_NOT_AVAILABLE", () -> service.review(teacher, p.publication(), a));
        var pending = new TeacherAssessmentService(queries(new SensitiveAssessmentPolicy.Pending()), sources, profiles);
        assertFalse(pending.history(teacher, p.publication(), student, 20, null)
                .items()
                .getFirst()
                .teacherReviewAvailable());
    }

    @Test
    void upgradeFromV11PreservesAllPriorChecksumsAndIsIdempotent() {
        String schema = "t2_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var baseline = org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target("11")
                .load();
        baseline.migrate();
        var before = f.db.queryForList("SELECT version,checksum FROM " + schema
                + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        var current = org.flywaydb.core.Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .load();
        assertEquals(2, current.migrate().migrationsExecuted);
        assertEquals(
                before,
                f.db.queryForList(
                        "SELECT version,checksum FROM " + schema
                                + ".flyway_schema_history WHERE version IS NOT NULL AND version NOT IN ('12','13') ORDER BY installed_rank"));
        assertEquals(0, current.migrate().migrationsExecuted);
        current.validate();
    }
}
