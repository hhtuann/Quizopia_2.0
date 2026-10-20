package com.quizopia.gateway.realtime.invalidation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-instance best-effort invalidation consumer; default OFF and independent of authority gating. */
@ConfigurationProperties("quizopia.realtime.invalidation")
public class RealtimeInvalidationProperties {
    private boolean enabled;
    private boolean allowInsecureLoopback;
    private String host = "";
    private int port = 5671;
    private String virtualHost = "";
    private String username = "";
    private String password = "";
    private String queuePrefix = "quizopia.gateway.realtime-invalidation";
    private int prefetch = 64;
    private int deduplicationCapacity = 4096;
    private int maximumRegistryEntries = 8192;

    public void validate(boolean production) {
        if (prefetch < 1
                || prefetch > 1024
                || deduplicationCapacity < 64
                || deduplicationCapacity > 65536
                || maximumRegistryEntries < 16
                || maximumRegistryEntries > 262144
                || queuePrefix == null
                || !queuePrefix.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,180}"))
            throw new IllegalStateException("Invalid realtime invalidation bounds");
        if (production && enabled)
            throw new IllegalStateException("Realtime invalidation activation is pending acceptance");
        if (!enabled) return;
        if (host == null
                || !host.matches("[A-Za-z0-9.:-]{1,253}")
                || port < 1
                || port > 65535
                || virtualHost == null
                || virtualHost.isBlank()
                || virtualHost.equals("/")
                || virtualHost.length() > 128
                || username == null
                || username.isBlank()
                || password == null
                || password.isBlank())
            throw new IllegalStateException("Private realtime invalidation broker configuration is required");
        if (allowInsecureLoopback
                && !java.util.Set.of("localhost", "127.0.0.1", "::1").contains(host))
            throw new IllegalStateException("Plaintext realtime invalidation transport is loopback only");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public boolean isAllowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    public void setAllowInsecureLoopback(boolean value) {
        allowInsecureLoopback = value;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String value) {
        host = value;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int value) {
        port = value;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public void setVirtualHost(String value) {
        virtualHost = value;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String value) {
        username = value;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String value) {
        password = value;
    }

    public String getQueuePrefix() {
        return queuePrefix;
    }

    public void setQueuePrefix(String value) {
        queuePrefix = value;
    }

    public int getPrefetch() {
        return prefetch;
    }

    public void setPrefetch(int value) {
        prefetch = value;
    }

    public int getDeduplicationCapacity() {
        return deduplicationCapacity;
    }

    public void setDeduplicationCapacity(int value) {
        deduplicationCapacity = value;
    }

    public int getMaximumRegistryEntries() {
        return maximumRegistryEntries;
    }

    public void setMaximumRegistryEntries(int value) {
        maximumRegistryEntries = value;
    }
}
