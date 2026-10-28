package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.application.StudentProfiles;
import com.quizopia.assessment.security.admission.AdmissionProperties;
import io.netty.channel.ChannelOption;
import java.util.*;
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

/** Fail-open display-name enrichment for owner results only. One bounded batch call per page;
 * any failure, non-200 status, non-JSON or defensively invalid entry yields an empty map and
 * never throws, so an Identity outage cannot stall or fail the authorized results page. */
public final class StudentProfileClient implements StudentProfiles, AutoCloseable {
    public static final String SCOPE = "identity.user-profile.read";
    public static final int MAX_BATCH = 100;
    private static final String CANONICAL_UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final AdmissionProperties admission;
    private final java.time.Duration totalTimeout;
    private final ConnectionProvider pool;
    private final WebClient http;
    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public StudentProfileClient(StudentProfileProperties profiles, AdmissionProperties admission) {
        profiles.validate();
        admission.validate();
        this.admission = admission;
        this.totalTimeout = profiles.getTotalTimeout();
        if (!profiles.isEnabled()) {
            pool = null;
            http = null;
            manager = null;
            return;
        }
        if (!admission.isEnabled()) throw new IllegalStateException("Student profiles require Identity admission");
        pool = ConnectionProvider.builder("student-profiles")
                .maxConnections(profiles.getMaxConnections())
                .pendingAcquireMaxCount(profiles.getPendingAcquires())
                .pendingAcquireTimeout(profiles.getConnectTimeout())
                .build();
        HttpClient transport = HttpClient.create(pool)
                .followRedirect(false)
                .disableRetry(true)
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(profiles.getConnectTimeout().toMillis()))
                .responseTimeout(profiles.getResponseTimeout());
        http = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(131072))
                .build();
        var registration = ClientRegistration.withRegistrationId(SCOPE)
                .clientId(admission.getClientId())
                .clientSecret(admission.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(
                        admission.getIdentityBaseUrl().resolve("/oauth2/token").toString())
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

    private static boolean canonicalUuid(String value) {
        return value != null && value.matches(CANONICAL_UUID);
    }

    private static boolean usableName(String value) {
        // The minimal wire contract does not distinguish display names (100 code points)
        // from legacy usernames (255); accept either without truncating or normalizing identity.
        return value != null
                && !value.isBlank()
                && value.codePointCount(0, value.length()) <= 255
                && value.codePoints()
                        .noneMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.SURROGATE);
    }

    private Map<UUID, String> parse(byte[] body, Set<UUID> requested) {
        var root = json.readTree(body);
        if (!root.isObject() || root.size() != 1 || !root.path("items").isArray()) return Map.of();
        if (root.path("items").size() > MAX_BATCH) return Map.of();
        var names = new HashMap<UUID, String>();
        for (JsonNode item : root.path("items")) {
            if (!item.isObject()
                    || item.size() != 2
                    || !item.path("studentId").isString()
                    || !item.path("displayName").isString()) continue;
            String id = item.path("studentId").asString();
            String name = item.path("displayName").asString();
            // Canonical UUID and usable name required; entries for ids we did not ask about are dropped.
            if (!canonicalUuid(id) || !usableName(name)) continue;
            var student = UUID.fromString(id);
            if (requested.contains(student)) names.put(student, name);
        }
        return Collections.unmodifiableMap(names);
    }

    @Override
    public Map<UUID, String> displayNames(Set<UUID> studentIds) {
        if (http == null || studentIds == null || studentIds.isEmpty() || studentIds.size() > MAX_BATCH)
            return Map.of();
        var ids = new ArrayList<UUID>();
        for (UUID id : studentIds) if (id != null) ids.add(id);
        if (ids.size() > MAX_BATCH) return Map.of();
        var requested = Set.copyOf(ids);
        try {
            return Mono.defer(() -> manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(SCOPE)
                                    .principal(new UsernamePasswordAuthenticationToken(
                                            "student-profile-client", "unused", List.of()))
                                    .build())
                            .switchIfEmpty(Mono.error(new IllegalStateException("unavailable")))
                            .flatMap(client -> http.post()
                                    .uri(admission
                                            .getIdentityBaseUrl()
                                            .resolve("/internal/security/user-profiles/batch"))
                                    .headers(header -> header.setBearerAuth(
                                            client.getAccessToken().getTokenValue()))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .accept(MediaType.APPLICATION_JSON)
                                    .bodyValue(json.writeValueAsString(Map.of("studentIds", ids)))
                                    .exchangeToMono(response -> {
                                        if (response.statusCode().value() != 200
                                                || response.headers()
                                                        .contentType()
                                                        .filter(MediaType.APPLICATION_JSON::isCompatibleWith)
                                                        .isEmpty())
                                            return response.releaseBody()
                                                    .then(Mono.error(new IllegalStateException("unavailable")));
                                        return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]);
                                    })))
                    .map(body -> parse(body, requested))
                    .timeout(totalTimeout)
                    .block(totalTimeout.plusMillis(100));
        } catch (RuntimeException failure) {
            return Map.of();
        }
    }

    @Override
    public void close() {
        if (pool != null) pool.dispose();
    }
}
