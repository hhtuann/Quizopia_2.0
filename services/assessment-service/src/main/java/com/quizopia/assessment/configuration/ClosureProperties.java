package com.quizopia.assessment.configuration;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("quizopia.assessment.closure")
public record ClosureProperties(
        boolean inboxEnabled,
        boolean workerEnabled,
        boolean edgeInboxVerified,
        boolean recipientEnabled,
        @DefaultValue("50") int unitBudget,
        @DefaultValue("50") int scanLimit,
        @DefaultValue("v1") String cursorActiveKey,
        Map<String, String> cursorKeys,
        java.net.URI edgeBaseUrl) {
    public ClosureProperties(
            boolean inboxEnabled,
            boolean workerEnabled,
            boolean edgeInboxVerified,
            boolean recipientEnabled,
            int unitBudget,
            int scanLimit,
            String cursorActiveKey,
            Map<String, String> cursorKeys) {
        this(
                inboxEnabled,
                workerEnabled,
                edgeInboxVerified,
                recipientEnabled,
                unitBudget,
                scanLimit,
                cursorActiveKey,
                cursorKeys,
                null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ClosureProperties {
        if (unitBudget < 1 || unitBudget > 50 || scanLimit < 1 || scanLimit > 100)
            throw new IllegalArgumentException("Invalid closure bounds");
        cursorKeys = cursorKeys == null ? Map.of() : Map.copyOf(cursorKeys);
    }

    @Override
    public String toString() {
        return "ClosureProperties[redacted]";
    }
}
