package com.quizopia.quiz.configuration;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FB-015 PR C outbound deletion-coordinator transport: the Quiz Service calls Assessment and
 * Proctoring with short-lived OAuth2 client-credentials SERVICE JWTs carrying the exact
 * scopes those services admit (quiz.deletion.revoke / quiz.deletion.purge).
 */
@ConfigurationProperties("quizopia.deletion.dependencies")
public class DeletionDependencyProperties {
    private boolean enabled;
    private URI identityBaseUrl;
    private URI assessmentBaseUrl;
    private URI proctoringBaseUrl;
    private String clientId = "quiz-service";
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
                || pendingAcquires > 256) throw new IllegalStateException("Invalid deletion dependency bounds");
        if (!enabled) return;
        if (identityBaseUrl == null
                || assessmentBaseUrl == null
                || proctoringBaseUrl == null
                || !java.util.List.of(identityBaseUrl, assessmentBaseUrl, proctoringBaseUrl).stream()
                        .allMatch(this::acceptable)
                || !clientId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
                || clientSecret.isBlank())
            throw new IllegalStateException("Invalid deletion dependency destinations or service credentials");
    }

    private boolean acceptable(URI uri) {
        return uri != null
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null
                && (uri.getPath() == null
                        || uri.getPath().isEmpty()
                        || uri.getPath().equals("/"))
                && ("https".equals(uri.getScheme())
                        || (allowInsecureLoopback
                                && "http".equals(uri.getScheme())
                                && java.util.Set.of("localhost", "127.0.0.1", "[::1]")
                                        .contains(uri.getHost())));
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

    public URI getAssessmentBaseUrl() {
        return assessmentBaseUrl;
    }

    public void setAssessmentBaseUrl(URI value) {
        assessmentBaseUrl = value;
    }

    public URI getProctoringBaseUrl() {
        return proctoringBaseUrl;
    }

    public void setProctoringBaseUrl(URI value) {
        proctoringBaseUrl = value;
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
