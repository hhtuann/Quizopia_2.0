package com.quizopia.identity.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("quizopia.identity.proctoring-students")
public record ProctoringStudentProperties(
        boolean enabled,
        @DefaultValue("true") boolean requireSecureTransport,
        @DefaultValue("assessment-service") String assessmentClientId,
        @DefaultValue("8") int maxConcurrent) {
    public ProctoringStudentProperties {
        if (maxConcurrent < 1 || maxConcurrent > 64)
            throw new IllegalArgumentException("Invalid proctoring eligibility concurrency bound");
        if (assessmentClientId == null || !assessmentClientId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Exact Assessment proctoring client required");
    }
}
