package com.quizopia.assessment.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded engineering budgets; statement timeout stays below the five-second JDBC read limit. */
@ConfigurationProperties("quizopia.assessment.transactions")
public record AssessmentTransactionProperties(
        @DefaultValue("15") int timeoutSeconds,
        @DefaultValue("3000") int statementMillis,
        @DefaultValue("3000") int lockMillis) {
    public AssessmentTransactionProperties {
        if (timeoutSeconds < 1
                || timeoutSeconds > 30
                || statementMillis < 1
                || statementMillis > 4000
                || lockMillis < 1
                || lockMillis > statementMillis)
            throw new IllegalArgumentException("Invalid assessment transaction limits");
    }

    public static AssessmentTransactionProperties defaults() {
        return new AssessmentTransactionProperties(15, 3000, 3000);
    }
}
