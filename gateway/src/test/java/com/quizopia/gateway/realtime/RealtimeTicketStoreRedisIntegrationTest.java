package com.quizopia.gateway.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

@Testcontainers
class RealtimeTicketStoreRedisIntegrationTest {
    private static final String TOKEN = "header." + "a".repeat(160) + ".signature";
    private static final String ORIGIN = "https://quizopia.test";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redis;
    private RealtimeProperties properties;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void clearRedis() {
        connectionFactory.getConnection().serverCommands().flushAll();
        properties = new RealtimeProperties();
        properties.setEnabled(true);
    }

    @Test
    void storesOnlyHashedTicketKeyAndBoundedNonCredentialMetadata() {
        RealtimeTicketStore store = store();
        UUID actor = UUID.randomUUID();

        RealtimeTicketStore.IssuedTicket issued =
                store.issue(actor, TOKEN, ORIGIN).block();

        assertEquals(43, issued.value().length());
        assertEquals(30, issued.expiresInSeconds());
        List<String> keys =
                redis.keys("quizopia:gateway:realtime:v1:*").collectList().block();
        assertTrue(keys.stream().noneMatch(key -> key.contains(issued.value())));
        List<String> values = Flux.fromIterable(
                        keys.stream().filter(key -> key.contains(":ticket:")).toList())
                .flatMap(key -> redis.opsForValue().get(key))
                .collectList()
                .block();
        assertTrue(values.stream().noneMatch(value -> value.contains(issued.value())));
        assertTrue(values.stream().noneMatch(value -> value.contains(TOKEN)));
        assertTrue(values.stream().allMatch(value -> value.length() < 512));

        RealtimeTicketStore.TicketMetadata metadata =
                store.consume(issued.value()).block();
        assertEquals(actor, metadata.actorId());
        assertEquals(ORIGIN, metadata.origin());
        assertEquals(RealtimeTicketStore.tokenFingerprint(TOKEN), metadata.tokenFingerprint());
    }

    @Test
    void atomicConsumeAllowsExactlyOneWinnerAcrossStoreInstances() {
        RealtimeTicketStore first = store();
        RealtimeTicketStore second = store();
        String ticket = first.issue(UUID.randomUUID(), TOKEN, ORIGIN).block().value();

        List<String> outcomes = Flux.merge(
                        first.consume(ticket).map(ignored -> "CONSUMED").onErrorReturn("DENIED"),
                        second.consume(ticket).map(ignored -> "CONSUMED").onErrorReturn("DENIED"))
                .collectList()
                .block();

        assertEquals(1, outcomes.stream().filter("CONSUMED"::equals).count());
        assertEquals(1, outcomes.stream().filter("DENIED"::equals).count());
    }

    @Test
    void issueRateAndOutstandingCapacityAreFailClosed() {
        properties.setTicketIssueRatePerActor(1);
        properties.setMaximumOutstandingTickets(1);
        RealtimeTicketStore store = store();
        UUID actor = UUID.randomUUID();
        store.issue(actor, TOKEN, ORIGIN).block();

        var sameActor =
                assertThrows(RealtimeTicketStore.TicketStoreFailure.class, () -> store.issue(actor, TOKEN, ORIGIN)
                        .block());
        assertEquals(RealtimeTicketStore.TicketStoreFailure.Kind.RATE_LIMITED, sameActor.kind());

        var capacity = assertThrows(
                RealtimeTicketStore.TicketStoreFailure.class,
                () -> store.issue(UUID.randomUUID(), TOKEN, ORIGIN).block());
        assertEquals(RealtimeTicketStore.TicketStoreFailure.Kind.RATE_LIMITED, capacity.kind());
    }

    @Test
    void expiredAndReplayTicketsAreDenied() throws Exception {
        properties.setTicketTtl(Duration.ofSeconds(1));
        RealtimeTicketStore store = store();
        String ticket = store.issue(UUID.randomUUID(), TOKEN, ORIGIN).block().value();
        Thread.sleep(1_200);

        var expired = assertThrows(RealtimeTicketStore.TicketStoreFailure.class, () -> store.consume(ticket)
                .block());
        assertEquals(RealtimeTicketStore.TicketStoreFailure.Kind.INVALID, expired.kind());

        String oneUse = store.issue(UUID.randomUUID(), TOKEN, ORIGIN).block().value();
        store.consume(oneUse).block();
        var replay = assertThrows(RealtimeTicketStore.TicketStoreFailure.class, () -> store.consume(oneUse)
                .block());
        assertEquals(RealtimeTicketStore.TicketStoreFailure.Kind.INVALID, replay.kind());
        assertNotEquals(ticket, oneUse);
        assertFalse(oneUse.contains("="));
    }

    private RealtimeTicketStore store() {
        return new RealtimeTicketStore(redis, properties, Clock.systemUTC());
    }
}
