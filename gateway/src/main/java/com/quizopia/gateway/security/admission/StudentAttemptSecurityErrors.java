package com.quizopia.gateway.security.admission;

import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.oauth2.server.resource.web.server.*;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Preserve legacy handlers; canonical B2 operations always return sanitized code-only JSON. */
public final class StudentAttemptSecurityErrors {
    private StudentAttemptSecurityErrors() {}

    private static boolean student(ServerWebExchange exchange) {
        return StudentAttemptRoutes.matches(
                exchange.getRequest().getMethod().name(),
                exchange.getRequest().getURI().getRawPath());
    }

    public static ServerAuthenticationEntryPoint unauthenticated() {
        var fallback = new BearerTokenServerAuthenticationEntryPoint();
        return (exchange, error) ->
                student(exchange) ? write(exchange, 401, "UNAUTHENTICATED") : fallback.commence(exchange, error);
    }

    public static ServerAccessDeniedHandler denied() {
        var fallback = new BearerTokenServerAccessDeniedHandler();
        return (exchange, error) ->
                student(exchange) ? write(exchange, 403, "ACCESS_DENIED") : fallback.handle(exchange, error);
    }

    private static Mono<Void> write(ServerWebExchange exchange, int status, String code) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == 401) response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.writeWith(Mono.just(response.bufferFactory()
                .wrap(("{\"code\":\"" + code + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
