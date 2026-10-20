package com.quizopia.gateway.realtime;

import com.quizopia.gateway.configuration.GatewayBrowserOriginProperties;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public final class RealtimeIngressFilter implements WebFilter {
    public static final String TICKET_PATH = "/api/realtime/tickets";
    public static final String WEBSOCKET_PATH = "/ws/realtime";
    public static final String SUBPROTOCOL = "quizopia.realtime.v1";
    private static final String WEBSOCKET_KEY = "Sec-WebSocket-Key";
    private static final Set<String> PREFLIGHT_HEADERS = Set.of("authorization", "content-type");
    private static final String BEARER = "Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+";

    private final RealtimeProperties properties;
    private final Set<String> allowedOrigins;
    private final RealtimeConnectionLimiter limiter;

    public RealtimeIngressFilter(
            RealtimeProperties properties,
            GatewayBrowserOriginProperties browserOrigins,
            RealtimeConnectionLimiter limiter) {
        this.properties = properties;
        this.allowedOrigins = Set.copyOf(browserOrigins.allowedOrigins());
        this.limiter = limiter;
        properties.validate();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (path.equals(TICKET_PATH)) return ticket(exchange, chain);
        if (path.equals(WEBSOCKET_PATH)) return websocket(exchange, chain);
        if (reserved(path, "/api/realtime") || reserved(path, WEBSOCKET_PATH)) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.NOT_FOUND, "INVALID_REQUEST");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> ticket(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (method == HttpMethod.OPTIONS) return preflight(exchange);
        if (method != HttpMethod.POST) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.METHOD_NOT_ALLOWED, "INVALID_REQUEST");
        }
        if (exchange.getRequest().getURI().getRawQuery() != null) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        String origin = approvedOrigin(exchange.getRequest().getHeaders());
        if (origin == null) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        List<String> authorization = exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization == null
                || authorization.size() != 1
                || !authorization.getFirst().matches(BEARER)) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        }
        if (!properties.isEnabled()) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AUTHORITY_UNAVAILABLE");
        }
        return chain.filter(exchange);
    }

    private Mono<Void> preflight(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String origin = approvedOrigin(headers);
        if (origin == null
                || !HttpMethod.POST.name().equals(headers.getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD))
                || !allowedPreflightHeaders(headers.getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS))) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.NO_CONTENT);
        response.getHeaders().set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        response.getHeaders().set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, HttpMethod.POST.name());
        response.getHeaders().set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization, Content-Type");
        response.getHeaders().set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        response.getHeaders().setCacheControl("no-store");
        return response.setComplete();
    }

    private Mono<Void> websocket(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.GET) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.METHOD_NOT_ALLOWED, "INVALID_REQUEST");
        }
        HttpHeaders headers = exchange.getRequest().getHeaders();
        if (exchange.getRequest().getURI().getRawQuery() != null
                || headers.containsHeader(HttpHeaders.AUTHORIZATION)
                || !"websocket".equalsIgnoreCase(headers.getUpgrade())
                || !headerContainsToken(headers, HttpHeaders.CONNECTION, "upgrade")
                || !List.of(SUBPROTOCOL).equals(headers.get("Sec-WebSocket-Protocol"))) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        String origin = approvedOrigin(headers);
        if (origin == null) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (!properties.isEnabled()) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AUTHORITY_UNAVAILABLE");
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        String connectionKey = connectionKey(remote, headers);
        if (connectionKey == null) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        String ip = remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
        RealtimeConnectionLimiter.Reservation reservation = limiter.reserve(ip, origin);
        if (reservation.rejection() != null) {
            return RealtimeHttpResponses.error(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        }
        if (!limiter.registerHandoff(connectionKey, reservation.lease())) {
            reservation.lease().close();
            return RealtimeHttpResponses.error(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        return chain.filter(exchange).doFinally(ignored -> limiter.cancelHandoff(connectionKey, reservation.lease()));
    }

    private static boolean headerContainsToken(HttpHeaders headers, String name, String expected) {
        return headers.getOrEmpty(name).stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .anyMatch(value -> value.equalsIgnoreCase(expected));
    }

    private static boolean allowedPreflightHeaders(String value) {
        if (value == null || value.isBlank()) return true;
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .allMatch(PREFLIGHT_HEADERS::contains);
    }

    private String approvedOrigin(HttpHeaders headers) {
        List<String> origins = headers.getOrEmpty(HttpHeaders.ORIGIN);
        if (origins.size() != 1) return null;
        String origin = origins.getFirst();
        return allowedOrigins.contains(origin) ? origin : null;
    }

    static String connectionKey(InetSocketAddress remote, HttpHeaders headers) {
        List<String> keys = headers.getOrEmpty(WEBSOCKET_KEY);
        if (keys.size() != 1 || keys.getFirst().isBlank()) return null;
        String source = remote == null ? "unknown" : remote.toString();
        return source + "|" + keys.getFirst();
    }

    private static boolean reserved(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
