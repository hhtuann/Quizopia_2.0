package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.gateway.realtime.RealtimeIngressFilter;
import com.quizopia.gateway.realtime.invalidation.RealtimeInvalidationConsumer;
import com.rabbitmq.client.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real RabbitMQ consumer with the contract-replica Assessment authority stub: hints fan out only to
 * this instance's authorized subscriptions after fresh revalidation, and REST remains authoritative.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import(RealtimeInvalidationFanoutIntegrationTest.Keys.class)
class RealtimeInvalidationFanoutIntegrationTest {
    private static final String ORIGIN = "https://quizopia.test";
    private static final String ISSUER = "https://identity.realtime-invalidation.test";
    private static final String ACTOR = "00000000-0000-0000-0000-000000000001";
    private static final String ADMISSION_CLIENT = "gateway-admission-test";
    private static final String ADMISSION_SECRET = "admission-test-secret";
    private static final String AUTHORITY_CLIENT = "gateway-realtime-test";
    private static final String AUTHORITY_SECRET = "authority-test-secret";
    private static final String AUTHORITY_SCOPE = "assessment.realtime.subscription-authorize";
    private static final String AUTHORITY_AUDIENCE = "quizopia-assessment-realtime-authority";
    private static final String EXCHANGE = "quizopia.realtime.invalidation.v1";
    private static final String CONSUMER_USER = "gateway-invalidation-test";
    private static final String CONSUMER_PASSWORD = UUID.randomUUID().toString();
    private static final com.nimbusds.jose.jwk.RSAKey KEY = key();
    private static final RSAPublicKey PUBLIC_KEY = publicKey();
    private static final JwtEncoder ENCODER = new NimbusJwtEncoder(
            new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(KEY)));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final AtomicInteger AUTHORITY_CALLS = new AtomicInteger();
    static final AtomicReference<String> AUTHORITY_STATUS = new AtomicReference<>("DECIDE");
    static final DisposableServer STUB = stub();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.1-management")
            .withEnv("RABBITMQ_DEFAULT_USER", UUID.randomUUID().toString())
            .withEnv("RABBITMQ_DEFAULT_PASS", UUID.randomUUID().toString())
            .withEnv(
                    "RABBITMQ_DEFAULT_VHOST",
                    "invalidation_" + UUID.randomUUID().toString().replace("-", ""))
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    static String vhost() {
        return RABBIT.getEnvMap().get("RABBITMQ_DEFAULT_VHOST");
    }

    static String adminUser() {
        return RABBIT.getEnvMap().get("RABBITMQ_DEFAULT_USER");
    }

    static String adminPassword() {
        return RABBIT.getEnvMap().get("RABBITMQ_DEFAULT_PASS");
    }

    static Connection admin() throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getMappedPort(5672));
        factory.setVirtualHost(vhost());
        factory.setUsername(adminUser());
        factory.setPassword(adminPassword());
        return factory.newConnection("invalidation-test-admin");
    }

    @BeforeAll
    static void topology() throws Exception {
        try (var connection = admin();
                var channel = connection.createChannel()) {
            channel.exchangeDeclare(EXCHANGE, "direct", true);
        }
        RABBIT.execInContainer("rabbitmqctl", "add_user", CONSUMER_USER, CONSUMER_PASSWORD);
        // Least privilege: configure/write only its own transient queue; read only that queue and
        // the invalidation exchange (required by queue.bind). Publishing events stays forbidden.
        RABBIT.execInContainer(
                "rabbitmqctl",
                "set_permissions",
                "-p",
                vhost(),
                CONSUMER_USER,
                "^quizopia\\.gateway\\.realtime-invalidation",
                "^quizopia\\.gateway\\.realtime-invalidation",
                "^(quizopia\\.realtime\\.invalidation\\.v1|quizopia\\.gateway\\.realtime-invalidation)");
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RealtimeInvalidationConsumer consumer;

    @BeforeEach
    void reset() throws Exception {
        AUTHORITY_STATUS.set("DECIDE");
        AUTHORITY_CALLS.set(0);
        awaitConsumer();
    }

    void awaitConsumer() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!consumer.isConnected() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(consumer.isConnected(), "Consumer must connect to the declared topology");
    }

    void publish(String eventType, String resourceKind, UUID resourceId, UUID eventId) throws Exception {
        try (var connection = admin();
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

    String ticket(String token) {
        var response = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build()
                .post()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return JSON.readTree(new String(response, StandardCharsets.UTF_8))
                .path("ticket")
                .asString();
    }

    static String subscribe(String kind, UUID resourceId) {
        return "{\"protocolVersion\":1,\"type\":\"SUBSCRIBE\",\"requestId\":\""
                + UUID.randomUUID()
                + "\",\"resource\":{\"kind\":\""
                + kind
                + "\",\"id\":\""
                + resourceId
                + "\"}}";
    }

    static String unsubscribe(String subscriptionId) {
        return "{\"protocolVersion\":1,\"type\":\"UNSUBSCRIBE\",\"requestId\":\""
                + UUID.randomUUID()
                + "\",\"subscriptionId\":\""
                + subscriptionId
                + "\"}";
    }

    static String auth(String ticket, String token) {
        return "{\"protocolVersion\":1,\"type\":\"AUTH\",\"ticket\":\""
                + ticket
                + "\",\"accessToken\":\""
                + token
                + "\"}";
    }

    static String subscriptionId(String subscribedFrame) {
        return JSON.readTree(subscribedFrame).path("subscriptionId").asString();
    }

    /** Single-connection scripted client: sequential sends with sequential response reads. */
    private final class ScriptedWebSocket implements AutoCloseable {
        final java.util.concurrent.BlockingQueue<String> incoming = new LinkedBlockingQueue<>();
        final java.net.http.WebSocket socket;
        final StringBuilder partial = new StringBuilder();

        ScriptedWebSocket() {
            java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
                @Override
                public void onOpen(java.net.http.WebSocket webSocket) {
                    webSocket.request(1);
                }

                @Override
                public java.util.concurrent.CompletionStage<?> onText(
                        java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                    partial.append(data);
                    if (last) {
                        incoming.add(partial.toString());
                        partial.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }
            };
            socket = java.net.http.HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .header(HttpHeaders.ORIGIN, ORIGIN)
                    .subprotocols(RealtimeIngressFilter.SUBPROTOCOL)
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + RealtimeIngressFilter.WEBSOCKET_PATH), listener)
                    .join();
        }

        void send(String frame) {
            socket.sendText(frame, true).join();
        }

        String next() throws InterruptedException {
            String message = incoming.poll(10, TimeUnit.SECONDS);
            assertNotNull(message, "Expected another frame");
            return message;
        }

        String quiet(long milliseconds) throws InterruptedException {
            String message = incoming.poll(milliseconds, TimeUnit.MILLISECONDS);
            return message;
        }

        @Override
        public void close() {
            try {
                socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "").join();
            } catch (RuntimeException alreadyClosed) {
                // The server may have closed the session first; teardown must not mask failures.
            }
        }
    }

    ScriptedWebSocket authenticated(String token) throws Exception {
        var socket = new ScriptedWebSocket();
        socket.send(auth(ticket(token), token));
        assertTrue(socket.next().contains("\"type\":\"WELCOME\""));
        assertEquals("{\"protocolVersion\":1,\"type\":\"AUTHENTICATED\"}", socket.next());
        return socket;
    }

    String userToken() {
        Instant now = Instant.now();
        return sign(
                ACTOR,
                "USER",
                Map.of("roles", List.of("STUDENT", "TEACHER")),
                now.minusSeconds(2),
                now.plusSeconds(300));
    }

    @Test
    void authorizedHintFansOutOnlyToItsOwnSubscribers(CapturedOutput output) throws Exception {
        UUID attempt = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        String token = userToken();
        try (var first = authenticated(token)) {
            first.send(subscribe("ATTEMPT_OWNER", attempt));
            String subscribed = first.next();
            assertTrue(subscribed.contains("\"type\":\"SUBSCRIBED\""), subscribed);
            String subscriptionId = subscriptionId(subscribed);
            try (var second = authenticated(token)) {
                second.send(subscribe("PUBLICATION_TEACHER", publication));
                assertTrue(second.next().contains("\"type\":\"SUBSCRIBED\""));

                UUID eventId = UUID.randomUUID();
                publish("ATTEMPT_INVALIDATED", "ATTEMPT", attempt, eventId);
                String hint = first.next();
                assertTrue(hint.contains("\"type\":\"RESOURCE_INVALIDATED\""), hint);
                assertTrue(hint.contains(eventId.toString()));
                assertTrue(hint.contains(subscriptionId));
                assertTrue(hint.contains(attempt.toString()));
                assertTrue(hint.contains("\"reason\":\"CHANGED\""));
                assertTrue(hint.contains("\"occurredAt\":\""));
                // Cross-resource isolation: the other connection receives nothing.
                assertNull(second.quiet(1500));
                assertNull(first.quiet(500));
            }
        }
        assertFalse(output.getAll().contains(token));
        assertFalse(output.getAll().contains(AUTHORITY_SECRET));
        assertFalse(output.getAll().contains(CONSUMER_PASSWORD));
    }

    @Test
    void deniedRevokesUnsubscribesAndStopsFutureFrames() throws Exception {
        UUID attempt = UUID.randomUUID();
        String token = userToken();
        try (var socket = authenticated(token)) {
            socket.send(subscribe("ATTEMPT_OWNER", attempt));
            assertTrue(socket.next().contains("\"type\":\"SUBSCRIBED\""));
            AUTHORITY_STATUS.set("404");
            publish("ATTEMPT_INVALIDATED", "ATTEMPT", attempt, UUID.randomUUID());
            String revoked = socket.next();
            assertTrue(revoked.contains("\"type\":\"SUBSCRIPTION_REVOKED\""), revoked);
            assertTrue(revoked.contains("\"code\":\"NOT_ACCESSIBLE\""));
            AUTHORITY_STATUS.set("DECIDE");
            publish("ATTEMPT_INVALIDATED", "ATTEMPT", attempt, UUID.randomUUID());
            assertNull(socket.quiet(1500));
        }
    }

    @Test
    void unavailableAuthorityRequestsResyncWithoutLosingTheSubscription() throws Exception {
        UUID publication = UUID.randomUUID();
        try (var socket = authenticated(userToken())) {
            socket.send(subscribe("PUBLICATION_TEACHER", publication));
            assertTrue(socket.next().contains("\"type\":\"SUBSCRIBED\""));
            AUTHORITY_STATUS.set("503");
            publish("PUBLICATION_INVALIDATED", "PUBLICATION", publication, UUID.randomUUID());
            String resync = socket.next();
            assertTrue(resync.contains("\"type\":\"RESYNC_REQUIRED\""), resync);
            assertTrue(resync.contains("\"subscriptionId\""));
            AUTHORITY_STATUS.set("DECIDE");
            publish("PUBLICATION_INVALIDATED", "PUBLICATION", publication, UUID.randomUUID());
            assertTrue(socket.next().contains("\"type\":\"RESOURCE_INVALIDATED\""));
        }
    }

    @Test
    void staleEvidenceRevokesAndClosesTheConnection() throws Exception {
        UUID attempt = UUID.randomUUID();
        try (var socket = authenticated(userToken())) {
            socket.send(subscribe("ATTEMPT_OWNER", attempt));
            assertTrue(socket.next().contains("\"type\":\"SUBSCRIBED\""));
            AUTHORITY_STATUS.set("USER_EVIDENCE_INVALID");
            publish("ATTEMPT_INVALIDATED", "ATTEMPT", attempt, UUID.randomUUID());
            String revoked = socket.next();
            assertTrue(revoked.contains("\"type\":\"SUBSCRIPTION_REVOKED\""), revoked);
            assertTrue(revoked.contains("\"code\":\"TOKEN_EXPIRED\""));
            assertNull(socket.quiet(3000), "No further frames after the session close");
        }
    }

    @Test
    void duplicatesInvalidSchemaAndForeignResourcesAreDropped() throws Exception {
        UUID attempt = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        try (var socket = authenticated(userToken())) {
            socket.send(subscribe("ATTEMPT_OWNER", attempt));
            assertTrue(socket.next().contains("\"type\":\"SUBSCRIBED\""));
            UUID eventId = UUID.randomUUID();
            publish("ATTEMPT_INVALIDATED", "ATTEMPT", attempt, eventId);
            assertTrue(socket.next().contains("\"type\":\"RESOURCE_INVALIDATED\""));
            publish("ATTEMPT_INVALIDATED", "ATTEMPT", attempt, eventId);
            assertNull(socket.quiet(1500), "Duplicate eventId must be deduplicated");

            try (var connection = admin();
                    var channel = connection.createChannel()) {
                String invalid = "{\"eventId\":\""
                        + UUID.randomUUID()
                        + "\",\"eventType\":\"ATTEMPT_INVALIDATED\",\"eventVersion\":2,\"resourceKind\":\"ATTEMPT\",\"resourceId\":\""
                        + attempt + "\",\"occurredAt\":\"" + Instant.now() + "\"}";
                channel.basicPublish(
                        EXCHANGE, "attempt.invalidated.v1", null, invalid.getBytes(StandardCharsets.UTF_8));
                String extra = "{\"eventId\":\""
                        + UUID.randomUUID()
                        + "\",\"eventType\":\"ATTEMPT_INVALIDATED\",\"eventVersion\":1,\"resourceKind\":\"ATTEMPT\",\"resourceId\":\""
                        + attempt + "\",\"occurredAt\":\"" + Instant.now() + "\",\"answer\":\"leak\"}";
                channel.basicPublish(EXCHANGE, "attempt.invalidated.v1", null, extra.getBytes(StandardCharsets.UTF_8));
            }
            publish("ATTEMPT_INVALIDATED", "ATTEMPT", foreign, UUID.randomUUID());
            assertNull(socket.quiet(1500), "Invalid and unmatched events must never produce frames");

            publish("ATTEMPT_INVALIDATED", "ATTEMPT", attempt, UUID.randomUUID());
            assertTrue(socket.next().contains("\"type\":\"RESOURCE_INVALIDATED\""), "Connection stays usable");
        }
    }

    @Test
    void unsubscribedAndTornDownConnectionsReceiveNothing() throws Exception {
        UUID attempt = UUID.randomUUID();
        try (var socket = authenticated(userToken())) {
            socket.send(subscribe("ATTEMPT_OWNER", attempt));
            String subscribed = socket.next();
            String subscriptionId = subscriptionId(subscribed);
            socket.send(unsubscribe(subscriptionId));
            assertTrue(socket.next().contains("\"type\":\"UNSUBSCRIBED\""));
            publish("ATTEMPT_INVALIDATED", "ATTEMPT", attempt, UUID.randomUUID());
            assertNull(socket.quiet(1500));
        }
        try (var socket = authenticated(userToken())) {
            socket.send(subscribe("ATTEMPT_OWNER", attempt));
            assertTrue(socket.next().contains("\"type\":\"SUBSCRIBED\""));
        }
        Thread.sleep(500);
        publish("ATTEMPT_INVALIDATED", "ATTEMPT", attempt, UUID.randomUUID());
        Thread.sleep(500);
        try (var fresh = authenticated(userToken())) {
            fresh.send(subscribe("ATTEMPT_OWNER", attempt));
            assertTrue(fresh.next().contains("\"type\":\"SUBSCRIBED\""));
        }
    }

    @Test
    void consumerReconnectsWithAFreshQueueAfterBrokerClosesTheConnection() throws Exception {
        UUID attempt = UUID.randomUUID();
        try (var socket = authenticated(userToken())) {
            socket.send(subscribe("ATTEMPT_OWNER", attempt));
            assertTrue(socket.next().contains("\"type\":\"SUBSCRIBED\""));
            RABBIT.execInContainer("rabbitmqctl", "close_all_connections", "-p", vhost(), "test-reconnect");
            awaitConsumer();
            publish("ATTEMPT_INVALIDATED", "ATTEMPT", attempt, UUID.randomUUID());
            String hint = socket.next();
            assertTrue(hint.contains("\"type\":\"RESOURCE_INVALIDATED\""), hint);
        }
    }

    private static com.nimbusds.jose.jwk.RSAKey key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("realtime-invalidation-test")
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RSAPublicKey publicKey() {
        try {
            return KEY.toRSAPublicKey();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sign(
            String subject, String type, Map<String, Object> claims, Instant issuedAt, Instant expiresAt) {
        var builder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("principal_type", type);
        claims.forEach(builder::claim);
        return ENCODER.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256)
                                .keyId(KEY.getKeyID())
                                .build(),
                        builder.build()))
                .getTokenValue();
    }

    private static String serviceToken(boolean dedicatedAuthorityClient) {
        Instant now = Instant.now();
        var claims = new java.util.HashMap<String, Object>();
        claims.put("scope", List.of(dedicatedAuthorityClient ? AUTHORITY_SCOPE : "identity.user-admission"));
        if (dedicatedAuthorityClient) claims.put("aud", List.of(AUTHORITY_AUDIENCE));
        return sign(
                dedicatedAuthorityClient ? AUTHORITY_CLIENT : ADMISSION_CLIENT,
                "SERVICE",
                claims,
                now.minusSeconds(2),
                now.plusSeconds(120));
    }

    private static String basic(String client, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((client + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /** Contract-replica authority stub identical in shape to the W4-B2 integration fixture. */
    private static DisposableServer stub() {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    String path = request.uri();
                    if ("/oauth2/token".equals(path)) {
                        return request.receive()
                                .aggregate()
                                .asString()
                                .defaultIfEmpty("")
                                .flatMap(form -> {
                                    String authorization =
                                            request.requestHeaders().get(HttpHeaders.AUTHORIZATION);
                                    boolean authorityClient = basic(AUTHORITY_CLIENT, AUTHORITY_SECRET)
                                            .equals(authorization);
                                    boolean admissionClient = basic(ADMISSION_CLIENT, ADMISSION_SECRET)
                                            .equals(authorization);
                                    if (!authorityClient && !admissionClient)
                                        return text(response, 401, "{\"code\":\"UNAUTHENTICATED\"}");
                                    if (authorityClient && !form.contains("scope=" + AUTHORITY_SCOPE))
                                        return text(response, 400, "{\"error\":\"invalid_scope\"}");
                                    return text(
                                            response,
                                            200,
                                            "{\"access_token\":\""
                                                    + serviceToken(authorityClient)
                                                    + "\",\"token_type\":\"Bearer\",\"expires_in\":1,\"scope\":\""
                                                    + (authorityClient ? AUTHORITY_SCOPE : "identity.user-admission")
                                                    + "\"}");
                                });
                    }
                    if ("/internal/security/user-admissions".equals(path)) {
                        return text(
                                response,
                                200,
                                "{\"actorId\":\"" + ACTOR + "\",\"roles\":" + evidenceRoles(request) + "}");
                    }
                    if ("/internal/realtime/subscription-authorizations".equals(path)) {
                        String bearer = bearer(request);
                        if (bearer == null) return text(response, 401, "{\"code\":\"SERVICE_AUTHENTICATION_INVALID\"}");
                        if (!serviceTokenValid(bearer))
                            return text(response, 401, "{\"code\":\"SERVICE_AUTHENTICATION_INVALID\"}");
                        List<String> evidence = request.requestHeaders().getAll("X-Quizopia-User-Evidence");
                        if (evidence.size() != 1) return text(response, 400, "{\"code\":\"INVALID_REQUEST\"}");
                        return request.receive()
                                .aggregate()
                                .asString()
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    AUTHORITY_CALLS.incrementAndGet();
                                    var root = JSON.readTree(body);
                                    return switch (AUTHORITY_STATUS.get()) {
                                        case "404" -> text(response, 404, "{\"code\":\"NOT_ACCESSIBLE\"}");
                                        case "403" -> text(response, 403, "{\"code\":\"SERVICE_CALLER_FORBIDDEN\"}");
                                        case "503" -> text(response, 503, "{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
                                        case "USER_EVIDENCE_INVALID" ->
                                            text(response, 401, "{\"code\":\"USER_EVIDENCE_INVALID\"}");
                                        default ->
                                            text(
                                                    response,
                                                    200,
                                                    "{\"resourceKind\":\""
                                                            + root.path("resourceKind")
                                                                    .asString()
                                                            + "\",\"resourceId\":\""
                                                            + root.path("resourceId")
                                                                    .asString()
                                                            + "\",\"authorized\":true,\"checkedAt\":\""
                                                            + Instant.now()
                                                            + "\"}");
                                    };
                                });
                    }
                    return text(response, 404, "{\"code\":\"NOT_ACCESSIBLE\"}");
                })
                .bindNow();
    }

    private static Mono<Void> text(reactor.netty.http.server.HttpServerResponse response, int status, String body) {
        return response.status(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .sendString(Mono.just(body))
                .then();
    }

    private static String bearer(reactor.netty.http.server.HttpServerRequest request) {
        String value = request.requestHeaders().get(HttpHeaders.AUTHORIZATION);
        return value != null && value.startsWith("Bearer ") ? value.substring(7) : null;
    }

    private static String evidenceRoles(reactor.netty.http.server.HttpServerRequest request) {
        List<String> evidence = request.requestHeaders().getAll("X-Quizopia-User-Evidence");
        if (evidence.size() != 1) return "[\"STUDENT\"]";
        try {
            List<String> roles =
                    SignedJWT.parse(evidence.getFirst()).getJWTClaimsSet().getStringListClaim("roles");
            if (roles == null || roles.isEmpty()) return "[\"STUDENT\"]";
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < roles.size(); i++) {
                if (i > 0) json.append(',');
                json.append('"').append(roles.get(i)).append('"');
            }
            return json.append(']').toString();
        } catch (Exception invalid) {
            return "[\"STUDENT\"]";
        }
    }

    private static boolean serviceTokenValid(String value) {
        try {
            SignedJWT jwt = SignedJWT.parse(value);
            JWSVerifier verifier = new RSASSAVerifier(PUBLIC_KEY);
            if (!jwt.verify(verifier)) return false;
            var claims = jwt.getJWTClaimsSet();
            return ISSUER.equals(claims.getIssuer())
                    && AUTHORITY_CLIENT.equals(claims.getSubject())
                    && "SERVICE".equals(claims.getClaim("principal_type"))
                    && List.of(AUTHORITY_AUDIENCE).equals(claims.getAudience())
                    && List.of(AUTHORITY_SCOPE).equals(claims.getStringListClaim("scope"))
                    && claims.getExpirationTime() != null
                    && claims.getExpirationTime().toInstant().isAfter(Instant.now());
        } catch (Exception invalid) {
            return false;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "1s");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("quizopia.gateway.browser.allowed-origins", () -> ORIGIN);
        registry.add("quizopia.realtime.enabled", () -> true);
        registry.add("quizopia.realtime.auth-timeout", () -> "PT3S");
        registry.add("quizopia.admission.enabled", () -> true);
        registry.add("quizopia.admission.identity-base-url", () -> "http://127.0.0.1:" + STUB.port());
        registry.add("quizopia.admission.client-id", () -> ADMISSION_CLIENT);
        registry.add("quizopia.admission.client-secret", () -> ADMISSION_SECRET);
        registry.add("quizopia.admission.allow-insecure-loopback", () -> true);
        registry.add("quizopia.admission.connect-timeout", () -> "PT0.5S");
        registry.add("quizopia.admission.response-timeout", () -> "PT2S");
        registry.add("quizopia.admission.total-timeout", () -> "PT3S");
        registry.add("quizopia.realtime.authority.enabled", () -> true);
        registry.add("quizopia.realtime.authority.identity-base-url", () -> "http://127.0.0.1:" + STUB.port());
        registry.add("quizopia.realtime.authority.assessment-base-url", () -> "http://127.0.0.1:" + STUB.port());
        registry.add("quizopia.realtime.authority.client-id", () -> AUTHORITY_CLIENT);
        registry.add("quizopia.realtime.authority.client-secret", () -> AUTHORITY_SECRET);
        registry.add("quizopia.realtime.authority.allow-insecure-loopback", () -> true);
        registry.add("quizopia.realtime.authority.connect-timeout", () -> "PT0.5S");
        registry.add("quizopia.realtime.authority.response-timeout", () -> "PT2S");
        registry.add("quizopia.realtime.authority.total-timeout", () -> "PT3S");
        registry.add("quizopia.realtime.invalidation.enabled", () -> true);
        registry.add("quizopia.realtime.invalidation.allow-insecure-loopback", () -> true);
        registry.add("quizopia.realtime.invalidation.host", RABBIT::getHost);
        registry.add("quizopia.realtime.invalidation.port", () -> RABBIT.getMappedPort(5672));
        registry.add("quizopia.realtime.invalidation.virtual-host", () -> vhost());
        registry.add("quizopia.realtime.invalidation.username", () -> CONSUMER_USER);
        registry.add("quizopia.realtime.invalidation.password", () -> CONSUMER_PASSWORD);
    }

    @AfterAll
    static void stopStub() {
        STUB.disposeNow();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        @Bean
        @Primary
        ReactiveJwtDecoder decoder() {
            var decoder = NimbusReactiveJwtDecoder.withPublicKey(PUBLIC_KEY).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
}
