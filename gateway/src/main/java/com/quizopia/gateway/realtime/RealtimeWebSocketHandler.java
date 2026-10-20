package com.quizopia.gateway.realtime;

import com.quizopia.gateway.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.gateway.security.QuizopiaTokenClaims;
import com.quizopia.gateway.security.admission.AdmissionFailure;
import com.quizopia.gateway.security.admission.IdentityAdmissionClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class RealtimeWebSocketHandler implements WebSocketHandler {
    private static final Pattern TICKET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final java.util.Set<String> RESOURCE_KINDS =
            java.util.Set.of("ATTEMPT_OWNER", "PUBLICATION_TEACHER");

    private final RealtimeProperties properties;
    private final RealtimeTicketStore tickets;
    private final RealtimeConnectionLimiter limiter;
    private final ReactiveJwtDecoder decoder;
    private final QuizopiaJwtAuthenticationConverter converter;
    private final IdentityAdmissionClient admission;
    private final MeterRegistry meters;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public RealtimeWebSocketHandler(
            RealtimeProperties properties,
            RealtimeTicketStore tickets,
            RealtimeConnectionLimiter limiter,
            ReactiveJwtDecoder decoder,
            QuizopiaJwtAuthenticationConverter converter,
            IdentityAdmissionClient admission,
            MeterRegistry meters,
            Clock clock) {
        this.properties = properties;
        this.tickets = tickets;
        this.limiter = limiter;
        this.decoder = decoder;
        this.converter = converter;
        this.admission = admission;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public List<String> getSubProtocols() {
        return List.of(RealtimeIngressFilter.SUBPROTOCOL);
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        RealtimeConnectionLimiter.Lease lease = limiter.claimHandoff(RealtimeIngressFilter.connectionKey(
                session.getHandshakeInfo().getRemoteAddress(),
                session.getHandshakeInfo().getHeaders()));
        if (lease == null) {
            meters.counter("gateway.realtime.websocket", "outcome", RealtimeFailure.Kind.AUTHORITY_UNAVAILABLE.name())
                    .increment();
            return session.close(new CloseStatus(RealtimeFailure.Kind.AUTHORITY_UNAVAILABLE.closeCode()));
        }
        AtomicReference<State> state = new AtomicReference<>(State.PENDING_AUTH);
        AtomicReference<Disposable> expiry = new AtomicReference<>();
        Sinks.Many<WebSocketMessage> outbound =
                Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(16));
        emit(outbound, session.textMessage(welcome()));

        Disposable timeout = Mono.delay(properties.getAuthTimeout())
                .subscribe(
                        ignored ->
                                closeIfState(session, outbound, state, State.PENDING_AUTH, RealtimeFailure.timeout()),
                        ignored -> closeIfState(
                                session, outbound, state, State.PENDING_AUTH, RealtimeFailure.authorityUnavailable()));

        Mono<Void> receive = session.receive()
                .concatMap(message -> Mono.defer(() -> process(session, message, state, expiry, outbound))
                        .onErrorResume(RealtimeFailure.class, failure -> close(session, outbound, state, failure))
                        .onErrorResume(
                                ignored -> close(session, outbound, state, RealtimeFailure.authorityUnavailable())))
                .then()
                .doFinally(ignored -> outbound.tryEmitComplete());
        Mono<Void> send = session.send(outbound.asFlux());

        return Mono.when(receive, send).doFinally(ignored -> {
            timeout.dispose();
            Disposable expiryTimer = expiry.getAndSet(null);
            if (expiryTimer != null) expiryTimer.dispose();
            state.set(State.CLOSED);
            outbound.tryEmitComplete();
            lease.close();
        });
    }

    private Mono<Void> process(
            WebSocketSession session,
            WebSocketMessage message,
            AtomicReference<State> state,
            AtomicReference<Disposable> expiry,
            Sinks.Many<WebSocketMessage> outbound) {
        if (message.getType() != WebSocketMessage.Type.TEXT
                || message.getPayload().readableByteCount() > properties.getMaximumFrameBytes()) {
            return Mono.error(RealtimeFailure.malformed());
        }
        String text = message.getPayloadAsText(StandardCharsets.UTF_8);
        if (state.get() == State.PENDING_AUTH) {
            return authenticate(session, text, state, expiry, outbound);
        }
        if (state.get() == State.AUTHENTICATED) {
            return authenticatedFrame(session, text, outbound);
        }
        return Mono.empty();
    }

    private Mono<Void> authenticate(
            WebSocketSession session,
            String text,
            AtomicReference<State> state,
            AtomicReference<Disposable> expiry,
            Sinks.Many<WebSocketMessage> outbound) {
        AuthFrame frame = parseAuth(text);
        String origin = session.getHandshakeInfo().getHeaders().getOrigin();
        return tickets.consume(frame.ticket())
                .onErrorMap(
                        RealtimeTicketStore.TicketStoreFailure.class,
                        failure -> failure.kind() == RealtimeTicketStore.TicketStoreFailure.Kind.UNAVAILABLE
                                ? RealtimeFailure.authorityUnavailable()
                                : RealtimeFailure.authentication())
                .flatMap(metadata -> {
                    if (!metadata.origin().equals(origin)
                            || !constantTimeEquals(
                                    metadata.tokenFingerprint(),
                                    RealtimeTicketStore.tokenFingerprint(frame.accessToken()))) {
                        return Mono.error(RealtimeFailure.authentication());
                    }
                    return decoder.decode(frame.accessToken())
                            .onErrorMap(ignored -> RealtimeFailure.authentication())
                            .flatMap(jwt -> validateUser(jwt, metadata))
                            .flatMap(jwt -> admission
                                    .admit(jwt, "STUDENT")
                                    .onErrorMap(AdmissionFailure.class, this::mapAdmissionFailure)
                                    .thenReturn(jwt));
                })
                .flatMap(jwt -> {
                    if (!session.isOpen() || !state.compareAndSet(State.PENDING_AUTH, State.AUTHENTICATED)) {
                        return Mono.error(RealtimeFailure.timeout());
                    }
                    meters.counter("gateway.realtime.websocket", "outcome", "authenticated")
                            .increment();
                    emit(outbound, session.textMessage("{\"protocolVersion\":1,\"type\":\"AUTHENTICATED\"}"));
                    scheduleExpiry(session, outbound, state, expiry, jwt.getExpiresAt());
                    return Mono.empty();
                });
    }

    private Mono<Jwt> validateUser(Jwt jwt, RealtimeTicketStore.TicketMetadata metadata) {
        return converter.convert(jwt).flatMap(authentication -> {
            boolean user = authentication.getAuthorities().stream()
                    .anyMatch(authority -> QuizopiaTokenClaims.USER_AUTHORITY.equals(authority.getAuthority()));
            if (!user || !metadata.actorId().toString().equals(jwt.getSubject())) {
                return Mono.error(RealtimeFailure.authentication());
            }
            return Mono.just(jwt);
        });
    }

    private Mono<Void> authenticatedFrame(
            WebSocketSession session, String text, Sinks.Many<WebSocketMessage> outbound) {
        JsonNode root = parseObject(text);
        String type = text(root, "type");
        if ("AUTH".equals(type)) return Mono.error(RealtimeFailure.malformed());
        if ("SUBSCRIBE".equals(type)) {
            requireSubscribe(root);
            emit(
                    outbound,
                    session.textMessage(requestError(root.path("requestId").asString())));
            meters.counter("gateway.realtime.subscription", "outcome", "feature_disabled")
                    .increment();
            return Mono.empty();
        }
        if ("UNSUBSCRIBE".equals(type)) {
            requireUnsubscribe(root);
            emit(
                    outbound,
                    session.textMessage(requestError(root.path("requestId").asString())));
            return Mono.empty();
        }
        return Mono.error(RealtimeFailure.malformed());
    }

    private AuthFrame parseAuth(String text) {
        JsonNode root = parseObject(text);
        if (root.size() != 4
                || !protocolVersionOne(root)
                || !"AUTH".equals(text(root, "type"))
                || !root.path("ticket").isString()
                || !root.path("accessToken").isString()) {
            throw RealtimeFailure.malformed();
        }
        String ticket = root.path("ticket").asString();
        String accessToken = root.path("accessToken").asString();
        if (!TICKET.matcher(ticket).matches()
                || accessToken.length() < 100
                || accessToken.length() > 16_384
                || !JWT.matcher(accessToken).matches()) {
            throw RealtimeFailure.malformed();
        }
        return new AuthFrame(ticket, accessToken);
    }

    private void requireSubscribe(JsonNode root) {
        if (root.size() != 4
                || !protocolVersionOne(root)
                || !canonicalUuid(root.path("requestId"))
                || !root.path("resource").isObject()
                || root.path("resource").size() != 2
                || !canonicalUuid(root.path("resource").path("id"))
                || !RESOURCE_KINDS.contains(text(root.path("resource"), "kind"))) {
            throw RealtimeFailure.malformed();
        }
    }

    private void requireUnsubscribe(JsonNode root) {
        if (root.size() != 4
                || !protocolVersionOne(root)
                || !canonicalUuid(root.path("requestId"))
                || !root.path("subscriptionId").isString()
                || root.path("subscriptionId").asString().isEmpty()
                || root.path("subscriptionId").asString().length() > 80) {
            throw RealtimeFailure.malformed();
        }
    }

    private JsonNode parseObject(String value) {
        try {
            JsonNode root = json.readTree(value);
            if (root == null || !root.isObject()) throw RealtimeFailure.malformed();
            return root;
        } catch (RealtimeFailure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw RealtimeFailure.malformed();
        }
    }

    private static String text(JsonNode root, String field) {
        return root.path(field).isString() ? root.path(field).asString() : null;
    }

    private static boolean protocolVersionOne(JsonNode root) {
        JsonNode version = root.path("protocolVersion");
        return version.isIntegralNumber() && version.canConvertToInt() && version.intValue() == 1;
    }

    private static boolean canonicalUuid(JsonNode value) {
        return value.isString() && UUID_PATTERN.matcher(value.asString()).matches();
    }

    private RealtimeFailure mapAdmissionFailure(AdmissionFailure failure) {
        return switch (failure.status()) {
            case 401 -> RealtimeFailure.authentication();
            case 403 -> RealtimeFailure.accessDenied();
            default -> RealtimeFailure.authorityUnavailable();
        };
    }

    private void scheduleExpiry(
            WebSocketSession session,
            Sinks.Many<WebSocketMessage> outbound,
            AtomicReference<State> state,
            AtomicReference<Disposable> expiry,
            Instant expiresAt) {
        Duration delay = Duration.between(clock.instant(), expiresAt);
        if (delay.isNegative() || delay.isZero()) {
            closeIfState(session, outbound, state, State.AUTHENTICATED, RealtimeFailure.authentication());
            return;
        }
        expiry.set(Mono.delay(delay)
                .subscribe(
                        ignored -> closeIfState(
                                session, outbound, state, State.AUTHENTICATED, RealtimeFailure.authentication()),
                        ignored -> closeIfState(
                                session,
                                outbound,
                                state,
                                State.AUTHENTICATED,
                                RealtimeFailure.authorityUnavailable())));
    }

    private void closeIfState(
            WebSocketSession session,
            Sinks.Many<WebSocketMessage> outbound,
            AtomicReference<State> state,
            State expected,
            RealtimeFailure failure) {
        if (state.compareAndSet(expected, State.CLOSING)) {
            meters.counter(
                            "gateway.realtime.websocket",
                            "outcome",
                            failure.kind().name())
                    .increment();
            session.close(new CloseStatus(failure.kind().closeCode()))
                    .doFinally(ignored -> outbound.tryEmitComplete())
                    .subscribe(ignored -> {}, ignored -> outbound.tryEmitComplete());
        }
    }

    private Mono<Void> close(
            WebSocketSession session,
            Sinks.Many<WebSocketMessage> outbound,
            AtomicReference<State> state,
            RealtimeFailure failure) {
        state.set(State.CLOSING);
        meters.counter("gateway.realtime.websocket", "outcome", failure.kind().name())
                .increment();
        return session.close(new CloseStatus(failure.kind().closeCode()))
                .doFinally(ignored -> outbound.tryEmitComplete());
    }

    private static void emit(Sinks.Many<WebSocketMessage> outbound, WebSocketMessage message) {
        if (outbound.tryEmitNext(message).isFailure()) message.release();
    }

    private static boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII), second.getBytes(StandardCharsets.US_ASCII));
    }

    private static String welcome() {
        return "{\"protocolVersion\":1,\"type\":\"WELCOME\",\"connectionId\":\"" + UUID.randomUUID() + "\"}";
    }

    private static String requestError(String requestId) {
        return "{\"protocolVersion\":1,\"type\":\"ERROR\",\"code\":\"NOT_ACCESSIBLE\",\"requestId\":\""
                + requestId
                + "\"}";
    }

    private enum State {
        PENDING_AUTH,
        AUTHENTICATED,
        CLOSING,
        CLOSED
    }

    private record AuthFrame(String ticket, String accessToken) {}
}
