package com.quizopia.gateway.realtime.invalidation;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;

/**
 * Per-instance best-effort consumer: declares its own exclusive auto-delete queue so every Gateway
 * instance receives its own copy of each hint. Loss, duplication and reordering are expected and
 * REST remains authoritative. Broker messages are never authorization evidence.
 */
public final class RealtimeInvalidationConsumer implements AutoCloseable {
    private static final String METRIC = "gateway.realtime.invalidation";
    private final RealtimeInvalidationProperties properties;
    private final RealtimeSubscriptionRegistry registry;
    private final MeterRegistry metrics;
    private final ConnectionFactory factory;
    private final AtomicReference<Connection> connection = new AtomicReference<>();
    private final AtomicReference<Channel> channel = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ArrayDeque<String> recentEventIds = new ArrayDeque<>();
    private ScheduledExecutorService executor;

    public RealtimeInvalidationConsumer(
            RealtimeInvalidationProperties properties,
            RealtimeSubscriptionRegistry registry,
            MeterRegistry metrics,
            boolean production) {
        properties.validate(production);
        if (!properties.isEnabled()) throw new IllegalStateException("Realtime invalidation consumer disabled");
        this.properties = properties;
        this.registry = registry;
        this.metrics = metrics;
        factory = new ConnectionFactory();
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

    public synchronized void start() {
        if (closed.get()) throw new IllegalStateException("Realtime invalidation consumer is closed");
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "gateway-realtime-invalidation");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(this::connect);
    }

    public boolean isConnected() {
        Connection current = connection.get();
        return current != null && current.isOpen();
    }

    void connect() {
        if (closed.get()) return;
        Connection opened = null;
        try {
            opened = factory.newConnection("gateway-realtime-invalidation");
            // Broker-initiated loss (restart, forced close, fault) reconnects with a fresh queue.
            final Connection current = opened;
            current.addShutdownListener(cause -> {
                if (connection.get() == current) reschedule();
            });
            Channel openedChannel = opened.createChannel();
            openedChannel.basicQos(properties.getPrefetch());
            // A fresh exclusive queue per connection gives this instance its own copy of each hint
            // and makes reconnect naturally tolerate loss/duplication.
            String queue = properties.getQueuePrefix() + "." + UUID.randomUUID();
            openedChannel.queueDeclare(queue, false, true, true, null);
            openedChannel.queueBind(
                    queue, RealtimeInvalidationEvent.EXCHANGE, RealtimeInvalidationEvent.PUBLICATION_ROUTING_KEY);
            openedChannel.queueBind(
                    queue, RealtimeInvalidationEvent.EXCHANGE, RealtimeInvalidationEvent.ATTEMPT_ROUTING_KEY);
            openedChannel.basicConsume(queue, true, this::deliver, tag -> reschedule());
            Connection previous = connection.getAndSet(opened);
            if (previous != null) previous.abort(2000);
            Channel previousChannel = channel.getAndSet(openedChannel);
            if (previousChannel != null) {
                try {
                    previousChannel.abort();
                } catch (Exception ignored) {
                }
            }
            outcome("connected");
        } catch (Exception failure) {
            // A half-open connection is not a consuming connection; report closed and retry later.
            if (opened != null) opened.abort(2000);
            outcome("broker_unavailable");
            reschedule();
        }
    }

    void deliver(String tag, Delivery message) {
        RealtimeInvalidationEvent event = null;
        try {
            event = RealtimeInvalidationEvent.parse(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) {
            event = null;
        }
        if (event == null) {
            outcome("invalid_schema");
            return;
        }
        if (!remember(event.eventId().toString())) {
            outcome("duplicate");
            return;
        }
        try {
            int matched = registry.invalidate(
                    "PUBLICATION".equals(event.resourceKind()) ? "PUBLICATION_TEACHER" : "ATTEMPT_OWNER",
                    event.resourceId().toString(),
                    event.eventId().toString(),
                    event.occurredAt());
            outcome(matched > 0 ? "fanned_out" : "no_subscribers");
        } catch (RuntimeException failure) {
            outcome("fanout_failed");
        }
    }

    private boolean remember(String eventId) {
        synchronized (recentEventIds) {
            if (recentEventIds.contains(eventId)) return false;
            recentEventIds.addLast(eventId);
            while (recentEventIds.size() > properties.getDeduplicationCapacity()) recentEventIds.pollFirst();
            return true;
        }
    }

    private void reschedule() {
        if (closed.get()) return;
        try {
            executor.schedule(this::connect, 5, TimeUnit.SECONDS);
        } catch (RuntimeException ignored) {
            // Executor already stopped by close().
        }
    }

    private void outcome(String name) {
        metrics.counter(METRIC, "outcome", name).increment();
    }

    @Override
    public synchronized void close() {
        closed.set(true);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        Channel openChannel = channel.getAndSet(null);
        if (openChannel != null) {
            try {
                openChannel.abort();
            } catch (Exception ignored) {
            }
        }
        Connection openConnection = connection.getAndSet(null);
        if (openConnection != null) openConnection.abort(2000);
    }
}
