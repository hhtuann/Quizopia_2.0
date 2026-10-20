package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.realtime.*;
import com.rabbitmq.client.*;
import com.zaxxer.hikari.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real RabbitMQ and PostgreSQL: confirmed dispatch, best-effort zero-binding, outage retry, quarantine. */
@Testcontainers
class RealtimeInvalidationBrokerIntegrationTest {
    static final String EXCHANGE = RealtimeInvalidationPublisher.EXCHANGE;
    static final String QUEUE = "quizopia.gateway.realtime-invalidation-test";
    static final String USER = UUID.randomUUID().toString().replace("-", "");
    static final String PASSWORD = UUID.randomUUID().toString();

    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.1-management")
            .withEnv("RABBITMQ_DEFAULT_USER", UUID.randomUUID().toString())
            .withEnv("RABBITMQ_DEFAULT_PASS", UUID.randomUUID().toString())
            .withEnv(
                    "RABBITMQ_DEFAULT_VHOST",
                    "realtime_" + UUID.randomUUID().toString().replace("-", ""))
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    static String vhost() {
        return RABBIT.getEnvMap().get("RABBITMQ_DEFAULT_VHOST");
    }

    static String adminUser() {
        return RABBIT.getEnvMap().get("RABBITMQ_DEFAULT_USER");
    }

    static String adminPassword() {
        return RABBIT.getEnvMap().get("RABBITMQ_DEFAULT_PASS");
    }

