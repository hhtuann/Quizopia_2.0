package com.quizopia.gateway.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.realtime")
public class RealtimeProperties {
    private boolean enabled;
    private Duration ticketTtl = Duration.ofSeconds(30);
    private Duration authTimeout = Duration.ofSeconds(5);
    private int maximumFrameBytes = 17_408;
    private int maximumOutstandingTickets = 10_000;
    private int ticketIssueRatePerActor = 20;
    private int ticketIssueRatePerOrigin = 200;
    private int upgradeRatePerIp = 120;
    private int maximumConnections = 512;
    private int maximumConnectionsPerIp = 64;
    private int maximumConnectionsPerOrigin = 128;

    public void validate() {
        if (ticketTtl == null
                || ticketTtl.compareTo(Duration.ofSeconds(1)) < 0
                || ticketTtl.compareTo(Duration.ofSeconds(60)) > 0
                || authTimeout == null
                || authTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || authTimeout.compareTo(Duration.ofSeconds(15)) > 0
                || maximumFrameBytes < 512
                || maximumFrameBytes > 65_536
                || maximumOutstandingTickets < 1
                || maximumOutstandingTickets > 1_000_000
                || ticketIssueRatePerActor < 1
                || ticketIssueRatePerActor > 10_000
                || ticketIssueRatePerOrigin < 1
                || ticketIssueRatePerOrigin > 100_000
                || upgradeRatePerIp < 1
                || upgradeRatePerIp > 100_000
                || maximumConnections < 1
                || maximumConnections > 100_000
                || maximumConnectionsPerIp < 1
                || maximumConnectionsPerIp > maximumConnections
                || maximumConnectionsPerOrigin < 1
                || maximumConnectionsPerOrigin > maximumConnections) {
            throw new IllegalStateException("Invalid realtime security bounds");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getTicketTtl() {
        return ticketTtl;
    }

    public void setTicketTtl(Duration ticketTtl) {
        this.ticketTtl = ticketTtl;
    }

    public Duration getAuthTimeout() {
        return authTimeout;
    }

    public void setAuthTimeout(Duration authTimeout) {
        this.authTimeout = authTimeout;
    }

    public int getMaximumFrameBytes() {
        return maximumFrameBytes;
    }

    public void setMaximumFrameBytes(int maximumFrameBytes) {
        this.maximumFrameBytes = maximumFrameBytes;
    }

    public int getMaximumOutstandingTickets() {
        return maximumOutstandingTickets;
    }

    public void setMaximumOutstandingTickets(int maximumOutstandingTickets) {
        this.maximumOutstandingTickets = maximumOutstandingTickets;
    }

    public int getTicketIssueRatePerActor() {
        return ticketIssueRatePerActor;
    }

    public void setTicketIssueRatePerActor(int ticketIssueRatePerActor) {
        this.ticketIssueRatePerActor = ticketIssueRatePerActor;
    }

    public int getTicketIssueRatePerOrigin() {
        return ticketIssueRatePerOrigin;
    }

    public void setTicketIssueRatePerOrigin(int ticketIssueRatePerOrigin) {
        this.ticketIssueRatePerOrigin = ticketIssueRatePerOrigin;
    }

    public int getUpgradeRatePerIp() {
        return upgradeRatePerIp;
    }

    public void setUpgradeRatePerIp(int upgradeRatePerIp) {
        this.upgradeRatePerIp = upgradeRatePerIp;
    }

    public int getMaximumConnections() {
        return maximumConnections;
    }

    public void setMaximumConnections(int maximumConnections) {
        this.maximumConnections = maximumConnections;
    }

    public int getMaximumConnectionsPerIp() {
        return maximumConnectionsPerIp;
    }

    public void setMaximumConnectionsPerIp(int maximumConnectionsPerIp) {
        this.maximumConnectionsPerIp = maximumConnectionsPerIp;
    }

    public int getMaximumConnectionsPerOrigin() {
        return maximumConnectionsPerOrigin;
    }

    public void setMaximumConnectionsPerOrigin(int maximumConnectionsPerOrigin) {
        this.maximumConnectionsPerOrigin = maximumConnectionsPerOrigin;
    }
}
