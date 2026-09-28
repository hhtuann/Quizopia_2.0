package com.quizopia.identity.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quizopia.identity.email-outbox.dispatcher")
public class EmailOutboxDispatcherProperties {
    public static final int MAXIMUM_BATCH_SIZE = 100;

    private boolean enabled;
    private Duration pollingInterval = Duration.ofSeconds(5);
    private int batchSize = 20;
    private Duration leaseDuration = Duration.ofMinutes(1);
    private int maximumAttempts = 5;
    private Duration initialRetryDelay = Duration.ofSeconds(30);
    private Duration maximumRetryDelay = Duration.ofMinutes(5);
    private String fromAddress;

    public void validateEnabledConfiguration() {
        requirePositive(pollingInterval, "pollingInterval");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(initialRetryDelay, "initialRetryDelay");
        requirePositive(maximumRetryDelay, "maximumRetryDelay");
        if (batchSize <= 0 || batchSize > MAXIMUM_BATCH_SIZE) {
            throw new IllegalStateException("Email outbox batch size must be between 1 and " + MAXIMUM_BATCH_SIZE);
        }
        if (maximumAttempts <= 0) {
            throw new IllegalStateException("Email outbox maximum attempts must be positive");
        }
        if (maximumRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalStateException("Email outbox maximum retry delay must not be shorter than initial delay");
        }
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalStateException("Email outbox sender address is required when dispatch is enabled");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("Email outbox " + name + " must be positive");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollingInterval() {
        return pollingInterval;
    }

    public void setPollingInterval(Duration pollingInterval) {
        this.pollingInterval = pollingInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public int getMaximumAttempts() {
        return maximumAttempts;
    }

    public void setMaximumAttempts(int maximumAttempts) {
        this.maximumAttempts = maximumAttempts;
    }

    public Duration getInitialRetryDelay() {
        return initialRetryDelay;
    }

    public void setInitialRetryDelay(Duration initialRetryDelay) {
        this.initialRetryDelay = initialRetryDelay;
    }

    public Duration getMaximumRetryDelay() {
        return maximumRetryDelay;
    }

    public void setMaximumRetryDelay(Duration maximumRetryDelay) {
        this.maximumRetryDelay = maximumRetryDelay;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }
}
