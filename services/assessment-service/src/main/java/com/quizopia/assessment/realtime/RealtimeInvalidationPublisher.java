package com.quizopia.assessment.realtime;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Dedicated serialized confirmed publisher for W4 best-effort invalidations. R3 semantics:
 * mandatory is deliberately false; a zero-binding ACK is accepted and is not proof of
 * subscriber delivery. Topology and ACL provisioning belong to operators.
 */
public final class RealtimeInvalidationPublisher implements AutoCloseable {
    public static final String EXCHANGE = "quizopia.realtime.invalidation.v1";
    public static final String PUBLICATION_ROUTING_KEY = "publication.invalidated.v1";
    public static final String ATTEMPT_ROUTING_KEY = "attempt.invalidated.v1";
    private final ConnectionFactory factory;
    private final String publisherIdentity;
    private Connection connection;
    private Channel channel;

    public RealtimeInvalidationPublisher(RealtimeInvalidationProperties properties) {
        properties.validate(false);
        if (!properties.isPublisherEnabled())
            throw new IllegalStateException("Realtime invalidation publisher disabled");
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

    public synchronized void publish(
            UUID eventId, String eventType, String resourceKind, UUID resourceId, Instant occurredAt) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Broker I/O inside an Assessment transaction is forbidden");
        String routingKey = "PUBLICATION_INVALIDATED".equals(eventType) ? PUBLICATION_ROUTING_KEY : ATTEMPT_ROUTING_KEY;
        String body = "{\"eventId\":\""
                + eventId + "\",\"eventType\":\"" + eventType + "\",\"eventVersion\":1,\"resourceKind\":\""
                + resourceKind + "\",\"resourceId\":\"" + resourceId + "\",\"occurredAt\":\"" + occurredAt + "\"}";
        byte[] envelope = body.getBytes(StandardCharsets.UTF_8);
        if (envelope.length > 1024) throw new IllegalArgumentException("Invalid realtime invalidation envelope");
        try {
            if (connection == null || !connection.isOpen() || channel == null || !channel.isOpen()) {
                close();
                connection = factory.newConnection("assessment-realtime-invalidation-publisher");
                channel = connection.createChannel();
                channel.confirmSelect();
            }
            var props = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .contentEncoding("utf-8")
                    .deliveryMode(2)
                    .messageId(eventId.toString())
                    .type(eventType)
                    .appId("assessment-service")
                    .userId(publisherIdentity)
                    .build();
            // mandatory=false per frozen R3 best-effort semantics: a zero-binding ACK is acceptable.
            channel.basicPublish(EXCHANGE, routingKey, false, props, envelope);
            channel.waitForConfirmsOrDie(3000);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("Realtime invalidation delivery unconfirmed");
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
