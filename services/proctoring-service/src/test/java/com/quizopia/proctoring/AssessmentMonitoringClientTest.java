package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.infrastructure.AssessmentMonitoringClient;
import com.quizopia.proctoring.security.admission.AdmissionProperties;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Actual OAuth/private HTTP transport with explicit authority doubles; not deployment evidence. */
class AssessmentMonitoringClientTest {
    static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID PUBLICATION = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID CLASSROOM = UUID.fromString("00000000-0000-0000-0000-000000000004");
    AtomicInteger tokens = new AtomicInteger(), checks = new AtomicInteger();
    AtomicReference<String> wireBody = new AtomicReference<>(),
            wireEvidence = new AtomicReference<>(),
            tokenForm = new AtomicReference<>();
    AtomicReference<String> response = new AtomicReference<>();
    AtomicReference<String> contentType = new AtomicReference<>("application/json");
    AtomicInteger status = new AtomicInteger(200);

    MonitoringAuthority.Actor actor() {
        return new MonitoringAuthority.Actor(
                ACTOR,
                Set.of("STUDENT", "TEACHER"),
                "original.payload.signature",
                Instant.now().plusSeconds(300));
    }

    String good() {
        return "{\"actorId\":\"" + ACTOR + "\",\"viewerRole\":\"STUDENT\",\"attemptId\":\"" + ATTEMPT
                + "\",\"publicationId\":\"" + PUBLICATION + "\",\"classroomId\":\"" + CLASSROOM + "\",\"studentId\":\""
                + ACTOR + "\",\"active\":true,\"deadlineAt\":\"" + Instant.now().plusSeconds(120)
                + "\",\"checkedAt\":\"" + Instant.now() + "\"}";
    }

    DisposableServer server(boolean slow) {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((req, res) -> {
                    if (req.uri().equals("/oauth2/token")) {
                        tokens.incrementAndGet();
                        assertTrue(req.requestHeaders().get("Authorization").startsWith("Basic "));
                        return req.receive().aggregate().asString().flatMap(form -> {
                            tokenForm.set(form);
                            return res.header("Content-Type", "application/json")
                                    .sendString(
                                            Mono.just(
                                                    "{\"access_token\":\"synthetic-service-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"assessment.proctoring-authority.read\"}"))
                                    .then();
                        });
                    }
                    assertEquals("/internal/proctoring/attempts/" + ATTEMPT + "/authority", req.uri());
                    assertEquals(
                            "Bearer synthetic-service-token",
                            req.requestHeaders().get("Authorization"));
                    assertNull(req.requestHeaders().get("Cookie"));
                    wireEvidence.set(req.requestHeaders().get("X-Quizopia-User-Evidence"));
                    checks.incrementAndGet();
                    return req.receive().aggregate().asString().flatMap(body -> {
                        wireBody.set(body);
                        return res.status(status.get())
                                .header("Content-Type", contentType.get())
                                .sendString(slow ? Mono.never() : Mono.just(response.get()))
                                .then();
                    });
                })
                .bindNow();
    }

    AdmissionProperties bounds(int port) {
        var p = new AdmissionProperties();
        p.setEnabled(true);
        p.setAllowInsecureLoopback(true);
        p.setIdentityBaseUrl(URI.create("http://127.0.0.1:" + port));
        p.setClientId("proctoring");
        p.setClientSecret("synthetic-secret");
        p.setConnectTimeout(Duration.ofMillis(200));
        p.setResponseTimeout(Duration.ofMillis(400));
        p.setTotalTimeout(Duration.ofMillis(800));
        return p;
    }

    MonitoringProperties monitoring(int port) {
        var p = new MonitoringProperties();
        p.setEnabled(true);
        p.setAssessmentBaseUrl(URI.create("http://127.0.0.1:" + port));
        return p;
    }

    @Test
    void everyOperationChecksFreshAuthorityWhileServiceTokenIsReused() {
        response.set(good());
        var server = server(false);
        try (var client = new AssessmentMonitoringClient(monitoring(server.port()), bounds(server.port()))) {
            assertTrue(client.check(actor(), ATTEMPT, "STUDENT").active());
            response.set(good().replace("\"active\":true", "\"active\":false"));
            assertFalse(client.check(actor(), ATTEMPT, "STUDENT").active());
            assertEquals(1, tokens.get());
            assertEquals(2, checks.get());
            assertEquals("original.payload.signature", wireEvidence.get());
            assertEquals("{\"viewerRole\":\"STUDENT\"}", wireBody.get());
            assertTrue(tokenForm.get().contains("scope=assessment.proctoring-authority.read"));
        } finally {
            server.disposeNow();
        }
    }

    static Stream<String> malformed() {
        return Stream.of(
                "extra",
                "duplicate",
                "wrongActor",
                "wrongAttempt",
                "wrongStudent",
                "wrongRole",
                "stale",
                "future",
                "malformedUuid",
                "badActive",
                "badDeadline",
                "trailing",
                "oversized",
                "missing");
    }

