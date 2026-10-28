package com.quizopia.assessment;

import static com.quizopia.assessment.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.quizopia.assessment.application.PublicationStore;
import com.quizopia.assessment.domain.PublicationConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;

/**
 * Flag-off behavior: the private command surface stays closed (503) while the compiled route
 * gate still enforces a reconciliation-installed fence row. Existing behavior with an empty
 * fence table is proven by the unchanged pre-existing suite.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.deletion.enabled=false",
            "quizopia.deletion.quiz-service-client-id=quiz-service",
            "quizopia.publication.enabled=true"
        })
@Import(ClosedBusinessBoundaryTest.Keys.class)
class QuizRevocationDisabledIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", DB::getJdbcUrl);
        values.add("spring.datasource.username", DB::getUsername);
        values.add("spring.datasource.password", DB::getPassword);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate db;

    @Autowired
    PublicationStore publications;

    @MockitoBean
    com.quizopia.assessment.security.admission.IdentityAdmissionClient admission;

    WebTestClient http;
    UUID owner = UUID.fromString(ACTOR);
    UUID teacher = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(admission.admit(any(), any())).thenReturn(Mono.empty());
        http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    String teacher() {
        return sign(
                KEY,
                ISSUER,
                teacher.toString(),
                "USER",
                Map.of("roles", List.of("TEACHER")),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
    }

    String service() {
        return sign(
                KEY,
                ISSUER,
                "quiz-service",
                "SERVICE",
                Map.of("scope", List.of("quiz.deletion.revoke")),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
    }

    @Test
    void commandSurfaceStaysClosedWhileFlagIsOff() {
        UUID quiz = UUID.randomUUID();
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + service())
                .bodyValue("{\"operations\":[{\"quizId\":\"" + quiz + "\",\"operationId\":\"" + UUID.randomUUID()
                        + "\"}]}")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTHORITY_UNAVAILABLE");
        assertEquals(
                "0",
                db.queryForObject("SELECT count(*) FROM quiz_revocation_fences WHERE quiz_id=?", String.class, quiz));
    }

    @Test
    void compiledGateStillEnforcesReconciliationFenceWithFlagOff() {
        UUID quiz = UUID.randomUUID();
        Instant time = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var created = publications.create(
                teacher,
                UUID.randomUUID().toString(),
                new PublicationConfiguration(
                        "Flag-off fixture",
                        null,
                        quiz,
                        UUID.randomUUID(),
                        1,
                        PublicationConfiguration.Audience.CLASS,
                        UUID.randomUUID(),
                        time.minusSeconds(86400),
                        time.plusSeconds(86400),
                        30,
                        PublicationConfiguration.MonitoringChoice.ENABLED,
                        1));
        db.update(
                "INSERT INTO quiz_revocation_fences(quiz_id,revoked_at,operation_id,source,ack_at) VALUES(?,clock_timestamp(),?,'RECONCILE',NULL)",
                quiz,
                UUID.randomUUID());
        http.get()
                .uri("/api/assessments/publications/" + created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + teacher())
                .exchange()
                .expectStatus()
                .isEqualTo(404)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ASSESSMENT_REMOVED");
    }
}
