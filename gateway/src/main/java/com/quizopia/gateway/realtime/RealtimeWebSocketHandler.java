package com.quizopia.gateway.realtime;

import com.quizopia.gateway.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.gateway.security.QuizopiaTokenClaims;
import com.quizopia.gateway.security.admission.AdmissionFailure;
import com.quizopia.gateway.security.admission.IdentityAdmissionClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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
    static final Set<String> CONNECTION_ROLES = Set.of("STUDENT", "TEACHER");
    private static final Pattern TICKET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final java.util.Set<String> RESOURCE_KINDS =
            java.util.Set.of("ATTEMPT_OWNER", "PUBLICATION_TEACHER");
    private static final SecureRandom SUBSCRIPTION_RANDOM = new SecureRandom();

    private final RealtimeProperties properties;
    private final RealtimeAuthorityProperties authorityBounds;
    private final RealtimeTicketStore tickets;
    private final RealtimeConnectionLimiter limiter;
    private final ReactiveJwtDecoder decoder;
    private final QuizopiaJwtAuthenticationConverter converter;
    private final IdentityAdmissionClient admission;
    private final RealtimeAuthorityClient authority;
    private final com.quizopia.gateway.realtime.invalidation.RealtimeSubscriptionRegistry registry;
    private final MeterRegistry meters;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public RealtimeWebSocketHandler(
            RealtimeProperties properties,
            RealtimeAuthorityProperties authorityBounds,
            RealtimeTicketStore tickets,
            RealtimeConnectionLimiter limiter,
            ReactiveJwtDecoder decoder,
            QuizopiaJwtAuthenticationConverter converter,
            IdentityAdmissionClient admission,
            RealtimeAuthorityClient authority,
            com.quizopia.gateway.realtime.invalidation.RealtimeSubscriptionRegistry registry,
            MeterRegistry meters,
            Clock clock) {
        this.properties = properties;
        this.authorityBounds = authorityBounds;
        this.tickets = tickets;
        this.limiter = limiter;
        this.decoder = decoder;
        this.converter = converter;
        this.admission = admission;
        this.authority = authority;
        this.registry = registry;
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
        AtomicReference<String> userEvidence = new AtomicReference<>();
        Subscriptions subscriptions = new Subscriptions(authorityBounds.getMaximumSubscriptionsPerConnection());
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
                .concatMap(message -> Mono.defer(
                                () -> process(session, message, state, expiry, userEvidence, subscriptions, outbound))
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
            userEvidence.set(null);
            for (String subscriptionId : subscriptions.ids()) registry.unregister(subscriptionId);
            subscriptions.clear();
            outbound.tryEmitComplete();
            lease.close();
        });
    }

    private Mono<Void> process(
            WebSocketSession session,
            WebSocketMessage message,
            AtomicReference<State> state,
            AtomicReference<Disposable> expiry,
            AtomicReference<String> userEvidence,
            Subscriptions subscriptions,
            Sinks.Many<WebSocketMessage> outbound) {
        if (message.getType() != WebSocketMessage.Type.TEXT
                || message.getPayload().readableByteCount() > properties.getMaximumFrameBytes()) {
            return Mono.error(RealtimeFailure.malformed());
        }
        String text = message.getPayloadAsText(StandardCharsets.UTF_8);
        if (state.get() == State.PENDING_AUTH) {
            return authenticate(session, text, state, expiry, userEvidence, outbound);
        }
        if (state.get() == State.AUTHENTICATED) {
            return authenticatedFrame(session, text, state, userEvidence, subscriptions, outbound);
        }
        return Mono.empty();
    }

    private Mono<Void> authenticate(
            WebSocketSession session,
            String text,
            AtomicReference<State> state,
            AtomicReference<Disposable> expiry,
            AtomicReference<String> userEvidence,
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
                                    .admitAny(jwt, CONNECTION_ROLES)
                                    .onErrorMap(AdmissionFailure.class, this::mapAdmissionFailure)
                                    .thenReturn(jwt));
                })
                .flatMap(jwt -> {
                    if (!session.isOpen() || !state.compareAndSet(State.PENDING_AUTH, State.AUTHENTICATED)) {
                        return Mono.error(RealtimeFailure.timeout());
                    }
                    meters.counter("gateway.realtime.websocket", "outcome", "authenticated")
                            .increment();
                    userEvidence.set(frame.accessToken());
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
            WebSocketSession session,
            String text,
            AtomicReference<State> state,
            AtomicReference<String> userEvidence,
            Subscriptions subscriptions,
            Sinks.Many<WebSocketMessage> outbound) {
        JsonNode root = parseObject(text);
        String type = text(root, "type");
        if ("AUTH".equals(type)) return Mono.error(RealtimeFailure.malformed());
        if ("SUBSCRIBE".equals(type)) return subscribe(session, root, state, userEvidence, subscriptions, outbound);
        if ("UNSUBSCRIBE".equals(type)) return unsubscribe(session, root, subscriptions, outbound);
        return Mono.error(RealtimeFailure.malformed());
    }

    private Mono<Void> subscribe(
            WebSocketSession session,
            JsonNode root,
            AtomicReference<State> state,
            AtomicReference<String> userEvidence,
            Subscriptions subscriptions,
            Sinks.Many<WebSocketMessage> outbound) {
        requireSubscribe(root);
        String requestId = root.path("requestId").asString();
        String kind = text(root.path("resource"), "kind");
        String resourceId = root.path("resource").path("id").asString();
        if (subscriptions.containsResource(kind, resourceId)) {
            subscriptionMetric("duplicate");
            emit(outbound, session.textMessage(requestError(requestId, "NOT_ACCESSIBLE")));
            return Mono.empty();
        }
        if (subscriptions.atCapacity()) {
            subscriptionMetric("capacity_exceeded");
            emit(outbound, session.textMessage(requestError(requestId, "CAPACITY_EXCEEDED")));
            return Mono.empty();
        }
        return authority
                .authorize(userEvidence.get(), kind, resourceId)
                .flatMap(decision -> switch (decision.outcome()) {
                    case AUTHORIZED -> {
                        String subscriptionId = subscriptionId();
                        if (!subscriptions.add(subscriptionId, kind, resourceId)) {
                            subscriptionMetric("duplicate");
                            emit(outbound, session.textMessage(requestError(requestId, "NOT_ACCESSIBLE")));
                        } else if (registry.register(
                                kind,
                                resourceId,
                                subscriptionId,
                                invalidationListener(
                                        session,
                                        state,
                                        userEvidence,
                                        subscriptions,
                                        outbound,
                                        subscriptionId,
                                        kind,
                                        resourceId))) {
                            subscriptionMetric("authorized");
                            emit(
                                    outbound,
                                    session.textMessage(subscribed(requestId, subscriptionId, kind, resourceId)));
                        } else {
                            // Registry saturation rolls the local subscription back; SUBSCRIBED is
                            // never emitted for a subscription that can no longer be invalidated.
                            subscriptions.remove(subscriptionId);
                            subscriptionMetric("capacity_exceeded");
                            emit(outbound, session.textMessage(requestError(requestId, "CAPACITY_EXCEEDED")));
                        }
                        yield Mono.<Void>empty();
                    }
                    case NOT_ACCESSIBLE, FORBIDDEN -> {
                        subscriptionMetric("not_accessible");
                        emit(outbound, session.textMessage(requestError(requestId, "NOT_ACCESSIBLE")));
                        yield Mono.<Void>empty();
                    }
                    case UNAVAILABLE -> {
                        subscriptionMetric("authority_unavailable");
                        emit(outbound, session.textMessage(requestError(requestId, "AUTHORITY_UNAVAILABLE")));
                        yield Mono.<Void>empty();
                    }
                    // Stale or revoked USER evidence terminates the whole session; fail closed.
                    case USER_EVIDENCE_INVALID -> Mono.<Void>error(RealtimeFailure.authentication());
                });
    }

    /**
     * Best-effort invalidation delivery: revalidates current Assessment authority and USER evidence
     * before any frame; revoked or stale authority never forwards a hint. No raw AMQP content,
     * business data or identity details are exposed beyond the frozen allowlist.
     */
    private com.quizopia.gateway.realtime.invalidation.RealtimeSubscriptionRegistry.Listener invalidationListener(
            WebSocketSession session,
            AtomicReference<State> state,
            AtomicReference<String> userEvidence,
            Subscriptions subscriptions,
            Sinks.Many<WebSocketMessage> outbound,
            String subscriptionId,
            String kind,
            String resourceId) {
        return (eventId, occurredAt) -> {
            if (state.get() != State.AUTHENTICATED || !subscriptions.contains(subscriptionId)) return;
            String evidence = userEvidence.get();
            if (evidence == null) return;
            authority.authorize(evidence, kind, resourceId).subscribe(decision -> {
                if (state.get() != State.AUTHENTICATED) return;
                switch (decision.outcome()) {
                    case AUTHORIZED -> {
                        if (!subscriptions.contains(subscriptionId)) return;
                        if (tryEmit(
                                outbound,
                                session.textMessage(
                                        resourceInvalidated(eventId, subscriptionId, kind, resourceId, occurredAt)))) {
                            subscriptionMetric("invalidated");
                        } else if (tryEmit(outbound, session.textMessage(resyncRequired(subscriptionId)))) {
                            // The hint was dropped, so the client is explicitly told to reconcile
                            // through REST; it must never be left silently desynchronized.
                            subscriptionMetric("overflow");
                        } else {
                            // Even the resync signal cannot be queued: close so the reconnect
                            // path performs a full REST resync. Bounded queues are preserved.
                            subscriptionMetric("overflow");
                            closeIfState(
                                    session,
                                    outbound,
                                    state,
                                    State.AUTHENTICATED,
                                    RealtimeFailure.authorityUnavailable());
                        }
                    }
                    case NOT_ACCESSIBLE, FORBIDDEN -> {
                        if (subscriptions.remove(subscriptionId)) registry.unregister(subscriptionId);
                        if (tryEmit(
                                outbound, session.textMessage(subscriptionRevoked(subscriptionId, "NOT_ACCESSIBLE")))) {
                            subscriptionMetric("revoked");
                        } else {
                            // The client may believe it is still subscribed; close to force a safe
                            // reconnect instead of leaving a stale subscription impression.
                            subscriptionMetric("revoked");
                            closeIfState(
                                    session,
                                    outbound,
                                    state,
                                    State.AUTHENTICATED,
                                    RealtimeFailure.authorityUnavailable());
                        }
                    }
                    case USER_EVIDENCE_INVALID -> {
                        subscriptionMetric("revoked");
                        emit(outbound, session.textMessage(subscriptionRevoked(subscriptionId, "TOKEN_EXPIRED")));
                        closeIfState(session, outbound, state, State.AUTHENTICATED, RealtimeFailure.authentication());
                    }
                    case UNAVAILABLE -> {
                        if (tryEmit(outbound, session.textMessage(resyncRequired(subscriptionId)))) {
                            subscriptionMetric("resync");
                        } else {
                            subscriptionMetric("overflow");
                            closeIfState(
                                    session,
                                    outbound,
                                    state,
                                    State.AUTHENTICATED,
                                    RealtimeFailure.authorityUnavailable());
                        }
                    }
                }
            });
        };
    }

    private Mono<Void> unsubscribe(
            WebSocketSession session,
            JsonNode root,
            Subscriptions subscriptions,
            Sinks.Many<WebSocketMessage> outbound) {
        requireUnsubscribe(root);
        String requestId = root.path("requestId").asString();
        String subscriptionId = root.path("subscriptionId").asString();
        if (subscriptions.remove(subscriptionId)) {
            registry.unregister(subscriptionId);
            subscriptionMetric("unsubscribed");
            emit(outbound, session.textMessage(unsubscribed(requestId, subscriptionId)));
        } else {
            subscriptionMetric("unknown_subscription");
            emit(outbound, session.textMessage(requestError(requestId, "NOT_ACCESSIBLE")));
        }
        return Mono.empty();
    }

    private void subscriptionMetric(String outcome) {
        meters.counter("gateway.realtime.subscription", "outcome", outcome).increment();
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

    /** Reports queueing failures so invalidation fanout never silently discards a hint. */
    private static boolean tryEmit(Sinks.Many<WebSocketMessage> outbound, WebSocketMessage message) {
        boolean emitted = outbound.tryEmitNext(message).isSuccess();
        if (!emitted) message.release();
        return emitted;
    }

    private static boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII), second.getBytes(StandardCharsets.US_ASCII));
    }

    private static String welcome() {
        return "{\"protocolVersion\":1,\"type\":\"WELCOME\",\"connectionId\":\"" + UUID.randomUUID() + "\"}";
    }

    private static String requestError(String requestId, String code) {
        return "{\"protocolVersion\":1,\"type\":\"ERROR\",\"code\":\"" + code + "\",\"requestId\":\""
                + requestId
                + "\"}";
    }

    private static String subscribed(String requestId, String subscriptionId, String kind, String resourceId) {
        return "{\"protocolVersion\":1,\"type\":\"SUBSCRIBED\",\"requestId\":\""
                + requestId
                + "\",\"subscriptionId\":\""
                + subscriptionId
                + "\",\"resource\":{\"kind\":\""
                + kind
                + "\",\"id\":\""
                + resourceId
                + "\"}}";
    }

    private static String unsubscribed(String requestId, String subscriptionId) {
        return "{\"protocolVersion\":1,\"type\":\"UNSUBSCRIBED\",\"requestId\":\""
                + requestId
                + "\",\"subscriptionId\":\""
                + subscriptionId
                + "\"}";
    }

    private static String resourceInvalidated(
            String eventId, String subscriptionId, String kind, String resourceId, Instant occurredAt) {
        return "{\"protocolVersion\":1,\"type\":\"RESOURCE_INVALIDATED\",\"eventId\":\""
                + eventId
                + "\",\"subscriptionId\":\""
                + subscriptionId
                + "\",\"resource\":{\"kind\":\""
                + kind
                + "\",\"id\":\""
                + resourceId
                + "\"},\"reason\":\"CHANGED\",\"occurredAt\":\""
                + occurredAt
                + "\"}";
    }

    private static String subscriptionRevoked(String subscriptionId, String code) {
        return "{\"protocolVersion\":1,\"type\":\"SUBSCRIPTION_REVOKED\",\"subscriptionId\":\""
                + subscriptionId
                + "\",\"code\":\""
                + code
                + "\"}";
    }

    private static String resyncRequired(String subscriptionId) {
        return "{\"protocolVersion\":1,\"type\":\"RESYNC_REQUIRED\",\"subscriptionId\":\"" + subscriptionId + "\"}";
    }

    private static String subscriptionId() {
        byte[] random = new byte[18];
        SUBSCRIPTION_RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /** Connection-scoped, bounded, opaque subscription state; no global registry. */
    private static final class Subscriptions {
        private final int maximum;
        private final java.util.Map<String, String> byId = new ConcurrentHashMap<>();
        private final java.util.Set<String> resources = ConcurrentHashMap.newKeySet();

        Subscriptions(int maximum) {
            this.maximum = maximum;
        }

        boolean containsResource(String kind, String resourceId) {
            return resources.contains(key(kind, resourceId));
        }

        boolean atCapacity() {
            return byId.size() >= maximum;
        }

        boolean add(String subscriptionId, String kind, String resourceId) {
            if (byId.size() >= maximum) return false;
            String resourceKey = key(kind, resourceId);
            if (!resources.add(resourceKey)) return false;
            if (byId.putIfAbsent(subscriptionId, resourceKey) != null) {
                resources.remove(resourceKey);
                return false;
            }
            return true;
        }

        boolean remove(String subscriptionId) {
            String resourceKey = byId.remove(subscriptionId);
            if (resourceKey == null) return false;
            resources.remove(resourceKey);
            return true;
        }

        boolean contains(String subscriptionId) {
            return byId.containsKey(subscriptionId);
        }

        java.util.Set<String> ids() {
            return java.util.Set.copyOf(byId.keySet());
        }

        void clear() {
            byId.clear();
            resources.clear();
        }

        private static String key(String kind, String resourceId) {
            return kind + ":" + resourceId;
        }
    }

    private enum State {
        PENDING_AUTH,
        AUTHENTICATED,
        CLOSING,
        CLOSED
    }

    private record AuthFrame(String ticket, String accessToken) {}
}
