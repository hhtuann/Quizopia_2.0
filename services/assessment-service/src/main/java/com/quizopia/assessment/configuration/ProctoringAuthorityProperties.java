package com.quizopia.assessment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Narrow private capability; an enabled flag never replaces current authority. */
@ConfigurationProperties("quizopia.assessment.proctoring-authority")
public record ProctoringAuthorityProperties(boolean enabled, String clientId, boolean allowInsecureLoopback) {
    public ProctoringAuthorityProperties {
        if (enabled && (clientId == null || !clientId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")))
            throw new IllegalStateException("Exact Proctoring client identity is required");
    }
}
