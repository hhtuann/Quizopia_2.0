package com.quizopia.gateway.security.admission;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.publication")
public class PublicationProperties {
    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }
}
