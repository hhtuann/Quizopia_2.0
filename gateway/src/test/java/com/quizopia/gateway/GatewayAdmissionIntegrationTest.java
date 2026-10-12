package com.quizopia.gateway;

import static com.quizopia.gateway.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Signed local USER verification plus real HTTP dependency stubs for isolated failures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import(GatewayAdmissionIntegrationTest.Keys.class)
class GatewayAdmissionIntegrationTest {
    static final AtomicInteger ADMISSIONS = new AtomicInteger(), BACKEND_CALLS = new AtomicInteger();
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicReference<String> BODY = new AtomicReference<>(),
            BACKEND_AUTH = new AtomicReference<>(),
            EVIDENCE = new AtomicReference<>();
    static final AtomicBoolean SLOW = new AtomicBoolean();
    static final DisposableServer IDENTITY = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> {
                if (request.uri().equals("/oauth2/token"))
                    return request.receive()
                            .then(response.header("Content-Type", "application/json")
                                    .sendString(
                                            Mono.just(
                                                    "{\"access_token\":\"service-only\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"identity.user-admission\"}"))
                                    .then());
                ADMISSIONS.incrementAndGet();
                assertEquals("/internal/security/user-admissions", request.uri());
                assertEquals("Bearer service-only", request.requestHeaders().get("Authorization"));
                EVIDENCE.set(request.requestHeaders().get("X-Quizopia-User-Evidence"));
                assertNull(request.requestHeaders().get("Cookie"));
                return response.status(STATUS.get())
                        .header("Content-Type", "application/json")
                        .sendString(SLOW.get() ? Mono.never() : Mono.just(BODY.get()));
            })
            .bindNow();
    static final DisposableServer BACKEND = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> {
                BACKEND_CALLS.incrementAndGet();
                BACKEND_AUTH.set(request.requestHeaders().get("Authorization"));
                assertNull(request.requestHeaders().get("X-Quizopia-User-Evidence"));
                assertNull(request.requestHeaders().get("X-User-Id"));
                return response.header("Content-Type", "application/json").sendString(Mono.just("{}"));
            })
            .bindNow();

    @Value("${local.server.port}")
    int port;

    WebTestClient http;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("ASSESSMENT_SERVICE_URL", () -> "http://127.0.0.1:" + BACKEND.port());
        p.add("quizopia.publication.enabled", () -> true);
        p.add("CLASSROOM_SERVICE_URL", () -> "http://127.0.0.1:" + BACKEND.port());
        p.add("QUIZ_SERVICE_URL", () -> "http://127.0.0.1:" + BACKEND.port());
        p.add("quizopia.admission.enabled", () -> true);
        p.add("quizopia.admission.allow-insecure-loopback", () -> true);
        p.add("quizopia.admission.identity-base-url", () -> "http://127.0.0.1:" + IDENTITY.port());
        p.add("quizopia.admission.client-id", () -> "gateway");
        p.add("quizopia.admission.client-secret", () -> "fixture-only");
        p.add("quizopia.admission.connect-timeout", () -> "PT0.2S");
        p.add("quizopia.admission.response-timeout", () -> "PT0.5S");
        p.add("quizopia.admission.total-timeout", () -> "PT1S");
    }

    @BeforeEach
    void setup() {
        http = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
        STATUS.set(200);
        BODY.set("{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\",\"TEACHER\"]}");
        SLOW.set(false);
        ADMISSIONS.set(0);
        BACKEND_CALLS.set(0);
    }

    @AfterAll
    static void stop() {
        IDENTITY.disposeNow();
        BACKEND.disposeNow();
    }

    @Test
    void eachPublicationOperationRequiresFreshAdmissionAndPreservesOriginalUser() {
        String token = user();
        for (String operation : List.of(
                "POST /api/assessments/publications",
                "GET /api/assessments/publications/" + ACTOR,
                "PUT /api/assessments/publications/" + ACTOR,
                "POST /api/assessments/publications/" + ACTOR + "/open",
                "PATCH /api/assessments/publications/" + ACTOR + "/schedule",
                "POST /api/assessments/publications/entry",
                "POST /api/assessments/publications/" + ACTOR + "/entry")) {
            var parts = operation.split(" ");
            http.method(HttpMethod.valueOf(parts[0]))
                    .uri(parts[1])
                    .headers(h -> h.setBearerAuth(token))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store");
            assertEquals("Bearer " + token, BACKEND_AUTH.get());
        }
        assertEquals(7, ADMISSIONS.get());
        assertEquals(7, BACKEND_CALLS.get());
        STATUS.set(503);
        http.post()
                .uri("/api/assessments/publications")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isEqualTo(503);
        assertEquals(7, BACKEND_CALLS.get());
    }

    @Test
    void credentialsAndAdmissionBodyAreNotLogged(CapturedOutput output) {
        String token = user();
        http.post()
                .uri("/classrooms")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk();
        assertFalse(output.getAll().contains(token));
        assertFalse(output.getAll().contains("service-only"));
        assertFalse(output.getAll().contains("fixture-only"));
        assertFalse(output.getAll().contains(BODY.get()));
    }

    @Test
    void validUserAdmittedPerRequestOriginalAuthorizationPreservedAndSpoofsStripped() {
        String token = user();
        for (int i = 0; i < 2; i++)
            http.post()
                    .uri("/classrooms")
                    .headers(h -> {
                        h.setBearerAuth(token);
                        h.set("X-Quizopia-User-Evidence", "spoof");
                        h.set("X-User-Id", "spoof");
                    })
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store");
        assertEquals(2, ADMISSIONS.get());
        assertEquals(2, BACKEND_CALLS.get());
        assertEquals("Bearer " + token, BACKEND_AUTH.get());
        assertEquals(token, EVIDENCE.get());
    }

    @Test
    void missingMalformedDuplicateAndInvalidSignedTokensNeverInvokeDependencies() {
        http.post().uri("/classrooms").exchange().expectStatus().isUnauthorized();
        var now = Instant.now();
        List<String> invalid = List.of(
                "malformed",
                sign(
                        key(),
                        ISSUER,
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        now.minusSeconds(5),
                        now.plusSeconds(300)),
                sign(
                        KEY,
                        "https://wrong.example",
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        now.minusSeconds(5),
                        now.plusSeconds(300)),
                sign(
                        KEY,
                        ISSUER,
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        now.minusSeconds(300),
                        now.minusSeconds(120)));
        for (String token : invalid)
            http.post()
                    .uri("/classrooms")
                    .headers(h -> h.setBearerAuth(token))
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
        http.post()
                .uri("/classrooms")
                .header("Authorization", "Bearer " + user(), "Bearer " + user())
                .exchange()
                .expectStatus()
                .isUnauthorized();
        assertEquals(0, ADMISSIONS.get());
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void serviceAndStudentCannotPerformTeacherCommand() {
        var now = Instant.now();
        for (String token : List.of(
                sign(
                        KEY,
                        ISSUER,
                        "assessment-service",
                        "SERVICE",
                        Map.of("scope", List.of("identity.user-admission")),
                        now.minusSeconds(5),
                        now.plusSeconds(300)),
                sign(
                        KEY,
                        ISSUER,
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT")),
                        now.minusSeconds(5),
                        now.plusSeconds(300))))
            http.post()
                    .uri("/classrooms")
                    .headers(h -> h.setBearerAuth(token))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        assertEquals(0, ADMISSIONS.get());
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void authoritativeUser401AndDependency401AreDistinct() {
        STATUS.set(401);
        BODY.set("{\"code\":\"USER_EVIDENCE_INVALID\"}");
        post().expectStatus().isUnauthorized().expectBody().json("{\"code\":\"UNAUTHENTICATED\"}");
        BODY.set("{\"code\":\"SERVICE_AUTHENTICATION_INVALID\"}");
        post().expectStatus().isEqualTo(503).expectBody().json("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void unavailableMalformedMismatchTimeoutAndRecoveryNeverReusePositiveVerdict() {
        post().expectStatus().isOk();
        BACKEND_CALLS.set(0);
        STATUS.set(503);
        BODY.set("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
        post().expectStatus().isEqualTo(503);
        STATUS.set(200);
        BODY.set("{\"actorId\":\"" + java.util.UUID.randomUUID() + "\",\"roles\":[\"STUDENT\",\"TEACHER\"]}");
        post().expectStatus().isEqualTo(503);
        BODY.set("invalid");
        post().expectStatus().isEqualTo(503);
        SLOW.set(true);
        post().expectStatus().isEqualTo(503);
        assertEquals(0, BACKEND_CALLS.get());
        SLOW.set(false);
        BODY.set("{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\",\"TEACHER\"]}");
        post().expectStatus().isOk();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/classrooms",
                "/classrooms/",
                "/classrooms/" + ACTOR + "/members",
                "/api/classes/" + ACTOR,
                "/api/assessments/publications",
                "/attempts/" + ACTOR,
                "/api/proctoring/sessions",
                "/internal/security/user-admissions",
                "/oauth2/token"
            })
    void futureAndInternalRoutesStayClosed(String path) {
        http.method(HttpMethod.DELETE)
                .uri(path)
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertEquals(0, ADMISSIONS.get());
        assertEquals(0, BACKEND_CALLS.get());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/cl%61ssrooms",
                "/classrooms%2f" + ACTOR,
                "/classrooms//" + ACTOR,
                "/classrooms;parameter",
                "/api/%61ssessments/publications"
            })
    void noncanonicalPathsCannotBypassInventory(String path) {
        http.post()
                .uri(java.net.URI.create("http://127.0.0.1:" + port + path))
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isBadRequest();
        assertEquals(0, ADMISSIONS.get());
        assertEquals(0, BACKEND_CALLS.get());
    }

    @Test
    void preflightAndQuizRemainOutsideAdmission() {
        http.options()
                .uri("/classrooms")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectStatus()
                .isOk();
        http.get()
                .uri("/api/quizzes")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isOk();
        assertEquals(0, ADMISSIONS.get());
        assertEquals(1, BACKEND_CALLS.get());
    }

    WebTestClient.ResponseSpec post() {
        return http.post()
                .uri("/classrooms")
                .headers(h -> h.setBearerAuth(user()))
                .exchange();
    }

    @TestConfiguration
    static class Keys {
        @Bean
        @Primary
        ReactiveJwtDecoder decoder() throws Exception {
            var decoder =
                    NimbusReactiveJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
}
