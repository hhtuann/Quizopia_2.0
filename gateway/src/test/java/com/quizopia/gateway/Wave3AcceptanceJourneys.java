package com.quizopia.gateway;

import static com.quizopia.gateway.PublicationTopologyTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import javax.net.ssl.*;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Extensions of the existing real topology; no independent fixture identities/databases. */
final class Wave3AcceptanceJourneys {
    private Wave3AcceptanceJourneys() {}

    static String peer(String original, String label, Predicate<String> remove, List<String> extra) throws Exception {
        String origin = "http://127.0.0.1:" + port();
        var args = new ArrayList<>(processArguments.get(original).stream()
                .filter(remove.negate())
                .filter(a -> !a.startsWith("--server.port="))
                .toList());
        args.add("--server.port=" + URI.create(origin).getPort());
        args.addAll(extra);
        startProcess(label, processArtifacts.get(original), args);
        awaitHttp(origin + "/actuator/health");
        return origin;
    }

    static void stopPeer(String label) throws Exception {
        var process = namedProcesses.get(label);
        if (process == null) return;
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        }
    }

    static boolean futureFlag(String arg) {
        return arg.startsWith("--quizopia.publication.")
                || arg.startsWith("--quizopia.assessment.")
                || arg.startsWith("--quizopia.admission.")
                || arg.startsWith("--quizopia.internal-authority.")
                || arg.startsWith("--quizopia.identity.notice-recipients.")
                || arg.startsWith("--quizopia.identity.user-admission.");
    }

    static void defaultDisabled() throws Exception {
        String disabledAssessment = null;
        try {
            disabledAssessment =
                    peer("assessment", "acceptance-default-assessment", Wave3AcceptanceJourneys::futureFlag, List.of());
            var args = new ArrayList<>(gatewayArguments.stream()
                    .filter(a -> !futureFlag(a))
                    .filter(a -> !a.startsWith("--ASSESSMENT_SERVICE_URL="))
                    .toList());
            args.add("--ASSESSMENT_SERVICE_URL=" + disabledAssessment);
            gatewayContext.close();
            gatewayContext = new SpringApplicationBuilder(GatewayApplication.class)
                    .profiles("test")
                    .run(args.toArray(String[]::new));
            {
                String origin = gateway;
                for (String base : List.of(origin, disabledAssessment)) {
                    for (var request : b2Requests())
                        assertEquals(
                                503,
                                send(
                                                request[0],
                                                base + request[1].replace("null", ACTOR.toString()),
                                                userToken,
                                                request[2],
                                                Map.of(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString()))
                                        .statusCode());
                    for (var request : b3Requests())
                        assertEquals(
                                503,
                                send(
                                                request[0],
                                                base + request[1].replace("null", ACTOR.toString()),
                                                userToken,
                                                request[2],
                                                Map.of(
                                                        "Idempotency-Key",
                                                        UUID.randomUUID().toString()))
                                        .statusCode());
                    assertEquals(
                            503,
                            send("GET", base + "/api/assessments/publications/" + ACTOR, userToken, null, Map.of())
                                    .statusCode());
                }
                assertEquals(
                        403,
                        send(
                                        "POST",
                                        origin + "/internal/security/notice-recipients/" + ACTOR + "/eligibility",
                                        serviceToken,
                                        null,
                                        Map.of())
                                .statusCode());
            }
        } finally {
            gatewayContext.close();
            gatewayContext = new SpringApplicationBuilder(GatewayApplication.class)
                    .profiles("test")
                    .run(gatewayArguments.toArray(String[]::new));
            stopPeer("acceptance-default-assessment");
        }
        for (String name : List.of("identity", "quiz", "classroom")) {
            String label = "acceptance-default-" + name;
            try {
                String origin = peer(name, label, Wave3AcceptanceJourneys::futureFlag, List.of());
                String path = name.equals("identity")
                        ? "/internal/security/notice-recipients/" + ACTOR + "/eligibility"
                        : name.equals("quiz")
                                ? "/internal/quiz-versions/" + ACTOR + "/" + ACTOR + "/1/snapshot"
                                : "/internal/classrooms/" + ACTOR + "/teacher-authority";
                String scope = name.equals("identity")
                        ? "identity.notice-recipient.read"
                        : name.equals("quiz") ? "quiz.version-snapshot.read" : "classroom.teacher-authority.read";
                var response = send(
                        "POST",
                        origin + path,
                        service("assessment-service", SECRET, scope),
                        null,
                        name.equals("identity") ? Map.of() : Map.of("X-Quizopia-User-Evidence", userToken));
                assertEquals(name.equals("identity") ? 503 : 403, response.statusCode(), name + ": " + response.body());
                assertEquals(
                        "no-store",
                        response.headers().firstValue("Cache-Control").orElseThrow());
            } finally {
                stopPeer(label);
            }
        }
    }

    static void twoProcesses() throws Exception {
        String second = peer("assessment", "acceptance-assessment-peer", a -> false, List.of());
        boolean primaryStopped = false;
        try {
            String other = additionalUser("STUDENT");
            var draft = send(
                    "POST",
                    quiz + "/api/quizzes",
                    userToken,
                    JSON.writeValueAsString(
                            Map.of("title", "Acceptance source", "description", "Fixture", "authoringSource", SOURCE)),
                    Map.of());
            assertEquals(201, draft.statusCode(), draft.body());
            String quizId = JSON.readTree(draft.body()).path("quizId").asString();
            var version = send("POST", quiz + "/api/quizzes/" + quizId + "/versions", userToken, null, Map.of());
            assertEquals(201, version.statusCode());
            var cfg = (tools.jackson.databind.node.ObjectNode) JSON.readTree(publicationBody(
                    quizId, JSON.readTree(version.body()).path("id").asString(), null));
            cfg.put("durationMinutes", 0);
            cfg.put("maxAttempts", 0);
            Instant end = Instant.now().plusSeconds(5).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            cfg.put("endAt", end.toString());
            var created = send(
                    "POST",
                    gateway + "/api/assessments/publications",
                    userToken,
                    cfg.toString(),
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(201, created.statusCode(), created.body());
            var publication = JSON.readTree(created.body());
            String id = publication.path("publicationId").asString(), path = "/api/assessments/publications/" + id;
            String locator = publication.path("privateLocator").asString();
            sensitiveLocators.add(locator);
            assertEquals(
                    200,
                    send("POST", gateway + path + "/open", userToken, "{\"expectedRevision\":0}", Map.of())
                            .statusCode());
            for (String token : List.of(userToken, other)) {
                var start = send(
                        "POST",
                        gateway + path + "/attempts/start",
                        token,
                        JSON.writeValueAsString(Map.of("locator", locator)),
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()));
                assertEquals(201, start.statusCode(), start.body());
                String attempt = JSON.readTree(start.body()).path("attemptId").asString();
                assertEquals(
                        200,
                        send(
                                        "POST",
                                        second + "/api/assessments/attempts/" + attempt + "/questions/1/answer",
                                        token,
                                        "{\"expectedRevision\":\"0\",\"answer\":{\"type\":\"NUMERIC_FILL\",\"value\":\"0001\"}}",
                                        Map.of(
                                                "Idempotency-Key",
                                                UUID.randomUUID().toString()))
                                .statusCode());
            }
            while (Instant.now().isBefore(end)) Thread.sleep(20);
            try {
                String disabled = peer(
                        "assessment",
                        "acceptance-disabled-inbox",
                        a -> a.startsWith("--quizopia.assessment.closure.inbox-enabled="),
                        List.of("--quizopia.assessment.closure.inbox-enabled=false"));
                var unavailable = send(
                        "POST",
                        disabled + path + "/closure-requests",
                        userToken,
                        "{\"expectedRevision\":1}",
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()));
                assertEquals(503, unavailable.statusCode(), unavailable.body());
                assertEquals("{\"code\":\"DEPENDENCY_UNAVAILABLE\"}", unavailable.body());
                assertEquals(0, scalar("SELECT count(*) FROM closure_requests WHERE publication_id='" + id + "'"));
            } finally {
                stopPeer("acceptance-disabled-inbox");
            }
            var requested = send(
                    "POST",
                    gateway + path + "/closure-requests",
                    userToken,
                    "{\"expectedRevision\":1}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            assertEquals(201, requested.statusCode(), requested.body());
            String request =
                    JSON.readTree(requested.body()).path("closureRequestId").asString();
            awaitNotice(request);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline
                    && scalar("SELECT count(*) FROM closure_request_members WHERE closure_request_id='" + request
                                    + "' AND state='NOTICE_CONFIRMED'")
                            != 2) Thread.sleep(20);
            assertEquals(
                    2,
                    scalar("SELECT count(*) FROM closure_request_members WHERE closure_request_id='" + request
                            + "' AND state='NOTICE_CONFIRMED'"));
            stopPeer("assessment");
            primaryStopped = true;
            // Confirmed evidence must survive primary application process loss; the other real JVM recovers it.
            assessmentTime(172801);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline
                    && scalar(
                                    "SELECT count(*) FROM grading_results WHERE attempt_id IN (SELECT id FROM attempts WHERE publication_id='"
                                            + id + "')")
                            != 2) Thread.sleep(20);
            assertEquals(
                    2,
                    scalar(
                            "SELECT count(*) FROM grading_results WHERE attempt_id IN (SELECT id FROM attempts WHERE publication_id='"
                                    + id + "')"));
            assertEquals(1, scalar("SELECT count(*) FROM publications WHERE id='" + id + "' AND state='OPEN'"));
            startProcess(
                    "acceptance-assessment-restarted",
                    processArtifacts.get("assessment"),
                    processArguments.get("assessment"));
            namedProcesses.put("assessment", namedProcesses.get("acceptance-assessment-restarted"));
            awaitHttp(assessment + "/actuator/health");
            primaryStopped = false;
            String key = UUID.randomUUID().toString(), complete = path + "/closure-requests/" + request + "/complete";
            try (var pool = Executors.newFixedThreadPool(2)) {
                var ready = new CountDownLatch(2);
                var release = new CountDownLatch(1);
                var futures = new ArrayList<Future<HttpResponse<String>>>();
                for (String base : List.of(assessment, second))
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return send(
                                "POST",
                                base + complete,
                                userToken,
                                "{\"expectedRevision\":2}",
                                Map.of("Idempotency-Key", key));
                    }));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                release.countDown();
                var first = futures.get(0).get(10, TimeUnit.SECONDS);
                var replay = futures.get(1).get(10, TimeUnit.SECONDS);
                assertEquals(200, first.statusCode(), first.body());
                assertEquals(200, replay.statusCode(), replay.body());
                assertEquals(first.body(), replay.body());
            }
            assertEquals(
                    2,
                    scalar("SELECT count(*) FROM attempt_finalization_audit WHERE publication_id='" + id
                            + "' AND reason='TEACHER_NOTICE_CUTOFF'"));
            assertEquals(
                    2,
                    scalar(
                            "SELECT count(*) FROM grading_results WHERE attempt_id IN (SELECT id FROM attempts WHERE publication_id='"
                                    + id + "') AND earned_weight=1"));
        } finally {
            assessmentTime(0);
            if (primaryStopped) {
                startProcess(
                        "acceptance-assessment-restarted",
                        processArtifacts.get("assessment"),
                        processArguments.get("assessment"));
                namedProcesses.put("assessment", namedProcesses.get("acceptance-assessment-restarted"));
                awaitHttp(assessment + "/actuator/health");
            }
            stopPeer("acceptance-assessment-peer");
        }
    }

    static long scalar(String sql) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                        ASSESSMENT_DB.getJdbcUrl(), ASSESSMENT_DB.getUsername(), ASSESSMENT_DB.getPassword());
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    static void https() throws Exception {
        Path store = keys.resolve("acceptance-tls.p12");
        String password = UUID.randomUUID().toString();
        var generate = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "keytool")
                                .toString(),
                        "-genkeypair",
                        "-alias",
                        "acceptance",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "2",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost,ip:127.0.0.1",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        store.toString(),
                        "-storepass",
                        password,
                        "-keypass",
                        password,
                        "-noprompt")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertTrue(generate.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, generate.exitValue());
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        var material = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            material.load(input, password.toCharArray());
        }
        trust.setCertificateEntry("acceptance", material.getCertificate("acceptance"));
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(null, managers.getTrustManagers(), null);
        var client = HttpClient.newBuilder()
                .sslContext(ssl)
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        int port = port();
        String origin = "https://127.0.0.1:" + port;
        var args = new ArrayList<>(processArguments.get("identity").stream()
                .filter(a -> !a.startsWith("--server.port=")
                        && !a.startsWith("--quizopia.identity.notice-recipients.require-secure-transport="))
                .toList());
        args.addAll(List.of(
                "--server.port=" + port,
                "--server.ssl.enabled=true",
                "--server.ssl.key-store=" + store.toUri(),
                "--server.ssl.key-store-type=PKCS12",
                "--server.ssl.key-store-password=" + password,
                "--quizopia.identity.notice-recipients.require-secure-transport=true",
                "--logging.level.root=TRACE"));
        String token = service("assessment-service", SECRET, "identity.notice-recipient.read");
        try {
            startProcess("acceptance-identity-https", processArtifacts.get("identity"), args);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            boolean up = false;
            while (System.nanoTime() < deadline) {
                try {
                    up = client.send(
                                            HttpRequest.newBuilder(URI.create(origin + "/actuator/health"))
                                                    .timeout(Duration.ofSeconds(3))
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.discarding())
                                    .statusCode()
                            == 200;
                    if (up) break;
                } catch (Exception unavailable) {
                }
                Thread.sleep(50);
            }
            assertTrue(up, "HTTPS Identity did not start");
            String path = "/internal/security/notice-recipients/" + ACTOR + "/eligibility";
            var response = client.send(
                    HttpRequest.newBuilder(URI.create(origin + path))
                            .timeout(Duration.ofSeconds(5))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(JSON.readTree(response.body()).path("eligible").asBoolean());
            assertEquals(3, JSON.readTree(response.body()).size());
            assertEquals(
                    "no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(response.sslSession().orElseThrow().getProtocol().startsWith("TLS"));
            for (String bearer : List.of(
                    userToken,
                    service("assessment-service", SECRET, "identity.user-admission"),
                    service("wrong-client", SECRET, "identity.user-admission"))) {
                var rejected = client.send(
                        HttpRequest.newBuilder(URI.create(origin + path))
                                .timeout(Duration.ofSeconds(5))
                                .header("Authorization", "Bearer " + bearer)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, rejected.statusCode());
                assertEquals("{\"code\":\"SERVICE_CALLER_FORBIDDEN\"}", rejected.body());
            }
            for (String lookalike : List.of(path + "/extra", path + "/")) {
                var rejected = client.send(
                        HttpRequest.newBuilder(URI.create(origin + lookalike))
                                .timeout(Duration.ofSeconds(5))
                                .header("Authorization", "Bearer " + token)
                                .POST(HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, rejected.statusCode());
            }
            var crossCapability = client.send(
                    HttpRequest.newBuilder(URI.create(origin + "/internal/security/user-admissions"))
                            .timeout(Duration.ofSeconds(5))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, crossCapability.statusCode());
            assertThrows(
                    SSLHandshakeException.class,
                    () -> client.send(
                            HttpRequest.newBuilder(URI.create(origin.replace("127.0.0.1", "127.0.0.2") + path))
                                    .timeout(Duration.ofSeconds(3))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.discarding()));
            assertThrows(
                    java.io.IOException.class,
                    () -> HTTP.send(
                            HttpRequest.newBuilder(URI.create(origin + path))
                                    .timeout(Duration.ofSeconds(3))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.discarding()));
            String log = Files.readString(logs.resolve("acceptance-identity-https.log"));
            // Diagnostic root TRACE must not disclose private JDBC-bound identity/credentials.
            assertFalse(
                    log.contains("parameter value [" + ACTOR + ']'),
                    "Identity JDBC TRACE disclosed a bound recipient identity");
            assertFalse(log.contains(token), "Identity logs disclosed a SERVICE bearer");
            assertFalse(log.contains(userToken), "Identity logs disclosed USER evidence");
            assertFalse(
                    java.util.regex.Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                            .matcher(log)
                            .find(),
                    "Identity diagnostic log contains a compact JWT");
            assertFalse(log.contains(password), "Identity logs disclosed TLS key-store credentials");
            assertFalse(log.contains(SECRET), "Identity logs disclosed a client credential");
            for (String name : List.of("assessment", "quiz", "classroom")) {
                String label = "acceptance-diagnostic-" + name;
                try {
                    String endpoint = peer(name, label, a -> false, List.of("--logging.level.root=TRACE"));
                    String fresh = service("assessment-service", SECRET, "identity.notice-recipient.read");
                    var probe = send("GET", endpoint + "/actuator/health", fresh, null, Map.of());
                    assertEquals(200, probe.statusCode());
                    String diagnostic = Files.readString(logs.resolve(label + ".log"));
                    assertFalse(diagnostic.contains(fresh), name + " logged a raw SERVICE bearer");
                } finally {
                    stopPeer(label);
                }
            }
        } finally {
            stopPeer("acceptance-identity-https");
            Files.deleteIfExists(store);
        }
    }
}
