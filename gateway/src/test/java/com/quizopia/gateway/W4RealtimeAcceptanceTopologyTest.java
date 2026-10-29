package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nimbusds.jose.jwk.RSAKey;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * W4-E realtime acceptance topology: real disposable Identity/Classroom/Assessment processes,
 * real RabbitMQ/Redis/PostgreSQL, TWO real Gateway instances (one in-JVM, one separate OS
 * process), real issued tickets/JWTs and the exact frozen browser wire protocol. The final
 * journey additionally drives a real Chromium browser against this same real stack.
 *
 * All credentials are generated, disposable, loopback-bound test fixtures. Successful local
 * acceptance is never production rollout authorization.
 */
@Testcontainers
@TestMethodOrder(OrderAnnotation.class)
class W4RealtimeAcceptanceTopologyTest {
    static final String VHOST = "w4real_" + UUID.randomUUID().toString().replace("-", "");
    static final String BROKER_ADMIN = UUID.randomUUID().toString();
    static final String BROKER_ADMIN_PASSWORD = UUID.randomUUID().toString();
    static final String PUBLISHER = "w4-assessment-publisher";
    static final String PUBLISHER_PASSWORD = UUID.randomUUID().toString();
    static final String CONSUMER = "w4-gateway-consumer";
    static final String CONSUMER_PASSWORD = UUID.randomUUID().toString();
    static final String EXCHANGE = "quizopia.realtime.invalidation.v1";
    static final String ORIGIN = "https://w4-acceptance.example";
    static final String SECRET = "w4-topology-service-fixture";
    static final String PASSWORD = "w4-topology-user-fixture";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    static final SecureRandom RANDOM = new SecureRandom();

    @Container
    static final PostgreSQLContainer<?> IDENTITY_DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> CLASSROOM_DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> ASSESSMENT_DB = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

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

    static final UUID STUDENT = UUID.randomUUID();
    static final UUID TEACHER = UUID.randomUUID();
    static final UUID OTHER_STUDENT = UUID.randomUUID();
    static final UUID CLASSROOM = UUID.randomUUID();
    static final List<String> SECRETS_OBSERVED = new CopyOnWriteArrayList<>();
    static final Map<String, String> TOKENS = new HashMap<>();
    static final Map<String, Process> NAMED = new LinkedHashMap<>();
    static RSAKey SIGNING_KEY;
    static Process frontendProcess;
    static Path repository, evidence, keyDirectory;
    static String identity, classroom, assessment, edgeA, edgeB;
    static int edgeAPort, edgeBPort, frontendPort;
    static ConfigurableApplicationContext edgeContext;
    static UUID publication;
    static UUID attemptA;

    @BeforeAll
    static void startOwnedTopology() throws Exception {
        repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(repository.resolve("AGENTS.md"))) repository = repository.getParent();
        evidence = repository.resolve("gateway/target/w4-acceptance-topology");
        Files.createDirectories(evidence);
        frontendPort = freePort();
        provisionBroker();
        keyDirectory = Files.createTempDirectory("w4-acceptance-keys");
        RSAKey signing = signingKey();
        SIGNING_KEY = signing;
        writeKey("private.pem", "PRIVATE KEY", signing.toRSAPrivateKey().getEncoded());
        writeKey("public.pem", "PUBLIC KEY", signing.toRSAPublicKey().getEncoded());
        for (String service : List.of("identity", "classroom", "assessment")) buildArtifact(service);
        buildArtifact("gateway");
        identity = "http://127.0.0.1:" + freePort();
        classroom = "http://127.0.0.1:" + freePort();
        assessment = "http://127.0.0.1:" + freePort();
        edgeAPort = freePort();
        edgeBPort = freePort();
        startService("identity");
        seedIdentity();
        try (var redisClient = io.lettuce.core.RedisClient.create(
                        "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
                var redis = redisClient.connect()) {
            for (UUID user : List.of(STUDENT, TEACHER, OTHER_STUDENT))
                redis.sync().set("quizopia:identity:user-authority:v1:" + user, projection(user));
        }
        for (UUID user : List.of(STUDENT, TEACHER)) {
            var login = send(
                    "POST",
                    identity + "/api/auth/login",
                    null,
                    "{\"identifier\":\"" + email(user) + "\",\"password\":\"" + PASSWORD + "\"}",
                    Map.of());
            assertEquals(200, login.statusCode(), "Real Identity login failed");
            String token = JSON.readTree(login.body()).path("accessToken").asString();
            TOKENS.put(String.valueOf(user), token);
            SECRETS_OBSERVED.add(token);
        }
        startService("classroom");
        try (var c = db(CLASSROOM_DB)) {
            execute(
                    c,
                    "INSERT INTO classrooms(id,owner_teacher_user_id,name,subject,grade) VALUES(?,?,'W4 class','Subject','Grade')",
                    CLASSROOM,
                    TEACHER);
            for (UUID student : List.of(STUDENT, OTHER_STUDENT))
                execute(
                        c,
                        "INSERT INTO classroom_memberships(id,classroom_id,user_id) VALUES(?,?,?)",
                        UUID.randomUUID(),
                        CLASSROOM,
                        student);
        }
        startService("assessment");
        publication = seedOpenClassPublication();
        // Gateway A starts realtime DEFAULT-OFF: the first journey proves the closed default
        // before any W4 edge flag exists anywhere in this topology.
        edgeContext = gatewayContext(edgeAPort, false);
        edgeA = "http://127.0.0.1:" + edgeAPort;
        // Start the real-browser frontend early: its first webpack compile then overlaps
        // the protocol journeys instead of stalling the final browser journey.
        frontendProcess = launchFrontend();
    }

