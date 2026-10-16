package com.quizopia.gateway;

import static com.quizopia.gateway.PublicationTopologyTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import io.lettuce.core.RedisClient;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.containers.*;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual five-service HTTP topology. No mocked Identity/Classroom/Assessment authority. */
@Testcontainers
class ProctoringTopologyTest {
    static final String BROKER_ADMIN =
            "operator_" + UUID.randomUUID().toString().replace("-", "");
    static final String BROKER_ADMIN_PASSWORD = UUID.randomUUID().toString();
    static final String BROKER_WRITER =
            "assessment_" + UUID.randomUUID().toString().replace("-", "");
    static final String BROKER_WRITER_PASSWORD = UUID.randomUUID().toString();
    static final String BROKER_READER =
            "proctoring_" + UUID.randomUUID().toString().replace("-", "");
    static final String BROKER_READER_PASSWORD = UUID.randomUUID().toString();
    static final String VHOST = "monitoring_" + UUID.randomUUID();
    static final String EXCHANGE = "quizopia.assessment.monitoring.v1";
    static final String ROUTING = "answer.persisted.v1";
    static final String QUEUE = "quizopia.proctoring.assessment-monitoring.v1";
    static final String AUDIT_QUEUE = "operator.audit." + UUID.randomUUID();
    static final String LIVEKIT_KEY = "synthetic-" + UUID.randomUUID();
    static final String LIVEKIT_SECRET = UUID.randomUUID().toString() + UUID.randomUUID();
    static final String LIVEKIT_CONFIG =
            "port: 7880\nrtc:\n  tcp_port: 7881\n  port_range_start: 50000\n  port_range_end: 50010\n  use_external_ip: false\nkeys:\n  "
                    + LIVEKIT_KEY + ": " + LIVEKIT_SECRET + "\nlogging:\n  level: warn\n";

