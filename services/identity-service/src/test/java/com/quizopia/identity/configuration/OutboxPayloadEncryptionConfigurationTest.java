package com.quizopia.identity.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import com.quizopia.identity.security.outbox.OutboxPayloadKeyRing;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OutboxPayloadEncryptionConfigurationTest {
    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(OutboxPayloadEncryptionConfiguration.class);

    @Test
    void startupFailsClosedWithoutAnActiveKeyVersion() {
        contextRunner.run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().toString().contains("active outbox encryption key version"));
        });
    }

    @Test
    void startupFailsClosedWhenActiveKeyIsMissingOrMalformed() {
        contextRunner
                .withPropertyValues("quizopia.identity.email-outbox.encryption.active-key-version=test-v1")
                .run(context -> assertNotNull(context.getStartupFailure()));
        contextRunner
                .withPropertyValues(
                        "quizopia.identity.email-outbox.encryption.active-key-version=test-v1",
                        "quizopia.identity.email-outbox.encryption.keys.test-v1=not-base64")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void applicationConfigMapsLocalOutboxEnvironmentVariablesIntoKeyRing() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(OutboxPayloadEncryptionConfiguration.class)
                .withPropertyValues(
                        "IDENTITY_EMAIL_OUTBOX_ACTIVE_KEY_VERSION=v1",
                        "QUIZOPIA_IDENTITY_EMAIL_OUTBOX_ENCRYPTION_KEYS_V1=" + TEST_KEY)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    OutboxPayloadEncryptionProperties properties =
                            context.getBean(OutboxPayloadEncryptionProperties.class);
                    assertEquals("v1", properties.getActiveKeyVersion());
                    assertEquals(TEST_KEY, properties.getKeys().get("v1"));
                    assertNotNull(context.getBean(OutboxPayloadKeyRing.class));
                });
    }

    @Test
    void validRuntimeKeyRingStartsCipherBeans() {
        contextRunner
                .withPropertyValues(
                        "quizopia.identity.email-outbox.encryption.active-key-version=test-v1",
                        "quizopia.identity.email-outbox.encryption.keys.test-v1=" + TEST_KEY)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(OutboxPayloadKeyRing.class));
                    assertNotNull(context.getBean(OutboxPayloadCipher.class));
                });
    }
}