    static Process launchFrontend() throws Exception {
        if (!Boolean.getBoolean("w4.browser")) return null; // protocol journeys only
        var frontendDirectory = repository.resolve("frontend");
        assumeTrue(
                Files.exists(frontendDirectory.resolve("node_modules/.bin/playwright" + (isWindows() ? ".cmd" : ""))),
                "Playwright toolchain unavailable; browser journey documented as not executed");
        List<String> command = isWindows()
                ? List.of(
                        "cmd.exe",
                        "/d",
                        "/s",
                        "/c",
                        "pnpm exec next dev --webpack --hostname 127.0.0.1 --port " + frontendPort)
                : List.of("bash", "-lc", "pnpm exec next dev --webpack --hostname 127.0.0.1 --port " + frontendPort);
        var builder = new ProcessBuilder(command)
                .directory(frontendDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(evidence.resolve("frontend.log").toFile());
        builder.environment().put("NEXT_PUBLIC_API_URL", "http://127.0.0.1:" + edgeAPort);
        builder.environment().put("NEXT_PUBLIC_REALTIME_ENABLED", "true");
        return builder.start();
    }

    @AfterAll
    static void stopOwnedTopology() throws Exception {
        if (edgeContext != null) edgeContext.close();
        if (frontendProcess != null) destroyTree(frontendProcess);
        for (Process process : NAMED.values()) destroyTree(process);
    }

    /** cmd.exe wrappers leave node/JVM children behind; destroy the whole tree. */
    static void destroyTree(Process process) throws Exception {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------ journeys

    /** E1: with no W4 flags supplied, the ticket route and upgrade fail closed. */
    @Test
    @Order(1)
    void e1_journey1_defaultOffFailsClosedAtTheEdge() throws Exception {
        var ticket = send(
                "POST",
                edgeA + "/api/realtime/tickets",
                TOKENS.get(String.valueOf(STUDENT)),
                "{}",
                Map.of("Origin", ORIGIN));
        assertEquals(503, ticket.statusCode(), "Default must stay OFF at the ticket boundary");
        assertEquals(
                "AUTHORITY_UNAVAILABLE",
                JSON.readTree(ticket.body()).path("code").asString());
        assertEquals(503, upgradeStatus(edgeAPort, ORIGIN), "Default must stay OFF at the upgrade boundary");
    }

    /** E1: real ticket -> upgrade -> AUTH -> SUBSCRIBE with real downstream authorization. */
    @Test
    @Order(2)
    void e1_journey2_realTicketAuthAndSubscription() throws Exception {
        restartEdgeAWithRealtime();
        attemptA = startAttempt(STUDENT);
        String ticket = ticket(STUDENT, ORIGIN);
        try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
            assertTrue(socket.next(Duration.ofSeconds(10)).contains("\"type\":\"WELCOME\""));
            socket.send(authFrame(ticket, STUDENT));
            String authenticated = socket.next(Duration.ofSeconds(10));
            assertTrue(authenticated.contains("\"type\":\"AUTHENTICATED\""), authenticated);
            socket.send(subscribeFrame("ATTEMPT_OWNER", attemptA));
            String subscribed = socket.next(Duration.ofSeconds(10));
            assertTrue(subscribed.contains("\"type\":\"SUBSCRIBED\""), subscribed);
            assertEquals(
                    attemptA.toString(),
                    JSON.readTree(subscribed).path("resource").path("id").asString());
        }
    }

    /** E1: the one-use ticket and its exact JWT binding are enforced by the real edge. */
    @Test
    @Order(3)
    void e1_journey3_ticketOneUseAndExactJwtBinding() throws Exception {
        String ticket = ticket(STUDENT, ORIGIN);
        try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
            socket.next(Duration.ofSeconds(10));
            socket.send(authFrame(ticket, STUDENT));
            socket.next(Duration.ofSeconds(10));
        }
        // Same ticket replayed with a fresh connection: refused even with the same JWT.
        try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
            socket.next(Duration.ofSeconds(10));
            socket.send(authFrame(ticket, STUDENT));
            assertEquals(4401, socket.closeSeen(Duration.ofSeconds(10)), "Replayed ticket must be refused");
        }
        // A brand-new ticket bound to student A cannot authenticate with student B's JWT.
        String fresh = ticket(STUDENT, ORIGIN);
        String frame = "{\"protocolVersion\":1,\"type\":\"AUTH\",\"ticket\":\""
                + fresh
                + "\",\"accessToken\":\""
                + TOKENS.get(String.valueOf(TEACHER))
                + "\"}";
        try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
            socket.next(Duration.ofSeconds(10));
            socket.send(frame);
            assertEquals(4401, socket.closeSeen(Duration.ofSeconds(10)), "Ticket/JWT mismatch must be refused");
        }
    }

    /** E1: Origin allowlist enforced at ticket issuance and at the upgrade. */
    @Test
    @Order(4)
    void e1_journey4_originRestrictions() throws Exception {
        var ticket = send(
                "POST",
                edgeA + "/api/realtime/tickets",
                TOKENS.get(String.valueOf(STUDENT)),
                "{}",
                Map.of("Origin", "https://attacker.example"));
        assertEquals(403, ticket.statusCode(), "Unapproved Origin must not obtain a ticket");
        assertEquals(403, upgradeStatus(edgeAPort, "https://attacker.example"), "Unapproved Origin must not upgrade");
    }

    /** E1: wrong role denial and revoked-session fail-closed through the real chain. */
    @Test
    @Order(5)
    void e1_journey5_wrongRoleAndRevokedSession() throws Exception {
        // STUDENT cannot subscribe PUBLICATION_TEACHER: downstream denies, edge relays.
        String ticket = ticket(STUDENT, ORIGIN);
        try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
            socket.next(Duration.ofSeconds(10));
            socket.send(authFrame(ticket, STUDENT));
            socket.next(Duration.ofSeconds(10));
            socket.send(subscribeFrame("PUBLICATION_TEACHER", publication));
            String error = socket.next(Duration.ofSeconds(10));
            assertTrue(error.contains("\"type\":\"ERROR\""), error);
            assertTrue(error.contains("\"code\":\"NOT_ACCESSIBLE\""), error);
            assertTrue(error.contains("\"requestId\""), error);
        }
        // A session issued BEFORE the revocation cutoff must fail every later admission.
        var login = send(
                "POST",
                identity + "/api/auth/login",
                null,
                "{\"identifier\":\"" + email(OTHER_STUDENT) + "\",\"password\":\"" + PASSWORD + "\"}",
                Map.of());
        assertEquals(200, login.statusCode());
        String revoked = JSON.readTree(login.body()).path("accessToken").asString();
        SECRETS_OBSERVED.add(revoked);
        try (var c = db(IDENTITY_DB)) {
            execute(
                    c,
                    "INSERT INTO user_access_revocation(user_id,revoked_before) VALUES (?,clock_timestamp())",
                    OTHER_STUDENT);
        }
        java.sql.Timestamp cutoff;
        try (var c = db(IDENTITY_DB);
                var statement =
                        c.prepareStatement("SELECT revoked_before FROM user_access_revocation WHERE user_id=?")) {
            statement.setObject(1, OTHER_STUDENT);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                cutoff = rows.getTimestamp(1);
            }
        }
        // Redis and primary must agree on the exact cutoff for the decisive outcome.
        try (var redisClient = io.lettuce.core.RedisClient.create(
                        "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
                var redis = redisClient.connect()) {
            Instant instant = cutoff.toInstant();
            redis.sync()
                    .set(
                            "quizopia:identity:user-authority:v1:" + OTHER_STUDENT,
                            "1|" + OTHER_STUDENT + "|PRESENT|VALUE:QUNUSVZF|VERIFIED|STUDENT|AT:"
                                    + instant.getEpochSecond() + ":" + instant.getNano());
        }
        var refused = send("POST", edgeA + "/api/realtime/tickets", revoked, "{}", Map.of("Origin", ORIGIN));
        assertEquals(401, refused.statusCode(), "Revoked session must fail decisively closed");
    }

    /** E1: Identity or Assessment outage fails closed without crashing the connection. */
    @Test
    @Order(6)
    void e1_journey6_failClosedOnDependencyOutage() throws Exception {
        // Assessment outage: subscription authorization degrades to AUTHORITY_UNAVAILABLE.
        stopService("assessment");
        try {
            String ticket = ticket(STUDENT, ORIGIN);
            try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
                socket.next(Duration.ofSeconds(10));
                socket.send(authFrame(ticket, STUDENT));
                socket.next(Duration.ofSeconds(10));
                socket.send(subscribeFrame("ATTEMPT_OWNER", attemptA));
                String error = socket.next(Duration.ofSeconds(15));
                assertTrue(error.contains("\"code\":\"AUTHORITY_UNAVAILABLE\""), error);
                assertNull(socket.quiet(Duration.ofSeconds(1)), "Connection must stay open per-request");
            }
            // Identity outage: admission cannot be verified, so ticket issuance fails closed.
            stopService("identity");
            var refused = send(
                    "POST",
                    edgeA + "/api/realtime/tickets",
                    TOKENS.get(String.valueOf(STUDENT)),
                    "{}",
                    Map.of("Origin", ORIGIN));
            assertTrue(
                    refused.statusCode() == 401 || refused.statusCode() == 403 || refused.statusCode() == 503,
                    "Identity outage must fail closed, got " + refused.statusCode());
        } finally {
            restartService("identity");
            restartService("assessment");
        }
    }

    /** E2: committed attempt submit propagates to the authorized subscriber, REST stays authoritative. */
    @Test
    @Order(7)
    void e2_journey7_attemptLifecyclePropagation() throws Exception {
        publication = seedOpenClassPublication();
        UUID attempt = startAttempt(STUDENT);
        // The attempt-creation outbox event must be dispatched before subscribing so it
        // cannot interleave with the synthetic probe below.
        awaitTrue(
                () -> outboxCountFor(attempt) >= 1 && outboxCount("PENDING") == 0,
                "creation outbox event dispatched before subscribing");
        String ticket = ticket(STUDENT, ORIGIN);
        try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
            socket.next(Duration.ofSeconds(10));
            socket.send(authFrame(ticket, STUDENT));
            socket.next(Duration.ofSeconds(10));
            socket.send(subscribeFrame("ATTEMPT_OWNER", attempt));
            String subscribed = socket.next(Duration.ofSeconds(10));
            assertTrue(subscribed.contains("\"type\":\"SUBSCRIBED\""), subscribed);
            String subscriptionId = subscriptionIdOf(subscribed);

            // While the attempt is still eligible, an invalidation hint revalidates and
            // delivers RESOURCE_INVALIDATED (frozen frame shape, opaque subscription id).
            publishInvalidation(UUID.randomUUID(), "ATTEMPT_INVALIDATED", "ATTEMPT", attempt);
            String invalidated = socket.next(Duration.ofSeconds(30));
            assertTrue(invalidated.contains("\"type\":\"RESOURCE_INVALIDATED\""), invalidated);
            assertTrue(invalidated.contains("\"subscriptionId\":\"" + subscriptionId + "\""), invalidated);
            assertTrue(invalidated.contains("\"reason\":\"CHANGED\""), invalidated);
            assertTrue(
                    JSON.readTree(invalidated)
                            .path("resource")
                            .path("id")
                            .asString()
                            .equals(attempt.toString()),
                    invalidated);
            // The authoritative state is what REST returns, not what the hint carries.
            var view = edgeRequest("GET", "/api/assessments/attempts/" + attempt, STUDENT, null, false);
            assertEquals(200, view.statusCode());
            assertEquals("ACTIVE", JSON.readTree(view.body()).path("state").asString());

            // Real committed transition: the student submits through the public REST API.
            // Revalidation now finds a terminal attempt, so the same subscription is revoked
            // safely instead of hinting at an ineligible resource.
            var submit = edgeRequest(
                    "POST",
                    "/api/assessments/attempts/" + attempt + "/submit",
                    STUDENT,
                    "{\"expectedRevision\":\"0\",\"answers\":[{\"questionNumber\":1,\"answer\":{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}}]}",
                    true);
            assertTrue(submit.statusCode() == 200, "Real submit failed: " + submit.statusCode() + " " + submit.body());
            String revoked = socket.next(Duration.ofSeconds(30));
            assertTrue(revoked.contains("\"type\":\"SUBSCRIPTION_REVOKED\""), revoked);
            assertTrue(revoked.contains("\"code\":\"NOT_ACCESSIBLE\""), revoked);
            var terminal = edgeRequest("GET", "/api/assessments/attempts/" + attempt, STUDENT, null, false);
            assertEquals(200, terminal.statusCode());
            assertEquals(
                    "SUBMITTED", JSON.readTree(terminal.body()).path("state").asString());
        }
        awaitTrue(() -> outboxCountFor(attempt) >= 2, "attempt creation+submit outbox rows");
    }

    /** E2: publication extension and close propagate to the authorized TEACHER subscriber. */
    @Test
    @Order(8)
    void e2_journey8_publicationTeacherPropagation() throws Exception {
        publication = seedOpenClassPublication();
        attemptA = startAttempt(STUDENT);
        String ticket = ticket(TEACHER, ORIGIN);
        try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
            socket.next(Duration.ofSeconds(10));
            socket.send(authFrame(ticket, TEACHER));
            socket.next(Duration.ofSeconds(10));
            socket.send(subscribeFrame("PUBLICATION_TEACHER", publication));
            String subscribed = socket.next(Duration.ofSeconds(10));
            assertTrue(subscribed.contains("\"type\":\"SUBSCRIBED\""), subscribed);

            // Meaningful schedule extension through the real teacher REST route.
            Instant end = Instant.now().plusSeconds(10800).truncatedTo(ChronoUnit.MICROS);
            var extend = edgeRequest(
                    "PATCH",
                    "/api/assessments/publications/" + publication + "/schedule",
                    TEACHER,
                    "{\"expectedRevision\":1,\"endAt\":\"" + end + "\",\"durationMinutes\":30}",
                    false);
            assertTrue(
                    extend.statusCode() == 200,
                    "Real schedule extension failed: " + extend.statusCode() + " " + extend.body());
            String invalidated = socket.next(Duration.ofSeconds(30));
            assertTrue(invalidated.contains("\"type\":\"RESOURCE_INVALIDATED\""), invalidated);
            assertTrue(invalidated.contains("\"id\":\"" + publication + "\""), invalidated);
            // Authoritative REST state, not the AMQP payload, is what the view reconciles with.
            var view = edgeRequest("GET", "/api/assessments/publications/" + publication, TEACHER, null, false);
            assertEquals(200, view.statusCode());
            assertEquals(2, JSON.readTree(view.body()).path("revision").asInt());

            // No ACTIVE attempt may remain when the teacher CLOSEs: finish attemptA first.
            var finish = edgeRequest(
                    "POST",
                    "/api/assessments/attempts/" + attemptA + "/submit",
                    STUDENT,
                    "{\"expectedRevision\":\"0\",\"answers\":[{\"questionNumber\":1,\"answer\":{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}}]}",
                    true);
            assertTrue(
                    finish.statusCode() == 200 || finish.statusCode() == 409,
                    "attemptA wind-down failed: " + finish.statusCode());

            // Teacher CLOSE (no active attempts remain) propagates and then revokes.
            var close = edgeRequest(
                    "POST",
                    "/api/assessments/publications/" + publication + "/close",
                    TEACHER,
                    "{\"expectedRevision\":2}",
                    true);
            assertTrue(close.statusCode() == 200, "Real teacher close failed: " + close.body());
            // Revalidation of a CLOSED publication denies eligibility, so the teacher's
            // subscription is revoked safely; the authoritative CLOSE is REST-visible.
            String revoked = socket.next(Duration.ofSeconds(30));
            assertTrue(revoked.contains("\"type\":\"SUBSCRIPTION_REVOKED\""), revoked);
            assertTrue(revoked.contains("\"code\":\"NOT_ACCESSIBLE\""), revoked);
            var closedView = edgeRequest("GET", "/api/assessments/publications/" + publication, TEACHER, null, false);
            assertEquals(200, closedView.statusCode());
            assertEquals(
                    "CLOSED", JSON.readTree(closedView.body()).path("state").asString());
        }
        // Reopen a fresh OPEN publication for the remaining journeys.
        publication = seedOpenClassPublication();
    }

    /** E2: rolled-back and no-op transitions never produce events; foreign resources stay isolated. */
    @Test
    @Order(9)
    void e2_journey9_rollbackExclusionAndResourceIsolation() throws Exception {
        publication = seedOpenClassPublication();
        long before = outboxCount("PENDING") + outboxCount("PUBLISHED");
        try (var c = db(ASSESSMENT_DB)) {
            c.setAutoCommit(false);
            execute(
                    c,
                    "UPDATE attempts SET state='ABANDONED',revision=revision+1,terminal_reason='STUDENT_ABANDON',finalized_at=clock_timestamp() WHERE id=? AND state='ACTIVE'",
                    attemptA);
            c.rollback(); // The business transition never commits: no event may exist.
        }
        execute(db(ASSESSMENT_DB), "UPDATE publications SET revision=revision+1 WHERE id=?", publication);
        assertEquals(before, outboxCount("PENDING") + outboxCount("PUBLISHED"), "Rollback/no-op must not emit");

        // Isolation: student A subscribed to attempt X receives nothing for student B's attempt.
        UUID ownAttempt = startAttempt(STUDENT);
        awaitTrue(
                () -> outboxCountFor(ownAttempt) >= 1 && outboxCount("PENDING") == 0,
                "own attempt creation dispatched before the isolation probe");
        UUID foreign = startAttempt(OTHER_STUDENT);
        String ticket = ticket(STUDENT, ORIGIN);
        try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
            socket.next(Duration.ofSeconds(10));
            socket.send(authFrame(ticket, STUDENT));
            socket.next(Duration.ofSeconds(10));
            socket.send(subscribeFrame("ATTEMPT_OWNER", ownAttempt));
            assertTrue(
                    socket.next(Duration.ofSeconds(10)).contains("\"type\":\"SUBSCRIBED\""),
                    "own attempt subscription must authorize");
            var submit = send(
                    "POST",
                    edgeA + "/api/assessments/attempts/" + foreign + "/submit",
                    TOKENS.get(String.valueOf(OTHER_STUDENT)),
                    "{\"expectedRevision\":\"0\",\"answers\":[{\"questionNumber\":1,\"answer\":{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}}]}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertTrue(submit.statusCode() == 200, "Foreign submit failed");
            assertNull(socket.quiet(Duration.ofSeconds(8)), "Foreign-resource transitions must stay isolated");
        }
    }

    /** E3: two real Gateway instances each fan out their own copy of every hint. */
    @Test
    @Order(10)
    void e3_journey10_twoGatewayInstancesFanOut() throws Exception {
        publication = seedOpenClassPublication();
        startGatewayProcess();
        UUID attempt = startAttempt(STUDENT);
        awaitTrue(
                () -> outboxCountFor(attempt) >= 1 && outboxCount("PENDING") == 0,
                "creation outbox event fully dispatched before subscribing");
        String ticketA = ticket(STUDENT, ORIGIN);
        // Gateway B is a separate process with its own queue: ticket route works through it too.
        var ticketBResponse = send(
                "POST",
                edgeB + "/api/realtime/tickets",
                TOKENS.get(String.valueOf(STUDENT)),
                "{}",
                Map.of("Origin", ORIGIN));
        if (ticketBResponse.statusCode() == 401) {
            freshToken(STUDENT);
            ticketA = ticket(STUDENT, ORIGIN);
            ticketBResponse = send(
                    "POST",
                    edgeB + "/api/realtime/tickets",
                    TOKENS.get(String.valueOf(STUDENT)),
                    "{}",
                    Map.of("Origin", ORIGIN));
        }
        assertEquals(201, ticketBResponse.statusCode(), "Gateway B ticket issuance failed");
        String ticketB = JSON.readTree(ticketBResponse.body()).path("ticket").asString();
        SECRETS_OBSERVED.add(ticketB);
        try (var socketA = new BrowserSocket(edgeA, ORIGIN);
                var socketB = new BrowserSocket(edgeB, ORIGIN)) {
            socketA.next(Duration.ofSeconds(10));
            socketB.next(Duration.ofSeconds(10));
            socketA.send(authFrame(ticketA, STUDENT));
            socketB.send(authFrame(ticketB, STUDENT));
            socketA.next(Duration.ofSeconds(10));
            socketB.next(Duration.ofSeconds(10));
            socketA.send(subscribeFrame("ATTEMPT_OWNER", attempt));
            socketB.send(subscribeFrame("ATTEMPT_OWNER", attempt));
            assertTrue(socketA.next(Duration.ofSeconds(10)).contains("\"type\":\"SUBSCRIBED\""));
            assertTrue(socketB.next(Duration.ofSeconds(10)).contains("\"type\":\"SUBSCRIBED\""));

            // Both instances receive their own copy of the same committed transition.
            var submit = edgeRequest(
                    "POST",
                    "/api/assessments/attempts/" + attempt + "/submit",
                    STUDENT,
                    "{\"expectedRevision\":\"0\",\"answers\":[{\"questionNumber\":1,\"answer\":{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}}]}",
                    true);
            assertTrue(submit.statusCode() == 200, "Real submit failed: " + submit.body());
            String outcomeA = socketA.next(Duration.ofSeconds(30));
            String outcomeB = socketB.next(Duration.ofSeconds(30));
            // The attempt is terminal after submit: both instances revoke safely.
            assertTrue(outcomeA.contains("\"type\":\"SUBSCRIPTION_REVOKED\""), outcomeA);
            assertTrue(outcomeB.contains("\"type\":\"SUBSCRIPTION_REVOKED\""), outcomeB);
        }
    }

    /** E3: duplicate and out-of-order hints are tolerated; each instance keeps working. */
    @Test
    @Order(11)
    void e3_journey11_duplicateAndOutOfOrderHints() throws Exception {
        publication = seedOpenClassPublication();
        UUID attempt = startAttempt(STUDENT);
        // The attempt-creation outbox event must be fully dispatched before subscribing,
        // otherwise it races the synthetic probes below.
        awaitTrue(
                () -> outboxCountFor(attempt) >= 1 && outboxCount("PENDING") == 0,
                "creation outbox event dispatched before subscribing");
        String ticket = ticket(STUDENT, ORIGIN);
        try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
            socket.next(Duration.ofSeconds(10));
            socket.send(authFrame(ticket, STUDENT));
            socket.next(Duration.ofSeconds(10));
            socket.send(subscribeFrame("ATTEMPT_OWNER", attempt));
            assertTrue(socket.next(Duration.ofSeconds(10)).contains("\"type\":\"SUBSCRIBED\""));

            UUID eventId = UUID.randomUUID();
            publishInvalidation(eventId, "ATTEMPT_INVALIDATED", "ATTEMPT", attempt);
            String first = socket.next(Duration.ofSeconds(30));
            assertTrue(first.contains("\"type\":\"RESOURCE_INVALIDATED\""), first);
            assertEquals(
                    eventId.toString(), JSON.readTree(first).path("eventId").asString());
            publishInvalidation(eventId, "ATTEMPT_INVALIDATED", "ATTEMPT", attempt);
            publishInvalidation(UUID.randomUUID(), "ATTEMPT_INVALIDATED", "ATTEMPT", attempt);
            String second = socket.next(Duration.ofSeconds(30));
            assertTrue(second.contains("\"type\":\"RESOURCE_INVALIDATED\""), second);
            assertNotEquals(
                    eventId.toString(), JSON.readTree(second).path("eventId").asString());
            assertNull(socket.quiet(Duration.ofSeconds(5)), "Duplicate eventId must be deduplicated");
        }
    }

    /** E3: broker outage queues outbox work; recovery drains it to live subscribers. */
    @Test
    @Order(12)
    void e3_journey12_brokerOutageAndRecovery() throws Exception {
        var consumer =
                edgeContext.getBean(com.quizopia.gateway.realtime.invalidation.RealtimeInvalidationConsumer.class);
        awaitTrue(consumer::isConnected, "Gateway consumer connected before the outage");
        publication = seedOpenClassPublication();
        UUID attempt = startAttempt(STUDENT);
        awaitTrue(
                () -> outboxCountFor(attempt) >= 1 && outboxCount("PENDING") == 0,
                "creation outbox event dispatched before the outage");
        String ticket = ticket(STUDENT, ORIGIN);
        try (var socket = new BrowserSocket(edgeA, ORIGIN)) {
            socket.next(Duration.ofSeconds(10));
            socket.send(authFrame(ticket, STUDENT));
            socket.next(Duration.ofSeconds(10));
            socket.send(subscribeFrame("ATTEMPT_OWNER", attempt));
            assertTrue(socket.next(Duration.ofSeconds(10)).contains("\"type\":\"SUBSCRIBED\""));

            assertEquals(0, BROKER.execInContainer("rabbitmqctl", "stop_app").getExitCode());
            awaitTrue(() -> !consumer.isConnected(), "Gateway consumer observes the broker outage");
            // Business REST must survive the broker outage, and a real committed transition
            // (teacher schedule extension) must be retained durably by the outbox.
            var extension = edgeRequest(
                    "PATCH",
                    "/api/assessments/publications/" + publication + "/schedule",
                    TEACHER,
                    "{\"expectedRevision\":1,\"endAt\":\""
                            + Instant.now().plusSeconds(14400).truncatedTo(ChronoUnit.MICROS)
                            + "\",\"durationMinutes\":30}",
                    false);
            assertTrue(
                    extension.statusCode() == 200,
                    "Business REST must survive broker outage: " + extension.statusCode());
            awaitTrue(() -> outboxCount("PENDING") >= 1, "outbox retains work during outage");
            assertNull(socket.quiet(Duration.ofSeconds(3)), "No hint may arrive while the broker is down");

            assertEquals(0, BROKER.execInContainer("rabbitmqctl", "start_app").getExitCode());
            // Publisher recovery and consumer recovery are independent. An empty outbox
            // does not prove this Gateway has recreated its exclusive transient queue.
            // Hints published before that queue exists may legitimately be lost (ADR-016).
            awaitTrue(() -> outboxCount("PENDING") == 0, "outbox drains after broker recovery");
            Files.writeString(
                    evidence.resolve("broker-recovery.txt"),
                    "Gateway consumer connected when Assessment outbox drained: " + consumer.isConnected() + "\n");
            // isConnected becomes true only after queue declaration, both bindings and
            // basicConsume complete. Do not restart the consumer or bypass its real retry.
            awaitTrue(consumer::isConnected, "Gateway consumer recreates its queue after broker recovery");
            Files.writeString(
                    evidence.resolve("broker-recovery.txt"),
                    "Gateway consumer connected before fresh probe: " + consumer.isConnected() + "\n",
                    java.nio.file.StandardOpenOption.APPEND);
            publishInvalidation(UUID.randomUUID(), "ATTEMPT_INVALIDATED", "ATTEMPT", attempt);
            String invalidated;
            try {
                invalidated = socket.next(Duration.ofSeconds(60));
            } finally {
                Files.writeString(
                        evidence.resolve("broker-recovery.txt"),
                        "Gateway consumer connected after frame wait: " + consumer.isConnected() + "\n",
                        java.nio.file.StandardOpenOption.APPEND);
            }
            assertTrue(invalidated.contains("\"type\":\"RESOURCE_INVALIDATED\""), invalidated);
            assertTrue(invalidated.contains("\"id\":\"" + attempt + "\""), invalidated);
        }
    }

    /** E3: a killed Gateway instance's sockets die; its restarted twin resumes delivery. */
    @Test
    @Order(13)
    void e3_journey13_gatewayDisconnectAndReconnect() throws Exception {
        publication = seedOpenClassPublication();
        UUID attempt = startAttempt(STUDENT);
        awaitTrue(
                () -> outboxCountFor(attempt) >= 1 && outboxCount("PENDING") == 0,
                "creation outbox event dispatched before the disconnect probe");
        try {
            var ticketBResponse = send(
                    "POST",
                    edgeB + "/api/realtime/tickets",
                    TOKENS.get(String.valueOf(STUDENT)),
                    "{}",
                    Map.of("Origin", ORIGIN));
            assertEquals(201, ticketBResponse.statusCode());
            String ticketB =
                    JSON.readTree(ticketBResponse.body()).path("ticket").asString();
            try (var socket = new BrowserSocket(edgeB, ORIGIN)) {
                socket.next(Duration.ofSeconds(10));
                socket.send(authFrame(ticketB, STUDENT));
                socket.next(Duration.ofSeconds(10));
                socket.send(subscribeFrame("ATTEMPT_OWNER", attempt));
                assertTrue(socket.next(Duration.ofSeconds(10)).contains("\"type\":\"SUBSCRIBED\""));
                stopService("gateway-b");
                // The killed instance's HTTP surface dies with it, proving real process loss.
                awaitTrue(() -> httpDead(edgeB), "killed instance HTTP surface must die");
                assertNotNull(socket.closeSeen(Duration.ofSeconds(25)), "Instance loss must close its sockets");
            }
            startGatewayProcess();
            // After the restart the fresh instance delivers again with a fresh ticket; a
            // synthetic hint for the still-active attempt proves end-to-end recovery.
            var fresh = send(
                    "POST",
                    edgeB + "/api/realtime/tickets",
                    TOKENS.get(String.valueOf(STUDENT)),
                    "{}",
                    Map.of("Origin", ORIGIN));
            assertEquals(201, fresh.statusCode());
            String ticket = JSON.readTree(fresh.body()).path("ticket").asString();
            try (var socket = new BrowserSocket(edgeB, ORIGIN)) {
                socket.next(Duration.ofSeconds(10));
                socket.send(authFrame(ticket, STUDENT));
                socket.next(Duration.ofSeconds(10));
                socket.send(subscribeFrame("ATTEMPT_OWNER", attempt));
                assertTrue(socket.next(Duration.ofSeconds(10)).contains("\"type\":\"SUBSCRIBED\""));
                publishInvalidation(UUID.randomUUID(), "ATTEMPT_INVALIDATED", "ATTEMPT", attempt);
                String invalidated = socket.next(Duration.ofSeconds(30));
                assertTrue(invalidated.contains("\"type\":\"RESOURCE_INVALIDATED\""), invalidated);
            }
        } finally {
            if (NAMED.get("gateway-b") == null || !NAMED.get("gateway-b").isAlive()) startGatewayProcess();
        }
    }

    /** E4: broker ACL separation, no secret disclosure in logs, operable metrics. */
    @Test
    @Order(14)
    void e4_journey14_aclSecrecyAndOperability() throws Exception {
        if (NAMED.get("gateway-b") == null || !NAMED.get("gateway-b").isAlive()) startGatewayProcess();
        // ACL separation (least privilege re-verified on the live broker).
        var publisher = operator("GET", "permissions/" + VHOST + "/" + PUBLISHER, null, 200);
        assertEquals("^$", publisher.path("read").asString());
        assertEquals("^" + regex(EXCHANGE) + "$", publisher.path("write").asString());
        var consumer = operator("GET", "permissions/" + VHOST + "/" + CONSUMER, null, 200);
        assertTrue(consumer.path("read").asString().contains(regex(EXCHANGE)));
        assertTrue(consumer.path("configure").asString().startsWith("^quizopia"));
        // The consumer identity must not be able to publish invalidations.
        assertTrue(
                cannotPublishAs(CONSUMER, CONSUMER_PASSWORD),
                "Gateway consumer identity must not publish to the exchange");
        // The publisher identity must not be able to declare or consume queues.
        assertTrue(
                cannotConsumeAs(PUBLISHER, PUBLISHER_PASSWORD),
                "Assessment publisher identity must not consume queues");

        // Secret hygiene: no issued ticket or JWT may appear in any process log.
        for (String name : List.of("identity", "classroom", "assessment", "gateway-b")) {
            String log = Files.readString(evidence.resolve(name + ".log"));
            for (String secret : SECRETS_OBSERVED) assertFalse(log.contains(secret), name + " log leaked a secret");
            assertFalse(log.contains(PUBLISHER_PASSWORD), name + " log leaked the broker password");
            assertFalse(log.contains(CONSUMER_PASSWORD), name + " log leaked the broker password");
        }

        // Operability: invalidation and outbox telemetry on the live separate-instance
        // gateway (the in-JVM instance shares the same exposure configuration).
        // Assessment's prometheus surface is security-gated behind authenticated business
        // routes in the current configuration; that exposure is a production operator
        // decision, so outbox telemetry is verified through the durable outbox state.
        var gatewayMetrics =
                send("GET", edgeB + "/actuator/prometheus", TOKENS.get(String.valueOf(STUDENT)), null, Map.of());
        assertEquals(200, gatewayMetrics.statusCode(), "gateway metrics endpoint must serve an authorized caller");
        assertTrue(gatewayMetrics.body().contains("gateway_realtime_invalidation"));
        assertTrue(gatewayMetrics.body().contains("gateway_realtime_subscription"));
        var assessmentHealth = send("GET", assessment + "/actuator/health", null, null, Map.of());
        assertEquals(200, assessmentHealth.statusCode());
        awaitTrue(() -> outboxCount("PUBLISHED") > 0, "outbox marks real dispatches as PUBLISHED for lag visibility");
        assertEquals(0, outboxCount("QUARANTINED"), "No quarantined events during acceptance");
    }
    /** E1/E2/E3 in a real Chromium browser against this same real stack (Playwright). */
    @Test
    @Order(15)
    void e_browserJourney_realChromiumOverTheRealStack() throws Exception {
        assumeTrue(
                Boolean.getBoolean("w4.browser"),
                "Real-browser journey runs on demand: -Dw4.browser=true (needs the frontend toolchain)");
        assumeTrue(frontendProcess != null && frontendProcess.isAlive(), "Frontend dev server unavailable");
        try {
            String frontendUrl = "http://127.0.0.1:" + frontendPort;
            awaitHttpBrowser(frontendUrl, frontendProcess);
            var frontendDirectory = repository.resolve("frontend");
            List<String> runCommand = isWindows()
                    ? List.of(
                            "cmd.exe",
                            "/d",
                            "/s",
                            "/c",
                            "pnpm exec playwright test --config playwright.w4-real.config.ts")
                    : List.of("bash", "-lc", "pnpm exec playwright test --config playwright.w4-real.config.ts");
            var runBuilder = new ProcessBuilder(runCommand)
                    .directory(frontendDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(evidence.resolve("playwright.log").toFile());
            var environment = runBuilder.environment();
            environment.put("W4_REAL_GATEWAY_URL", edgeA);
            environment.put("W4_REAL_FRONTEND_URL", frontendUrl);
            environment.put("W4_REAL_TEACHER_EMAIL", email(TEACHER));
            environment.put("W4_REAL_STUDENT_EMAIL", email(STUDENT));
            environment.put("W4_REAL_USER_PASSWORD", PASSWORD);
            environment.put("W4_REAL_PUBLICATION_ID", publication.toString());
            var run = runBuilder.start();
            assertTrue(run.waitFor(15, TimeUnit.MINUTES), "Playwright browser journey timed out");
            assertEquals(0, run.exitValue(), "Real browser journey failed; see playwright.log");
        } finally {
            if (frontendProcess != null) destroyTree(frontendProcess);
        }
    }

    static void awaitHttpBrowser(String url, Process process) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(420).toNanos();
        Exception last = null;
        while (System.nanoTime() < until) {
            if (!process.isAlive() && !process.waitFor(5, TimeUnit.SECONDS))
                fail("Frontend dev server exited; see frontend.log");
            try {
                // HTTP/1.1 only: the dev server hangs on cleartext HTTP/2 upgrade probes.
                // Probing /login also compiles the page the browser journeys start on.
                var request = java.net.http.HttpRequest.newBuilder(URI.create(url + "/login"))
                        .timeout(Duration.ofSeconds(60))
                        .version(java.net.http.HttpClient.Version.HTTP_1_1);
                var response = HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) return;
            } catch (Exception failure) {
                last = failure;
            }
            Thread.sleep(500);
        }
        fail("Frontend dev server not ready; last probe: " + (last == null ? "non-200" : last.toString()));
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    static boolean cannotPublishAs(String user, String password) throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(BROKER.getHost());
        factory.setPort(BROKER.getMappedPort(5672));
        factory.setVirtualHost(VHOST);
        factory.setUsername(user);
        factory.setPassword(password);
        try (var connection = factory.newConnection("w4-acl-probe");
                var channel = connection.createChannel()) {
            channel.basicPublish(EXCHANGE, "attempt.invalidated.v1", null, "x".getBytes(StandardCharsets.UTF_8));
            return false; // Publishing unexpectedly succeeded: ACL too permissive.
        } catch (Exception expected) {
            return true;
        }
    }

    static boolean cannotConsumeAs(String user, String password) throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(BROKER.getHost());
        factory.setPort(BROKER.getMappedPort(5672));
        factory.setVirtualHost(VHOST);
        factory.setUsername(user);
        factory.setPassword(password);
        try (var connection = factory.newConnection("w4-acl-probe");
                var channel = connection.createChannel()) {
            channel.queueDeclare("w4-acl-probe-" + UUID.randomUUID(), false, true, true, null);
            return false; // Queue declaration unexpectedly succeeded: ACL too permissive.
        } catch (Exception expected) {
            return true;
        }
    }

    // ------------------------------------------------------------------ harness

    static RSAKey signingKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("w4-acceptance-signing")
                    .build();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void writeKey(String name, String kind, byte[] encoded) throws Exception {
        Files.writeString(
                keyDirectory.resolve(name),
                "-----BEGIN " + kind + "-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                        + "\n-----END " + kind + "-----\n");
    }

    static int freePort() throws Exception {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", 0), 1);
            return socket.getLocalPort();
        }
    }

    static String email(UUID user) {
        return user + "@w4.test";
    }

    static String roles(UUID user) {
        return user.equals(TEACHER) ? "STUDENT,TEACHER" : "STUDENT";
    }

    static String projection(UUID user) {
        return "1|" + user + "|PRESENT|VALUE:QUNUSVZF|VERIFIED|" + roles(user) + "|NONE";
    }

    static java.sql.Connection db(PostgreSQLContainer<?> database) throws Exception {
        return java.sql.DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    static void execute(java.sql.Connection connection, String sql, Object... arguments) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) statement.setObject(i + 1, arguments[i]);
            statement.executeUpdate();
        }
    }

    static void seedIdentity() throws Exception {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        try (var c = db(IDENTITY_DB)) {
            for (UUID user : List.of(STUDENT, TEACHER, OTHER_STUDENT)) {
                execute(
                        c,
                        "INSERT INTO user_account(id,email,username,account_status,email_verified_at,created_at,updated_at) VALUES(?,?,?,'ACTIVE',now(),now(),now())",
                        user,
                        email(user),
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
            for (var client : Map.of(
                            "gateway",
                            List.of("identity.user-admission"),
                            "gateway-realtime",
                            List.of("assessment.realtime.subscription-authorize"),
                            "classroom-service",
                            List.of("identity.user-admission"),
                            "assessment-service",
                            List.of(
                                    "identity.user-admission",
                                    "classroom.teacher-authority.read",
                                    "classroom.membership.read"))
                    .entrySet()) {
                UUID id = UUID.randomUUID();
                execute(
                        c,
                        "INSERT INTO oauth2_service_client(id,client_id,client_secret_hash,enabled,created_at,updated_at) VALUES(?,?,?,true,now(),now())",
                        id,
                        client.getKey(),
                        encoder.encode(SECRET));
                for (String scope : client.getValue())
                    execute(
                            c,
                            "INSERT INTO oauth2_service_client_scope(service_client_id,scope) VALUES (?,?)",
                            id,
                            scope);
            }
        }
    }

    /** A teacher-owned OPEN CLASS publication with one synthetic NUMERIC_FILL question. */
    static UUID seedOpenClassPublication() throws Exception {
        UUID id = UUID.randomUUID(), quiz = UUID.randomUUID(), version = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        Instant end = Instant.now().plusSeconds(7200).truncatedTo(ChronoUnit.MICROS);
        var configuration = new LinkedHashMap<String, Object>();
        configuration.put("title", "W4 acceptance publication");
        configuration.put("description", null);
        configuration.put("quizId", quiz);
        configuration.put("quizVersionId", version);
        configuration.put("versionNumber", 1);
        configuration.put("audience", "CLASS");
        configuration.put("classroomId", CLASSROOM);
        configuration.put("startAt", start.toString());
        configuration.put("endAt", end.toString());
        configuration.put("durationMinutes", 30);
        configuration.put("monitoringChoice", "DISABLED");
        configuration.put("maxAttempts", 0);
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("quizId", quiz);
        snapshot.put("quizVersionId", version);
        snapshot.put("versionNumber", 1);
        snapshot.put("titleSnapshot", "W4 acceptance");
        snapshot.put("descriptionSnapshot", null);
        snapshot.put("sourceSnapshot", "private synthetic source");
        snapshot.put("contentSchemaVersion", 1);
        snapshot.put("createdAt", start.toString());
        var question = new LinkedHashMap<String, Object>();
        question.put("number", 1);
        question.put("type", "NUMERIC_FILL");
        question.put("stemMarkdown", "W4 acceptance question");
        question.put("numericAnswer", "0001");
        question.put("explanationMarkdown", "private synthetic explanation");
        question.put("options", List.of());
        snapshot.put("structuredContent", Map.of("questions", List.of(question)));
        try (var c = db(ASSESSMENT_DB)) {
            c.setAutoCommit(false);
            execute(
                    c,
                    "INSERT INTO publications(id,owner_id,configuration,quiz_id,quiz_version_id,version_number,audience,classroom_id,start_at,end_at,duration_minutes,monitoring_choice,state,revision,created_at,max_attempts) VALUES(?,?,?::jsonb,?,?,1,'CLASS',?,?::timestamptz,?::timestamptz,30,'DISABLED','DRAFT',0,now(),0)",
                    id,
                    TEACHER,
                    JSON.writeValueAsString(configuration),
                    quiz,
                    version,
                    CLASSROOM,
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

    static void provisionBroker() throws Exception {
        for (var user : Map.of(PUBLISHER, PUBLISHER_PASSWORD, CONSUMER, CONSUMER_PASSWORD)
                .entrySet())
            operator("PUT", "users/" + user.getKey(), Map.of("password", user.getValue(), "tags", ""), 201);
        // Least privilege: Assessment may only publish to the invalidation exchange; the
        // Gateway consumer may only declare its own transient queue and read the exchange.
        operator(
                "PUT",
                "permissions/" + VHOST + "/" + PUBLISHER,
                Map.of("configure", "^$", "write", "^" + regex(EXCHANGE) + "$", "read", "^$"),
                201);
        operator(
                "PUT",
                "permissions/" + VHOST + "/" + CONSUMER,
                Map.of(
                        "configure",
                        "^quizopia\\.gateway\\.realtime-invalidation",
                        "write",
                        "^quizopia\\.gateway\\.realtime-invalidation",
                        "read",
                        "^(" + regex(EXCHANGE) + "|quizopia\\.gateway\\.realtime-invalidation)"),
                201);
        operator(
                "PUT",
                "exchanges/" + VHOST + "/" + EXCHANGE,
                Map.of("type", "direct", "durable", true, "auto_delete", false, "internal", false),
                201);
    }

    static String regex(String literal) {
        return literal.replace(".", "\\.");
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
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), "Owned broker operator request failed");
        return response.body().isEmpty() ? JSON.readTree("null") : JSON.readTree(response.body());
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    static void buildArtifact(String name) throws Exception {
        String module = name.equals("gateway") ? "gateway" : "services/" + name + "-service";
        var command = mavenWrapper();
        command.addAll(List.of(
                "-B",
                "-DskipTests",
                "-Dquizopia.build.directory=" + repository.resolve(module + "/target/w4-acceptance-build"),
                "package"));
        var process = new ProcessBuilder(command)
                .directory(repository.resolve(module).toFile())
                .redirectErrorStream(true)
                .redirectOutput(evidence.resolve(name + "-build.log").toFile())
                .start();
        try {
            assertTrue(process.waitFor(300, TimeUnit.SECONDS), "Owned artifact build timeout: " + name);
            assertEquals(0, process.exitValue(), "Owned artifact build failed: " + name);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
            }
        }
    }

    static ArrayList<String> mavenWrapper() {
        if (isWindows()) return new ArrayList<>(List.of("cmd.exe", "/d", "/s", "/c", "mvnw.cmd"));
        return new ArrayList<>(List.of("bash", "mvnw"));
    }

    static void startService(String name) throws Exception {
        launch(name, processArgumentsOf(name));
        awaitHttp(originOf(name) + (name.equals("identity") ? "/oauth2/jwks" : "/actuator/health"));
    }

    static String originOf(String name) {
        return name.equals("identity") ? identity : name.equals("classroom") ? classroom : assessment;
    }

    static void launch(String name, List<String> args) throws Exception {
        Process previous = NAMED.remove(name);
        if (previous != null && previous.isAlive()) {
            previous.destroy();
            assertTrue(previous.waitFor(15, TimeUnit.SECONDS), "Owned process restart overrun: " + name);
        }
        NAMED.put(
                name,
                new ProcessBuilder(args)
                        .directory(repository.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(evidence.resolve(name + ".log").toFile())
                        .start());
    }

    static void stopService(String name) throws Exception {
        Process process = NAMED.get(name);
        assertNotNull(process, "Owned process not started: " + name);
        destroyTree(process);
        assertTrue(!process.isAlive(), "Owned process did not stop: " + name);
    }

    static void restartService(String name) throws Exception {
        // Windows can hold the listening socket briefly after process death; wait for it.
        awaitTrue(() -> httpDead(originOf(name)), "stopped service port must be released: " + name);
        launch(name, processArgumentsOf(name));
        awaitHttp(originOf(name) + (name.equals("identity") ? "/oauth2/jwks" : "/actuator/health"));
    }

    static List<String> processArgumentsOf(String name) {
        String origin = originOf(name);
        PostgreSQLContainer<?> database =
                name.equals("identity") ? IDENTITY_DB : name.equals("classroom") ? CLASSROOM_DB : ASSESSMENT_DB;
        var args = new ArrayList<String>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                repository
                        .resolve("services/" + name + "-service/target/w4-acceptance-build/quizopia-" + name
                                + "-0.1.0-SNAPSHOT.jar")
                        .toString(),
                "--server.port=" + URI.create(origin).getPort(),
                "--spring.profiles.active=local",
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                // Migrate the disposable fixture with its own credentials, not deployment defaults.
                "--spring.flyway.user=" + database.getUsername(),
                "--spring.flyway.password=" + database.getPassword()));
        if (!name.equals("identity")) {
            args.add("--quizopia.admission.enabled=true");
            args.add("--quizopia.admission.allow-insecure-loopback=true");
            args.add("--quizopia.admission.identity-base-url=" + identity);
            args.add("--quizopia.admission.client-id=" + name + "-service");
            args.add("--quizopia.admission.client-secret=" + SECRET);
            args.add("--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity);
            args.add("--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity + "/oauth2/jwks");
        }
        if (name.equals("identity")) {
            // Identity validates its own issued JWTs on /api/auth/me; its resource server
            // must resolve JWKS from the actual disposable origin, not the 8081 default.
            args.add("--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity);
            args.add("--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity + "/oauth2/jwks");
            args.addAll(identityExtraArgs());
        }
        if (name.equals("classroom")) args.addAll(classroomExtraArgs());
        if (name.equals("assessment")) args.addAll(assessmentExtraArgs());
        return args;
    }

    static List<String> identityExtraArgs() {
        return List.of(
                // Identity's refresh/logout cookie flow requires the browser Origin to be
                // trusted (same property the W3A runtime supplies via GATEWAY_ALLOWED_ORIGINS).
                "--quizopia.identity.security.browser.trusted-origins=" + ORIGIN + ",http://127.0.0.1:" + frontendPort,
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--quizopia.identity.email-outbox.encryption.active-key-version=v1",
                "--quizopia.identity.email-outbox.encryption.keys.v1="
                        + Base64.getEncoder().encodeToString(new byte[32]),
                "--quizopia.identity.security.authorization-server.enabled=true",
                "--quizopia.identity.security.authorization-server.issuer=" + identity,
                "--quizopia.identity.security.signing-key.kid=" + SIGNING_KEY.getKeyID(),
                "--quizopia.identity.security.signing-key.private-key-path=" + keyDirectory.resolve("private.pem"),
                "--quizopia.identity.security.signing-key.public-key-path=" + keyDirectory.resolve("public.pem"),
                "--quizopia.identity.user-admission.enabled=true",
                "--quizopia.identity.user-admission.require-secure-transport=false",
                "--quizopia.identity.user-admission.allowed-clients=gateway,classroom-service,assessment-service",
                "--quizopia.identity.user-admission.reconciliation-enabled=false",
                // Disposable local journey lifetime comfortably exceeds the topology run.
                "--quizopia.identity.security.authorization-server.user-access-token-ttl=PT2H",
                // W4-B2: the dedicated realtime authority audience is minted only for this client.
                "--quizopia.identity.security.authorization-server.realtime-gateway-client-id=gateway-realtime");
    }

    static List<String> classroomExtraArgs() {
        return List.of(
                "--quizopia.internal-authority.enabled=true",
                "--quizopia.internal-authority.require-tls=false",
                "--quizopia.internal-authority.assessment-client-id=assessment-service");
    }

    static List<String> assessmentExtraArgs() {
        return List.of(
                "--quizopia.publication.enabled=true",
                "--quizopia.assessment.features.attempts-enabled=true",
                "--quizopia.assessment.features.results-enabled=true",
                "--quizopia.assessment.features.closure-enabled=true",
                "--quizopia.assessment.realtime-authority.enabled=true",
                "--quizopia.assessment.realtime-authority.gateway-client-id=gateway-realtime",
                "--quizopia.assessment.realtime-authority.allow-insecure-loopback=true",
                "--quizopia.assessment.realtime-invalidation.capture-enabled=true",
                "--quizopia.assessment.realtime-invalidation.publisher-enabled=true",
                "--quizopia.assessment.realtime-invalidation.allow-insecure-loopback=true",
                "--quizopia.assessment.realtime-invalidation.host=" + BROKER.getHost(),
                "--quizopia.assessment.realtime-invalidation.port=" + BROKER.getMappedPort(5672),
                "--quizopia.assessment.realtime-invalidation.virtual-host=" + VHOST,
                "--quizopia.assessment.realtime-invalidation.username=" + PUBLISHER,
                "--quizopia.assessment.realtime-invalidation.password=" + PUBLISHER_PASSWORD,
                "--quizopia.assessment.realtime-invalidation.poll-delay-seconds=1",
                "--quizopia.publication.sources.enabled=true",
                "--quizopia.publication.sources.classroom-base-url=" + classroom,
                "--quizopia.publication.sources.quiz-base-url=http://127.0.0.1:1");
    }

    static void startGatewayProcess() throws Exception {
        var args = new ArrayList<String>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-XX:ActiveProcessorCount=2",
                "-jar",
                repository
                        .resolve("gateway/target/w4-acceptance-build/quizopia-gateway-0.1.0-SNAPSHOT.jar")
                        .toString()));
        args.addAll(realtimeEdgeArgs(edgeBPort));
        launch("gateway-b", args);
        edgeB = "http://127.0.0.1:" + edgeBPort;
        awaitHttp(edgeB + "/actuator/health");
    }

    /** W4 edge configuration: identical flags for the in-JVM instance and the OS-process twin. */
    static List<String> realtimeEdgeArgs(int serverPort) {
        String origins = ORIGIN + ",http://127.0.0.1:" + frontendPort;
        return List.of(
                "--server.port=" + serverPort,
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity + "/oauth2/jwks",
                "--IDENTITY_SERVICE_URL=" + identity,
                "--CLASSROOM_SERVICE_URL=" + classroom,
                "--ASSESSMENT_SERVICE_URL=" + assessment,
                "--QUIZ_SERVICE_URL=http://127.0.0.1:1",
                "--quizopia.publication.enabled=true",
                "--quizopia.assessment.features.attempts-enabled=true",
                "--quizopia.assessment.features.results-enabled=true",
                "--quizopia.assessment.features.closure-enabled=true",
                "--quizopia.admission.enabled=true",
                "--quizopia.admission.allow-insecure-loopback=true",
                "--quizopia.admission.identity-base-url=" + identity,
                "--quizopia.admission.client-id=gateway",
                "--quizopia.admission.client-secret=" + SECRET,
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--quizopia.gateway.browser.allowed-origins=" + origins,
                "--quizopia.realtime.enabled=true",
                "--quizopia.realtime.auth-timeout=PT8S",
                "--quizopia.realtime.authority.enabled=true",
                "--quizopia.realtime.authority.identity-base-url=" + identity,
                "--quizopia.realtime.authority.assessment-base-url=" + assessment,
                "--quizopia.realtime.authority.client-id=gateway-realtime",
                "--quizopia.realtime.authority.client-secret=" + SECRET,
                "--quizopia.realtime.authority.allow-insecure-loopback=true",
                "--quizopia.realtime.invalidation.enabled=true",
                "--quizopia.realtime.invalidation.allow-insecure-loopback=true",
                "--quizopia.realtime.invalidation.host=" + BROKER.getHost(),
                "--quizopia.realtime.invalidation.port=" + BROKER.getMappedPort(5672),
                "--quizopia.realtime.invalidation.virtual-host=" + VHOST,
                "--quizopia.realtime.invalidation.username=" + CONSUMER,
                "--quizopia.realtime.invalidation.password=" + CONSUMER_PASSWORD);
    }

    static void restartEdgeAWithRealtime() {
        edgeContext.close();
        edgeContext = gatewayContext(edgeAPort, true);
        edgeA = "http://127.0.0.1:" + edgeAPort;
    }

    static ConfigurableApplicationContext gatewayContext(int serverPort, boolean realtime) {
        var args = new ArrayList<String>(List.of(
                "--server.port=" + serverPort,
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity,
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity + "/oauth2/jwks",
                "--IDENTITY_SERVICE_URL=" + identity,
                "--CLASSROOM_SERVICE_URL=" + classroom,
                "--ASSESSMENT_SERVICE_URL=" + assessment,
                "--QUIZ_SERVICE_URL=http://127.0.0.1:1",
                "--quizopia.publication.enabled=true",
                "--quizopia.assessment.features.attempts-enabled=true",
                "--quizopia.assessment.features.results-enabled=true",
                "--quizopia.assessment.features.closure-enabled=true",
                "--quizopia.admission.enabled=true",
                "--quizopia.admission.allow-insecure-loopback=true",
                "--quizopia.admission.identity-base-url=" + identity,
                "--quizopia.admission.client-id=gateway",
                "--quizopia.admission.client-secret=" + SECRET,
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                // The Origin allowlist exists even while realtime stays default-OFF, so the
                // default-off journey exercises the capability gate rather than the allowlist.
                "--quizopia.gateway.browser.allowed-origins=" + ORIGIN + ",http://127.0.0.1:" + frontendPort));
        if (realtime) {
            // Skip the base arguments already supplied above (everything up to and
            // including the allowlist) so no property is supplied twice.
            args.addAll(realtimeEdgeArgs(serverPort)
                    .subList(19, realtimeEdgeArgs(serverPort).size()));
        }
        return new SpringApplicationBuilder(GatewayApplication.class)
                .profiles("test")
                .run(args.toArray(new String[0]));
    }

    /** The JDK client forbids Connection/Upgrade headers; the probe speaks HTTP/1.1 raw. */
    static int upgradeStatus(int port, String origin) throws Exception {
        try (var socket = new java.net.Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
            socket.setSoTimeout(10_000);
            byte[] secKey = new byte[16];
            RANDOM.nextBytes(secKey);
            var request = "GET /ws/realtime HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "
                    + Base64.getEncoder().encodeToString(secKey)
                    + "\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: quizopia.realtime.v1\r\nOrigin: "
                    + origin
                    + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String statusLine = reader.readLine();
            assertNotNull(statusLine, "Upgrade probe received no response");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    static void awaitHttp(String url) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < until) {
            try {
                if (send("GET", url, null, null, Map.of()).statusCode() == 200) return;
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        fail("Owned topology endpoint not ready: " + url);
    }

    static HttpResponse<String> send(String method, String url, String token, String body, Map<String, String> headers)
            throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        if (body != null) request.header("Content-Type", "application/json");
        return HTTP.send(
                request.method(
                                method,
                                body == null
                                        ? java.net.http.HttpRequest.BodyPublishers.noBody()
                                        : java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Refresh the disposable identity session through a real login when it goes stale. */
    static String freshToken(UUID actor) throws Exception {
        var login = send(
                "POST",
                identity + "/api/auth/login",
                null,
                "{\"identifier\":\"" + email(actor) + "\",\"password\":\"" + PASSWORD + "\"}",
                Map.of());
        assertEquals(200, login.statusCode(), "Real Identity re-login failed");
        String token = JSON.readTree(login.body()).path("accessToken").asString();
        TOKENS.put(String.valueOf(actor), token);
        SECRETS_OBSERVED.add(token);
        return token;
    }

    static HttpResponse<String> edgeRequest(String method, String path, UUID actor, String body, boolean idempotency)
            throws Exception {
        var response = send(
                method,
                edgeA + path,
                TOKENS.get(String.valueOf(actor)),
                body,
                idempotency ? Map.of("Idempotency-Key", UUID.randomUUID().toString()) : Map.of());
        if (response.statusCode() == 401) {
            // A stale disposable session (restart, rotation window) is re-established once
            // through the real login route; the retry then carries the fresh JWT.
            freshToken(actor);
            response = send(
                    method,
                    edgeA + path,
                    TOKENS.get(String.valueOf(actor)),
                    body,
                    idempotency ? Map.of("Idempotency-Key", UUID.randomUUID().toString()) : Map.of());
        }
        return response;
    }

    /** Real ticket issuance through the public edge exactly as a browser performs it. */
    static String ticket(UUID actor, String origin) throws Exception {
        var response = send(
                "POST",
                edgeA + "/api/realtime/tickets",
                TOKENS.get(String.valueOf(actor)),
                "{}",
                Map.of("Origin", origin));
        assertEquals(201, response.statusCode(), "Real ticket issuance failed: " + response.body());
        String ticket = JSON.readTree(response.body()).path("ticket").asString();
        SECRETS_OBSERVED.add(ticket);
        return ticket;
    }

    /** Real WebSocket speaking the exact frozen browser wire protocol. */
    static final class BrowserSocket implements AutoCloseable {
        final java.util.concurrent.BlockingQueue<String> inbound = new LinkedBlockingQueue<>();
        final java.util.concurrent.BlockingQueue<Integer> closes = new LinkedBlockingQueue<>();
        final java.net.http.WebSocket socket;

        BrowserSocket(String base, String origin) throws Exception {
            java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
                private final StringBuilder text = new StringBuilder();

                @Override
                public void onOpen(java.net.http.WebSocket webSocket) {
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                    text.append(data);
                    if (last) {
                        inbound.add(text.toString());
                        text.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(java.net.http.WebSocket webSocket, int statusCode, String reason) {
                    closes.add(statusCode);
                    return null;
                }

                @Override
                public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                    // Abrupt process loss can surface as a transport error before close.
                    closes.add(1006);
                }
            };
            socket = java.net.http.HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .header("Origin", origin)
                    .subprotocols("quizopia.realtime.v1")
                    .buildAsync(URI.create(base.replace("http", "ws") + "/ws/realtime"), listener)
                    .join();
        }

        void send(String text) {
            socket.sendText(text, true).join();
        }

        String next(Duration timeout) throws Exception {
            String message = inbound.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(message, "Expected another realtime frame");
            return message;
        }

        String quiet(Duration timeout) throws Exception {
            return inbound.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        Integer closeSeen(Duration timeout) throws Exception {
            return closes.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            try {
                socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "").join();
            } catch (RuntimeException ignored) {
            }
        }
    }

    static String authFrame(String ticket, UUID actor) {
        return "{\"protocolVersion\":1,\"type\":\"AUTH\",\"ticket\":\""
                + ticket
                + "\",\"accessToken\":\""
                + TOKENS.get(String.valueOf(actor))
                + "\"}";
    }

    static String subscribeFrame(String kind, UUID id) {
        return "{\"protocolVersion\":1,\"type\":\"SUBSCRIBE\",\"requestId\":\""
                + UUID.randomUUID()
                + "\",\"resource\":{\"kind\":\""
                + kind
                + "\",\"id\":\""
                + id
                + "\"}}";
    }

    static String subscriptionIdOf(String subscribedFrame) {
        return JSON.readTree(subscribedFrame).path("subscriptionId").asString();
    }

    static UUID startAttempt(UUID actor) throws Exception {
        var response = edgeRequest(
                "POST", "/api/assessments/publications/" + publication + "/attempts/start", actor, "{}", true);
        assertTrue(
                response.statusCode() == 200 || response.statusCode() == 201,
                "Real attempt start failed: HTTP " + response.statusCode() + " " + response.body());
        return UUID.fromString(JSON.readTree(response.body()).path("attemptId").asString());
    }

    static long outboxCount(String state) {
        try (var c = db(ASSESSMENT_DB);
                var statement = c.prepareStatement("SELECT count(*) FROM realtime_invalidation_outbox WHERE state=?")) {
            statement.setString(1, state);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static long outboxCountFor(UUID resource) {
        try (var c = db(ASSESSMENT_DB);
                var statement =
                        c.prepareStatement("SELECT count(*) FROM realtime_invalidation_outbox WHERE resource_id=?")) {
            statement.setObject(1, resource);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void publishInvalidation(UUID eventId, String eventType, String resourceKind, UUID resourceId)
            throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(BROKER.getHost());
        factory.setPort(BROKER.getMappedPort(5672));
        factory.setVirtualHost(VHOST);
        factory.setUsername(BROKER_ADMIN);
        factory.setPassword(BROKER_ADMIN_PASSWORD);
        try (var connection = factory.newConnection("w4-acceptance-injector");
                var channel = connection.createChannel()) {
            String body = "{\"eventId\":\""
                    + eventId + "\",\"eventType\":\"" + eventType
                    + "\",\"eventVersion\":1,\"resourceKind\":\"" + resourceKind
                    + "\",\"resourceId\":\"" + resourceId + "\",\"occurredAt\":\"" + Instant.now() + "\"}";
            channel.basicPublish(
                    EXCHANGE,
                    "PUBLICATION".equals(resourceKind) ? "publication.invalidated.v1" : "attempt.invalidated.v1",
                    null,
                    body.getBytes(StandardCharsets.UTF_8));
        }
    }

    static boolean httpDead(String base) {
        try {
            send("GET", base + "/actuator/health", null, null, Map.of());
            return false;
        } catch (Exception unreachable) {
            return true;
        }
    }

    static void awaitTrue(java.util.function.BooleanSupplier supplier, String what) {
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!supplier.getAsBoolean()) {
            if (System.nanoTime() >= until) fail("Timed out waiting for: " + what);
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for: " + what);
            }
        }
    }
}
