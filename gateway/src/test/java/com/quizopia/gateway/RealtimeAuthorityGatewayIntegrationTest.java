package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.quizopia.gateway.realtime.RealtimeIngressFilter;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * W4-B2 Gateway integration with a contract-replica authority stub: the token endpoint performs real
 * client-credential checks and issues genuinely signed RS256 SERVICE tokens with the exact dedicated
 * audience/scope; the authority endpoint independently verifies that SERVICE JWT and the forwarded
 * USER evidence before deciding. SUBSCRIBE never succeeds without the downstream decision.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import(RealtimeAuthorityGatewayIntegrationTest.Keys.class)
class RealtimeAuthorityGatewayIntegrationTest {
    private static final String ORIGIN = "https://quizopia.test";
    private static final String ISSUER = "https://identity.realtime-authority.test";
    private static final String ACTOR = "00000000-0000-0000-0000-000000000001";
    private static final String ADMISSION_CLIENT = "gateway-admission-test";
    private static final String ADMISSION_SECRET = "admission-test-secret";
    private static final String AUTHORITY_CLIENT = "gateway-realtime-test";
    private static final String AUTHORITY_SECRET = "authority-test-secret";
    private static final String AUTHORITY_SCOPE = "assessment.realtime.subscription-authorize";
    private static final String AUTHORITY_AUDIENCE = "quizopia-assessment-realtime-authority";
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final com.nimbusds.jose.jwk.RSAKey KEY = key();
    private static final RSAPublicKey PUBLIC_KEY = publicKey();
    private static final JwtEncoder ENCODER = new NimbusJwtEncoder(
            new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(KEY)));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final AtomicInteger ADMISSION_CALLS = new AtomicInteger();
    static final AtomicInteger AUTHORITY_TOKEN_CALLS = new AtomicInteger();
    static final AtomicInteger AUTHORITY_CALLS = new AtomicInteger();
    static final AtomicReference<String> AUTHORITY_STATUS = new AtomicReference<>("DECIDE");
    static final AtomicReference<String> LAST_USER_EVIDENCE = new AtomicReference<>();
    static final AtomicReference<String> LAST_SERVICE_SUBJECT = new AtomicReference<>();
    static final List<String> REJECTED_SERVICE_REASONS = new CopyOnWriteArrayList<>();

    static final DisposableServer STUB = stub();

    /** Single-connection scripted client: sequential sends with sequential response reads. */
    private final class ScriptedWebSocket implements AutoCloseable {
        private final java.util.concurrent.BlockingQueue<String> incoming =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private final java.net.http.WebSocket socket;
        private final StringBuilder partial = new StringBuilder();

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
            org.junit.jupiter.api.Assertions.assertNotNull(message, "Expected another frame");
            return message;
        }

        @Override
        public void close() {
            socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "").join();
        }
    }

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "1s");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("quizopia.gateway.browser.allowed-origins", () -> ORIGIN);
        registry.add("quizopia.realtime.enabled", () -> true);
        registry.add("quizopia.realtime.auth-timeout", () -> "PT3S");
        registry.add("quizopia.realtime.authority.maximum-subscriptions-per-connection", () -> 2);
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
    }

    @BeforeEach
    void reset() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHALL");
        AUTHORITY_STATUS.set("DECIDE");
        ADMISSION_CALLS.set(0);
        AUTHORITY_TOKEN_CALLS.set(0);
        AUTHORITY_CALLS.set(0);
        LAST_USER_EVIDENCE.set(null);
        LAST_SERVICE_SUBJECT.set(null);
        REJECTED_SERVICE_REASONS.clear();
    }

    @AfterAll
    static void stopStub() {
        STUB.disposeNow();
    }

    @Test
    void teacherSessionSubscribesPublicationAfterRealServiceAuthorization(CapturedOutput output) {
        String token = userToken(List.of("STUDENT", "TEACHER"));
        String ticket = ticket(token);
        UUID requestId = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        String subscribe = "{\"protocolVersion\":1,\"type\":\"SUBSCRIBE\",\"requestId\":\"" + requestId
                + "\",\"resource\":{\"kind\":\"PUBLICATION_TEACHER\",\"id\":\"" + publication + "\"}}";
        List<String> messages = exchange(List.of(auth(ticket, token), subscribe), 3);

        assertTrue(messages.get(0).contains("\"type\":\"WELCOME\""));
        assertEquals("{\"protocolVersion\":1,\"type\":\"AUTHENTICATED\"}", messages.get(1));
        assertTrue(messages.get(2).contains("\"type\":\"SUBSCRIBED\""), messages.get(2));
        assertTrue(messages.get(2).contains(requestId.toString()));
        assertTrue(messages.get(2).contains(publication.toString()));
        assertTrue(messages.get(2).contains("\"kind\":\"PUBLICATION_TEACHER\""));
        String subscriptionId = subscriptionId(messages.get(2));
        assertTrue(subscriptionId.matches("[A-Za-z0-9_-]{20,40}"), subscriptionId);
        assertFalse(subscriptionId.equals(publication.toString()));
        assertEquals(1, AUTHORITY_CALLS.get());
        assertEquals(AUTHORITY_CLIENT, LAST_SERVICE_SUBJECT.get());
        assertEquals(token, LAST_USER_EVIDENCE.get());
        assertTrue(REJECTED_SERVICE_REASONS.isEmpty());
        assertFalse(output.getAll().contains(token));
        assertFalse(output.getAll().contains(AUTHORITY_SECRET));
    }

    @Test
    void studentSessionSubscribesAttemptThenUnsubscribesExactlyOnce() throws Exception {
        String token = userToken(List.of("STUDENT"));
        String ticket = ticket(token);
        UUID attempt = UUID.randomUUID();
        try (ScriptedWebSocket socket = new ScriptedWebSocket()) {
            socket.send(auth(ticket, token));
            assertTrue(socket.next().contains("\"type\":\"WELCOME\""));
            assertEquals("{\"protocolVersion\":1,\"type\":\"AUTHENTICATED\"}", socket.next());
            socket.send(subscribe(ATTEMPT_OWNER_LITERAL, attempt, UUID.randomUUID()));
            String subscribed = socket.next();
            assertTrue(subscribed.contains("\"type\":\"SUBSCRIBED\""), subscribed);
            String subscriptionId = subscriptionId(subscribed);

            UUID second = UUID.randomUUID();
            socket.send(unsubscribe(subscriptionId, second));
            String unsubscribed = socket.next();
            assertTrue(unsubscribed.contains("\"type\":\"UNSUBSCRIBED\""), unsubscribed);
            assertTrue(unsubscribed.contains(second.toString()));

            socket.send(unsubscribe(subscriptionId, UUID.randomUUID()));
            String replay = socket.next();
            assertTrue(replay.contains("\"type\":\"ERROR\""), replay);
            assertTrue(replay.contains("\"code\":\"NOT_ACCESSIBLE\""));
        }
    }

    @Test
    void duplicateAndCapacitySubscriptionsAreBoundedLocally() throws Exception {
        String token = userToken(List.of("STUDENT"));
        String ticket = ticket(token);
        UUID attempt = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        try (ScriptedWebSocket socket = new ScriptedWebSocket()) {
            socket.send(auth(ticket, token));
            assertTrue(socket.next().contains("\"type\":\"WELCOME\""));
            assertEquals("{\"protocolVersion\":1,\"type\":\"AUTHENTICATED\"}", socket.next());

            socket.send(subscribe(ATTEMPT_OWNER_LITERAL, attempt, UUID.randomUUID()));
            String first = socket.next();
            assertTrue(first.contains("\"type\":\"SUBSCRIBED\""), first);
            assertEquals(1, AUTHORITY_CALLS.get());

            socket.send(subscribe(ATTEMPT_OWNER_LITERAL, attempt, UUID.randomUUID()));
            String duplicate = socket.next();
            assertTrue(duplicate.contains("\"code\":\"NOT_ACCESSIBLE\""), duplicate);

            socket.send(subscribe(ATTEMPT_OWNER_LITERAL, other, UUID.randomUUID()));
            String second = socket.next();
            assertTrue(second.contains("\"type\":\"SUBSCRIBED\""), second);
            assertEquals(2, AUTHORITY_CALLS.get());

            socket.send(subscribe(ATTEMPT_OWNER_LITERAL, third, UUID.randomUUID()));
            String capacity = socket.next();
            assertTrue(capacity.contains("\"code\":\"CAPACITY_EXCEEDED\""), capacity);
            assertEquals(2, AUTHORITY_CALLS.get());

            String secondSubscription = subscriptionId(second);
            socket.send(unsubscribe(secondSubscription, UUID.randomUUID()));
            assertTrue(socket.next().contains("\"type\":\"UNSUBSCRIBED\""));

            socket.send(subscribe(ATTEMPT_OWNER_LITERAL, third, UUID.randomUUID()));
            String resubscribed = socket.next();
            assertTrue(resubscribed.contains("\"type\":\"SUBSCRIBED\""), resubscribed);
            assertEquals(3, AUTHORITY_CALLS.get());
        }
    }

    @Test
    void authorityDenialsAndOutageAreRequestScopedAndFailClosed() {
        String token = userToken(List.of("STUDENT"));
        for (String status : List.of("404", "403", "503", "MALFORMED")) {
            AUTHORITY_STATUS.set(status);
            String ticket = ticket(token);
            List<String> messages = exchange(
                    List.of(
                            auth(ticket, token),
                            subscribe(ATTEMPT_OWNER_LITERAL, UUID.randomUUID(), UUID.randomUUID())),
                    3);
            String outcome = messages.get(2);
            assertTrue(outcome.contains("\"type\":\"ERROR\""), outcome);
            if (status.equals("503") || status.equals("MALFORMED"))
                assertTrue(outcome.contains("\"code\":\"AUTHORITY_UNAVAILABLE\""), outcome);
            else assertTrue(outcome.contains("\"code\":\"NOT_ACCESSIBLE\""), outcome);
        }
    }

    @Test
    void staleUserEvidenceTerminatesTheWholeConnection() {
        String token = userToken(List.of("STUDENT"));
        AUTHORITY_STATUS.set("USER_EVIDENCE_INVALID");
        String ticket = ticket(token);
        AtomicReference<org.springframework.web.reactive.socket.CloseStatus> close = new AtomicReference<>();
        exchangeUntilClose(
                List.of(auth(ticket, token), subscribe(ATTEMPT_OWNER_LITERAL, UUID.randomUUID(), UUID.randomUUID())),
                close);
        assertEquals(4401, close.get().getCode());
    }

    @Test
    void refusedServiceCredentialsFailClosedWithoutSubscription() {
        String token = userToken(List.of("STUDENT"));
        AUTHORITY_STATUS.set("REFUSE_TOKEN");
        String ticket = ticket(token);
        List<String> messages = exchange(
                List.of(auth(ticket, token), subscribe(ATTEMPT_OWNER_LITERAL, UUID.randomUUID(), UUID.randomUUID())),
                3);
        assertTrue(messages.get(2).contains("\"code\":\"AUTHORITY_UNAVAILABLE\""));
        assertFalse(messages.get(2).contains("SUBSCRIBED"));
        assertEquals(0, AUTHORITY_CALLS.get());
    }

    @Test
    void strictFrameValidationStillPrecedesAuthority() {
        String token = userToken(List.of("STUDENT"));
        String ticket = ticket(token);
        AtomicReference<org.springframework.web.reactive.socket.CloseStatus> close = new AtomicReference<>();
        List<String> messages = exchangeUntilClose(
                List.of(
                        auth(ticket, token),
                        "{\"protocolVersion\":1,\"type\":\"SUBSCRIBE\",\"requestId\":\""
                                + UUID.randomUUID()
                                + "\",\"resource\":{\"kind\":\"ATTEMPT_OWNER\",\"id\":\""
                                + UUID.randomUUID()
                                + "\"},\"extra\":1}"),
                close);
        assertEquals(4400, close.get().getCode());
        assertTrue(messages.stream().anyMatch(message -> message.contains("AUTHENTICATED")));
        assertEquals(0, AUTHORITY_CALLS.get());
    }

    private static final String ATTEMPT_OWNER_LITERAL = "ATTEMPT_OWNER";
    private static final String USER_EVIDENCE_HEADER = "X-Quizopia-User-Evidence";

    private String ticket(String token) {
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

    private String ticket2(String token) {
        return ticket(token);
    }

    private static String subscribe(String kind, UUID resourceId, UUID requestId) {
        return "{\"protocolVersion\":1,\"type\":\"SUBSCRIBE\",\"requestId\":\""
                + requestId
                + "\",\"resource\":{\"kind\":\""
                + kind
                + "\",\"id\":\""
                + resourceId
                + "\"}}";
    }

    private static String unsubscribe(String subscriptionId, UUID requestId) {
        return "{\"protocolVersion\":1,\"type\":\"UNSUBSCRIBE\",\"requestId\":\""
                + requestId
                + "\",\"subscriptionId\":\""
                + subscriptionId
                + "\"}";
    }

    private static void awaitAuthenticated(List<String> messages) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (messages.stream().noneMatch(message -> message.contains("AUTHENTICATED"))
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private static String auth(String ticket, String token) {
        return "{\"protocolVersion\":1,\"type\":\"AUTH\",\"ticket\":\""
                + ticket
                + "\",\"accessToken\":\""
                + token
                + "\"}";
    }

    private static String subscriptionId(String subscribedFrame) {
        JsonNode root = JSON.readTree(subscribedFrame);
        return root.path("subscriptionId").asString();
    }

    private List<String> exchange(List<String> outbound, int expectedMessages) {
        List<String> messages = new CopyOnWriteArrayList<>();
        reactor.core.publisher.Sinks.Empty<Void> authenticated = reactor.core.publisher.Sinks.empty();
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(ORIGIN);
        var client = new ReactorNettyWebSocketClient(
                HttpClient.create(), () -> WebsocketClientSpec.builder().protocols(RealtimeIngressFilter.SUBPROTOCOL));
        client.execute(
                        URI.create("ws://127.0.0.1:" + port + RealtimeIngressFilter.WEBSOCKET_PATH),
                        headers,
                        session -> {
                            Flux<WebSocketMessage> inbound = session.receive().doOnNext(message -> {
                                String text = message.getPayloadAsText();
                                messages.add(text);
                                if (text.contains("\"type\":\"AUTHENTICATED\"")) authenticated.tryEmitEmpty();
                            });
                            Mono<Void> receive = inbound.take(expectedMessages).then();
                            // The first frame is always AUTH; later frames wait for AUTHENTICATED.
                            Mono<Void> send = session.send(Flux.just(outbound.getFirst())
                                    .map(session::textMessage)
                                    .concatWith(authenticated
                                            .asMono()
                                            .thenMany(Flux.fromIterable(outbound.subList(1, outbound.size()))
                                                    .delayElements(Duration.ofMillis(200))
                                                    .map(session::textMessage))));
                            return Mono.when(send, receive).then(session.close());
                        })
                .block(Duration.ofSeconds(15));
        return messages;
    }

    private List<String> exchangeUntilClose(
            List<String> outbound, AtomicReference<org.springframework.web.reactive.socket.CloseStatus> close) {
        List<String> messages = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        java.net.http.WebSocket.Listener listener = new java.net.http.WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();

            @Override
            public void onOpen(java.net.http.WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onText(
                    java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                text.append(data);
                if (last) {
                    messages.add(text.toString());
                    text.setLength(0);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public java.util.concurrent.CompletionStage<?> onClose(
                    java.net.http.WebSocket webSocket, int statusCode, String reason) {
                close.set(new org.springframework.web.reactive.socket.CloseStatus(statusCode, reason));
                closed.complete(null);
                return null;
            }

            @Override
            public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                closed.completeExceptionally(error);
            }
        };
        java.net.http.WebSocket socket = java.net.http.HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .subprotocols(RealtimeIngressFilter.SUBPROTOCOL)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + RealtimeIngressFilter.WEBSOCKET_PATH), listener)
                .join();
        outbound.forEach(frame -> {
            socket.sendText(frame, true).join();
            if (frame == outbound.getFirst()) awaitAuthenticated(messages);
        });
        try {
            closed.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("WebSocket close was not observed", exception);
        }
        return messages;
    }

    private static String userToken(List<String> roles) {
        Instant now = Instant.now();
        return sign(ACTOR, "USER", Map.of("roles", roles), now.minusSeconds(2), now.plusSeconds(300));
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

    private static RSAPublicKey publicKey() {
        try {
            return KEY.toRSAPublicKey();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static com.nimbusds.jose.jwk.RSAKey key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("realtime-authority-test")
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String basic(String client, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((client + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /** Contract-replica stub: real client-credential checks, real RS256 SERVICE issuance, strict authority. */
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
                                    if (authorityClient) {
                                        AUTHORITY_TOKEN_CALLS.incrementAndGet();
                                        if ("REFUSE_TOKEN".equals(AUTHORITY_STATUS.get()))
                                            return text(response, 401, "{\"error\":\"invalid_client\"}");
                                        if (!form.contains("scope=" + AUTHORITY_SCOPE))
                                            return text(response, 400, "{\"error\":\"invalid_scope\"}");
                                    }
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
                        ADMISSION_CALLS.incrementAndGet();
                        // Contract echo: current Identity roles must equal the token roles exactly.
                        return text(
                                response,
                                200,
                                "{\"actorId\":\"" + ACTOR + "\",\"roles\":" + evidenceRoles(request) + "}");
                    }
                    if ("/internal/realtime/subscription-authorizations".equals(path)) {
                        String bearer = bearer(request);
                        if (bearer == null) return text(response, 401, "{\"code\":\"SERVICE_AUTHENTICATION_INVALID\"}");
                        String reject = verifyServiceToken(bearer);
                        if (reject != null) {
                            REJECTED_SERVICE_REASONS.add(reject);
                            return text(response, 401, "{\"code\":\"SERVICE_AUTHENTICATION_INVALID\"}");
                        }
                        List<String> evidence = request.requestHeaders().getAll(USER_EVIDENCE_HEADER);
                        if (evidence.size() != 1) return text(response, 400, "{\"code\":\"INVALID_REQUEST\"}");
                        LAST_USER_EVIDENCE.set(evidence.getFirst());
                        return request.receive()
                                .aggregate()
                                .asString()
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    JsonNode root;
                                    try {
                                        root = JSON.readTree(body);
                                    } catch (RuntimeException invalid) {
                                        root = null;
                                    }
                                    if (root == null
                                            || !root.isObject()
                                            || root.size() != 2
                                            || !root.path("resourceKind").isString()
                                            || !root.path("resourceId").isString()
                                            || !CANONICAL_UUID
                                                    .matcher(root.path("resourceId")
                                                            .asString())
                                                    .matches())
                                        return text(response, 400, "{\"code\":\"INVALID_REQUEST\"}");
                                    AUTHORITY_CALLS.incrementAndGet();
                                    String mode = AUTHORITY_STATUS.get();
                                    return switch (mode) {
                                        case "404" -> text(response, 404, "{\"code\":\"NOT_ACCESSIBLE\"}");
                                        case "403" -> text(response, 403, "{\"code\":\"SERVICE_CALLER_FORBIDDEN\"}");
                                        case "503" -> text(response, 503, "{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
                                        case "USER_EVIDENCE_INVALID" ->
                                            text(response, 401, "{\"code\":\"USER_EVIDENCE_INVALID\"}");
                                        case "MALFORMED" ->
                                            text(
                                                    response,
                                                    200,
                                                    "{\"resourceKind\":\"ATTEMPT_OWNER\",\"authorized\":true}");
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
        List<String> evidence = request.requestHeaders().getAll(USER_EVIDENCE_HEADER);
        if (evidence.size() != 1) return "[\"STUDENT\"]";
        try {
            var claims = SignedJWT.parse(evidence.getFirst()).getJWTClaimsSet();
            List<String> roles = claims.getStringListClaim("roles");
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

    /** Returns null when the SERVICE JWT satisfies every frozen dedicated-client requirement. */
    private static String verifyServiceToken(String value) {
        try {
            SignedJWT jwt = SignedJWT.parse(value);
            JWSVerifier verifier = new RSASSAVerifier(PUBLIC_KEY);
            if (!jwt.verify(verifier)) return "signature";
            var claims = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(claims.getIssuer())) return "issuer";
            if (!"RS256".equals(jwt.getHeader().getAlgorithm().getName())) return "algorithm";
            if (!AUTHORITY_CLIENT.equals(claims.getSubject())) return "subject";
            if (!"SERVICE".equals(claims.getClaim("principal_type"))) return "principal_type";
            if (!List.of(AUTHORITY_AUDIENCE).equals(claims.getAudience())) return "audience";
            if (!List.of(AUTHORITY_SCOPE).equals(claims.getStringListClaim("scope"))) return "scope";
            Instant now = Instant.now();
            if (claims.getIssueTime() == null
                    || claims.getIssueTime().toInstant().isAfter(now)
                    || claims.getExpirationTime() == null
                    || !claims.getExpirationTime().toInstant().isAfter(now)) return "validity";
            LAST_SERVICE_SUBJECT.set(claims.getSubject());
            return null;
        } catch (Exception invalid) {
            return "malformed";
        }
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
