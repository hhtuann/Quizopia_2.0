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
                "--quizopia.publication.enabled=true"));
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
    @Order(7)
    void privateSourceAndAssessmentLogsContainNoCredentialsOrAnswerBodies() throws Exception {
        for (String name : List.of("quiz", "classroom", "assessment")) {
            String log = Files.readString(logs.resolve(name + ".log"));
            assertFalse(log.contains(userToken));
            for (String locator : sensitiveLocators) assertFalse(log.contains(locator));
            assertFalse(log.contains("private explanation"));
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
        long families = countFamilies();
        redisConnection.sync().del(key());
        both(503);
        redisConnection.sync().set(key(), projection);
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            both(503);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        IDENTITY_DB
                .getDockerClient()
                .pauseContainerCmd(IDENTITY_DB.getContainerId())
                .exec();
        try {
            both(503);
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
        redisConnection
                .sync()
                .set(key(), projection.replace("|NONE", "|AT:" + cutoff.getEpochSecond() + ":" + cutoff.getNano()));
        both(401);
    }

    @Test
    @Order(6)
    void unavailableIdentityFailsClosed() throws Exception {
        PROCESSES.getFirst().destroy();
        assertTrue(PROCESSES.getFirst().waitFor(10, TimeUnit.SECONDS));
        both(503);
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
