package com.quizopia.identity.application.serviceclient;

import com.quizopia.identity.security.client.RawServiceClientSecret;
import java.util.Objects;
import java.util.UUID;

public final class ServiceClientRegistration {
    private final UUID serviceClientId;
    private final String clientId;
    private final RawServiceClientSecret clientSecret;

    public ServiceClientRegistration(UUID serviceClientId, String clientId, RawServiceClientSecret clientSecret) {
        this.serviceClientId = Objects.requireNonNull(serviceClientId, "serviceClientId");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
    }

    public UUID serviceClientId() {
        return serviceClientId;
    }

    public String clientId() {
        return clientId;
    }

    public RawServiceClientSecret clientSecret() {
        return clientSecret;
    }

    @Override
    public String toString() {
        return "ServiceClientRegistration{serviceClientId=" + serviceClientId + ", clientId='" + clientId
                + "', clientSecretPresent=true}";
    }
}
