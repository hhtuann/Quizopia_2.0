package com.quizopia.gateway.security.admission;

import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public final class UserAdmissionWebFilter implements WebFilter {
    private final IdentityAdmissionClient admission;

    public UserAdmissionWebFilter(IdentityAdmissionClient admission) {
        this.admission = admission;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String required = AdmissionRoutes.requiredRole(
                exchange.getRequest().getMethod().name(),
                exchange.getRequest().getURI().getRawPath());
        if (required == null) return chain.filter(exchange);
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
                            .thenReturn(Mono.defer(() -> chain.filter(exchange)));
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
