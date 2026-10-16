package com.quizopia.assessment.monitoring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.assessment.monitoring-events")
public class MonitoringPublisherProperties {
    private boolean captureEnabled;
    private boolean publisherEnabled;
    private boolean allowInsecureLoopback;
    private String host = "";
    private int port = 5671;
    private String virtualHost = "";
    private String username = "";
    private String password = "";

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

    public void validate(boolean production) {
        if (production && (captureEnabled || publisherEnabled))
            throw new IllegalStateException("Monitoring event privacy acceptance is pending");
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
            throw new IllegalStateException("Private monitoring broker configuration is required");
        if (allowInsecureLoopback
                && !java.util.Set.of("localhost", "127.0.0.1", "::1").contains(host))
            throw new IllegalStateException("Plaintext monitoring transport is loopback only");
    }

    @Override
    public String toString() {
        return "MonitoringPublisherProperties[redacted]";
    }
}
