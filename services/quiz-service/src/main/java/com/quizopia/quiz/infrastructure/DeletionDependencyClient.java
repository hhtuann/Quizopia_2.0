package com.quizopia.quiz.infrastructure;

import com.quizopia.quiz.application.DeletionDependencies;
import com.quizopia.quiz.configuration.DeletionDependencyProperties;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dedicated bounded private transport for the FB-015 deletion coordinator, mirroring the
 * Assessment source-client pattern: client-credentials SERVICE JWTs for exactly
 * quiz.deletion.revoke and quiz.deletion.purge, no cookies/redirects/retries, strict JSON
 * parsing and no quiz content in any request. Transport failures surface as
 * {@link DependencyUnavailable} so the saga can park in WAITING_DEPENDENCY and retry.
 */
public final class DeletionDependencyClient implements DeletionDependencies, AutoCloseable {
    public static final String SCOPE_REVOKE = "quiz.deletion.revoke";
    public static final String SCOPE_PURGE = "quiz.deletion.purge";

    private final DeletionDependencyProperties properties;
    private final ConnectionProvider pool;
    private final WebClient http;
    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public DeletionDependencyClient(DeletionDependencyProperties properties) {
        this.properties = properties;
        properties.validate();
        if (!properties.isEnabled()) {
            pool = null;
            http = null;
            manager = null;
            return;
        }
        pool = ConnectionProvider.builder("quiz-deletion-dependencies")
                .maxConnections(properties.getMaxConnections())
                .pendingAcquireMaxCount(properties.getPendingAcquires())
                .pendingAcquireTimeout(properties.getConnectTimeout())
                .build();
        var transport = HttpClient.create(pool)
                .followRedirect(false)
                .disableRetry(true)
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getResponseTimeout());
        http = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(65536))
                .build();
        var registrations = new InMemoryReactiveClientRegistrationRepository(List.of(SCOPE_REVOKE, SCOPE_PURGE).stream()
                .map(scope -> ClientRegistration.withRegistrationId(scope)
                        .clientId(properties.getClientId())
                        .clientSecret(properties.getClientSecret())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .tokenUri(properties
                                .getIdentityBaseUrl()
                                .resolve("/oauth2/token")
                                .toString())
                        .scope(scope)
                        .build())
                .toList());
        var tokens = new WebClientReactiveClientCredentialsTokenResponseClient();
        tokens.setWebClient(http);
        var provider = new ClientCredentialsReactiveOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokens);
        provider.setClockSkew(Duration.ofSeconds(5));
        var clients = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                registrations, new InMemoryReactiveOAuth2AuthorizedClientService(registrations));
        clients.setAuthorizedClientProvider(provider);
        manager = clients;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public URI assessmentBaseUrl() {
        return properties.getAssessmentBaseUrl();
    }

    public URI proctoringBaseUrl() {
        return properties.getProctoringBaseUrl();
    }

    /** Idempotent fence installation; any 200 answer (accepted or already-revoked) is success. */
    public void revokeBatch(URI base, List<Operation> operations) {
        call(base.resolve("/internal/security/quiz-revocations/batch"), SCOPE_REVOKE, operations);
    }

    /** Returns null when the fence is not installed yet (404); never guesses. */
    public RevocationStatus revocationStatus(URI base, UUID quizId) {
        JsonNode body = fetch(base.resolve("/internal/security/quiz-revocations/" + quizId + "/status"), SCOPE_REVOKE);
        if (body == null) return null;
        return new RevocationStatus(instant(body, "revokedAt"), instant(body, "acknowledgedAt"));
    }

    public List<PurgeOutcome> purgeBatch(URI base, List<Operation> operations) {
        JsonNode body = call(base.resolve("/internal/security/quiz-purgings/batch"), SCOPE_PURGE, operations);
        var outcomes = new java.util.ArrayList<PurgeOutcome>(body.size());
        for (JsonNode item : body) {
            if (item == null || !item.isObject() || item.size() != 4) throw unavailable();
            outcomes.add(new PurgeOutcome(uuid(item, "operationId"), uuid(item, "quizId"), text(item, "status")));
        }
        return List.copyOf(outcomes);
    }

    /** Returns null when no fence/purge state exists (404). */
    public PurgeStatus purgeStatus(URI base, UUID quizId) {
        JsonNode body = fetch(base.resolve("/internal/security/quiz-purgings/" + quizId + "/status"), SCOPE_PURGE);
        if (body == null) return null;
        return new PurgeStatus(
                instant(body, "acknowledgedAt"),
                body.path("purged").isBoolean() && body.path("purged").asBoolean(),
                body.path("retentionHoldActive").isBoolean()
                        && body.path("retentionHoldActive").asBoolean());
    }

    private JsonNode call(URI uri, String scope, List<Operation> operations) {
        var payload = new StringBuilder("{\"operations\":[");
        for (int i = 0; i < operations.size(); i++) {
            if (i > 0) payload.append(',');
            payload.append("{\"operationId\":\"")
                    .append(operations.get(i).operationId())
                    .append("\",\"quizId\":\"")
                    .append(operations.get(i).quizId())
                    .append("\"}");
        }
        payload.append("]}");
        return exchange(uri, scope, payload.toString());
    }

    private JsonNode fetch(URI uri, String scope) {
        try {
            var request = OAuth2AuthorizeRequest.withClientRegistrationId(scope)
                    .principal(
                            new UsernamePasswordAuthenticationToken("quiz-deletion-coordinator", "unused", List.of()))
                    .build();
            return Mono.defer(() -> manager.authorize(request))
                    .switchIfEmpty(Mono.error(new DependencyUnavailable()))
                    .flatMap(client -> {
                        if (client.getAccessToken().getExpiresAt() == null
                                || !client.getAccessToken().getExpiresAt().isAfter(Instant.now()))
                            return Mono.error(new DependencyUnavailable());
                        return http.get()
                                .uri(uri)
                                .headers(h ->
                                        h.setBearerAuth(client.getAccessToken().getTokenValue()))
                                .accept(MediaType.APPLICATION_JSON)
                                .exchangeToMono(response -> {
                                    int status = response.statusCode().value();
                                    return response.bodyToMono(byte[].class)
                                            .defaultIfEmpty(new byte[0])
                                            .map(bytes -> {
                                                if (status == 404) return (JsonNode) null;
                                                if (status != 200) throw unavailable();
                                                var tree = bytes.length == 0 ? null : json.readTree(bytes);
                                                if (tree == null || !tree.isObject()) throw unavailable();
                                                return tree;
                                            });
                                });
                    })
                    .timeout(properties.getTotalTimeout())
                    .block(properties.getTotalTimeout().plusMillis(100));
        } catch (DependencyUnavailable failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private JsonNode exchange(URI uri, String scope, String payload) {
        try {
            var request = OAuth2AuthorizeRequest.withClientRegistrationId(scope)
                    .principal(
                            new UsernamePasswordAuthenticationToken("quiz-deletion-coordinator", "unused", List.of()))
                    .build();
            return Mono.defer(() -> manager.authorize(request))
                    .switchIfEmpty(Mono.error(new DependencyUnavailable()))
                    .flatMap(client -> {
                        if (client.getAccessToken().getExpiresAt() == null
                                || !client.getAccessToken().getExpiresAt().isAfter(Instant.now()))
                            return Mono.error(new DependencyUnavailable());
                        return http.post()
                                .uri(uri)
                                .headers(h ->
                                        h.setBearerAuth(client.getAccessToken().getTokenValue()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .bodyValue(payload)
                                .exchangeToMono(response -> {
                                    int status = response.statusCode().value();
                                    return response.bodyToMono(byte[].class)
                                            .defaultIfEmpty(new byte[0])
                                            .map(bytes -> {
                                                if (status != 200) throw unavailable();
                                                var tree = bytes.length == 0 ? null : json.readTree(bytes);
                                                if (tree == null || !(tree.isObject() || tree.isArray()))
                                                    throw unavailable();
                                                return tree;
                                            });
                                });
                    })
                    .timeout(properties.getTotalTimeout())
                    .block(properties.getTotalTimeout().plusMillis(100));
        } catch (DependencyUnavailable failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static DependencyUnavailable unavailable() {
        return new DependencyUnavailable();
    }

    private static Instant instant(JsonNode body, String field) {
        var value = body.path(field);
        if (value.isNull() || value.isMissingNode()) return null;
        if (!value.isString()) throw unavailable();
        return Instant.parse(value.asString());
    }

    private static UUID uuid(JsonNode body, String field) {
        var value = body.path(field);
        if (!value.isString()) throw unavailable();
        return UUID.fromString(value.asString());
    }

    private static String text(JsonNode body, String field) {
        var value = body.path(field);
        if (!value.isString()) throw unavailable();
        return value.asString();
    }

    @Override
    public void close() {
        if (pool != null) pool.dispose();
    }
}
