package com.quizopia.identity.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("quizopia.identity.user-profiles")
public record UserProfileProperties(
        boolean enabled,
        @DefaultValue("true") boolean requireSecureTransport,
        @DefaultValue("assessment-service") String assessmentClientId,
        @DefaultValue("8") int maxConcurrent,
        @DefaultValue("60") int requestsPerMinute) {
    public UserProfileProperties {
        if (maxConcurrent < 1 || maxConcurrent > 64)
            throw new IllegalArgumentException("Invalid profile batch concurrency bound");
        if (requestsPerMinute < 1 || requestsPerMinute > 6000)
            throw new IllegalArgumentException("Invalid profile batch request rate bound");
        if (assessmentClientId == null || !assessmentClientId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Exact Assessment profile client required");
    }
}
