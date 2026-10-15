package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.configuration.ProctoringAuthorityProperties;
import com.quizopia.assessment.infrastructure.*;
import com.quizopia.assessment.security.admission.AdmissionProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/** Real bounded HTTP/OAuth adapter; remote services are explicit protocol doubles. */
class ProctoringTargetClientTest {
    HttpServer server;
    ProctoringTargetClient client;
    UUID classroom = UUID.randomUUID(), student = UUID.randomUUID();
    Instant start = Instant.now().minusSeconds(60);
    String identity, membership;
    int identityStatus = 200, membershipStatus = 200;
    AtomicInteger identityCalls = new AtomicInteger(), membershipCalls = new AtomicInteger();
    List<String> scopes = new ArrayList<>();

    String identity(boolean eligible, Instant checked) {
        return "{\"studentId\":\"" + student + "\",\"attemptStartedAt\":\"" + start + "\",\"eligible\":" + eligible
                + ",\"checkedAt\":\"" + checked + "\"}";
    }

    @BeforeEach
    void setup() throws Exception {
        identity = identity(true, Instant.now());
        membership = "{\"classroomId\":\"" + classroom + "\",\"studentId\":\"" + student + "\",\"checkedAt\":\""
                + Instant.now() + "\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            String response;
            if (path.equals("/oauth2/token")) {
                scopes.add(URLDecoder.decode(request, StandardCharsets.UTF_8));
                response = "{\"access_token\":\"synthetic-service-token\",\"token_type\":\"Bearer\",\"expires_in\":60}";
            } else if (path.equals("/internal/security/proctoring-students/" + student + "/eligibility")) {
                identityCalls.incrementAndGet();
                assertNull(exchange.getRequestHeaders().getFirst("X-Quizopia-User-Evidence"));
                assertEquals("{\"attemptStartedAt\":\"" + start + "\"}", request);
                response = identity;
                status = identityStatus;
            } else {
                assertEquals(
                        "/internal/classrooms/" + classroom + "/proctoring-students/" + student + "/membership", path);
                membershipCalls.incrementAndGet();
                assertEquals(
                        "synthetic-teacher-evidence",
                        exchange.getRequestHeaders().getFirst("X-Quizopia-User-Evidence"));
                assertEquals("", request);
                response = membership;
                status = membershipStatus;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var admission = new AdmissionProperties();
        admission.setEnabled(true);
        admission.setAllowInsecureLoopback(true);
        admission.setIdentityBaseUrl(uri);
        admission.setClientId("assessment-service");
        admission.setClientSecret("synthetic-only");
        var sources = new SourceProperties();
        sources.setEnabled(true);
        sources.setQuizBaseUrl(uri);
        sources.setClassroomBaseUrl(uri);
        client = new ProctoringTargetClient(
                new ProctoringAuthorityProperties(true, "proctoring", true, true), sources, admission);
    }

    @AfterEach
    void cleanup() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    void check() {
        client.require(classroom, student, start, "synthetic-teacher-evidence");
    }

    void fails(int status) {
        assertEquals(status, assertThrows(PublicationFailure.class, this::check).status());
    }

    @Test
    void distinctScopesFreshEligibilityAndTeacherOnlyEvidenceForwarding() {
        check();
        check();
        assertEquals(2, identityCalls.get());
        assertEquals(2, membershipCalls.get());
        assertTrue(scopes.stream().anyMatch(s -> s.contains("scope=identity.proctoring-student.read")));
        assertTrue(scopes.stream().anyMatch(s -> s.contains("scope=classroom.proctoring-membership.read")));
        assertFalse(scopes.stream().anyMatch(s -> s.contains("notice-recipient")));
    }

    @Test
    void identityDenialAndUnavailableNeverReachMembership() {
        identity = identity(false, Instant.now());
        fails(404);
        identityStatus = 503;
        identity = "{\"code\":\"AUTHORITY_UNAVAILABLE\"}";
        fails(503);
        identityStatus = 401;
        identity = "{\"code\":\"USER_EVIDENCE_INVALID\"}";
        fails(503);
        assertEquals(0, membershipCalls.get());
    }

    @Test
    void strictBindingFreshnessAndClosedResponseRejectUntrustedAuthority() {
        for (String malformed : List.of(
                identity(true, Instant.now().minusSeconds(60)),
                identity(true, Instant.now())
                        .replace(student.toString(), UUID.randomUUID().toString()),
                identity(true, Instant.now())
                        .replace(start.toString(), start.minusSeconds(1).toString()),
                identity(true, Instant.now()).replace("true", "\"true\""),
                identity(true, Instant.now()).replace("}", ",\"profile\":\"forbidden\"}"),
                identity(true, Instant.now()) + "{}")) {
            identity = malformed;
            fails(503);
        }
        assertEquals(0, membershipCalls.get());
    }

    @Test
    void membershipMaskingAndStudentBindingOutageAreSanitized() {
        membershipStatus = 404;
        membership = "{\"code\":\"CLASSROOM_NOT_ACCESSIBLE\"}";
        fails(404);
        membershipStatus = 503;
        membership = "{\"code\":\"AUTHORITY_UNAVAILABLE\"}";
        fails(503);
        membershipStatus = 200;
        membership = "{\"classroomId\":\"" + classroom + "\",\"studentId\":\"" + UUID.randomUUID()
                + "\",\"checkedAt\":\"" + Instant.now() + "\"}";
        fails(503);
    }

    @Test
    void unconfiguredTargetCapabilityNeverFallsBackToJwtOrNoticeScope() {
        try (var disabled = new ProctoringTargetClient(
                new ProctoringAuthorityProperties(true, "proctoring", true, false),
                new SourceProperties(),
                new AdmissionProperties())) {
            assertEquals(
                    503,
                    assertThrows(
                                    PublicationFailure.class,
                                    () -> disabled.require(classroom, student, start, "synthetic"))
                            .status());
            assertEquals(0, identityCalls.get());
        }
    }
}
