package com.quizopia.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.quizopia.gateway.security.QuizopiaTokenClaims;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(GatewayAuthIntegrationTest.TestJwtConfiguration.class)
class GatewayAuthIntegrationTest {
    private static final String TRUSTED_ORIGIN = "http://localhost:3000";
    private static final String LOOKALIKE_ORIGIN = "http://localhost:3000.attacker.example";
    private static final String TEST_KEY_ID = "gateway-auth-integration-test";
    private static final String ISSUER = "https://identity.gateway.test";
    private static final RSAKey RSA_KEY = rsaKey();
    private static final JwtEncoder JWT_ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(RSA_KEY)));
    private static final ConcurrentLinkedQueue<CapturedRequest> CAPTURED = new ConcurrentLinkedQueue<>();
    private static final DisposableServer IDENTITY = identityStub();

    @Value("${local.server.port}")
    private int gatewayPort;

    private WebTestClient client;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("IDENTITY_SERVICE_URL", () -> "http://127.0.0.1:" + IDENTITY.port());
        registry.add("quizopia.gateway.browser.allowed-origins", () -> TRUSTED_ORIGIN);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
    }

    @BeforeEach
    void setUp() {
        CAPTURED.clear();
        client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + gatewayPort)
                .build();
    }

    @AfterAll
    static void stopIdentityStub() {
        IDENTITY.disposeNow();
    }

    @Test
    void exactAnonymousPostRoutesReachIdentityAndPreserveItsStatuses() {
        Map<String, HttpStatus> paths = Map.of(
                "/api/auth/register", HttpStatus.ACCEPTED,
                "/api/auth/email-verification/request", HttpStatus.ACCEPTED,
                "/api/auth/email-verification/confirm", HttpStatus.BAD_REQUEST,
                "/api/auth/login", HttpStatus.OK,
                "/api/auth/refresh", HttpStatus.OK,
                "/api/auth/logout", HttpStatus.NO_CONTENT);

        paths.forEach((path, expectedStatus) -> {
            CAPTURED.clear();
            client.post()
                    .uri(path)
                    .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(expectedStatus);
            CapturedRequest request = CAPTURED.remove();
            assertEquals(HttpMethod.POST.name(), request.method());
            assertEquals(path, request.path());
        });
    }

    @Test
    void meRequiresUserJwtAndForwardsAcceptedAuthorizationHeader() {
        client.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        assertTrue(CAPTURED.isEmpty());

        String serviceToken = serviceToken();
        client.get()
                .uri("/api/auth/me")
                .headers(headers -> headers.setBearerAuth(serviceToken))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertTrue(CAPTURED.isEmpty());

        String userToken = userToken();
        client.get()
                .uri("/api/auth/me")
                .headers(headers -> headers.setBearerAuth(userToken))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.username")
                .isEqualTo("gateway-user");
        assertEquals("Bearer " + userToken, CAPTURED.remove().authorization());
    }

    @Test
    void wrongMethodsAndNeighboringAuthPathsNeverBecomeAnonymous() {
        client.get().uri("/api/auth/login").exchange().expectStatus().isUnauthorized();
        client.put().uri("/api/auth/refresh").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/auth/logout").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/auth/foo").exchange().expectStatus().isUnauthorized();
        assertTrue(CAPTURED.isEmpty());
    }

    @Test
    void trustedCredentialedCorsIsExplicitAndUntrustedOriginsAreDenied() {
        client.post()
                .uri("/api/auth/login")
                .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, TRUSTED_ORIGIN)
                .expectHeader()
                .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader()
                .value(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, value -> assertNotEquals("*", value));
        assertEquals(TRUSTED_ORIGIN, CAPTURED.remove().origin());

        for (String rejected : List.of("https://attacker.example", LOOKALIKE_ORIGIN)) {
            CAPTURED.clear();
            client.post()
                    .uri("/api/auth/login")
                    .header(HttpHeaders.ORIGIN, rejected)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectHeader()
                    .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                    .expectHeader()
                    .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
            assertTrue(CAPTURED.isEmpty());
        }
    }

    @Test
    void trustedPreflightsAreHandledAtGatewayWithoutInvokingIdentity() {
        List<Preflight> preflights = List.of(
                new Preflight("/api/auth/login", HttpMethod.POST, HttpHeaders.CONTENT_TYPE),
                new Preflight("/api/auth/refresh", HttpMethod.POST, HttpHeaders.CONTENT_TYPE),
                new Preflight("/api/auth/logout", HttpMethod.POST, HttpHeaders.CONTENT_TYPE),
                new Preflight("/api/auth/me", HttpMethod.GET, HttpHeaders.AUTHORIZATION));

        for (Preflight preflight : preflights) {
            CAPTURED.clear();
            client.options()
                    .uri(preflight.path())
                    .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN)
                    .header(
                            HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                            preflight.method().name())
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, preflight.requestHeader())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, TRUSTED_ORIGIN)
                    .expectHeader()
                    .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            assertTrue(CAPTURED.isEmpty());
        }
    }

    @Test
    void gatewayPreservesCookieOriginAndSetCookieForRefreshAndLogout() {
        String refreshCookie = "quizopia_refresh=synthetic-refresh-for-gateway-test";
        client.post()
                .uri("/api/auth/refresh")
                .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN)
                .header(HttpHeaders.COOKIE, refreshCookie)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.SET_COOKIE, refreshSetCookie());
        CapturedRequest refresh = CAPTURED.remove();
        assertEquals(refreshCookie, refresh.cookie());
        assertEquals(TRUSTED_ORIGIN, refresh.origin());

        String logoutCookie = "quizopia_refresh=synthetic-rotated-for-gateway-test";
        client.post()
                .uri("/api/auth/logout")
                .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN)
                .header(HttpHeaders.COOKIE, logoutCookie)
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectHeader()
                .valueEquals(HttpHeaders.SET_COOKIE, logoutSetCookie());
        CapturedRequest logout = CAPTURED.remove();
        assertEquals(logoutCookie, logout.cookie());
        assertEquals(TRUSTED_ORIGIN, logout.origin());
    }

    @Test
    void gatewayPreservesLoginSetCookieWithoutRewritingAttributes() {
        client.post()
                .uri("/api/auth/login")
                .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(HttpHeaders.SET_COOKIE, loginSetCookie());
    }

    @Test
    void exactPublicPostsIgnoreMalformedAndExpiredBearerWithoutWeakeningProtectedRoutes() {
        for (String bearer : List.of("malformed", expiredUserToken())) {
            CAPTURED.clear();
            client.post()
                    .uri("/api/auth/login")
                    .headers(headers -> headers.setBearerAuth(bearer))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .isOk();
            assertEquals("Bearer " + bearer, CAPTURED.remove().authorization());
        }

        String expired = expiredUserToken();
        for (String path : List.of("/api/auth/refresh", "/api/auth/logout")) {
            CAPTURED.clear();
            client.post()
                    .uri(path)
                    .header(HttpHeaders.ORIGIN, TRUSTED_ORIGIN)
                    .header(HttpHeaders.COOKIE, "quizopia_refresh=synthetic-expired-bearer-test")
                    .headers(headers -> headers.setBearerAuth(expired))
                    .exchange()
                    .expectStatus()
                    .value(status -> assertTrue(status == 200 || status == 204));
            assertEquals("Bearer " + expired, CAPTURED.remove().authorization());
        }

        client.get()
                .uri("/api/auth/me")
                .headers(headers -> headers.setBearerAuth("malformed"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/api/auth/me")
                .headers(headers -> headers.setBearerAuth(expired))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/api/auth/foo")
                .headers(headers -> headers.setBearerAuth("malformed"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void trustedSignatureWithWrongIssuerIsRejected() {
        client.get()
                .uri("/api/auth/me")
                .headers(headers -> headers.setBearerAuth(userToken("https://wrong-issuer.example")))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        assertTrue(CAPTURED.isEmpty());
    }

    private static DisposableServer identityStub() {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((request, response) -> request.receive()
                        .aggregate()
                        .asString()
                        .defaultIfEmpty("")
                        .flatMap(ignored -> {
                            String path = request.fullPath();
                            CAPTURED.add(new CapturedRequest(
                                    request.method().name(),
                                    path,
                                    request.requestHeaders().get(HttpHeaders.COOKIE),
                                    request.requestHeaders().get(HttpHeaders.ORIGIN),
                                    request.requestHeaders().get(HttpHeaders.AUTHORIZATION)));
                            return identityResponse(path, response);
                        }))
                .bindNow();
    }

    private static Mono<Void> identityResponse(String path, reactor.netty.http.server.HttpServerResponse response) {
        return switch (path) {
            case "/api/auth/register", "/api/auth/email-verification/request" ->
                response.status(202)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("{\"status\":\"ACCEPTED\"}"))
                        .then();
            case "/api/auth/email-verification/confirm" ->
                response.status(400)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("{\"code\":\"AUTH_VERIFICATION_FAILED\"}"))
                        .then();
            case "/api/auth/login" ->
                response.status(200)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header(HttpHeaders.SET_COOKIE, loginSetCookie())
                        .sendString(Mono.just("{\"accessToken\":\"synthetic-response-value\"}"))
                        .then();
            case "/api/auth/refresh" ->
                response.status(200)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header(HttpHeaders.SET_COOKIE, refreshSetCookie())
                        .sendString(Mono.just("{\"accessToken\":\"synthetic-response-value\"}"))
                        .then();
            case "/api/auth/logout" ->
                response.status(204)
                        .header(HttpHeaders.SET_COOKIE, logoutSetCookie())
                        .send()
                        .then();
            case "/api/auth/me" ->
                response.status(200)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .sendString(Mono.just("{\"id\":\"00000000-0000-0000-0000-000000000001\","
                                + "\"username\":\"gateway-user\",\"email\":\"gateway-user@gmail.com\","
                                + "\"roles\":[\"STUDENT\"]}"))
                        .then();
            default -> response.status(404).send().then();
        };
    }

    private static String loginSetCookie() {
        return "quizopia_refresh=synthetic-login; Path=/api/auth; HttpOnly; SameSite=Lax";
    }

    private static String refreshSetCookie() {
        return "quizopia_refresh=synthetic-rotated; Path=/api/auth; HttpOnly; SameSite=Lax";
    }

    private static String logoutSetCookie() {
        return "quizopia_refresh=; Path=/api/auth; Max-Age=0; HttpOnly; SameSite=Lax";
    }

    private static String userToken() {
        return userToken(ISSUER);
    }

    private static String userToken(String issuer) {
        return token(
                UUID.randomUUID().toString(),
                QuizopiaTokenClaims.USER,
                Map.of(QuizopiaTokenClaims.ROLES, List.of("STUDENT")),
                issuer,
                Instant.now().truncatedTo(ChronoUnit.SECONDS),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(300));
    }

    private static String serviceToken() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return token(
                "assessment-service",
                QuizopiaTokenClaims.SERVICE,
                Map.of(QuizopiaTokenClaims.SCOPE, List.of("classroom.membership.read")),
                ISSUER,
                now,
                now.plusSeconds(300));
    }

    private static String expiredUserToken() {
        Instant expiredAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        return token(
                UUID.randomUUID().toString(),
                QuizopiaTokenClaims.USER,
                Map.of(QuizopiaTokenClaims.ROLES, List.of("STUDENT")),
                ISSUER,
                expiredAt.minusSeconds(300),
                expiredAt);
    }

    private static String token(
            String subject,
            String principalType,
            Map<String, Object> additionalClaims,
            String issuer,
            Instant issuedAt,
            Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(QuizopiaTokenClaims.PRINCIPAL_TYPE, principalType);
        additionalClaims.forEach(claims::claim);
        return JWT_ENCODER
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256)
                                .keyId(TEST_KEY_ID)
                                .build(),
                        claims.build()))
                .getTokenValue();
    }

    private static RSAKey rsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(TEST_KEY_ID)
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create test RSA key", exception);
        }
    }

    record CapturedRequest(String method, String path, String cookie, String origin, String authorization) {}

    record Preflight(String path, HttpMethod method, String requestHeader) {}

    @TestConfiguration
    static class TestJwtConfiguration {
        @Bean
        @Primary
        ReactiveJwtDecoder gatewayTestJwtDecoder() throws Exception {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(RSA_KEY.toRSAPublicKey())
                    .build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
}
