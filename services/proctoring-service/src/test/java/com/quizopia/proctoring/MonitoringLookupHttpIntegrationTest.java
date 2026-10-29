package com.quizopia.proctoring;

import static com.quizopia.proctoring.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.application.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP, RS256 validation and PostgreSQL. Identity/Assessment are explicit dependency doubles. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.proctoring.monitoring.enabled=true",
            "quizopia.admission.enabled=true",
            "quizopia.admission.allow-insecure-loopback=true",
            "quizopia.admission.client-id=proctoring-http-fixture",
            "quizopia.admission.client-secret=synthetic-fixture-secret",
            "logging.level.root=WARN"
        })
@ActiveProfiles("local")
@Import(MonitoringLookupHttpIntegrationTest.Fixture.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MonitoringLookupHttpIntegrationTest {
    static final String BASE = "/api/proctoring/monitoring/sessions";
    static final AtomicInteger ADMISSIONS = new AtomicInteger();
    static final AtomicInteger ADMISSION_STATUS = new AtomicInteger(200);
    static final AtomicBoolean AUTHORITY_UNAVAILABLE = new AtomicBoolean();
    static final AtomicBoolean ACTIVE = new AtomicBoolean(true);
    static final AtomicReference<Instant> DEADLINE = new AtomicReference<>();
    static final UUID TEACHER = UUID.randomUUID();
    static final AtomicInteger AUTHORITY_CALLS = new AtomicInteger();
    static final UUID STUDENT = UUID.fromString(ACTOR);
    static final UUID PUBLICATION = UUID.randomUUID();
    static final UUID CLASSROOM = UUID.randomUUID();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");

    static final DisposableServer IDENTITY = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> {
                if (request.uri().equals("/oauth2/token")) {
                    return request.receive().aggregate().asString().flatMap(body -> response.header(
                                    "Content-Type", "application/json")
                            .sendString(
                                    Mono.just(
                                            "{\"access_token\":\"synthetic-service-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"identity.user-admission\"}"))
                            .then());
                }
                ADMISSIONS.incrementAndGet();
                int status = ADMISSION_STATUS.get();
                String body;
                if (status == 200) {
                    var token = request.requestHeaders().get("X-Quizopia-User-Evidence");
                    var claims = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
                    body = "{\"actorId\":\"" + claims.path("sub").asString()
                            + "\",\"roles\":[\"STUDENT\",\"TEACHER\"]}";
                } else {
                    body = status == 401
                            ? "{\"code\":\"USER_EVIDENCE_INVALID\"}"
                            : "{\"code\":\"AUTHORITY_UNAVAILABLE\"}";
                }
                return response.status(status)
                        .header("Content-Type", "application/json")
                        .sendString(Mono.just(body));
            })
            .bindNow();

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("quizopia.admission.identity-base-url", () -> "http://127.0.0.1:" + IDENTITY.port());
        registry.add("quizopia.proctoring.monitoring.assessment-base-url", () -> "http://127.0.0.1:" + IDENTITY.port());
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate db;

    UUID attempt;

    @BeforeEach
    void fixture() {
        db.execute("TRUNCATE monitoring_access_audit,monitoring_evidence,monitoring_sessions RESTART IDENTITY CASCADE");
        ADMISSION_STATUS.set(200);
        ADMISSIONS.set(0);
        ACTIVE.set(true);
        AUTHORITY_CALLS.set(0);
        AUTHORITY_UNAVAILABLE.set(false);
        DEADLINE.set(Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS));
        attempt = UUID.randomUUID();
    }

    @AfterAll
    static void stopDependency() {
        IDENTITY.disposeNow();
    }

    WebTestClient http() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    String token(String subject, List<String> roles) {
        var now = Instant.now();
        return sign(KEY, ISSUER, subject, "USER", Map.of("roles", roles), now.minusSeconds(1), now.plusSeconds(600));
    }

    String create() {
        byte[] response = http().post()
                .uri(BASE)
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue("{\"attemptId\":\"" + attempt + "\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("ACTIVE")
                .returnResult()
                .getResponseBody();
        assertNotNull(response);
        return JSON.readTree(response).path("id").asString();
    }

    String event(UUID id, long sequence) {
        return "{\"eventId\":\"" + id + "\",\"streamId\":\"" + attempt + "\",\"sequence\":" + sequence
                + ",\"type\":\"WINDOW_FOCUS\",\"schemaVersion\":1}";
    }

    int evidenceCount() {
        return db.queryForObject("SELECT count(*) FROM monitoring_evidence", Integer.class);
    }

    String lookup() {
        return BASE + "/by-attempt/" + attempt;
    }

    String teacher() {
        return token(TEACHER.toString(), List.of("STUDENT", "TEACHER"));
    }

    @Test
    void existingSessionResolvesForCurrentOwningTeacherAndRecordsReadAudit() {
        String session = create();
        http().get()
                .uri(lookup())
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(session)
                .jsonPath("$.attemptId")
                .isEqualTo(attempt.toString())
                .jsonPath("$.studentId")
                .isEqualTo(STUDENT.toString())
                .jsonPath("$.status")
                .isEqualTo("ACTIVE");
        assertEquals(2, AUTHORITY_CALLS.get());
        assertEquals(2, ADMISSIONS.get());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM monitoring_sessions", Integer.class));
        assertEquals(1, evidenceCount());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_access_audit WHERE actor_id=? AND viewer_role='TEACHER' AND action='SESSION_READ'",
                        Integer.class,
                        TEACHER));
    }

    @Test
    void lookupNeverCreatesSessionAndChecksAuthorityBeforeLocalExistence() {
        AUTHORITY_UNAVAILABLE.set(true);
        http().get()
                .uri(lookup())
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .json("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
        AUTHORITY_UNAVAILABLE.set(false);
        http().get()
                .uri(lookup())
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .json("{\"code\":\"PROCTORING_NOT_ACCESSIBLE\"}");
        assertEquals(2, AUTHORITY_CALLS.get());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM monitoring_sessions", Integer.class));
        assertEquals(0, evidenceCount());
    }

    @Test
    void peerTeacherAndStudentCannotDiscoverExistingSession() {
        create();
        http().get()
                .uri(lookup())
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isNotFound();
        http().get()
                .uri(lookup())
                .headers(h -> h.setBearerAuth(token(STUDENT.toString(), List.of("STUDENT"))))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertEquals(0, db.queryForObject("SELECT count(*) FROM monitoring_access_audit", Integer.class));
    }

    @Test
    void anonymousServiceWrongSignatureAndPrivateAliasRemainClosed() {
        create();
        http().get().uri(lookup()).exchange().expectStatus().isUnauthorized();
        var now = Instant.now();
        for (String jwt : List.of(
                sign(
                        KEY,
                        ISSUER,
                        "proctoring-client",
                        "SERVICE",
                        Map.of("scope", "assessment.proctoring-authority.read"),
                        now.minusSeconds(1),
                        now.plusSeconds(60)),
                sign(
                        key(),
                        ISSUER,
                        TEACHER.toString(),
                        "USER",
                        Map.of("roles", List.of("STUDENT", "TEACHER")),
                        now.minusSeconds(1),
                        now.plusSeconds(60)))) {
            http().get()
                    .uri(lookup())
                    .headers(h -> h.setBearerAuth(jwt))
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
        }
        http().get()
                .uri("/internal/proctoring/monitoring/sessions/by-attempt/" + attempt)
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertEquals(1, AUTHORITY_CALLS.get());
    }

    @Test
    void queryAndBodyCannotExpandLookupAndIdentityOutageIsNotCached() {
        create();
        http().get()
                .uri(lookup() + "?studentId=" + STUDENT)
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isBadRequest();
        http().method(org.springframework.http.HttpMethod.GET)
                .uri(lookup())
                .headers(h -> h.setBearerAuth(teacher()))
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        http().get()
                .uri(lookup())
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isOk();
        ADMISSION_STATUS.set(503);
        http().get()
                .uri(lookup())
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .json("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
        assertEquals(2, AUTHORITY_CALLS.get());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM monitoring_access_audit", Integer.class));
    }

    @Test
    void terminalAuthorityEndsLocalSessionWithoutCreatingAnotherOne() {
        String session = create();
        ACTIVE.set(false);
        http().get()
                .uri(lookup())
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(session)
                .jsonPath("$.status")
                .isEqualTo("ENDED");
        http().get()
                .uri(lookup())
                .headers(h -> h.setBearerAuth(teacher()))
                .exchange()
                .expectStatus()
                .isOk();
        assertEquals(1, db.queryForObject("SELECT count(*) FROM monitoring_sessions", Integer.class));
        assertEquals(2, evidenceCount());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM monitoring_access_audit", Integer.class));
    }

    @TestConfiguration
    static class Fixture {
        @Bean
        @Primary
        JwtDecoder decoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }

        @Bean
        @Primary
        MonitoringAuthority authoritativeDependencyDouble() {
            return (actor, attempt, role) -> {
                AUTHORITY_CALLS.incrementAndGet();
                if (role.equals("TEACHER") && !actor.id().equals(TEACHER))
                    throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
                if (AUTHORITY_UNAVAILABLE.get()) throw MonitoringFailure.unavailable();
                return new MonitoringAuthority.Snapshot(
                        actor.id(),
                        role,
                        attempt,
                        PUBLICATION,
                        CLASSROOM,
                        STUDENT,
                        ACTIVE.get(),
                        DEADLINE.get(),
                        Instant.now());
            };
        }
    }
}
