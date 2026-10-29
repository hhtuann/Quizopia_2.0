package com.quizopia.assessment;

import static com.quizopia.assessment.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.quizopia.assessment.application.QuizPurgeService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Private quiz-purge surface over real HTTP: exact SERVICE caller with the dedicated
 * quiz.deletion.purge scope, strict bounded transport, sanitized responses, truthful
 * hold/ack statuses and no-store semantics. Mirrors the PR B revocation surface.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.deletion.enabled=true",
            "quizopia.deletion.quiz-service-client-id=quiz-service",
            "quizopia.deletion.allow-insecure-loopback=true",
            "quizopia.deletion.requests-per-minute=6000",
            "quizopia.assessment.features.attempts-enabled=false",
            "quizopia.assessment.features.results-enabled=false"
        })
@Import(ClosedBusinessBoundaryTest.Keys.class)
class QuizPurgeHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", DB::getJdbcUrl);
        values.add("spring.datasource.username", DB::getUsername);
        values.add("spring.datasource.password", DB::getPassword);
        values.add("spring.flyway.user", DB::getUsername);
        values.add("spring.flyway.password", DB::getPassword);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate db;

    WebTestClient http;

    @BeforeEach
    void setup() {
        http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    String service(String client, String scope) {
        return sign(
                KEY,
                ISSUER,
                client,
                "SERVICE",
                Map.of("scope", List.of(scope)),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
    }

    String purgeCaller() {
        return service("quiz-service", "quiz.deletion.purge");
    }

    String body(UUID quizId, UUID operationId) {
        return "{\"operations\":[{\"quizId\":\"" + quizId + "\",\"operationId\":\"" + operationId + "\"}]}";
    }

    void revokeAndAcknowledge(UUID quiz, UUID operation) {
        db.update(
                "INSERT INTO deletion_command_inbox(id,operation_id,quiz_id,received_at,processed_at,attempts)"
                        + " VALUES(?,?,?,?,?,1)",
                UUID.randomUUID(),
                operation,
                quiz,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,revoked_at,operation_id,source,ack_at)"
                        + " VALUES(?,clock_timestamp(),?,'COMMAND',clock_timestamp())",
                quiz,
                operation);
    }

    @Test
    void purgeBatchRequiresExactServiceCallerAndDedicatedScope() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        String payload = body(quiz, operation);
        http.post()
                .uri("/internal/security/quiz-purgings/batch")
                .contentType(APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus()
                .isEqualTo(401)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_AUTHENTICATION_INVALID");
        // The revocation scope must not open the purge surface, and vice versa.
        http.post()
                .uri("/internal/security/quiz-purgings/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service("quiz-service", "quiz.deletion.revoke"))
                .bodyValue(payload)
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_CALLER_FORBIDDEN");
        http.post()
                .uri("/internal/security/quiz-purgings/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service("gateway", "quiz.deletion.purge"))
                .bodyValue(payload)
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_CALLER_FORBIDDEN");
        http.get()
                .uri("/internal/security/quiz-purgings/" + quiz + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .exchange()
                .expectStatus()
                .isEqualTo(404)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PURGE_NOT_FOUND");
    }

    @Test
    void malformedTransportAndBodyShapesAreRejectedBeforeAnyPurge() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        String valid = body(quiz, operation);
        List<Runnable> calls = List.<Runnable>of(
                () -> http.post()
                        .uri("/internal/security/quiz-purgings/batch?x=1")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                        .bodyValue(valid)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-purgings/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                        .header(HttpHeaders.COOKIE, "session=1")
                        .bodyValue(valid)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-purgings/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                        .header("X-Service-Identity", "forged")
                        .bodyValue(valid)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-purgings/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                        .bodyValue("{\"operations\":[]}")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-purgings/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                        .bodyValue("{\"operations\":[],\"operations\":[]}")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.get()
                        .uri("/internal/security/quiz-purgings/" + quiz + "/status?x=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400));
        for (Runnable call : calls) call.run();
        assertEquals(
                "0",
                db.queryForObject("SELECT count(*) FROM deletion_purge_receipts WHERE quiz_id=?", String.class, quiz));
    }

    @Test
    void acknowledgedQuizPurgesAndReportsTruthfulStatusWithHold() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        revokeAndAcknowledge(quiz, operation);
        var purged = http.post()
                .uri("/internal/security/quiz-purgings/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .bodyValue(body(quiz, operation))
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBodyList(QuizPurgeService.Result.class)
                .returnResult()
                .getResponseBody();
        assertEquals(1, purged.size());
        assertEquals(QuizPurgeService.Outcome.PURGED, purged.getFirst().status());
        assertTrue(purged.getFirst().steps().containsKey("verified_zero"));

        http.get()
                .uri("/internal/security/quiz-purgings/" + quiz + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(QuizPurgeService.PurgeStatus.class)
                .value(status -> {
                    assertEquals(quiz, status.quizId());
                    assertNotNull(status.revokedAt());
                    assertNotNull(status.acknowledgedAt());
                    assertTrue(status.purged());
                    assertFalse(status.retentionHoldActive());
                });

        // An active hold on another quiz surfaces as BLOCKED_BY_RETENTION without purging.
        UUID held = UUID.randomUUID(), heldOperation = UUID.randomUUID();
        revokeAndAcknowledge(held, heldOperation);
        db.update(
                "INSERT INTO retention_holds(id,quiz_id,authority,reason,reviewer,expires_at,review_at)"
                        + " VALUES(?,?,?,'fixture hold','reviewer',NULL,clock_timestamp()+interval '10 days')",
                UUID.randomUUID(),
                held,
                "authority");
        var blocked = http.post()
                .uri("/internal/security/quiz-purgings/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .bodyValue(body(held, heldOperation))
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectBodyList(QuizPurgeService.Result.class)
                .returnResult()
                .getResponseBody();
        assertEquals(
                QuizPurgeService.Outcome.BLOCKED_BY_RETENTION,
                blocked.getFirst().status());
        http.get()
                .uri("/internal/security/quiz-purgings/" + held + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectBody(QuizPurgeService.PurgeStatus.class)
                .value(status -> assertTrue(status.retentionHoldActive()));

        // Without acknowledgment the surface refuses rather than half-purging.
        UUID fenced = UUID.randomUUID(), fencedOperation = UUID.randomUUID();
        db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,revoked_at,operation_id,source,ack_at)"
                        + " VALUES(?,clock_timestamp(),?,'COMMAND',NULL)",
                fenced,
                fencedOperation);
        var waiting = http.post()
                .uri("/internal/security/quiz-purgings/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .bodyValue(body(fenced, fencedOperation))
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectBodyList(QuizPurgeService.Result.class)
                .returnResult()
                .getResponseBody();
        assertEquals(
                QuizPurgeService.Outcome.WAITING_REVOCATION_ACK,
                waiting.getFirst().status());
    }
}
