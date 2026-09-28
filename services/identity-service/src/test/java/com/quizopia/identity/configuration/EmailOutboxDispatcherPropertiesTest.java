package com.quizopia.identity.configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmailOutboxDispatcherPropertiesTest {
    @Test
    void acceptedDefaultsAreValidWithRuntimeSender() {
        var properties = valid();
        assertDoesNotThrow(properties::validateEnabledConfiguration);
    }

    @Test
    void rejectsInvalidOperationalValuesAndMissingSender() {
        assertInvalid(value -> value.setMaximumAttempts(0));
        assertInvalid(value -> value.setBatchSize(0));
        assertInvalid(value -> value.setBatchSize(EmailOutboxDispatcherProperties.MAXIMUM_BATCH_SIZE + 1));
        assertInvalid(value -> value.setPollingInterval(Duration.ZERO));
        assertInvalid(value -> value.setPollingInterval(Duration.ofSeconds(-1)));
        assertInvalid(value -> value.setPollingInterval(null));
        assertInvalid(value -> value.setLeaseDuration(Duration.ZERO));
        assertInvalid(value -> value.setLeaseDuration(Duration.ofSeconds(-1)));
        assertInvalid(value -> value.setInitialRetryDelay(Duration.ZERO));
        assertInvalid(value -> value.setMaximumRetryDelay(null));
        assertInvalid(value -> value.setMaximumRetryDelay(Duration.ofSeconds(1)));
        assertInvalid(value -> value.setFromAddress(" "));
    }

    private static void assertInvalid(java.util.function.Consumer<EmailOutboxDispatcherProperties> mutation) {
        var properties = valid();
        mutation.accept(properties);
        assertThrows(IllegalStateException.class, properties::validateEnabledConfiguration);
    }

    private static EmailOutboxDispatcherProperties valid() {
        var properties = new EmailOutboxDispatcherProperties();
        properties.setEnabled(true);
        properties.setFromAddress("no-reply@quizopia.test");
        return properties;
    }
}
