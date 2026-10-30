package com.quizopia.proctoring;

import static com.quizopia.proctoring.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.media.*;
import java.time.*;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP/RS256/PostgreSQL with explicit dependency doubles. Worker ticks are invoked manually.
 * No browser camera, provider qualification, timed-worker or real Cloud revocation acceptance is claimed. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "quizopia.proctoring.monitoring.enabled=true", "quizopia.admission.enabled=true",
            "quizopia.admission.allow-insecure-loopback=true", "quizopia.admission.client-id=synthetic-media-http",
            "quizopia.admission.client-secret=synthetic-media-http-secret", "quizopia.proctoring.media.enabled=true",
            "quizopia.proctoring.media.worker-enabled=true", "quizopia.proctoring.media.allow-insecure-loopback=true",
            "quizopia.proctoring.media.public-url=ws://127.0.0.1:1",
                    "quizopia.proctoring.media.api-key=synthetic-media-key",
            "quizopia.proctoring.media.api-secret=synthetic-media-api-secret-at-least-32-bytes",
                    "logging.level.root=DEBUG"
        })
@ActiveProfiles("local")
@Import(MediaHttpIntegrationTest.Fixture.class)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MediaHttpIntegrationTest {
    static final String BASE = "/api/proctoring/monitoring/sessions";
    static final String GRANT_MARKER = "synthetic-media-token-never-log";
    static final UUID STUDENT = UUID.fromString(ACTOR),
            TEACHER = UUID.randomUUID(),
            PUBLICATION = UUID.randomUUID(),
            CLASSROOM = UUID.randomUUID();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicReference<Instant> DEADLINE = new AtomicReference<>();
    static final AtomicInteger ADMISSION_STATUS = new AtomicInteger(200), ADMISSIONS = new AtomicInteger();
    static final AtomicBoolean ACTIVE = new AtomicBoolean(true),
            AUTHORITY_UNAVAILABLE = new AtomicBoolean(),
            QUALIFIED = new AtomicBoolean(true),
            PROVIDER_FAIL = new AtomicBoolean(),
            REVOKE_FAIL = new AtomicBoolean(),
            EXPIRE_DURING_NETWORK = new AtomicBoolean();
    static final AtomicInteger MINTS = new AtomicInteger(), REVOCATIONS = new AtomicInteger();
    static final AtomicBoolean TARGET_ELIGIBLE = new AtomicBoolean(true),
            TARGET_UNAVAILABLE = new AtomicBoolean(),
            TARGET_LOST_DURING_NETWORK = new AtomicBoolean();

    @Container
    static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("fb015_proctoring_roles.sql");

    static final DisposableServer IDENTITY = HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .handle((request, response) -> {
                if (request.uri().equals("/oauth2/token"))
                    return request.receive().aggregate().asString().flatMap(body -> response.header(
                                    "Content-Type", "application/json")
                            .sendString(
                                    Mono.just(
                                            "{\"access_token\":\"synthetic-service-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"identity.user-admission\"}"))
                            .then());
                ADMISSIONS.incrementAndGet();
                int status = ADMISSION_STATUS.get();
                String body;
                if (status == 200) {
                    var token = request.requestHeaders().get("X-Quizopia-User-Evidence");
                    var claims = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
                    body = "{\"actorId\":\"" + claims.path("sub").asString()
                            + "\",\"roles\":[\"STUDENT\",\"TEACHER\"]}";
                } else
                    body = status == 401
                            ? "{\"code\":\"USER_EVIDENCE_INVALID\"}"
                            : "{\"code\":\"AUTHORITY_UNAVAILABLE\"}";
                return response.status(status)
                        .header("Content-Type", "application/json")
                        .sendString(Mono.just(body));
            })
            .bindNow();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DATABASE::getJdbcUrl);
        p.add("spring.datasource.username", DATABASE::getUsername);
        p.add("spring.datasource.password", DATABASE::getPassword);
        p.add("spring.flyway.user", DATABASE::getUsername);
        p.add("spring.flyway.password", DATABASE::getPassword);
        p.add("quizopia.admission.identity-base-url", () -> "http://127.0.0.1:" + IDENTITY.port());
        p.add("quizopia.proctoring.monitoring.assessment-base-url", () -> "http://127.0.0.1:" + IDENTITY.port());
        p.add("quizopia.proctoring.media.api-url", () -> "http://127.0.0.1:" + IDENTITY.port());
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate db;

    @Autowired
    MediaRecovery recovery;

    @Autowired
    MediaStore store;

    @Autowired
    MediaProvider provider;

    UUID attempt;

    @BeforeEach
    void fixture() {
        db.execute(
                "TRUNCATE monitoring_media_grants,monitoring_media_rooms,monitoring_access_audit,monitoring_evidence,monitoring_sessions RESTART IDENTITY CASCADE");
        attempt = UUID.randomUUID();
        DEADLINE.set(Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS));
        ADMISSION_STATUS.set(200);
        ADMISSIONS.set(0);
        ACTIVE.set(true);
        AUTHORITY_UNAVAILABLE.set(false);
        QUALIFIED.set(true);
        PROVIDER_FAIL.set(false);
        REVOKE_FAIL.set(false);
        EXPIRE_DURING_NETWORK.set(false);
        MINTS.set(0);
        TARGET_ELIGIBLE.set(true);
        TARGET_UNAVAILABLE.set(false);
        TARGET_LOST_DURING_NETWORK.set(false);
        REVOCATIONS.set(0);
    }

    @AfterAll
    static void stop() {
        IDENTITY.disposeNow();
    }

    WebTestClient http() {
        return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    String actorToken(UUID id) {
        return sign(
                KEY,
                ISSUER,
                id.toString(),
                "USER",
                Map.of("roles", List.of("STUDENT", "TEACHER")),
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(600));
    }

    String session() {
        byte[] body = http().post()
                .uri(BASE)
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .header("Content-Type", "application/json")
                .bodyValue("{\"attemptId\":\"" + attempt + "\"}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return JSON.readTree(body).path("id").asString();
    }

    String path(String session) {
        return BASE + "/" + session + "/media-grants";
    }

    String create(String session, UUID actor) {
        byte[] body = http().post()
                .uri(path(session))
                .headers(h -> h.setBearerAuth(actorToken(actor)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.technicalState")
                .isEqualTo("GRANT_ISSUED")
                .returnResult()
                .getResponseBody();
        return JSON.readTree(body).path("id").asString();
    }

    int count() {
        return db.queryForObject("SELECT count(*) FROM monitoring_media_grants", Integer.class);
    }

    @Test
    void targetDenialOrOutageBlocksTeacherCreateAndRenewWhileHistoryRemainsAccessible() {
        String session = session(), grant = create(session, TEACHER);
        String persisted = db.queryForObject(
                "SELECT row_to_json(g)::text FROM monitoring_media_grants g WHERE id=?",
                String.class,
                UUID.fromString(grant));
        for (boolean unavailable : List.of(false, true)) {
            TARGET_ELIGIBLE.set(false);
            TARGET_UNAVAILABLE.set(unavailable);
            for (String operation : List.of(path(session), path(session) + "/" + grant + "/renew"))
                http().post()
                        .uri(operation)
                        .headers(h -> h.setBearerAuth(actorToken(TEACHER)))
                        .exchange()
                        .expectStatus()
                        .isEqualTo(unavailable ? 503 : 404)
                        .expectHeader()
                        .valueEquals("Cache-Control", "no-store")
                        .expectBody()
                        .jsonPath("$.code")
                        .isEqualTo(unavailable ? "AUTHORITY_UNAVAILABLE" : "PROCTORING_NOT_ACCESSIBLE");
            assertEquals(
                    persisted,
                    db.queryForObject(
                            "SELECT row_to_json(g)::text FROM monitoring_media_grants g WHERE id=?",
                            String.class,
                            UUID.fromString(grant)));
            assertEquals(1, MINTS.get());
            http().get()
                    .uri(BASE + "/" + session + "/events")
                    .headers(h -> h.setBearerAuth(actorToken(TEACHER)))
                    .exchange()
                    .expectStatus()
                    .isOk();
        }
    }

    @Test
    void targetLostDuringProviderWorkCannotActivateOrRenewTeacherMedia() {
        String session = session();
        TARGET_LOST_DURING_NETWORK.set(true);
        http().post()
                .uri(path(session))
                .headers(h -> h.setBearerAuth(actorToken(TEACHER)))
                .exchange()
                .expectStatus()
                .isNotFound();
        assertEquals(0, MINTS.get());
        assertEquals("PREPARED", db.queryForObject("SELECT state FROM monitoring_media_grants", String.class));
        TARGET_ELIGIBLE.set(true);
        TARGET_LOST_DURING_NETWORK.set(false);
        String grant = create(session, TEACHER);
        String persisted = db.queryForObject(
                "SELECT row_to_json(g)::text FROM monitoring_media_grants g WHERE id=?",
                String.class,
                UUID.fromString(grant));
        TARGET_LOST_DURING_NETWORK.set(true);
        http().post()
                .uri(path(session) + "/" + grant + "/renew")
                .headers(h -> h.setBearerAuth(actorToken(TEACHER)))
                .exchange()
                .expectStatus()
                .isNotFound();
        assertEquals(1, MINTS.get());
        assertEquals(
                persisted,
                db.queryForObject(
                        "SELECT row_to_json(g)::text FROM monitoring_media_grants g WHERE id=?",
                        String.class,
                        UUID.fromString(grant)));
    }

    @Test
    void historicalTeacherGrantReadsAndFencesKeepDurableAuditsAfterTargetLoss() {
        String session = session(), grant = create(session, TEACHER);
        TARGET_ELIGIBLE.set(false);
        ACTIVE.set(false);
        long before = db.queryForObject(
                "SELECT count(*) FROM monitoring_access_audit WHERE actor_id=? AND action='SESSION_READ'",
                Long.class,
                TEACHER);
        http().get()
                .uri(path(session) + "/" + grant)
                .headers(h -> h.setBearerAuth(actorToken(TEACHER)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.token")
                .doesNotExist();
        http().post()
                .uri(path(session) + "/" + grant + "/revoke")
                .headers(h -> h.setBearerAuth(actorToken(TEACHER)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("REVOKE_PENDING");
        assertEquals(
                before + 2,
                db.queryForObject(
                        "SELECT count(*) FROM monitoring_access_audit WHERE actor_id=? AND action='SESSION_READ'",
                        Long.class,
                        TEACHER));
        assertEquals(
                "ENDED",
                db.queryForObject(
                        "SELECT status FROM monitoring_sessions WHERE id=?", String.class, UUID.fromString(session)));
        assertEquals(1, MINTS.get());
    }

    @Test
    void realHttpCreateRenewStatusAndRevokePersistBindingsAndNeverPersistUserToken() {
        String session = session(), grant = create(session, STUDENT);
        http().post()
                .uri(path(session) + "/" + grant + "/renew")
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.role")
                .isEqualTo("STUDENT");
        http().get()
                .uri(path(session) + "/" + grant)
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("ACTIVE")
                .jsonPath("$.token")
                .doesNotExist();
        http().post()
                .uri(path(session) + "/" + grant + "/revoke")
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("REVOKE_PENDING");
        assertEquals(1, count());
        assertEquals(2, MINTS.get());
        assertEquals(5, ADMISSIONS.get());
        assertFalse(db.queryForObject("SELECT row_to_json(g)::text FROM monitoring_media_grants g", String.class)
                .contains(GRANT_MARKER));
    }

    @Test
    void assignedTeacherGetsOnlyOwnGrantAndOtherActorCannotReadOrRenewStudentGrant() {
        String session = session(), student = create(session, STUDENT), teacher = create(session, TEACHER);
        assertNotEquals(student, teacher);
        assertEquals("TEACHER", store.find(UUID.fromString(teacher)).role());
        for (UUID actor : List.of(TEACHER, UUID.randomUUID())) {
            http().get()
                    .uri(path(session) + "/" + student)
                    .headers(h -> h.setBearerAuth(actorToken(actor)))
                    .exchange()
                    .expectStatus()
                    .isNotFound();
            http().post()
                    .uri(path(session) + "/" + student + "/renew")
                    .headers(h -> h.setBearerAuth(actorToken(actor)))
                    .exchange()
                    .expectStatus()
                    .isNotFound();
        }
        assertEquals(2, count());
    }

    @Test
    void bodylessRouteRejectsPeerHeadersQueryBodiesAndFutureRoutes() {
        String session = session();
        http().post()
                .uri(path(session))
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        http().post()
                .uri(path(session) + "?room=forged")
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isBadRequest();
        http().post()
                .uri(path(session))
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .header("X-User-Id", STUDENT.toString())
                .exchange()
                .expectStatus()
                .isBadRequest();
        http().post()
                .uri(path(session) + "/future")
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertEquals(0, count());
        assertEquals(0, MINTS.get());
    }

    @Test
    void unqualifiedProviderAndDependencyOutagesCannotMintCredentials() {
        String session = session();
        QUALIFIED.set(false);
        http().post()
                .uri(path(session))
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .json("{\"code\":\"MEDIA_REVOCATION_UNVERIFIED\"}");
        QUALIFIED.set(true);
        ADMISSION_STATUS.set(503);
        http().post()
                .uri(path(session))
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isEqualTo(503);
        ADMISSION_STATUS.set(200);
        AUTHORITY_UNAVAILABLE.set(true);
        http().post()
                .uri(path(session))
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isEqualTo(503);
        assertEquals(0, MINTS.get());
        assertEquals(0, count());
    }

    @Test
    void providerFailureLeavesRecoverablePreparedIntentWithoutReturningToken() {
        String session = session();
        PROVIDER_FAIL.set(true);
        http().post()
                .uri(path(session))
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .json("{\"code\":\"MEDIA_UNAVAILABLE\"}");
        assertEquals(0, MINTS.get());
        assertEquals(1, count());
        assertEquals("PREPARED", db.queryForObject("SELECT state FROM monitoring_media_grants", String.class));
        PROVIDER_FAIL.set(false);
        assertNotNull(create(session, STUDENT));
        assertEquals(1, count());
    }

    @Test
    void networkCompletionCannotOverrideNewTerminalAuthority() {
        String session = session();
        EXPIRE_DURING_NETWORK.set(true);
        http().post()
                .uri(path(session))
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .json("{\"code\":\"SESSION_ENDED\"}");
        assertEquals(0, MINTS.get());
        assertEquals("ENDED", db.queryForObject("SELECT status FROM monitoring_sessions", String.class));
        recovery.tick();
        assertEquals("REVOKED", db.queryForObject("SELECT state FROM monitoring_media_grants", String.class));
    }

    @Test
    void failedProviderRevocationRetainsFenceAndReconstructedWorkerRetriesWithoutUserToken() {
        String session = session(), grant = create(session, STUDENT);
        REVOKE_FAIL.set(true);
        http().post()
                .uri(path(session) + "/" + grant + "/revoke")
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isOk();
        recovery.tick();
        assertEquals("REVOKE_PENDING", store.find(UUID.fromString(grant)).status());
        http().post()
                .uri(path(session) + "/" + grant + "/renew")
                .headers(h -> h.setBearerAuth(actorToken(STUDENT)))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        REVOKE_FAIL.set(false);
        var reconstructedStore = new com.quizopia.proctoring.media.persistence.JdbcMediaStore(
                db,
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        Objects.requireNonNull(db.getDataSource())));
        new MediaRecovery(reconstructedStore, provider, new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
                .tick();
        assertEquals("REVOKED", store.find(UUID.fromString(grant)).status());
        assertEquals(1, MINTS.get());
        assertTrue(REVOCATIONS.get() >= 2);
    }

    @Test
    void debugLoggingDoesNotExposeUserOrReturnedMediaGrant(CapturedOutput output) {
        String session = session();
        String jwt = actorToken(STUDENT);
        http().post()
                .uri(path(session))
                .headers(h -> h.setBearerAuth(jwt))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.token")
                .isEqualTo(GRANT_MARKER);
        assertFalse(output.getAll().contains(jwt));
        assertFalse(output.getAll().contains(GRANT_MARKER));
    }

    @TestConfiguration
    static class Fixture {
        @Bean
        @Primary
        JwtDecoder decoder() throws Exception {
            var d = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            d.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return d;
        }

        @Bean
        @Primary
        MonitoringAuthority authority() {
            return new MonitoringAuthority() {
                @Override
                public Snapshot check(Actor actor, UUID attempt, String role) {
                    return snapshot(actor, attempt, role);
                }

                @Override
                public Snapshot checkLive(Actor actor, UUID attempt, String role) {
                    if (role.equals("TEACHER")) {
                        if (TARGET_UNAVAILABLE.get()) throw MonitoringFailure.unavailable();
                        if (!TARGET_ELIGIBLE.get()) throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
                    }
                    return snapshot(actor, attempt, role);
                }

                private Snapshot snapshot(Actor actor, UUID attempt, String role) {
                    if (AUTHORITY_UNAVAILABLE.get()) throw MonitoringFailure.unavailable();
                    if (role.equals("TEACHER") && !actor.id().equals(TEACHER))
                        throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
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
                }
            };
        }

        @Bean
        TaskScheduler taskScheduler() {
            var scheduler = mock(TaskScheduler.class);
            when(scheduler.getClock()).thenReturn(Clock.systemUTC());
            return scheduler;
        }

        @Bean
        @Primary
        MediaProvider provider() {
            return new MediaProvider() {
                public boolean qualified() {
                    return QUALIFIED.get();
                }

                public void ensureRoom(String room) {
                    if (PROVIDER_FAIL.get()) throw new MonitoringFailure(503, "MEDIA_UNAVAILABLE");
                    if (EXPIRE_DURING_NETWORK.get()) ACTIVE.set(false);
                    if (TARGET_LOST_DURING_NETWORK.get()) TARGET_ELIGIBLE.set(false);
                }

                public String token(String room, String identity, String role, Instant expires) {
                    MINTS.incrementAndGet();
                    return GRANT_MARKER;
                }

                public void revoke(String room, String identity, Instant cutoff) {
                    REVOCATIONS.incrementAndGet();
                    if (REVOKE_FAIL.get()) throw new MonitoringFailure(503, "MEDIA_UNAVAILABLE");
                }

                public void deleteRoom(String room) {}
            };
        }
    }
}
