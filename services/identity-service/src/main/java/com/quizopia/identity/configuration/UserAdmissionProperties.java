package com.quizopia.identity.configuration;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.identity.user-admission")
public class UserAdmissionProperties {
    private boolean enabled;
    private boolean requireSecureTransport = true;
    private Set<String> allowedClients = Set.of();
    private int queryTimeoutSeconds = 2;
    private boolean reconciliationEnabled;
    private int batchSize = 50;
    private Duration pollingInterval = Duration.ofSeconds(5);

    public boolean isRequireSecureTransport() {
        return requireSecureTransport;
    }

    public void setRequireSecureTransport(boolean value) {
        requireSecureTransport = value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public Set<String> getAllowedClients() {
        return allowedClients;
    }

    public void setAllowedClients(Set<String> value) {
        allowedClients = Set.copyOf(value);
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int value) {
        queryTimeoutSeconds = value;
    }

    public boolean isReconciliationEnabled() {
        return reconciliationEnabled;
    }

    public void setReconciliationEnabled(boolean value) {
        reconciliationEnabled = value;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int value) {
        batchSize = value;
    }

    public Duration getPollingInterval() {
        return pollingInterval;
    }

    public void setPollingInterval(Duration value) {
        pollingInterval = value;
    }

    public void validate() {
        if (queryTimeoutSeconds < 1
                || queryTimeoutSeconds > 30
                || batchSize < 1
                || batchSize > 500
                || pollingInterval == null
                || pollingInterval.compareTo(Duration.ofSeconds(1)) < 0)
            throw new IllegalStateException("Invalid bounded authority configuration");
        if ((enabled && allowedClients.isEmpty())
                || allowedClients.stream().anyMatch(value -> !value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")))
            throw new IllegalStateException("Exact admission caller allowlist required");
    }
}
