package com.quizopia.proctoring;

import static com.quizopia.proctoring.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.quizopia.proctoring.application.QuizPurgeService;
import com.quizopia.proctoring.application.QuizRevocationService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Private quiz-deletion surface over real HTTP: exact SERVICE caller, dedicated scopes,
 * strict bounded transport, sanitized responses and truthful purge status. Deletion is
 * enabled with loopback transport exactly like the assessment PR B surface.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.deletion.enabled=true",
            "quizopia.deletion.quiz-service-client-id=quiz-service",
            "quizopia.deletion.allow-insecure-loopback=true",
            "quizopia.deletion.requests-per-minute=6000",
            "quizopia.proctoring.monitoring.enabled=false",
            "quizopia.proctoring.media.enabled=false"
        })
@Import(QuizDeletionHttpIntegrationTest.Fixture.class)
class QuizDeletionHttpIntegrationTest {
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

    String revokeCaller() {
        return service("quiz-service", "quiz.deletion.revoke");
    }

    String purgeCaller() {
        return service("quiz-service", "quiz.deletion.purge");
    }

    String body(UUID quizId, UUID operationId) {
        return "{\"operations\":[{\"quizId\":\"" + quizId + "\",\"operationId\":\"" + operationId + "\"}]}";
    }

    @Test
    void deletionRoutesRequireExactServiceCallerAndDedicatedScope() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        String payload = body(quiz, operation);
        // Unauthenticated callers never reach the transport checks.
        for (String path :
                List.of("/internal/security/quiz-revocations/batch", "/internal/security/quiz-purgings/batch")) {
            http.post()
                    .uri(path)
                    .contentType(APPLICATION_JSON)
                    .bodyValue(payload)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(401)
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store")
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("UNAUTHENTICATED");
        }
        // Wrong dedicated scope on both surfaces, and the wrong direction between them.
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service("quiz-service", "quiz.deletion.purge"))
                .bodyValue(payload)
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ACCESS_DENIED");
        http.post()
                .uri("/internal/security/quiz-purgings/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokeCaller())
                .bodyValue(payload)
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ACCESS_DENIED");
        // Wrong service client identity, even with the right scope.
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service("assessment-service", "quiz.deletion.revoke"))
                .bodyValue(payload)
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ACCESS_DENIED");
        // A USER token is never a deletion service principal.
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + user())
                .bodyValue(payload)
                .exchange()
                .expectStatus()
                .isEqualTo(403)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ACCESS_DENIED");
    }

    @Test
    void malformedTransportAndBodyShapesAreRejectedBeforeAnyFenceOrPurge() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        String valid = body(quiz, operation);
        List<Runnable> calls = List.<Runnable>of(
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch?x=1")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokeCaller())
                        .bodyValue(valid)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokeCaller())
                        .header(HttpHeaders.COOKIE, "session=1")
                        .bodyValue(valid)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-purgings/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                        .header("X-Authenticated-User", "forged")
                        .bodyValue(valid)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-purgings/batch")
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
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
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokeCaller())
                        .bodyValue("{\"operations\":[],\"operations\":[]}")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.get()
                        .uri("/internal/security/quiz-revocations/" + quiz + "/status?x=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokeCaller())
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.get()
                        .uri("/internal/security/quiz-revocations/" + quiz + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                        .exchange()
                        .expectStatus()
                        .isEqualTo(403),
                () -> http.get()
                        .uri("/internal/security/quiz-purgings/" + quiz + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokeCaller())
                        .exchange()
                        .expectStatus()
                        .isEqualTo(403));
        for (Runnable call : calls) call.run();
        assertEquals(
                "0",
                db.queryForObject("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", String.class, quiz));
    }

    @Test
    void acceptedRevocationIsSanitizedIdempotentAndReportsTruthfulPurgeStatus() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        var first = http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokeCaller())
                .bodyValue(body(quiz, operation))
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(QuizRevocationService.Batch.class)
                .returnResult()
                .getResponseBody();
        assertEquals(1, first.accepted().size());
        var second = http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokeCaller())
                .bodyValue(body(quiz, operation))
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectBody(QuizRevocationService.Batch.class)
                .returnResult()
                .getResponseBody();
        assertEquals(0, second.accepted().size());
        assertEquals(1, second.alreadyRevoked().size());
        assertEquals(
                first.accepted().getFirst().revokedAt(),
                second.alreadyRevoked().getFirst().revokedAt());

        // Acknowledge, then purge through the private surface.
        db.update("UPDATE quiz_revocation_fences SET ack_at=clock_timestamp() WHERE quiz_id=?", quiz);
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
                    assertTrue(status.purged());
                    assertFalse(status.retentionHoldActive());
                    // No media provider is configured in this fixture: no fabricated receipt.
                    assertEquals("NOT_CONFIGURED", status.providerErasure());
                });
        http.get()
                .uri("/internal/security/quiz-purgings/" + UUID.randomUUID() + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .exchange()
                .expectStatus()
                .isEqualTo(404)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PURGE_NOT_FOUND");
    }

    @Test
    void statusExplicitlyReportsLocalHoldBeforeAnyPurge() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,operation_id,revoked_at,ack_at) VALUES(?,?,clock_timestamp(),NULL)",
                quiz,
                operation);
        String path = "/internal/security/quiz-purgings/" + quiz + "/status";
        http.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.retentionHoldActive")
                .isEqualTo(false);
        db.queryForObject(
                "SELECT proctoring_set_hold(?,?,NULL,'institution','appeal','reviewer',clock_timestamp()+interval '1 day',clock_timestamp()+interval '1 hour')",
                Object.class,
                UUID.randomUUID(),
                quiz);
        http.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.retentionHoldActive")
                .isEqualTo(true)
                .jsonPath("$.purged")
                .isEqualTo(false);
        db.update(
                "UPDATE proctoring_retention_holds SET review_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 hour' WHERE quiz_id=?",
                quiz);
        http.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.retentionHoldActive")
                .isEqualTo(false);
    }

    @Test
    void purgeWithoutAcknowledgmentReportsWaitingRevocationAck() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + revokeCaller())
                .bodyValue(body(quiz, operation))
                .exchange()
                .expectStatus()
                .isEqualTo(200);
        var result = http.post()
                .uri("/internal/security/quiz-purgings/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purgeCaller())
                .bodyValue(body(quiz, operation))
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectBodyList(QuizPurgeService.Result.class)
                .returnResult()
                .getResponseBody();
        assertEquals(
                QuizPurgeService.Outcome.WAITING_REVOCATION_ACK,
                result.getFirst().status());
    }

    static class Fixture {
        @Bean
        @Primary
        JwtDecoder decoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
}
