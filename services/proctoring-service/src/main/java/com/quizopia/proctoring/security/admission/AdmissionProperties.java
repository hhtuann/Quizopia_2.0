package com.quizopia.proctoring.security.admission;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.admission")
public class AdmissionProperties {
    private boolean enabled;
    private URI identityBaseUrl;
    private String clientId = "";
    private String clientSecret = "";
    private boolean allowInsecureLoopback;
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration responseTimeout = Duration.ofSeconds(3);
    private Duration totalTimeout = Duration.ofSeconds(5);
    private int maxConnections = 8;
    private int pendingAcquires = 32;

    public void validate() {
        if (connectTimeout == null
                || responseTimeout == null
                || totalTimeout == null
                || connectTimeout.toMillis() < 1
                || responseTimeout.toMillis() < 1
                || totalTimeout.toMillis() < 1
                || totalTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || connectTimeout.compareTo(totalTimeout) > 0
                || responseTimeout.compareTo(totalTimeout) > 0
                || maxConnections < 1
                || maxConnections > 64
                || pendingAcquires < 1
                || pendingAcquires > 256) throw new IllegalStateException("Invalid admission bounds");
        if (!enabled) return;
        if (identityBaseUrl == null
                || identityBaseUrl.getHost() == null
                || identityBaseUrl.getUserInfo() != null
                || identityBaseUrl.getQuery() != null
                || identityBaseUrl.getFragment() != null
                || !(identityBaseUrl.getPath().isEmpty()
                        || identityBaseUrl.getPath().equals("/"))
                || !("https".equals(identityBaseUrl.getScheme())
                        || (allowInsecureLoopback
                                && "http".equals(identityBaseUrl.getScheme())
                                && java.util.Set.of("localhost", "127.0.0.1", "[::1]")
                                        .contains(identityBaseUrl.getHost())))
                || !clientId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
                || clientSecret.isBlank())
            throw new IllegalStateException("Invalid admission destination or service credentials");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public URI getIdentityBaseUrl() {
        return identityBaseUrl;
    }

    public void setIdentityBaseUrl(URI value) {
        identityBaseUrl = value;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String value) {
        clientId = value;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String value) {
        clientSecret = value;
    }

    public boolean isAllowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    public void setAllowInsecureLoopback(boolean value) {
        allowInsecureLoopback = value;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration value) {
        connectTimeout = value;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration value) {
        responseTimeout = value;
    }

    public Duration getTotalTimeout() {
        return totalTimeout;
    }

    public void setTotalTimeout(Duration value) {
        totalTimeout = value;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int value) {
        maxConnections = value;
    }

    public int getPendingAcquires() {
        return pendingAcquires;
    }

    public void setPendingAcquires(int value) {
        pendingAcquires = value;
    }
}
