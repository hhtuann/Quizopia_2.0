package com.quizopia.gateway.security.admission;

import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.*;
import reactor.core.publisher.*;

/** Bounded request collection after fresh admission, before proxying any bytes downstream. */
final class StudentAttemptBodyFilter {
    private StudentAttemptBodyFilter() {}

    static Mono<Void> invalid(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.BAD_REQUEST);
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory()
                .wrap("{\"code\":\"INVALID_REQUEST\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    static Mono<Void> forward(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        if (TeacherAssessmentRoutes.role(
                        request.getMethod().name(), request.getURI().getRawPath())
                != null) return teacherForward(exchange, chain);
        if (!StudentAttemptRoutes.matches(
                        request.getMethod().name(), request.getURI().getRawPath())
                && ClosureRoutes.role(
                                request.getMethod().name(), request.getURI().getRawPath())
                        == null) return chain.filter(exchange);
        if ((!ClosureRoutes.paged(request.getURI().getRawPath())
                        && request.getURI().getRawQuery() != null)
                || request.getHeaders().getContentLength() > 2097152) return invalid(exchange);
        var encoding = request.getHeaders().get("Content-Encoding");
        if (encoding != null && (encoding.size() != 1 || !encoding.getFirst().equalsIgnoreCase("identity")))
            return invalid(exchange);
        if (request.getMethod() == HttpMethod.POST) {
            var keys = request.getHeaders().get("Idempotency-Key");
            var types = request.getHeaders().get("Content-Type");
            if (keys == null
                    || keys.size() != 1
                    || !keys.getFirst().matches("[A-Za-z0-9_-]{16,128}")
                    || types == null
                    || types.size() != 1
                    || !types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?"))
                return invalid(exchange);
        }
        return DataBufferUtils.join(request.getBody(), 2097152)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    if (request.getMethod() == HttpMethod.GET && bytes.length != 0
                            || request.getMethod() == HttpMethod.POST && bytes.length == 0) return invalid(exchange);
                    var cached = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.defer(() -> Flux.just(
                                    exchange.getResponse().bufferFactory().wrap(bytes)));
                        }
                    };
                    return chain.filter(exchange.mutate().request(cached).build());
                })
                .onErrorResume(DataBufferLimitException.class, e -> invalid(exchange));
    }

    private static Mono<Void> teacherForward(ServerWebExchange exchange, WebFilterChain chain) {
        var request = exchange.getRequest();
        if (!TeacherAssessmentRoutes.paged(request.getURI().getRawPath())
                        && request.getURI().getRawQuery() != null
                || request.getHeaders().getContentLength() > 4096) return invalid(exchange);
        var encodings = request.getHeaders().get("Content-Encoding");
        if (encodings != null && (encodings.size() != 1 || !encodings.getFirst().equalsIgnoreCase("identity")))
            return invalid(exchange);
        if (request.getMethod() == HttpMethod.POST || request.getMethod() == HttpMethod.PUT) {
            var types = request.getHeaders().get("Content-Type");
            if (types == null
                    || types.size() != 1
                    || !types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?"))
                return invalid(exchange);
        }
        return DataBufferUtils.join(request.getBody(), 4096)
                .map(buffer -> {
                    try {
                        var bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    if (request.getMethod() == HttpMethod.GET && bytes.length != 0
                            || (request.getMethod() == HttpMethod.POST || request.getMethod() == HttpMethod.PUT)
                                    && bytes.length == 0) return invalid(exchange);
                    return chain.filter(exchange.mutate()
                            .request(new ServerHttpRequestDecorator(request) {
                                public Flux<DataBuffer> getBody() {
                                    return Flux.defer(() -> Flux.just(exchange.getResponse()
                                            .bufferFactory()
                                            .wrap(bytes)));
                                }
                            })
                            .build());
                })
                .onErrorResume(DataBufferLimitException.class, e -> invalid(exchange));
    }
}
