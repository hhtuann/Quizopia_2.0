package com.quizopia.gateway;

import static com.quizopia.gateway.PublicationTopologyTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * ADR-018 disposable Browser -> Gateway -> real services, with real Identity USER/SERVICE issuance.
 * An additional -Dfb015.ui=true round drives the production Next.js UI through the same topology.
 */
@EnabledIfSystemProperty(
        named = "fb015.browser",
        matches = "true",
        disabledReason = "Opt in with -Dfb015.browser=true after installing Node/Playwright Chromium")
class QuizDeletionTopologyTest {
    static final PostgreSQLContainer<?> PROCTORING_DB =
            new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("fb015_proctoring_roles.sql");
    static final List<org.testcontainers.containers.GenericContainer<?>> OWNED =
            List.of(IDENTITY_DB, CLASSROOM_DB, QUIZ_DB, ASSESSMENT_DB, REDIS, PROCTORING_DB);
    static final boolean UI_ROUND = "true".equals(System.getProperty("fb015.ui"));
    static Process browser;
    static Process next;
    static BufferedWriter browserInput;
    static BufferedReader browserOutput;
    static String proctoring;
    static String frontend;
    static int frontendPort;
    static Path evidence;
    static boolean completed;
    static final List<String> checks = new ArrayList<>();

