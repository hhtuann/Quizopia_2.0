package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.quizopia.gateway.realtime.RealtimeConnectionLimiter;
import com.quizopia.gateway.realtime.RealtimeIngressFilter;
import com.quizopia.gateway.realtime.RealtimeTicketStore;
import com.quizopia.gateway.security.admission.IdentityAdmissionClient;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.CloseStatus;
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
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Import(RealtimeGatewayIntegrationTest.Keys.class)
class RealtimeGatewayIntegrationTest {
    private static final String ORIGIN = "https://quizopia.test";
    private static final String SECOND_ORIGIN = "https://second.quizopia.test";
    private static final String ISSUER = "https://identity.realtime.test";
    private static final String ACTOR = "00000000-0000-0000-0000-000000000001";
    private static final RSAKey KEY = key();
    private static final JwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));
    private static final AtomicInteger ADMISSION_STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> ADMISSION_BODY =
            new AtomicReference<>("{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\"]}");
    private static final AtomicInteger ADMISSION_CALLS = new AtomicInteger();
    private static final AtomicInteger TOKEN_CALLS = new AtomicInteger();
    private static final DisposableServer IDENTITY = identityStub();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RealtimeTicketStore tickets;

    @Autowired
    private RealtimeConnectionLimiter connectionLimiter;

    @Autowired
    private CapturingObservationHandler observations;

    @Autowired
    private IdentityAdmissionClient admission;

    private WebTestClient http;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "1s");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("quizopia.gateway.browser.allowed-origins", () -> ORIGIN + "," + SECOND_ORIGIN);
        registry.add("quizopia.realtime.enabled", () -> true);
        registry.add("quizopia.realtime.auth-timeout", () -> "PT3S");
        registry.add("quizopia.realtime.ticket-issue-rate-per-actor", () -> 3);
        registry.add("quizopia.realtime.maximum-connections", () -> 1);
        registry.add("quizopia.realtime.maximum-connections-per-ip", () -> 1);
        registry.add("quizopia.realtime.maximum-connections-per-origin", () -> 1);
        registry.add("quizopia.admission.enabled", () -> true);
        registry.add("quizopia.admission.identity-base-url", () -> "http://127.0.0.1:" + IDENTITY.port());
        registry.add("quizopia.admission.client-id", () -> "gateway-realtime-test");
        registry.add("quizopia.admission.client-secret", () -> "test-only-secret");
        registry.add("quizopia.admission.allow-insecure-loopback", () -> true);
        registry.add("quizopia.admission.connect-timeout", () -> "PT0.2S");
        registry.add("quizopia.admission.response-timeout", () -> "PT0.5S");
        registry.add("quizopia.admission.total-timeout", () -> "PT0.8S");
    }

    @BeforeEach
    void setUp() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHALL");
        ADMISSION_STATUS.set(200);
        ADMISSION_BODY.set("{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\"]}");
        ADMISSION_CALLS.set(0);
        awaitConnectionCount(0);
        observations.clear();
        http = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterAll
    static void stopIdentity() {
        IDENTITY.disposeNow();
    }

    @Test
    void exactTicketRouteUsesSecurityAdmissionAndStrictPayload() {
        String token = userToken(Duration.ofMinutes(5));
        var response = issue(token, ORIGIN, "{}")
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.ticket")
                .value(value -> assertEquals(43, value.toString().length()))
                .jsonPath("$.expiresInSeconds")
                .isEqualTo(30)
                .jsonPath("$.protocolVersion")
                .isEqualTo(1)
                .returnResult();
        assertEquals(1, ADMISSION_CALLS.get());
        String body = new String(response.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(body.contains(token));

        issue(token, ORIGIN, "{\"extra\":true}")
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .json("{\"code\":\"INVALID_REQUEST\"}", true);
        issue(token, ORIGIN, "not-json")
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .json("{\"code\":\"INVALID_REQUEST\"}", true);
    }

    @Test
    void routeMatrixPreventsGenericGatewayFallback() {
        http.options()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type")
                .exchange()
                .expectStatus()
                .isNoContent();
        http.get()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .exchange()
                .expectStatus()
                .isEqualTo(405);
        http.post().uri("/api/realtime/unknown").exchange().expectStatus().isNotFound();
        http.get()
                .uri(RealtimeIngressFilter.WEBSOCKET_PATH)
                .exchange()
                .expectStatus()
                .isBadRequest();
        http.post()
                .uri(RealtimeIngressFilter.WEBSOCKET_PATH)
                .exchange()
                .expectStatus()
                .isEqualTo(405);
        http.get().uri("/ws/realtime/unknown").exchange().expectStatus().isNotFound();
        assertEquals(0, ADMISSION_CALLS.get());
    }

    @Test
    void duplicateOriginsFailClosedAcrossTicketPreflightAndUpgrade() {
        String token = userToken(Duration.ofMinutes(5));
        http.post()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN, SECOND_ORIGIN)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .json("{\"code\":\"ACCESS_DENIED\"}", true);
        http.options()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN, SECOND_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.POST.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .json("{\"code\":\"ACCESS_DENIED\"}", true);
        websocketUpgrade(List.of(ORIGIN, SECOND_ORIGIN), RealtimeIngressFilter.SUBPROTOCOL, null, null)
                .expectStatus()
                .isForbidden()
                .expectBody()
                .json("{\"code\":\"ACCESS_DENIED\"}", true);
        assertEquals(0, ADMISSION_CALLS.get());
    }

    @Test
    void ticketRejectsServiceMalformedDuplicateOriginAndAuthorityFailures() {
        issue(serviceToken(), ORIGIN, "{}")
                .expectStatus()
                .isForbidden()
                .expectBody()
                .json("{\"code\":\"ACCESS_DENIED\"}", true);
        http.post()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        http.post()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(Duration.ofMinutes(5)), "Bearer duplicate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        issue(userToken(Duration.ofMinutes(5)), "https://attacker.example", "{}")
                .expectStatus()
                .isForbidden();

        ADMISSION_STATUS.set(401);
        ADMISSION_BODY.set("{\"code\":\"USER_EVIDENCE_INVALID\"}");
        issue(userToken(Duration.ofMinutes(5)), ORIGIN, "{}")
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .json("{\"code\":\"UNAUTHENTICATED\"}", true);
        ADMISSION_STATUS.set(503);
        ADMISSION_BODY.set("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
        issue(userToken(Duration.ofMinutes(5)), ORIGIN, "{}")
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .json("{\"code\":\"AUTHORITY_UNAVAILABLE\"}", true);
    }

    @Test
    void ticketRejectsStaleAuthorityMalformedIngressAndQuotaWithoutEcho() {
        String token = userToken(Duration.ofMinutes(5));
        ADMISSION_STATUS.set(200);
        ADMISSION_BODY.set("{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\",\"TEACHER\"]}");
        byte[] denied = issue(token, ORIGIN, "{}")
                .expectStatus()
                .isForbidden()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertFalse(new String(denied, java.nio.charset.StandardCharsets.UTF_8).contains(token));

        ADMISSION_STATUS.set(200);
        ADMISSION_BODY.set("{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\"]}");
        http.post()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        http.post()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isBadRequest();
        http.post()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isBadRequest();

        issue(token, ORIGIN, "{}").expectStatus().isCreated();
        issue(token, ORIGIN, "{}").expectStatus().isCreated();
        issue(token, ORIGIN, "{}").expectStatus().isCreated();
        issue(token, ORIGIN, "{}").expectStatus().isEqualTo(429).expectBody().json("{\"code\":\"RATE_LIMITED\"}", true);
    }

    @Test
    void redisOutageFailsTicketIssuanceClosedWithoutCredentialEcho() throws Exception {
        String token = userToken(Duration.ofMinutes(5));
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            byte[] body = issue(token, ORIGIN, "{}")
                    .expectStatus()
                    .isEqualTo(503)
                    .expectBody()
                    .returnResult()
                    .getResponseBody();
            String response = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("{\"code\":\"AUTHORITY_UNAVAILABLE\"}", response);
            assertFalse(response.contains(token));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            REDIS.execInContainer("redis-cli", "PING");
        }
    }

    @Test
    void redisFailureDuringTicketConsumptionClosesWithAuthorityUnavailable() throws Exception {
        String token = userToken(Duration.ofMinutes(5));
        String ticket = ticket(issueBody(token, ORIGIN));
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            AtomicReference<CloseStatus> close = new AtomicReference<>();
            List<String> messages = exchange(ORIGIN, List.of(auth(ticket, token)), 1, close);
            assertEquals(4503, close.get().getCode());
            assertFalse(messages.stream().anyMatch(message -> message.contains("AUTHENTICATED")));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            REDIS.execInContainer("redis-cli", "PING");
        }
    }

    @Test
    void strictUpgradeIngressRejectsCredentialsProtocolQueryAndOriginBefore101() {
        websocketUpgrade("https://attacker.example", RealtimeIngressFilter.SUBPROTOCOL, null, null)
                .expectStatus()
                .isForbidden();
        websocketUpgrade(ORIGIN, "wrong.protocol", null, null).expectStatus().isBadRequest();
        websocketUpgrade(ORIGIN, RealtimeIngressFilter.SUBPROTOCOL, "Bearer forbidden", null)
                .expectStatus()
                .isBadRequest();
        websocketUpgrade(ORIGIN, RealtimeIngressFilter.SUBPROTOCOL, null, "credential=forbidden")
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void websocketAuthenticatesThenKeepsSubscriptionsFeatureDisabled() {
        String token = userToken(Duration.ofMinutes(5));
        String ticket = ticket(issueBody(token, ORIGIN));
        UUID requestId = UUID.randomUUID();
        String auth = auth(ticket, token);
        String subscribe = "{\"protocolVersion\":1,\"type\":\"SUBSCRIBE\",\"requestId\":\""
                + requestId
                + "\",\"resource\":{\"kind\":\"ATTEMPT_OWNER\",\"id\":\""
                + UUID.randomUUID()
                + "\"}}";
        List<String> messages = exchange(ORIGIN, List.of(auth, subscribe), 3, null);

        assertTrue(messages.get(0).contains("\"type\":\"WELCOME\""));
        assertEquals("{\"protocolVersion\":1,\"type\":\"AUTHENTICATED\"}", messages.get(1));
        assertTrue(messages.get(2).contains("\"code\":\"NOT_ACCESSIBLE\""));
        assertTrue(messages.get(2).contains(requestId.toString()));
        assertFalse(messages.stream().anyMatch(message -> message.contains("SUBSCRIBED")));
        assertEquals(2, ADMISSION_CALLS.get());
    }

    @Test
    void websocketRejectsReplayOriginMismatchInvalidFirstFrameAndTimeout() {
        String token = userToken(Duration.ofMinutes(5));
        String replayTicket = ticket(issueBody(token, ORIGIN));
        exchange(ORIGIN, List.of(auth(replayTicket, token)), 2, null);
        AtomicReference<CloseStatus> replayClose = new AtomicReference<>();
        exchange(ORIGIN, List.of(auth(replayTicket, token)), 1, replayClose);
        assertEquals(4401, replayClose.get().getCode());

        String originTicket = ticket(issueBody(token, ORIGIN));
        AtomicReference<CloseStatus> originClose = new AtomicReference<>();
        exchange(SECOND_ORIGIN, List.of(auth(originTicket, token)), 1, originClose);
        assertEquals(4401, originClose.get().getCode());

        AtomicReference<CloseStatus> firstFrameClose = new AtomicReference<>();
        exchange(ORIGIN, List.of("{\"protocolVersion\":1,\"type\":\"SUBSCRIBE\"}"), 1, firstFrameClose);
        assertEquals(4400, firstFrameClose.get().getCode());

        AtomicReference<CloseStatus> timeoutClose = new AtomicReference<>();
        exchange(ORIGIN, List.of(), 1, timeoutClose);
        assertEquals(4408, timeoutClose.get().getCode());
    }

    @Test
    void websocketRejectsTokenActorAndAdmissionMismatchesBeforeAuthentication() {
        String issuedToken = userToken(Duration.ofMinutes(5));
        String differentToken = userToken(Duration.ofMinutes(4));
        String fingerprintTicket = ticket(issueBody(issuedToken, ORIGIN));
        AtomicReference<CloseStatus> fingerprintClose = new AtomicReference<>();
        List<String> fingerprintMessages =
                exchange(ORIGIN, List.of(auth(fingerprintTicket, differentToken)), 1, fingerprintClose);
        assertEquals(4401, fingerprintClose.get().getCode());
        assertFalse(fingerprintMessages.stream().anyMatch(message -> message.contains("AUTHENTICATED")));

        String actorTicket =
                tickets.issue(UUID.randomUUID(), issuedToken, ORIGIN).block().value();
        AtomicReference<CloseStatus> actorClose = new AtomicReference<>();
        List<String> actorMessages = exchange(ORIGIN, List.of(auth(actorTicket, issuedToken)), 1, actorClose);
        assertEquals(4401, actorClose.get().getCode());
        assertFalse(actorMessages.stream().anyMatch(message -> message.contains("AUTHENTICATED")));

        String admissionTicket = ticket(issueBody(issuedToken, ORIGIN));
        ADMISSION_STATUS.set(200);
        ADMISSION_BODY.set("{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\",\"TEACHER\"]}");
        AtomicReference<CloseStatus> admissionClose = new AtomicReference<>();
        List<String> admissionMessages =
                exchange(ORIGIN, List.of(auth(admissionTicket, issuedToken)), 1, admissionClose);
        assertEquals(4403, admissionClose.get().getCode());
        assertFalse(admissionMessages.stream().anyMatch(message -> message.contains("AUTHENTICATED")));

        ADMISSION_STATUS.set(200);
        ADMISSION_BODY.set("{\"actorId\":\"" + ACTOR + "\",\"roles\":[\"STUDENT\"]}");
        String outageTicket = ticket(issueBody(issuedToken, ORIGIN));
        ADMISSION_STATUS.set(503);
        ADMISSION_BODY.set("{\"code\":\"AUTHORITY_UNAVAILABLE\"}");
        AtomicReference<CloseStatus> outageClose = new AtomicReference<>();
        List<String> outageMessages = exchange(ORIGIN, List.of(auth(outageTicket, issuedToken)), 1, outageClose);
        assertEquals(4503, outageClose.get().getCode());
        assertFalse(outageMessages.stream().anyMatch(message -> message.contains("AUTHENTICATED")));
    }

    @Test
    void websocketRejectsDuplicateAuthUnknownFieldsOversizedAndBinaryFrames() {
        String token = userToken(Duration.ofMinutes(5));
        String ticket = ticket(issueBody(token, ORIGIN));
        AtomicReference<CloseStatus> duplicateClose = new AtomicReference<>();
        exchange(ORIGIN, List.of(auth(ticket, token), auth(ticket, token)), 1, duplicateClose);
        assertEquals(4400, duplicateClose.get().getCode());

        AtomicReference<CloseStatus> fieldsClose = new AtomicReference<>();
        exchange(
                ORIGIN,
                List.of(
                        "{\"protocolVersion\":2,\"type\":\"AUTH\",\"ticket\":\"x\",\"accessToken\":\"y\",\"extra\":true}"),
                1,
                fieldsClose);
        assertEquals(4400, fieldsClose.get().getCode());

        AtomicReference<CloseStatus> oversizedClose = new AtomicReference<>();
        exchange(ORIGIN, List.of("x".repeat(17_409)), 1, oversizedClose);
        assertEquals(4400, oversizedClose.get().getCode());

        AtomicReference<CloseStatus> binaryClose = new AtomicReference<>();
        exchangeBinaryUntilClose(ORIGIN, ByteBuffer.wrap(new byte[] {1, 2, 3}), binaryClose);
        assertEquals(4400, binaryClose.get().getCode());
    }

    @Test
    void websocketRejectsCoercedProtocolVersionsForEveryClientFrame() {
        String token = userToken(Duration.ofMinutes(5));

        String authTicket = ticket(issueBody(token, ORIGIN));
        AtomicReference<CloseStatus> authClose = new AtomicReference<>();
        exchange(
                ORIGIN,
                List.of(auth(authTicket, token).replace("\"protocolVersion\":1", "\"protocolVersion\":\"1\"")),
                1,
                authClose);
        assertEquals(4400, authClose.get().getCode());

        String subscribeTicket = ticket(issueBody(token, ORIGIN));
        AtomicReference<CloseStatus> subscribeClose = new AtomicReference<>();
        String subscribe = "{\"protocolVersion\":1.5,\"type\":\"SUBSCRIBE\",\"requestId\":\""
                + UUID.randomUUID()
                + "\",\"resource\":{\"kind\":\"ATTEMPT_OWNER\",\"id\":\""
                + UUID.randomUUID()
                + "\"}}";
        exchange(ORIGIN, List.of(auth(subscribeTicket, token), subscribe), 1, subscribeClose);
        assertEquals(4400, subscribeClose.get().getCode());

        String unsubscribeTicket = ticket(issueBody(token, ORIGIN));
        AtomicReference<CloseStatus> unsubscribeClose = new AtomicReference<>();
        String unsubscribe = "{\"protocolVersion\":true,\"type\":\"UNSUBSCRIBE\",\"requestId\":\""
                + UUID.randomUUID()
                + "\",\"subscriptionId\":\"subscription-1\"}";
        exchange(ORIGIN, List.of(auth(unsubscribeTicket, token), unsubscribe), 1, unsubscribeClose);
        assertEquals(4400, unsubscribeClose.get().getCode());
    }

    @Test
    void websocketConnectionLeaseIsHeldUntilTeardownAndThenReleased() throws Exception {
        String token = userToken(Duration.ofMinutes(5));
        String heldTicket = ticket(issueBody(token, ORIGIN));
        CompletableFuture<Void> heldClosed = new CompletableFuture<>();
        CompletableFuture<Void> heldAuthenticated = new CompletableFuture<>();
        WebSocket held = openWebSocket(ORIGIN, heldClosed, heldAuthenticated);
        try {
            held.sendText(auth(heldTicket, token), true).join();
            heldAuthenticated.get(5, TimeUnit.SECONDS);
            websocketUpgrade(ORIGIN, RealtimeIngressFilter.SUBPROTOCOL, null, null)
                    .expectStatus()
                    .isEqualTo(429)
                    .expectBody()
                    .json("{\"code\":\"RATE_LIMITED\"}", true);
        } finally {
            held.sendClose(WebSocket.NORMAL_CLOSURE, "").join();
            heldClosed.get(5, TimeUnit.SECONDS);
        }
        awaitConnectionCount(0);

        String replacementTicket = ticket(issueBody(token, ORIGIN));
        List<String> messages = exchange(ORIGIN, List.of(auth(replacementTicket, token)), 2, null);
        assertEquals("{\"protocolVersion\":1,\"type\":\"AUTHENTICATED\"}", messages.get(1));
    }

    @Test
    void websocketClosesAtJwtExpiryAndNeverDisclosesCredentials(CapturedOutput output) {
        String token = userToken(Duration.ofSeconds(3));
        AtomicReference<CloseStatus> close = new AtomicReference<>();
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level previousLevel = root.getLevel();
        int tokenCallsBefore = TOKEN_CALLS.get();
        String ticket;
        String authFrame;
        String ticketObservations;
        String websocketObservations;
        List<String> messages;
        root.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            clearCachedAdmissionClient();
            ticket = ticket(issueBody(token, ORIGIN));
            authFrame = auth(ticket, token);
            ticketObservations = observations.completed();
            observations.clear();
            messages = exchange(ORIGIN, List.of(authFrame), 1, close);
            websocketObservations = observations.completed();
        } finally {
            root.setLevel(previousLevel);
        }

        assertEquals(tokenCallsBefore + 1, TOKEN_CALLS.get());
        assertEquals(4401, close.get().getCode());
        assertTrue(messages.stream().anyMatch(message -> message.contains("AUTHENTICATED")));
        assertTrue(messages.stream().noneMatch(message -> message.contains(token) || message.contains(ticket)));
        assertCredentialsAbsent(output.getAll(), token, ticket, authFrame);
        assertFalse(ticketObservations.isBlank());
        assertFalse(websocketObservations.isBlank());
        assertCredentialsAbsent(ticketObservations + websocketObservations, token, ticket, authFrame);
    }

    private void clearCachedAdmissionClient() {
        Object manager = ReflectionTestUtils.getField(admission, "manager");
        ReactiveOAuth2AuthorizedClientService clients = (ReactiveOAuth2AuthorizedClientService)
                ReflectionTestUtils.getField(manager, "authorizedClientService");
        clients.removeAuthorizedClient("identity-admission", "identity-admission-client")
                .block(Duration.ofSeconds(2));
    }

    private static void assertCredentialsAbsent(String captured, String token, String ticket, String authFrame) {
        assertFalse(captured.contains(token));
        assertFalse(captured.contains(ticket));
        assertFalse(captured.contains(authFrame));
        assertFalse(captured.contains("test-only-secret"));
        assertFalse(captured.contains("service-token"));
    }

    private void awaitConnectionCount(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (connectionCount() != expected && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertEquals(expected, connectionCount());
    }

    private int connectionCount() {
        synchronized (connectionLimiter) {
            return (int) ReflectionTestUtils.getField(connectionLimiter, "total");
        }
    }

    private WebTestClient.ResponseSpec issue(String token, String origin, String body) {
        return http.post()
                .uri(RealtimeIngressFilter.TICKET_PATH)
                .header(HttpHeaders.ORIGIN, origin)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.ResponseSpec websocketUpgrade(
            String origin, String protocol, String authorization, String query) {
        return websocketUpgrade(List.of(origin), protocol, authorization, query);
    }

    private WebTestClient.ResponseSpec websocketUpgrade(
            List<String> origins, String protocol, String authorization, String query) {
        String uri = RealtimeIngressFilter.WEBSOCKET_PATH + (query == null ? "" : "?" + query);
        return http.get()
                .uri(uri)
                .header(HttpHeaders.ORIGIN, origins.toArray(String[]::new))
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.CONNECTION, "Upgrade")
                .header("Sec-WebSocket-Protocol", protocol)
                .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                .header("Sec-WebSocket-Version", "13")
                .headers(headers -> {
                    if (authorization != null) headers.set(HttpHeaders.AUTHORIZATION, authorization);
                })
                .exchange();
    }

    private WebSocket openWebSocket(
            String origin, CompletableFuture<Void> closed, CompletableFuture<Void> authenticated) {
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                text.append(data);
                if (last) {
                    if ("{\"protocolVersion\":1,\"type\":\"AUTHENTICATED\"}".contentEquals(text)) {
                        authenticated.complete(null);
                    }
                    text.setLength(0);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                closed.complete(null);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                closed.completeExceptionally(error);
            }
        };
        return java.net.http.HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header(HttpHeaders.ORIGIN, origin)
                .subprotocols(RealtimeIngressFilter.SUBPROTOCOL)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + RealtimeIngressFilter.WEBSOCKET_PATH), listener)
                .join();
    }

    private String issueBody(String token, String origin) {
        byte[] body = issue(token, origin, "{}")
                .expectStatus()
                .isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String ticket(String body) {
        return JSON.readTree(body).path("ticket").asString();
    }

    private List<String> exchange(
            String origin, List<String> outbound, int expectedMessages, AtomicReference<CloseStatus> close) {
        if (close != null) {
            return exchangeUntilClose(origin, outbound, close);
        }
        List<String> messages = new CopyOnWriteArrayList<>();
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        var client = new ReactorNettyWebSocketClient(
                HttpClient.create(), () -> WebsocketClientSpec.builder().protocols(RealtimeIngressFilter.SUBPROTOCOL));
        client.execute(
                        URI.create("ws://127.0.0.1:" + port + RealtimeIngressFilter.WEBSOCKET_PATH),
                        headers,
                        session -> {
                            Flux<WebSocketMessage> inbound =
                                    session.receive().doOnNext(message -> messages.add(message.getPayloadAsText()));
                            Mono<Void> receive = inbound.take(expectedMessages).then();
                            Mono<Void> send = outbound.isEmpty()
                                    ? Mono.empty()
                                    : session.send(Flux.fromIterable(outbound)
                                            .delayElements(Duration.ofMillis(100))
                                            .map(session::textMessage));
                            return Mono.when(send, receive).then(session.close());
                        })
                .block(Duration.ofSeconds(6));
        return messages;
    }

    private List<String> exchangeUntilClose(String origin, List<String> outbound, AtomicReference<CloseStatus> close) {
        return exchangeUntilClose(
                origin,
                close,
                socket -> outbound.forEach(frame -> socket.sendText(frame, true).join()));
    }

    private List<String> exchangeBinaryUntilClose(
            String origin, ByteBuffer payload, AtomicReference<CloseStatus> close) {
        return exchangeUntilClose(
                origin, close, socket -> socket.sendBinary(payload, true).join());
    }

    private List<String> exchangeUntilClose(
            String origin, AtomicReference<CloseStatus> close, Consumer<WebSocket> sender) {
        List<String> messages = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                text.append(data);
                if (last) {
                    messages.add(text.toString());
                    text.setLength(0);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                close.set(new CloseStatus(statusCode, reason));
                closed.complete(null);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                closed.completeExceptionally(error);
            }
        };
        WebSocket socket = java.net.http.HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header(HttpHeaders.ORIGIN, origin)
                .subprotocols(RealtimeIngressFilter.SUBPROTOCOL)
                .buildAsync(URI.create("ws://127.0.0.1:" + port + RealtimeIngressFilter.WEBSOCKET_PATH), listener)
                .join();
        sender.accept(socket);
        try {
            closed.get(6, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("WebSocket close was not observed", exception);
        }
        return messages;
    }

    private static String auth(String ticket, String token) {
        return "{\"protocolVersion\":1,\"type\":\"AUTH\",\"ticket\":\""
                + ticket
                + "\",\"accessToken\":\""
                + token
                + "\"}";
    }

    private static DisposableServer identityStub() {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> {
                    if (request.uri().equals("/oauth2/token")) {
                        TOKEN_CALLS.incrementAndGet();
                        return request.receive()
                                .then(response.status(200)
                                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                        .sendString(
                                                Mono.just(
                                                        "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"identity.user-admission\"}"))
                                        .then());
                    }
                    ADMISSION_CALLS.incrementAndGet();
                    return response.status(ADMISSION_STATUS.get())
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .sendString(Mono.just(ADMISSION_BODY.get()))
                            .then();
                })
                .bindNow();
    }

    private static String userToken(Duration lifetime) {
        Instant now = Instant.now();
        return sign(ACTOR, "USER", Map.of("roles", List.of("STUDENT")), now.minusSeconds(2), now.plus(lifetime));
    }

    private static String serviceToken() {
        Instant now = Instant.now();
        return sign(
                "assessment-service",
                "SERVICE",
                Map.of("scope", List.of("identity.user-admission")),
                now.minusSeconds(2),
                now.plusSeconds(300));
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

    private static RSAKey key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("realtime-test")
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        @Bean
        @Primary
        ReactiveJwtDecoder decoder() throws Exception {
            var decoder =
                    NimbusReactiveJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }

        @Bean
        CapturingObservationHandler capturingObservationHandler() {
            return new CapturingObservationHandler();
        }
    }

    static final class CapturingObservationHandler implements ObservationHandler<Observation.Context> {
        private final List<String> completed = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            completed.add(context.getName()
                    + '|'
                    + context.getContextualName()
                    + '|'
                    + context.getAllKeyValues()
                    + '|'
                    + context.getError());
        }

        @Override
        public void onEvent(Observation.Event event, Observation.Context context) {
            completed.add(
                    "event|" + event.getName() + '|' + event.getContextualName() + '|' + context.getAllKeyValues());
        }

        void clear() {
            completed.clear();
        }

        String completed() {
            return String.join("\n", completed);
        }
    }
}
