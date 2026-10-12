package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.domain.Publication;
import com.quizopia.assessment.infrastructure.*;
import com.quizopia.assessment.persistence.JdbcPublicationStore;
import com.quizopia.assessment.security.admission.AdmissionProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.system.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import reactor.core.publisher.*;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Actual configured source client and HTTP/OAuth stubs; atomicity uses real PostgreSQL/Flyway. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class SnapshotTransportIntegrationTest {
    static final int LIMIT = 2097152;

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    final AtomicReference<byte[]> body = new AtomicReference<>();
    final AtomicBoolean chunked = new AtomicBoolean();
    final AtomicInteger status = new AtomicInteger(200);
    DisposableServer server;
    PublicationSourceClient client;
    JdbcPublicationStore store;
    JdbcTemplate db;
    final com.quizopia.assessment.domain.PublicationConfiguration configuration = SnapshotFixtures.configuration();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void start() {
        var data = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        db = new JdbcTemplate(data);
        store = new JdbcPublicationStore(db, new DataSourceTransactionManager(data), Clock.systemUTC());
        server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    if (request.uri().equals("/oauth2/token"))
                        return request.receive()
                                .then(response.header("Content-Type", "application/json")
                                        .sendString(
                                                Mono.just(
                                                        "{\"access_token\":\"transport-service-fixture\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"quiz.version-snapshot.read\"}"))
                                        .then());
                    assertEquals(
                            "Bearer transport-service-fixture",
                            request.requestHeaders().get("Authorization"));
                    assertEquals(
                            "transport-user-fixture", request.requestHeaders().get("X-Quizopia-User-Evidence"));
                    assertNull(request.requestHeaders().get("Cookie"));
                    response.status(status.get()).header("Content-Type", "application/json; charset=UTF-8");
                    byte[] bytes = body.get();
                    if (!chunked.get())
                        return response.header("Content-Length", Integer.toString(bytes.length))
                                .sendByteArray(Mono.just(bytes));
                    response.chunkedTransfer(true);
                    return response.sendByteArray(Flux.range(0, (bytes.length + 8191) / 8192)
                            .map(i -> Arrays.copyOfRange(bytes, i * 8192, Math.min(bytes.length, (i + 1) * 8192))));
                })
                .bindNow();
        var admission = new AdmissionProperties();
        admission.setEnabled(true);
        admission.setAllowInsecureLoopback(true);
        admission.setIdentityBaseUrl(URI.create("http://127.0.0.1:" + server.port()));
        admission.setClientId("assessment-service");
        admission.setClientSecret("transport-fixture-only");
        admission.setConnectTimeout(Duration.ofSeconds(1));
        admission.setResponseTimeout(Duration.ofSeconds(2));
        admission.setTotalTimeout(Duration.ofSeconds(4));
        var sources = new SourceProperties();
        sources.setEnabled(true);
        sources.setQuizBaseUrl(admission.getIdentityBaseUrl());
        sources.setClassroomBaseUrl(admission.getIdentityBaseUrl());
        client = new PublicationSourceClient(sources, admission);
    }

    @AfterEach
    void stop() {
        if (client != null) client.close();
        if (server != null) server.disposeNow();
    }

    static java.util.stream.Stream<Arguments> boundaries() {
        return java.util.stream.IntStream.of(LIMIT - 1, LIMIT, LIMIT + 1, LIMIT + 65536)
                .boxed()
                .flatMap(size -> java.util.stream.Stream.of(false, true)
                        .flatMap(chunked -> java.util.stream.Stream.of(false, true)
                                .map(unicode -> Arguments.of(size, chunked, unicode))));
    }

    @ParameterizedTest(name = "{0} bytes, chunked={1}, unicode={2}")
    @MethodSource("boundaries")
    void measuredUtf8BodyBoundary(int size, boolean chunks, boolean unicode, CapturedOutput output) {
        body.set(SnapshotFixtures.sized(configuration, size, unicode));
        chunked.set(chunks);
        assertEquals(size, body.get().length);
        if (unicode) assertTrue(new String(body.get(), StandardCharsets.UTF_8).length() < size);
        if (size <= LIMIT) {
            var result = client.snapshot(configuration, "transport-user-fixture");
            assertEquals(SnapshotFixtures.JSON.readTree(body.get()), SnapshotFixtures.JSON.readTree(result));
        } else sanitized(() -> client.snapshot(configuration, "transport-user-fixture"));
        assertFalse(output.getAll().contains("private-answer-marker"));
        assertFalse(output.getAll().contains("transport-user-fixture"));
    }

    void sanitized(org.junit.jupiter.api.function.Executable action) {
        var e = assertThrows(PublicationFailure.class, action);
        assertEquals(503, e.status());
        assertEquals("DEPENDENCY_UNAVAILABLE", e.code());
        assertNull(e.getCause());
        assertFalse(e.toString().contains("private-answer-marker"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedOpenLeavesDraftAndZeroSnapshotsThenRetrySucceeds(boolean chunks, CapturedOutput output) {
        UUID actor = UUID.randomUUID();
        var p = store.create(actor, UUID.randomUUID().toString(), configuration);
        var service = new PublicationService(store, client);
        chunked.set(chunks);
        body.set(SnapshotFixtures.sized(configuration, LIMIT + 1, true));
        sanitized(() -> service.open(actor, p.id(), 0, "transport-user-fixture"));
        assertEquals(Publication.State.DRAFT, store.owned(actor, p.id()).state());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM publication_snapshots WHERE publication_id=?", Integer.class, p.id()));
        body.set(SnapshotFixtures.sized(configuration, LIMIT, true));
        var opened = service.open(actor, p.id(), 0, "transport-user-fixture");
        assertEquals(Publication.State.OPEN, opened.state());
        assertEquals(opened, service.open(actor, p.id(), 0, "transport-user-fixture"));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT grading_policy_version FROM publication_snapshots WHERE publication_id=?",
                        Integer.class,
                        p.id()));
        var payload = db.queryForObject(
                "SELECT payload::text FROM publication_snapshots WHERE publication_id=?", String.class, p.id());
        assertEquals(SnapshotFixtures.JSON.readTree(body.get()), SnapshotFixtures.JSON.readTree(payload));
        assertFalse(output.getAll().contains("private-answer-marker"));
    }

    @Test
    void partialJsonBelowCapFailsValidation() {
        body.set(Arrays.copyOf(SnapshotFixtures.snapshot(configuration, "Original"), 100));
        sanitized(() -> client.snapshot(configuration, "transport-user-fixture"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedOversizedAndErrorBodiesFailClosed(boolean chunks) {
        chunked.set(chunks);
        body.set(("{" + "x".repeat(LIMIT)).getBytes(StandardCharsets.UTF_8));
        sanitized(() -> client.snapshot(configuration, "transport-user-fixture"));
        status.set(401);
        body.set(SnapshotFixtures.sized(configuration, LIMIT + 1, false));
        sanitized(() -> client.snapshot(configuration, "transport-user-fixture"));
    }
}