    @BeforeAll
    static void topology() throws Exception {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(repository.resolve("AGENTS.md"))) repository = repository.getParent();
        evidence = repository.resolve("gateway/target/fb015-acceptance/" + UUID.randomUUID());
        Files.createDirectories(evidence);
        System.out.println("FB015 evidence: " + evidence);
        // Explicit lifecycle: even startup/assertion failures are cleaned up by @AfterAll.
        for (var container : OWNED) container.start();
        PublicationTopologyTest.start();
        build("services/proctoring-service");
        proctoring = "http://127.0.0.1:" + port();
        try (var c = identityDb()) {
            for (String scope : List.of("quiz.deletion.revoke", "quiz.deletion.purge"))
                execute(
                        c,
                        "INSERT INTO oauth2_service_client_scope(service_client_id,scope) SELECT id,? FROM oauth2_service_client WHERE client_id='quiz-service'",
                        scope);
            UUID client = UUID.randomUUID();
            execute(
                    c,
                    "INSERT INTO oauth2_service_client(id,client_id,client_secret_hash,enabled,created_at,updated_at) VALUES (?,'proctoring-service',?,true,now(),now())",
                    client,
                    PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(SECRET));
            execute(
                    c,
                    "INSERT INTO oauth2_service_client_scope(service_client_id,scope) VALUES (?,'identity.user-admission')",
                    client);
        }
        restart(
                "identity",
                List.of(
                        "--quizopia.identity.user-admission.allowed-clients=gateway,classroom-service,quiz-service,assessment-service,proctoring-service"));
        restart(
                "quiz",
                List.of(
                        "--quizopia.deletion.enabled=true",
                        "--quizopia.deletion.dependencies.enabled=true",
                        "--quizopia.deletion.dependencies.identity-base-url=" + identity,
                        "--quizopia.deletion.dependencies.assessment-base-url=" + assessment,
                        "--quizopia.deletion.dependencies.proctoring-base-url=" + proctoring,
                        "--quizopia.deletion.dependencies.client-secret=" + SECRET,
                        "--quizopia.deletion.dependencies.allow-insecure-loopback=true"));
        restart(
                "assessment",
                List.of(
                        "--spring.datasource.username=assessment", "--spring.datasource.password=assessment_local_only",
                        "--quizopia.deletion.enabled=true", "--quizopia.deletion.allow-insecure-loopback=true",
                        "--quizopia.deletion.ack-poll-delay-seconds=1", "--quizopia.deletion.requests-per-minute=6000",
                        "--server.tomcat.accesslog.enabled=true", "--server.tomcat.accesslog.directory=" + evidence,
                        "--server.tomcat.accesslog.prefix=assessment-access",
                                "--server.tomcat.accesslog.pattern=%m %U %s"));
        startBusiness(
                "proctoring",
                PROCTORING_DB,
                proctoring,
                List.of(
                        "--quizopia.deletion.enabled=true",
                        "--quizopia.deletion.allow-insecure-loopback=true",
                        "--quizopia.deletion.ack-poll-delay-seconds=1",
                        "--quizopia.deletion.requests-per-minute=6000",
                        "--quizopia.proctoring.monitoring.enabled=true",
                        "--quizopia.proctoring.monitoring.assessment-base-url=" + assessment,
                        "--server.tomcat.accesslog.enabled=true",
                        "--server.tomcat.accesslog.directory=" + evidence,
                        "--server.tomcat.accesslog.prefix=proctoring-access",
                        "--server.tomcat.accesslog.pattern=%m %U %s"));
        restart(
                "proctoring",
                List.of(
                        "--spring.datasource.username=proctoring",
                        "--spring.datasource.password=proctoring_local_only"));
        gatewayContext.close();
        var edgeArgs = new ArrayList<>(gatewayArguments);
        edgeArgs.add("--PROCTORING_SERVICE_URL=" + proctoring);
        edgeArgs.add("--quizopia.proctoring.enabled=true");
        if (UI_ROUND) {
            frontendPort = port();
            frontend = "http://127.0.0.1:" + frontendPort;
            // The real UI shell authenticates against Identity itself: /api/auth/me decodes the
            // issued JWT, and /api/auth/refresh demands a single explicitly trusted Origin.
            // Production supplies these as env; the 8081/localhost:3000 YAML defaults point at
            // no real origin in this disposable topology, so the round replays them as args.
            restart(
                    "identity",
                    List.of(
                            "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + identity + "/oauth2/jwks",
                            "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + identity,
                            "--quizopia.identity.security.browser.trusted-origins=" + frontend));
            // The UI edge proxy forwards the real browser Origin header; the Gateway only
            // accepts it when the disposable UI origin is allowed before the context starts.
            edgeArgs.add("--quizopia.gateway.browser.allowed-origins=" + frontend);
        }
        gatewayContext = new SpringApplicationBuilder(GatewayApplication.class)
                .profiles("test")
                .run(edgeArgs.toArray(String[]::new));
        browser = new ProcessBuilder(
                        "node", root.resolve("scripts/local/fb015-browser.cjs").toString())
                .directory(root.toFile())
                .redirectError(evidence.resolve("browser.log").toFile())
                .start();
        browserInput = new BufferedWriter(new OutputStreamWriter(browser.getOutputStream(), StandardCharsets.UTF_8));
        browserOutput = new BufferedReader(new InputStreamReader(browser.getInputStream(), StandardCharsets.UTF_8));
        assertEquals(
                200,
                command(Map.of("gateway", gateway, "identifier", "topology-user", "password", PASSWORD))
                        .path("status")
                        .asInt(),
                "Real browser login through Gateway");
        pass("Chromium login through Gateway; Identity-issued USER and real client-credentials SERVICE authentication");
    }

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void browserDeletionWithHoldLegacyProviderOutageRecoveryAndIsolation() throws Exception {
        var victim = quiz("FB015 disposable victim");
        var survivor = quiz("FB015 unrelated survivor");
        String quizId = victim.path("quizId").asString();
        String otherQuiz = survivor.path("quizId").asString();
        String version = api("POST", "/api/quizzes/" + quizId + "/versions", null, 201)
                .path("id")
                .asString();
        api(
                "PUT",
                "/api/quizzes/" + quizId + "/draft",
                Map.of("title", "FB015 disposable victim", "authoringSource", SOURCE.replace("0001", "0002")),
                200);
        String version2 = api("POST", "/api/quizzes/" + quizId + "/versions", null, 201)
                .path("id")
                .asString();
        String otherVersion = api("POST", "/api/quizzes/" + otherQuiz + "/versions", null, 201)
                .path("id")
                .asString();
        var classView = api(
                "POST",
                "/classrooms",
                Map.of("name", "FB015 synthetic class", "subject", "Test", "grade", "Test"),
                201);
        UUID classroomId = UUID.fromString(classView.path("id").asString());
        try (var c = connection(CLASSROOM_DB)) {
            execute(
                    c,
                    "INSERT INTO classroom_memberships(id,classroom_id,user_id) VALUES (?,?,?)",
                    UUID.randomUUID(),
                    classroomId,
                    ACTOR);
        }
        var publicPublication = publication(quizId, version, null, 1);
        var classPublication = publication(quizId, version2, classroomId.toString(), 2);
        var otherPublication = publication(otherQuiz, otherVersion, null, 1);
        String attempt = startAttempt(publicPublication, true);
        String classAttempt = startAttempt(classPublication, false);
        String otherAttempt = startAttempt(otherPublication, true);
        api(
                "POST",
                "/api/assessments/attempts/" + attempt + "/submit",
                Map.of(
                        "expectedRevision",
                        "0",
                        "answers",
                        List.of(Map.of(
                                "questionNumber", 1, "answer", Map.of("type", "NUMERIC_FILL", "value", "0001")))),
                200);
        UUID legacy = session(attempt, publicPublication.path("publicationId").asString(), null, classroomId, true);
        UUID unrelatedSession = session(
                otherAttempt,
                otherPublication.path("publicationId").asString(),
                UUID.fromString(otherQuiz),
                classroomId,
                false);
        pass(
                "Two QuizVersions; PUBLIC submitted/graded and CLASS active attempts; null-quiz legacy monitoring + media fixtures; unrelated quiz/attempt/session");

        // An independent custodian classifies a synthetic hold before deletion starts.
        try (var c = connection(PROCTORING_DB);
                var s = c.createStatement()) {
            s.execute("SET ROLE quizopia_proctoring_custodian");
            s.execute(
                    "SELECT proctoring_set_hold('" + UUID.randomUUID() + "','" + quizId + "','" + legacy
                            + "','synthetic institution','synthetic appeal','fixture reviewer',clock_timestamp()+interval '75 seconds',clock_timestamp()+interval '30 seconds')");
        }
        stopService("proctoring");
        String deletionPath = "/api/quizzes/" + quizId + "/deletion-requests";
        String idempotency = UUID.randomUUID().toString();
        var body = Map.of("confirmTitle", "FB015 disposable victim");
        String operation = request("POST", deletionPath, body, Map.of("Idempotency-Key", idempotency), 202)
                .path("operationId")
                .asString();
        awaitStatus(quizId, "WAITING_DEPENDENCY", null);
        assertEquals(1, count(QUIZ_DB, "SELECT count(*) FROM quizzes WHERE id=?", quizId));
        pass("Dependency process outage parks honestly in WAITING_DEPENDENCY and preserves primary content");
        restart("proctoring", List.of());
        awaitStatus(quizId, "BLOCKED_BY_RETENTION", null);
        assertEquals(1, count(PROCTORING_DB, "SELECT count(*) FROM monitoring_sessions WHERE id=?", legacy.toString()));
        assertEquals(2, count(ASSESSMENT_DB, "SELECT count(*) FROM publications WHERE quiz_id=?", quizId));
        // At this point both fences have acknowledged but the hold prevents purge.
        denied("GET", "/api/assessments/attempts/" + classAttempt, null);
        denied(
                "POST",
                "/api/assessments/attempts/" + attempt + "/submit",
                Map.of(
                        "expectedRevision",
                        "0",
                        "answers",
                        List.of(Map.of(
                                "questionNumber", 1, "answer", Map.of("type", "NUMERIC_FILL", "value", "0001")))));
        denied(
                "POST",
                "/api/assessments/publications/entry",
                Map.of("locator", publicPublication.path("privateLocator").asString()));
        denied("GET", "/api/proctoring/monitoring/sessions/" + legacy, null);
        pass("Hold blocks purge while stale Assessment and Proctoring browser routes deny access after the fence");
        // No forced clock, trigger disabling or hold deletion: allow the short fixture hold to expire.
        awaitStatus(quizId, "WAITING_DEPENDENCY", "pending-provider");
        assertEquals(
                1,
                count(
                        PROCTORING_DB,
                        "SELECT count(*) FROM monitoring_media_rooms WHERE session_id=?",
                        legacy.toString()));
        assertEquals(2, count(ASSESSMENT_DB, "SELECT count(*) FROM publications WHERE quiz_id=?", quizId));
        pass("Expired hold retries naturally; PENDING_PROVIDER preserves media locators and Assessment inventory");
        restart("quiz", List.of());
        restart("proctoring", List.of());
        awaitStatus(quizId, "WAITING_DEPENDENCY", "pending-provider");
        pass(
                "Coordinator and Proctoring process restart recover the same durable operation and pending provider state");
        // Explicitly synthetic custodian evidence, NOT an actual LiveKit erasure claim.
        try (var c = connection(PROCTORING_DB);
                var s = c.createStatement()) {
            s.execute("SET ROLE quizopia_proctoring_custodian");
            s.execute("INSERT INTO proctoring_provider_cleanup(operation_id,receipt_reference) VALUES ('" + operation
                    + "','SYNTHETIC-FB015-NO-EXTERNAL-MEDIA')");
        }
        var terminal = awaitStatus(quizId, "PRIMARY_PURGED", null);
        assertEquals("COPY_EXPIRY_PENDING", terminal.path("note").asString());
        assertEquals(
                operation,
                request("POST", deletionPath, body, Map.of("Idempotency-Key", idempotency), 202)
                        .path("operationId")
                        .asString());
        request("POST", deletionPath, Map.of("confirmTitle", "different"), Map.of("Idempotency-Key", idempotency), 409);
        assertEquals(
                operation,
                api("GET", deletionPath + "/latest", null, 200)
                        .path("operationId")
                        .asString());
        assertEquals(
                "PRIMARY_PURGED",
                api("GET", deletionPath + "/" + operation, null, 200)
                        .path("status")
                        .asString());
        pass(
                "PRIMARY_PURGED with COPY_EXPIRY_PENDING; latest/status discovery and idempotent replay/mismatch survive ledger scrub");
        for (String table : List.of("quizzes", "quiz_drafts", "quiz_versions"))
            assertEquals(
                    0,
                    count(
                            QUIZ_DB,
                            "SELECT count(*) FROM " + table + " WHERE " + (table.equals("quizzes") ? "id" : "quiz_id")
                                    + "=?",
                            quizId),
                    table);
        assertEquals(0, count(ASSESSMENT_DB, "SELECT count(*) FROM publications WHERE quiz_id=?", quizId));
        for (String id : List.of(attempt, classAttempt)) {
            for (String table : List.of(
                    "attempts",
                    "attempt_answers",
                    "submitted_answers",
                    "grading_results",
                    "attempt_operations",
                    "attempt_finalization_audit"))
                assertEquals(
                        0,
                        count(
                                ASSESSMENT_DB,
                                "SELECT count(*) FROM " + table + " WHERE "
                                        + (table.equals("attempts") ? "id" : "attempt_id") + "=?",
                                id),
                        table);
        }
        for (var publication : List.of(publicPublication, classPublication))
            assertEquals(
                    0,
                    count(
                            ASSESSMENT_DB,
                            "SELECT count(*) FROM publication_snapshots WHERE publication_id=?",
                            publication.path("publicationId").asString()));
        for (String table : List.of(
                "monitoring_sessions",
                "monitoring_evidence",
                "monitoring_access_audit",
                "monitoring_media_rooms",
                "monitoring_media_grants"))
            assertEquals(
                    0,
                    count(
                            PROCTORING_DB,
                            "SELECT count(*) FROM " + table + " WHERE "
                                    + (table.equals("monitoring_sessions") ? "id" : "session_id") + "=?",
                            legacy.toString()),
                    table);
        assertEquals(
                1,
                count(
                        QUIZ_DB,
                        "SELECT count(*) FROM quiz_deletion_operations WHERE quiz_id=? AND confirmed_title IS NULL AND version_inventory='{\"versionCount\":2}'::jsonb",
                        quizId));
        assertEquals(1, count(QUIZ_DB, "SELECT count(*) FROM quizzes WHERE id=?", otherQuiz));
        assertEquals(
                1,
                count(
                        PROCTORING_DB,
                        "SELECT count(*) FROM monitoring_sessions WHERE id=?",
                        unrelatedSession.toString()));
        api("GET", "/api/quizzes/" + otherQuiz + "/draft", null, 200);
        api("GET", "/api/assessments/attempts/" + otherAttempt, null, 200);
        assertEquals(
                1,
                count(
                        CLASSROOM_DB,
                        "SELECT count(*) FROM classroom_memberships WHERE classroom_id=?",
                        classroomId.toString()));
        assertEquals(1, count(IDENTITY_DB, "SELECT count(*) FROM user_account WHERE id=?", ACTOR.toString()));
        pass(
                "Primary quiz/version/draft, publication/attempt/answer/result and legacy monitoring/media rows absent; ledger title/inventory scrubbed; unrelated quiz/attempt/session and Identity/Classroom preserved");
        if (UI_ROUND) realUiDeletionRound(otherQuiz);
        var limitations = new ArrayList<String>();
        limitations.add(
                UI_ROUND
                        ? "Real-UI round covers the deletion UX journeys only (login, named/untitled dialog delete, reload recovery, terminal wording); role/negative and accessibility matrices remain frontend unit/e2e work"
                        : "Backend HTTP browser acceptance; frontend #125 UI not exercised");
        limitations.addAll(
                List.of(
                        "Provider receipt is a synthetic custodian fixture; no LiveKit/provider deletion performed",
                        "No backup/CDN/export erasure claim; ALL_COPIES_ERASED is not reached",
                        "Not an exhaustive race, lifecycle, closure/outbox or authorization matrix; retain service integration suites"));
        Files.writeString(
                evidence.resolve("result.json"),
                JSON.writeValueAsString(Map.of("status", "PASS", "checks", checks, "limitations", limitations)));
        completed = true;
    }

    static JsonNode quiz(String title) throws Exception {
        return api("POST", "/api/quizzes", Map.of("title", title, "authoringSource", SOURCE), 201);
    }

    static JsonNode publication(String quizId, String version, String classroom, int number) throws Exception {
        var body = JSON.readTree(publicationBody(quizId, version, classroom));
        var object = (tools.jackson.databind.node.ObjectNode) body;
        object.put("versionNumber", number);
        var created = api("POST", "/api/assessments/publications", body, 201);
        api(
                "POST",
                "/api/assessments/publications/" + created.path("publicationId").asString() + "/open",
                Map.of("expectedRevision", 0),
                200);
        return created;
    }

    static String startAttempt(JsonNode publication, boolean isPublic) throws Exception {
        return api(
                        "POST",
                        "/api/assessments/publications/"
                                + publication.path("publicationId").asString() + "/attempts/start",
                        isPublic
                                ? Map.of(
                                        "locator",
                                        publication.path("privateLocator").asString())
                                : Map.of(),
                        201)
                .path("attemptId")
                .asString();
    }

    static UUID session(String attempt, String publication, UUID quizId, UUID classroom, boolean media)
            throws Exception {
        UUID id = UUID.randomUUID();
        try (var c = connection(PROCTORING_DB)) {
            execute(
                    c,
                    "INSERT INTO monitoring_sessions(id,attempt_id,publication_id,classroom_id,student_id,quiz_id,deadline_at,status,created_at) VALUES (?,?,?,?,?,?,clock_timestamp()+interval '1 hour','ACTIVE',clock_timestamp())",
                    id,
                    UUID.fromString(attempt),
                    UUID.fromString(publication),
                    classroom,
                    ACTOR,
                    quizId);
            if (media) {
                execute(
                        c,
                        "INSERT INTO monitoring_evidence(id,session_id,actor_id,event_type,received_at,integrity_sha256,source) VALUES (?,?,?,'SESSION_ENDED',clock_timestamp(),?,'SERVER_AUTHORITY')",
                        UUID.randomUUID(),
                        id,
                        ACTOR,
                        "a".repeat(64));
                execute(
                        c,
                        "INSERT INTO monitoring_access_audit(session_id,actor_id,viewer_role,action,accessed_at) VALUES (?,?,'STUDENT','SESSION_READ',clock_timestamp())",
                        id,
                        ACTOR);
                execute(c, "INSERT INTO monitoring_media_rooms(session_id,room_name) VALUES (?,?)", id, "fb015-" + id);
                execute(
                        c,
                        "INSERT INTO monitoring_media_grants(id,session_id,actor_id,viewer_role,participant_identity,lease_until,state) VALUES (?,?,?,'STUDENT',?,clock_timestamp()+interval '10 minutes','PREPARED')",
                        UUID.randomUUID(),
                        id,
                        ACTOR,
                        "fb015-" + id);
            }
        }
        return id;
    }

    static JsonNode api(String method, String path, Object body, int status) throws Exception {
        return request(
                method, path, body, Map.of("Idempotency-Key", UUID.randomUUID().toString()), status);
    }

    static JsonNode request(String method, String path, Object body, Map<String, String> headers, int status)
            throws Exception {
        var command = new LinkedHashMap<String, Object>();
        command.put("method", method);
        command.put("path", path);
        command.put("body", body);
        command.put("headers", headers);
        var response = command(command);
        assertEquals(
                status,
                response.path("status").asInt(),
                method + " " + path + " code="
                        + response.path("body").path("code").asString());
        return response.path("body");
    }

    static void denied(String method, String path, Object body) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("method", method);
        request.put("path", path);
        request.put("body", body);
        request.put("headers", Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        var response = command(request);
        assertTrue(
                Set.of(403, 404, 410).contains(response.path("status").asInt()),
                "Stale route must deny business access: " + path + " status="
                        + response.path("status").asInt() + " code="
                        + response.path("body").path("code").asString());
    }

    static JsonNode awaitStatus(String quizId, String status, String reason) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        JsonNode last = null;
        do {
            last = api("GET", "/api/quizzes/" + quizId + "/deletion-requests/latest", null, 200);
            if (status.equals(last.path("status").asString())
                    && (reason == null || reason.equals(last.path("lastReason").asString()))) return last;
            Thread.sleep(1000);
        } while (System.nanoTime() < until);
        fail("Expected " + status + "/" + reason + "; last=" + last);
        return last;
    }

    static JsonNode command(Map<String, ?> value) throws Exception {
        browserInput.write(JSON.writeValueAsString(value));
        browserInput.newLine();
        browserInput.flush();
        // Bound even a broken Node/Chromium startup or one slow UI round; do not hang the Maven process.
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!browserOutput.ready() && browser.isAlive() && System.nanoTime() < until) Thread.sleep(25);
        assertTrue(browserOutput.ready(), "Browser driver did not reply; see fb015-acceptance/browser.log");
        var result = JSON.readTree(browserOutput.readLine());
        assertFalse(result.has("error"), "Browser transport failed; see fb015-acceptance/browser.log");
        return result;
    }

    static Connection connection(PostgreSQLContainer<?> db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    static long count(PostgreSQLContainer<?> db, String sql, String id) throws Exception {
        try (var c = connection(db);
                var s = c.prepareStatement(sql)) {
            s.setObject(1, UUID.fromString(id));
            try (var rows = s.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    static void restart(String name, List<String> overrides) throws Exception {
        stopService(name);
        var args = new ArrayList<>(processArguments.get(name));
        for (String override : overrides) {
            String prefix = override.substring(0, override.indexOf('=') + 1);
            args.removeIf(arg -> arg.startsWith(prefix));
            args.add(override);
        }
        startProcess(name, processArtifacts.get(name), args);
        String origin =
                switch (name) {
                    case "identity" -> identity;
                    case "quiz" -> quiz;
                    case "assessment" -> assessment;
                    default -> proctoring;
                };
        awaitHttp(origin + (name.equals("identity") ? "/oauth2/jwks" : "/actuator/health"));
    }

    static void stopService(String name) throws Exception {
        Process process = namedProcesses.get(name);
        if (process != null) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    static void pass(String check) throws IOException {
        checks.add(check);
        System.out.println("FB015 PASS: " + check);
        Files.writeString(evidence.resolve("checks.json"), JSON.writeValueAsString(checks));
    }

    /**
     * Opt-in (-Dfb015.ui=true) real-UI round. A production Next build with a RELATIVE public API
     * base (the local frontend/.env.local pins an absolute base; a whitespace override wins at
     * build time and trims back to relative inside publicApiTarget) is served by fb015-next.cjs,
     * which proxies same-origin /api traffic to this disposable Gateway. The real Chromium then
     * logs in and deletes one named and one untitled quiz through the actual dialog, rediscovers
     * status after a storage-cleared reload, and must show PRIMARY_PURGED copies-pending wording.
     */
    static void realUiDeletionRound(String survivor) throws Exception {
        String frontendOrigin = frontend;
        List<String> pnpm =
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? List.of("cmd.exe", "/d", "/s", "/c", "pnpm.cmd", "build")
                        : List.of("pnpm", "build");
        var buildCommand = new ProcessBuilder(pnpm)
                .directory(root.resolve("frontend").toFile())
                .redirectErrorStream(true)
                .redirectOutput(evidence.resolve("frontend-build.log").toFile());
        buildCommand.environment().put("NEXT_PUBLIC_API_URL", " ");
        buildCommand.environment().put("NEXT_TELEMETRY_DISABLED", "1");
        Process build = buildCommand.start();
        assertTrue(build.waitFor(300, TimeUnit.SECONDS), "Frontend production build timed out");
        assertEquals(0, build.exitValue(), "See fb015-acceptance/frontend-build.log");
        var serverCommand = new ProcessBuilder(
                        "node", root.resolve("scripts/local/fb015-next.cjs").toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(evidence.resolve("next.log").toFile());
        serverCommand.environment().put("FB015_GATEWAY_URL", gateway);
        serverCommand.environment().put("FB015_UI_PORT", Integer.toString(frontendPort));
        next = serverCommand.start();
        awaitHttp(frontendOrigin + "/login");
        pass("Production Next server serves the real UI and proxies same-origin API traffic to the disposable Gateway");
        assertEquals(
                200,
                ui("login", "frontend", frontendOrigin, "identifier", "topology-user", "password", PASSWORD)
                        .path("status")
                        .asInt(),
                "Real UI login through the Gateway edge");
        pass("Real UI login with the topology fixture account");
        uiDeletionRound("FB015 UI disposable victim", survivor);
        uiDeletionRound("", survivor);
    }

    static void uiDeletionRound(String title, String survivor) throws Exception {
        String quizId = quiz(title).path("quizId").asString();
        // Park the fresh operation with a genuine Proctoring outage, exactly like the backend
        // round above, so the reload-recovery assertion cannot race the primary purge.
        stopService("proctoring");
        assertEquals(
                202,
                ui("delete", "quizId", quizId, "title", title).path("status").asInt(),
                title.isEmpty()
                        ? "Real dialog deletion of the untitled quiz"
                        : "Real dialog deletion of the named quiz");
        pass(
                (title.isEmpty() ? "Untitled" : "Named")
                        + " quiz deleted through the real dialog: exact confirmation gating, single 202 POST with Idempotency-Key, fenced card without a Delete entry");
        assertEquals(
                200,
                ui("recover", "quizId", quizId, "text", "Deletion progress")
                        .path("status")
                        .asInt(),
                "Reload recovery without any stored operation");
        pass("Reload with cleared tab storage rediscovers the latest deletion status through the real UI");
        restart("proctoring", List.of());
        awaitStatus(quizId, "PRIMARY_PURGED", null); // synchronization only; the UI evidence is below
        assertEquals(
                200,
                ui("terminal", "quizId", quizId, "survivor", survivor)
                        .path("status")
                        .asInt(),
                "Terminal UI round");
        pass(
                "UI terminal wording shows PRIMARY_PURGED with copies-pending copy, never fully erased; survivor quiz preserved");
    }

    static JsonNode ui(String action, String... pairs) throws Exception {
        var command = new LinkedHashMap<String, Object>();
        command.put("ui", action);
        for (int i = 0; i < pairs.length; i += 2) command.put(pairs[i], pairs[i + 1]);
        return command(command);
    }

    @AfterAll
    static void cleanup() throws Exception {
        try {
            if (evidence != null && !completed)
                Files.writeString(
                        evidence.resolve("result.json"),
                        JSON.writeValueAsString(
                                Map.of(
                                        "status",
                                        "FAIL",
                                        "checksPassedBeforeFailure",
                                        checks,
                                        "details",
                                        "See Surefire failure and sanitized Assessment/Proctoring access logs; unexecuted later assertions are not acceptance evidence")));
            if (browserInput != null && browser != null && browser.isAlive()) {
                browserInput.write("{\"close\":true}\n");
                browserInput.flush();
                if (!browser.waitFor(10, TimeUnit.SECONDS)) {
                    browser.descendants().forEach(ProcessHandle::destroyForcibly);
                    browser.destroyForcibly();
                }
            }
            if (next != null && next.isAlive()) {
                next.destroy();
                if (!next.waitFor(10, TimeUnit.SECONDS)) {
                    next.descendants().forEach(ProcessHandle::destroyForcibly);
                    next.destroyForcibly();
                    next.waitFor(10, TimeUnit.SECONDS);
                }
            }
        } finally {
            try {
                PublicationTopologyTest.stop();
            } finally {
                for (var container : OWNED.reversed()) if (container.isRunning()) container.stop();
            }
        }
    }
}
