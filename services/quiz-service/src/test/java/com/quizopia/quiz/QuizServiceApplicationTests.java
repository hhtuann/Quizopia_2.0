package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.quizopia.quiz.application.QuizApplicationService;
import com.quizopia.quiz.application.QuizDraftDetails;
import com.quizopia.quiz.application.QuizDraftInput;
import com.quizopia.quiz.application.QuizDraftRepository;
import com.quizopia.quiz.application.QuizMarkdownInvalidException;
import com.quizopia.quiz.application.QuizPublishResult;
import com.quizopia.quiz.application.QuizRepository;
import com.quizopia.quiz.application.QuizVersionRepository;
import com.quizopia.quiz.domain.Quiz;
import com.quizopia.quiz.domain.QuizDraft;
import com.quizopia.quiz.domain.QuizVersion;
import com.quizopia.quiz.domain.markdown.QuizContent;
import com.quizopia.quiz.domain.markdown.QuizMarkdownParser;
import com.quizopia.quiz.domain.markdown.QuizQuestionType;
import com.quizopia.quiz.security.QuizopiaTokenClaims;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuizServiceApplicationTests {
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
    QuizRepository quizzes;

    @Autowired
    QuizDraftRepository drafts;

    @Autowired
    QuizVersionRepository versions;

    @Autowired
    QuizApplicationService quizApplicationService;

    @Autowired
    Flyway flyway;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void contextLoadsAfterFlywayMigrationAndHibernateValidation() {
        assertEquals("2", flyway.info().current().getVersion().getVersion());
        assertEquals(0, flyway.info().pending().length);
        flyway.validate();
        assertEquals(
                2,
                jdbc.queryForObject(
                        "select count(*) from flyway_schema_history where version in ('1', '2') and success",
                        Integer.class));
        assertTrue(entityManagerFactory.isOpen());
        assertEquals(3, entityManagerFactory.getMetamodel().getEntities().size());
        assertEquals(
                Set.of("flyway_schema_history", "quiz_drafts", "quiz_versions", "quizzes"),
                new HashSet<>(jdbc.queryForList(
                        "select tablename from pg_tables where schemaname = 'public'", String.class)));
    }

    @Test
    void quizVersionRoundTripsJsonbAndImmutableSnapshotFields() {
        UUID quizId = UUID.randomUUID();
        quizzes.insert(new Quiz(quizId, UUID.randomUUID(), Instant.parse("2026-09-25T08:00:00Z")));
        String source = richPublishedSource().replace("\n", "\r\n");
        QuizContent content = new QuizMarkdownParser().parse(source).content().orElseThrow();
        QuizVersion version = new QuizVersion(
                UUID.randomUUID(),
                quizId,
                1,
                "Snapshot title",
                "Snapshot description",
                source,
                content,
                1,
                Instant.parse("2026-09-25T08:01:02.123456Z"));

        versions.insert(version);
        QuizVersion loaded = versions.findLatestByQuizId(quizId).orElseThrow();

        assertEquals(version, loaded);
        assertEquals(source, loaded.sourceSnapshot());
        assertEquals(1, loaded.contentSchemaVersion());
        assertEquals(
                "jsonb",
                jdbc.queryForObject(
                        "select pg_typeof(structured_content)::text from quiz_versions where id = ?",
                        String.class,
                        version.id()));
        assertEquals(4, loaded.structuredContent().questions().size());
        assertEquals(
                List.of(
                        QuizQuestionType.SINGLE_CHOICE,
                        QuizQuestionType.MULTIPLE_CHOICE,
                        QuizQuestionType.TRUE_FALSE_MATRIX,
                        QuizQuestionType.NUMERIC_FILL),
                loaded.structuredContent().questions().stream()
                        .map(question -> question.type())
                        .toList());
        assertEquals(
                "Stem line 1\nStem line 2",
                loaded.structuredContent().questions().get(0).stemMarkdown());
        assertEquals(
                "Option A line 1\nOption A line 2",
                loaded.structuredContent().questions().get(0).options().get(0).markdown());
        assertTrue(
                loaded.structuredContent().questions().get(0).options().get(0).correct());
        assertTrue(
                loaded.structuredContent().questions().get(2).options().stream().noneMatch(option -> option.correct()));
        assertEquals("-.50", loaded.structuredContent().questions().get(3).numericAnswer());
        assertTrue(loaded.structuredContent()
                .questions()
                .get(3)
                .explanationMarkdown()
                .contains("```text"));
    }

    @Test
    void quizVersionRequiresLocalQuizAndUniqueQuizVersionNumber() {
        QuizContent content = new QuizMarkdownParser()
                .parse("Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 1234\n")
                .content()
                .orElseThrow();
        UUID missingQuizId = UUID.randomUUID();
        assertThrows(
                DataIntegrityViolationException.class,
                () -> versions.insert(version(UUID.randomUUID(), missingQuizId, 1, "missing", content)));

        UUID quizId = UUID.randomUUID();
        quizzes.insert(new Quiz(quizId, UUID.randomUUID(), Instant.parse("2026-09-25T09:00:00Z")));
        versions.insert(version(UUID.randomUUID(), quizId, 1, "v1", content));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> versions.insert(version(UUID.randomUUID(), quizId, 1, "duplicate", content)));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "insert into quiz_versions "
                                + "(id, quiz_id, version_number, source_snapshot, structured_content, content_schema_version, created_at) "
                                + "values (?, ?, 0, 'x', '{}'::jsonb, 1, now())",
                        UUID.randomUUID(),
                        quizId));
    }

    @Test
    void latestVersionLookupUsesHighestVersionForQuizOnly() {
        QuizContent content = new QuizMarkdownParser()
                .parse("Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 1234\n")
                .content()
                .orElseThrow();
        UUID quizId = UUID.randomUUID();
        UUID otherQuizId = UUID.randomUUID();
        quizzes.insert(new Quiz(quizId, UUID.randomUUID(), Instant.parse("2026-09-25T10:00:00Z")));
        quizzes.insert(new Quiz(otherQuizId, UUID.randomUUID(), Instant.parse("2026-09-25T10:00:01Z")));
        versions.insert(version(UUID.randomUUID(), quizId, 1, "v1", content));
        versions.insert(version(UUID.randomUUID(), quizId, 2, "v2", content));
        versions.insert(version(UUID.randomUUID(), otherQuizId, 7, "other", content));

        QuizVersion latest = versions.findLatestByQuizId(quizId).orElseThrow();

        assertEquals(2, latest.versionNumber());
        assertEquals("v2", latest.titleSnapshot());
    }

    @Test
    void versionInsertRollsBackWithSurroundingTransaction() {
        QuizContent content = new QuizMarkdownParser()
                .parse("Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 1234\n")
                .content()
                .orElseThrow();
        UUID quizId = UUID.randomUUID();
        quizzes.insert(new Quiz(quizId, UUID.randomUUID(), Instant.parse("2026-09-25T11:00:00Z")));
        QuizVersion version = version(UUID.randomUUID(), quizId, 1, "rollback", content);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThrows(
                RuntimeException.class,
                () -> transaction.executeWithoutResult(status -> {
                    versions.insert(version);
                    throw new RuntimeException("force rollback");
                }));

        assertEquals(
                0, jdbc.queryForObject("select count(*) from quiz_versions where quiz_id = ?", Integer.class, quizId));
    }

    @Test
    void concurrentSameDraftPublishesCreateAtMostOneNewVersionAndReuseIt() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        String source = "Câu 1 [NUMERIC_FILL]: concurrent?\nĐáp án: 1234\n";
        QuizDraftDetails created =
                quizApplicationService.create(ownerUserId, new QuizDraftInput("Concurrent", null, source));
        UUID quizId = created.quiz().id();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<QuizPublishResult> first = executor.submit(() -> {
                start.await();
                return quizApplicationService.publishOwnedDraft(ownerUserId, quizId);
            });
            Future<QuizPublishResult> second = executor.submit(() -> {
                start.await();
                return quizApplicationService.publishOwnedDraft(ownerUserId, quizId);
            });
            start.countDown();

            QuizPublishResult firstResult = first.get(20, TimeUnit.SECONDS);
            QuizPublishResult secondResult = second.get(20, TimeUnit.SECONDS);

            assertEquals(firstResult.version().id(), secondResult.version().id());
            assertEquals(1, firstResult.version().versionNumber());
            assertEquals(1, secondResult.version().versionNumber());
            assertEquals(1, (firstResult.created() ? 1 : 0) + (secondResult.created() ? 1 : 0));
            assertEquals(
                    1,
                    jdbc.queryForObject("select count(*) from quiz_versions where quiz_id = ?", Integer.class, quizId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidPublishPersistsNoVersionAndLeavesDraftExact() {
        UUID ownerUserId = UUID.randomUUID();
        String invalidSource = "not quiz markdown\r\n";
        QuizDraftDetails created =
                quizApplicationService.create(ownerUserId, new QuizDraftInput("Invalid", null, invalidSource));

        assertThrows(
                QuizMarkdownInvalidException.class,
                () -> quizApplicationService.publishOwnedDraft(
                        ownerUserId, created.quiz().id()));

        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from quiz_versions where quiz_id = ?",
                        Integer.class,
                        created.quiz().id()));
        assertEquals(
                invalidSource,
                drafts.findByQuizId(created.quiz().id()).orElseThrow().authoringSource());
    }

    @Test
    void stableQuizAndMutableDraftRoundTripThroughPersistencePorts() {
        UUID quizId = UUID.randomUUID();
        UUID ownerUserId = UUID.randomUUID();
        Quiz quiz = new Quiz(quizId, ownerUserId, Instant.parse("2026-09-25T01:02:03.123456Z"));
        QuizDraft initialDraft = new QuizDraft(
                quizId,
                "Draft title",
                "Draft description",
                "opaque authoring source\nwith no grammar interpretation",
                Instant.parse("2026-09-25T02:03:04.123456Z"));

        quizzes.insert(quiz);
        drafts.save(initialDraft);

        assertEquals(quiz, quizzes.findById(quizId).orElseThrow());
        assertEquals(initialDraft, drafts.findByQuizId(quizId).orElseThrow());

        QuizDraft changedDraft = new QuizDraft(
                quizId,
                null,
                null,
                "still opaque: [UNRESOLVED_TYPE_SYNTAX]",
                Instant.parse("2026-09-25T03:04:05.123456Z"));
        drafts.save(changedDraft);

        assertEquals(changedDraft, drafts.findByQuizId(quizId).orElseThrow());
        assertEquals(quiz, quizzes.findById(quizId).orElseThrow());
    }

    @Test
    void ownerUserIdIsAScalarWithoutIdentityDatabaseCoupling() {
        UUID ownerUserId = UUID.randomUUID();
        Quiz quiz = new Quiz(UUID.randomUUID(), ownerUserId, Instant.parse("2026-09-25T04:05:06Z"));
        quizzes.insert(quiz);

        assertEquals(
                ownerUserId,
                jdbc.queryForObject("select owner_user_id from quizzes where id = ?", UUID.class, quiz.id()));
        assertEquals(
                Set.of("quizzes"),
                new HashSet<>(jdbc.queryForList(
                        "select confrelid::regclass::text from pg_constraint "
                                + "where contype = 'f' and connamespace = 'public'::regnamespace",
                        String.class)));
    }

    @Test
    void draftRequiresAnExistingLocalQuiz() {
        QuizDraft orphan =
                new QuizDraft(UUID.randomUUID(), "Orphan", null, "opaque", Instant.parse("2026-09-25T05:06:07Z"));

        assertThrows(DataIntegrityViolationException.class, () -> drafts.save(orphan));
    }

    @Test
    void primaryKeysAndRequiredStableFieldsAreDatabaseEnforced() {
        UUID quizId = UUID.randomUUID();
        Quiz quiz = new Quiz(quizId, UUID.randomUUID(), Instant.parse("2026-09-25T06:07:08Z"));
        quizzes.insert(quiz);
        drafts.save(new QuizDraft(quizId, null, null, null, Instant.parse("2026-09-25T07:08:09Z")));

        assertThrows(DataIntegrityViolationException.class, () -> quizzes.insert(quiz));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "insert into quizzes (id, owner_user_id, created_at) values (?, ?, ?)",
                        UUID.randomUUID(),
                        null,
                        OffsetDateTime.now()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "insert into quizzes (id, owner_user_id, created_at) values (?, ?, ?)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update("update quiz_drafts set updated_at = null where quiz_id = ?", quizId));
    }

    @Test
    void applicationUseCasesCreateReadAndUpdateThroughProductionPersistenceBoundaries() {
        UUID ownerUserId = UUID.randomUUID();
        String initialSource = "  initial opaque source\r\n";

        QuizDraftDetails created = quizApplicationService.create(
                ownerUserId, new QuizDraftInput("Initial title", "Initial description", initialSource));

        assertNotNull(created.quiz().id());
        assertEquals(created.quiz().id(), created.draft().quizId());
        assertEquals(ownerUserId, created.quiz().ownerUserId());
        assertEquals(initialSource, created.draft().authoringSource());
        QuizDraftDetails persisted =
                quizApplicationService.getOwnedDraft(ownerUserId, created.quiz().id());
        assertEquals(created.quiz().id(), persisted.quiz().id());
        assertEquals(created.quiz().ownerUserId(), persisted.quiz().ownerUserId());
        assertEquals(created.draft().title(), persisted.draft().title());
        assertEquals(created.draft().description(), persisted.draft().description());
        assertEquals(created.draft().authoringSource(), persisted.draft().authoringSource());

        String changedSource = "\nchanged source [still opaque]\t";
        QuizDraftDetails updated = quizApplicationService.updateOwnedDraft(
                ownerUserId, created.quiz().id(), new QuizDraftInput(null, "Changed description", changedSource));

        assertEquals(persisted.quiz(), updated.quiz());
        assertEquals(changedSource, updated.draft().authoringSource());
        QuizDraft persistedUpdate = drafts.findByQuizId(created.quiz().id()).orElseThrow();
        assertEquals(updated.draft().quizId(), persistedUpdate.quizId());
        assertEquals(updated.draft().title(), persistedUpdate.title());
        assertEquals(updated.draft().description(), persistedUpdate.description());
        assertEquals(updated.draft().authoringSource(), persistedUpdate.authoringSource());
        assertTrue(Duration.between(updated.draft().updatedAt(), persistedUpdate.updatedAt())
                        .abs()
                        .compareTo(Duration.ofNanos(1_000))
                < 0);
        assertEquals(persisted.quiz(), quizzes.findById(created.quiz().id()).orElseThrow());
    }

    @Test
    void teacherHttpCreateReadAndUpdateFlowUsesRealApplicationAndPostgreSql() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        String token = "postgres-http-teacher";
        String initialSource = "  opaque HTTP source\r\n[unparsed]\t";
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("STUDENT", "TEACHER")));

        String createBody = draftRequest("Initial title", "Initial description", initialSource);
        String createResponse = mvc.perform(post("/api/quizzes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.authoringSource").value(initialSource))
                .andExpect(jsonPath("$.ownerUserId").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode created = objectMapper.readTree(createResponse);
        UUID quizId = UUID.fromString(created.path("quizId").asString());
        String location = "/api/quizzes/" + quizId + "/draft";

        assertEquals(
                ownerUserId, jdbc.queryForObject("select owner_user_id from quizzes where id = ?", UUID.class, quizId));
        assertEquals(
                initialSource,
                jdbc.queryForObject(
                        "select authoring_source from quiz_drafts where quiz_id = ?", String.class, quizId));

        String readResponse = mvc.perform(get(location).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authoringSource").value(initialSource))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String persistedCreatedAt =
                objectMapper.readTree(readResponse).path("createdAt").asString();

        String changedSource = "\nreplacement HTTP source [still opaque]  ";
        mvc.perform(put(location)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftRequest("Changed title", null, changedSource)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Changed title"))
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.authoringSource").value(changedSource))
                .andExpect(jsonPath("$.createdAt").value(persistedCreatedAt));

        mvc.perform(get(location).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authoringSource").value(changedSource))
                .andExpect(jsonPath("$.createdAt").value(persistedCreatedAt));
        assertEquals(
                changedSource,
                jdbc.queryForObject(
                        "select authoring_source from quiz_drafts where quiz_id = ?", String.class, quizId));
    }

    @Test
    void teacherHttpPublishCreatesThenReusesLatestWithMetadataOnlyResponse() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        String token = "postgres-publish-teacher";
        String source = "Câu 1 [NUMERIC_FILL]: Giá trị?\r\nĐáp án: -.50\r\n";
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));
        QuizDraftDetails created = quizApplicationService.create(
                ownerUserId, new QuizDraftInput("Published title", "Description", source));
        String path = "/api/quizzes/" + created.quiz().id() + "/versions";

        String firstBody = mvc.perform(post(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quizId").value(created.quiz().id().toString()))
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.sourceSnapshot").doesNotExist())
                .andExpect(jsonPath("$.structuredContent").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode first = objectMapper.readTree(firstBody);
        assertEquals(Set.of("id", "quizId", "versionNumber", "createdAt"), new HashSet<>(first.propertyNames()));

        String secondBody = mvc.perform(post(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNumber").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode second = objectMapper.readTree(secondBody);
        assertEquals(first.path("id").asString(), second.path("id").asString());
        assertEquals(
                first.path("createdAt").asString(), second.path("createdAt").asString());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from quiz_versions where quiz_id = ?",
                        Integer.class,
                        created.quiz().id()));
        assertEquals(
                source,
                jdbc.queryForObject(
                        "select source_snapshot from quiz_versions where quiz_id = ?",
                        String.class,
                        created.quiz().id()));
    }

    @Test
    void invalidMarkdownHttpPublishReturnsStructured400AndPersistsNothing() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        String token = "postgres-invalid-publish";
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));
        QuizDraftDetails created = quizApplicationService.create(
                ownerUserId, new QuizDraftInput("Invalid", null, "not quiz markdown\r\n"));
        String path = "/api/quizzes/" + created.quiz().id() + "/versions";

        mvc.perform(post(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUIZ_MARKDOWN_INVALID"))
                .andExpect(jsonPath("$.message").value("Quiz Markdown validation failed."))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].code").value("CONTENT_OUTSIDE_QUESTION"))
                .andExpect(jsonPath("$.errors[0].line").value(1))
                .andExpect(jsonPath("$.errors[0].column").value(1));

        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from quiz_versions where quiz_id = ?",
                        Integer.class,
                        created.quiz().id()));
    }

    @Test
    void createRollsBackStableQuizWhenInitialDraftPersistenceFails() {
        int quizCountBefore = jdbc.queryForObject("select count(*) from quizzes", Integer.class);
        int draftCountBefore = jdbc.queryForObject("select count(*) from quiz_drafts", Integer.class);

        assertThrows(
                DataAccessException.class,
                () -> quizApplicationService.create(
                        UUID.randomUUID(),
                        new QuizDraftInput("Will roll back", null, "PostgreSQL rejects " + Character.toString(0))));

        assertEquals(quizCountBefore, jdbc.queryForObject("select count(*) from quizzes", Integer.class));
        assertEquals(draftCountBefore, jdbc.queryForObject("select count(*) from quiz_drafts", Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "select count(*) from quizzes q left join quiz_drafts d on d.quiz_id = q.id "
                                + "where d.quiz_id is null",
                        Integer.class));
    }

    private String draftRequest(String title, String description, String authoringSource) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", title);
        request.put("description", description);
        request.put("authoringSource", authoringSource);
        return objectMapper.writeValueAsString(request);
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

    private static QuizVersion version(UUID id, UUID quizId, int versionNumber, String title, QuizContent content) {
        return new QuizVersion(
                id,
                quizId,
                versionNumber,
                title,
                null,
                "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 1234\n",
                content,
                1,
                Instant.parse("2026-09-25T12:00:00Z").plusSeconds(versionNumber));
    }

    private static String richPublishedSource() {
        return """
                Câu 1 [SINGLE_CHOICE]: Stem line 1
                Stem line 2
                *A. Option A line 1
                Option A line 2
                B. Option B
                C. Option C
                D. Option D
                Lời giải: Explanation one
                Câu 2 [MULTIPLE_CHOICE]: Pick values
                *A. One
                *B. Two
                C. Three
                D. Four
                Câu 3 [TRUE_FALSE_MATRIX]: Matrix
                A. False A
                B. False B
                C. False C
                D. False D
                Câu 4 [NUMERIC_FILL]: Number
                Đáp án: -.50
                Lời giải:
                ```text
                Câu 99 [SINGLE_CHOICE]: content only
                Đáp án: 1234
                ```
                """;
    }
}
