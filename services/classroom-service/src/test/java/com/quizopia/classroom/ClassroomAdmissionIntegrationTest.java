package com.quizopia.classroom;

import static com.quizopia.classroom.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.quizopia.classroom.api.*;
import com.quizopia.classroom.application.*;
import com.quizopia.classroom.configuration.SecurityConfiguration;
import com.quizopia.classroom.domain.Classroom;
import com.quizopia.classroom.security.*;
import com.quizopia.classroom.security.admission.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Direct downstream HTTP with real signed USER validation and isolated Identity HTTP failures. */
@SpringBootTest(
        classes = ClassroomAdmissionIntegrationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ClassroomAdmissionIntegrationTest {
    static final AtomicInteger STATUS = new AtomicInteger(200), CALLS = new AtomicInteger();
    static final AtomicReference<String> BODY = new AtomicReference<>();
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
                                                    "{\"access_token\":\"classroom-service-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"identity.user-admission\"}"))
                                    .then());
                CALLS.incrementAndGet();
                assertEquals(
                        "Bearer classroom-service-token",
                        request.requestHeaders().get("Authorization"));
                assertNotNull(request.requestHeaders().get("X-Quizopia-User-Evidence"));
                return response.status(STATUS.get())
                        .header("Content-Type", "application/json")
                        .sendString(SLOW.get() ? Mono.never() : Mono.just(BODY.get()));
            })
            .bindNow();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("quizopia.admission.enabled", () -> true);
        p.add("quizopia.admission.allow-insecure-loopback", () -> true);
        p.add("quizopia.admission.identity-base-url", () -> "http://127.0.0.1:" + IDENTITY.port());
        p.add("quizopia.admission.client-id", () -> "classroom-service");
        p.add("quizopia.admission.client-secret", () -> "fixture-only");
        p.add("quizopia.admission.connect-timeout", () -> "PT0.2S");
        p.add("quizopia.admission.response-timeout", () -> "PT0.5S");
        p.add("quizopia.admission.total-timeout", () -> "PT1S");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    ClassroomApplicationService service;

    WebTestClient http;

    @BeforeEach
    void setup() {
        reset(service);
        CALLS.set(0);
        STATUS.set(200);
        SLOW.set(false);
        BODY.set("{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\",\"TEACHER\"]}");
        http = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @AfterAll
    static void stop() {
        IDENTITY.disposeNow();
    }

    @Test
    void successfulAdmissionPreservesOwnerAuthorization() {
        UUID id = UUID.randomUUID(), actor = UUID.fromString(ACTOR);
        when(service.getOwnedClassroom(actor, id)).thenThrow(new ClassroomOwnershipDeniedException(id, actor));
        http.get()
                .uri("/classrooms/" + id)
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden();
        verify(service).getOwnedClassroom(actor, id);
        assertEquals(1, CALLS.get());
    }

    @Test
    void validTeacherCreatesWithTrustedActor() {
        UUID id = UUID.randomUUID();
        when(service.create(any(), any()))
                .thenReturn(new Classroom(id, UUID.fromString(ACTOR), "Class", "Subject", "Grade", null));
        http.post()
                .uri("/classrooms")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue("{\"name\":\"Class\",\"subject\":\"Subject\",\"grade\":\"Grade\"}")
                .exchange()
                .expectStatus()
                .isCreated();
        verify(service).create(eq(UUID.fromString(ACTOR)), any());
    }

    @Test
    void revokedUnavailableRoleMismatchActorMismatchAndTimeoutCannotReachBusiness() {
        STATUS.set(401);
        BODY.set("{\"code\":\"USER_EVIDENCE_INVALID\"}");
        get().expectStatus().isUnauthorized();
        STATUS.set(503);
        BODY.set("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
        get().expectStatus().isEqualTo(503);
        STATUS.set(200);
        BODY.set("{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\"]}");
        get().expectStatus().isForbidden();
        BODY.set("{\"actorId\":\"" + UUID.randomUUID() + "\",\"roles\":[\"STUDENT\",\"TEACHER\"]}");
        get().expectStatus().isEqualTo(503);
        SLOW.set(true);
        get().expectStatus().isEqualTo(503);
        verifyNoInteractions(service);
    }

    @Test
    void missingMalformedWrongIssuerAndServiceCannotBypass() {
        http.get().uri("/classrooms/" + ACTOR).exchange().expectStatus().isUnauthorized();
        http.get()
                .uri("/classrooms/" + ACTOR)
                .header("Authorization", "Bearer invalid")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        var now = Instant.now();
        for (var token : List.of(
                sign(
                        KEY,
                        "https://wrong.test",
                        ACTOR,
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        now.minusSeconds(5),
                        now.plusSeconds(300)),
                sign(
                        KEY,
                        ISSUER,
                        "gateway",
                        "SERVICE",
                        Map.of("scope", List.of("identity.user-admission")),
                        now.minusSeconds(5),
                        now.plusSeconds(300)))) {
            http.get()
                    .uri("/classrooms/" + ACTOR)
                    .headers(h -> h.setBearerAuth(token))
                    .exchange()
                    .expectStatus()
                    .value(status -> assertTrue(status == 401 || status == 403));
        }
        assertEquals(0, CALLS.get());
        verifyNoInteractions(service);
    }

    @Test
    void spoofedAdmissionEvidenceAndUnknownNestedRoutesAreRejected() {
        http.get()
                .uri("/classrooms/" + ACTOR)
                .headers(h -> h.setBearerAuth(user()))
                .header("X-Quizopia-Admitted", "true")
                .exchange()
                .expectStatus()
                .isBadRequest();
        http.post()
                .uri("/classrooms/" + ACTOR + "/members")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden();
        http.post()
                .uri("/internal/teacher-authority")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertEquals(0, CALLS.get());
        verifyNoInteractions(service);
    }

    WebTestClient.ResponseSpec get() {
        return http.get()
                .uri("/classrooms/" + ACTOR)
                .headers(h -> h.setBearerAuth(user()))
                .exchange();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
        SecurityConfiguration.class,
        QuizopiaJwtAuthenticationConverter.class,
        ClassroomSecurityErrorHandler.class,
        AdmissionClientConfiguration.class,
        ClassroomController.class,
        ClassroomApiExceptionHandler.class,
        AuthenticatedUserIdResolver.class
    })
    static class App {
        @Bean
        ClassroomApplicationService service() {
            return mock(ClassroomApplicationService.class);
        }

        @Bean
        JwtDecoder decoder() throws Exception {
            var d = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            d.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return d;
        }
    }
}
