package com.quizopia.assessment;

import static com.quizopia.assessment.AdmissionTestTokens.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Dedicated context: a one-request rolling minute proves the private surface is rate bounded. */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.deletion.enabled=true",
            "quizopia.deletion.quiz-service-client-id=quiz-service",
            "quizopia.deletion.allow-insecure-loopback=true",
            "quizopia.deletion.requests-per-minute=1"
        })
@Import(ClosedBusinessBoundaryTest.Keys.class)
class QuizRevocationRateLimitIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("fb015_assessment_roles.sql");

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

    WebTestClient http;

    @BeforeEach
    void setup() {
        http = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void rollingPerMinuteBudgetBoundIsEnforcedWithNoStore() {
        String caller = sign(
                KEY,
                ISSUER,
                "quiz-service",
                "SERVICE",
                Map.of("scope", List.of("quiz.deletion.revoke")),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
        String payload = "{\"operations\":[{\"quizId\":\"" + UUID.randomUUID() + "\",\"operationId\":\""
                + UUID.randomUUID() + "\"}]}";
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller)
                .bodyValue(payload)
                .exchange()
                .expectStatus()
                .isEqualTo(200);
        http.post()
                .uri("/internal/security/quiz-revocations/batch")
                .contentType(APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller)
                .bodyValue(payload)
                .exchange()
                .expectStatus()
                .isEqualTo(429)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("REVOCATION_RATE_LIMITED");
    }
}
