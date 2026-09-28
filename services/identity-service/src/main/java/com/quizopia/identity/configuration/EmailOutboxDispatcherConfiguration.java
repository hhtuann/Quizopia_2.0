package com.quizopia.identity.configuration;

import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxDispatcher;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxStore;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationSendFence;
import com.quizopia.identity.application.emailverification.delivery.VerificationEmailDelivery;
import com.quizopia.identity.infrastructure.email.EmailVerificationOutboxPoller;
import com.quizopia.identity.infrastructure.email.SpringMailVerificationEmailDelivery;
import com.quizopia.identity.infrastructure.email.VerificationEmailTemplate;
import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(EmailOutboxDispatcherProperties.class)
public class EmailOutboxDispatcherConfiguration {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "quizopia.identity.email-outbox.dispatcher", name = "enabled", havingValue = "true")
    ScheduledExecutorService emailOutboxLeaseHeartbeatExecutor() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "identity-email-outbox-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    @ConditionalOnProperty(prefix = "quizopia.identity.email-outbox.dispatcher", name = "enabled", havingValue = "true")
    VerificationEmailDelivery verificationEmailDelivery(
            JavaMailSender mailSender, EmailOutboxDispatcherProperties properties) {
        properties.validateEnabledConfiguration();
        return new SpringMailVerificationEmailDelivery(
                mailSender, new VerificationEmailTemplate(), properties.getFromAddress());
    }

    @Bean
    @ConditionalOnProperty(prefix = "quizopia.identity.email-outbox.dispatcher", name = "enabled", havingValue = "true")
    EmailVerificationOutboxDispatcher emailVerificationOutboxDispatcher(
            EmailVerificationOutboxStore store,
            OutboxPayloadCipher payloadCipher,
            VerificationEmailDelivery delivery,
            EmailVerificationSendFence sendFence,
            EmailOutboxDispatcherProperties properties,
            @Qualifier("identityClock") Clock clock,
            ScheduledExecutorService emailOutboxLeaseHeartbeatExecutor) {
        properties.validateEnabledConfiguration();
        return new EmailVerificationOutboxDispatcher(
                store,
                payloadCipher,
                delivery,
                sendFence,
                properties,
                clock,
                "identity-" + UUID.randomUUID(),
                emailOutboxLeaseHeartbeatExecutor);
    }

    @Bean
    @ConditionalOnProperty(prefix = "quizopia.identity.email-outbox.dispatcher", name = "enabled", havingValue = "true")
    EmailVerificationOutboxPoller emailVerificationOutboxPoller(EmailVerificationOutboxDispatcher dispatcher) {
        return new EmailVerificationOutboxPoller(dispatcher);
    }
}
