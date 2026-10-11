package com.quizopia.gateway.security.admission;

import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdmissionIngressFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (AdmissionRoutes.closed(path)) exchange.getResponse().getHeaders().setCacheControl("no-store");
        if (path.contains("%")
                || path.contains("\\")
                || path.contains("//")
                || path.contains(";")
                || java.util.Arrays.stream(path.split("/")).anyMatch(part -> part.equals(".") || part.equals(".."))) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            return exchange.getResponse().setComplete();
        }
        // Browser evidence is never trusted or forwarded, even on existing out-of-scope routes.
        var sanitized = exchange.getRequest()
                .mutate()
                .headers(headers -> headers.headerNames().stream()
                        .filter(name -> {
                            String value = name.toLowerCase(Locale.ROOT);
                            return value.startsWith("x-quizopia-")
                                    || value.startsWith("x-authenticated-")
                                    || java.util.Set.of("x-user-id", "x-user-roles", "x-service-identity")
                                            .contains(value);
                        })
                        .toList()
                        .forEach(headers::remove))
                .build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }
}
