package com.quizopia.classroom;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.classroom.security.admission.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Real HTTP/OAuth wire tests with isolated dependency stubs, not the full Identity topology. */
class IdentityAdmissionClientTest {
    private static final String ACTOR = "00000000-0000-0000-0000-000000000001";
    private static final String GOOD = "{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\",\"TEACHER\"]}";
    private final AtomicInteger tokens = new AtomicInteger();
    private final AtomicInteger admissions = new AtomicInteger();
    private final AtomicReference<String> tokenBody = new AtomicReference<>(
            "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"identity.user-admission\"}");
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> evidence = new AtomicReference<>();
    private final AtomicReference<String> form = new AtomicReference<>();
    private final AtomicReference<String> basic = new AtomicReference<>();

    DisposableServer server(int status, String body, boolean slow) {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    if (request.uri().equals("/oauth2/token")) {
                        tokens.incrementAndGet();
                        basic.set(request.requestHeaders().get("Authorization"));
                        return request.receive().aggregate().asString().flatMap(value -> {
                            form.set(value);
                            return response.header("Content-Type", "application/json")
                                    .sendString(Mono.just(tokenBody.get()))
                                    .then();
                        });
                    }
                    assertEquals("/internal/security/user-admissions", request.uri());
                    assertNull(request.requestHeaders().get("Cookie"));
                    admissions.incrementAndGet();
                    authorization.set(request.requestHeaders().get("Authorization"));
                    evidence.set(request.requestHeaders().get("X-Quizopia-User-Evidence"));
                    return response.status(status)
                            .header("Content-Type", "application/json")
                            .sendString(slow ? Mono.never() : Mono.just(body));
                })
                .bindNow();
    }

    AdmissionProperties properties(int port) {
        var p = new AdmissionProperties();
        p.setEnabled(true);
        p.setAllowInsecureLoopback(true);
        p.setIdentityBaseUrl(URI.create("http://127.0.0.1:" + port));
        p.setClientId("test-service");
        p.setClientSecret("fixture-secret");
        p.setConnectTimeout(Duration.ofMillis(100));
        p.setResponseTimeout(Duration.ofMillis(250));
        p.setTotalTimeout(Duration.ofMillis(500));
        p.setMaxConnections(2);
        p.setPendingAcquires(2);
        return p;
    }

    Jwt user() {
        return Jwt.withTokenValue("original.payload.signature")
                .header("alg", "RS256")
                .subject(ACTOR)
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("principal_type", "USER")
                .claim("roles", List.of("STUDENT", "TEACHER"))
                .build();
    }

    @Test
    void dualCredentialsServiceTokenReuseAndNoPositiveAdmissionCache() {
        var server = server(200, GOOD, false);
        try (var client = new IdentityAdmissionClient(properties(server.port()))) {
            client.admit(user(), "TEACHER").block();
            client.admit(user(), "TEACHER").block();
            assertEquals(1, tokens.get());
            assertEquals(2, admissions.get());
            assertEquals("Bearer service-token", authorization.get());
            assertEquals("original.payload.signature", evidence.get());
            assertTrue(form.get().contains("grant_type=client_credentials"));
            assertTrue(form.get().contains("scope=identity.user-admission"));
            assertTrue(basic.get().startsWith("Basic "));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void nearExpiryServiceTokenIsRenewed() {
        tokenBody.set(tokenBody.get().replace("3600", "1"));
        var server = server(200, GOOD, false);
        try (var client = new IdentityAdmissionClient(properties(server.port()))) {
            client.admit(user(), "TEACHER").block();
            client.admit(user(), "TEACHER").block();
            assertEquals(2, tokens.get());
            assertEquals(2, admissions.get());
        } finally {
            server.disposeNow();
        }
    }

    record ErrorCase(int status, String body, int expected) {}

    static Stream<ErrorCase> failures() {
        return Stream.of(
                new ErrorCase(401, "{\"code\":\"USER_EVIDENCE_INVALID\"}", 401),
                new ErrorCase(401, "{\"code\":\"SERVICE_AUTHENTICATION_INVALID\"}", 503),
                new ErrorCase(403, "{\"code\":\"SERVICE_CALLER_FORBIDDEN\"}", 503),
                new ErrorCase(400, "{\"code\":\"INVALID_ADMISSION_REQUEST\"}", 503),
                new ErrorCase(503, "{\"code\":\"AUTHORITY_UNAVAILABLE\"}", 503),
                new ErrorCase(404, "{}", 503),
                new ErrorCase(302, "{}", 503),
                new ErrorCase(200, "not-json", 503),
                new ErrorCase(200, "null", 503),
                new ErrorCase(200, GOOD.replace(ACTOR, UUID.randomUUID().toString()), 503),
                new ErrorCase(200, GOOD.replace("STUDENT", "UNKNOWN"), 503),
                new ErrorCase(200, GOOD.replace("[\"STUDENT\",\"TEACHER\"]", "null"), 503),
                new ErrorCase(200, GOOD.replace("STUDENT", "TEACHER"), 503),
                new ErrorCase(200, GOOD.replace("[\"STUDENT\",\"TEACHER\"]", "[\"TEACHER\",\"STUDENT\"]"), 503),
                new ErrorCase(200, GOOD.replace("[\"STUDENT\",\"TEACHER\"]", "[\"STUDENT\"]"), 403),
                new ErrorCase(200, GOOD.replace("}", ",\"extra\":true}"), 503),
                new ErrorCase(200, GOOD.replace("}", ",\"actorId\":\"" + ACTOR + "\"}"), 503),
                new ErrorCase(200, GOOD + "{}", 503),
                new ErrorCase(200, "x".repeat(5000), 503),
                new ErrorCase(401, "{\"code\":\"USER_EVIDENCE_INVALID\",\"extra\":true}", 503));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void sanitizedClassifications(ErrorCase value) {
        var server = server(value.status(), value.body(), false);
        try (var client = new IdentityAdmissionClient(properties(server.port()))) {
            var error = assertThrows(AdmissionFailure.class, () -> client.admit(user(), "TEACHER")
                    .block());
            assertEquals(value.expected(), error.status());
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("original"));
            assertFalse(error.getMessage().contains("127.0.0.1"));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void boundedPoolTimeoutUnderConcurrentFailures() {
        var server = server(200, GOOD, true);
        try (var client = new IdentityAdmissionClient(properties(server.port()))) {
            long start = System.nanoTime();
            var outcomes = Flux.range(0, 20)
                    .flatMap(
                            i -> client.admit(user(), "TEACHER")
                                    .thenReturn(0)
                                    .onErrorResume(AdmissionFailure.class, failure -> Mono.just(failure.status())),
                            20)
                    .collectList()
                    .block(Duration.ofSeconds(3));
            assertEquals(20, outcomes.size());
            assertTrue(outcomes.stream().allMatch(status -> status == 503));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void refusalAndDisabledConfigurationCannotAllow() {
        var server = server(200, GOOD, false);
        int port = server.port();
        server.disposeNow();
        try (var client = new IdentityAdmissionClient(properties(port))) {
            assertEquals(
                    503,
                    assertThrows(AdmissionFailure.class, () -> client.admit(user(), "TEACHER")
                                    .block())
                            .status());
        }
        try (var client = new IdentityAdmissionClient(new AdmissionProperties())) {
            assertEquals(
                    503,
                    assertThrows(AdmissionFailure.class, () -> client.admit(user(), "TEACHER")
                                    .block())
                            .status());
        }
    }

    @Test
    void destinationAndBoundsAreValidatedWithoutExposingSecrets() {
        var p = properties(1);
        p.setAllowInsecureLoopback(false);
        assertThrows(IllegalStateException.class, p::validate);
        p.setAllowInsecureLoopback(true);
        p.setIdentityBaseUrl(URI.create("http://attacker.example"));
        assertThrows(IllegalStateException.class, p::validate);
        p.setIdentityBaseUrl(URI.create("https://identity.example/?token=secret"));
        assertThrows(IllegalStateException.class, p::validate);
        p.setIdentityBaseUrl(URI.create("https://identity.example"));
        p.validate();
        p.setTotalTimeout(Duration.ofSeconds(31));
        assertThrows(IllegalStateException.class, p::validate);
    }
}
