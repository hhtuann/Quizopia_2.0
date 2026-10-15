package com.quizopia.assessment.monitoring;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Dedicated serialized confirmed publisher. Topology and ACL provisioning belong to operators. */
public final class AssessmentMonitoringPublisher implements AutoCloseable {
    public static final String EXCHANGE = "quizopia.assessment.monitoring.v1";
    public static final String ROUTING_KEY = "answer.persisted.v1";
    public static final String TYPE = "ASSESSMENT_ANSWER_PERSISTED";
    private final ConnectionFactory factory;
    private final String publisherIdentity;
    private Connection connection;
    private Channel channel;
    private final AtomicBoolean returned = new AtomicBoolean();

    public AssessmentMonitoringPublisher(MonitoringPublisherProperties properties) {
        properties.validate(false);
        if (!properties.isPublisherEnabled()) throw new IllegalStateException("Monitoring publisher disabled");
        factory = new ConnectionFactory();
        publisherIdentity = properties.getUsername();
        factory.setHost(properties.getHost());
        factory.setPort(properties.getPort());
        factory.setVirtualHost(properties.getVirtualHost());
        factory.setUsername(properties.getUsername());
        factory.setPassword(properties.getPassword());
        factory.setConnectionTimeout(2000);
        factory.setHandshakeTimeout(2000);
        factory.setChannelRpcTimeout(2000);
        factory.setShutdownTimeout(2000);
        factory.setRequestedHeartbeat(5);
        factory.setMaxInboundMessageBodySize(65536);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        // Bounded enqueue avoids unbounded blocking socket writes when the broker stops reading.
        factory.useNio();
        factory.setNioParams(new com.rabbitmq.client.impl.nio.NioParams()
                .setNbIoThreads(1)
                .setWriteQueueCapacity(16)
                .setWriteEnqueuingTimeoutInMs(2000));
        if (!properties.isAllowInsecureLoopback()) {
            try {
                factory.useSslProtocol(SSLContext.getDefault());
                factory.enableHostnameVerification();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException("Trusted broker TLS unavailable");
            }
        }
    }

    public synchronized void publish(byte[] body, UUID id) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Broker I/O inside an Assessment transaction is forbidden");
        if (body == null || body.length == 0 || body.length > 4096 || id == null)
            throw new IllegalArgumentException("Invalid monitoring envelope");
        try {
            if (connection == null || !connection.isOpen() || channel == null || !channel.isOpen()) {
                close();
                connection = factory.newConnection("assessment-monitoring-publisher");
                channel = connection.createChannel();
                channel.addReturnListener(message -> returned.set(true));
                channel.confirmSelect();
            }
            returned.set(false);
            var props = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .contentEncoding("utf-8")
                    .deliveryMode(2)
                    .messageId(id.toString())
                    .type(TYPE)
                    .appId("assessment-service")
                    .userId(publisherIdentity)
                    .build();
            // RabbitMQ emits mandatory RETURN before its confirm. The official client dispatches
            // return listeners on the channel I/O thread before processing the following ACK.
            channel.basicPublish(EXCHANGE, ROUTING_KEY, true, props, body);
            channel.waitForConfirmsOrDie(3000);
            if (returned.get()) throw new IllegalStateException("Monitoring envelope unroutable");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("Monitoring broker delivery unconfirmed");
        }
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            try {
                channel.abort();
            } catch (Exception ignored) {
            }
            channel = null;
        }
        if (connection != null) {
            connection.abort(2000);
            connection = null;
        }
    }
}
