package com.quizopia.classroom.security.admission;

import io.netty.channel.ChannelOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** A private, bounded dual-credential client; never attached to a browser forwarding client. */
public final class IdentityAdmissionClient implements AutoCloseable {
    public static final String USER_HEADER = "X-Quizopia-User-Evidence";
    public static final String SCOPE = "identity.user-admission";
    private static final Set<String> ROLES = Set.of("ADMIN", "STUDENT", "TEACHER");
    private final AdmissionProperties properties;
    private final ConnectionProvider pool;
    private final WebClient http;
    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public IdentityAdmissionClient(AdmissionProperties properties) {
        properties.validate();
        this.properties = properties;
        if (!properties.isEnabled()) {
            pool = null;
            http = null;
            manager = null;
            return;
        }
        pool = ConnectionProvider.builder("identity-admission")
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
        var registration = ClientRegistration.withRegistrationId("identity-admission")
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

    public Mono<Void> admit(Jwt user, String requiredRole) {
        return Mono.<Void>defer(() -> {
                    List<String> localRoles = userRoles(user);
                    if (!localRoles.contains(requiredRole)) return Mono.error(AdmissionFailure.forbidden());
                    if (!properties.isEnabled()) return Mono.error(AdmissionFailure.unavailable());
                    var request = OAuth2AuthorizeRequest.withClientRegistrationId("identity-admission")
                            .principal(new UsernamePasswordAuthenticationToken(
                                    "identity-admission-client", "unused", List.of()))
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
                                                .resolve("/internal/security/user-admissions"))
                                        .headers(headers -> headers.setBearerAuth(
                                                client.getAccessToken().getTokenValue()))
                                        .header(USER_HEADER, user.getTokenValue())
                                        .accept(MediaType.APPLICATION_JSON)
                                        .exchangeToMono(response -> {
                                            if (response.headers()
                                                    .contentType()
                                                    .filter(MediaType.APPLICATION_JSON::isCompatibleWith)
                                                    .isEmpty())
                                                return response.releaseBody()
                                                        .then(Mono.error(AdmissionFailure.unavailable()));
                                            int status = response.statusCode().value();
                                            return response.bodyToMono(byte[].class)
                                                    .map(bytes ->
                                                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                                                    .defaultIfEmpty("")
                                                    .flatMap(body -> {
                                                        if (status == 200) {
                                                            validateResponse(body, user, localRoles, requiredRole);
                                                            return Mono.<Void>empty();
                                                        }
                                                        if (status == 401 && invalidUserError(body))
                                                            return Mono.error(AdmissionFailure.invalidUser());
                                                        return Mono.error(AdmissionFailure.unavailable());
                                                    });
                                        });
                            })
                            .then(Mono.<Void>fromRunnable(() -> userRoles(user)));
                })
                .timeout(properties.getTotalTimeout())
                .onErrorMap(error -> error instanceof AdmissionFailure ? error : AdmissionFailure.unavailable());
    }

    private List<String> userRoles(Jwt user) {
        if (!"USER".equals(user.getClaims().get("principal_type"))
                || user.hasClaim("scope")
                || user.getIssuedAt() == null
                || user.getExpiresAt() == null
                || !user.getExpiresAt().isAfter(Instant.now())
                || user.getIssuedAt().isAfter(Instant.now())
                || !user.getExpiresAt().isAfter(user.getIssuedAt())) throw AdmissionFailure.invalidUser();
        if (!UUID.fromString(user.getSubject()).toString().equals(user.getSubject()))
            throw AdmissionFailure.invalidUser();
        Object value = user.getClaims().get("roles");
        if (!(value instanceof java.util.Collection<?> raw)) throw AdmissionFailure.invalidUser();
        var roles = new ArrayList<String>();
        for (Object item : raw) {
            if (!(item instanceof String role) || !ROLES.contains(role) || roles.contains(role))
                throw AdmissionFailure.invalidUser();
            roles.add(role);
        }
        return roles.stream().sorted().toList();
    }

    private void validateResponse(String body, Jwt user, List<String> tokenRoles, String requiredRole) {
        var root = json.readTree(body);
        if (!root.isObject()
                || root.size() != 2
                || !root.has("actorId")
                || !root.has("roles")
                || !root.path("actorId").isString()
                || !root.path("roles").isArray()) throw AdmissionFailure.unavailable();
        String actor = root.path("actorId").asString();
        if (!UUID.fromString(actor).toString().equals(actor) || !actor.equals(user.getSubject()))
            throw AdmissionFailure.unavailable();
        var roles = new ArrayList<String>();
        for (var value : root.path("roles")) {
            if (!value.isString() || !ROLES.contains(value.asString()) || roles.contains(value.asString()))
                throw AdmissionFailure.unavailable();
            roles.add(value.asString());
        }
        if (roles.isEmpty()
                || roles.size() > 3
                || !roles.contains("STUDENT")
                || !roles.equals(roles.stream().sorted().toList())) throw AdmissionFailure.unavailable();
        if (!roles.contains(requiredRole) || !roles.equals(tokenRoles)) throw AdmissionFailure.forbidden();
    }

    private boolean invalidUserError(String body) {
        var value = json.readTree(body);
        return value.isObject()
                && value.size() == 1
                && value.path("code").isString()
                && "USER_EVIDENCE_INVALID".equals(value.path("code").asString());
    }

    @Override
    public void close() {
        if (pool != null) pool.dispose();
    }
}
