package com.quizopia.gateway.realtime;

import io.netty.channel.ChannelOption;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A private, bounded Gateway-to-Assessment client. Uses only the dedicated W4-B1 Identity-issued
 * SERVICE token (client_credentials, audience quizopia-assessment-realtime-authority, exact
 * scope). Forwards the original USER evidence header verbatim; never trusts actor identifiers from
 * the browser. No positive authorization cache. Never logs tokens, evidence or error bodies.
 */
public final class RealtimeAuthorityClient implements AutoCloseable {
    public static final String SCOPE = "assessment.realtime.subscription-authorize";
    public static final String AUDIENCE = "quizopia-assessment-realtime-authority";
    private static final String USER_HEADER = "X-Quizopia-User-Evidence";
    private final RealtimeAuthorityProperties properties;
    private final ConnectionProvider pool;
    private final WebClient http;
    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public RealtimeAuthorityClient(RealtimeAuthorityProperties properties) {
        properties.validate();
        this.properties = properties;
        if (!properties.isEnabled()) {
            pool = null;
            http = null;
            manager = null;
            return;
        }
        pool = ConnectionProvider.builder("realtime-authority")
                .maxConnections(properties.getMaxConnections())
                .pendingAcquireMaxCount(properties.getPendingAcquires())
                .pendingAcquireTimeout(properties.getConnectTimeout())
                .build();
        HttpClient transport = HttpClient.create(pool)
                .followRedirect(false)
                .disableRetry(true)
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getResponseTimeout());
        // A dedicated builder avoids inherited cookies, caller headers, request dumps and trace attributes.
        http = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4096))
                .build();
        var registration = ClientRegistration.withRegistrationId("realtime-authority")
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(
                        properties.getIdentityBaseUrl().resolve("/oauth2/token").toString())
                .scope(SCOPE)
                .build();
        var registrations = new InMemoryReactiveClientRegistrationRepository(registration);
        var tokenClient = new WebClientReactiveClientCredentialsTokenResponseClient();
        tokenClient.setWebClient(http);
        var provider = new ClientCredentialsReactiveOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokenClient);
        provider.setClockSkew(java.time.Duration.ofSeconds(5));
        var authorized = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                registrations, new InMemoryReactiveOAuth2AuthorizedClientService(registrations));
        authorized.setAuthorizedClientProvider(provider);
        manager = authorized;
    }

    public enum Outcome {
        AUTHORIZED,
        NOT_ACCESSIBLE,
        FORBIDDEN,
        USER_EVIDENCE_INVALID,
        UNAVAILABLE
    }

    public record Decision(Outcome outcome) {}

    public Mono<Decision> authorize(String userEvidence, String resourceKind, String resourceId) {
        return Mono.<Decision>defer(() -> {
                    if (!properties.isEnabled()) return Mono.just(new Decision(Outcome.UNAVAILABLE));
                    if (userEvidence == null || userEvidence.isEmpty())
                        return Mono.just(new Decision(Outcome.USER_EVIDENCE_INVALID));
                    var request = OAuth2AuthorizeRequest.withClientRegistrationId("realtime-authority")
                            .principal(new UsernamePasswordAuthenticationToken(
                                    "realtime-authority-client", "unused", List.of()))
                            .build();
                    return manager.authorize(request)
                            .flatMap(client -> {
                                if (client.getAccessToken().getExpiresAt() == null
                                        || !client.getAccessToken()
                                                .getExpiresAt()
                                                .isAfter(Instant.now()))
                                    return Mono.just(new Decision(Outcome.UNAVAILABLE));
                                return http.post()
                                        .uri(properties
                                                .getAssessmentBaseUrl()
                                                .resolve("/internal/realtime/subscription-authorizations"))
                                        .headers(headers -> headers.setBearerAuth(
                                                client.getAccessToken().getTokenValue()))
                                        .header(USER_HEADER, userEvidence)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .accept(MediaType.APPLICATION_JSON)
                                        .bodyValue(authorizationBody(resourceKind, resourceId))
                                        .exchangeToMono(response -> {
                                            if (response.headers()
                                                    .contentType()
                                                    .filter(MediaType.APPLICATION_JSON::isCompatibleWith)
                                                    .isEmpty())
                                                return response.releaseBody()
                                                        .then(Mono.just(new Decision(Outcome.UNAVAILABLE)));
                                            int status = response.statusCode().value();
                                            return response.bodyToMono(byte[].class)
                                                    .map(bytes ->
                                                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                                                    .defaultIfEmpty("")
                                                    .map(body -> interpret(status, body, resourceKind, resourceId));
                                        });
                            })
                            .defaultIfEmpty(new Decision(Outcome.UNAVAILABLE));
                })
                .timeout(properties.getTotalTimeout())
                .onErrorReturn(new Decision(Outcome.UNAVAILABLE));
    }

    private String authorizationBody(String resourceKind, String resourceId) {
        return "{\"resourceKind\":\"" + resourceKind + "\",\"resourceId\":\"" + resourceId + "\"}";
    }

    private Decision interpret(int status, String body, String resourceKind, String resourceId) {
        if (status == 200) return authorized(body, resourceKind, resourceId);
        if (status == 404) return new Decision(Outcome.NOT_ACCESSIBLE);
        if (status == 403) return new Decision(Outcome.FORBIDDEN);
        if (status == 401 && userEvidenceInvalid(body)) return new Decision(Outcome.USER_EVIDENCE_INVALID);
        return new Decision(Outcome.UNAVAILABLE);
    }

    private Decision authorized(String body, String resourceKind, String resourceId) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null
                    || !root.isObject()
                    || root.size() != 4
                    || !resourceKind.equals(text(root, "resourceKind"))
                    || !resourceId.equals(text(root, "resourceId"))
                    || !root.path("authorized").isBoolean()
                    || !root.path("authorized").asBoolean()
                    || !root.path("checkedAt").isString()
                    || !Instant.parse(root.path("checkedAt").asString()).isAfter(Instant.EPOCH))
                return new Decision(Outcome.UNAVAILABLE);
            return new Decision(Outcome.AUTHORIZED);
        } catch (RuntimeException invalid) {
            return new Decision(Outcome.UNAVAILABLE);
        }
    }

    private boolean userEvidenceInvalid(String body) {
        try {
            JsonNode value = json.readTree(body);
            return value != null
                    && value.isObject()
                    && value.size() == 1
                    && "USER_EVIDENCE_INVALID".equals(text(value, "code"));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static String text(JsonNode root, String field) {
        return root.path(field).isString() ? root.path(field).asString() : null;
    }

    @Override
    public void close() {
        if (pool != null) pool.dispose();
    }
}
