package com.quizopia.assessment.infrastructure;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.configuration.ProctoringAuthorityProperties;
import com.quizopia.assessment.security.admission.AdmissionProperties;
import io.netty.channel.ChannelOption;
import java.net.URI;
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
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Separate purpose/scopes from notice delivery; no Student JWT or positive eligibility cache. */
public final class ProctoringTargetClient implements ProctoringTargetEligibility, AutoCloseable {
    public static final String IDENTITY_SCOPE = "identity.proctoring-student.read";
    public static final String MEMBERSHIP_SCOPE = "classroom.proctoring-membership.read";
    private final AdmissionProperties bounds;
    private final SourceProperties sources;
    private final ConnectionProvider pool;
    private final WebClient http;
    private final ReactiveOAuth2AuthorizedClientManager manager;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ProctoringTargetClient(
            ProctoringAuthorityProperties capability, SourceProperties sources, AdmissionProperties bounds) {
        this.bounds = bounds;
        this.sources = sources;
        if (!capability.targetEligibilityEnabled()) {
            pool = null;
            http = null;
            manager = null;
            return;
        }
        if (!capability.enabled())
            throw new IllegalStateException("Live target capability requires Proctoring authority");
        bounds.validate();
        sources.validate(bounds.isAllowInsecureLoopback());
        if (!bounds.isEnabled() || !sources.isEnabled())
            throw new IllegalStateException("Live target checks require private Identity/Classroom authority");
        pool = ConnectionProvider.builder("proctoring-target")
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
        var registrations = new InMemoryReactiveClientRegistrationRepository(
                java.util.stream.Stream.of(IDENTITY_SCOPE, MEMBERSHIP_SCOPE)
                        .map(scope -> ClientRegistration.withRegistrationId(scope)
                                .clientId(bounds.getClientId())
                                .clientSecret(bounds.getClientSecret())
                                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                                .tokenUri(bounds.getIdentityBaseUrl()
                                        .resolve("/oauth2/token")
                                        .toString())
                                .scope(scope)
                                .build())
                        .toList());
        var tokens = new WebClientReactiveClientCredentialsTokenResponseClient();
        tokens.setWebClient(http);
        var provider = new ClientCredentialsReactiveOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokens);
        provider.setClockSkew(java.time.Duration.ofSeconds(5));
        var authorized = new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                registrations, new InMemoryReactiveOAuth2AuthorizedClientService(registrations));
        authorized.setAuthorizedClientProvider(provider);
        manager = authorized;
    }

    private PublicationFailure unavailable() {
        return new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
    }

    private PublicationFailure denied() {
        return new PublicationFailure(404, "PROCTORING_NOT_ACCESSIBLE");
    }

    private Mono<JsonNode> call(URI uri, String scope, String teacher, Instant startedAt) {
        return manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(scope)
                        .principal(new UsernamePasswordAuthenticationToken(
                                "proctoring-target-client", "unused", List.of()))
                        .build())
                .switchIfEmpty(Mono.error(unavailable()))
                .flatMap(client -> {
                    var token = client.getAccessToken();
                    if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(Instant.now()))
                        return Mono.error(unavailable());
                    var request = http.post()
                            .uri(uri)
                            .headers(h -> h.setBearerAuth(token.getTokenValue()))
                            .accept(MediaType.APPLICATION_JSON);
                    // Teacher evidence is forwarded only to Classroom as Teacher authority, never to Identity.
                    if (teacher != null) request.header("X-Quizopia-User-Evidence", teacher);
                    WebClient.RequestHeadersSpec<?> sent = startedAt == null
                            ? request
                            : request.contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(Map.of("attemptStartedAt", startedAt.toString()));
                    return sent.exchangeToMono(response -> {
                        if (response.headers()
                                .contentType()
                                .filter(MediaType.APPLICATION_JSON::isCompatibleWith)
                                .isEmpty()) return response.releaseBody().then(Mono.error(unavailable()));
                        int status = response.statusCode().value();
                        return response.bodyToMono(byte[].class)
                                .switchIfEmpty(Mono.error(unavailable()))
                                .flatMap(bytes -> {
                                    var body = json.readTree(bytes);
                                    if (status == 200) return Mono.just(body);
                                    if (status == 404
                                            && body.isObject()
                                            && body.size() == 1
                                            && "CLASSROOM_NOT_ACCESSIBLE"
                                                    .equals(body.path("code").asString())) return Mono.error(denied());
                                    if (teacher != null
                                            && status == 401
                                            && body.isObject()
                                            && body.size() == 1
                                            && "USER_EVIDENCE_INVALID"
                                                    .equals(body.path("code").asString()))
                                        return Mono.error(new PublicationFailure(401, "USER_EVIDENCE_INVALID"));
                                    if (teacher != null
                                            && status == 403
                                            && body.isObject()
                                            && body.size() == 1
                                            && "USER_ROLE_FORBIDDEN"
                                                    .equals(body.path("code").asString()))
                                        return Mono.error(new PublicationFailure(403, "ACCESS_DENIED"));
                                    return Mono.error(unavailable());
                                });
                    });
                });
    }

    private void fresh(JsonNode body) {
        if (!body.path("checkedAt").isString()) throw unavailable();
        Instant checked = Instant.parse(body.path("checkedAt").asString()), now = Instant.now();
        if (checked.isAfter(now) || checked.isBefore(now.minusSeconds(5))) throw unavailable();
    }

    @Override
    public void require(UUID classroom, UUID student, Instant startedAt, String teacher) {
        if (http == null) throw unavailable();
        try {
            Mono.defer(() -> call(
                            bounds.getIdentityBaseUrl()
                                    .resolve("/internal/security/proctoring-students/" + student + "/eligibility"),
                            IDENTITY_SCOPE,
                            null,
                            startedAt))
                    .flatMap(body -> {
                        if (!body.isObject()
                                || body.size() != 4
                                || !body.path("eligible").isBoolean()
                                || !body.path("studentId").isString()
                                || !student.toString()
                                        .equals(body.path("studentId").asString())
                                || !body.path("attemptStartedAt").isString()
                                || !startedAt.equals(Instant.parse(
                                        body.path("attemptStartedAt").asString()))) throw unavailable();
                        fresh(body);
                        if (!body.path("eligible").asBoolean()) throw denied();
                        return call(
                                sources.getClassroomBaseUrl()
                                        .resolve("/internal/classrooms/" + classroom + "/proctoring-students/" + student
                                                + "/membership"),
                                MEMBERSHIP_SCOPE,
                                teacher,
                                null);
                    })
                    .map(body -> {
                        if (!body.isObject()
                                || body.size() != 3
                                || !body.path("studentId").isString()
                                || !student.toString()
                                        .equals(body.path("studentId").asString())
                                || !body.path("classroomId").isString()
                                || !classroom
                                        .toString()
                                        .equals(body.path("classroomId").asString())) throw unavailable();
                        fresh(body);
                        return true;
                    })
                    .timeout(bounds.getTotalTimeout())
                    .block(bounds.getTotalTimeout().plusMillis(100));
        } catch (PublicationFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    @Override
    public void close() {
        if (pool != null) pool.dispose();
    }
}
