package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quizopia.quiz.application.QuizApplicationService;
import com.quizopia.quiz.application.QuizDraftDetails;
import com.quizopia.quiz.application.QuizDraftInput;
import com.quizopia.quiz.outbox.DeletionOutboxDispatcher;
import com.quizopia.quiz.outbox.DeletionOutboxPublisher;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuizDeletionOutboxIntegrationTest {
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
    JdbcTemplate jdbc;

    @Autowired
    tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    QuizApplicationService quizApplicationService;

    @Autowired
    com.quizopia.quiz.application.QuizDeletionOutboxRepository outboxRepository;

    @Autowired
    java.time.Clock clock;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    DeletionOutboxDispatcher scheduledDispatcher;

    @MockitoBean
    DeletionOutboxPublisher publisher;

    private DeletionOutboxDispatcher dispatcher;

    private static final String SOURCE = "Câu 1 [NUMERIC_FILL]: value?\nĐáp án: 1234\n";

    @BeforeEach
    void setUp() {
        dispatcher = new DeletionOutboxDispatcher(outboxRepository, publisher, clock);
        Mockito.reset(publisher);
    }

    private UUID requestDeletion() throws Exception {
        UUID ownerUserId = UUID.randomUUID();
        QuizDraftDetails created = quizApplicationService.create(
                ownerUserId, new QuizDraftInput("Outbox quiz " + UUID.randomUUID(), null, SOURCE));
        UUID quizId = created.quiz().id();
        String token = "outbox-token-" + UUID.randomUUID();
        when(jwtDecoder.decode(token)).thenReturn(jwt(ownerUserId, List.of("TEACHER")));
        String response = mvc.perform(post("/api/quizzes/{quizId}/deletion-requests", quizId)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "outbox-key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("confirmTitle", created.draft().title()))))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID operationId = UUID.fromString(
                objectMapper.readTree(response).path("operationId").asString());
        return jdbc.queryForObject(
                "select id from quiz_deletion_outbox where operation_id = ?", UUID.class, operationId);
    }

    @Test
    void dueRowIsPublishedOnceAndMarkedDispatched() throws Exception {
        UUID outboxId = requestDeletion();
        doNothing().when(publisher).publish(any());

        dispatcher.dispatchOnce();

        verify(publisher).publish(any());
        assertNotNull(jdbc.queryForObject(
                "select dispatched_at from quiz_deletion_outbox where id = ?", OffsetDateTime.class, outboxId));

        dispatcher.dispatchOnce();
        verify(publisher, Mockito.times(1)).publish(any());
    }

    @Test
    void notDueRowIsSkipped() throws Exception {
        UUID outboxId = requestDeletion();
        jdbc.update(
                "update quiz_deletion_outbox set next_attempt_at = ? where id = ?",
                OffsetDateTime.now().plusMinutes(5),
                outboxId);

        dispatcher.dispatchOnce();

        verify(publisher, never()).publish(any());
        assertNull(jdbc.queryForObject(
                "select dispatched_at from quiz_deletion_outbox where id = ?", OffsetDateTime.class, outboxId));
    }

    @Test
    void failedPublishRecordsBoundedExponentialBackoffUntilSuccess() throws Exception {
        UUID outboxId = requestDeletion();
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        dispatcher.dispatchOnce();
        assertEquals(
                1,
                jdbc.queryForObject("select attempts from quiz_deletion_outbox where id = ?", Integer.class, outboxId));
        assertEquals(
                "java.lang.RuntimeException",
                jdbc.queryForObject(
                        "select last_error from quiz_deletion_outbox where id = ?", String.class, outboxId));
        assertNull(jdbc.queryForObject(
                "select dispatched_at from quiz_deletion_outbox where id = ?", OffsetDateTime.class, outboxId));
        Duration firstBackoff = Duration.between(
                Instant.now(),
                jdbc.queryForObject(
                                "select next_attempt_at from quiz_deletion_outbox where id = ?",
                                OffsetDateTime.class,
                                outboxId)
                        .toInstant());
        assertTrue(firstBackoff.compareTo(Duration.ofSeconds(4)) >= 0, "first backoff was " + firstBackoff);
        assertTrue(firstBackoff.compareTo(Duration.ofSeconds(6)) < 0, "first backoff was " + firstBackoff);

        jdbc.update(
                "update quiz_deletion_outbox set next_attempt_at = ? where id = ?",
                OffsetDateTime.now().minusSeconds(1),
                outboxId);
        dispatcher.dispatchOnce();
        assertEquals(
                2,
                jdbc.queryForObject("select attempts from quiz_deletion_outbox where id = ?", Integer.class, outboxId));
        Duration secondBackoff = Duration.between(
                Instant.now(),
                jdbc.queryForObject(
                                "select next_attempt_at from quiz_deletion_outbox where id = ?",
                                OffsetDateTime.class,
                                outboxId)
                        .toInstant());
        assertTrue(secondBackoff.compareTo(Duration.ofSeconds(9)) >= 0, "second backoff was " + secondBackoff);
        assertTrue(secondBackoff.compareTo(Duration.ofSeconds(11)) < 0, "second backoff was " + secondBackoff);

        doNothing().when(publisher).publish(any());
        jdbc.update(
                "update quiz_deletion_outbox set next_attempt_at = ? where id = ?",
                OffsetDateTime.now().minusSeconds(1),
                outboxId);
        dispatcher.dispatchOnce();
        assertNotNull(jdbc.queryForObject(
                "select dispatched_at from quiz_deletion_outbox where id = ?", OffsetDateTime.class, outboxId));
    }

    @Test
    void exhaustedRowsAreNoLongerDue() throws Exception {
        UUID outboxId = requestDeletion();
        jdbc.update(
                "update quiz_deletion_outbox set attempts = 10, next_attempt_at = ? where id = ?",
                OffsetDateTime.now().minusSeconds(1),
                outboxId);

        dispatcher.dispatchOnce();

        verify(publisher, never()).publish(any());
        assertEquals(
                10,
                jdbc.queryForObject("select attempts from quiz_deletion_outbox where id = ?", Integer.class, outboxId));
        assertNull(jdbc.queryForObject(
                "select dispatched_at from quiz_deletion_outbox where id = ?", OffsetDateTime.class, outboxId));
    }

    private static Jwt jwt(UUID userId, List<String> roles) {
        return Jwt.withTokenValue("redacted-test-token")
                .header("alg", "RS256")
                .claim("sub", userId.toString())
                .claim("principal_type", "USER")
                .claim("roles", roles)
                .issuedAt(Instant.parse("2026-09-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-26T00:05:00Z"))
                .build();
    }
}