    static Connection admin() throws Exception {
        var factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getMappedPort(5672));
        factory.setVirtualHost(vhost());
        factory.setUsername(adminUser());
        factory.setPassword(adminPassword());
        return factory.newConnection("realtime-invalidation-admin");
    }

    AtomicFinalizationIntegrationTest f;
    HikariDataSource pool;
    JdbcRealtimeInvalidationOutbox outbox;
    RealtimeInvalidationProperties properties;

    @BeforeEach
    void setup() throws Exception {
        f = new AtomicFinalizationIntegrationTest();
        f.setup();
        var config = new HikariConfig();
        config.setJdbcUrl(DB.getJdbcUrl() + "&currentSchema=" + f.schema);
        config.setUsername(DB.getUsername());
        config.setPassword(DB.getPassword());
        config.setMaximumPoolSize(4);
        config.setConnectionTimeout(2000);
        config.setConnectionInitSql("SET quizopia.realtime_invalidation='true'");
        pool = new HikariDataSource(config);
        f.db = new JdbcTemplate(pool);
        f.manager = new JdbcTransactionManager(pool);
        f.core = new com.quizopia.assessment.persistence.JdbcAttemptFinalization(f.db, f.manager);
        outbox = new JdbcRealtimeInvalidationOutbox(f.db, f.manager);
        properties = new RealtimeInvalidationProperties();
        properties.setPublisherEnabled(true);
        properties.setAllowInsecureLoopback(true);
        properties.setHost(RABBIT.getHost());
        properties.setPort(RABBIT.getMappedPort(5672));
        properties.setVirtualHost(vhost());
        properties.setUsername(USER);
        properties.setPassword(PASSWORD);
        try (var connection = admin();
                var channel = connection.createChannel()) {
            channel.exchangeDeclare(EXCHANGE, "direct", true);
            channel.queueDeclare(QUEUE, true, false, false, Map.of("x-queue-type", "quorum"));
            channel.queueBind(QUEUE, EXCHANGE, RealtimeInvalidationPublisher.PUBLICATION_ROUTING_KEY);
            channel.queueBind(QUEUE, EXCHANGE, RealtimeInvalidationPublisher.ATTEMPT_ROUTING_KEY);
            channel.queuePurge(QUEUE);
        }
        RABBIT.execInContainer("rabbitmqctl", "add_user", USER, PASSWORD);
        RABBIT.execInContainer(
                "rabbitmqctl",
                "set_permissions",
                "-p",
                vhost(),
                USER,
                "^$",
                "^quizopia\\.realtime\\.invalidation\\.v1$",
                "^$");
    }

    @AfterEach
    void cleanup() {
        if (pool != null) pool.close();
    }

    RealtimeInvalidationPublisher publisher() {
        return new RealtimeInvalidationPublisher(properties);
    }

    UUID seedEvent(String eventType, String resourceKind) {
        UUID id = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        f.db.update(
                "INSERT INTO realtime_invalidation_outbox(event_id,event_type,event_version,resource_kind,resource_id,occurred_at) VALUES(?,?,?,?,?,clock_timestamp())",
                id,
                eventType,
                1,
                resourceKind,
                resource);
        return id;
    }

    String state(UUID id) {
        return f.scalar("SELECT state FROM realtime_invalidation_outbox WHERE event_id=?", id);
    }

    @Test
    void confirmedDispatchDeliversFrozenEnvelopeAndMarksPublished() throws Exception {
        var worker =
                new RealtimeInvalidationPublisherWorker(outbox, publisher(), properties, new SimpleMeterRegistry());
        UUID event = seedEvent("PUBLICATION_INVALIDATED", "PUBLICATION");
        worker.tick();
        assertEquals("PUBLISHED", state(event));
        try (var connection = admin();
                var channel = connection.createChannel()) {
            var response = channel.basicGet(QUEUE, true);
            assertNotNull(response);
            var body = new String(response.getBody(), StandardCharsets.UTF_8);
            var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
            assertEquals(6, json.size());
            assertEquals(event.toString(), json.path("eventId").asString());
            assertEquals("PUBLICATION_INVALIDATED", json.path("eventType").asString());
            assertEquals(1, json.path("eventVersion").intValue());
            assertEquals("PUBLICATION", json.path("resourceKind").asString());
            assertEquals(json.path("resourceId").asString().length(), 36);
            assertTrue(json.path("occurredAt").asString().endsWith("Z"));
            assertEquals(event.toString(), response.getProps().getMessageId());
            assertEquals("PUBLICATION_INVALIDATED", response.getProps().getType());
            assertEquals(2, response.getProps().getDeliveryMode().intValue());
            assertEquals("assessment-service", response.getProps().getAppId());
            assertEquals(USER, response.getProps().getUserId());
            assertEquals(
                    RealtimeInvalidationPublisher.PUBLICATION_ROUTING_KEY,
                    response.getEnvelope().getRoutingKey());
        }
        worker.close();
    }

    @Test
    void zeroBindingAckIsAcceptedBecauseInvalidationsAreBestEffort() throws Exception {
        try (var connection = admin();
                var channel = connection.createChannel()) {
            channel.queueUnbind(QUEUE, EXCHANGE, RealtimeInvalidationPublisher.PUBLICATION_ROUTING_KEY);
            channel.queueUnbind(QUEUE, EXCHANGE, RealtimeInvalidationPublisher.ATTEMPT_ROUTING_KEY);
        }
        var worker =
                new RealtimeInvalidationPublisherWorker(outbox, publisher(), properties, new SimpleMeterRegistry());
        UUID event = seedEvent("ATTEMPT_INVALIDATED", "ATTEMPT");
        worker.tick();
        assertEquals("PUBLISHED", state(event));
        worker.close();
    }

    @Test
    void missingExchangeRetriesAndNeverLeakesOrAcknowledges() throws Exception {
        try (var connection = admin();
                var channel = connection.createChannel()) {
            channel.exchangeDelete(EXCHANGE);
        }
        var worker =
                new RealtimeInvalidationPublisherWorker(outbox, publisher(), properties, new SimpleMeterRegistry());
        UUID event = seedEvent("ATTEMPT_INVALIDATED", "ATTEMPT");
        worker.tick();
        assertEquals("PENDING", state(event));
        assertEquals(
                1L,
                Long.parseLong(f.scalar(
                        "SELECT delivery_attempts FROM realtime_invalidation_outbox WHERE event_id=?", event)));
        double deferred = Double.parseDouble(f.scalar(
                "SELECT extract(epoch FROM (next_attempt_at-clock_timestamp())) FROM realtime_invalidation_outbox WHERE event_id=?",
                event));
        assertTrue(deferred >= 0);
        var failure = assertThrows(IllegalStateException.class, () -> publisher()
                .publish(event, "ATTEMPT_INVALIDATED", "ATTEMPT", UUID.randomUUID(), Instant.now()));
        assertEquals("Realtime invalidation delivery unconfirmed", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(PASSWORD));
        worker.close();
    }

    @Test
    void poisonEventsAreQuarantinedAfterBoundedAttempts() {
        properties.setMaxDeliveryAttempts(1);
        properties.setHost("127.0.0.1");
        properties.setPort(1);
        var worker =
                new RealtimeInvalidationPublisherWorker(outbox, publisher(), properties, new SimpleMeterRegistry());
        UUID event = seedEvent("ATTEMPT_INVALIDATED", "ATTEMPT");
        worker.tick();
        assertEquals("QUARANTINED", state(event));
        worker.close();
    }

    @Test
    void wrongCredentialsNeverLeakAndBrokerIoIsForbiddenInsideTransactions() throws Exception {
        properties.setPassword("wrong-" + UUID.randomUUID());
        var worker =
                new RealtimeInvalidationPublisherWorker(outbox, publisher(), properties, new SimpleMeterRegistry());
        UUID event = seedEvent("PUBLICATION_INVALIDATED", "PUBLICATION");
        worker.tick();
        assertEquals("PENDING", state(event));
        worker.close();
        properties.setPassword(PASSWORD);
        new TransactionTemplate(f.manager).execute(status -> {
            var failure = assertThrows(IllegalStateException.class, () -> publisher()
                    .publish(event, "PUBLICATION_INVALIDATED", "PUBLICATION", UUID.randomUUID(), Instant.now()));
            assertEquals("Broker I/O inside an Assessment transaction is forbidden", failure.getMessage());
            return null;
        });
    }
}
