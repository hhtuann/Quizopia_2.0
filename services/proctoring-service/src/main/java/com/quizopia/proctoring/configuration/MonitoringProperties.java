package com.quizopia.proctoring.configuration;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.proctoring.monitoring")
public class MonitoringProperties {
    private boolean enabled;
    private URI assessmentBaseUrl;
    private Integer retentionDays;
    private String retentionPolicyReference = "";
    private String privacyApprovalReference = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean x) {
        enabled = x;
    }

    public URI getAssessmentBaseUrl() {
        return assessmentBaseUrl;
    }

    public void setAssessmentBaseUrl(URI x) {
        assessmentBaseUrl = x;
    }

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer x) {
        retentionDays = x;
    }

    public String getRetentionPolicyReference() {
        return retentionPolicyReference;
    }

    public void setRetentionPolicyReference(String x) {
        retentionPolicyReference = x;
    }

    public String getPrivacyApprovalReference() {
        return privacyApprovalReference;
    }

    public void setPrivacyApprovalReference(String x) {
        privacyApprovalReference = x;
    }

    public void validate(boolean prod, boolean loopback) {
        if (retentionDays != null
                && (retentionDays < 1
                        || retentionDays > 36500
                        || retentionPolicyReference.isBlank()
                        || retentionPolicyReference.length() > 512))
            throw new IllegalStateException("Approved monitoring retention required");
        if (!enabled) return;
        if (assessmentBaseUrl == null
                || assessmentBaseUrl.getHost() == null
                || assessmentBaseUrl.getUserInfo() != null
                || assessmentBaseUrl.getQuery() != null
                || assessmentBaseUrl.getFragment() != null
                || !(assessmentBaseUrl.getPath().isEmpty()
                        || assessmentBaseUrl.getPath().equals("/"))
                || !("https".equals(assessmentBaseUrl.getScheme())
                        || (!prod
                                && loopback
                                && "http".equals(assessmentBaseUrl.getScheme())
                                && java.util.Set.of("localhost", "127.0.0.1", "[::1]")
                                        .contains(assessmentBaseUrl.getHost()))))
            throw new IllegalStateException("Private Assessment HTTPS origin required");
        // M1 alone is never an approved Activity-only production mode.
        if (prod)
            throw new IllegalStateException(
                    "Production monitoring remains closed until actual M2 integration and policy acceptance");
    }
}
