package com.quizopia.proctoring.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.proctoring.assessment-events")
public class AssessmentEventsProperties {
    private boolean enabled, tls = true, allowInsecureLoopback;
    private String host = "", virtualHost = "", username = "", password = "", publisherIdentity = "";
    private int port = 5671;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isTls() {
        return tls;
    }

    public void setTls(boolean v) {
        tls = v;
    }

    public boolean isAllowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    public void setAllowInsecureLoopback(boolean v) {
        allowInsecureLoopback = v;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String v) {
        host = v;
    }

    public String getVirtualHost() {
        return virtualHost;
    }

    public void setVirtualHost(String v) {
        virtualHost = v;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String v) {
        username = v;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String v) {
        password = v;
    }

    public String getPublisherIdentity() {
        return publisherIdentity;
    }

    public void setPublisherIdentity(String v) {
        publisherIdentity = v;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int v) {
        port = v;
    }

    public void validate(boolean prod) {
        if (!enabled) return;
        if (prod)
            throw new IllegalStateException(
                    "Production monitoring event consumption remains closed pending policy/security acceptance");
        if (host == null
                || !host.matches("[A-Za-z0-9.:-]{1,253}")
                || port < 1
                || port > 65535
                || virtualHost == null
                || virtualHost.isBlank()
                || virtualHost.equals("/")
                || virtualHost.length() > 128
                || publisherIdentity == null
                || publisherIdentity.isBlank()
                || publisherIdentity.length() > 128
                || username == null
                || username.isBlank()
                || username.length() > 128
                || password == null
                || password.isBlank()
                || password.length() > 1024
                || !tls
                        && !(allowInsecureLoopback
                                && java.util.Set.of("localhost", "127.0.0.1", "::1")
                                        .contains(host)))
            throw new IllegalStateException("Bounded private monitoring broker configuration required");
    }
}
