package com.quizopia.identity.application.serviceclient;

import com.quizopia.identity.persistence.entity.OAuth2ServiceClientEntity;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ServiceClientDescriptor {
    private final UUID serviceClientId;
    private final String clientId;
    private final String encodedClientSecret;
    private final boolean enabled;
    private final Set<String> scopes;
    private final Instant createdAt;
    private final Instant updatedAt;

    private ServiceClientDescriptor(
            UUID serviceClientId,
            String clientId,
            String encodedClientSecret,
            boolean enabled,
            Set<String> scopes,
            Instant createdAt,
            Instant updatedAt) {
        this.serviceClientId = Objects.requireNonNull(serviceClientId, "serviceClientId");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.encodedClientSecret = Objects.requireNonNull(encodedClientSecret, "encodedClientSecret");
        this.enabled = enabled;
        this.scopes = Collections.unmodifiableSet(new LinkedHashSet<>(scopes));
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static ServiceClientDescriptor from(OAuth2ServiceClientEntity entity) {
        return new ServiceClientDescriptor(
                entity.getId(),
                entity.getClientId(),
                entity.getClientSecretHash(),
                entity.isEnabled(),
                entity.getScopes(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public UUID serviceClientId() {
        return serviceClientId;
    }

    public String clientId() {
        return clientId;
    }

    public String encodedClientSecret() {
        return encodedClientSecret;
    }

    public boolean enabled() {
        return enabled;
    }

    public Set<String> scopes() {
        return scopes;
    }

    public ServiceClientAuthenticationMethod authenticationMethod() {
        return ServiceClientAuthenticationMethod.CLIENT_SECRET_BASIC;
    }

    public ServiceClientGrantType grantType() {
        return ServiceClientGrantType.CLIENT_CREDENTIALS;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "ServiceClientDescriptor{serviceClientId=" + serviceClientId + ", clientId='" + clientId + "', enabled="
                + enabled + ", scopes=" + scopes + ", encodedClientSecretPresent=true}";
    }
}
