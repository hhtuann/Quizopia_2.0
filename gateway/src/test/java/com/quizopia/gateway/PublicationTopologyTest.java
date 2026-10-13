package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.jwk.RSAKey;
import io.lettuce.core.RedisClient;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.containers.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import tools.jackson.databind.json.JsonMapper;

/** Actual processes: Gateway -> Identity admission -> PostgreSQL/Redis, then Classroom -> Identity. */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PublicationTopologyTest {
    @Container
    static final PostgreSQLContainer<?> IDENTITY_DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> CLASSROOM_DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> QUIZ_DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> ASSESSMENT_DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<Process> PROCESSES = new ArrayList<>();
    static final List<String> sensitiveLocators = new ArrayList<>();
    static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String PASSWORD = "topology-fixture-only", SECRET = "topology-service-fixture";
    static String identity,
            classroom,
            quiz,
            assessment,
            gateway,
            userToken,
            serviceToken,
            sourcePath,
            sourceServiceToken;
    static ConfigurableApplicationContext gatewayContext;
    static RedisClient redis;
    static io.lettuce.core.api.StatefulRedisConnection<String, String> redisConnection;
    static Path root, logs, keys;
    static String projection = "1|" + ACTOR + "|PRESENT|VALUE:QUNUSVZF|VERIFIED|STUDENT,TEACHER|NONE";

    @BeforeAll
    static void start() throws Exception {
        root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("AGENTS.md"))) root = root.getParent();
        logs = root.resolve("gateway/target/publication-topology");
        Files.createDirectories(logs);
        keys = Files.createTempDirectory("ld05-topology-keys");
        build("services/identity-service");
        build("services/classroom-service");
        build("services/quiz-service");
        build("services/assessment-service");
        RSAKey key = AdmissionTestTokens.key();
        identityKey = key;
        Files.writeString(
                keys.resolve("private.pem"),
                pem("PRIVATE KEY", key.toRSAPrivateKey().getEncoded()));
        Files.writeString(
                keys.resolve("public.pem"),
                pem("PUBLIC KEY", key.toRSAPublicKey().getEncoded()));
        identity = "http://127.0.0.1:" + port();
        classroom = "http://127.0.0.1:" + port();
        quiz = "http://127.0.0.1:" + port();
        assessment = "http://127.0.0.1:" + port();
        String encryption = Base64.getEncoder().encodeToString(new byte[32]);
        startProcess(
                "identity",
                root.resolve("services/identity-service/target/quizopia-identity-0.1.0-SNAPSHOT.jar"),
                List.of(
                        "--server.port=" + URI.create(identity).getPort(),
                        "--spring.profiles.active=local",
                        "--spring.datasource.url=" + IDENTITY_DB.getJdbcUrl(),
                        "--spring.datasource.username=" + IDENTITY_DB.getUsername(),
                        "--spring.datasource.password=" + IDENTITY_DB.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--quizopia.identity.email-outbox.encryption.active-key-version=v1",
                        "--quizopia.identity.email-outbox.encryption.keys.v1=" + encryption,
                        "--quizopia.identity.security.authorization-server.enabled=true",
                        "--quizopia.identity.security.authorization-server.issuer=" + identity,
                        "--quizopia.identity.security.signing-key.kid=" + key.getKeyID(),
                        "--quizopia.identity.security.signing-key.private-key-path=" + keys.resolve("private.pem"),
                        "--quizopia.identity.security.signing-key.public-key-path=" + keys.resolve("public.pem"),
                        "--quizopia.identity.user-admission.enabled=true",
                        "--quizopia.identity.user-admission.require-secure-transport=false",
                        "--quizopia.identity.user-admission.allowed-clients=gateway,classroom-service,quiz-service,assessment-service",
                        "--quizopia.identity.user-admission.reconciliation-enabled=false"));
        awaitHttp(identity + "/oauth2/jwks");
        seed();
        redis = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redisConnection = redis.connect();
        redisConnection.sync().set(key(), projection);
        userToken = JSON.readTree(send(
                                "POST",
                                identity + "/api/auth/login",
                                null,
                                "{\"identifier\":\"topology-user\",\"password\":\"" + PASSWORD + "\"}",
                                Map.of())
                        .body())
                .path("accessToken")
                .asString();
        assertNotNull(userToken);
        assertTrue(userToken.split("\\.").length == 3, "Identity must issue the USER token");
        serviceToken = service("gateway", SECRET, "identity.user-admission");
        var args = new ArrayList<String>(List.of(
                "--server.port=" + URI.create(classroom).getPort(),
                "--spring.profiles.active=local",
                "--spring.datasource.url=" + CLASSROOM_DB.getJdbcUrl(),
                "--spring.datasource.username=" + CLASSROOM_DB.getUsername(),
                "--spring.datasource.password=" + CLASSROOM_DB.getPassword(),
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity + "/oauth2/jwks"));
        args.addAll(admissionArgs("classroom-service"));
        args.addAll(List.of(
                "--quizopia.internal-authority.enabled=true",
                "--quizopia.internal-authority.require-tls=false",
                "--quizopia.internal-authority.assessment-client-id=assessment-service"));
        startProcess(
                "classroom",
                root.resolve(
                        "services/classroom-service/target/publication-topology-build/quizopia-classroom-0.1.0-SNAPSHOT.jar"),
                args);
        awaitHttp(classroom + "/actuator/health");
        startBusiness(
                "quiz",
                QUIZ_DB,
                quiz,
                List.of(
                        "--quizopia.internal-authority.enabled=true",
                        "--quizopia.internal-authority.require-tls=false",
                        "--quizopia.internal-authority.assessment-client-id=assessment-service"));
        startBusiness(
                "assessment",
                ASSESSMENT_DB,
                assessment,
                List.of(
                        "--quizopia.publication.enabled=true",
                        "--quizopia.assessment.features.attempts-enabled=true",
                        "--quizopia.assessment.features.results-enabled=true",
                        "--quizopia.publication.sources.enabled=true",
                        "--quizopia.publication.sources.quiz-base-url=" + quiz,
                        "--quizopia.publication.sources.classroom-base-url=" + classroom));
        var gatewayArgs = new ArrayList<String>(List.of(
                "--server.port=0",
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity + "/oauth2/jwks",
                "--CLASSROOM_SERVICE_URL=" + classroom,
                "--IDENTITY_SERVICE_URL=" + identity,
                "--ASSESSMENT_SERVICE_URL=" + assessment,
                "--QUIZ_SERVICE_URL=" + quiz,
                "--quizopia.publication.enabled=true",
                "--quizopia.assessment.features.attempts-enabled=true",
                "--quizopia.assessment.features.results-enabled=true"));
        gatewayArgs.addAll(admissionArgs("gateway"));
        gatewayContext = new SpringApplicationBuilder(GatewayApplication.class)
                .profiles("test")
                .run(gatewayArgs.toArray(String[]::new));
        gateway = "http://127.0.0.1:"
                + ((WebServerApplicationContext) gatewayContext).getWebServer().getPort();
    }

    static void startBusiness(String name, PostgreSQLContainer<?> database, String origin, List<String> extra)
            throws Exception {
        var args = new ArrayList<String>(List.of(
                "--server.port=" + URI.create(origin).getPort(),
                "--spring.profiles.active=local",
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity + "/oauth2/jwks"));
        args.addAll(admissionArgs(name + "-service"));
        args.addAll(extra);
        startProcess(
                name,
                root.resolve("services/" + name + "-service/target/publication-topology-build/quizopia-" + name
                        + "-0.1.0-SNAPSHOT.jar"),
                args);
        awaitHttp(origin + "/actuator/health");
    }

    static final String SOURCE =
            "Câu 1 [NUMERIC_FILL]: Numeric **question**\nĐáp án: 0001\nLời giải: private explanation\n";

    static String publicationBody(String quizId, String versionId, String classroomId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("title", "Publication");
        body.put("description", "Description");
        body.put("quizId", quizId);
        body.put("quizVersionId", versionId);
        body.put("versionNumber", 1);
        body.put("audience", classroomId == null ? "PUBLIC" : "CLASS");
        body.put("classroomId", classroomId);
        body.put(
                "startAt",
                Instant.now()
                        .minusSeconds(300)
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .toString());
        body.put(
                "endAt",
                Instant.now()
                        .plusSeconds(3600)
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .toString());
        body.put("durationMinutes", 30);
        body.put("monitoringChoice", "DISABLED");
        return JSON.writeValueAsString(body);
    }

    @Test
    @Order(0)
    void realPublicationFlowAndIndependentBoundaries() throws Exception {
        var created = send(
                "POST",
                quiz + "/api/quizzes",
                userToken,
                JSON.writeValueAsString(
                        Map.of("title", "Source", "description", "Description", "authoringSource", SOURCE)),
                Map.of());
        assertEquals(201, created.statusCode(), created.body());
        String quizId = JSON.readTree(created.body()).path("quizId").asString();
        var published = send("POST", quiz + "/api/quizzes/" + quizId + "/versions", userToken, null, Map.of());
        assertEquals(201, published.statusCode(), published.body());
        String version = JSON.readTree(published.body()).path("id").asString();
        assertEquals(
                200,
                send(
                                "PUT",
                                quiz + "/api/quizzes/" + quizId + "/draft",
                                userToken,
                                JSON.writeValueAsString(Map.of(
                                        "title",
                                        "Changed draft",
                                        "description",
                                        "Changed",
                                        "authoringSource",
                                        SOURCE.replace("0001", "0002"))),
                                Map.of())
                        .statusCode());
        var classResponse = send(
                "POST",
                gateway + "/classrooms",
                userToken,
                "{\"name\":\"Class\",\"subject\":\"Subject\",\"grade\":\"Grade\"}",
                Map.of());
        assertEquals(201, classResponse.statusCode(), classResponse.body());
        String classroomId = JSON.readTree(classResponse.body()).path("id").asString();
        for (String classId : new String[] {classroomId, null}) {
            String body = publicationBody(quizId, version, classId);
            String key = UUID.randomUUID().toString();
            var draft = send(
                    "POST", gateway + "/api/assessments/publications", userToken, body, Map.of("Idempotency-Key", key));
            assertEquals(201, draft.statusCode(), draft.body());
            var resource = JSON.readTree(draft.body());
            if (classId == null)
                sensitiveLocators.add(resource.path("privateLocator").asString());
            String id = resource.path("publicationId").asString();
            var replay = send(
                    "POST", gateway + "/api/assessments/publications", userToken, body, Map.of("Idempotency-Key", key));
            assertEquals(id, JSON.readTree(replay.body()).path("publicationId").asString());
            // Real HTTP requests and separate PostgreSQL transactions race OPEN.
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                String url = gateway + "/api/assessments/publications/" + id + "/open";
                var gate = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.Callable<HttpResponse<String>> call = () -> {
                    gate.await();
                    return send("POST", url, userToken, "{\"expectedRevision\":0}", Map.of());
                };
                var a = executor.submit(call);
                var b = executor.submit(call);
                gate.countDown();
                assertEquals(200, a.get(15, TimeUnit.SECONDS).statusCode());
                assertEquals(200, b.get(15, TimeUnit.SECONDS).statusCode());
            }
            String path = classId == null
                    ? "/api/assessments/publications/entry"
                    : "/api/assessments/publications/" + id + "/entry";
            String entry = classId == null
                    ? JSON.writeValueAsString(
                            Map.of("locator", resource.path("privateLocator").asString()))
                    : null;
            if (classId != null) {
                var denied = send("POST", gateway + path, userToken, null, Map.of());
                assertEquals(404, denied.statusCode(), denied.body());
                try (var connection = DriverManager.getConnection(
                        CLASSROOM_DB.getJdbcUrl(), CLASSROOM_DB.getUsername(), CLASSROOM_DB.getPassword())) {
                    execute(
                            connection,
                            "INSERT INTO classroom_memberships(id,classroom_id,user_id) VALUES(?,?,?)",
                            UUID.randomUUID(),
                            UUID.fromString(classId),
                            ACTOR);
                }
            }
            var admitted = send("POST", gateway + path, userToken, entry, Map.of());
            assertEquals(200, admitted.statusCode(), admitted.body());
            assertEquals(9, JSON.readTree(admitted.body()).size());
            for (String secret : List.of(
                    "numericAnswer", "correct", "explanation", "sourceSnapshot", "quizVersionId", "privateLocator"))
                assertFalse(admitted.body().contains(secret));
            try (var connection = DriverManager.getConnection(
                            ASSESSMENT_DB.getJdbcUrl(), ASSESSMENT_DB.getUsername(), ASSESSMENT_DB.getPassword());
                    var stmt = connection.prepareStatement(
                            "SELECT payload::text FROM publication_snapshots WHERE publication_id=?")) {
                stmt.setObject(1, UUID.fromString(id));
                try (var rows = stmt.executeQuery()) {
                    assertTrue(rows.next());
                    assertTrue(rows.getString(1).contains("0001"));
                    assertTrue(rows.getString(1).contains("private explanation"));
                }
            }
            // Opened metadata is self-contained; a source outage cannot change its snapshot.
            var unavailable = classId == null ? QUIZ_DB : CLASSROOM_DB;
            unavailable
                    .getDockerClient()
                    .pauseContainerCmd(unavailable.getContainerId())
                    .exec();
            try {
                assertEquals(
                        200,
                        send("GET", assessment + "/api/assessments/publications/" + id, userToken, null, Map.of())
                                .statusCode());
                assertEquals(
                        classId == null ? 200 : 503,
                        send("POST", gateway + path, userToken, entry, Map.of()).statusCode());
                assertEquals(
                        503,
                        send(
                                        "POST",
                                        gateway + "/api/assessments/publications",
                                        userToken,
                                        body,
                                        Map.of(
                                                "Idempotency-Key",
                                                UUID.randomUUID().toString()))
                                .statusCode());
            } finally {
                unavailable
                        .getDockerClient()
                        .unpauseContainerCmd(unavailable.getContainerId())
                        .exec();
            }
            // Every direct service still requires current authority; no trusted Gateway assertion.
            redisConnection.sync().del(key());
            assertEquals(
                    503,
                    send("GET", assessment + "/api/assessments/publications/" + id, userToken, null, Map.of())
                            .statusCode());
            assertEquals(
                    503,
                    send("GET", gateway + "/api/assessments/publications/" + id, userToken, null, Map.of())
                            .statusCode());
            redisConnection.sync().set(key(), projection);
            assertEquals(
                    403,
                    send(
                                    "POST",
                                    assessment + "/api/assessments/publications/" + id + "/close",
                                    userToken,
                                    "{}",
                                    Map.of())
                            .statusCode());
        }
        sourcePath = "/internal/quiz-versions/" + quizId + "/" + version + "/1/snapshot";
        assertEquals(
                403,
                send("POST", quiz + sourcePath, userToken, null, Map.of("X-Quizopia-User-Evidence", userToken))
                        .statusCode());
        assertEquals(
                403,
                send("POST", gateway + sourcePath, userToken, null, Map.of()).statusCode());
        String assessmentService = service("assessment-service", SECRET, "quiz.version-snapshot.read");
        sourceServiceToken = assessmentService;
        assertEquals(
                403,
                send(
                                "POST",
                                quiz + sourcePath,
                                service("wrong-client", SECRET, "quiz.version-snapshot.read"),
                                null,
                                Map.of("X-Quizopia-User-Evidence", userToken))
                        .statusCode());
        assertEquals(
                403,
                send(
                                "POST",
                                quiz + sourcePath,
                                service("assessment-service", SECRET, "identity.user-admission"),
                                null,
                                Map.of("X-Quizopia-User-Evidence", userToken))
                        .statusCode());
        assertEquals(
                200,
                send("POST", quiz + sourcePath, assessmentService, null, Map.of("X-Quizopia-User-Evidence", userToken))
                        .statusCode());
        redisConnection.sync().set(key(), projection.replace("STUDENT,TEACHER", "STUDENT"));
        assertEquals(
                503,
                send("POST", quiz + sourcePath, assessmentService, null, Map.of("X-Quizopia-User-Evidence", userToken))
                        .statusCode());
        redisConnection.sync().set(key(), projection);
    }

    @Test
    @Order(1)
    void oversizedRealPublishedSourceCannotPartiallyOpenSeededDraft() throws Exception {
        String large = SOURCE.replace("Numeric **question**", "bulk-private-marker " + "Tiếng Việt ".repeat(90000));
        var created = send(
                "POST",
                quiz + "/api/quizzes",
                userToken,
                JSON.writeValueAsString(Map.of("title", "Large", "authoringSource", large)),
                Map.of());
        assertEquals(201, created.statusCode());
        String quizId = JSON.readTree(created.body()).path("quizId").asString();
        var published = send("POST", quiz + "/api/quizzes/" + quizId + "/versions", userToken, null, Map.of());
        assertEquals(201, published.statusCode());
        String version = JSON.readTree(published.body()).path("id").asString();
        String path = "/internal/quiz-versions/" + quizId + "/" + version + "/1/snapshot";
        String credential = service("assessment-service", SECRET, "quiz.version-snapshot.read");
        var source = send("POST", quiz + path, credential, null, Map.of("X-Quizopia-User-Evidence", userToken));
        assertEquals(200, source.statusCode());
        int bytes = source.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        System.out.println("LD78 real topology snapshot bytes=" + bytes);
        assertTrue(bytes > 2097152);
        String configuration = publicationBody(quizId, version, null);
        assertEquals(
                503,
                send(
                                "POST",
                                gateway + "/api/assessments/publications",
                                userToken,
                                configuration,
                                Map.of("Idempotency-Key", UUID.randomUUID().toString()))
                        .statusCode());
        // Seed an existing DRAFT directly: current create already fails at the same transport boundary.
        UUID id = UUID.randomUUID();
        String locator = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        sensitiveLocators.add(locator);
        String digest = HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(locator.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var c = JSON.readTree(configuration);
        try (var connection = DriverManager.getConnection(
                ASSESSMENT_DB.getJdbcUrl(), ASSESSMENT_DB.getUsername(), ASSESSMENT_DB.getPassword())) {
            execute(
                    connection,
                    "INSERT INTO publications(id,owner_id,configuration,quiz_id,quiz_version_id,version_number,audience,start_at,end_at,duration_minutes,monitoring_choice,state,revision,private_locator,locator_digest,created_at) VALUES(?,?,?::jsonb,?,?,1,'PUBLIC',?,?,30,'DISABLED','DRAFT',0,?,?,now())",
                    id,
                    ACTOR,
                    configuration,
                    UUID.fromString(quizId),
                    UUID.fromString(version),
                    Timestamp.from(Instant.parse(c.path("startAt").asString())),
                    Timestamp.from(Instant.parse(c.path("endAt").asString())),
                    locator,
                    digest);
            for (int retry = 0; retry < 2; retry++) {
                var failure = send(
                        "POST",
                        gateway + "/api/assessments/publications/" + id + "/open",
                        userToken,
                        "{\"expectedRevision\":0}",
                        Map.of());
                assertEquals(503, failure.statusCode());
                assertEquals(
                        "DEPENDENCY_UNAVAILABLE",
                        JSON.readTree(failure.body()).path("code").asString());
                assertEquals(
                        "no-store",
                        failure.headers().firstValue("Cache-Control").orElseThrow());
                assertFalse(failure.body().contains(locator));
                assertFalse(failure.body().contains("bulk-private-marker"));
                try (var statement = connection.prepareStatement(
                        "SELECT state,revision,(SELECT count(*) FROM publication_snapshots WHERE publication_id=?) FROM publications WHERE id=?")) {
                    statement.setObject(1, id);
                    statement.setObject(2, id);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("DRAFT", rows.getString(1));
                        assertEquals(0, rows.getLong(2));
                        assertEquals(0, rows.getInt(3));
                    }
                }
            }
        }
        var unchanged = send("POST", quiz + path, credential, null, Map.of("X-Quizopia-User-Evidence", userToken));
        assertEquals(200, unchanged.statusCode());
        assertEquals(source.body(), unchanged.body());
    }

    @Test
    @Order(7)
    void privateSourceAndAssessmentLogsContainNoCredentialsOrAnswerBodies() throws Exception {
        for (String name : List.of("quiz", "classroom", "assessment")) {
            String log = Files.readString(logs.resolve(name + ".log"));
            assertFalse(log.contains(userToken));
            for (String locator : sensitiveLocators) assertFalse(log.contains(locator));
            assertFalse(log.contains("private explanation"));
            assertFalse(log.contains("bulk-private-marker"));
            assertFalse(log.contains("sourceSnapshot="));
            assertFalse(log.contains("service-fixture-only"));
        }
    }

    @AfterAll
    static void stop() throws Exception {
        if (gatewayContext != null) gatewayContext.close();
        if (redisConnection != null) redisConnection.close();
        if (redis != null) redis.shutdown();
        for (Process process : PROCESSES) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        }
        if (keys != null)
            try (var files = Files.list(keys)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
        if (keys != null) Files.deleteIfExists(keys);
    }

    @Test
    @Order(1)
    void actualIssuedCredentialsAdmitAtBothBoundariesAndPreserveOwnerChecks() throws Exception {
        var created = send(
                "POST",
                gateway + "/classrooms",
                userToken,
                "{\"name\":\"Class\",\"subject\":\"Subject\",\"grade\":\"Grade\"}",
                Map.of("X-Quizopia-User-Evidence", "spoofed"));
        assertEquals(201, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).path("id").asString();
        assertEquals(
                200,
                send("GET", classroom + "/classrooms/" + id, userToken, null, Map.of())
                        .statusCode());
        UUID other = UUID.randomUUID();
        try (var c = identityDb()) {
            execute(
                    c,
                    "INSERT INTO user_account(id,email,username,account_status,email_verified_at,created_at,updated_at) VALUES (?,'other@gmail.com','other-user','ACTIVE',now(),now(),now())",
                    other);
            execute(
                    c,
                    "INSERT INTO user_role(id,user_id,role,created_at) VALUES (?,?,'STUDENT',now())",
                    UUID.randomUUID(),
                    other);
            execute(
                    c,
                    "INSERT INTO local_credential(user_id,password_hash,created_at,updated_at) VALUES (?,?,now(),now())",
                    other,
                    PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
        }
        redisConnection
                .sync()
                .set(
                        "quizopia:identity:user-authority:v1:" + other,
                        "1|" + other + "|PRESENT|VALUE:QUNUSVZF|VERIFIED|STUDENT|NONE");
        var login = send(
                "POST",
                identity + "/api/auth/login",
                null,
                "{\"identifier\":\"other-user\",\"password\":\"" + PASSWORD + "\"}",
                Map.of());
        String otherToken = JSON.readTree(login.body()).path("accessToken").asString();
        assertEquals(
                403,
                send("GET", classroom + "/classrooms/" + id, otherToken, null, Map.of())
                        .statusCode());
        assertEquals(
                403,
                send("POST", gateway + "/classrooms", serviceToken, "{}", Map.of())
                        .statusCode());
        assertEquals(
                403,
                send("POST", classroom + "/classrooms", serviceToken, "{}", Map.of())
                        .statusCode());
    }

    static String b2Attempt, b2Publication;
    static com.nimbusds.jose.jwk.RSAKey identityKey;

    static List<String[]> b2Requests() {
        return List.of(
                new String[] {"POST", "/api/assessments/publications/" + b2Publication + "/attempts/start", "{}"},
                new String[] {"POST", "/api/assessments/publications/" + b2Publication + "/attempts/retake", "{}"},
                new String[] {"GET", "/api/assessments/attempts/" + b2Attempt, null},
                new String[] {
                    "POST",
                    "/api/assessments/attempts/" + b2Attempt + "/questions/1/answer",
                    "{\"expectedRevision\":\"0\",\"answer\":{\"type\":\"NUMERIC_FILL\",\"value\":null}}"
                },
                new String[] {
                    "POST", "/api/assessments/attempts/" + b2Attempt + "/submit", "{\"expectedRevision\":\"0\"}"
                },
                new String[] {
                    "POST", "/api/assessments/attempts/" + b2Attempt + "/abandon", "{\"expectedRevision\":\"0\"}"
                },
                new String[] {"GET", "/api/assessments/attempts/" + b2Attempt + "/result", null},
                new String[] {"GET", "/api/assessments/attempts/" + b2Attempt + "/review", null},
                new String[] {"GET", "/api/assessments/publications/" + b2Publication + "/aggregate-score", null});
    }

    static void b2Both(int status) throws Exception {
        if (b2Attempt == null) return;
        // The same issued USER is independently checked at the edge and direct Assessment.
        for (String base : List.of(gateway, assessment)) {
            var response = send("GET", base + "/api/assessments/attempts/" + b2Attempt, userToken, null, Map.of());
            assertEquals(status, response.statusCode(), response.body());
            assertEquals(
                    "no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        }
    }

    @Test
    @Order(1)
    void completeStudentLifecycleThroughRealGatewayAndCurrentClassroomAuthority() throws Exception {
        var draft = send(
                "POST",
                quiz + "/api/quizzes",
                userToken,
                JSON.writeValueAsString(
                        Map.of("title", "Student source", "description", "Description", "authoringSource", SOURCE)),
                Map.of());
        assertEquals(201, draft.statusCode(), draft.body());
        String quizId = JSON.readTree(draft.body()).path("quizId").asString();
        var versionResponse = send("POST", quiz + "/api/quizzes/" + quizId + "/versions", userToken, null, Map.of());
        assertEquals(201, versionResponse.statusCode(), versionResponse.body());
        String version = JSON.readTree(versionResponse.body()).path("id").asString();
        var cls = send(
                "POST",
                gateway + "/classrooms",
                userToken,
                "{\"name\":\"Student class\",\"subject\":\"Subject\",\"grade\":\"Grade\"}",
                Map.of());
        assertEquals(201, cls.statusCode(), cls.body());
        String classroomId = JSON.readTree(cls.body()).path("id").asString();
        for (String classId : new String[] {classroomId, null}) {
            var cfg = (tools.jackson.databind.node.ObjectNode) JSON.readTree(publicationBody(quizId, version, classId));
            cfg.put("maxAttempts", 0);
            cfg.put("durationMinutes", 0);
            var publication = send(
                    "POST",
                    gateway + "/api/assessments/publications",
                    userToken,
                    cfg.toString(),
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(201, publication.statusCode(), publication.body());
            var resource = JSON.readTree(publication.body());
            String pub = resource.path("publicationId").asString(),
                    locator = resource.path("privateLocator").asString();
            if (classId == null) sensitiveLocators.add(locator);
            String p = "/api/assessments/publications/" + pub;
            assertEquals(
                    200,
                    send("POST", gateway + p + "/open", userToken, "{\"expectedRevision\":0}", Map.of())
                            .statusCode());
            String body = classId == null ? JSON.writeValueAsString(Map.of("locator", locator)) : "{}";
            String startKey = UUID.randomUUID().toString();
            if (classId != null) {
                assertEquals(
                        404,
                        send(
                                        "POST",
                                        gateway + p + "/attempts/start",
                                        userToken,
                                        body,
                                        Map.of("Idempotency-Key", startKey))
                                .statusCode());
                try (var c = DriverManager.getConnection(
                        CLASSROOM_DB.getJdbcUrl(), CLASSROOM_DB.getUsername(), CLASSROOM_DB.getPassword())) {
                    execute(
                            c,
                            "INSERT INTO classroom_memberships(id,classroom_id,user_id) VALUES(?,?,?)",
                            UUID.randomUUID(),
                            UUID.fromString(classId),
                            ACTOR);
                }
            } else {
                assertEquals(
                        400,
                        send(
                                        "POST",
                                        gateway + p + "/attempts/start",
                                        userToken,
                                        "{}",
                                        Map.of("Idempotency-Key", startKey))
                                .statusCode());
                assertEquals(
                        404,
                        send(
                                        "POST",
                                        assessment + p + "/attempts/start",
                                        userToken,
                                        JSON.writeValueAsString(Map.of("locator", "z".repeat(43))),
                                        Map.of("Idempotency-Key", startKey))
                                .statusCode());
            }
            var start =
                    send("POST", gateway + p + "/attempts/start", userToken, body, Map.of("Idempotency-Key", startKey));
            assertEquals(201, start.statusCode(), start.body());
            var view = JSON.readTree(start.body());
            String id = view.path("attemptId").asString(), a = "/api/assessments/attempts/" + id;
            b2Attempt = id;
            b2Publication = pub;
            assertTrue(view.path("deadlineAt").isNull());
            assertEquals("0", view.path("revision").asString());
            assertEquals("1", view.path("ordinal").asString());
            assertEquals(
                    200, send("GET", gateway + a, userToken, null, Map.of()).statusCode());
            assertEquals(
                    200, send("GET", assessment + a, userToken, null, Map.of()).statusCode());
            assertEquals(
                    200,
                    send("POST", gateway + p + "/attempts/start", userToken, body, Map.of("Idempotency-Key", startKey))
                            .statusCode());
            assertEquals(
                    409,
                    send("GET", gateway + a + "/result", userToken, null, Map.of())
                            .statusCode());
            assertEquals(
                    409,
                    send("GET", gateway + a + "/review", userToken, null, Map.of())
                            .statusCode());
            String saveBody = "{\"expectedRevision\":\"0\",\"answer\":{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}}",
                    saveKey = UUID.randomUUID().toString();
            var saved = send(
                    "POST",
                    gateway + a + "/questions/1/answer",
                    userToken,
                    saveBody,
                    Map.of("Idempotency-Key", saveKey));
            assertEquals(200, saved.statusCode(), saved.body());
            assertEquals(
                    saved.body(),
                    send(
                                    "POST",
                                    assessment + a + "/questions/1/answer",
                                    userToken,
                                    saveBody,
                                    Map.of("Idempotency-Key", saveKey))
                            .body());
            String submitKey = UUID.randomUUID().toString();
            var submitted = send(
                    "POST",
                    gateway + a + "/submit",
                    userToken,
                    "{\"expectedRevision\":\"1\"}",
                    Map.of("Idempotency-Key", submitKey));
            assertEquals(200, submitted.statusCode(), submitted.body());
            var result = JSON.readTree(submitted.body());
            assertEquals("1", result.path("earnedWeight").asString());
            assertEquals("100.00", result.path("percentageDisplay").asString());
            assertEquals(
                    submitted.body(),
                    send(
                                    "POST",
                                    assessment + a + "/submit",
                                    userToken,
                                    "{\"expectedRevision\":\"1\"}",
                                    Map.of("Idempotency-Key", submitKey))
                            .body());
            assertEquals(
                    submitted.body(),
                    send("GET", gateway + a + "/result", userToken, null, Map.of())
                            .body());
            var retake = send(
                    "POST",
                    gateway + p + "/attempts/retake",
                    userToken,
                    body,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(201, retake.statusCode(), retake.body());
            String next = JSON.readTree(retake.body()).path("attemptId").asString();
            var abandoned = send(
                    "POST",
                    gateway + "/api/assessments/attempts/" + next + "/abandon",
                    userToken,
                    "{\"expectedRevision\":\"0\"}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(200, abandoned.statusCode(), abandoned.body());
            assertEquals(
                    "ABANDONED", JSON.readTree(abandoned.body()).path("state").asString());
            var aggregate = send("GET", gateway + p + "/aggregate-score", userToken, null, Map.of());
            assertEquals(200, aggregate.statusCode(), aggregate.body());
            assertEquals(result, JSON.readTree(aggregate.body()).path("bestResult"));
            for (var response : List.of(start, submitted, aggregate, saved, abandoned)) {
                assertEquals(
                        "no-store",
                        response.headers().firstValue("Cache-Control").orElseThrow());
                for (String forbidden : List.of(
                        "numericAnswer",
                        "explanationMarkdown",
                        "correctAnswer",
                        "sourceSnapshot",
                        "submittedAnswersId",
                        "studentId")) assertFalse(response.body().contains(forbidden), forbidden);
            }
            if (classId != null) {
                try (var c = DriverManager.getConnection(
                        CLASSROOM_DB.getJdbcUrl(), CLASSROOM_DB.getUsername(), CLASSROOM_DB.getPassword())) {
                    execute(
                            c,
                            "DELETE FROM classroom_memberships WHERE classroom_id=? AND user_id=?",
                            UUID.fromString(classId),
                            ACTOR);
                }
                for (String base : List.of(gateway, assessment))
                    for (var operation : b2Requests())
                        assertEquals(
                                404,
                                send(
                                                operation[0],
                                                base + operation[1],
                                                userToken,
                                                operation[2],
                                                Map.of(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString()))
                                        .statusCode());
                for (String base : List.of(gateway, assessment))
                    for (String suffix : List.of("", "/result", "/review"))
                        assertEquals(
                                404,
                                send("GET", base + a + suffix, userToken, null, Map.of())
                                        .statusCode());
            }
        }
    }

    @Test
    @Order(2)
    void allStudentRoutesIndependentlyRejectServiceAndMissingIssuedRoleAndForgedHeaders() throws Exception {
        for (String base : List.of(gateway, assessment))
            for (var op : b2Requests()) {
                var response = send(
                        op[0],
                        base + op[1],
                        serviceToken,
                        op[2],
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()));
                assertEquals(403, response.statusCode(), op[1] + response.body());
                assertEquals("{\"code\":\"ACCESS_DENIED\"}", response.body());
                assertEquals(
                        "no-store",
                        response.headers().firstValue("Cache-Control").orElseThrow());
                assertEquals(
                        400,
                        send(
                                        op[0],
                                        base + op[1],
                                        userToken,
                                        op[2],
                                        Map.of(
                                                "Idempotency-Key",
                                                UUID.randomUUID().toString(),
                                                "X-User-Id",
                                                ACTOR.toString()))
                                .statusCode());
            }
        for (String base : List.of(gateway, assessment)) {
            assertEquals(
                    404,
                    send("GET", base + "/api/assessments/attempts/" + UUID.randomUUID(), userToken, null, Map.of())
                            .statusCode());
            assertEquals(
                    403,
                    send("POST", base + "/api/assessments/attempts/" + b2Attempt + "/close", userToken, "{}", Map.of())
                            .statusCode());
            assertEquals(
                    403,
                    send("PUT", base + "/api/assessments/attempts/" + b2Attempt, userToken, "{}", Map.of())
                            .statusCode());
            assertEquals(
                    400,
                    send(
                                    "POST",
                                    base + "/api/assessments/attempts/" + b2Attempt + "/submit",
                                    userToken,
                                    "{\"expectedRevision\":\"0\",\"expectedRevision\":\"1\"}",
                                    Map.of("Idempotency-Key", UUID.randomUUID().toString()))
                            .statusCode());
            assertEquals(
                    400,
                    send(
                                    "GET",
                                    base + "/api/assessments/attempts/" + b2Attempt + "?unknown=1",
                                    userToken,
                                    null,
                                    Map.of())
                            .statusCode());
        }
    }

    static String additionalUser(String role) throws Exception {
        UUID id = UUID.randomUUID();
        String username = "b2" + id.toString().replace("-", "").substring(0, 16);
        try (var c = identityDb()) {
            execute(
                    c,
                    "INSERT INTO user_account(id,email,username,account_status,email_verified_at,created_at,updated_at) VALUES(?,?,?,'ACTIVE',now(),now(),now())",
                    id,
                    username + "@gmail.com",
                    username);
            execute(
                    c,
                    "INSERT INTO user_role(id,user_id,role,created_at) VALUES(?,?,?,now())",
                    UUID.randomUUID(),
                    id,
                    role);
            execute(
                    c,
                    "INSERT INTO local_credential(user_id,password_hash,created_at,updated_at) VALUES(?,?,now(),now())",
                    id,
                    PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
        }
        redisConnection
                .sync()
                .set(
                        "quizopia:identity:user-authority:v1:" + id,
                        "1|" + id + "|PRESENT|VALUE:QUNUSVZF|VERIFIED|" + role + "|NONE");
        var response = send(
                "POST",
                identity + "/api/auth/login",
                null,
                JSON.writeValueAsString(Map.of("identifier", username, "password", PASSWORD)),
                Map.of());
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body()).path("accessToken").asString();
    }

    @Test
    @Order(2)
    void actualOtherStudentAndSignedTeacherEvidenceCannotEnumerateOwnedResources() throws Exception {
        String other = additionalUser("STUDENT");
        // Existing Identity browser login requires STUDENT. Craft negative issued-role
        // evidence with the actual fixture signing key; verification/admission stay real.
        String teacher = AdmissionTestTokens.sign(
                identityKey,
                identity,
                ACTOR.toString(),
                "USER",
                Map.of("roles", List.of("TEACHER")),
                Instant.now().minusSeconds(5),
                Instant.now().plusSeconds(300));
        for (String base : List.of(gateway, assessment))
            for (var op : b2Requests()) {
                assertEquals(
                        403,
                        send(
                                        op[0],
                                        base + op[1],
                                        teacher,
                                        op[2],
                                        Map.of(
                                                "Idempotency-Key",
                                                UUID.randomUUID().toString()))
                                .statusCode());
                if (!op[1].endsWith("/start") && !op[1].endsWith("/retake"))
                    assertEquals(
                            404,
                            send(
                                            op[0],
                                            base + op[1],
                                            other,
                                            op[2],
                                            Map.of(
                                                    "Idempotency-Key",
                                                    UUID.randomUUID().toString()))
                                    .statusCode());
            }
        String expired = AdmissionTestTokens.sign(
                identityKey,
                identity,
                ACTOR.toString(),
                "USER",
                Map.of("roles", List.of("STUDENT", "TEACHER")),
                Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(300));
        for (String base : List.of(gateway, assessment)) {
            var expiredResponse = send("GET", base + "/api/assessments/attempts/" + b2Attempt, expired, null, Map.of());
            assertEquals(401, expiredResponse.statusCode());
            assertEquals("{\"code\":\"UNAUTHENTICATED\"}", expiredResponse.body());
            assertEquals(
                    401,
                    send(
                                    "GET",
                                    base + "/api/assessments/attempts/" + b2Attempt,
                                    userToken.substring(0, userToken.length() - 8) + "AAAAAAAA",
                                    null,
                                    Map.of())
                            .statusCode());
        }
    }

    @Test
    @Order(2)
    void bodyBudgetEnforcedForContentLengthAndChunkedAtBothBoundaries() throws Exception {
        String prefix = "{\"expectedRevision\":\"0\"}",
                exact = prefix + " ".repeat(2097152 - prefix.length()),
                oversized = exact + " ";
        long before;
        try (var c = DriverManager.getConnection(
                        ASSESSMENT_DB.getJdbcUrl(), ASSESSMENT_DB.getUsername(), ASSESSMENT_DB.getPassword());
                var statement = c.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM attempt_operations")) {
            rows.next();
            before = rows.getLong(1);
        }
        for (String base : List.of(gateway, assessment)) {
            String url = base + "/api/assessments/attempts/" + b2Attempt + "/submit";
            assertEquals(
                    409,
                    send(
                                    "POST",
                                    url,
                                    userToken,
                                    exact,
                                    Map.of("Idempotency-Key", UUID.randomUUID().toString()))
                            .statusCode());
            assertEquals(
                    400,
                    send(
                                    "POST",
                                    url,
                                    userToken,
                                    oversized,
                                    Map.of("Idempotency-Key", UUID.randomUUID().toString()))
                            .statusCode());
            var chunked = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + userToken)
                            .header("Content-Type", "application/json")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(
                                    oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, chunked.statusCode(), chunked.body());
            assertEquals("{\"code\":\"INVALID_REQUEST\"}", chunked.body());
            assertEquals(
                    "no-store", chunked.headers().firstValue("Cache-Control").orElseThrow());
        }
        try (var c = DriverManager.getConnection(
                        ASSESSMENT_DB.getJdbcUrl(), ASSESSMENT_DB.getUsername(), ASSESSMENT_DB.getPassword());
                var statement = c.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM attempt_operations")) {
            rows.next();
            assertEquals(before, rows.getLong(1));
        }
    }

    @Test
    @Order(2)
    void studentRoleRemovalAndAccountDisableFailClosedWithActualPrimaryRedisEquality() throws Exception {
        try {
            try (var c = identityDb()) {
                execute(c, "DELETE FROM user_role WHERE user_id=? AND role='STUDENT'", ACTOR);
            }
            b2Both(503);
            redisConnection.sync().set(key(), projection.replace("STUDENT,TEACHER", "TEACHER"));
            b2Both(401);
        } finally {
            try (var c = identityDb()) {
                execute(
                        c,
                        "INSERT INTO user_role(id,user_id,role,created_at) VALUES(?,?,'STUDENT',now())",
                        UUID.randomUUID(),
                        ACTOR);
            }
            redisConnection.sync().set(key(), projection);
        }
        for (String state : List.of("disabled", "unverified"))
            try {
                try (var c = identityDb()) {
                    execute(
                            c,
                            state.equals("disabled")
                                    ? "UPDATE user_account SET account_status='DISABLED' WHERE id=?"
                                    : "UPDATE user_account SET email_verified_at=NULL WHERE id=?",
                            ACTOR);
                }
                b2Both(503);
                redisConnection
                        .sync()
                        .set(
                                key(),
                                state.equals("disabled")
                                        ? projection.replace("QUNUSVZF", "RElTQUJMRUQ")
                                        : projection.replace("VERIFIED", "UNVERIFIED"));
                b2Both(401);
            } finally {
                try (var c = identityDb()) {
                    execute(
                            c,
                            "UPDATE user_account SET account_status='ACTIVE',email_verified_at=now() WHERE id=?",
                            ACTOR);
                }
                redisConnection.sync().set(key(), projection);
            }
    }

    @Test
    @Order(2)
    void wrongClientScopeAndSecretAreRejectedByActualAuthorizationServerAndAdmission() throws Exception {
        for (String client : List.of("wrong-client", "wrong-scope")) {
            String token =
                    service(client, SECRET, client.equals("wrong-scope") ? "other.scope" : "identity.user-admission");
            assertEquals(
                    403,
                    send(
                                    "POST",
                                    identity + "/internal/security/user-admissions",
                                    token,
                                    null,
                                    Map.of("X-Quizopia-User-Evidence", userToken))
                            .statusCode());
        }
        var wrong = HTTP.send(
                HttpRequest.newBuilder(URI.create(identity + "/oauth2/token"))
                        .header("Authorization", basic("gateway", "wrong-secret"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&scope=identity.user-admission"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, wrong.statusCode());
    }

    @Test
    @Order(3)
    void currentRoleRemovalDeniesOldTokenAtGatewayAndDirectService() throws Exception {
        sourceServiceToken = service("assessment-service", SECRET, "quiz.version-snapshot.read");
        try (var c = identityDb()) {
            execute(c, "DELETE FROM user_role WHERE user_id=? AND role='TEACHER'", ACTOR);
        }
        both(503);
        redisConnection.sync().set(key(), projection.replace("STUDENT,TEACHER", "STUDENT"));
        both(401);
        try (var c = identityDb()) {
            execute(
                    c,
                    "INSERT INTO user_role(id,user_id,role,created_at) VALUES (?,?,'TEACHER',now())",
                    UUID.randomUUID(),
                    ACTOR);
        }
        redisConnection.sync().set(key(), projection);
    }

    @Test
    @Order(4)
    void primaryRedisAndNetworkOutagesDenyWithoutSessionInvalidation() throws Exception {
        sourceServiceToken = service("assessment-service", SECRET, "quiz.version-snapshot.read");
        long families = countFamilies();
        redisConnection.sync().del(key());
        both(503);
        b2Both(503);
        redisConnection.sync().set(key(), projection);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            both(503);
            b2Both(503);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        IDENTITY_DB
                .getDockerClient()
                .pauseContainerCmd(IDENTITY_DB.getContainerId())
                .exec();
        try {
            both(503);
            b2Both(503);
        } finally {
            IDENTITY_DB
                    .getDockerClient()
                    .unpauseContainerCmd(IDENTITY_DB.getContainerId())
                    .exec();
        }
        assertEquals(families, countFamilies());
        assertEquals(
                404,
                send("GET", gateway + "/classrooms/" + UUID.randomUUID(), userToken, null, Map.of())
                        .statusCode());
    }

    @Test
    @Order(5)
    void committedRevocationWithStaleRedisNeverAdmits() throws Exception {
        sourceServiceToken = service("assessment-service", SECRET, "quiz.version-snapshot.read");
        Instant cutoff;
        try (var c = identityDb()) {
            execute(
                    c,
                    "INSERT INTO user_access_revocation(user_id,revoked_before) VALUES (?,clock_timestamp())",
                    ACTOR);
            try (var statement =
                    c.prepareStatement("SELECT revoked_before FROM user_access_revocation WHERE user_id=?")) {
                statement.setObject(1, ACTOR);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    cutoff = rows.getTimestamp(1).toInstant();
                }
            }
        }
        both(503);
        b2Both(503);
        redisConnection
                .sync()
                .set(key(), projection.replace("|NONE", "|AT:" + cutoff.getEpochSecond() + ":" + cutoff.getNano()));
        both(401);
        b2Both(401);
    }

    @Test
    @Order(6)
    void unavailableIdentityFailsClosed() throws Exception {
        sourceServiceToken = service("assessment-service", SECRET, "quiz.version-snapshot.read");
        PROCESSES.getFirst().destroy();
        assertTrue(PROCESSES.getFirst().waitFor(10, TimeUnit.SECONDS));
        both(503);
        b2Both(503);
    }

    static void both(int status) throws Exception {
        if (status != 404 && sourceServiceToken != null)
            assertEquals(
                    status,
                    send(
                                    "POST",
                                    quiz + sourcePath,
                                    sourceServiceToken,
                                    null,
                                    Map.of("X-Quizopia-User-Evidence", userToken))
                            .statusCode());
        for (String base : List.of(gateway, assessment))
            assertEquals(
                    status,
                    send("GET", base + "/api/assessments/publications/" + ACTOR, userToken, null, Map.of())
                            .statusCode());
        for (String base : List.of(gateway, classroom)) {
            var response = send("GET", base + "/classrooms/" + ACTOR, userToken, null, Map.of());
            assertEquals(status, response.statusCode(), response.body());
            assertEquals(
                    "no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
            assertFalse(response.body().contains(userToken));
            assertFalse(response.body().contains("127.0.0.1"));
        }
    }

    static List<String> admissionArgs(String client) {
        return List.of(
                "--quizopia.admission.enabled=true",
                "--quizopia.admission.allow-insecure-loopback=true",
                "--quizopia.admission.identity-base-url=" + identity,
                "--quizopia.admission.client-id=" + client,
                "--quizopia.admission.client-secret=" + SECRET,
                "--quizopia.admission.connect-timeout=PT0.5S",
                "--quizopia.admission.response-timeout=PT3S",
                "--quizopia.admission.total-timeout=PT4S");
    }

    static String key() {
        return "quizopia:identity:user-authority:v1:" + ACTOR;
    }

    static int port() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static String pem(String kind, byte[] bytes) {
        return "-----BEGIN " + kind + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes) + "\n-----END " + kind + "-----\n";
    }

    static void build(String project) throws Exception {
        var command = new ArrayList<String>(List.of("bash", "mvnw", "-B", "-DskipTests", "package"));
        if (!project.contains("identity"))
            command.add("-Dquizopia.build.directory=" + root.resolve(project + "/target/publication-topology-build"));
        var process = new ProcessBuilder(command)
                .directory(root.resolve(project).toFile())
                .redirectErrorStream(true)
                .redirectOutput(logs.resolve(Path.of(project).getFileName() + "-build.log")
                        .toFile())
                .start();
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Topology artifact build timed out");
        assertEquals(0, process.exitValue(), "See gateway/target/publication-topology build logs");
    }

    static void startProcess(String name, Path jar, List<String> args) throws Exception {
        assertTrue(Files.exists(jar), "Missing topology artifact");
        var command = new ArrayList<String>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                jar.toString()));
        command.addAll(args);
        PROCESSES.add(new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logs.resolve(name + ".log").toFile())
                .start());
    }

    static void awaitHttp(String url) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var r = send("GET", url, null, null, Map.of());
                if (r.statusCode() == 200) return;
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        fail("Topology process did not become ready; see gateway/target/publication-topology logs");
    }

    static HttpResponse<String> send(String method, String url, String token, String body, Map<String, String> headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        headers.forEach(builder::header);
        if (body != null) builder.header("Content-Type", "application/json");
        return HTTP.send(
                builder.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String service(String client, String secret, String scope) throws Exception {
        var response = HTTP.send(
                HttpRequest.newBuilder(URI.create(identity + "/oauth2/token"))
                        .header("Authorization", basic(client, secret))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + scope))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Actual Client Credentials issuance failed");
        return JSON.readTree(response.body()).path("access_token").asString();
    }

    static String basic(String client, String secret) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString((client + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static Connection identityDb() throws Exception {
        return DriverManager.getConnection(
                IDENTITY_DB.getJdbcUrl(), IDENTITY_DB.getUsername(), IDENTITY_DB.getPassword());
    }

    static void execute(Connection c, String sql, Object... args) throws Exception {
        try (var statement = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.executeUpdate();
        }
    }

    static long countFamilies() throws Exception {
        try (var c = identityDb();
                var s = c.createStatement();
                var rows = s.executeQuery("SELECT count(*) FROM refresh_token_family WHERE revoked_at IS NULL")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    static void seed() throws Exception {
        var hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(SECRET);
        try (var c = identityDb()) {
            execute(
                    c,
                    "INSERT INTO user_account(id,email,username,account_status,email_verified_at,created_at,updated_at) VALUES (?,'topology@gmail.com','topology-user','ACTIVE',now(),now(),now())",
                    ACTOR);
            for (String role : List.of("STUDENT", "TEACHER"))
                execute(
                        c,
                        "INSERT INTO user_role(id,user_id,role,created_at) VALUES (?,?,?,now())",
                        UUID.randomUUID(),
                        ACTOR,
                        role);
            execute(
                    c,
                    "INSERT INTO local_credential(user_id,password_hash,created_at,updated_at) VALUES (?,?,now(),now())",
                    ACTOR,
                    PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD));
            for (String client : List.of(
                    "gateway",
                    "classroom-service",
                    "quiz-service",
                    "assessment-service",
                    "wrong-client",
                    "wrong-scope")) {
                UUID id = UUID.randomUUID();
                execute(
                        c,
                        "INSERT INTO oauth2_service_client(id,client_id,client_secret_hash,enabled,created_at,updated_at) VALUES (?,?,?,true,now(),now())",
                        id,
                        client,
                        hash);
                for (String scope : client.equals("assessment-service")
                        ? List.of(
                                "identity.user-admission",
                                "quiz.version-snapshot.read",
                                "classroom.teacher-authority.read",
                                "classroom.membership.read")
                        : (client.equals("wrong-client")
                                ? List.of("identity.user-admission", "quiz.version-snapshot.read")
                                : List.of(client.equals("wrong-scope") ? "other.scope" : "identity.user-admission")))
                    execute(
                            c,
                            "INSERT INTO oauth2_service_client_scope(service_client_id,scope) VALUES (?,?)",
                            id,
                            scope);
            }
        }
    }
}
