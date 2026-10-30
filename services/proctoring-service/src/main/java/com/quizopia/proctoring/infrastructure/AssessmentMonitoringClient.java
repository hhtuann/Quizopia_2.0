package com.quizopia.proctoring.infrastructure;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.security.admission.AdmissionProperties;
import io.netty.channel.ChannelOption;
import java.time.Instant;
import java.util.*;
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
import tools.jackson.databind.json.JsonMapper;

/** Dedicated bounded private transport. No cookies, redirects, positive authority cache or browser forwarding. */
public final class AssessmentMonitoringClient implements MonitoringAuthority, AutoCloseable {
    public static final String SCOPE = "assessment.proctoring-authority.read";
    private final MonitoringProperties monitoring;
    private final AdmissionProperties bounds;
    private final ConnectionProvider pool;
    private final WebClient http;
    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .findAndAddModules()
            .build();

    public AssessmentMonitoringClient(MonitoringProperties monitoring, AdmissionProperties bounds) {
        this.monitoring = monitoring;
        this.bounds = bounds;
        bounds.validate();
        monitoring.validate(false, bounds.isAllowInsecureLoopback());
        pool = ConnectionProvider.builder("proctoring-authority")
                .maxConnections(bounds.getMaxConnections())
                .pendingAcquireMaxCount(bounds.getPendingAcquires())
                .pendingAcquireTimeout(bounds.getConnectTimeout())
                .build();
        var transport = HttpClient.create(pool)
                .followRedirect(false)
                .disableRetry(true)
                .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(bounds.getConnectTimeout().toMillis()))
                .responseTimeout(bounds.getResponseTimeout());
        http = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4096))
                .build();
        var registration = ClientRegistration.withRegistrationId(SCOPE)
                .clientId(bounds.getClientId())
                .clientSecret(bounds.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(bounds.getIdentityBaseUrl().resolve("/oauth2/token").toString())
                .scope(SCOPE)
                .build();
        var registrations = new InMemoryReactiveClientRegistrationRepository(registration);
        var tokens = new WebClientReactiveClientCredentialsTokenResponseClient();
        tokens.setWebClient(http);
        var provider = new ClientCredentialsReactiveOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokens);
        provider.setClockSkew(java.time.Duration.ofSeconds(5));
        var clients = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                registrations, new InMemoryReactiveOAuth2AuthorizedClientService(registrations));
        clients.setAuthorizedClientProvider(provider);
        manager = clients;
    }

    @Override
    public Snapshot check(Actor actor, UUID attempt, String role) {
        return check(actor, attempt, role, "HISTORY");
    }

    @Override
    public Snapshot checkLive(Actor actor, UUID attempt, String role) {
        return check(actor, attempt, role, "LIVE_MEDIA");
    }

    private Snapshot check(Actor actor, UUID attempt, String role, String purpose) {
        if (!actor.roles().contains(role)) throw new MonitoringFailure(403, "ACCESS_DENIED");
        try {
            var request = OAuth2AuthorizeRequest.withClientRegistrationId(SCOPE)
                    .principal(new UsernamePasswordAuthenticationToken("proctoring-authority", "unused", List.of()))
                    .build();
            return Mono.defer(() -> manager.authorize(request))
                    .switchIfEmpty(Mono.error(MonitoringFailure.unavailable()))
                    .flatMap(client -> {
                        if (client.getAccessToken().getExpiresAt() == null
                                || !client.getAccessToken().getExpiresAt().isAfter(Instant.now()))
                            return Mono.error(MonitoringFailure.unavailable());
                        return http.post()
                                .uri(monitoring
                                        .getAssessmentBaseUrl()
                                        .resolve("/internal/proctoring/attempts/" + attempt + "/authority"))
                                .headers(h ->
                                        h.setBearerAuth(client.getAccessToken().getTokenValue()))
                                .header("X-Quizopia-User-Evidence", actor.userEvidence())
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .bodyValue(Map.of("viewerRole", role, "purpose", purpose))
                                .exchangeToMono(response -> {
                                    if (response.headers()
                                            .contentType()
                                            .filter(MediaType.APPLICATION_JSON::isCompatibleWith)
                                            .isEmpty())
                                        return response.releaseBody().then(Mono.error(MonitoringFailure.unavailable()));
                                    int status = response.statusCode().value();
                                    return response.bodyToMono(byte[].class)
                                            .defaultIfEmpty(new byte[0])
                                            .map(body -> {
                                                if (status == 200) return parse(body, actor, attempt, role, purpose);
                                                var error = json.readTree(body);
                                                String code = error != null
                                                                && error.isObject()
                                                                && error.size() == 1
                                                                && error.path("code")
                                                                        .isString()
                                                        ? error.path("code").asString()
                                                        : "";
                                                if (status == 404 && code.equals("PROCTORING_NOT_ACCESSIBLE"))
                                                    throw new MonitoringFailure(404, code);
                                                if (status == 401 && code.equals("USER_EVIDENCE_INVALID"))
                                                    throw new MonitoringFailure(401, "UNAUTHENTICATED");
                                                if (status == 403 && code.equals("ACCESS_DENIED"))
                                                    throw new MonitoringFailure(403, code);
                                                throw MonitoringFailure.unavailable();
                                            });
                                });
                    })
                    .timeout(bounds.getTotalTimeout())
                    .onErrorMap(e -> e instanceof MonitoringFailure ? e : MonitoringFailure.unavailable())
                    .block(bounds.getTotalTimeout().plusMillis(100));
        } catch (MonitoringFailure f) {
            throw f;
        } catch (RuntimeException unavailable) {
            throw MonitoringFailure.unavailable();
        }
    }

    private Snapshot parse(byte[] body, Actor actor, UUID attempt, String role, String purpose) {
        var n = json.readTree(body);
        var keys = Set.of(
                "actorId",
                "viewerRole",
                "purpose",
                "attemptId",
                "publicationId",
                "classroomId",
                "studentId",
                "active",
                "deadlineAt",
                "checkedAt",
                "quizId");
        if (n == null || !n.isObject() || !new HashSet<>(n.propertyNames()).equals(keys))
            throw MonitoringFailure.unavailable();
        for (String key : keys)
            if (!key.equals("active") && !n.path(key).isString()) throw MonitoringFailure.unavailable();
        if (!n.path("active").isBoolean()) throw MonitoringFailure.unavailable();
        if (!purpose.equals(n.path("purpose").asString())) throw MonitoringFailure.unavailable();
        var snapshot = new Snapshot(
                uuid(n.path("actorId").asString()),
                n.path("viewerRole").asString(),
                uuid(n.path("attemptId").asString()),
                uuid(n.path("publicationId").asString()),
                uuid(n.path("classroomId").asString()),
                uuid(n.path("studentId").asString()),
                n.path("active").asBoolean(),
                Instant.parse(n.path("deadlineAt").asString()),
                Instant.parse(n.path("checkedAt").asString()),
                uuid(n.path("quizId").asString()));
        var now = Instant.now();
        if (!snapshot.actorId().equals(actor.id())
                || !snapshot.viewerRole().equals(role)
                || !snapshot.attemptId().equals(attempt)
                || (role.equals("STUDENT") && !snapshot.studentId().equals(actor.id()))
                || snapshot.checkedAt().isAfter(now.plusSeconds(1))
                || snapshot.checkedAt().isBefore(now.minusSeconds(5))
                || actor.expiresAt() == null
                || !actor.expiresAt().isAfter(now)
                || (snapshot.active() && !snapshot.deadlineAt().isAfter(now))) throw MonitoringFailure.unavailable();
        return snapshot;
    }

    private UUID uuid(String text) {
        var id = UUID.fromString(text);
        if (!id.toString().equals(text)) throw MonitoringFailure.unavailable();
        return id;
    }

    @Override
    public void close() {
        pool.dispose();
    }
}
