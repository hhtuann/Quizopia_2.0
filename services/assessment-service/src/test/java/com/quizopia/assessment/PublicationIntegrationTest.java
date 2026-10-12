package com.quizopia.assessment;

import static com.quizopia.assessment.AdmissionTestTokens.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.domain.*;
import com.quizopia.assessment.security.admission.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"quizopia.publication.enabled=true"})
@Import(ClosedBusinessBoundaryTest.Keys.class)
class PublicationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    PublicationStore store;

    @Autowired
    PublicationService service;

    @Autowired
    JdbcTemplate db;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    PublicationSources sources;

    @MockitoBean
    IdentityAdmissionClient admission;

    @Value("${local.server.port}")
    int port;

    final UUID actor = UUID.fromString(ACTOR);
    PublicationConfiguration configuration;

    @BeforeEach
    void init() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-11T00:10:00Z"));
        configuration = new PublicationConfiguration(
                "Publication",
                "Private notes",
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                PublicationConfiguration.Audience.PUBLIC,
                null,
                Instant.parse("2026-10-11T00:00:00.123456Z"),
                Instant.parse("2026-10-12T00:00:00.123456Z"),
                30,
                PublicationConfiguration.MonitoringChoice.DISABLED);
        when(admission.admit(any(), any())).thenReturn(Mono.empty());
        when(sources.snapshot(any(), any())).thenReturn(snapshot());
    }

    String snapshot() {
        var root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", 1);
        root.put("quizId", configuration.quizId());
        root.put("quizVersionId", configuration.quizVersionId());
        root.put("versionNumber", 1);
        root.put("titleSnapshot", "Title");
        root.put("descriptionSnapshot", null);
        root.put("sourceSnapshot", "secret 0001");
        root.put("structuredContent", Map.of("questions", List.of()));
        root.put("contentSchemaVersion", 1);
        root.put("createdAt", "2026-10-11T00:00:00Z");
        return json.writeValueAsString(root);
    }

    @Test
    void databaseRejectsOpenWithoutSnapshot() {
        var p = create();
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update(
                        "UPDATE publications SET state='OPEN',opened_at=now(),opened_from_revision=0,revision=1 WHERE id=?",
                        p.id()));
        assertEquals(Publication.State.DRAFT, store.owned(actor, p.id()).state());
    }

    Publication create() {
        return service.create(actor, UUID.randomUUID().toString(), configuration, "evidence");
    }

    @Test
    void draftCreationReplayAndDifferentRequestConflict() {
        String key = UUID.randomUUID().toString();
        var a = service.create(actor, key, configuration, "evidence");
        var b = service.create(actor, key, configuration, "evidence");
        assertEquals(a, b);
        verify(sources, times(1)).snapshot(any(), any());
        assertEquals(43, a.privateLocator().length());
        assertFalse(a.toString().contains(a.privateLocator()));
        var other = new PublicationConfiguration(
                "Other",
                null,
                configuration.quizId(),
                configuration.quizVersionId(),
                1,
                configuration.audience(),
                null,
                configuration.startAt(),
                configuration.endAt(),
                30,
                configuration.monitoringChoice());
        assertEquals(
                "IDEMPOTENCY_CONFLICT",
                assertThrows(PublicationFailure.class, () -> service.create(actor, key, other, "evidence"))
                        .code());
    }

    @Test
    void concurrentCreationCommitsOneReplayResource() throws Exception {
        String key = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var latch = new CountDownLatch(1);
            Callable<Publication> call = () -> {
                latch.await();
                return store.create(actor, key, configuration);
            };
            var a = pool.submit(call);
            var b = pool.submit(call);
            latch.countDown();
            assertEquals(
                    a.get(10, TimeUnit.SECONDS).id(),
                    b.get(10, TimeUnit.SECONDS).id());
        }
    }

    @Test
    void audienceChangesRotateAndClearPrivateLocator() {
        var p = create();
        String original = p.privateLocator();
        var c = p.configuration();
        var classConfig = new PublicationConfiguration(
                c.title(),
                c.description(),
                c.quizId(),
                c.quizVersionId(),
                1,
                PublicationConfiguration.Audience.CLASS,
                UUID.randomUUID(),
                c.startAt(),
                c.endAt(),
                30,
                PublicationConfiguration.MonitoringChoice.ENABLED);
        var switched = service.update(actor, p.id(), 0, classConfig, "evidence");
        assertNull(switched.privateLocator());
        var restored = service.update(actor, p.id(), 1, c, "evidence");
        assertNotEquals(original, restored.privateLocator());
        var same = service.update(actor, p.id(), 2, c, "evidence");
        assertEquals(restored.privateLocator(), same.privateLocator());
    }

    @Test
    void enabledClassOpenFreezesMonitoringAndMembershipRemainsAuthoritative() {
        var c = configuration;
        configuration = new PublicationConfiguration(
                c.title(),
                c.description(),
                c.quizId(),
                c.quizVersionId(),
                1,
                PublicationConfiguration.Audience.CLASS,
                UUID.randomUUID(),
                c.startAt(),
                c.endAt(),
                30,
                PublicationConfiguration.MonitoringChoice.ENABLED);
        var p = create();
        var opened = service.open(actor, p.id(), 0, "evidence");
        assertEquals(
                PublicationConfiguration.MonitoringChoice.ENABLED,
                opened.configuration().monitoringChoice());
        assertEquals(opened, service.classEntry(actor, p.id(), "student-evidence"));
        verify(sources).member(configuration.classroomId(), actor, "student-evidence");
        doThrow(new PublicationFailure(404, "CLASSROOM_NOT_ACCESSIBLE"))
                .when(sources)
                .member(any(), any(), any());
        assertEquals(
                404,
                assertThrows(PublicationFailure.class, () -> service.classEntry(actor, p.id(), "student-evidence"))
                        .status());
    }

    @Test
    void ownershipAndRevisionChecks() {
        var p = create();
        assertEquals(
                404,
                assertThrows(PublicationFailure.class, () -> store.owned(UUID.randomUUID(), p.id()))
                        .status());
        var updated = service.update(actor, p.id(), 0, configuration, "evidence");
        assertEquals(1, updated.revision());
        assertEquals(
                409,
                assertThrows(
                                PublicationFailure.class,
                                () -> service.update(actor, p.id(), 0, configuration, "evidence"))
                        .status());
    }

    @Test
    void atomicOpenRepeatAndImmutableSnapshot() {
        var p = create();
        var open = service.open(actor, p.id(), 0, "evidence");
        assertEquals(Publication.State.OPEN, open.state());
        assertEquals(open, service.open(actor, p.id(), 0, "evidence"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT count(*) FROM publication_snapshots WHERE publication_id=?", Integer.class, p.id()));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update("DELETE FROM publications WHERE id=?", p.id()));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update("UPDATE publication_snapshots SET payload='{}'::jsonb WHERE publication_id=?", p.id()));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update(
                        "UPDATE publications SET quiz_id=?,revision=revision+1 WHERE id=?", UUID.randomUUID(), p.id()));
        assertEquals(
                409,
                assertThrows(
                                PublicationFailure.class,
                                () -> service.update(actor, p.id(), open.revision(), configuration, "evidence"))
                        .status());
    }

    @Test
    void concurrentOpenUsesRealPostgresRowSerialization() throws Exception {
        var p = create();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            when(sources.snapshot(any(), any())).thenAnswer(inv -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return snapshot();
            });
            var a = pool.submit(() -> service.open(actor, p.id(), 0, "evidence"));
            var b = pool.submit(() -> service.open(actor, p.id(), 0, "evidence"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    db.queryForObject(
                            "SELECT count(*) FROM publication_snapshots WHERE publication_id=?",
                            Integer.class,
                            p.id()));
        }
    }

    @Test
    void editDuringOpenPrefetchInvalidatesPreparation() throws Exception {
        var p = create();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            when(sources.snapshot(any(), any())).thenAnswer(inv -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return "{}";
            });
            var pending = pool.submit(() -> service.open(actor, p.id(), 0, "evidence"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            store.update(actor, p.id(), 0, configuration);
            release.countDown();
            var failure = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
            assertInstanceOf(PublicationFailure.class, failure.getCause());
            assertEquals(Publication.State.DRAFT, store.owned(actor, p.id()).state());
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT count(*) FROM publication_snapshots WHERE publication_id=?",
                            Integer.class,
                            p.id()));
        }
    }

    @Test
    void snapshotInsertRollbackCannotCommitOpen() {
        var p = create();
        assertEquals(
                503,
                assertThrows(PublicationFailure.class, () -> store.open(actor, p.id(), 0, "not-json"))
                        .status());
        assertEquals(Publication.State.DRAFT, store.owned(actor, p.id()).state());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM publication_snapshots WHERE publication_id=?", Integer.class, p.id()));
    }

    @Test
    void sourceFailureLeavesDraft() {
        var p = create();
        when(sources.snapshot(any(), any())).thenThrow(PublicationFailure.dependency());
        assertEquals(
                503,
                assertThrows(PublicationFailure.class, () -> service.open(actor, p.id(), 0, "evidence"))
                        .status());
        assertEquals(Publication.State.DRAFT, store.owned(actor, p.id()).state());
    }

    @Test
    void classRequiresOwnerAndExplicitMonitoring() {
        UUID classroom = UUID.randomUUID();
        configuration = new PublicationConfiguration(
                "Class",
                null,
                configuration.quizId(),
                configuration.quizVersionId(),
                1,
                PublicationConfiguration.Audience.CLASS,
                classroom,
                configuration.startAt(),
                configuration.endAt(),
                5,
                PublicationConfiguration.MonitoringChoice.UNCONFIGURED);
        var p = create();
        verify(sources).teacher(classroom, actor, "evidence");
        assertNull(p.privateLocator());
        assertEquals(
                400,
                assertThrows(PublicationFailure.class, () -> service.open(actor, p.id(), 0, "evidence"))
                        .status());
    }

    @Test
    void finalizedScheduleOnlyExtendsAndNoAutoClose() {
        var p = create();
        service.open(actor, p.id(), 0, "evidence");
        var next = service.extend(actor, p.id(), 1, configuration.endAt().plusSeconds(600), 40);
        assertEquals(Publication.State.OPEN, next.state());
        assertEquals(
                400,
                assertThrows(
                                PublicationFailure.class,
                                () -> service.extend(actor, p.id(), 2, configuration.endAt(), 30))
                        .status());
        assertEquals(
                409,
                assertThrows(
                                PublicationFailure.class,
                                () -> service.extend(
                                        actor, p.id(), 1, next.configuration().endAt(), 40))
                        .status());
    }

    @Test
    void discoveryWindowUsesServerClockAndNeverClosesPublication() {
        var p = create();
        service.open(actor, p.id(), 0, "evidence");
        String body = "{\"locator\":\"" + p.privateLocator() + "\"}";
        when(clock.instant()).thenReturn(configuration.startAt().minusNanos(1000));
        assertEquals(
                "BEFORE_START",
                json.readTree(call("POST", "/entry", body, user()).body)
                        .path("windowStatus")
                        .asString());
        when(clock.instant()).thenReturn(configuration.startAt());
        assertEquals(
                "AVAILABLE",
                json.readTree(call("POST", "/entry", body, user()).body)
                        .path("windowStatus")
                        .asString());
        when(clock.instant()).thenReturn(configuration.endAt());
        assertEquals(
                "WINDOW_ENDED",
                json.readTree(call("POST", "/entry", body, user()).body)
                        .path("windowStatus")
                        .asString());
        assertEquals(Publication.State.OPEN, store.owned(actor, p.id()).state());
    }

    @Test
    void publicLocatorDraftUndiscoverableAndSafeStudentProjection() {
        var p = create();
        assertEquals(
                404,
                assertThrows(PublicationFailure.class, () -> service.publicEntry(p.privateLocator()))
                        .status());
        service.open(actor, p.id(), 0, "evidence");
        var response = call("POST", "/entry", "{\"locator\":\"" + p.privateLocator() + "\"}", user());
        assertEquals(200, response.status);
        var fields = json.readTree(response.body);
        assertEquals(9, fields.size());
        for (String secret : List.of(
                "configuration",
                "quizId",
                "quizVersionId",
                "privateLocator",
                "correct",
                "numericAnswer",
                "secret",
                "snapshot",
                "ownerId")) assertFalse(fields.has(secret));
        assertFalse(response.body.contains(p.privateLocator()));
    }

    @Test
    void signedUserTeacherManagementNoStoreAndAdmissionFailure() {
        var body = json.writeValueAsString(configuration);
        var response = call("POST", "", body, user());
        assertEquals(201, response.status);
        assertEquals("no-store", response.cache);
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.unavailable()));
        var denied = call("POST", "", body, user());
        assertEquals(503, denied.status);
        assertFalse(denied.body.contains(ACTOR));
        when(admission.admit(any(), any())).thenReturn(Mono.error(AdmissionFailure.invalidUser()));
        assertEquals(401, call("POST", "", body, user()).status);
    }

    @Test
    void wrongPrincipalAndFutureRoutesDeny() {
        String token = sign(
                KEY,
                ISSUER,
                "assessment-service",
                "SERVICE",
                Map.of("scope", List.of("identity.user-admission")),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
        assertEquals(403, call("POST", "", "{}", token).status);
        assertEquals(403, call("POST", "/" + UUID.randomUUID() + "/close", "{}", user()).status);
        assertEquals(403, call("POST", "/entry/nested", "{}", user()).status);
    }

    @Test
    void unknownAndDuplicateFieldsAndFractionalIntegersReject() {
        String body = json.writeValueAsString(configuration);
        assertEquals(
                400,
                call("POST", "", body.substring(0, body.length() - 1) + ",\"ownerId\":\"" + ACTOR + "\"}", user())
                        .status);
        assertEquals(
                400,
                call("POST", "", body.replace("\"durationMinutes\":30", "\"durationMinutes\":30.5"), user()).status);
        assertEquals(400, call("POST", "", body.replace("\"audience\":\"PUBLIC\"", "\"audience\":1"), user()).status);
        assertEquals(
                400,
                call("POST", "", body.replace("\"durationMinutes\":30", "\"durationMinutes\":\"30\""), user()).status);
        assertEquals(
                400,
                call("POST", "", body.substring(0, body.length() - 1) + ",\"title\":\"duplicate\"}", user()).status);
    }

    record Response(int status, String body, String cache) {}

    Response call(String method, String path, String body, String token) {
        return RestClient.create("http://127.0.0.1:" + port)
                .method(org.springframework.http.HttpMethod.valueOf(method))
                .uri("/api/assessments/publications" + path)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> new Response(
                        response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                        response.getHeaders().getFirst("Cache-Control")));
    }
}
