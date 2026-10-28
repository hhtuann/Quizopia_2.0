package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quizopia.quiz.application.DeletionOperationService;
import com.quizopia.quiz.application.QuizApplicationService;
import com.quizopia.quiz.application.QuizDraftDetails;
import com.quizopia.quiz.application.QuizDraftInput;
import com.quizopia.quiz.domain.QuizDeletionOperation;
import com.quizopia.quiz.domain.QuizDeletionState;
import com.quizopia.quiz.security.QuizopiaTokenClaims;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuizDeletionIntegrationTest {
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
        registry.add("quizopia.deletion.enabled", () -> true);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    QuizApplicationService quizApplicationService;

    @Autowired
    DeletionOperationService deletionOperationService;

    @Autowired
    com.quizopia.quiz.application.QuizRepository quizRepository;

    @Autowired
    com.quizopia.quiz.application.QuizDraftRepository quizDraftRepository;

    @Autowired
    com.quizopia.quiz.application.QuizVersionRepository quizVersionRepository;

    @Autowired
    com.quizopia.quiz.application.QuizDeletionOperationRepository deletionOperationRepository;

    @Autowired
    com.quizopia.quiz.application.QuizDeletionOutboxRepository deletionOutboxRepository;

    @Autowired
    com.quizopia.quiz.application.QuizIdGenerator quizIdGenerator;

    @Autowired
    com.quizopia.quiz.application.QuizVersionIdGenerator quizVersionIdGenerator;

    @Autowired
    com.quizopia.quiz.domain.markdown.QuizMarkdownParser quizMarkdownParser;

    @Autowired
    java.time.Clock clock;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final String SOURCE = "Câu 1 [NUMERIC_FILL]: Giá trị?\r\nĐáp án: 1234\r\n";

    @Test
    void ownerRequestFullFlowFencesQuizWritesOperationOutboxAndStatus() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        QuizDraftDetails created =
                quizApplicationService.create(ownerUserId, new QuizDraftInput("Deletion target", null, SOURCE));
        UUID quizId = created.quiz().id();
        String token = "deletion-owner";
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));

        MvcResult result = mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "owner-full-flow-key-000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Deletion target")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("QUIZ_FENCED"))
                .andExpect(jsonPath("$.operationId").isNotEmpty())
                .andReturn();
        UUID operationId = UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("operationId")
                .asString());

        assertNotNull(jdbc.queryForObject(
                "select deletion_fenced_at from quizzes where id = ?", OffsetDateTime.class, quizId));
        assertEquals(
                "QUIZ_FENCED",
                jdbc.queryForObject(
                        "select status from quiz_deletion_operations where id = ?", String.class, operationId));
        Map<String, Object> outbox = jdbc.queryForMap(
                "select kind, payload::text, attempts from quiz_deletion_outbox where operation_id = ?", operationId);
        assertEquals("quiz.deletion.requested.v1", outbox.get("kind"));
        assertEquals(0, ((Number) outbox.get("attempts")).intValue());
        JsonNode payload = objectMapper.readTree((String) outbox.get("payload"));
        assertEquals(operationId.toString(), payload.path("operationId").asString());
        assertEquals(quizId.toString(), payload.path("quizId").asString());
        assertEquals(ownerUserId.toString(), payload.path("ownerUserId").asString());
        assertEquals(0, payload.path("versionInventory").size());
        assertFalse(((String) outbox.get("payload")).contains("Deletion target"));

        mvc.perform(get("/api/quizzes/{quizId}/deletion-requests/{operationId}", quizId, operationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.operationId").value(operationId.toString()))
                .andExpect(jsonPath("$.status").value("QUIZ_FENCED"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.lastReason").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.stages.length()").value(8))
                .andExpect(jsonPath("$.stages[0]").value("REQUESTED"))
                .andExpect(jsonPath("$.stages[7]").value("ALL_COPIES_ERASED"));
    }

    @Test
    void sameKeySameTitleReplayReturnsSameOperationWithoutNewRows() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = quizApplicationService
                .create(ownerUserId, new QuizDraftInput("Replay quiz", null, SOURCE))
                .quiz()
                .id();
        String token = "deletion-replay";
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));
        String body = confirmTitle("Replay quiz");

        MvcResult first = mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "replay-key-0000000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();
        int operationsAfterFirst = jdbc.queryForObject("select count(*) from quiz_deletion_operations", Integer.class);
        int outboxAfterFirst = jdbc.queryForObject("select count(*) from quiz_deletion_outbox", Integer.class);

        MvcResult second = mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "replay-key-0000000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andReturn();

        assertEquals(
                objectMapper
                        .readTree(first.getResponse().getContentAsString())
                        .path("operationId")
                        .asString(),
                objectMapper
                        .readTree(second.getResponse().getContentAsString())
                        .path("operationId")
                        .asString());
        assertEquals(
                operationsAfterFirst,
                jdbc.queryForObject("select count(*) from quiz_deletion_operations", Integer.class));
        assertEquals(outboxAfterFirst, jdbc.queryForObject("select count(*) from quiz_deletion_outbox", Integer.class));
    }

    @Test
    void sameKeyDifferentTitleConflictsAndDifferentKeyAgainstFencedQuizConflicts() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = quizApplicationService
                .create(ownerUserId, new QuizDraftInput("Conflict quiz", null, SOURCE))
                .quiz()
                .id();
        String token = "deletion-conflict";
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));

        mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "conflict-key-000000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Conflict quiz")))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "conflict-key-000000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Different title")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELETION_CONFLICT"));

        mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "another-key-00000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Conflict quiz")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELETION_CONFLICT"));
    }

    @Test
    void wrongOrMissingTitleConfirmationIsRejectedCaseSensitively() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = quizApplicationService
                .create(ownerUserId, new QuizDraftInput("Exact Title", null, SOURCE))
                .quiz()
                .id();
        String token = "deletion-title";
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));

        for (String wrong : List.of("exact title", "Exact Titles", "Other")) {
            mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "title-key-" + wrong.length() + "-0000000001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmTitle(wrong)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TITLE_CONFIRMATION_REQUIRED"));
        }
        mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "title-missing-key-00000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("  Exact Title  ")))
                .andExpect(status().isAccepted());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from quiz_deletion_operations where quiz_id = ?", Integer.class, quizId));
    }

    @Test
    void missingShortOrMalformedIdempotencyKeyAndOversizedTitleAreInvalidRequests() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = quizApplicationService
                .create(ownerUserId, new QuizDraftInput("Key quiz", null, SOURCE))
                .quiz()
                .id();
        String token = "deletion-key";
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));

        mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Key quiz")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (String invalid : List.of("short-key-123", "bad key with spaces!", "ünïcode-key-ünïcode")) {
            mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", invalid)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(confirmTitle("Key quiz")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "long-title-key-0000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("x".repeat(301))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from quiz_deletion_operations where quiz_id = ?", Integer.class, quizId));
    }

    @Test
    void nonexistentQuizRequestUsesStandardQuizNotFoundEnvelope() throws Exception {
        UUID callerUserId = UUID.randomUUID();
        String token = "deletion-missing-quiz";
        when(jwtDecoder.decode(token)).thenReturn(jwt(callerUserId, List.of("TEACHER")));

        mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", UUID.randomUUID())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "missing-quiz-key-000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Any")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_NOT_FOUND"));
    }

    @Test
    void quizWithoutDraftOrNullTitleRequiresTitleConfirmation() {
        UUID ownerUserId = UUID.randomUUID();
        UUID noDraftQuizId = UUID.randomUUID();
        UUID nullTitleQuizId = UUID.randomUUID();
        jdbc.update(
                "insert into quizzes (id, owner_user_id, created_at) values (?, ?, now())", noDraftQuizId, ownerUserId);
        jdbc.update(
                "insert into quizzes (id, owner_user_id, created_at) values (?, ?, now())",
                nullTitleQuizId,
                ownerUserId);
        jdbc.update(
                "insert into quiz_drafts (quiz_id, title, authoring_source, updated_at) values (?, null, ?, now())",
                nullTitleQuizId,
                SOURCE);

        assertEquals(
                noDraftQuizId,
                assertThrows(
                                com.quizopia.quiz.application.DeletionTitleConfirmationRequiredException.class,
                                () -> deletionOperationService.requestDeletion(
                                        ownerUserId, noDraftQuizId, "no-draft-key-00000000001", "Any title"))
                        .quizId());
        assertEquals(
                nullTitleQuizId,
                assertThrows(
                                com.quizopia.quiz.application.DeletionTitleConfirmationRequiredException.class,
                                () -> deletionOperationService.requestDeletion(
                                        ownerUserId, nullTitleQuizId, "null-title-key-000000001", "Any title"))
                        .quizId());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from quiz_deletion_operations where owner_user_id = ?",
                        Integer.class,
                        ownerUserId));
    }

    @Test
    void nonOwnerTeacherIsDeniedAndCannotEnumerateOperations() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID otherTeacherId = UUID.randomUUID();
        UUID quizId = quizApplicationService
                .create(ownerUserId, new QuizDraftInput("Private quiz", null, SOURCE))
                .quiz()
                .id();
        String ownerToken = "deletion-owner-private";
        String otherToken = "deletion-other-teacher";
        when(jwtDecoder.decode(ownerToken)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));
        when(jwtDecoder.decode(otherToken)).thenReturn(jwt(otherTeacherId, List.of("TEACHER")));

        mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + otherToken)
                        .header("Idempotency-Key", "other-teacher-key-0000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Private quiz")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from quiz_deletion_operations where quiz_id = ?", Integer.class, quizId));

        MvcResult created = mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .header("Idempotency-Key", "owner-private-key-0000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Private quiz")))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID operationId = UUID.fromString(objectMapper
                .readTree(created.getResponse().getContentAsString())
                .path("operationId")
                .asString());

        mvc.perform(get("/api/quizzes/{quizId}/deletion-requests/{operationId}", quizId, operationId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_DELETION_NOT_FOUND"));
        mvc.perform(get("/api/quizzes/{quizId}/deletion-requests/{operationId}", UUID.randomUUID(), operationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_DELETION_NOT_FOUND"));
        mvc.perform(get("/api/quizzes/{quizId}/deletion-requests/{operationId}", quizId, UUID.randomUUID())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_DELETION_NOT_FOUND"));
        mvc.perform(get("/api/quizzes/{quizId}/deletion-requests/{operationId}", quizId, operationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUIZ_FENCED"));
    }

    @Test
    void studentRoleCannotUseDeletionRoutes() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = quizApplicationService
                .create(ownerUserId, new QuizDraftInput("Student guard", null, SOURCE))
                .quiz()
                .id();
        String token = "deletion-student";
        when(jwtDecoder.decode(token)).thenReturn(jwt(UUID.randomUUID(), List.of("STUDENT")));

        mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "student-guard-key-000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Student guard")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/quizzes/{quizId}/deletion-requests/{operationId}", quizId, UUID.randomUUID())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void fencedQuizRejectsDraftUpdateAndPublishWhileReadsKeepWorking() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = quizApplicationService
                .create(ownerUserId, new QuizDraftInput("Fenced quiz", null, SOURCE))
                .quiz()
                .id();
        String token = "deletion-fenced";
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));
        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletionStatus").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "fenced-quiz-key-00000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Fenced quiz")))
                .andExpect(status().isAccepted());

        mvc.perform(put("/api/quizzes/{quizId}/draft", quizId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Blocked title", "description", "", "authoringSource", SOURCE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_DELETION_PENDING"));
        mvc.perform(post("/api/quizzes/{quizId}/versions", quizId).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUIZ_DELETION_PENDING"));
        assertEquals(
                0, jdbc.queryForObject("select count(*) from quiz_versions where quiz_id = ?", Integer.class, quizId));

        mvc.perform(get("/api/quizzes/{quizId}/draft", quizId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Fenced quiz"))
                .andExpect(jsonPath("$.deletionStatus").value("QUIZ_FENCED"));
        mvc.perform(get("/api/quizzes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].deletionStatus").value("QUIZ_FENCED"));
    }

    @Test
    void versionInventoryFreezesEveryPublishedVersionAtRequestTime() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        QuizDraftDetails created =
                quizApplicationService.create(ownerUserId, new QuizDraftInput("Inventory v1", null, SOURCE));
        UUID quizId = created.quiz().id();
        UUID firstVersionId = quizApplicationService
                .publishOwnedDraft(ownerUserId, quizId)
                .version()
                .id();
        quizApplicationService.updateOwnedDraft(ownerUserId, quizId, new QuizDraftInput("Inventory v2", null, SOURCE));
        UUID secondVersionId = quizApplicationService
                .publishOwnedDraft(ownerUserId, quizId)
                .version()
                .id();
        String token = "deletion-inventory";
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));

        MvcResult result = mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "inventory-key-000000000001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmTitle("Inventory v2")))
                .andExpect(status().isAccepted())
                .andReturn();
        UUID operationId = UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .path("operationId")
                .asString());

        QuizDeletionOperation operation = deletionOperationService.getOperation(ownerUserId, quizId, operationId);
        assertEquals(
                List.of(
                        new QuizDeletionOperation.VersionRef(firstVersionId, 1),
                        new QuizDeletionOperation.VersionRef(secondVersionId, 2)),
                operation.versionInventory());
        JsonNode payload = objectMapper.readTree(jdbc.queryForObject(
                "select payload::text from quiz_deletion_outbox where operation_id = ?", String.class, operationId));
        assertEquals(2, payload.path("versionInventory").size());
        assertEquals(
                firstVersionId.toString(),
                payload.path("versionInventory").get(0).path("id").asString());
        assertEquals(
                1, payload.path("versionInventory").get(0).path("versionNumber").asInt());
        assertEquals(
                secondVersionId.toString(),
                payload.path("versionInventory").get(1).path("id").asString());
        assertEquals(
                2, payload.path("versionInventory").get(1).path("versionNumber").asInt());
    }

    @Test
    void freshServiceInstanceSeesTheSameDurableDeletionState() {
        UUID ownerUserId = UUID.randomUUID();
        UUID quizId = quizApplicationService
                .create(ownerUserId, new QuizDraftInput("Restart quiz", null, SOURCE))
                .quiz()
                .id();
        com.quizopia.quiz.application.DeletionRequestResult accepted = deletionOperationService.requestDeletion(
                ownerUserId, quizId, "restart-key-0000000000001", "Restart quiz");

        DeletionOperationService restartedService = new DeletionOperationService(
                quizRepository,
                quizDraftRepository,
                quizVersionRepository,
                deletionOperationRepository,
                deletionOutboxRepository,
                clock);
        QuizDeletionOperation durableOperation =
                restartedService.getOperation(ownerUserId, quizId, accepted.operationId());

        assertTrue(accepted.created());
        assertEquals(accepted.operationId(), durableOperation.id());
        assertEquals(QuizDeletionState.QUIZ_FENCED, durableOperation.status());
        QuizApplicationService restartedAuthoring = new QuizApplicationService(
                quizRepository,
                quizDraftRepository,
                quizVersionRepository,
                deletionOperationRepository,
                quizIdGenerator,
                quizVersionIdGenerator,
                quizMarkdownParser,
                clock);
        assertThrows(
                com.quizopia.quiz.application.QuizDeletionPendingException.class,
                () -> restartedAuthoring.publishOwnedDraft(ownerUserId, quizId));
        assertEquals(
                QuizDeletionState.QUIZ_FENCED,
                restartedAuthoring.getOwnedDraft(ownerUserId, quizId).deletionStatus());
    }

    private String confirmTitle(String title) throws Exception {
        return objectMapper.writeValueAsString(Map.of("confirmTitle", title));
    }

    private static Jwt jwt(UUID userId, List<String> roles) {
        return Jwt.withTokenValue("redacted-test-token")
                .header("alg", "RS256")
                .claim("sub", userId.toString())
                .claim(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.USER)
                .claim(QuizopiaTokenClaims.ROLES, roles)
                .issuedAt(Instant.parse("2026-09-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-26T00:05:00Z"))
                .build();
    }
}
