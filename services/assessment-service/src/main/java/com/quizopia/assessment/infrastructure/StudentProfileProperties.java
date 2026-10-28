package com.quizopia.assessment.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Owner-results display-name enrichment bounds. Kept deliberately short so an Identity
 * outage can never stall the owner results page; fail-open semantics live in the client. */
@ConfigurationProperties("quizopia.student-profiles")
public class StudentProfileProperties {
    private boolean enabled;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration responseTimeout = Duration.ofSeconds(2);
    private Duration totalTimeout = Duration.ofSeconds(5);
    private int maxConnections = 4;
    private int pendingAcquires = 16;

    public void validate() {
        if (connectTimeout == null
                || responseTimeout == null
                || totalTimeout == null
                || connectTimeout.toMillis() < 1
                || responseTimeout.toMillis() < 1
                || totalTimeout.toMillis() < 1
                || totalTimeout.compareTo(Duration.ofSeconds(10)) > 0
                || connectTimeout.compareTo(totalTimeout) > 0
                || responseTimeout.compareTo(totalTimeout) > 0
                || maxConnections < 1
                || maxConnections > 64
                || pendingAcquires < 1
                || pendingAcquires > 256) throw new IllegalStateException("Invalid student profile bounds");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration value) {
        connectTimeout = value;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration value) {
        responseTimeout = value;
    }

    public Duration getTotalTimeout() {
        return totalTimeout;
    }

    public void setTotalTimeout(Duration value) {
        totalTimeout = value;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int value) {
        maxConnections = value;
    }

    public int getPendingAcquires() {
        return pendingAcquires;
    }

    public void setPendingAcquires(int value) {
        pendingAcquires = value;
    }
}
