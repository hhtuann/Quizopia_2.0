package com.quizopia.identity.configuration;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quizopia.identity.security.authorization-server")
public class AuthorizationServerProperties {
    private boolean enabled;
    private String issuer;
    private Duration serviceAccessTokenTtl;
    private Duration userAccessTokenTtl;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getServiceAccessTokenTtl() {
        return serviceAccessTokenTtl;
    }

    public void setServiceAccessTokenTtl(Duration serviceAccessTokenTtl) {
        this.serviceAccessTokenTtl = serviceAccessTokenTtl;
    }

    public Duration getUserAccessTokenTtl() {
        return userAccessTokenTtl;
    }

    public void setUserAccessTokenTtl(Duration userAccessTokenTtl) {
        this.userAccessTokenTtl = userAccessTokenTtl;
    }

    public void validateRequiredValues() {
        if (!enabled) {
            return;
        }
        String configuredIssuer = issuer == null ? null : issuer.trim();
        if (configuredIssuer == null || configuredIssuer.isEmpty()) {
            throw new IllegalStateException("Authorization Server issuer is required when token issuance is enabled");
        }
        try {
            if (!URI.create(configuredIssuer).isAbsolute()) {
                throw new IllegalStateException("Authorization Server issuer must be an absolute URI");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Authorization Server issuer must be a valid absolute URI", exception);
        }
        if (serviceAccessTokenTtl == null || serviceAccessTokenTtl.isZero() || serviceAccessTokenTtl.isNegative()) {
            throw new IllegalStateException(
                    "A positive service access-token TTL is required when token issuance is enabled");
        }
        if (userAccessTokenTtl != null && (userAccessTokenTtl.isZero() || userAccessTokenTtl.isNegative())) {
            throw new IllegalStateException("User access-token TTL must be positive when configured");
        }
    }

    public String requiredIssuer() {
        validateRequiredValues();
        return issuer.trim();
    }

    public Duration requiredServiceAccessTokenTtl() {
        validateRequiredValues();
        return serviceAccessTokenTtl;
    }

    public Duration requiredUserAccessTokenTtl() {
        validateRequiredValues();
        return userAccessTokenTtl == null ? serviceAccessTokenTtl : userAccessTokenTtl;
    }
}
