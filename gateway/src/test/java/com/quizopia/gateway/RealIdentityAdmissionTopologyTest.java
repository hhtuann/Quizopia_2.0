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
class RealIdentityAdmissionTopologyTest {
    @Container
    static final PostgreSQLContainer<?> IDENTITY_DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> CLASSROOM_DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<Process> PROCESSES = new ArrayList<>();
    static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final String PASSWORD = "topology-fixture-only", SECRET = "topology-service-fixture";
    static String identity, classroom, gateway, userToken, serviceToken;
    static ConfigurableApplicationContext gatewayContext;
    static RedisClient redis;
    static io.lettuce.core.api.StatefulRedisConnection<String, String> redisConnection;
    static Path root, logs, keys;
    static String projection = "1|" + ACTOR + "|PRESENT|VALUE:QUNUSVZF|VERIFIED|STUDENT,TEACHER|NONE";

    @BeforeAll
    static void start() throws Exception {
        root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("AGENTS.md"))) root = root.getParent();
        logs = root.resolve("gateway/target/admission-topology");
        Files.createDirectories(logs);
        keys = Files.createTempDirectory("ld05-topology-keys");
        build("services/identity-service");
        build("services/classroom-service");
        RSAKey key = AdmissionTestTokens.key();
        Files.writeString(
                keys.resolve("private.pem"),
                pem("PRIVATE KEY", key.toRSAPrivateKey().getEncoded()));
        Files.writeString(
                keys.resolve("public.pem"),
                pem("PUBLIC KEY", key.toRSAPublicKey().getEncoded()));
        identity = "http://127.0.0.1:" + port();
        classroom = "http://127.0.0.1:" + port();
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
                        "--quizopia.identity.user-admission.allowed-clients=gateway,classroom-service",
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
        startProcess(
                "classroom",
                root.resolve(
                        "services/classroom-service/target/admission-topology-build/quizopia-classroom-0.1.0-SNAPSHOT.jar"),
                args);
        awaitHttp(classroom + "/actuator/health");
        var gatewayArgs = new ArrayList<String>(List.of(
                "--server.port=0",
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity + "/oauth2/jwks",
                "--CLASSROOM_SERVICE_URL=" + classroom,
                "--IDENTITY_SERVICE_URL=" + identity));
        gatewayArgs.addAll(admissionArgs("gateway"));
        gatewayContext = new SpringApplicationBuilder(GatewayApplication.class)
                .profiles("test")
                .run(gatewayArgs.toArray(String[]::new));
        gateway = "http://127.0.0.1:"
                + ((WebServerApplicationContext) gatewayContext).getWebServer().getPort();
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
        for (String base : List.of(gateway, classroom)) {
            var page = send("GET", base + "/classrooms?limit=1", userToken, null, Map.of());
            assertEquals(200, page.statusCode(), page.body());
            var item = JSON.readTree(page.body()).path("items").get(0);
            assertEquals(id, item.path("id").asString());
            assertEquals(
                    java.util.Set.of("id", "name", "subject", "grade", "description", "createdAt"),
                    item.propertyNames());
            assertTrue(JSON.readTree(page.body()).path("nextCursor").isNull());
        }
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
        for (String base : List.of(gateway, classroom)) {
            assertEquals(
                    403,
                    send("GET", base + "/classrooms", otherToken, null, Map.of())
                            .statusCode());
            assertEquals(
                    403,
                    send("GET", base + "/classrooms", serviceToken, null, Map.of())
                            .statusCode());
        }
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
        var command = PublicationTopologyTest.mavenWrapperCommand();
        command.addAll(List.of("-B", "-DskipTests", "package"));
        if (project.contains("classroom"))
            command.add("-Dquizopia.build.directory=" + root.resolve(project + "/target/admission-topology-build"));
        var process = new ProcessBuilder(command)
                .directory(root.resolve(project).toFile())
                .redirectErrorStream(true)
                .redirectOutput(
                        logs.resolve(project.contains("identity") ? "identity-build.log" : "classroom-build.log")
                                .toFile())
                .start();
        assertTrue(process.waitFor(180, TimeUnit.SECONDS), "Topology artifact build timed out");
        assertEquals(0, process.exitValue(), "See gateway/target/admission-topology build logs");
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
        fail("Topology process did not become ready; see gateway/target/admission-topology logs");
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
            for (String client : List.of("gateway", "classroom-service", "wrong-client", "wrong-scope")) {
                UUID id = UUID.randomUUID();
                execute(
                        c,
                        "INSERT INTO oauth2_service_client(id,client_id,client_secret_hash,enabled,created_at,updated_at) VALUES (?,?,?,true,now(),now())",
                        id,
                        client,
                        hash);
                execute(
                        c,
                        "INSERT INTO oauth2_service_client_scope(service_client_id,scope) VALUES (?,?)",
                        id,
                        client.equals("wrong-scope") ? "other.scope" : "identity.user-admission");
            }
        }
    }
}
