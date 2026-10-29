package com.quizopia.assessment;

import static com.quizopia.assessment.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_PLAIN;

import com.quizopia.assessment.application.QuizRevocationService;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.persistence.JdbcPublicationStore;
import java.nio.charset.StandardCharsets;
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
 * Private quiz-revocation surface over real HTTP: exact SERVICE caller, dedicated scope, strict
 * bounded transport, sanitized responses and truthful acknowledgment status. Route-level denial
 * and serialization behavior are covered by the dedicated fence/concurrency integration tests.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.deletion.enabled=true",
            "quizopia.deletion.quiz-service-client-id=quiz-service",
            "quizopia.deletion.allow-insecure-loopback=true",
            "quizopia.deletion.requests-per-minute=6000"
        })
@Import(ClosedBusinessBoundaryTest.Keys.class)
class QuizRevocationHttpIntegrationTest {
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

    @Autowired
    JdbcPublicationStore publications;

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

    String caller() {
        return service("quiz-service", "quiz.deletion.revoke");
    }

    String body(String quizId, String operationId) {
        return "{\"operations\":[{\"quizId\":\"" + quizId + "\",\"operationId\":\"" + operationId + "\"}]}";
    }

    @Test
    void purgeCallerReadsOnlyOwnedDependentIdsAndOnlyAfterFence() {
        UUID quiz = UUID.randomUUID(), owner = UUID.randomUUID();
        var opened = dependentPublication(quiz, owner, true);
        var draft = dependentPublication(quiz, owner, false);
        var unrelated = dependentPublication(UUID.randomUUID(), owner, false);
        UUID attempt = UUID.randomUUID();
        Instant started = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        db.update(
                "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,1,?,1,1,'ACTIVE',30,?,?)",
                attempt,
                opened,
                UUID.randomUUID(),
                opened,
                java.sql.Timestamp.from(started),
                java.sql.Timestamp.from(started.plusSeconds(1800)));
        String path = "/internal/security/quiz-revocations/" + quiz + "/dependents";
        String purge = service("quiz-service", "quiz.deletion.purge");
        http.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purge)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("REVOCATION_NOT_FOUND");
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                .contentType(APPLICATION_JSON)
                .bodyValue(body(quiz.toString(), UUID.randomUUID().toString()))
                .exchange()
                .expectStatus()
                .isOk();
        var result = http.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purge)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(QuizRevocationService.Dependents.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(result);
        assertEquals(quiz, result.quizId());
        assertEquals(Set.of(opened, draft), Set.copyOf(result.publicationIds()));
        assertEquals(List.of(attempt), result.attemptIds());
        assertFalse(result.publicationIds().contains(unrelated));
        http.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + purge)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(response -> {
                    var json = tools.jackson.databind.json.JsonMapper.builder()
                            .build()
                            .readTree(response.getResponseBody());
                    assertEquals(3, json.size(), "Inventory must contain only quizId/publicationIds/attemptIds");
                    assertTrue(json.has("quizId") && json.has("publicationIds") && json.has("attemptIds"));
                    assertFalse(new String(response.getResponseBody(), StandardCharsets.UTF_8).contains("private"));
                });
    }

    UUID dependentPublication(UUID quiz, UUID owner, boolean open) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var configuration = new PublicationConfiguration(
                "private title",
                "private description",
                quiz,
                UUID.randomUUID(),
                1,
                PublicationConfiguration.Audience.PUBLIC,
                null,
                now.minusSeconds(60),
                now.plusSeconds(3600),
                30,
                PublicationConfiguration.MonitoringChoice.DISABLED,
                0);
        var publication = publications.create(owner, UUID.randomUUID().toString(), configuration);
        if (open)
            publications.open(
                    owner,
                    publication.id(),
                    0,
                    new String(SnapshotFixtures.snapshot(configuration, "private source"), StandardCharsets.UTF_8));
        return publication.id();
    }

    @Test
    void dependentsRequiresPurgeScopeExactClientAndServicePrincipal() {
        String path = "/internal/security/quiz-revocations/" + UUID.randomUUID() + "/dependents";
        http.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store");
        for (String token : List.of(caller(), service("assessment-service", "quiz.deletion.purge"), user())) {
            http.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store")
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("SERVICE_CALLER_FORBIDDEN");
        }
    }

    @Test
    void dependentsRejectsNoncanonicalPathsMethodsAndMalformedTransport() {
        String path = "/internal/security/quiz-revocations/" + UUID.randomUUID() + "/dependents";
        String token = service("quiz-service", "quiz.deletion.purge");
        for (HttpMethod method :
                List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.HEAD))
            http.method(method)
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        for (String invalid : List.of(
                path + "/extra",
                path + "/",
                path.replace("/dependents", "/dependents-extra"),
                "/internal/security/quiz-revocations/ABCDEFAB-1234-1234-1234-123456789ABC/dependents",
                "/internal/security/quiz-revocations/not-a-uuid/dependents"))
            http.get()
                    .uri(invalid)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        http.get()
                .uri(path + "?unexpected=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus()
                .isBadRequest();
        http.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.COOKIE, "session=1")
                .exchange()
                .expectStatus()
                .isBadRequest();
        http.method(HttpMethod.GET)
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void batchRequiresExactServiceCallerAndDedicatedScope() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        String payload = body(quiz.toString(), operation.toString());
        // Unauthenticated callers never reach the transport checks.
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
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
        // Wrong dedicated scope.
        for (String scope : List.of("assessment.proctoring-authority.read", "quiz.deletion.read")) {
            http.post()
                    .uri("/internal/security/quiz-revocations/batch")
                    .contentType(APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + service("quiz-service", scope))
                    .bodyValue(payload)
                    .exchange()
                    .expectStatus()
                    .isEqualTo(403)
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store")
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("SERVICE_CALLER_FORBIDDEN");
        }
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
                .isEqualTo("SERVICE_CALLER_FORBIDDEN");
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
                .isEqualTo("SERVICE_CALLER_FORBIDDEN");
    }

    @Test
    void batchRejectsEveryMalformedTransportAndBodyShape() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        String valid = body(quiz.toString(), operation.toString());
        List<Runnable> calls = List.<Runnable>of(
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch?x=1")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                        .bodyValue(valid)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                        .header(HttpHeaders.COOKIE, "session=1")
                        .bodyValue(valid)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                        .header("X-Authenticated-User", "forged")
                        .bodyValue(valid)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(TEXT_PLAIN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                        .bodyValue(valid)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                        .bodyValue("{\"operations\":[{\"quizId\":\"" + quiz + "\",\"operationId\":\"" + operation
                                + "\"},{\"quizId\":\"" + quiz + "\",\"operationId\":\"" + UUID.randomUUID() + "\"}]}")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                        .bodyValue("{\"operations\":[]}")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                        .bodyValue("{\"operations\":[" + duplicateQuizBody(quiz) + "]}")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                        .bodyValue("{\"operations\":[{\"quizId\":\""
                                + quiz.toString().toUpperCase(Locale.ROOT) + "\",\"operationId\":\"" + operation
                                + "\"}]}")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                        .bodyValue("{\"operations\":[{\"quizId\":\"" + quiz + "\",\"operationId\":\"" + operation
                                + "\",\"extra\":1}]}")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400),
                () -> http.post()
                        .uri("/internal/security/quiz-revocations/batch")
                        .contentType(APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                        .bodyValue("{\"operations\":[{\"quizId\":\"" + quiz + "\",\"operationId\":\"" + operation
                                + "\"}],\"other\":1}")
                        .exchange()
                        .expectStatus()
                        .isEqualTo(400));
        int index = 0;
        for (Runnable call : calls) {
            call.run();
            index++;
        }
        assertEquals(calls.size(), index);
        assertEquals(
                "0",
                db.queryForObject("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", String.class, quiz),
                "no malformed request may install a fence");
    }

    private String duplicateQuizBody(UUID quiz) {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 2; i++)
            json.append(i > 0 ? "," : "")
                    .append("{\"quizId\":\"")
                    .append(quiz)
                    .append("\",\"operationId\":\"")
                    .append(UUID.randomUUID())
                    .append("\"}");
        return json.toString();
    }

    @Test
    void oversizedAndDuplicateKeyBodiesAreRejectedBeforeParsing() {
        UUID quiz = UUID.randomUUID();
        String padding = "1".repeat(17 * 1024);
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                .bodyValue("{\"padding\":\"" + padding + "\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(400);
        // Jackson strict duplicate detection and trailing-token rejection run server-side.
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                .bodyValue("{\"operations\":[],\"operations\":[]}")
                .exchange()
                .expectStatus()
                .isEqualTo(400);
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                .bodyValue("{\"operations\":[]} {}")
                .exchange()
                .expectStatus()
                .isEqualTo(400);
    }

    @Test
    void acceptedBatchIsSanitizedIdempotentAndNoStore() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        var first = http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                .bodyValue(body(quiz.toString(), operation.toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(QuizRevocationService.Batch.class)
                .returnResult()
                .getResponseBody();
        assertEquals(1, first.accepted().size());
        assertEquals(0, first.alreadyRevoked().size());
        assertEquals(quiz, first.accepted().getFirst().quizId());
        assertNotNull(first.accepted().getFirst().revokedAt());

        var second = http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                .bodyValue(body(quiz.toString(), operation.toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(QuizRevocationService.Batch.class)
                .returnResult()
                .getResponseBody();
        assertEquals(0, second.accepted().size());
        assertEquals(1, second.alreadyRevoked().size());
        assertEquals(
                first.accepted().getFirst().revokedAt(),
                second.alreadyRevoked().getFirst().revokedAt());
        assertEquals(
                "1",
                db.queryForObject(
                        "SELECT count(*) FROM deletion_command_inbox WHERE operation_id=?", String.class, operation));
    }

    @Test
    void statusReportsTruthfulCountsAndUnknownFenceIsNotFound() {
        UUID quiz = UUID.randomUUID(), operation = UUID.randomUUID();
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                .bodyValue(body(quiz.toString(), operation.toString()))
                .exchange()
                .expectStatus()
                .isEqualTo(200);

        http.get()
                .uri("/internal/security/quiz-revocations/" + quiz + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                .exchange()
                .expectStatus()
                .isEqualTo(200)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody(QuizRevocationService.Status.class)
                .value(status -> {
                    assertEquals(quiz, status.quizId());
                    assertNotNull(status.revokedAt());
                    assertNull(status.acknowledgedAt());
                    assertEquals(0, status.publicationCount());
                    assertEquals(0, status.attemptCount());
                });

        http.get()
                .uri("/internal/security/quiz-revocations/" + UUID.randomUUID() + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                .exchange()
                .expectStatus()
                .isEqualTo(404)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("REVOCATION_NOT_FOUND");

        // Status requires the same exact caller and scope; no other transport is admitted.
        http.get()
                .uri("/internal/security/quiz-revocations/" + quiz + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service("quiz-service", "quiz.deletion.read"))
                .exchange()
                .expectStatus()
                .isEqualTo(403);
        http.get()
                .uri("/internal/security/quiz-revocations/" + quiz + "/status?x=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller())
                .exchange()
                .expectStatus()
                .isEqualTo(400);
        http.get()
                .uri("/internal/security/quiz-revocations/" + quiz + "/status")
                .exchange()
                .expectStatus()
                .isEqualTo(401);
    }
}
