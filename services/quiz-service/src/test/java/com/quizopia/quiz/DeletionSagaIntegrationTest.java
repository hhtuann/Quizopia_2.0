package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quizopia.quiz.application.DeletionSagaService;
import com.quizopia.quiz.security.QuizopiaTokenClaims;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * FB-015 PR C saga on real PostgreSQL with explicit dependency doubles: the coordinator walks
 * QUIZ_FENCED -> ... -> PRIMARY_PURGED only from BOTH dependent acknowledgments and BOTH
 * dependent PURGED answers, parks in WAITING_DEPENDENCY during outages, surfaces assessment
 * retention holds as BLOCKED_BY_RETENTION in the public status, hard-deletes its own
 * quiz/versions/drafts, and never claims ALL_COPIES_ERASED.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeletionSagaIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("quiz_db")
            .withUsername("quiz")
            .withPassword("quiz_test");

    static final AtomicBoolean ASSESSMENT_ACK = new AtomicBoolean();
    static final AtomicBoolean PROCTORING_ACK = new AtomicBoolean();
    static final AtomicReference<String> ASSESSMENT_PURGE = new AtomicReference<>("PURGED");
    static final AtomicBoolean ASSESSMENT_HOLD = new AtomicBoolean();
    static final AtomicBoolean ASSESSMENT_PURGED = new AtomicBoolean();
    static final AtomicBoolean PROCTORING_PURGED = new AtomicBoolean();
    static final AtomicBoolean ASSESSMENT_OUTAGE = new AtomicBoolean();

    static final DisposableServer ASSESSMENT =
            dependencyServer(ASSESSMENT_ACK, ASSESSMENT_PURGE, ASSESSMENT_HOLD, ASSESSMENT_PURGED, ASSESSMENT_OUTAGE);
    static final DisposableServer PROCTORING = dependencyServer(
            PROCTORING_ACK,
            new AtomicReference<>("PURGED"),
            new AtomicBoolean(),
            PROCTORING_PURGED,
            new AtomicBoolean());

    static DisposableServer dependencyServer(
            AtomicBoolean acknowledged,
            AtomicReference<String> purgeOutcome,
            AtomicBoolean hold,
            AtomicBoolean purged,
            AtomicBoolean outage) {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    if (request.uri().equals("/oauth2/token")) {
                        return response.header("Content-Type", "application/json")
                                .sendString(Mono.just(
                                        "{\"access_token\":\"synthetic-dependency-token\",\"token_type\":\"Bearer\","
                                                + "\"expires_in\":3600,\"scope\":\"quiz.deletion.revoke\"}"))
                                .then();
                    }
                    String path = request.uri();
                    if (path.endsWith("/quiz-revocations/batch")) {
                        if (outage.get()) return response.status(503).send();
                        return request.receive().aggregate().asString().flatMap(body -> response.header(
                                        "Content-Type", "application/json")
                                .sendString(Mono.just("{\"accepted\":[],\"alreadyRevoked\":[]}"))
                                .then());
                    }
                    if (path.endsWith("/quiz-purgings/batch")) {
                        if (outage.get()) return response.status(503).send();
                        return request.receive().aggregate().asString().flatMap(body -> {
                            String quiz = body.split("\"quizId\":\"")[1].split("\"")[0];
                            String operation =
                                    body.split("\"operationId\":\"")[1].split("\"")[0];
                            return response.header("Content-Type", "application/json")
                                    .sendString(Mono.just("[{\"operationId\":\"" + operation + "\",\"quizId\":\"" + quiz
                                            + "\",\"status\":\"" + purgeOutcome.get() + "\",\"steps\":{}}]"))
                                    .then();
                        });
                    }
                    if (path.contains("/quiz-revocations/")) {
                        String quiz = path.split("/quiz-revocations/")[1].split("/")[0];
                        return response.header("Content-Type", "application/json")
                                .sendString(Mono.just("{\"quizId\":\"" + quiz + "\",\"revokedAt\":\""
                                        + Instant.now() + "\",\"acknowledgedAt\":"
                                        + (acknowledged.get() ? "\"" + Instant.now() + "\"" : "null")
                                        + ",\"publicationCount\":0,\"attemptCount\":0}"))
                                .then();
                    }
                    // purge status
                    String quiz = path.split("/quiz-purgings/")[1].split("/")[0];
                    return response.header("Content-Type", "application/json")
                            .sendString(Mono.just("{\"quizId\":\"" + quiz + "\",\"revokedAt\":\"" + Instant.now()
                                    + "\",\"acknowledgedAt\":\"" + Instant.now() + "\",\"purged\":"
                                    + purged.get() + ",\"retentionHoldActive\":" + hold.get() + ",\"steps\":{}}"))
                            .then();
                })
                .bindNow();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("quizopia.deletion.enabled", () -> true);
        registry.add("quizopia.deletion.dependencies.enabled", () -> true);
        registry.add("quizopia.deletion.dependencies.identity-base-url", () -> identity());
        registry.add("quizopia.deletion.dependencies.assessment-base-url", () -> assessment());
        registry.add("quizopia.deletion.dependencies.proctoring-base-url", () -> proctoring());
        registry.add("quizopia.deletion.dependencies.client-id", () -> "quiz-service");
        registry.add("quizopia.deletion.dependencies.client-secret", () -> "synthetic-secret");
        registry.add("quizopia.deletion.dependencies.allow-insecure-loopback", () -> true);
        registry.add("quizopia.deletion.dependencies.total-timeout", () -> "PT2S");
        registry.add("quizopia.deletion.dependencies.response-timeout", () -> "PT1S");
    }

    static String identity() {
        return "http://127.0.0.1:" + ASSESSMENT.port();
    }

    static String assessment() {
        return "http://127.0.0.1:" + ASSESSMENT.port();
    }

    static String proctoring() {
        return "http://127.0.0.1:" + PROCTORING.port();
    }

    @AfterAll
    static void stopDoubles() {
        ASSESSMENT.disposeNow();
        PROCTORING.disposeNow();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DeletionSagaService saga;

    @MockitoBean
    JwtDecoder jwtDecoder;

    UUID owner;
    UUID quizId;
    UUID operationId;

    @BeforeEach
    void fixture() {
        ASSESSMENT_ACK.set(false);
        PROCTORING_ACK.set(false);
        ASSESSMENT_PURGE.set("PURGED");
        ASSESSMENT_HOLD.set(false);
        ASSESSMENT_PURGED.set(false);
        PROCTORING_PURGED.set(false);
        ASSESSMENT_OUTAGE.set(false);
        jdbc.update("delete from quiz_deletion_outbox");
        jdbc.update("delete from quiz_deletion_operations");
        jdbc.update("delete from quiz_versions");
        jdbc.update("delete from quiz_drafts");
        jdbc.update("delete from quizzes");
        owner = UUID.randomUUID();
        quizId = UUID.randomUUID();
        operationId = UUID.randomUUID();
        jdbc.update(
                "insert into quizzes(id, owner_user_id, created_at, deletion_fenced_at) values(?,?,clock_timestamp(),clock_timestamp())",
                quizId,
                owner);
        jdbc.update(
                "insert into quiz_drafts(quiz_id, title, description, authoring_source, updated_at)"
                        + " values(?, 'Saga fixture', null, '# source', clock_timestamp())",
                quizId);
        jdbc.update(
                "insert into quiz_versions(id, quiz_id, version_number, title_snapshot, description_snapshot,"
                        + " source_snapshot, structured_content, content_schema_version, created_at)"
                        + " values(?,?,1,'Saga fixture',null,'# source','{\"questions\":[]}'::jsonb,1,clock_timestamp())",
                UUID.randomUUID(),
                quizId);
        jdbc.update(
                "insert into quiz_deletion_operations(id, quiz_id, owner_user_id, idempotency_key, status,"
                        + " main_progress, created_at, updated_at, confirmed_title, version_inventory)"
                        + " values(?,?,?,?, 'QUIZ_FENCED', 1, clock_timestamp(), clock_timestamp(), 'Saga fixture', '[]'::jsonb)",
                operationId,
                quizId,
                owner,
                "saga-fixture-key-" + Long.toHexString(System.nanoTime()));
        jdbc.update(
                "insert into quiz_deletion_outbox(id, operation_id, kind, payload, created_at, next_attempt_at)"
                        + " values(?,?,'quiz.deletion.requested.v1','{}'::jsonb,clock_timestamp(),clock_timestamp())",
                UUID.randomUUID(),
                operationId);
    }

    String operationStatus() {
        return jdbc.queryForObject(
                "select status from quiz_deletion_operations where id = ?", String.class, operationId);
    }

    @Test
    void sagaAdvancesToPrimaryPurgedOnlyAfterBothDependentsAcknowledgeAndPurge() {
        // Round 1: revocation dispatched to both, but neither acknowledged yet.
        saga.reconcileDue();
        assertEquals("WAITING_DEPENDENCY", operationStatus());
        assertEquals(
                "waiting-revocation-ack",
                jdbc.queryForObject(
                        "select last_reason from quiz_deletion_operations where id = ?", String.class, operationId));

        // Both acknowledge: ACCESS_REVOKED requires BOTH services.
        ASSESSMENT_ACK.set(true);
        saga.reconcileDue();
        assertEquals("WAITING_DEPENDENCY", operationStatus());
        PROCTORING_ACK.set(true);
        saga.reconcileDue();
        assertEquals("ACCESS_REVOKED", operationStatus());

        // Purge dispatched; assessment purged only -> parked waiting while main progress
        // durably records the reached PURGING phase.
        ASSESSMENT_PURGED.set(true);
        saga.reconcileDue();
        assertEquals("WAITING_DEPENDENCY", operationStatus());
        assertEquals(
                5,
                jdbc.queryForObject(
                        "select main_progress from quiz_deletion_operations where id = ?", Integer.class, operationId));

        // Both purged -> own content hard-deleted, ledger kept, PRIMARY_PURGED reached
        // (and never ALL_COPIES_ERASED).
        PROCTORING_PURGED.set(true);
        saga.reconcileDue();
        assertEquals("PRIMARY_PURGED", operationStatus());
        assertEquals(0, jdbc.queryForObject("select count(*) from quizzes where id = ?", Integer.class, quizId));
        assertEquals(
                0, jdbc.queryForObject("select count(*) from quiz_drafts where quiz_id = ?", Integer.class, quizId));
        assertEquals(
                0, jdbc.queryForObject("select count(*) from quiz_versions where quiz_id = ?", Integer.class, quizId));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from quiz_deletion_operations where id = ?", Integer.class, operationId));
        // Idempotent extra rounds change nothing.
        saga.reconcileDue();
        assertEquals("PRIMARY_PURGED", operationStatus());
    }

    @Test
    void outageParksInWaitingDependencyAndRecoversWithBoundProgress() {
        ASSESSMENT_OUTAGE.set(true);
        saga.reconcileDue();
        assertEquals("WAITING_DEPENDENCY", operationStatus());
        assertEquals(
                "dependency-unavailable",
                jdbc.queryForObject(
                        "select last_reason from quiz_deletion_operations where id = ?", String.class, operationId));
        // The dependency returns; progress continues from exactly where it parked.
        ASSESSMENT_OUTAGE.set(false);
        ASSESSMENT_ACK.set(true);
        PROCTORING_ACK.set(true);
        ASSESSMENT_PURGED.set(true);
        PROCTORING_PURGED.set(true);
        saga.reconcileDue();
        assertEquals("ACCESS_REVOKED", operationStatus());
        saga.reconcileDue();
        assertEquals("PRIMARY_PURGED", operationStatus());
    }

    @Test
    void retentionHoldSurfacesAsBlockedByRetentionInPublicStatusThenCompletes() throws Exception {
        String token = "saga-owner";
        when(jwtDecoder.decode(token)).thenReturn(jwt(owner, List.of("TEACHER")));
        ASSESSMENT_ACK.set(true);
        PROCTORING_ACK.set(true);
        ASSESSMENT_PURGED.set(true);
        PROCTORING_PURGED.set(true);
        ASSESSMENT_PURGE.set("BLOCKED_BY_RETENTION");
        ASSESSMENT_HOLD.set(true);
        saga.reconcileDue();
        assertEquals("ACCESS_REVOKED", operationStatus());
        saga.reconcileDue();
        assertEquals("BLOCKED_BY_RETENTION", operationStatus());
        mvc.perform(get("/api/quizzes/{quizId}/deletion-requests/{operationId}", quizId, operationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED_BY_RETENTION"))
                .andExpect(jsonPath("$.lastReason").value("blocked-by-retention"));
        // Nothing was purged while held.
        assertEquals(1, jdbc.queryForObject("select count(*) from quizzes where id = ?", Integer.class, quizId));

        // The hold lifts; the saga completes to PRIMARY_PURGED with the copies-pending note.
        ASSESSMENT_PURGE.set("PURGED");
        ASSESSMENT_HOLD.set(false);
        saga.reconcileDue();
        assertEquals("PRIMARY_PURGED", operationStatus());
        mvc.perform(get("/api/quizzes/{quizId}/deletion-requests/{operationId}", quizId, operationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PRIMARY_PURGED"))
                .andExpect(jsonPath("$.note").value(DeletionSagaService.COPIES_PENDING_NOTE));
        assertEquals(0, jdbc.queryForObject("select count(*) from quizzes where id = ?", Integer.class, quizId));
    }

    @Test
    void unacknowledgedPurgeAnswerNeverReachesPrimaryPurged() {
        ASSESSMENT_ACK.set(true);
        PROCTORING_ACK.set(true);
        ASSESSMENT_PURGED.set(true);
        PROCTORING_PURGED.set(true);
        ASSESSMENT_PURGE.set("WAITING_REVOCATION_ACK");
        saga.reconcileDue();
        assertEquals("ACCESS_REVOKED", operationStatus());
        saga.reconcileDue();
        assertEquals("WAITING_DEPENDENCY", operationStatus());
        assertEquals(1, jdbc.queryForObject("select count(*) from quizzes where id = ?", Integer.class, quizId));
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
