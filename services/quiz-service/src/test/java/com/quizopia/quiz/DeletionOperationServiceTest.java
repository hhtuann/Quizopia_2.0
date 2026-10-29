package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quizopia.quiz.application.DeletionOperationService;
import com.quizopia.quiz.application.DeletionOperationServiceAccess;
import com.quizopia.quiz.application.DeletionRequestResult;
import com.quizopia.quiz.application.IllegalDeletionTransitionException;
import com.quizopia.quiz.application.QuizDeletionNotFoundException;
import com.quizopia.quiz.domain.QuizDeletionOperation;
import com.quizopia.quiz.domain.QuizDeletionState;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class DeletionOperationServiceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("quiz_db")
            .withUsername("quiz")
            .withPassword("quiz_test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    DeletionOperationService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.quizopia.quiz.application.QuizDeletionOperationRepository deletionRepository;

    private static final String SOURCE = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 1234\n";

    private DeletionOperationServiceAccess access() {
        return new DeletionOperationServiceAccess(service);
    }

    private UUID seedQuiz(UUID ownerUserId, String title) {
        UUID quizId = UUID.randomUUID();
        jdbc.update("insert into quizzes (id, owner_user_id, created_at) values (?, ?, now())", quizId, ownerUserId);
        jdbc.update(
                "insert into quiz_drafts (quiz_id, title, authoring_source, updated_at) values (?, ?, ?, now())",
                quizId,
                title,
                SOURCE);
        return quizId;
    }

    @Test
    void requestDeletionStartsAtQuizFencedWithFrozenInventoryAndDurableFence() {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = seedQuiz(ownerUserId, "Advance quiz");

        DeletionRequestResult result =
                service.requestDeletion(ownerUserId, quizId, "advance-key-000000000001", "Advance quiz");

        assertEquals(QuizDeletionState.QUIZ_FENCED, result.status());
        assertEquals(true, result.created());
        assertEquals(
                "QUIZ_FENCED",
                jdbc.queryForObject(
                        "select status from quiz_deletion_operations where id = ?",
                        String.class,
                        result.operationId()));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select main_progress from quiz_deletion_operations where id = ?",
                        Integer.class,
                        result.operationId()));
        assertNotNull(jdbc.queryForObject(
                "select deletion_fenced_at from quizzes where id = ?", java.time.OffsetDateTime.class, quizId));
    }

    @Test
    void advanceStateAcceptsOnlyForwardMonotonicTransitions() {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = seedQuiz(ownerUserId, "Monotonic quiz");
        UUID operationId = service.requestDeletion(ownerUserId, quizId, "monotonic-key-0000000001", "Monotonic quiz")
                .operationId();

        assertEquals(
                QuizDeletionState.DEPENDENCIES_FENCING,
                access().advanceState(operationId, QuizDeletionState.DEPENDENCIES_FENCING, null)
                        .status());
        assertEquals(
                QuizDeletionState.WAITING_DEPENDENCY,
                access().advanceState(operationId, QuizDeletionState.WAITING_DEPENDENCY, "assessment unavailable")
                        .status());
        assertEquals(
                QuizDeletionState.DEPENDENCIES_FENCING,
                access().advanceState(operationId, QuizDeletionState.DEPENDENCIES_FENCING, null)
                        .status());
        assertEquals(
                "assessment unavailable",
                service.getOperation(ownerUserId, quizId, operationId).lastReason());
        assertThrows(IllegalDeletionTransitionException.class, () -> access().advanceState(
                        operationId, QuizDeletionState.QUIZ_FENCED, null));
        assertThrows(IllegalDeletionTransitionException.class, () -> access().advanceState(
                        operationId, QuizDeletionState.REQUESTED, null));
        assertThrows(IllegalDeletionTransitionException.class, () -> access().advanceState(
                        operationId, QuizDeletionState.DEPENDENCIES_FENCING, null));
        assertEquals(
                QuizDeletionState.DEPENDENCIES_FENCING,
                service.getOperation(ownerUserId, quizId, operationId).status());

        access().advanceState(operationId, QuizDeletionState.ACCESS_REVOKED, null);
        access().advanceState(operationId, QuizDeletionState.PURGE_PENDING, null);
        assertThrows(IllegalDeletionTransitionException.class, () -> access().advanceState(
                        operationId, QuizDeletionState.ACCESS_REVOKED, null));
        access().advanceState(operationId, QuizDeletionState.PURGING, null);
        access().advanceState(operationId, QuizDeletionState.RETRYABLE_FAILURE, "transient");
        access().advanceState(operationId, QuizDeletionState.PURGING, null);
        access().advanceState(operationId, QuizDeletionState.PRIMARY_PURGED, null);
        access().advanceState(operationId, QuizDeletionState.ALL_COPIES_ERASED, null);
        assertThrows(IllegalDeletionTransitionException.class, () -> access().advanceState(
                        operationId, QuizDeletionState.PURGING, null));
        assertThrows(IllegalDeletionTransitionException.class, () -> access().advanceState(
                        operationId, QuizDeletionState.RETRYABLE_FAILURE, null));
        assertEquals(
                QuizDeletionState.ALL_COPIES_ERASED,
                service.getOperation(ownerUserId, quizId, operationId).status());
    }

    @Test
    void advanceStateRejectsUnknownOperationAndSideStateCannotSkipBackwardMainProgress() {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = seedQuiz(ownerUserId, "Side quiz");
        UUID operationId = service.requestDeletion(ownerUserId, quizId, "side-key-00000000000001", "Side quiz")
                .operationId();

        assertThrows(QuizDeletionNotFoundException.class, () -> access().advanceState(
                        UUID.randomUUID(), QuizDeletionState.ACCESS_REVOKED, null));
        access().advanceState(operationId, QuizDeletionState.DEPENDENCIES_FENCING, null);
        access().advanceState(operationId, QuizDeletionState.BLOCKED_BY_RETENTION, "legal hold");
        assertEquals(
                QuizDeletionState.BLOCKED_BY_RETENTION,
                service.getOperation(ownerUserId, quizId, operationId).status());
        access().advanceState(operationId, QuizDeletionState.PENDING_POLICY, null);
        assertEquals(
                QuizDeletionState.PENDING_POLICY,
                service.getOperation(ownerUserId, quizId, operationId).status());
        assertThrows(IllegalDeletionTransitionException.class, () -> access().advanceState(
                        operationId, QuizDeletionState.QUIZ_FENCED, null));
        assertEquals(
                QuizDeletionState.PENDING_POLICY,
                service.getOperation(ownerUserId, quizId, operationId).status());
    }

    @Test
    void requestDeletionFreezesInventoryOfEveryPublishedVersion() {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = seedQuiz(ownerUserId, "Inventory quiz");
        UUID firstVersionId = UUID.randomUUID();
        UUID secondVersionId = UUID.randomUUID();
        jdbc.update(
                "insert into quiz_versions (id, quiz_id, version_number, source_snapshot, structured_content, content_schema_version, created_at) "
                        + "values (?, ?, 1, ?, '{\"questions\":[]}'::jsonb, 1, now())",
                firstVersionId,
                quizId,
                SOURCE);
        jdbc.update(
                "insert into quiz_versions (id, quiz_id, version_number, source_snapshot, structured_content, content_schema_version, created_at) "
                        + "values (?, ?, 2, ?, '{\"questions\":[]}'::jsonb, 1, now())",
                secondVersionId,
                quizId,
                SOURCE);

        UUID operationId = service.requestDeletion(ownerUserId, quizId, "inventory-freeze-key-000001", "Inventory quiz")
                .operationId();

        assertEquals(
                List.of(
                        new QuizDeletionOperation.VersionRef(firstVersionId, 1),
                        new QuizDeletionOperation.VersionRef(secondVersionId, 2)),
                service.getOperation(ownerUserId, quizId, operationId).versionInventory());
    }

    @Test
    void primaryPurgeLocksValidatesIdentityAndStageAndIsIdempotent() {
        UUID owner = UUID.randomUUID();
        UUID quiz = seedQuiz(owner, "Purge boundary");
        UUID otherQuiz = seedQuiz(owner, "Unrelated");
        UUID operation = service.requestDeletion(owner, quiz, "purge-boundary-key", "Purge boundary")
                .operationId();
        assertThrows(QuizDeletionNotFoundException.class, () -> service.completePrimaryPurge(operation, otherQuiz));
        assertThrows(IllegalDeletionTransitionException.class, () -> service.completePrimaryPurge(operation, quiz));
        assertEquals(1, jdbc.queryForObject("select count(*) from quizzes where id=?", Integer.class, quiz));
        access().advanceState(operation, QuizDeletionState.PURGING, null);
        access().advanceState(operation, QuizDeletionState.BLOCKED_BY_RETENTION, "hold");
        assertThrows(IllegalDeletionTransitionException.class, () -> service.completePrimaryPurge(operation, quiz));
        access().advanceState(operation, QuizDeletionState.PURGING, null);
        service.completePrimaryPurge(operation, quiz);
        service.completePrimaryPurge(operation, quiz);
        // Direct repeated scrub also preserves the count-object representation.
        jdbc.execute("update quiz_deletion_operations set version_inventory = '{\"versionCount\":3}'::jsonb where id='"
                + operation + "'");
        service.completePrimaryPurge(operation, quiz);
        deletionRepository.scrubLedgerContent(operation);
        deletionRepository.scrubLedgerContent(operation);
        assertEquals(
                "3",
                jdbc.queryForObject(
                        "select version_inventory->>'versionCount' from quiz_deletion_operations where id=?",
                        String.class,
                        operation));
        assertEquals(1, jdbc.queryForObject("select count(*) from quizzes where id=?", Integer.class, otherQuiz));
        assertEquals(
                operation,
                service.requestDeletion(owner, quiz, "purge-boundary-key", "Purge boundary")
                        .operationId());
        assertThrows(
                com.quizopia.quiz.application.DeletionConflictException.class,
                () -> service.requestDeletion(owner, quiz, "purge-boundary-key", "Different"));
        assertThrows(
                QuizDeletionNotFoundException.class,
                () -> service.requestDeletion(UUID.randomUUID(), quiz, "purge-boundary-key", "Purge boundary"));
        assertEquals(operation, service.getLatestOperation(owner, quiz).id());
        assertThrows(QuizDeletionNotFoundException.class, () -> service.getLatestOperation(UUID.randomUUID(), quiz));
    }

    @Test
    void explicitEmptyConfirmationSupportsNullEmptyAndWhitespaceHistoricalTitles() {
        UUID owner = UUID.randomUUID();
        for (String title : new String[] {null, "", "   "}) {
            UUID quiz = seedQuiz(owner, title);
            assertThrows(
                    com.quizopia.quiz.application.DeletionTitleConfirmationRequiredException.class,
                    () -> service.requestDeletion(owner, quiz, "historical-title-key", "Untitled quiz"));
            assertEquals(
                    QuizDeletionState.QUIZ_FENCED,
                    service.requestDeletion(owner, quiz, "historical-title-key", "")
                            .status());
        }
    }
}
