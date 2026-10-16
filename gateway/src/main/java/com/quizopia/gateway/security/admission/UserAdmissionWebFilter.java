package com.quizopia.gateway.security.admission;

import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public final class UserAdmissionWebFilter implements WebFilter {
    private final boolean inboxEnabled;
    private final boolean proctoringEnabled;
    private final boolean mediaEnabled;
    private final IdentityAdmissionClient admission;
    private final PublicationProperties publication;
    private final AttemptFeatureProperties features;

    public UserAdmissionWebFilter(IdentityAdmissionClient admission, PublicationProperties publication) {
        this(admission, publication, new AttemptFeatureProperties(false, false, false));
    }

    public UserAdmissionWebFilter(
            IdentityAdmissionClient admission, PublicationProperties publication, AttemptFeatureProperties features) {
        this(admission, publication, features, false);
    }

    public UserAdmissionWebFilter(
            IdentityAdmissionClient admission,
            PublicationProperties publication,
            AttemptFeatureProperties features,
            boolean inboxEnabled) {
        this(admission, publication, features, inboxEnabled, false);
    }

    public UserAdmissionWebFilter(
            IdentityAdmissionClient admission,
            PublicationProperties publication,
            AttemptFeatureProperties features,
            boolean inboxEnabled,
            boolean proctoringEnabled) {
        this(admission, publication, features, inboxEnabled, proctoringEnabled, false);
    }

    public UserAdmissionWebFilter(
            IdentityAdmissionClient admission,
            PublicationProperties publication,
            AttemptFeatureProperties features,
            boolean inboxEnabled,
            boolean proctoringEnabled,
            boolean mediaEnabled) {
        this.mediaEnabled = mediaEnabled;
        this.proctoringEnabled = proctoringEnabled;
        this.inboxEnabled = inboxEnabled;
        this.features = features;
        this.admission = admission;
        this.publication = publication;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String required = AdmissionRoutes.requiredRole(
                exchange.getRequest().getMethod().name(),
                exchange.getRequest().getURI().getRawPath());
        if (required == null) return chain.filter(exchange);
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getRawPath();
        boolean student = StudentAttemptRoutes.matches(method, path);
        if (ProctoringRoutes.role(method, path) != null
                && (!proctoringEnabled || MediaRoutes.matches(method, path) && !mediaEnabled))
            return error(exchange, AdmissionFailure.unavailable());
        if (ClosureRoutes.role(method, path) != null
                ? !ClosureRoutes.enabled(
                        method, path, features.resultsEnabled(), features.closureEnabled(), inboxEnabled)
                : student
                        ? !StudentAttemptRoutes.enabled(
                                method, path, features.attemptsEnabled(), features.resultsEnabled())
                        : PublicationRoutes.requiredRole(method, path) != null && !publication.isEnabled())
            return error(exchange, AdmissionFailure.unavailable());
        var auth = exchange.getRequest().getHeaders().get("Authorization");
        if (auth == null
                || auth.size() != 1
                || !auth.getFirst().matches("(?i)Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
            return error(exchange, AdmissionFailure.invalidUser());
        return exchange.getPrincipal()
                .switchIfEmpty(Mono.error(AdmissionFailure.invalidUser()))
                .flatMap(principal -> {
                    if (!(principal instanceof JwtAuthenticationToken token))
                        return Mono.<Mono<Void>>error(AdmissionFailure.invalidUser());
                    exchange.getResponse().beforeCommit(() -> {
                        exchange.getResponse().getHeaders().setCacheControl("no-store");
                        return Mono.empty();
                    });
                    return admission
                            .admit(token.getToken(), required)
                            .thenReturn(Mono.defer(() -> StudentAttemptBodyFilter.forward(exchange, chain)));
                })
                .onErrorResume(failure -> Mono.just(error(
                        exchange,
                        failure instanceof AdmissionFailure admissionFailure
                                ? admissionFailure
                                : AdmissionFailure.unavailable())))
                .flatMap(java.util.function.Function.identity());
    }

    private Mono<Void> error(ServerWebExchange exchange, AdmissionFailure failure) {
        var response = exchange.getResponse();
        response.setStatusCode(org.springframework.http.HttpStatus.valueOf(failure.status()));
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory()
                .wrap(("{\"code\":\"" + failure.code() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
