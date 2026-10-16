package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.assessment.monitoring.*;
import com.rabbitmq.client.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;

/** Real isolated broker/PG; operator declarations and scoped ACLs are fixture setup only. */
@Testcontainers
class AssessmentMonitoringBrokerIntegrationTest {
    static final String ADMIN = "admin_" + UUID.randomUUID().toString().replace("-", "");
    static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    static final String USER = "assessment_" + UUID.randomUUID().toString().replace("-", "");
    static final String PASSWORD = UUID.randomUUID().toString();
    static final String VHOST = "monitoring_" + UUID.randomUUID();
    static final String QUEUE = "quizopia.proctoring.assessment-monitoring.v1";

    @Container
    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.1-management")
            .withEnv("RABBITMQ_DEFAULT_USER", ADMIN)
            .withEnv("RABBITMQ_DEFAULT_PASS", ADMIN_PASSWORD)
            .withEnv("RABBITMQ_DEFAULT_VHOST", VHOST)
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    @Container
    static final PostgreSQLContainer<?> DB = AtomicFinalizationIntegrationTest.DB;

    AssessmentMonitoringOutboxIntegrationTest state;
    Connection admin;
    Channel operator;
    AssessmentMonitoringPublisher publisher;

    @BeforeEach
    void setup() throws Exception {
        state = new AssessmentMonitoringOutboxIntegrationTest();
        state.setup();
        var f = new ConnectionFactory();
        f.setHost(RABBIT.getHost());
        f.setPort(RABBIT.getMappedPort(5672));
        f.setVirtualHost(VHOST);
        f.setUsername(ADMIN);
        f.setPassword(ADMIN_PASSWORD);
        f.setAutomaticRecoveryEnabled(false);
        f.setConnectionTimeout(2000);
        f.setHandshakeTimeout(2000);
        f.setChannelRpcTimeout(2000);
        admin = f.newConnection();
        operator = admin.createChannel();
        operator.exchangeDeclare(AssessmentMonitoringPublisher.EXCHANGE, "direct", true);
        operator.queueDeclare(QUEUE, true, false, false, Map.of("x-queue-type", "quorum"));
        operator.queueBind(QUEUE, AssessmentMonitoringPublisher.EXCHANGE, AssessmentMonitoringPublisher.ROUTING_KEY);
        operator.queuePurge(QUEUE);
        var added = RABBIT.execInContainer("rabbitmqctl", "add_user", USER, PASSWORD);
        // Later test methods reuse the same isolated user; an already-existing user is expected.
        if (added.getExitCode() != 0)
            assertEquals(
                    0,
                    RABBIT.execInContainer("rabbitmqctl", "authenticate_user", USER, PASSWORD)
                            .getExitCode());
        assertEquals(
                0,
                RABBIT.execInContainer(
                                "rabbitmqctl",
                                "set_permissions",
                                "-p",
                                VHOST,
                                USER,
                                "^$",
                                "^quizopia\\.assessment\\.monitoring\\.v1$",
                                "^$")
                        .getExitCode());
        publisher = new AssessmentMonitoringPublisher(properties(PASSWORD));
    }

    MonitoringPublisherProperties properties(String password) {
        var p = new MonitoringPublisherProperties();
        p.setPublisherEnabled(true);
        p.setAllowInsecureLoopback(true);
        p.setHost(RABBIT.getHost());
        p.setPort(RABBIT.getMappedPort(5672));
        p.setVirtualHost(VHOST);
        p.setUsername(USER);
        p.setPassword(password);
        return p;
    }

    MonitoringOutbox.Event event() {
        var a = state.f.active(state.monitored(5), 5);
        state.save(a, 4, state.answer("0001"), 1);
        return state.outbox.claim(UUID.randomUUID(), 1).getFirst();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (publisher != null) publisher.close();
        if (operator != null && operator.isOpen()) operator.close();
        if (admin != null && admin.isOpen()) admin.close();
        if (state != null) state.cleanup();
    }

