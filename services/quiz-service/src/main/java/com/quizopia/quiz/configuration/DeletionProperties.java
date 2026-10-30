package com.quizopia.quiz.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quizopia.deletion")
public class DeletionProperties {
    private boolean enabled;
    private int sagaBatchSize = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSagaBatchSize() {
        return sagaBatchSize;
    }

    public void setSagaBatchSize(int sagaBatchSize) {
        this.sagaBatchSize = sagaBatchSize;
    }
}
