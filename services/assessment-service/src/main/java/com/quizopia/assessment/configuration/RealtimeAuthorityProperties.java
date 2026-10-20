package com.quizopia.assessment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Narrow private capability; an enabled flag never replaces current authority. */
@ConfigurationProperties("quizopia.assessment.realtime-authority")
public record RealtimeAuthorityProperties(boolean enabled, String gatewayClientId, boolean allowInsecureLoopback) {

    public RealtimeAuthorityProperties {
        if (enabled && (gatewayClientId == null || !gatewayClientId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")))
            throw new IllegalStateException("Exact realtime Gateway client identity is required");
    }
}
