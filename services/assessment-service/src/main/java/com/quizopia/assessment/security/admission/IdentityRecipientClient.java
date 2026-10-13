package com.quizopia.assessment.security.admission;

import io.netty.channel.ChannelOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;

/** Private SERVICE eligibility client and credential-free technical readiness probe; never forwards USER evidence. */
public final class IdentityRecipientClient implements AutoCloseable {
    public static final String SCOPE = "identity.notice-recipient.read";
    private final AdmissionProperties properties;
    private final ConnectionProvider pool;
    private final WebClient http;
    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public IdentityRecipientClient(AdmissionProperties properties) {
        properties.validate();
        this.properties = properties;
        if (!properties.isEnabled()) {
            pool = null;
            http = null;
            manager = null;
            return;
        }
        pool = ConnectionProvider.builder("identity-recipient")
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
        var registration = ClientRegistration.withRegistrationId("identity-recipient")
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

    public void requireEdgeInbox(java.net.URI origin) {
        try {
            if (http == null
                    || origin == null
                    || origin.getHost() == null
                    || origin.getUserInfo() != null
                    || origin.getQuery() != null
                    || origin.getFragment() != null
                    || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))
                    || !("https".equals(origin.getScheme())
                            || properties.isAllowInsecureLoopback()
                                    && "http".equals(origin.getScheme())
                                    && java.util.Set.of("localhost", "127.0.0.1", "[::1]")
                                            .contains(origin.getHost()))) throw AdmissionFailure.unavailable();
            http.get()
                    .uri(origin.resolve("/actuator/health/assessment-inbox"))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> {
                        if (response.statusCode().value() != 200
                                || response.headers()
                                        .contentType()
                                        .filter(MediaType.APPLICATION_JSON::isCompatibleWith)
                                        .isEmpty())
                            return response.releaseBody().then(Mono.error(AdmissionFailure.unavailable()));
                        return response.bodyToMono(byte[].class)
                                .switchIfEmpty(Mono.error(AdmissionFailure.unavailable()))
                                .map(bytes -> {
                                    var value = json.readTree(bytes);
                                    if (!value.isObject()
                                            || value.size() != 1
                                            || !value.path("status").isString()
                                            || !"UP".equals(value.path("status").asString()))
                                        throw AdmissionFailure.unavailable();
                                    return true;
                                });
                    })
                    .timeout(properties.getTotalTimeout())
                    .block(properties.getTotalTimeout().plusMillis(100));
        } catch (RuntimeException e) {
            throw new com.quizopia.assessment.application.PublicationFailure(503, "DEPENDENCY_UNAVAILABLE");
        }
    }

    public com.quizopia.assessment.application.ClosureDependencies.Eligibility eligible(UUID student) {
        long validUntil =
                System.nanoTime() + Math.min(properties.getTotalTimeout().toNanos(), 5_000_000_000L);
        try {
            return Mono.defer(() -> {
                        if (!properties.isEnabled()) return Mono.error(AdmissionFailure.unavailable());
                        var request = OAuth2AuthorizeRequest.withClientRegistrationId("identity-recipient")
                                .principal(new UsernamePasswordAuthenticationToken(
                                        "identity-recipient-client", "unused", List.of()))
                                .build();
                        return manager.authorize(request)
                                .switchIfEmpty(Mono.error(AdmissionFailure.unavailable()))
                                .flatMap(client -> {
                                    if (client.getAccessToken().getExpiresAt() == null
                                            || !client.getAccessToken()
                                                    .getExpiresAt()
                                                    .isAfter(Instant.now()))
                                        return Mono.error(AdmissionFailure.unavailable());
                                    return http.post()
                                            .uri(properties
                                                    .getIdentityBaseUrl()
                                                    .resolve("/internal/security/notice-recipients/" + student
                                                            + "/eligibility"))
                                            .headers(h -> h.setBearerAuth(
                                                    client.getAccessToken().getTokenValue()))
                                            .accept(MediaType.APPLICATION_JSON)
                                            .exchangeToMono(response -> {
                                                if (response.statusCode().value() != 200
                                                        || response.headers()
                                                                .contentType()
                                                                .filter(MediaType.APPLICATION_JSON::isCompatibleWith)
                                                                .isEmpty())
                                                    return response.releaseBody()
                                                            .then(Mono.error(AdmissionFailure.unavailable()));
                                                return response.bodyToMono(byte[].class)
                                                        .switchIfEmpty(Mono.error(AdmissionFailure.unavailable()))
                                                        .map(bytes -> {
                                                            var root = json.readTree(bytes);
                                                            if (!root.isObject()
                                                                    || root.size() != 3
                                                                    || !root.path("studentId")
                                                                            .isString()
                                                                    || !root.path("eligible")
                                                                            .isBoolean()
                                                                    || !root.path("checkedAt")
                                                                            .isString()
                                                                    || !student.toString()
                                                                            .equals(root.path("studentId")
                                                                                    .asString()))
                                                                throw AdmissionFailure.unavailable();
                                                            var checked = Instant.parse(root.path("checkedAt")
                                                                    .asString());
                                                            var now = Instant.now();
                                                            if (checked.isAfter(now)
                                                                    || checked.plusSeconds(5)
                                                                            .isBefore(now))
                                                                throw AdmissionFailure.unavailable();
                                                            return new com.quizopia.assessment.application
                                                                    .ClosureDependencies.Eligibility(
                                                                    student,
                                                                    root.path("eligible")
                                                                            .asBoolean(),
                                                                    checked,
                                                                    validUntil);
                                                        });
                                            });
                                });
                    })
                    .timeout(properties.getTotalTimeout())
                    .block(properties.getTotalTimeout().plusMillis(100));
        } catch (RuntimeException e) {
            throw new com.quizopia.assessment.application.PublicationFailure(503, "DEPENDENCY_UNAVAILABLE");
        }
    }

    @Override
    public void close() {
        if (pool != null) pool.dispose();
    }
}
