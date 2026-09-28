package com.quizopia.identity.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxDispatcher;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationOutboxStore;
import com.quizopia.identity.application.emailverification.delivery.EmailVerificationSendFence;
import com.quizopia.identity.application.emailverification.delivery.VerificationEmailDelivery;
import com.quizopia.identity.infrastructure.email.EmailVerificationOutboxPoller;
import com.quizopia.identity.infrastructure.email.SpringMailVerificationEmailDelivery;
import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class EmailOutboxDispatcherConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(EmailOutboxDispatcherConfiguration.class)
            .withBean(EmailVerificationOutboxStore.class, () -> mock(EmailVerificationOutboxStore.class))
            .withBean(EmailVerificationSendFence.class, () -> mock(EmailVerificationSendFence.class))
            .withBean(OutboxPayloadCipher.class, () -> mock(OutboxPayloadCipher.class))
            .withBean("identityClock", Clock.class, Clock::systemUTC)
            .withPropertyValues(
                    "spring.mail.host=localhost",
                    "spring.mail.port=1025",
                    "spring.mail.properties.mail.smtp.auth=false",
                    "spring.mail.properties.mail.smtp.starttls.enable=false",
                    "quizopia.identity.email-outbox.dispatcher.enabled=true",
                    "quizopia.identity.email-outbox.dispatcher.from-address=no-reply@quizopia.local",
                    "quizopia.identity.email-outbox.dispatcher.polling-interval=PT1H",
                    "quizopia.identity.email-outbox.dispatcher.batch-size=7");

    @Test
    void enabledMailpitConfigurationWiresTheProductionSpringMailAdapterAndPoller() {
        contextRunner.run(context -> {
            assertFalse(context.getStartupFailure() != null);
            assertTrue(context.getBean(VerificationEmailDelivery.class) instanceof SpringMailVerificationEmailDelivery);
            assertNotNull(context.getBean(EmailVerificationOutboxDispatcher.class));
            assertNotNull(context.getBean(EmailVerificationOutboxPoller.class));
            assertEquals(
                    7, context.getBean(EmailOutboxDispatcherProperties.class).getBatchSize());
            JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
            assertEquals("localhost", sender.getHost());
            assertEquals(1025, sender.getPort());
        });
    }

    @Test
    void localProfileLoadsTheCommittedMailpitAndDispatcherConfiguration() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(EmailOutboxDispatcherConfiguration.class)
                .withBean(EmailVerificationOutboxStore.class, () -> mock(EmailVerificationOutboxStore.class))
                .withBean(EmailVerificationSendFence.class, () -> mock(EmailVerificationSendFence.class))
                .withBean(OutboxPayloadCipher.class, () -> mock(OutboxPayloadCipher.class))
                .withBean("identityClock", Clock.class, Clock::systemUTC)
                .withPropertyValues("spring.profiles.active=local")
                .run(context -> {
                    assertFalse(context.getStartupFailure() != null);
                    EmailOutboxDispatcherProperties properties = context.getBean(EmailOutboxDispatcherProperties.class);
                    assertTrue(properties.isEnabled());
                    assertEquals("no-reply@quizopia.local", properties.getFromAddress());
                    assertEquals(Duration.ofSeconds(5), properties.getPollingInterval());
                    assertEquals(20, properties.getBatchSize());
                    assertEquals(Duration.ofMinutes(1), properties.getLeaseDuration());
                    assertEquals(5, properties.getMaximumAttempts());

                    JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
                    assertEquals("localhost", sender.getHost());
                    assertEquals(1025, sender.getPort());
                    assertEquals("false", sender.getJavaMailProperties().getProperty("mail.smtp.auth"));
                    assertEquals("false", sender.getJavaMailProperties().getProperty("mail.smtp.starttls.enable"));
                    assertEquals("10000", sender.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout"));
                    assertEquals("10000", sender.getJavaMailProperties().getProperty("mail.smtp.timeout"));
                    assertEquals("10000", sender.getJavaMailProperties().getProperty("mail.smtp.writetimeout"));
                });
    }

    @Test
    void disabledDispatcherDoesNotCreateDeliveryOrPollingBeans() {
        contextRunner
                .withPropertyValues("quizopia.identity.email-outbox.dispatcher.enabled=false")
                .run(context -> {
                    assertTrue(context.getBeansOfType(VerificationEmailDelivery.class)
                            .isEmpty());
                    assertTrue(context.getBeansOfType(EmailVerificationOutboxDispatcher.class)
                            .isEmpty());
                    assertTrue(context.getBeansOfType(EmailVerificationOutboxPoller.class)
                            .isEmpty());
                });
    }

    @Test
    void enabledDispatcherWithoutSenderAddressFailsStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(EmailOutboxDispatcherConfiguration.class)
                .withBean(EmailVerificationOutboxStore.class, () -> mock(EmailVerificationOutboxStore.class))
                .withBean(OutboxPayloadCipher.class, () -> mock(OutboxPayloadCipher.class))
                .withBean("identityClock", Clock.class, Clock::systemUTC)
                .withPropertyValues(
                        "spring.mail.host=localhost", "quizopia.identity.email-outbox.dispatcher.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
