package com.quizopia.assessment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * FB-015 PR B/C deployment switch. Enforcement itself is table-driven and always compiled: the flag
 * gates only the private command endpoints and background acknowledgment, never route checks.
 */
@ConfigurationProperties("quizopia.deletion")
public record AssessmentDeletionProperties(
        boolean enabled,
        @DefaultValue("quiz-service") String quizServiceClientId,
        @DefaultValue("false") boolean allowInsecureLoopback,
        @DefaultValue("8") int maxConcurrent,
        @DefaultValue("30") int requestsPerMinute,
        @DefaultValue("10") int ackPollDelaySeconds,
        @DefaultValue("20") int ackBatchSize,
        @DefaultValue("5000") int purgeBudget) {
    public AssessmentDeletionProperties {
        if (quizServiceClientId == null || !quizServiceClientId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}"))
            throw new IllegalStateException("Exact Quiz deletion client identity is required");
        if (maxConcurrent < 1 || maxConcurrent > 64)
            throw new IllegalStateException("Invalid revocation concurrency bound");
        if (requestsPerMinute < 1 || requestsPerMinute > 6000)
            throw new IllegalStateException("Invalid revocation rate bound");
        if (ackPollDelaySeconds < 1 || ackPollDelaySeconds > 3600 || ackBatchSize < 1 || ackBatchSize > 100)
            throw new IllegalStateException("Invalid acknowledgment polling bounds");
        if (purgeBudget < 100 || purgeBudget > 50000) throw new IllegalStateException("Invalid purge budget bound");
    }
}
