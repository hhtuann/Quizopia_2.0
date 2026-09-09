package com.quizopia.identity.security.authorization;

import com.quizopia.identity.persistence.entity.OAuth2ServiceClientEntity;
import com.quizopia.identity.persistence.repository.OAuth2ServiceClientRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

public final class IdentityRegisteredClientRepository implements RegisteredClientRepository {
    private final OAuth2ServiceClientRepository serviceClientRepository;
    private final TokenSettings tokenSettings;
    private final PasswordEncoder passwordEncoder;

    public IdentityRegisteredClientRepository(
            OAuth2ServiceClientRepository serviceClientRepository,
            TokenSettings tokenSettings,
            PasswordEncoder passwordEncoder) {
        this.serviceClientRepository = Objects.requireNonNull(serviceClientRepository, "serviceClientRepository");
        this.tokenSettings = Objects.requireNonNull(tokenSettings, "tokenSettings");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        Objects.requireNonNull(registeredClient, "registeredClient");
        UUID serviceClientId = requiredUuid(registeredClient.getId());
        OAuth2ServiceClientEntity existingEntity = serviceClientRepository
                .findById(serviceClientId)
                .filter(OAuth2ServiceClientEntity::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Only an enabled existing service client may use the secret-upgrade path"));
        RegisteredClient existingClient = toRegisteredClient(existingEntity);

        if (!sameRegistrationExceptSecret(existingClient, registeredClient)) {
            throw new UnsupportedOperationException(
                    "RegisteredClient save only supports an existing client-secret encoding upgrade");
        }

        String upgradedSecret = registeredClient.getClientSecret();
        if (upgradedSecret == null
                || upgradedSecret.isBlank()
                || Objects.equals(existingClient.getClientSecret(), upgradedSecret)) {
            throw new UnsupportedOperationException(
                    "RegisteredClient save only supports an existing client-secret encoding upgrade");
        }
        if (!isDelegatingEncodedSecret(upgradedSecret)
                || !requiresEncodingUpgrade(existingClient.getClientSecret())
                || requiresEncodingUpgrade(upgradedSecret)) {
            throw new UnsupportedOperationException("RegisteredClient save only supports a password-encoder upgrade");
        }

        if (serviceClientRepository.updateClientSecretHash(
                        serviceClientId, existingClient.getClientSecret(), upgradedSecret)
                != 1) {
            OAuth2ServiceClientEntity currentEntity = serviceClientRepository
                    .findById(serviceClientId)
                    .filter(OAuth2ServiceClientEntity::isEnabled)
                    .orElseThrow(
                            () -> new IllegalStateException("Service client secret upgrade lost an enabled client"));
            if (requiresEncodingUpgrade(currentEntity.getClientSecretHash())) {
                throw new IllegalStateException("Service client secret upgrade was stale");
            }
        }
    }

    @Override
    public RegisteredClient findById(String id) {
        if (id == null) {
            return null;
        }
        UUID serviceClientId;
        try {
            serviceClientId = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return serviceClientRepository
                .findById(serviceClientId)
                .filter(OAuth2ServiceClientEntity::isEnabled)
                .map(this::toRegisteredClient)
                .orElse(null);
    }

    private static UUID requiredUuid(String id) {
        if (id == null) {
            throw new IllegalArgumentException("RegisteredClient id is required");
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "RegisteredClient id must be the persisted service-client UUID", exception);
        }
    }

    private boolean requiresEncodingUpgrade(String encodedSecret) {
        try {
            return passwordEncoder.upgradeEncoding(encodedSecret);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("RegisteredClient secret is not a valid encoded secret", exception);
        }
    }

    private static boolean isDelegatingEncodedSecret(String encodedSecret) {
        int delimiter = encodedSecret.indexOf('}');
        return encodedSecret.startsWith("{") && delimiter > 1 && delimiter < encodedSecret.length() - 1;
    }

    private static boolean sameRegistrationExceptSecret(RegisteredClient expected, RegisteredClient actual) {
        return Objects.equals(expected.getId(), actual.getId())
                && Objects.equals(expected.getClientId(), actual.getClientId())
                && Objects.equals(expected.getClientIdIssuedAt(), actual.getClientIdIssuedAt())
                && Objects.equals(expected.getClientSecretExpiresAt(), actual.getClientSecretExpiresAt())
                && Objects.equals(expected.getClientName(), actual.getClientName())
                && Objects.equals(expected.getClientAuthenticationMethods(), actual.getClientAuthenticationMethods())
                && Objects.equals(expected.getAuthorizationGrantTypes(), actual.getAuthorizationGrantTypes())
                && Objects.equals(expected.getRedirectUris(), actual.getRedirectUris())
                && Objects.equals(expected.getPostLogoutRedirectUris(), actual.getPostLogoutRedirectUris())
                && Objects.equals(expected.getScopes(), actual.getScopes())
                && Objects.equals(expected.getClientSettings(), actual.getClientSettings())
                && Objects.equals(expected.getTokenSettings(), actual.getTokenSettings());
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        if (clientId == null) {
            return null;
        }
        return serviceClientRepository
                .findByClientId(clientId)
                .filter(OAuth2ServiceClientEntity::isEnabled)
                .map(this::toRegisteredClient)
                .orElse(null);
    }

    private RegisteredClient toRegisteredClient(OAuth2ServiceClientEntity entity) {
        RegisteredClient.Builder builder = RegisteredClient.withId(
                        entity.getId().toString())
                .clientId(entity.getClientId())
                .clientSecret(entity.getClientSecretHash())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenSettings(tokenSettings);
        if (entity.getCreatedAt() != null) {
            builder.clientIdIssuedAt(entity.getCreatedAt());
        }
        entity.getScopes().forEach(builder::scope);
        return builder.build();
    }
}
