package com.quizopia.identity.configuration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.security.outbox.OutboxPayloadCipher;
import com.quizopia.identity.security.outbox.OutboxPayloadKeyRing;
import org.junit.jupiter.api.Test;
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
