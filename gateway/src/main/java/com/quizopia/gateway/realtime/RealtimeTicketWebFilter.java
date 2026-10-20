package com.quizopia.gateway.realtime;

import com.quizopia.gateway.security.admission.AdmissionFailure;
import com.quizopia.gateway.security.admission.IdentityAdmissionClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class RealtimeTicketWebFilter implements WebFilter {
    private static final int MAXIMUM_REQUEST_BYTES = 64;
    private final RealtimeProperties properties;
    private final IdentityAdmissionClient admission;
    private final RealtimeTicketStore tickets;
    private final MeterRegistry meters;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public RealtimeTicketWebFilter(
            RealtimeProperties properties,
            IdentityAdmissionClient admission,
            RealtimeTicketStore tickets,
            MeterRegistry meters) {
        this.properties = properties;
        this.admission = admission;
        this.tickets = tickets;
        this.meters = meters;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST
                || !RealtimeIngressFilter.TICKET_PATH.equals(
                        exchange.getRequest().getURI().getRawPath())) {
            return chain.filter(exchange);
        }
        if (!properties.isEnabled()) return failure(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AUTHORITY_UNAVAILABLE");
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || contentType.getParameters().keySet().stream().anyMatch(name -> !"charset".equals(name))) {
            return failure(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        return exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .switchIfEmpty(Mono.error(AdmissionFailure.invalidUser()))
                .flatMap(authentication -> strictEmptyBody(exchange)
                        .then(admission.admitAny(authentication.getToken(), RealtimeWebSocketHandler.CONNECTION_ROLES))
                        .then(tickets.issue(
                                UUID.fromString(authentication.getToken().getSubject()),
                                authentication.getToken().getTokenValue(),
                                exchange.getRequest().getHeaders().getOrigin())))
                .flatMap(ticket -> success(exchange, ticket))
                .onErrorResume(AdmissionFailure.class, error -> switch (error.status()) {
                    case 401 -> failure(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
                    case 403 -> failure(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
                    default -> failure(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AUTHORITY_UNAVAILABLE");
                })
                .onErrorResume(RealtimeTicketStore.TicketStoreFailure.class, error -> switch (error.kind()) {
                    case RATE_LIMITED -> failure(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
                    case INVALID -> failure(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
                    case UNAVAILABLE -> failure(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AUTHORITY_UNAVAILABLE");
                })
                .onErrorResume(
                        InvalidTicketRequest.class,
                        ignored -> failure(exchange, HttpStatus.BAD_REQUEST, "INVALID_REQUEST"))
                .onErrorResume(ignored -> failure(exchange, HttpStatus.SERVICE_UNAVAILABLE, "AUTHORITY_UNAVAILABLE"));
    }

    private Mono<Void> strictEmptyBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAXIMUM_REQUEST_BYTES)
                .switchIfEmpty(Mono.error(new InvalidTicketRequest()))
                .flatMap(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    try {
                        buffer.read(bytes);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                    try {
                        var root = json.readTree(bytes);
                        if (root == null || !root.isObject() || root.size() != 0) {
                            return Mono.<Void>error(new InvalidTicketRequest());
                        }
                        return Mono.<Void>empty();
                    } catch (RuntimeException exception) {
                        return Mono.<Void>error(new InvalidTicketRequest());
                    } finally {
                        java.util.Arrays.fill(bytes, (byte) 0);
                    }
                })
                .onErrorMap(error -> error instanceof InvalidTicketRequest ? error : new InvalidTicketRequest());
    }

    private Mono<Void> success(ServerWebExchange exchange, RealtimeTicketStore.IssuedTicket ticket) {
        meters.counter("gateway.realtime.ticket", "outcome", "issued").increment();
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.CREATED);
        response.getHeaders().setCacheControl("no-store");
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"ticket\":\""
                + ticket.value()
                + "\",\"expiresInSeconds\":"
                + ticket.expiresInSeconds()
                + ",\"protocolVersion\":1}";
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    private Mono<Void> failure(ServerWebExchange exchange, HttpStatus status, String code) {
        meters.counter("gateway.realtime.ticket", "outcome", code).increment();
        return RealtimeHttpResponses.error(exchange, status, code);
    }

    private static final class InvalidTicketRequest extends RuntimeException {
        private InvalidTicketRequest() {
            super(null, null, false, false);
        }
    }
}
