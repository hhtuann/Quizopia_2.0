package com.quizopia.proctoring.integration;

import com.rabbitmq.client.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;

/** Dedicated read-only broker identity, no declarations or USER/SERVICE credentials in event bodies. */
public final class AssessmentEventConsumer {
    public static final String QUEUE = "quizopia.proctoring.assessment-monitoring.v1";
    private final AssessmentEventInbox inbox;
    private final AssessmentEventsProperties properties;
    private final MeterRegistry meters;

    public AssessmentEventConsumer(AssessmentEventInbox inbox, AssessmentEventsProperties p, MeterRegistry meters) {
        this.inbox = inbox;
        this.properties = p;
        this.meters = meters;
    }

    public void poll() {
        if (!properties.isEnabled()) return;
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(properties.getHost());
            factory.setPort(properties.getPort());
            factory.setVirtualHost(properties.getVirtualHost());
            factory.setUsername(properties.getUsername());
            factory.setPassword(properties.getPassword());
            factory.setAutomaticRecoveryEnabled(false);
            factory.setTopologyRecoveryEnabled(false);
            factory.setConnectionTimeout(2000);
            factory.setHandshakeTimeout(2000);
            factory.setChannelRpcTimeout(2000);
            factory.setShutdownTimeout(1000);
            factory.setRequestedHeartbeat(5);
            factory.setMaxInboundMessageBodySize(65536);
            if (properties.isTls()) {
                factory.useSslProtocol(SSLContext.getDefault());
                factory.enableHostnameVerification();
            }
            try (Connection connection = factory.newConnection("proctoring-assessment-monitoring");
                    Channel channel = connection.createChannel()) {
                for (int i = 0; i < 10; i++) {
                    GetResponse delivery = channel.basicGet(QUEUE, false);
                    if (delivery == null) break;
                    boolean invalid = false;
                    AssessmentAnswerEvent event = null;
                    try {
                        var metadata = delivery.getProps();
                        event = AssessmentAnswerEvent.decode(delivery.getBody());
                        if (!properties.getPublisherIdentity().equals(metadata.getUserId())
                                || !Integer.valueOf(2).equals(metadata.getDeliveryMode())
                                || !"assessment-service".equals(metadata.getAppId())
                                || !AssessmentAnswerEvent.TYPE.equals(metadata.getType())
                                || !event.eventId().toString().equals(metadata.getMessageId())
                                || !"application/json".equals(metadata.getContentType())) invalid = true;
                    } catch (IllegalArgumentException malformed) {
                        invalid = true;
                    }
                    try {
                        if (invalid) {
                            inbox.reject(delivery.getBody());
                            meters.counter("proctoring.assessment.events", "outcome", "rejected")
                                    .increment();
                        } else {
                            inbox.accept(event);
                            meters.counter("proctoring.assessment.events", "outcome", "received")
                                    .increment();
                        }
                        channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    } catch (RuntimeException unavailable) {
                        channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                        throw unavailable;
                    }
                }
            }
        } catch (Exception unavailable) {
            meters.counter("proctoring.assessment.events", "outcome", "pending").increment();
        }
        // Already durable facts can attach after broker outage/restart; no positive authority renewal.
        try {
            inbox.drain();
        } catch (RuntimeException unavailable) {
            meters.counter("proctoring.assessment.events", "outcome", "pending").increment();
        }
    }
}