    @Test
    void durableMandatoryConfirmedPublishUsesExactMetadataAndMinimumAcl() throws Exception {
        var event = event();
        publisher.publish(event.payload(), event.id());
        state.outbox.published(event);
        var got = operator.basicGet(QUEUE, true);
        assertNotNull(got);
        assertArrayEquals(event.payload(), got.getBody());
        assertEquals(event.id().toString(), got.getProps().getMessageId());
        assertEquals(2, got.getProps().getDeliveryMode());
        assertEquals("application/json", got.getProps().getContentType());
        assertEquals(AssessmentMonitoringPublisher.TYPE, got.getProps().getType());
        assertEquals("assessment-service", got.getProps().getAppId());
        assertEquals(USER, got.getProps().getUserId());
        assertEquals(
                AssessmentMonitoringPublisher.ROUTING_KEY, got.getEnvelope().getRoutingKey());
        assertFalse(new String(got.getBody(), StandardCharsets.UTF_8).contains("0001"));
        assertEquals(
                "PUBLISHED",
                state.f.scalar("SELECT state FROM assessment_monitoring_outbox WHERE event_id=?", event.id()));
    }

    @Test
    void mandatoryReturnNeverMarksPublishedAndRecoveryUsesTheSameEvent() throws Exception {
        var event = event();
        state.outbox.retry(event);
        operator.queueUnbind(QUEUE, AssessmentMonitoringPublisher.EXCHANGE, AssessmentMonitoringPublisher.ROUTING_KEY);
        var metrics = new SimpleMeterRegistry();
        var worker = new MonitoringPublisherWorker(state.outbox, publisher, metrics);
        worker.tick();
        assertEquals(
                "PENDING",
                state.f.scalar("SELECT state FROM assessment_monitoring_outbox WHERE event_id=?", event.id()));
        assertNull(operator.basicGet(QUEUE, true));
        assertEquals(
                1,
                metrics.counter("quizopia.assessment.monitoring.publish", "outcome", "retry")
                        .count());
        operator.queueBind(QUEUE, AssessmentMonitoringPublisher.EXCHANGE, AssessmentMonitoringPublisher.ROUTING_KEY);
        worker.tick();
        var got = operator.basicGet(QUEUE, true);
        assertNotNull(got);
        assertArrayEquals(event.payload(), got.getBody());
        assertEquals(event.id().toString(), got.getProps().getMessageId());
        assertEquals(
                "PUBLISHED",
                state.f.scalar("SELECT state FROM assessment_monitoring_outbox WHERE event_id=?", event.id()));
        assertTrue(state.outbox.claim(UUID.randomUUID(), 5).isEmpty());
        worker.close();
    }

    @Test
    void uncertainDatabaseAcknowledgmentDuplicatesOnlyTheSameImmutableEvent() throws Exception {
        var event = event();
        publisher.publish(event.payload(), event.id());
        // Broker accepted, application crashed before its DB acknowledgment: expire the owned delivery lease.
        state.f.db.update(
                "UPDATE assessment_monitoring_outbox SET lease_until=clock_timestamp()-interval '1 second' WHERE event_id=?",
                event.id());
        new MonitoringPublisherWorker(state.outbox, publisher, new SimpleMeterRegistry()).tick();
        var first = operator.basicGet(QUEUE, true);
        var second = operator.basicGet(QUEUE, true);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(event.id().toString(), first.getProps().getMessageId());
        assertEquals(first.getProps().getMessageId(), second.getProps().getMessageId());
        assertArrayEquals(first.getBody(), second.getBody());
        assertNull(operator.basicGet(QUEUE, true));
    }