    @ParameterizedTest
    @MethodSource("malformed")
    void untrustedOrOverbroadProjectionNeverAllows(String kind) {
        String body = good();
        body = switch (kind) {
            case "extra" -> body.replace("{", "{\"email\":\"private@example.test\",");
            case "duplicate" -> body.replace("{", "{\"active\":false,");
            case "wrongActor" -> body.replace("\"actorId\":\"" + ACTOR, "\"actorId\":\"" + PUBLICATION);
            case "wrongAttempt" -> body.replace(ATTEMPT.toString(), PUBLICATION.toString());
            case "wrongStudent" -> body.replace("\"studentId\":\"" + ACTOR, "\"studentId\":\"" + PUBLICATION);
            case "wrongRole" -> body.replace("STUDENT", "TEACHER");
            case "stale" ->
                body.replaceFirst(
                        "\"checkedAt\":\"[^\"]+\"",
                        "\"checkedAt\":\"" + Instant.now().minusSeconds(30) + "\"");
            case "future" ->
                body.replaceFirst(
                        "\"checkedAt\":\"[^\"]+\"",
                        "\"checkedAt\":\"" + Instant.now().plusSeconds(30) + "\"");
            case "malformedUuid" -> body.replace(CLASSROOM.toString(), "1-1-1-1-1");
            case "badActive" -> body.replace("\"active\":true", "\"active\":\"true\"");
            case "badDeadline" ->
                body.replaceFirst(
                        "\"deadlineAt\":\"[^\"]+\"",
                        "\"deadlineAt\":\"" + Instant.now().minusSeconds(30) + "\"");
            case "trailing" -> body + "{}";
            case "oversized" -> " ".repeat(5000) + body;
            case "missing" -> "{}";
            default -> throw new IllegalArgumentException();
        };
        response.set(body);
        var server = server(false);
        try (var client = new AssessmentMonitoringClient(monitoring(server.port()), bounds(server.port()))) {
            assertEquals(
                    503,
                    assertThrows(MonitoringFailure.class, () -> client.check(actor(), ATTEMPT, "STUDENT"))
                            .status());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void wrongMediaTypeAndUnrecognizedFailureAreUnavailable() {
        response.set(good());
        contentType.set("text/plain");
        var server = server(false);
        try (var client = new AssessmentMonitoringClient(monitoring(server.port()), bounds(server.port()))) {
            assertEquals(
                    503,
                    assertThrows(MonitoringFailure.class, () -> client.check(actor(), ATTEMPT, "STUDENT"))
                            .status());
            contentType.set("application/json");
            status.set(404);
            response.set("{\"code\":\"untrusted-private-detail\"}");
            assertEquals(
                    "AUTHORITY_UNAVAILABLE",
                    assertThrows(MonitoringFailure.class, () -> client.check(actor(), ATTEMPT, "STUDENT"))
                            .code());
            status.set(401);
            response.set("{\"code\":\"UNAUTHENTICATED\"}");
            assertEquals(
                    503,
                    assertThrows(MonitoringFailure.class, () -> client.check(actor(), ATTEMPT, "STUDENT"))
                            .status());
            response.set("{\"code\":\"USER_EVIDENCE_INVALID\"}");
            assertEquals(
                    401,
                    assertThrows(MonitoringFailure.class, () -> client.check(actor(), ATTEMPT, "STUDENT"))
                            .status());
            status.set(404);
            response.set("{\"code\":\"PROCTORING_NOT_ACCESSIBLE\"}");
            assertEquals(
                    404,
                    assertThrows(MonitoringFailure.class, () -> client.check(actor(), ATTEMPT, "STUDENT"))
                            .status());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void authorityTimeoutIsBoundedAndExpiredUserCannotSucceed() {
        response.set(good());
        var server = server(true);
        try (var client = new AssessmentMonitoringClient(monitoring(server.port()), bounds(server.port()))) {
            long start = System.nanoTime();
            assertEquals(
                    503,
                    assertThrows(MonitoringFailure.class, () -> client.check(actor(), ATTEMPT, "STUDENT"))
                            .status());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
        } finally {
            server.disposeNow();
        }
        var fast = server(false);
        try (var client = new AssessmentMonitoringClient(monitoring(fast.port()), bounds(fast.port()))) {
            var expired = new MonitoringAuthority.Actor(
                    ACTOR,
                    Set.of("STUDENT"),
                    "original.payload.signature",
                    Instant.now().minusSeconds(1));
            assertEquals(
                    503,
                    assertThrows(MonitoringFailure.class, () -> client.check(expired, ATTEMPT, "STUDENT"))
                            .status());
        } finally {
            fast.disposeNow();
        }
    }

    @Test
    void productionEnablementRemainsClosedUntilActualBundleAndPolicyIntegration() {
        var p = monitoring(1);
        p.setAssessmentBaseUrl(URI.create("https://assessment.internal"));
        assertThrows(IllegalStateException.class, () -> p.validate(true, false));
        p.setRetentionDays(1);
        p.setRetentionPolicyReference("synthetic-policy");
        p.setPrivacyApprovalReference("synthetic-review");
        assertThrows(IllegalStateException.class, () -> p.validate(true, false));
        p.setAssessmentBaseUrl(URI.create("http://outside.invalid"));
        assertThrows(IllegalStateException.class, () -> p.validate(false, true));
        p.setAssessmentBaseUrl(URI.create("https://user:credential@assessment.internal"));
        assertThrows(IllegalStateException.class, () -> p.validate(false, false));
    }
}
