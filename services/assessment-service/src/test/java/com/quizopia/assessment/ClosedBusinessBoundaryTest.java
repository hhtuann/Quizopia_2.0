package com.quizopia.assessment;

import static com.quizopia.assessment.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.assessment.features.attempts-enabled=false",
            "quizopia.assessment.features.results-enabled=false",
            "quizopia.assessment.features.closure-enabled=true"
        })
@ActiveProfiles("test")
@Import(ClosedBusinessBoundaryTest.Keys.class)
class ClosedBusinessBoundaryTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.quizopia.assessment.application.ClosureStore closureStore;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.quizopia.assessment.application.ClosureDependencies closureDependencies;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.quizopia.assessment.application.StudentAttemptStore studentStore;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.quizopia.assessment.application.AttemptFinalization finalization;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.quizopia.assessment.application.AttemptPersistence attempts;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.quizopia.assessment.application.PublicationStore store;

    @Value("${local.server.port}")
    int port;

    WebTestClient http() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/publications",
                "/attempts/" + ACTOR,
                "/api/proctoring/sessions",
                "/proctoring/token",
                "/internal/authority",
                "/unexpected-business"
            })
    void signedUserCannotOpenFutureOrInternalRoute(String path) {
        http().post()
                .uri(path)
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store");
    }

    @Test
    void publicationIsDefaultDisabled() {
        http().post()
                .uri("/api/assessments/publications")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503);
    }

    @Test
    void serviceTokenCannotOpenUserOperationAndWrongIssuerDenied() {
        var now = Instant.now();
        var service = sign(
                KEY,
                ISSUER,
                "gateway",
                "SERVICE",
                Map.of("scope", List.of("identity.user-admission")),
                now.minusSeconds(5),
                now.plusSeconds(300));
        http().post()
                .uri("/publications")
                .headers(h -> h.setBearerAuth(service))
                .exchange()
                .expectStatus()
                .isForbidden();
        var wrong = sign(
                KEY,
                "https://wrong.test",
                ACTOR,
                "USER",
                Map.of("roles", List.of("STUDENT")),
                now.minusSeconds(5),
                now.plusSeconds(300));
        http().post()
                .uri("/publications")
                .headers(h -> h.setBearerAuth(wrong))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void spoofedHeadersMissingAndMalformedAuthorizationCannotOpenBoundary() {
        http().post()
                .uri("/publications")
                .header("X-Quizopia-Admitted", "true")
                .header("X-User-Id", ACTOR)
                .exchange()
                .expectStatus()
                .isUnauthorized();
        http().post()
                .uri("/publications")
                .header("Authorization", "Bearer malformed")
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    static java.util.List<String[]> studentRoutes() {
        String a = "/api/assessments/attempts/" + ACTOR, p = "/api/assessments/publications/" + ACTOR;
        return java.util.List.of(
                new String[] {"POST", p + "/attempts/start"},
                new String[] {"POST", p + "/attempts/retake"},
                new String[] {"GET", a},
                new String[] {"POST", a + "/questions/1/answer"},
                new String[] {"POST", a + "/submit"},
                new String[] {"POST", a + "/abandon"},
                new String[] {"GET", a + "/result"},
                new String[] {"GET", a + "/review"},
                new String[] {"GET", p + "/aggregate-score"});
    }

    @Test
    void everyStudentRouteIsDefaultDisabledAndFutureRoutesStayDenied() {
        for (var op : studentRoutes()) {
            http().method(org.springframework.http.HttpMethod.valueOf(op[0]))
                    .uri(op[1])
                    .headers(h -> h.setBearerAuth(user()))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store");
        }
    }

    @TestConfiguration
    static class Keys {
        @Bean
        @Primary
        JwtDecoder decoder() throws Exception {
            var d = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            d.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return d;
        }
    }
}
