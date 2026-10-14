package com.quizopia.proctoring.media;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("quizopia.proctoring.media")
public class MediaProperties {
    public enum RevocationMode {
        UNVERIFIED,
        CLOUD_STRICT
    }

    private boolean enabled, workerEnabled, allowInsecureLoopback;
    private URI apiUrl, publicUrl;
    private String apiKey = "", apiSecret = "", qualificationReference = "";
    private RevocationMode revocationMode = RevocationMode.UNVERIFIED;
    private Duration leaseDuration = Duration.ofSeconds(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean v) {
        workerEnabled = v;
    }

    public boolean isAllowInsecureLoopback() {
        return allowInsecureLoopback;
    }

    public void setAllowInsecureLoopback(boolean v) {
        allowInsecureLoopback = v;
    }

    public URI getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(URI v) {
        apiUrl = v;
    }

    public URI getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(URI v) {
        publicUrl = v;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String v) {
        apiKey = v;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String v) {
        apiSecret = v;
    }

    public String getQualificationReference() {
        return qualificationReference;
    }

    public void setQualificationReference(String v) {
        qualificationReference = v;
    }

    public RevocationMode getRevocationMode() {
        return revocationMode;
    }

    public void setRevocationMode(RevocationMode v) {
        revocationMode = v;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration v) {
        leaseDuration = v;
    }

    public boolean qualified() {
        return revocationMode == RevocationMode.CLOUD_STRICT
                && qualificationReference != null
                && !qualificationReference.isBlank();
    }

    public void validate(boolean prod) {
        if (!enabled) return;
        if (prod)
            throw new IllegalStateException(
                    "Production media remains closed pending provider and product/security acceptance");
        if (!origin(apiUrl, "https", "http")
                || !origin(publicUrl, "wss", "ws")
                || apiKey.isBlank()
                || apiSecret.length() < 32
                || leaseDuration == null
                || leaseDuration.compareTo(Duration.ofSeconds(1)) < 0
                || leaseDuration.compareTo(Duration.ofSeconds(30)) > 0
                || qualificationReference.length() > 512
                || revocationMode == null) throw new IllegalStateException("Invalid bounded media configuration");
    }

    private boolean origin(URI uri, String secure, String local) {
        return uri != null
                && uri.getHost() != null
                && uri.getUserInfo() == null
                && uri.getQuery() == null
                && uri.getFragment() == null
                && (uri.getPath().isEmpty() || uri.getPath().equals("/"))
                && (secure.equals(uri.getScheme())
                        || allowInsecureLoopback
                                && local.equals(uri.getScheme())
                                && java.util.Set.of("localhost", "127.0.0.1", "[::1]")
                                        .contains(uri.getHost()));
    }
}
