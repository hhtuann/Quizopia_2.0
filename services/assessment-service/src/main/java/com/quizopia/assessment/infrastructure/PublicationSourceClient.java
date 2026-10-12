package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.domain.PublicationConfiguration;
import com.quizopia.assessment.security.admission.AdmissionProperties;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.time.Instant;
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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class PublicationSourceClient implements PublicationSources, AutoCloseable {
    private final SourceProperties sources;
    private final AdmissionProperties properties;
    private final ConnectionProvider pool;
    private final WebClient http;
    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final SnapshotValidator snapshots = new SnapshotValidator();
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public PublicationSourceClient(SourceProperties sources, AdmissionProperties properties) {
        this.sources = sources;
        this.properties = properties;
        properties.validate();
        sources.validate(properties.isAllowInsecureLoopback());
        if (!sources.isEnabled()) {
            pool = null;
            http = null;
            manager = null;
            return;
        }
        if (!properties.isEnabled()) throw new IllegalStateException("Source authority requires Identity admission");
        pool = ConnectionProvider.builder("publication-sources")
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
        http = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(transport))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2097152))
                .build();
        var registrations = new InMemoryReactiveClientRegistrationRepository(java.util.stream.Stream.of(
                        "quiz.version-snapshot.read", "classroom.teacher-authority.read", "classroom.membership.read")
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

    private byte[] call(URI uri, String scope, String evidence) {
        if (!sources.isEnabled()) throw PublicationFailure.dependency();
        try {
            return Mono.defer(() -> manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(scope)
                                    .principal(new UsernamePasswordAuthenticationToken(
                                            "publication-source-client", "unused", List.of()))
                                    .build())
                            .switchIfEmpty(Mono.error(PublicationFailure.dependency()))
                            .flatMap(client -> {
                                if (client.getAccessToken().getExpiresAt() == null
                                        || !client.getAccessToken()
                                                .getExpiresAt()
                                                .isAfter(Instant.now()))
                                    return Mono.error(PublicationFailure.dependency());
                                return http.post()
                                        .uri(uri)
                                        .headers(h -> h.setBearerAuth(
                                                client.getAccessToken().getTokenValue()))
                                        .header("X-Quizopia-User-Evidence", evidence)
                                        .accept(MediaType.APPLICATION_JSON)
                                        .exchangeToMono(response -> {
                                            if (response.headers()
                                                    .contentType()
                                                    .filter(MediaType.APPLICATION_JSON::isCompatibleWith)
                                                    .isEmpty())
                                                return response.releaseBody()
                                                        .then(Mono.error(PublicationFailure.dependency()));
                                            int status = response.statusCode().value();
                                            return response.bodyToMono(byte[].class)
                                                    .defaultIfEmpty(new byte[0])
                                                    .flatMap(bytes -> {
                                                        if (status == 200) return Mono.just(bytes);
                                                        var error = json.readTree(bytes);
                                                        String code = error.isObject()
                                                                        && error.size() == 1
                                                                        && error.path("code")
                                                                                .isString()
                                                                ? error.path("code")
                                                                        .asString()
                                                                : "";
                                                        if (status == 401 && code.equals("USER_EVIDENCE_INVALID"))
                                                            return Mono.error(
                                                                    new PublicationFailure(401, "UNAUTHENTICATED"));
                                                        if (status == 403 && code.equals("USER_ROLE_FORBIDDEN"))
                                                            return Mono.error(
                                                                    new PublicationFailure(403, "ACCESS_DENIED"));
                                                        if (status == 404
                                                                && Set.of(
                                                                                "SOURCE_NOT_ACCESSIBLE",
                                                                                "CLASSROOM_NOT_ACCESSIBLE")
                                                                        .contains(code))
                                                            return Mono.error(new PublicationFailure(404, code));
                                                        return Mono.error(PublicationFailure.dependency());
                                                    });
                                        });
                            }))
                    .timeout(properties.getTotalTimeout())
                    .block(properties.getTotalTimeout().plusMillis(100));
        } catch (PublicationFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw PublicationFailure.dependency();
        }
    }

    public String snapshot(PublicationConfiguration c, String evidence) {
        byte[] bytes = call(
                sources.getQuizBaseUrl() == null
                        ? null
                        : sources.getQuizBaseUrl()
                                .resolve("/internal/quiz-versions/" + c.quizId() + "/" + c.quizVersionId() + "/"
                                        + c.versionNumber() + "/snapshot"),
                "quiz.version-snapshot.read",
                evidence);
        return snapshots.validate(bytes, c);
    }

    private void confirm(UUID classroomId, UUID actor, String evidence, boolean membership) {
        String operation = membership ? "student-membership" : "teacher-authority";
        var bytes = call(
                sources.getClassroomBaseUrl() == null
                        ? null
                        : sources.getClassroomBaseUrl()
                                .resolve("/internal/classrooms/" + classroomId + "/" + operation),
                membership ? "classroom.membership.read" : "classroom.teacher-authority.read",
                evidence);
        try {
            var root = json.readTree(bytes);
            if (!root.isObject()
                    || root.size() != 2
                    || !root.path("classroomId").isString()
                    || !root.path("actorId").isString()
                    || !root.path("classroomId").asString().equals(classroomId.toString())
                    || !root.path("actorId").asString().equals(actor.toString())) throw PublicationFailure.dependency();
        } catch (RuntimeException e) {
            throw PublicationFailure.dependency();
        }
    }

    public void teacher(UUID classroomId, UUID actor, String evidence) {
        confirm(classroomId, actor, evidence, false);
    }

    public void member(UUID classroomId, UUID actor, String evidence) {
        confirm(classroomId, actor, evidence, true);
    }

    public void close() {
        if (pool != null) pool.dispose();
    }
}
