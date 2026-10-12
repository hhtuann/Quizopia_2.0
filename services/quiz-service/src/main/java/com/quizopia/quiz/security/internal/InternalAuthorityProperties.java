package com.quizopia.quiz.security.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.internal-authority")
public class InternalAuthorityProperties {
    private boolean enabled;
    private boolean requireTls = true;
    private String assessmentClientId = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isRequireTls() {
        return requireTls;
    }

    public void setRequireTls(boolean value) {
        requireTls = value;
    }

    public String getAssessmentClientId() {
        return assessmentClientId;
    }

    public void setAssessmentClientId(String value) {
        assessmentClientId = value;
    }
}