    @Test
    void wrongCredentialAndAbsentExchangeStayUnconfirmedWithoutLeakingCredentials() throws Exception {
        var event = event();
        try (var wrong =
                new AssessmentMonitoringPublisher(properties(UUID.randomUUID().toString()))) {
            var error = assertThrows(IllegalStateException.class, () -> wrong.publish(event.payload(), event.id()));
            assertEquals("Monitoring broker delivery unconfirmed", error.getMessage());
            assertNull(error.getCause());
        }
        operator.queueUnbind(QUEUE, AssessmentMonitoringPublisher.EXCHANGE, AssessmentMonitoringPublisher.ROUTING_KEY);
        operator.exchangeDelete(AssessmentMonitoringPublisher.EXCHANGE);
        var error = assertThrows(IllegalStateException.class, () -> publisher.publish(event.payload(), event.id()));
        assertNull(error.getCause());
        assertEquals(
                "PENDING",
                state.f.scalar("SELECT state FROM assessment_monitoring_outbox WHERE event_id=?", event.id()));
    }

    @Test
    void brokerWorkIsRejectedInsideAnswerTransactionsAndBrokerLossCannotBlockSave() {
        var event = event();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(state.f.manager).execute(s -> {
            publisher.publish(event.payload(), event.id());
            return null;
        }));
        RABBIT.getDockerClient().pauseContainerCmd(RABBIT.getContainerId()).exec();
        try {
            var a = state.f.active(state.monitored(5), 5);
            state.save(a, 4, state.answer("0001"), 1);
            assertEquals("1", state.f.scalar("SELECT revision FROM attempts WHERE id=?", a));
            assertEquals(2, state.count());
            assertThrows(IllegalStateException.class, () -> publisher.publish(event.payload(), event.id()));
            assertEquals(
                    "PENDING",
                    state.f.scalar("SELECT state FROM assessment_monitoring_outbox WHERE event_id=?", event.id()));
        } finally {
            RABBIT.getDockerClient()
                    .unpauseContainerCmd(RABBIT.getContainerId())
                    .exec();
        }
    }

    @Test
    void brokerChecksPublisherIdentityAndRejectsQueueReadOrTopologyConfiguration() throws Exception {
        var f = new ConnectionFactory();
        f.setHost(RABBIT.getHost());
        f.setPort(RABBIT.getMappedPort(5672));
        f.setVirtualHost(VHOST);
        f.setUsername(USER);
        f.setPassword(PASSWORD);
        f.setAutomaticRecoveryEnabled(false);
        f.setConnectionTimeout(2000);
        f.setHandshakeTimeout(2000);
        f.setChannelRpcTimeout(2000);
        try (var connection = f.newConnection()) {
            var read = connection.createChannel();
            try {
                brokerDenial(403, () -> read.basicGet(QUEUE, true));
            } finally {
                abort(read);
            }
            var configure = connection.createChannel();
            try {
                brokerDenial(403, () -> configure.exchangeDeclare("forbidden", "direct", true));
            } finally {
                abort(configure);
            }
            var channel = connection.createChannel();
            try {
                channel.confirmSelect();
                brokerDenial(406, () -> {
                    channel.basicPublish(
                            AssessmentMonitoringPublisher.EXCHANGE,
                            AssessmentMonitoringPublisher.ROUTING_KEY,
                            true,
                            new AMQP.BasicProperties.Builder()
                                    .userId(ADMIN)
                                    .deliveryMode(2)
                                    .build(),
                            "{}".getBytes(StandardCharsets.UTF_8));
                    channel.waitForConfirmsOrDie(3000);
                });
            } finally {
                abort(channel);
            }
        }
    }

    void abort(Channel channel) {
        try {
            channel.abort();
        } catch (Exception ignored) {
        }
    }

    void brokerDenial(int expected, org.junit.jupiter.api.function.Executable action) {
        Throwable failure = assertThrows(Exception.class, action);
        while (failure != null && !(failure instanceof ShutdownSignalException)) failure = failure.getCause();
        assertInstanceOf(ShutdownSignalException.class, failure);
        var close = assertInstanceOf(AMQP.Channel.Close.class, ((ShutdownSignalException) failure).getReason());
        assertEquals(expected, close.getReplyCode());
    }
}
