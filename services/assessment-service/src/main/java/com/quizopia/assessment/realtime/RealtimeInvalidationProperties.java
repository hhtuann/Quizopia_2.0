package com.quizopia.assessment.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.assessment.realtime-invalidation")
public class RealtimeInvalidationProperties {
    private boolean captureEnabled;
    private boolean publisherEnabled;
    private boolean allowInsecureLoopback;
    private String host = "";
    private int port = 5671;
    private String virtualHost = "";
    private String username = "";
    private String password = "";
    private int claimBatch = 5;
    private int maxDeliveryAttempts = 10;
    private int pollDelaySeconds = 1;

    public boolean isCaptureEnabled() {
        return captureEnabled;
    }

    public void setCaptureEnabled(boolean value) {
        captureEnabled = value;
    }

    public boolean isPublisherEnabled() {
        return publisherEnabled;
    }

    public void setPublisherEnabled(boolean value) {
        publisherEnabled = value;
    }

    public boolean isAllowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    public void setAllowInsecureLoopback(boolean value) {
        allowInsecureLoopback = value;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String value) {
        host = value;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int value) {
        port = value;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public void setVirtualHost(String value) {
        virtualHost = value;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String value) {
        username = value;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String value) {
        password = value;
    }

    public int getClaimBatch() {
        return claimBatch;
    }

    public void setClaimBatch(int value) {
        claimBatch = value;
    }

    public int getMaxDeliveryAttempts() {
        return maxDeliveryAttempts;
    }

    public void setMaxDeliveryAttempts(int value) {
        maxDeliveryAttempts = value;
    }

    public int getPollDelaySeconds() {
        return pollDelaySeconds;
    }

    public void setPollDelaySeconds(int value) {
        pollDelaySeconds = value;
    }

    public void validate(boolean production) {
        if (claimBatch < 1
                || claimBatch > 5
                || maxDeliveryAttempts < 1
                || maxDeliveryAttempts > 100
                || pollDelaySeconds < 1
                || pollDelaySeconds > 60) throw new IllegalStateException("Invalid realtime invalidation bounds");
        if (production && (captureEnabled || publisherEnabled))
            throw new IllegalStateException("Realtime invalidation activation is pending acceptance");
        if (!publisherEnabled) return;
        if (host == null
                || !host.matches("[A-Za-z0-9.:-]{1,253}")
                || port < 1
                || port > 65535
                || virtualHost == null
                || virtualHost.isBlank()
                || virtualHost.equals("/")
                || virtualHost.length() > 128
                || username == null
                || username.isBlank()
                || password == null
                || password.isBlank())
            throw new IllegalStateException("Private realtime invalidation broker configuration is required");
        if (allowInsecureLoopback
                && !java.util.Set.of("localhost", "127.0.0.1", "::1").contains(host))
            throw new IllegalStateException("Plaintext realtime invalidation transport is loopback only");
    }

    @Override
    public String toString() {
        return "RealtimeInvalidationProperties[redacted]";
    }
}
