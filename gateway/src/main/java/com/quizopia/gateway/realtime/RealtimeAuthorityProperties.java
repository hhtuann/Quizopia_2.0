package com.quizopia.gateway.realtime;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dedicated private Assessment realtime authority client; default OFF and fails closed. */
@ConfigurationProperties("quizopia.realtime.authority")
public class RealtimeAuthorityProperties {
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private boolean enabled;
    private URI assessmentBaseUrl;
    private URI identityBaseUrl;
    private String clientId = "";
    private String clientSecret = "";
    private boolean allowInsecureLoopback;
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration responseTimeout = Duration.ofSeconds(3);
    private Duration totalTimeout = Duration.ofSeconds(5);
    private int maxConnections = 8;
    private int pendingAcquires = 32;
    private int maximumSubscriptionsPerConnection = 16;

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
                || pendingAcquires > 256
                || maximumSubscriptionsPerConnection < 1
                || maximumSubscriptionsPerConnection > 64)
            throw new IllegalStateException("Invalid realtime authority bounds");
        if (!enabled) return;
        if (!validPrivateTarget(assessmentBaseUrl)
                || !validPrivateTarget(identityBaseUrl)
                || !clientId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
                || clientSecret.isBlank())
            throw new IllegalStateException("Invalid realtime authority destination or service credentials");
    }

    private boolean validPrivateTarget(URI target) {
        return target != null
                && target.getHost() != null
                && target.getUserInfo() == null
                && target.getQuery() == null
                && target.getFragment() == null
                && (target.getPath().isEmpty() || target.getPath().equals("/"))
                && ("https".equals(target.getScheme())
                        || (allowInsecureLoopback
                                && "http".equals(target.getScheme())
                                && LOOPBACK.contains(target.getHost())));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public URI getAssessmentBaseUrl() {
        return assessmentBaseUrl;
    }

    public void setAssessmentBaseUrl(URI value) {
        assessmentBaseUrl = value;
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

    public int getMaximumSubscriptionsPerConnection() {
        return maximumSubscriptionsPerConnection;
    }

    public void setMaximumSubscriptionsPerConnection(int value) {
        maximumSubscriptionsPerConnection = value;
    }
}