    @Container
    static final GenericContainer<?> LIVEKIT = new GenericContainer<>("livekit/livekit-server:v1.9.0")
            .withExposedPorts(7880)
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withPortBindings(
                            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(7880))))
            .withCopyToContainer(
                    Transferable.of(LIVEKIT_CONFIG.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0600),
                    "/tmp/quizopia-livekit.yaml")
            .withCommand("--config", "/tmp/quizopia-livekit.yaml", "--bind", "0.0.0.0")
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/")
                    .forPort(7880)
                    .forStatusCode(200));

    @Container
    static final GenericContainer<?> BROKER = new GenericContainer<>("rabbitmq:4.1-management")
            .withEnv("RABBITMQ_DEFAULT_USER", BROKER_ADMIN)
            .withEnv("RABBITMQ_DEFAULT_PASS", BROKER_ADMIN_PASSWORD)
            .withEnv("RABBITMQ_DEFAULT_VHOST", VHOST)
            .withExposedPorts(5672, 15672)
            .waitingFor(new org.testcontainers.containers.wait.strategy.WaitAllStrategy()
                    .withStrategy(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(
                            ".*Server startup complete.*", 1))
                    .withStrategy(org.testcontainers.containers.wait.strategy.Wait.forHttp("/api/overview")
                            .forPort(15672)
                            .withBasicCredentials(BROKER_ADMIN, BROKER_ADMIN_PASSWORD)
                            .forStatusCode(200)));

    @Container
    static final PostgreSQLContainer<?> IDENTITY = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> CLASSROOM = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> ASSESSMENT = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> PROCTORING = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> CACHE = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SECRET = "synthetic-proctoring-topology-only", PASSWORD = "synthetic-user-password";
    static final UUID STUDENT = UUID.randomUUID(),
            TEACHER = UUID.randomUUID(),
            PEER = UUID.randomUUID(),
            OTHER_TEACHER = UUID.randomUUID();
    static final UUID CLASS = UUID.randomUUID();
    static final Map<String, String> ORIGINS = new LinkedHashMap<>();
    static final List<Process> OWNED = new ArrayList<>();
    static final Map<UUID, String> TOKENS = new HashMap<>();
    static final List<String> MEDIA_TOKENS = new ArrayList<>();
    static Path repository, evidence, keyDirectory;
    static RedisClient redisClient;
    static io.lettuce.core.api.StatefulRedisConnection<String, String> cache;
    static ConfigurableApplicationContext edgeContext;
    static String edge;

    @BeforeAll
    static void startOwnedTopology() throws Exception {
        repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(repository.resolve("AGENTS.md"))) repository = repository.getParent();
        evidence = repository.resolve("gateway/target/proctoring-topology");
        Files.createDirectories(evidence);
        provisionBroker();
        keyDirectory = Files.createTempDirectory("quizopia-proctoring-topology-keys");
        RSAKey signing = AdmissionTestTokens.key();
        for (var entry : Map.of(
                        "private.pem",
                        pem("PRIVATE KEY", signing.toRSAPrivateKey().getEncoded()),
                        "public.pem",
                        pem("PUBLIC KEY", signing.toRSAPublicKey().getEncoded()))
                .entrySet()) {
            Files.writeString(keyDirectory.resolve(entry.getKey()), entry.getValue());
            Files.setPosixFilePermissions(
                    keyDirectory.resolve(entry.getKey()), PosixFilePermissions.fromString("rw-------"));
        }
        for (String service : List.of("identity", "classroom", "assessment", "proctoring")) {
            ORIGINS.put(service, "http://127.0.0.1:" + port());
            buildOwned(service);
        }
        startOwned(
                "identity",
                IDENTITY,
                List.of(
                        "--spring.data.redis.host=" + CACHE.getHost(),
                        "--spring.data.redis.port=" + CACHE.getMappedPort(6379),
                        "--quizopia.identity.email-outbox.encryption.active-key-version=v1",
                        "--quizopia.identity.email-outbox.encryption.keys.v1="
                                + Base64.getEncoder().encodeToString(new byte[32]),
                        "--quizopia.identity.security.authorization-server.enabled=true",
                        "--quizopia.identity.security.authorization-server.issuer=" + ORIGINS.get("identity"),
                        "--quizopia.identity.security.signing-key.kid=" + signing.getKeyID(),
                        "--quizopia.identity.security.signing-key.private-key-path="
                                + keyDirectory.resolve("private.pem"),
                        "--quizopia.identity.security.signing-key.public-key-path="
                                + keyDirectory.resolve("public.pem"),
                        "--quizopia.identity.user-admission.enabled=true",
                        "--quizopia.identity.user-admission.require-secure-transport=false",
                        "--quizopia.identity.user-admission.allowed-clients=gateway,classroom-service,assessment-service,proctoring-service",
                        "--quizopia.identity.user-admission.reconciliation-enabled=false",
                        "--quizopia.identity.proctoring-students.enabled=true",
                        "--quizopia.identity.proctoring-students.require-secure-transport=false"));
        awaitReady(ORIGINS.get("identity") + "/oauth2/jwks");
        seedIdentity();
        redisClient = RedisClient.create("redis://" + CACHE.getHost() + ":" + CACHE.getMappedPort(6379));
        cache = redisClient.connect();
        for (UUID user : List.of(STUDENT, TEACHER, PEER, OTHER_TEACHER)) {
            cache.sync().set(authorityKey(user), projection(user));
            var login = send(
                    "POST",
                    ORIGINS.get("identity") + "/api/auth/login",
                    null,
                    JSON.writeValueAsString(Map.of("identifier", user.toString(), "password", PASSWORD)),
                    Map.of());
            assertEquals(200, login.statusCode(), "Actual Identity login failed");
            TOKENS.put(user, JSON.readTree(login.body()).path("accessToken").asString());
        }
        startOwned(
                "classroom",
                CLASSROOM,
                List.of(
                        "--quizopia.internal-authority.enabled=true",
                        "--quizopia.internal-authority.require-tls=false",
                        "--quizopia.internal-authority.assessment-client-id=assessment-service",
                        "--quizopia.internal-authority.proctoring-membership-enabled=true"));
        awaitReady(ORIGINS.get("classroom") + "/actuator/health");
        try (var c = db(CLASSROOM)) {
            execute(
                    c,
                    "INSERT INTO classrooms(id,owner_teacher_user_id,name,subject,grade) VALUES(?,?,'Synthetic monitored class','Subject','Grade')",
                    CLASS,
                    TEACHER);
            for (UUID student : List.of(STUDENT, PEER))
                execute(
                        c,
                        "INSERT INTO classroom_memberships(id,classroom_id,user_id) VALUES(?,?,?)",
                        UUID.randomUUID(),
                        CLASS,
                        student);
        }
        startOwned(
                "assessment",
                ASSESSMENT,
                List.of(
                        "--quizopia.publication.enabled=true",
                        "--quizopia.assessment.features.attempts-enabled=true",
                        "--quizopia.assessment.features.results-enabled=true",
                        "--quizopia.assessment.monitoring-events.capture-enabled=true",
                        "--quizopia.assessment.monitoring-events.publisher-enabled=true",
                        "--quizopia.assessment.monitoring-events.allow-insecure-loopback=true",
                        "--quizopia.assessment.monitoring-events.host=" + BROKER.getHost(),
                        "--quizopia.assessment.monitoring-events.port=" + BROKER.getMappedPort(5672),
                        "--quizopia.assessment.monitoring-events.virtual-host=" + VHOST,
                        "--quizopia.assessment.monitoring-events.username=" + BROKER_WRITER,
                        "--quizopia.assessment.monitoring-events.password=" + BROKER_WRITER_PASSWORD,
                        "--quizopia.assessment.proctoring-authority.enabled=true",
                        "--quizopia.assessment.proctoring-authority.target-eligibility-enabled=true",
                        "--quizopia.assessment.proctoring-authority.client-id=proctoring-service",
                        "--quizopia.assessment.proctoring-authority.allow-insecure-loopback=true",
                        "--quizopia.publication.sources.enabled=true",
                        "--quizopia.publication.sources.classroom-base-url=" + ORIGINS.get("classroom"),
                        // No Quiz calls occur: immutable synthetic snapshot is seeded locally below.
                        "--quizopia.publication.sources.quiz-base-url=http://127.0.0.1:" + port()));
        awaitReady(ORIGINS.get("assessment") + "/actuator/health");
        startOwned(
                "proctoring",
                PROCTORING,
                List.of(
                        "--quizopia.proctoring.monitoring.enabled=true",
                        "--quizopia.proctoring.assessment-events.enabled=true",
                        "--quizopia.proctoring.assessment-events.host=" + BROKER.getHost(),
                        "--quizopia.proctoring.assessment-events.port=" + BROKER.getMappedPort(5672),
                        "--quizopia.proctoring.assessment-events.virtual-host=" + VHOST,
                        "--quizopia.proctoring.assessment-events.username=" + BROKER_READER,
                        "--quizopia.proctoring.assessment-events.password=" + BROKER_READER_PASSWORD,
                        "--quizopia.proctoring.assessment-events.publisher-identity=" + BROKER_WRITER,
                        "--quizopia.proctoring.assessment-events.tls=false",
                        "--quizopia.proctoring.assessment-events.allow-insecure-loopback=true",
                        "--quizopia.proctoring.monitoring.assessment-base-url=" + ORIGINS.get("assessment"),
                        "--quizopia.proctoring.media.enabled=true",
                        "--quizopia.proctoring.media.worker-enabled=true",
                        "--quizopia.proctoring.media.lease-duration=30s",
                        "--quizopia.proctoring.media.revocation-mode=CLOUD_STRICT",
                        "--quizopia.proctoring.media.qualification-reference=synthetic-owned-server-NOT-Cloud-qualification",
                        "--quizopia.proctoring.media.api-url=http://127.0.0.1:" + LIVEKIT.getMappedPort(7880),
                        "--quizopia.proctoring.media.public-url=ws://127.0.0.1:" + LIVEKIT.getMappedPort(7880),
                        "--quizopia.proctoring.media.api-key=" + LIVEKIT_KEY,
                        "--quizopia.proctoring.media.api-secret=" + LIVEKIT_SECRET,
                        "--quizopia.proctoring.media.allow-insecure-loopback=true"));
        awaitReady(ORIGINS.get("proctoring") + "/actuator/health");
        var args = new ArrayList<String>(List.of(
                "--server.port=0",
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ORIGINS.get("identity"),
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + ORIGINS.get("identity") + "/oauth2/jwks",
                "--IDENTITY_SERVICE_URL=" + ORIGINS.get("identity"),
                "--CLASSROOM_SERVICE_URL=" + ORIGINS.get("classroom"),
                "--ASSESSMENT_SERVICE_URL=" + ORIGINS.get("assessment"),
                "--PROCTORING_SERVICE_URL=" + ORIGINS.get("proctoring"),
                "--quizopia.publication.enabled=true",
                "--quizopia.assessment.features.attempts-enabled=true",
                "--quizopia.assessment.features.results-enabled=true",
                "--quizopia.proctoring.enabled=true",
                "--quizopia.proctoring.media.enabled=true"));
        args.addAll(admission("gateway"));
        edgeContext = new SpringApplicationBuilder(GatewayApplication.class)
                .profiles("test")
                .run(args.toArray(String[]::new));
        edge = "http://127.0.0.1:"
                + ((WebServerApplicationContext) edgeContext).getWebServer().getPort();
    }

    static JsonNode operator(String method, String path, Object body, int expected) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(
                        URI.create("http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(15672) + "/api/" + path))
                .timeout(Duration.ofSeconds(10))
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .header("Authorization", basic(BROKER_ADMIN, BROKER_ADMIN_PASSWORD));
        if (body != null) request.header("Content-Type", "application/json");
        var response = HTTP.send(
                request.method(
                                method,
                                body == null
                                        ? java.net.http.HttpRequest.BodyPublishers.noBody()
                                        : java.net.http.HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(body)))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), "Owned broker operator request failed");
        return response.body().isEmpty() ? JSON.readTree("null") : JSON.readTree(response.body());
    }

    static void provisionBroker() throws Exception {
        for (var user : Map.of(BROKER_WRITER, BROKER_WRITER_PASSWORD, BROKER_READER, BROKER_READER_PASSWORD)
                .entrySet())
            operator("PUT", "users/" + user.getKey(), Map.of("password", user.getValue(), "tags", ""), 201);
        operator(
                "PUT",
                "permissions/" + VHOST + "/" + BROKER_WRITER,
                Map.of("configure", "^$", "write", "^quizopia\\.assessment\\.monitoring\\.v1$", "read", "^$"),
                201);
        operator(
                "PUT",
                "permissions/" + VHOST + "/" + BROKER_READER,
                Map.of(
                        "configure",
                        "^$",
                        "write",
                        "^$",
                        "read",
                        "^quizopia\\.proctoring\\.assessment-monitoring\\.v1$"),
                201);
        operator(
                "PUT",
                "exchanges/" + VHOST + "/" + EXCHANGE,
                Map.of(
                        "type",
                        "direct",
                        "durable",
                        true,
                        "auto_delete",
                        false,
                        "internal",
                        false,
                        "arguments",
                        Map.of()),
                201);
        for (String queue : List.of(QUEUE, AUDIT_QUEUE)) {
            operator(
                    "PUT",
                    "queues/" + VHOST + "/" + queue,
                    Map.of("durable", true, "auto_delete", false, "arguments", Map.of("x-queue-type", "quorum")),
                    201);
            operator(
                    "POST",
                    "bindings/" + VHOST + "/e/" + EXCHANGE + "/q/" + queue,
                    Map.of("routing_key", ROUTING, "arguments", Map.of()),
                    201);
        }
        for (String user : List.of(BROKER_WRITER, BROKER_READER)) {
            var acl = operator("GET", "permissions/" + VHOST + "/" + user, null, 200);
            assertEquals("^$", acl.path("configure").asString());
            assertEquals(
                    user.equals(BROKER_WRITER) ? "^$" : "^quizopia\\.proctoring\\.assessment-monitoring\\.v1$",
                    acl.path("read").asString());
            assertEquals(
                    user.equals(BROKER_READER) ? "^$" : "^quizopia\\.assessment\\.monitoring\\.v1$",
                    acl.path("write").asString());
            assertEquals(
                    "[]",
                    operator("GET", "users/" + user, null, 200).path("tags").toString());
        }
    }

    static void buildOwned(String name) throws Exception {
        Path module = repository.resolve("services/" + name + "-service");
        var p = new ProcessBuilder(
                        "bash",
                        "mvnw",
                        "-B",
                        "-DskipTests",
                        "-Dquizopia.build.directory=" + module.resolve("target/proctoring-topology-build"),
                        "package")
                .directory(module.toFile())
                .redirectErrorStream(true)
                .redirectOutput(evidence.resolve(name + "-build.log").toFile())
                .start();
        try {
            assertTrue(p.waitFor(180, TimeUnit.SECONDS), "Owned topology build timeout: " + name);
            assertEquals(0, p.exitValue(), "Owned artifact build failed: " + name);
        } finally {
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroy);
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    static void startOwned(String name, PostgreSQLContainer<?> db, List<String> extra) throws Exception {
        var args = new ArrayList<String>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                repository
                        .resolve("services/" + name + "-service/target/proctoring-topology-build/quizopia-" + name
                                + "-0.1.0-SNAPSHOT.jar")
                        .toString(),
                "--server.port=" + URI.create(ORIGINS.get(name)).getPort(),
                "--spring.profiles.active=local",
                "--spring.datasource.url=" + db.getJdbcUrl(),
                "--spring.datasource.username=" + db.getUsername(),
                "--spring.datasource.password=" + db.getPassword()));
        if (!name.equals("identity")) {
            args.addAll(admission(name + "-service"));
            args.add("--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + ORIGINS.get("identity"));
            args.add("--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + ORIGINS.get("identity")
                    + "/oauth2/jwks");
        }
        args.addAll(extra);
        OWNED.add(new ProcessBuilder(args)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .redirectOutput(evidence.resolve(name + ".log").toFile())
                .start());
    }

    static List<String> admission(String client) {
        return List.of(
                "--quizopia.admission.enabled=true",
                "--quizopia.admission.allow-insecure-loopback=true",
                "--quizopia.admission.identity-base-url=" + ORIGINS.get("identity"),
                "--quizopia.admission.client-id=" + client,
                "--quizopia.admission.client-secret=" + SECRET);
    }

    static void awaitReady(String url) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < until) {
            try {
                if (send("GET", url, null, null, Map.of()).statusCode() == 200) return;
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        fail("Owned topology did not become ready; inspect gateway/target/proctoring-topology logs");
    }

    static Connection db(PostgreSQLContainer<?> db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    static String authorityKey(UUID user) {
        return "quizopia:identity:user-authority:v1:" + user;
    }

    static String roles(UUID user) {
        return user.equals(TEACHER) || user.equals(OTHER_TEACHER) ? "STUDENT,TEACHER" : "STUDENT";
    }

    static String projection(UUID user) {
        return "1|" + user + "|PRESENT|VALUE:QUNUSVZF|VERIFIED|" + roles(user) + "|NONE";
    }

    static void seedIdentity() throws Exception {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        try (var c = db(IDENTITY)) {
            for (UUID user : List.of(STUDENT, TEACHER, PEER, OTHER_TEACHER)) {
                execute(
                        c,
                        "INSERT INTO user_account(id,email,username,account_status,email_verified_at,created_at,updated_at) VALUES(?,?,?,'ACTIVE',now(),now(),now())",
                        user,
                        user + "@gmail.com",
                        user.toString());
                for (String role : roles(user).split(","))
                    execute(
                            c,
                            "INSERT INTO user_role(id,user_id,role,created_at) VALUES(?,?,?,now())",
                            UUID.randomUUID(),
                            user,
                            role);
                execute(
                        c,
                        "INSERT INTO local_credential(user_id,password_hash,created_at,updated_at) VALUES(?,?,now(),now())",
                        user,
                        encoder.encode(PASSWORD));
            }
            for (String client : List.of(
                    "gateway", "classroom-service", "assessment-service", "proctoring-service", "wrong-client")) {
                UUID id = UUID.randomUUID();
                execute(
                        c,
                        "INSERT INTO oauth2_service_client(id,client_id,client_secret_hash,enabled,created_at,updated_at) VALUES(?,?,?,true,now(),now())",
                        id,
                        client,
                        encoder.encode(SECRET));
                var scopes = new ArrayList<>(List.of("identity.user-admission"));
                if (client.equals("assessment-service"))
                    scopes.addAll(List.of(
                            "classroom.teacher-authority.read",
                            "classroom.membership.read",
                            "identity.proctoring-student.read",
                            "classroom.proctoring-membership.read"));
                if (client.equals("proctoring-service") || client.equals("wrong-client"))
                    scopes.add("assessment.proctoring-authority.read");
                for (String scope : scopes)
                    execute(
                            c,
                            "INSERT INTO oauth2_service_client_scope(service_client_id,scope) VALUES(?,?)",
                            id,
                            scope);
            }
        }
    }

    static String credential(String client, String scope) throws Exception {
        // Form issuance uses the real authorization server, separate from JSON business requests.
        var response = HTTP.send(
                java.net.http.HttpRequest.newBuilder(URI.create(ORIGINS.get("identity") + "/oauth2/token"))
                        .header("Authorization", basic(client, SECRET))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                "grant_type=client_credentials&scope=" + scope))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "Real SERVICE issuance failed");
        return JSON.readTree(response.body()).path("access_token").asString();
    }

    static HttpResponse<String> request(String method, String base, String path, UUID actor, String body, boolean key)
            throws Exception {
        return send(
                method,
                base + path,
                TOKENS.get(actor),
                body,
                key ? Map.of("Idempotency-Key", UUID.randomUUID().toString()) : Map.of());
    }

    static JsonNode expect(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), "Unexpected protected HTTP status; response body omitted");
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(response.body().contains(SECRET));
        assertFalse(response.body().contains("127.0.0.1"));
        for (String token : TOKENS.values()) assertFalse(response.body().contains(token));
        return JSON.readTree(response.body());
    }

    static UUID monitoredPublication() throws Exception {
        UUID id = UUID.randomUUID(), quiz = UUID.randomUUID(), version = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS),
                end = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        var configuration = new LinkedHashMap<String, Object>();
        configuration.put("title", "Synthetic monitored publication");
        configuration.put("description", null);
        configuration.put("quizId", quiz);
        configuration.put("quizVersionId", version);
        configuration.put("versionNumber", 1);
        configuration.put("audience", "CLASS");
        configuration.put("classroomId", CLASS);
        configuration.put("startAt", start.toString());
        configuration.put("endAt", end.toString());
        configuration.put("durationMinutes", 5);
        configuration.put("monitoringChoice", "ENABLED");
        configuration.put("maxAttempts", 0);
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("quizId", quiz);
        snapshot.put("quizVersionId", version);
        snapshot.put("versionNumber", 1);
        snapshot.put("titleSnapshot", "Synthetic quiz");
        snapshot.put("descriptionSnapshot", null);
        snapshot.put("sourceSnapshot", "private synthetic answer 0001");
        snapshot.put("contentSchemaVersion", 1);
        snapshot.put("createdAt", start.toString());
        var question = new LinkedHashMap<String, Object>();
        question.put("number", 1);
        question.put("type", "NUMERIC_FILL");
        question.put("stemMarkdown", "Synthetic question");
        question.put("numericAnswer", "0001");
        question.put("explanationMarkdown", "private synthetic explanation");
        question.put("options", List.of());
        snapshot.put("structuredContent", Map.of("questions", List.of(question)));
        try (var c = db(ASSESSMENT)) {
            c.setAutoCommit(false);
            execute(
                    c,
                    "INSERT INTO publications(id,owner_id,configuration,quiz_id,quiz_version_id,version_number,audience,classroom_id,start_at,end_at,duration_minutes,monitoring_choice,state,revision,created_at,max_attempts) VALUES(?,?,?::jsonb,?,?,1,'CLASS',?,?::timestamptz,?::timestamptz,5,'ENABLED','DRAFT',0,now(),0)",
                    id,
                    TEACHER,
                    JSON.writeValueAsString(configuration),
                    quiz,
                    version,
                    CLASS,
                    start.toString(),
                    end.toString());
            execute(
                    c,
                    "INSERT INTO publication_snapshots VALUES(?,1,1,?::jsonb,now())",
                    id,
                    JSON.writeValueAsString(snapshot));
            execute(
                    c,
                    "UPDATE publications SET state='OPEN',snapshot_id=id,opened_at=now(),opened_from_revision=0,revision=1 WHERE id=?",
                    id);
            c.commit();
        }
        return id;
    }

    static UUID attempt(UUID publication) throws Exception {
        return UUID.fromString(expect(
                        201,
                        request(
                                "POST",
                                edge,
                                "/api/assessments/publications/" + publication + "/attempts/start",
                                STUDENT,
                                "{}",
                                true))
                .path("attemptId")
                .asString());
    }

    static UUID session(UUID attempt) throws Exception {
        return UUID.fromString(expect(
                        200,
                        request(
                                "POST",
                                edge,
                                "/api/proctoring/monitoring/sessions",
                                STUDENT,
                                JSON.writeValueAsString(Map.of("attemptId", attempt)),
                                false))
                .path("id")
                .asString());
    }

    static String sessionPath(UUID session) {
        return "/api/proctoring/monitoring/sessions/" + session;
    }

    static String assessmentFingerprint(UUID attempt) throws Exception {
        // Owning-test inspection includes answers/results/operations, not only the Attempt row.
        try (var c = db(ASSESSMENT);
                var statement = c.prepareStatement(
                        """
                    SELECT a.state, jsonb_build_object(
                      'attempt',to_jsonb(a),
                      'answers',(SELECT jsonb_agg(to_jsonb(x) ORDER BY question_number) FROM attempt_answers x WHERE attempt_id=a.id),
                      'submitted',(SELECT to_jsonb(x) FROM submitted_answers x WHERE attempt_id=a.id),
                      'grade',(SELECT to_jsonb(x) FROM grading_results x WHERE attempt_id=a.id),
                      'questions',(SELECT jsonb_agg(to_jsonb(x) ORDER BY question_number) FROM grading_question_results x WHERE result_id=a.result_id),
                      'operations',(SELECT jsonb_agg(to_jsonb(x) ORDER BY key_digest) FROM attempt_operations x WHERE attempt_id=a.id),
                      'audit',(SELECT to_jsonb(x) FROM attempt_finalization_audit x WHERE attempt_id=a.id)
                    )::text FROM attempts a WHERE id=?
                    """)) {
            statement.setObject(1, attempt);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(rows.getString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return rows.getString(1) + ":" + HexFormat.of().formatHex(digest);
            }
        }
    }

    static long evidenceCount(UUID session) throws Exception {
        try (var c = db(PROCTORING);
                var s = c.prepareStatement("SELECT count(*) FROM monitoring_evidence WHERE session_id=?")) {
            s.setObject(1, session);
            try (var rows = s.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    static UUID expectGrant(HttpResponse<String> response, String role) throws Exception {
        assertEquals(200, response.statusCode(), "Media issuance failed; protected response body omitted");
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var body = JSON.readTree(response.body());
        assertEquals(6, body.size());
        assertEquals(role, body.path("role").asString());
        assertEquals("GRANT_ISSUED", body.path("technicalState").asString());
        assertEquals(
                "ws://127.0.0.1:" + LIVEKIT.getMappedPort(7880),
                body.path("url").asString());
        assertFalse(response.body().contains(LIVEKIT_SECRET));
        String wire = body.path("token").asString();
        MEDIA_TOKENS.add(wire);
        SignedJWT token;
        try {
            token = SignedJWT.parse(wire);
        } catch (java.text.ParseException invalid) {
            fail("Media response did not contain a valid JWT; protected value omitted");
            return null;
        }
        assertTrue(token.verify(new MACVerifier(LIVEKIT_SECRET)), "Owned SFU grant signature must verify");
        var claims = token.getJWTClaimsSet();
        assertEquals(LIVEKIT_KEY, claims.getIssuer());
        var video = claims.getJSONObjectClaim("video");
        assertEquals(true, video.get("roomJoin"));
        assertEquals(role.equals("STUDENT"), video.get("canPublish"));
        assertEquals(role.equals("TEACHER"), video.get("canSubscribe"));
        assertEquals(false, video.get("canPublishData"));
        for (String privilege : List.of("roomCreate", "roomAdmin", "roomList", "roomRecord", "canUpdateOwnMetadata"))
            assertNotEquals(true, video.get(privilege));
        if (role.equals("STUDENT")) assertEquals(List.of("camera"), video.get("canPublishSources"));
        assertTrue(claims.getSubject().startsWith("p-"));
        assertTrue(((String) video.get("room")).startsWith("m-"));
        var expiry = claims.getExpirationTime().toInstant();
        assertEquals(Instant.parse(body.path("leaseUntil").asString()).truncatedTo(ChronoUnit.SECONDS), expiry);
        assertTrue(expiry.isAfter(Instant.now()));
        assertFalse(expiry.isAfter(Instant.now().plusSeconds(30)));
        return UUID.fromString(body.path("id").asString());
    }

    static String mediaFingerprint(UUID session) throws Exception {
        return scalar(
                PROCTORING,
                "SELECT coalesce(jsonb_agg(to_jsonb(g) ORDER BY id),'[]'::jsonb)::text FROM monitoring_media_grants g WHERE session_id=?",
                session);
    }

    static JsonNode privateAuthority(UUID attempt, UUID actor, String purpose) throws Exception {
        return expect(
                200,
                send(
                        "POST",
                        ORIGINS.get("assessment") + "/internal/proctoring/attempts/" + attempt + "/authority",
                        credential("proctoring-service", "assessment.proctoring-authority.read"),
                        JSON.writeValueAsString(Map.of("viewerRole", "TEACHER", "purpose", purpose)),
                        Map.of("X-Quizopia-User-Evidence", TOKENS.get(actor))));
    }

    static void assertLiveMediaDenied(UUID attempt, UUID session, UUID grant, int status) throws Exception {
        String assessment = assessmentFingerprint(attempt), grants = mediaFingerprint(session);
        for (String base : List.of(edge, ORIGINS.get("proctoring"))) {
            expect(status, request("POST", base, sessionPath(session) + "/media-grants", TEACHER, null, false));
            expect(
                    status,
                    request(
                            "POST",
                            base,
                            sessionPath(session) + "/media-grants/" + grant + "/renew",
                            TEACHER,
                            null,
                            false));
        }
        assertEquals(grants, mediaFingerprint(session), "Denied live authority must not create or renew a grant");
        assertEquals(assessment, assessmentFingerprint(attempt), "Monitoring denial must preserve Assessment");
    }

    @Test
    void teacherLiveMediaRequiresCurrentTargetMembershipWhileHistoricalEvidenceRemainsVisible() throws Exception {
        UUID attempt = attempt(monitoredPublication()), session = session(attempt);
        var live = privateAuthority(attempt, TEACHER, "LIVE_MEDIA");
        assertEquals(10, live.size());
        assertEquals(TEACHER.toString(), live.path("actorId").asString());
        assertEquals(STUDENT.toString(), live.path("studentId").asString());
        assertEquals("LIVE_MEDIA", live.path("purpose").asString());
        assertTrue(live.path("active").asBoolean());
        UUID grant = expectGrant(
                request("POST", edge, sessionPath(session) + "/media-grants", TEACHER, null, false), "TEACHER");
        assertEquals(
                grant,
                expectGrant(
                        request(
                                "POST",
                                edge,
                                sessionPath(session) + "/media-grants/" + grant + "/renew",
                                TEACHER,
                                null,
                                false),
                        "TEACHER"));
        for (String base : List.of(edge, ORIGINS.get("proctoring")))
            expect(404, request("POST", base, sessionPath(session) + "/media-grants", OTHER_TEACHER, null, false));
        try (var connection = db(CLASSROOM)) {
            execute(connection, "DELETE FROM classroom_memberships WHERE classroom_id=? AND user_id=?", CLASS, STUDENT);
        }
        try {
            assertLiveMediaDenied(attempt, session, grant, 404);
            var historical = privateAuthority(attempt, TEACHER, "HISTORY");
            assertEquals("HISTORY", historical.path("purpose").asString());
            for (String base : List.of(edge, ORIGINS.get("proctoring")))
                expect(200, request("GET", base, sessionPath(session) + "/events", TEACHER, null, false));
        } finally {
            try (var connection = db(CLASSROOM)) {
                execute(
                        connection,
                        "INSERT INTO classroom_memberships(id,classroom_id,user_id) VALUES(?,?,?)",
                        UUID.randomUUID(),
                        CLASS,
                        STUDENT);
            }
        }
        try (var connection = db(CLASSROOM)) {
            execute(connection, "UPDATE classrooms SET owner_teacher_user_id=? WHERE id=?", OTHER_TEACHER, CLASS);
        }
        try {
            assertLiveMediaDenied(attempt, session, grant, 404);
        } finally {
            try (var connection = db(CLASSROOM)) {
                execute(connection, "UPDATE classrooms SET owner_teacher_user_id=? WHERE id=?", TEACHER, CLASS);
            }
        }
    }

    @Test
    void teacherLiveMediaRequiresCurrentTargetAccountAndAttemptStartRevocationFence() throws Exception {
        UUID attempt = attempt(monitoredPublication()), session = session(attempt);
        UUID grant = expectGrant(
                request("POST", edge, sessionPath(session) + "/media-grants", TEACHER, null, false), "TEACHER");
        try {
            try (var connection = db(IDENTITY)) {
                execute(connection, "UPDATE user_account SET account_status='DISABLED' WHERE id=?", STUDENT);
            }
            assertLiveMediaDenied(attempt, session, grant, 503);
            cache.sync().set(authorityKey(STUDENT), projection(STUDENT).replace("QUNUSVZF", "RElTQUJMRUQ"));
            assertLiveMediaDenied(attempt, session, grant, 404);
            assertEquals(
                    "HISTORY",
                    privateAuthority(attempt, TEACHER, "HISTORY")
                            .path("purpose")
                            .asString());
            expect(200, request("GET", edge, sessionPath(session) + "/events", TEACHER, null, false));
        } finally {
            try (var connection = db(IDENTITY)) {
                execute(connection, "UPDATE user_account SET account_status='ACTIVE' WHERE id=?", STUDENT);
            }
            cache.sync().set(authorityKey(STUDENT), projection(STUDENT));
        }
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            try (var connection = db(IDENTITY)) {
                execute(
                        connection,
                        "INSERT INTO user_access_revocation(user_id,revoked_before) VALUES(?,?::timestamptz)",
                        STUDENT,
                        cutoff.toString());
            }
            assertLiveMediaDenied(attempt, session, grant, 503);
            String projected = projection(STUDENT);
            cache.sync()
                    .set(
                            authorityKey(STUDENT),
                            projected.substring(0, projected.lastIndexOf('|') + 1) + "AT:" + cutoff.getEpochSecond()
                                    + ":" + cutoff.getNano());
            assertLiveMediaDenied(attempt, session, grant, 404);
            expect(200, request("GET", edge, sessionPath(session) + "/events", TEACHER, null, false));
        } finally {
            try (var connection = db(IDENTITY)) {
                execute(connection, "DELETE FROM user_access_revocation WHERE user_id=?", STUDENT);
            }
            cache.sync().set(authorityKey(STUDENT), projection(STUDENT));
        }
    }

    @Test
    void targetClassroomAuthorityOutageDeniesTeacherLiveCreateAndRenewWithoutMutation() throws Exception {
        UUID attempt = attempt(monitoredPublication()), session = session(attempt);
        UUID grant = expectGrant(
                request("POST", edge, sessionPath(session) + "/media-grants", TEACHER, null, false), "TEACHER");
        CLASSROOM
                .getDockerClient()
                .pauseContainerCmd(CLASSROOM.getContainerId())
                .exec();
        try {
            assertLiveMediaDenied(attempt, session, grant, 503);
        } finally {
            CLASSROOM
                    .getDockerClient()
                    .unpauseContainerCmd(CLASSROOM.getContainerId())
                    .exec();
        }
        assertEquals(
                grant,
                expectGrant(
                        request(
                                "POST",
                                edge,
                                sessionPath(session) + "/media-grants/" + grant + "/renew",
                                TEACHER,
                                null,
                                false),
                        "TEACHER"));
    }

    @Test
    void realSessionEvidenceAndCurrentRoleResourceBoundaries() throws Exception {
        UUID attempt = attempt(monitoredPublication()), session = session(attempt);
        String path = sessionPath(session);
        var body = JSON.writeValueAsString(Map.of(
                "schemaVersion",
                1,
                "eventId",
                UUID.randomUUID(),
                "streamId",
                UUID.randomUUID(),
                "sequence",
                0,
                "type",
                "ANSWER_CHANGED",
                "questionNumber",
                1));
        var ack = expect(200, request("POST", edge, path + "/events", STUDENT, body, false));
        assertEquals(
                ack, expect(200, request("POST", ORIGINS.get("proctoring"), path + "/events", STUDENT, body, false)));
        for (String base : List.of(edge, ORIGINS.get("proctoring"))) {
            String lookup = "/api/proctoring/monitoring/sessions/by-attempt/" + attempt;
            assertEquals(
                    session.toString(),
                    expect(200, request("GET", base, lookup, TEACHER, null, false))
                            .path("id")
                            .asString());
            expect(404, request("GET", base, lookup, OTHER_TEACHER, null, false));
            expect(403, request("GET", base, lookup, STUDENT, null, false));
            var history = expect(200, request("GET", base, path + "/events", TEACHER, null, false));
            assertTrue(history.toString().contains("ANSWER_CHANGED"));
            for (String secret : List.of("0001", "numericAnswer", "sourceSnapshot", "private synthetic explanation"))
                assertFalse(history.toString().contains(secret));
            expect(404, request("GET", base, path, PEER, null, false));
            expect(404, request("GET", base, path + "/events", OTHER_TEACHER, null, false));
            expect(403, request("GET", base, path + "/events", STUDENT, null, false));
            // This fixture explicitly opts into an owned synthetic SFU, never provider qualification.
            UUID grant = expectGrant(request("POST", base, path + "/media-grants", STUDENT, null, false), "STUDENT");
            expect(404, request("GET", base, path + "/media-grants/" + grant, PEER, null, false));
            expect(404, request("GET", base, path + "/media-grants/" + grant, TEACHER, null, false));
        }
        long before = evidenceCount(session);
        try (var c = db(CLASSROOM)) {
            execute(c, "DELETE FROM classroom_memberships WHERE classroom_id=? AND user_id=?", CLASS, STUDENT);
        }
        try {
            for (String base : List.of(edge, ORIGINS.get("proctoring")))
                expect(404, request("POST", base, path + "/events", STUDENT, body, false));
        } finally {
            try (var c = db(CLASSROOM)) {
                execute(
                        c,
                        "INSERT INTO classroom_memberships(id,classroom_id,user_id) VALUES(?,?,?)",
                        UUID.randomUUID(),
                        CLASS,
                        STUDENT);
            }
        }
        assertEquals(before, evidenceCount(session));
        // Retaining Publication ownership cannot bypass subsequently lost Classroom assignment.
        try (var c = db(CLASSROOM)) {
            execute(c, "UPDATE classrooms SET owner_teacher_user_id=? WHERE id=?", OTHER_TEACHER, CLASS);
        }
        try {
            for (String base : List.of(edge, ORIGINS.get("proctoring")))
                expect(404, request("GET", base, path + "/events", TEACHER, null, false));
        } finally {
            try (var c = db(CLASSROOM)) {
                execute(c, "UPDATE classrooms SET owner_teacher_user_id=? WHERE id=?", TEACHER, CLASS);
            }
        }
        assertEquals(before, evidenceCount(session));
    }

    @Test
    void privateAuthorityRequiresExactServiceAndIndependentlyVerifiedOriginalUser() throws Exception {
        UUID attempt = attempt(monitoredPublication());
        String path = "/internal/proctoring/attempts/" + attempt + "/authority",
                body = "{\"viewerRole\":\"STUDENT\",\"purpose\":\"HISTORY\"}";
        String service = credential("proctoring-service", "assessment.proctoring-authority.read");
        var view = expect(
                200,
                send(
                        "POST",
                        ORIGINS.get("assessment") + path,
                        service,
                        body,
                        Map.of("X-Quizopia-User-Evidence", TOKENS.get(STUDENT))));
        assertEquals(10, view.size());
        assertEquals("HISTORY", view.path("purpose").asString());
        assertEquals(STUDENT.toString(), view.path("actorId").asString());
        for (String invalidPurpose :
                List.of("{\"viewerRole\":\"STUDENT\"}", "{\"viewerRole\":\"STUDENT\",\"purpose\":\"UNKNOWN\"}"))
            expect(
                    400,
                    send(
                            "POST",
                            ORIGINS.get("assessment") + path,
                            service,
                            invalidPurpose,
                            Map.of("X-Quizopia-User-Evidence", TOKENS.get(STUDENT))));
        expect(
                403,
                send(
                        "POST",
                        ORIGINS.get("assessment") + path,
                        TOKENS.get(STUDENT),
                        body,
                        Map.of("X-Quizopia-User-Evidence", TOKENS.get(STUDENT))));
        expect(
                403,
                send(
                        "POST",
                        ORIGINS.get("assessment") + path,
                        credential("wrong-client", "assessment.proctoring-authority.read"),
                        body,
                        Map.of("X-Quizopia-User-Evidence", TOKENS.get(STUDENT))));
        expect(
                403,
                send(
                        "POST",
                        ORIGINS.get("assessment") + path,
                        credential("proctoring-service", "identity.user-admission"),
                        body,
                        Map.of("X-Quizopia-User-Evidence", TOKENS.get(STUDENT))));
        expect(
                401,
                send(
                        "POST",
                        ORIGINS.get("assessment") + path,
                        service,
                        body,
                        Map.of("X-Quizopia-User-Evidence", "invalid-original-user")));
        String original = TOKENS.get(STUDENT);
        int signature = original.lastIndexOf('.') + 1;
        String forged = original.substring(0, signature)
                + (original.charAt(signature) == 'A' ? 'B' : 'A')
                + original.substring(signature + 1);
        expect(
                401,
                send(
                        "POST",
                        ORIGINS.get("assessment") + path,
                        service,
                        body,
                        Map.of("X-Quizopia-User-Evidence", forged)));
        expect(
                401,
                send(
                        "POST",
                        ORIGINS.get("assessment") + path,
                        service,
                        body,
                        Map.of("X-Quizopia-User-Evidence", service)));
        expect(
                404,
                send(
                        "POST",
                        ORIGINS.get("assessment") + path,
                        service,
                        body,
                        Map.of("X-Quizopia-User-Evidence", TOKENS.get(PEER))));
        expect(403, send("POST", edge + path, service, body, Map.of("X-Quizopia-User-Evidence", TOKENS.get(STUDENT))));
    }

    @Test
    void submitAndAbandonEndOnlyMonitoringAndKeepCommittedAssessmentState() throws Exception {
        for (String terminal : List.of("submit", "abandon")) {
            UUID attempt = attempt(monitoredPublication()), session = session(attempt);
            if (terminal.equals("submit"))
                expect(
                        200,
                        request(
                                "POST",
                                edge,
                                "/api/assessments/attempts/" + attempt + "/questions/1/answer",
                                STUDENT,
                                "{\"expectedRevision\":\"0\",\"answer\":{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}}",
                                true));
            var result = expect(
                    200,
                    request(
                            "POST",
                            edge,
                            "/api/assessments/attempts/" + attempt + "/" + terminal,
                            STUDENT,
                            "{\"expectedRevision\":\"" + (terminal.equals("submit") ? 1 : 0) + "\"}",
                            true));
            if (terminal.equals("submit"))
                assertEquals("100.00", result.path("percentageDisplay").asString());
            String before = assessmentFingerprint(attempt);
            for (String base : List.of(edge, ORIGINS.get("proctoring")))
                assertEquals(
                        "ENDED",
                        expect(200, request("POST", base, sessionPath(session) + "/reconcile", STUDENT, null, false))
                                .path("status")
                                .asString());
            assertEquals(before, assessmentFingerprint(attempt));
            if (terminal.equals("submit"))
                assertEquals(
                        result,
                        expect(
                                200,
                                request(
                                        "GET",
                                        edge,
                                        "/api/assessments/attempts/" + attempt + "/result",
                                        STUDENT,
                                        null,
                                        false)));
        }
    }

    @Test
    void authoritativeDeadlineEndsMonitoringWithoutSubmittingOrGradingAttempt() throws Exception {
        UUID publication = monitoredPublication(), attempt = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(290).truncatedTo(ChronoUnit.MICROS),
                deadline = start.plusSeconds(300);
        try (var c = db(ASSESSMENT)) {
            execute(
                    c,
                    "INSERT INTO attempts(id,publication_id,student_id,ordinal,snapshot_id,grading_policy_version,answer_schema_version,state,duration_minutes,started_at,deadline_at) VALUES(?,?,?,1,?,1,1,'ACTIVE',5,?::timestamptz,?::timestamptz)",
                    attempt,
                    publication,
                    STUDENT,
                    publication,
                    start.toString(),
                    deadline.toString());
        }
        UUID session = session(attempt);
        String before = assessmentFingerprint(attempt);
        long wait = Duration.between(Instant.now(), deadline.plusMillis(100)).toMillis();
        if (wait > 0) Thread.sleep(wait);
        assertEquals(
                "ENDED",
                expect(200, request("POST", edge, sessionPath(session) + "/reconcile", STUDENT, null, false))
                        .path("status")
                        .asString());
        assertEquals(before, assessmentFingerprint(attempt));
        assertTrue(before.contains("ACTIVE"));
    }

    @Test
    void missingCurrentIdentityEqualityAndAssessmentOutageFailClosedWithoutEvidenceMutation() throws Exception {
        UUID attempt = attempt(monitoredPublication()), session = session(attempt);
        String path = sessionPath(session);
        long before = evidenceCount(session);
        String original = assessmentFingerprint(attempt);
        cache.sync().del(authorityKey(STUDENT));
        try {
            for (String base : List.of(edge, ORIGINS.get("proctoring")))
                expect(503, request("GET", base, path, STUDENT, null, false));
        } finally {
            cache.sync().set(authorityKey(STUDENT), projection(STUDENT));
        }
        ASSESSMENT
                .getDockerClient()
                .pauseContainerCmd(ASSESSMENT.getContainerId())
                .exec();
        try {
            for (String base : List.of(edge, ORIGINS.get("proctoring")))
                expect(503, request("POST", base, path + "/reconcile", STUDENT, null, false));
        } finally {
            ASSESSMENT
                    .getDockerClient()
                    .unpauseContainerCmd(ASSESSMENT.getContainerId())
                    .exec();
        }
        assertEquals(
                "ACTIVE",
                expect(200, request("GET", edge, path, STUDENT, null, false))
                        .path("status")
                        .asString());
        assertEquals(before, evidenceCount(session));
        assertEquals(original, assessmentFingerprint(attempt));
    }

    @Test
    void accountDisableRequiresPrimaryRedisEqualityAndNeverFallsBackToJwt() throws Exception {
        UUID attempt = attempt(monitoredPublication()), session = session(attempt);
        String before = assessmentFingerprint(attempt);
        long count = evidenceCount(session);
        try {
            try (var c = db(IDENTITY)) {
                execute(c, "UPDATE user_account SET account_status='DISABLED' WHERE id=?", STUDENT);
            }
            for (String base : List.of(edge, ORIGINS.get("proctoring")))
                expect(503, request("GET", base, sessionPath(session), STUDENT, null, false));
            cache.sync().set(authorityKey(STUDENT), projection(STUDENT).replace("QUNUSVZF", "RElTQUJMRUQ"));
            for (String base : List.of(edge, ORIGINS.get("proctoring")))
                expect(401, request("GET", base, sessionPath(session), STUDENT, null, false));
        } finally {
            try (var c = db(IDENTITY)) {
                execute(c, "UPDATE user_account SET account_status='ACTIVE' WHERE id=?", STUDENT);
            }
            cache.sync().set(authorityKey(STUDENT), projection(STUDENT));
        }
        assertEquals(count, evidenceCount(session));
        assertEquals(before, assessmentFingerprint(attempt));
    }

    static String scalar(PostgreSQLContainer<?> database, String sql, Object... arguments) throws Exception {
        try (var connection = db(database);
                var query = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) query.setObject(i + 1, arguments[i]);
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    static void awaitValue(PostgreSQLContainer<?> database, String sql, String value, Object... arguments)
            throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        do {
            if (Objects.equals(value, scalar(database, sql, arguments))) return;
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        assertEquals(value, scalar(database, sql, arguments), "Durable monitoring delivery did not converge");
    }

    static JsonNode savedAnswer(UUID attempt, String revision, String answer, String key) throws Exception {
        return expect(
                200,
                send(
                        "POST",
                        edge + "/api/assessments/attempts/" + attempt + "/questions/1/answer",
                        TOKENS.get(STUDENT),
                        JSON.writeValueAsString(Map.of(
                                "expectedRevision",
                                revision,
                                "answer",
                                Map.of("type", "NUMERIC_FILL", "value", answer))),
                        Map.of("Idempotency-Key", key)));
    }

    static UUID factId(UUID attempt, long revision) throws Exception {
        return UUID.fromString(scalar(
                ASSESSMENT,
                "SELECT event_id::text FROM assessment_monitoring_outbox WHERE attempt_id=? AND answer_revision=?",
                attempt,
                revision));
    }

    static void delivered(UUID event, String inboxState) throws Exception {
        awaitValue(ASSESSMENT, "SELECT state FROM assessment_monitoring_outbox WHERE event_id=?", "PUBLISHED", event);
        awaitValue(PROCTORING, "SELECT state FROM assessment_monitoring_inbox WHERE event_id=?", inboxState, event);
    }

    static void assertWireFact(UUID event, UUID attempt, String revision) throws Exception {
        var messages = operator(
                "POST",
                "queues/" + VHOST + "/" + AUDIT_QUEUE + "/get",
                Map.of("count", 100, "ackmode", "ack_requeue_false", "encoding", "auto", "truncate", 65536),
                200);
        JsonNode matched = null;
        for (JsonNode message : messages)
            if (event.toString()
                    .equals(message.path("properties").path("message_id").asString())) matched = message;
        assertNotNull(matched, "Confirmed message missing from independent operator audit queue");
        var metadata = matched.path("properties");
        assertEquals(2, metadata.path("delivery_mode").asInt());
        assertEquals(BROKER_WRITER, metadata.path("user_id").asString());
        assertEquals("assessment-service", metadata.path("app_id").asString());
        assertEquals("ASSESSMENT_ANSWER_PERSISTED", metadata.path("type").asString());
        assertEquals("application/json", metadata.path("content_type").asString());
        assertEquals(EXCHANGE, matched.path("exchange").asString());
        assertEquals(ROUTING, matched.path("routing_key").asString());
        var payload = JSON.readTree(matched.path("payload").asString());
        assertEquals(12, payload.size());
        for (String field : List.of(
                "eventId",
                "eventType",
                "eventVersion",
                "occurredAt",
                "attemptId",
                "publicationId",
                "classroomId",
                "studentId",
                "attemptStartedAt",
                "deadlineAt",
                "questionNumber",
                "revision")) assertFalse(payload.path(field).isMissingNode());
        assertEquals(event.toString(), payload.path("eventId").asString());
        assertEquals(attempt.toString(), payload.path("attemptId").asString());
        assertEquals(revision, payload.path("revision").asString());
        assertSanitizedFact(payload.toString());
    }

    static void assertSanitizedFact(String body) {
        for (String sensitive : List.of(
                "\"0001\"",
                "\"0002\"",
                "\"0003\"",
                "numericAnswer",
                "sourceSnapshot",
                "explanationMarkdown",
                "answerDigest",
                "canonical_answers",
                SECRET,
                PASSWORD,
                BROKER_WRITER_PASSWORD,
                BROKER_READER_PASSWORD))
            assertFalse(body.contains(sensitive), "Monitoring metadata exposed protected content");
        for (String jwt : TOKENS.values()) assertFalse(body.contains(jwt));
    }

    @Test
    void actualAnswerOutboxBrokerInboxAndHistoricalEvidenceRemainCoherentAcrossRecovery() throws Exception {
        UUID attempt = attempt(monitoredPublication());
        String firstKey = UUID.randomUUID().toString();
        savedAnswer(attempt, "0", "0001", firstKey);
        UUID first = factId(attempt, 1);
        delivered(first, "PENDING");
        assertEquals("0", scalar(PROCTORING, "SELECT count(*) FROM monitoring_sessions WHERE attempt_id=?", attempt));
        assertEquals("0", scalar(PROCTORING, "SELECT count(*) FROM monitoring_evidence WHERE id=?", first));
        assertWireFact(first, attempt, "1");
        UUID session = session(attempt);
        delivered(first, "APPLIED");
        String secondKey = UUID.randomUUID().toString();
        JsonNode secondSave;
        UUID second;
        BROKER.getDockerClient().pauseContainerCmd(BROKER.getContainerId()).exec();
        try {
            secondSave = savedAnswer(attempt, "1", "0002", secondKey);
            second = factId(attempt, 2);
            assertEquals(
                    "0002",
                    scalar(
                            ASSESSMENT,
                            "SELECT answer->>'value' FROM attempt_answers WHERE attempt_id=? AND question_number=1",
                            attempt));
            assertEquals(
                    "PENDING",
                    scalar(ASSESSMENT, "SELECT state FROM assessment_monitoring_outbox WHERE event_id=?", second));
            awaitValue(
                    ASSESSMENT,
                    "SELECT (delivery_attempts>0)::text FROM assessment_monitoring_outbox WHERE event_id=?",
                    "true",
                    second);
        } finally {
            BROKER.getDockerClient()
                    .unpauseContainerCmd(BROKER.getContainerId())
                    .exec();
        }
        delivered(second, "APPLIED");
        assertWireFact(second, attempt, "2");
        assertEquals(secondSave, savedAnswer(attempt, "1", "0002", secondKey));
        assertEquals(second, factId(attempt, 2));
        assertEquals(
                "2",
                scalar(ASSESSMENT, "SELECT count(*) FROM assessment_monitoring_outbox WHERE attempt_id=?", attempt));
        assertEquals(
                "2",
                scalar(
                        PROCTORING,
                        "SELECT count(*) FROM monitoring_evidence WHERE session_id=? AND source='ASSESSMENT_AUTHORITY'",
                        session));

        UUID third;
        String finalized;
        BROKER.getDockerClient().pauseContainerCmd(BROKER.getContainerId()).exec();
        try {
            savedAnswer(attempt, "2", "0003", UUID.randomUUID().toString());
            third = factId(attempt, 3);
            expect(
                    200,
                    request(
                            "POST",
                            edge,
                            "/api/assessments/attempts/" + attempt + "/submit",
                            STUDENT,
                            "{\"expectedRevision\":\"3\"}",
                            true));
            assertEquals(
                    "ENDED",
                    expect(200, request("POST", edge, sessionPath(session) + "/reconcile", STUDENT, null, false))
                            .path("status")
                            .asString());
            finalized = assessmentFingerprint(attempt);
            assertEquals(
                    "PENDING",
                    scalar(ASSESSMENT, "SELECT state FROM assessment_monitoring_outbox WHERE event_id=?", third));
        } finally {
            BROKER.getDockerClient()
                    .unpauseContainerCmd(BROKER.getContainerId())
                    .exec();
        }
        delivered(third, "APPLIED");
        assertWireFact(third, attempt, "3");
        assertEquals(finalized, assessmentFingerprint(attempt));
        assertEquals(
                "ENDED",
                expect(200, request("GET", edge, sessionPath(session), STUDENT, null, false))
                        .path("status")
                        .asString());
        var history = expect(200, request("GET", edge, sessionPath(session) + "/events", TEACHER, null, false));
        assertSanitizedFact(history.toString());
        long endedOrdinal = -1, thirdOrdinal = -1;
        int facts = 0;
        for (JsonNode row : history.path("items")) {
            if ("SESSION_ENDED".equals(row.path("type").asString()))
                endedOrdinal = row.path("ordinal").asLong();
            if ("ASSESSMENT_AUTHORITY".equals(row.path("source").asString())) {
                facts++;
                assertEquals("ANSWER_PERSISTED", row.path("type").asString());
                assertEquals(STUDENT.toString(), row.path("actorId").asString());
                assertEquals(1, row.path("questionNumber").asInt());
                assertTrue(List.of("1", "2", "3")
                        .contains(row.path("assessmentRevision").asString()));
                if (third.toString().equals(row.path("id").asString()))
                    thirdOrdinal = row.path("ordinal").asLong();
            }
        }
        assertEquals(3, facts);
        assertTrue(
                endedOrdinal > 0 && thirdOrdinal > endedOrdinal,
                "Late committed metadata must append after END without reopening monitoring");
        assertEquals(
                "3",
                scalar(
                        PROCTORING,
                        "SELECT count(*) FROM assessment_monitoring_inbox WHERE attempt_id=? AND state='APPLIED'",
                        attempt));
        assertEquals("0", scalar(PROCTORING, "SELECT count(*) FROM assessment_monitoring_rejections"));
    }

    @AfterAll
    static void stopOnlyOwnedResources() throws Exception {
        if (edgeContext != null) edgeContext.close();
        if (cache != null) cache.close();
        if (redisClient != null) redisClient.shutdown();
        for (var process : OWNED) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            }
        }
        if (keyDirectory != null) {
            try (var files = Files.list(keyDirectory)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(keyDirectory);
        }
        if (evidence != null) {
            try (var files = Files.list(evidence)) {
                for (Path log : files.filter(
                                path -> path.getFileName().toString().endsWith(".log"))
                        .toList()) {
                    String content = Files.readString(log);
                    assertFalse(content.contains(SECRET), "Private SERVICE credential appeared in owned log");
                    assertFalse(content.contains(PASSWORD), "USER password appeared in owned log");
                    for (String brokerSecret :
                            List.of(BROKER_ADMIN_PASSWORD, BROKER_WRITER_PASSWORD, BROKER_READER_PASSWORD))
                        assertFalse(content.contains(brokerSecret), "Broker credential appeared in owned log");
                    assertFalse(content.contains("private synthetic explanation"));
                    assertFalse(content.contains("private synthetic answer"));
                    assertFalse(content.contains(LIVEKIT_SECRET), "SFU administrative secret appeared in owned log");
                    for (String token : TOKENS.values())
                        assertFalse(content.contains(token), "USER JWT appeared in owned log");
                    for (String token : MEDIA_TOKENS)
                        assertFalse(content.contains(token), "Media JWT appeared in owned log");
                }
            }
        }
        // Each Testcontainers resource belongs exclusively to this fixture; JUnit removes it.
    }
}
