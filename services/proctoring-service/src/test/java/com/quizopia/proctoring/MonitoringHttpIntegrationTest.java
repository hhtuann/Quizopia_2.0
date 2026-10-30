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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
            "logging.level.root=DEBUG"
        })
@ActiveProfiles("local")
@Import(MonitoringHttpIntegrationTest.Fixture.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MonitoringHttpIntegrationTest {
    static final String BASE = "/api/proctoring/monitoring/sessions";
    static final AtomicInteger ADMISSIONS = new AtomicInteger();
    static final AtomicInteger ADMISSION_STATUS = new AtomicInteger(200);
    static final AtomicBoolean AUTHORITY_UNAVAILABLE = new AtomicBoolean();
    static final AtomicBoolean ACTIVE = new AtomicBoolean(true);
    static final AtomicReference<Instant> DEADLINE = new AtomicReference<>();
    static final UUID STUDENT = UUID.fromString(ACTOR);
    static final UUID PUBLICATION = UUID.randomUUID();
    static final UUID CLASSROOM = UUID.randomUUID();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("fb015_proctoring_roles.sql");

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

    static java.util.stream.Stream<String> invalidWireRepresentations() {
        return java.util.stream.Stream.of(
                "sequence=1.9",
                "questionNumber=2.9",
                "schemaVersion=1.9",
                "observedAt=123",
                "sequence=\"1\"",
                "questionNumber=\"2\"",
                "schemaVersion=\"1\"",
                "sequence=true",
                "questionNumber=false",
                "schemaVersion=true",
                "sequence=1e0",
                "sequence=1.0",
                "questionNumber=2e0",
                "schemaVersion=1e0",
                "sequence=9223372036854775808",
                "questionNumber=2147483648",
                "observedAt=\"invalid-time\"",
                "observedAt=true",
                "observedAt={}",
                "observedAt=[]",
                "sequence=null",
                "schemaVersion=null",
                "questionNumber=null",
                "type=null",
                "eventId=null",
                "streamId=null",
                "observedAt=\"2026-10-15T24:00:00Z\"",
                "observedAt=\"2026-10-15T01:02:03.Z\"",
                "observedAt=\"2026-10-15T01:02:03+01:00:15\"",
                "trailing",
                "duplicate");
    }

    List<String> durableState() {
        var state = new ArrayList<String>();
        for (String table : List.of("monitoring_sessions", "monitoring_evidence", "monitoring_access_audit"))
            state.addAll(db.queryForList(
                    "SELECT row_to_json(t)::text FROM " + table + " t ORDER BY row_to_json(t)::text", String.class));
        return state;
    }

    @ParameterizedTest
    @MethodSource("invalidWireRepresentations")
    void rejectedWireFormsAreMutationFreeAndDoNotReserveReplayIdentity(String malformed) {
        String session = create();
        UUID id = UUID.randomUUID();
        String valid = event(id, 1)
                .replace("WINDOW_FOCUS", "QUESTION_VIEWED")
                .replace("}", ",\"questionNumber\":2,\"observedAt\":\"2026-01-01T00:00:00Z\"}");
        String bad;
        if (malformed.equals("trailing")) bad = valid + "{}";
        else if (malformed.equals("duplicate")) bad = valid.replace("}", ",\"sequence\":2}");
        else {
            String[] pair = malformed.split("=", 2);
            bad = valid.replaceAll(
                    "\"" + pair[0] + "\":(?:\"[^\"]*\"|[0-9]+)",
                    java.util.regex.Matcher.quoteReplacement("\"" + pair[0] + "\":" + pair[1]));
            assertNotEquals(valid, bad);
        }
        var before = durableState();
        http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue(bad)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .json("{\"code\":\"INVALID_REQUEST\"}");
        assertEquals(before, durableState(), "Rejected input changed session/evidence/audit/replay state");
        byte[] accepted = http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue(valid)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue(valid)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(response -> assertArrayEquals(accepted, response.getResponseBody()));
        assertEquals(2, evidenceCount());
    }

    @Test
    void validNullAndIsoTimestampWireRemainCompatible() {
        String session = create();
        int sequence = 0;
        for (String stamp : List.of(
                "null", "\"2026-01-01T00:00:00Z\"", "\"2026-01-01t00:00:00z\"", "\"2026-01-01T01:00:00+01:00\"")) {
            String body = event(UUID.randomUUID(), ++sequence).replace("}", ",\"observedAt\":" + stamp + "}");
            http().post()
                    .uri(BASE + "/" + session + "/events")
                    .headers(h -> h.setBearerAuth(user()))
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
        assertEquals(5, evidenceCount());
    }

    @Test
    void createAppendReplayTimelineAndStatusUseRealRowsAndFreshAdmission() {
        String session = create();
        var id = UUID.randomUUID();
        byte[] first = http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue(event(id, 1))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue(event(id, 1))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .consumeWith(result -> assertArrayEquals(first, result.getResponseBody()));
        http().get()
                .uri(BASE + "/" + session)
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isOk();
        http().get()
                .uri(BASE + "/" + session + "/events?after=0&limit=1")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(1)
                .jsonPath("$.nextAfter")
                .isNotEmpty();
        assertEquals(2, evidenceCount());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM monitoring_access_audit", Integer.class));
        assertEquals(5, ADMISSIONS.get());
    }

    @Test
    void missingSequenceOrSchemaVersionUnknownFieldsAndDuplicateJsonDoNotWrite() {
        String session = create();
        String valid = event(UUID.randomUUID(), 1);
        for (String body : List.of(
                valid.replace("\"sequence\":1,", ""),
                valid.replace(",\"schemaVersion\":1", ""),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":null"),
                valid.replace("\"sequence\":1", "\"sequence\":\"1\""),
                valid.replace("}", ",\"answer\":\"forbidden-answer-marker\"}"),
                valid.replace("}", ",\"sequence\":2}"),
                valid + "{}")) {
            http().post()
                    .uri(BASE + "/" + session + "/events")
                    .headers(h -> h.setBearerAuth(user()))
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store")
                    .expectBody()
                    .json("{\"code\":\"INVALID_REQUEST\"}");
            assertEquals(1, evidenceCount());
        }
    }

    @Test
    void unsupportedSignalOrQuestionMetadataIsRejected() {
        String session = create();
        String valid = event(UUID.randomUUID(), 1);
        for (String body : List.of(
                valid.replace("WINDOW_FOCUS", "SESSION_ENDED"),
                valid.replace("WINDOW_FOCUS", "ANSWER_CHANGED"),
                valid.replace("}", ",\"questionNumber\":2}"),
                valid.replace("WINDOW_FOCUS", "CLIPBOARD_CAPTURED"))) {
            http().post()
                    .uri(BASE + "/" + session + "/events")
                    .headers(h -> h.setBearerAuth(user()))
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        }
        assertEquals(1, evidenceCount());
    }

    @Test
    void bodyBudgetRejectsOversizedPayloadWithoutMutation() {
        String session = create();
        http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue("x".repeat(16385))
                .exchange()
                .expectStatus()
                .isEqualTo(413)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .json("{\"code\":\"PAYLOAD_TOO_LARGE\"}");
        assertEquals(1, evidenceCount());
    }

    @Test
    void unsupportedQueriesBodiesEncodingsAndPeerHeadersNeverMutate() {
        String session = create();
        http().post()
                .uri(BASE + "?admin=true")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue("{\"attemptId\":\"" + attempt + "\"}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        http().post()
                .uri(BASE + "/" + session + "/reconcile")
                .headers(h -> h.setBearerAuth(user()))
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        http().get()
                .uri(BASE + "/" + session + "/events?limit=1&limit=2")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isBadRequest();
        http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .header("Content-Encoding", "gzip")
                .bodyValue(event(UUID.randomUUID(), 1))
                .exchange()
                .expectStatus()
                .isBadRequest();
        http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .header("X-User-Id", ACTOR)
                .bodyValue(event(UUID.randomUUID(), 1))
                .exchange()
                .expectStatus()
                .isBadRequest();
        assertEquals(1, evidenceCount());
    }

    @Test
    void userRoleMissingEvidenceServicePrincipalAndUnknownRouteAreDenied() {
        String session = create();
        http().get()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(token(ACTOR, List.of("STUDENT"))))
                .exchange()
                .expectStatus()
                .isForbidden();
        http().post()
                .uri(BASE)
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        String service = sign(
                KEY,
                ISSUER,
                "proctoring",
                "SERVICE",
                Map.of("scope", List.of("identity.user-admission")),
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(300));
        http().post()
                .uri(BASE)
                .headers(h -> h.setBearerAuth(service))
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isForbidden();
        http().post()
                .uri(BASE + "/future-operation")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden();
        http().post()
                .uri("/internal/security/user-admissions")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertEquals(1, evidenceCount());
    }

    @Test
    void otherStudentCannotReadOrAppendAnotherSession() {
        String session = create();
        String other = token(UUID.randomUUID().toString(), List.of("STUDENT", "TEACHER"));
        http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(other))
                .header("Content-Type", "application/json")
                .bodyValue(event(UUID.randomUUID(), 1))
                .exchange()
                .expectStatus()
                .isNotFound();
        assertEquals(1, evidenceCount());
    }

    @Test
    void identityOrAssessmentAuthorityOutageIsFailClosedWithoutNewEvidence() {
        String session = create();
        ADMISSION_STATUS.set(503);
        http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue(event(UUID.randomUUID(), 1))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .json("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
        ADMISSION_STATUS.set(200);
        AUTHORITY_UNAVAILABLE.set(true);
        http().get()
                .uri(BASE + "/" + session)
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .json("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
        assertEquals(1, evidenceCount());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM monitoring_access_audit", Integer.class));
    }

    @Test
    void newlyInvalidUserEvidenceDoesNotBecomeUnavailableOrPositiveCache() {
        String session = create();
        ADMISSION_STATUS.set(401);
        http().get()
                .uri(BASE + "/" + session)
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        assertEquals(2, ADMISSIONS.get());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM monitoring_access_audit", Integer.class));
    }

    @Test
    void terminalAuthorityReconciliationPersistsEndAndRejectsNewEvent() {
        String session = create();
        ACTIVE.set(false);
        http().post()
                .uri(BASE + "/" + session + "/reconcile")
                .headers(h -> h.setBearerAuth(user()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("ENDED");
        http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(user()))
                .header("Content-Type", "application/json")
                .bodyValue(event(UUID.randomUUID(), 1))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .json("{\"code\":\"SESSION_ENDED\"}");
        assertEquals(2, evidenceCount());
    }

    @Test
    void diagnosticRootDebugNeverLeaksBearerOrRejectedAnswerPayload(CapturedOutput output) {
        String jwt = user();
        String session = create();
        String marker = "sensitive-answer-must-not-log-" + UUID.randomUUID();
        byte[] body = http().post()
                .uri(BASE + "/" + session + "/events")
                .headers(h -> h.setBearerAuth(jwt))
                .header("Content-Type", "application/json")
                .bodyValue(event(UUID.randomUUID(), 1).replace("}", ",\"answer\":\"" + marker + "\"}"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertFalse(new String(body, java.nio.charset.StandardCharsets.UTF_8).contains(marker));
        assertFalse(output.getAll().contains(jwt));
        assertFalse(output.getAll().contains("synthetic-service-token"));
        assertFalse(output.getAll().contains(marker));
        assertEquals(1, evidenceCount());
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
