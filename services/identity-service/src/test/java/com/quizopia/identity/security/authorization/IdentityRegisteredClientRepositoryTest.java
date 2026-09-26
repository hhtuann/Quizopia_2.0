package com.quizopia.identity.security.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.quizopia.identity.persistence.entity.OAuth2ServiceClientEntity;
import com.quizopia.identity.persistence.repository.OAuth2ServiceClientRepository;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

class IdentityRegisteredClientRepositoryTest {
    private static final Duration TOKEN_TTL = Duration.ofSeconds(45);

    private OAuth2ServiceClientRepository repository;
    private PasswordEncoder passwordEncoder;
    private TokenSettings tokenSettings;
    private IdentityRegisteredClientRepository registeredClientRepository;

    @BeforeEach
    void setUp() {
        repository = mock(OAuth2ServiceClientRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenSettings = TokenSettings.builder()
                .accessTokenTimeToLive(TOKEN_TTL)
                .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                .build();
        registeredClientRepository = new IdentityRegisteredClientRepository(repository, tokenSettings, passwordEncoder);
    }

    @Test
    void mapsEnabledServiceClientToClientCredentialsRegisteredClient() {
        UUID serviceClientId = UUID.randomUUID();
        OAuth2ServiceClientEntity entity = serviceClient(serviceClientId, true);
        when(repository.findByClientId("assessment-service")).thenReturn(java.util.Optional.of(entity));

        RegisteredClient registeredClient = registeredClientRepository.findByClientId("assessment-service");

        assertEquals(serviceClientId.toString(), registeredClient.getId());
        assertEquals("assessment-service", registeredClient.getClientId());
        assertEquals("{bcrypt}encoded-secret", registeredClient.getClientSecret());
        assertEquals(
                Set.of(ClientAuthenticationMethod.CLIENT_SECRET_BASIC),
                registeredClient.getClientAuthenticationMethods());
        assertEquals(Set.of(AuthorizationGrantType.CLIENT_CREDENTIALS), registeredClient.getAuthorizationGrantTypes());
        assertEquals(Set.of("test.read", "test.write"), registeredClient.getScopes());
        assertTrue(registeredClient.getRedirectUris().isEmpty());
        assertFalse(registeredClient.getAuthorizationGrantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE));
        assertFalse(registeredClient.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN));
        assertEquals(TOKEN_TTL, registeredClient.getTokenSettings().getAccessTokenTimeToLive());
        assertEquals(
                OAuth2TokenFormat.SELF_CONTAINED,
                registeredClient.getTokenSettings().getAccessTokenFormat());
    }

    @Test
    void findByIdUsesStablePersistedUuid() {
        UUID serviceClientId = UUID.randomUUID();
        OAuth2ServiceClientEntity entity = serviceClient(serviceClientId, true);
        when(repository.findById(serviceClientId)).thenReturn(java.util.Optional.of(entity));

        RegisteredClient registeredClient = registeredClientRepository.findById(serviceClientId.toString());

        assertEquals(serviceClientId.toString(), registeredClient.getId());
        assertEquals(Set.of("test.read", "test.write"), registeredClient.getScopes());
    }

    @Test
    void disabledClientsAreNotExposedByEitherLookup() {
        UUID serviceClientId = UUID.randomUUID();
        OAuth2ServiceClientEntity entity = serviceClient(serviceClientId, false);
        when(repository.findByClientId("assessment-service")).thenReturn(java.util.Optional.of(entity));
        when(repository.findById(serviceClientId)).thenReturn(java.util.Optional.of(entity));

        assertNull(registeredClientRepository.findByClientId("assessment-service"));
        assertNull(registeredClientRepository.findById(serviceClientId.toString()));
    }

    @Test
    void malformedIdsAreRejectedSafely() {
        assertNull(registeredClientRepository.findById("not-a-uuid"));
    }

    @Test
    void upgradesOnlyTheEncodedSecretForAnExistingClient() {
        UUID serviceClientId = UUID.randomUUID();
        OAuth2ServiceClientEntity entity = serviceClient(serviceClientId, true, "{old}encoded-secret");
        RegisteredClient existing = mappedClient(entity);
        RegisteredClient upgraded = RegisteredClient.from(existing)
                .clientSecret("{new}encoded-secret")
                .build();
        when(repository.findById(serviceClientId)).thenReturn(java.util.Optional.of(entity));
        when(passwordEncoder.upgradeEncoding("{old}encoded-secret")).thenReturn(true);
        when(passwordEncoder.upgradeEncoding("{new}encoded-secret")).thenReturn(false);
        when(repository.updateClientSecretHash(serviceClientId, "{old}encoded-secret", "{new}encoded-secret"))
                .thenReturn(1);

        registeredClientRepository.save(upgraded);

        verify(repository).updateClientSecretHash(serviceClientId, "{old}encoded-secret", "{new}encoded-secret");
    }

    @Test
    void treatsAConcurrentCompletedUpgradeAsSafeNoOp() {
        UUID serviceClientId = UUID.randomUUID();
        OAuth2ServiceClientEntity staleEntity = serviceClient(serviceClientId, true, "{old}encoded-secret");
        OAuth2ServiceClientEntity currentEntity = serviceClient(serviceClientId, true, "{current}encoded-secret");
        RegisteredClient upgraded = RegisteredClient.from(mappedClient(staleEntity))
                .clientSecret("{new}encoded-secret")
                .build();
        when(repository.findById(serviceClientId))
                .thenReturn(java.util.Optional.of(staleEntity), java.util.Optional.of(currentEntity));
        when(passwordEncoder.upgradeEncoding("{old}encoded-secret")).thenReturn(true);
        when(passwordEncoder.upgradeEncoding("{new}encoded-secret")).thenReturn(false);
        when(passwordEncoder.upgradeEncoding("{current}encoded-secret")).thenReturn(false);
        when(repository.updateClientSecretHash(serviceClientId, "{old}encoded-secret", "{new}encoded-secret"))
                .thenReturn(0);

        registeredClientRepository.save(upgraded);

        verify(repository).updateClientSecretHash(serviceClientId, "{old}encoded-secret", "{new}encoded-secret");
    }

    @Test
    void rejectsUnknownRegistrationId() {
        UUID serviceClientId = UUID.randomUUID();
        when(repository.findById(serviceClientId)).thenReturn(java.util.Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> registeredClientRepository.save(
                        client(serviceClientId, "assessment-service", Set.of("test.read"))));
        verify(repository, never())
                .updateClientSecretHash(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsChangingClientId() {
        UUID serviceClientId = UUID.randomUUID();
        OAuth2ServiceClientEntity entity = serviceClient(serviceClientId, true, "{old}encoded-secret");
        when(repository.findById(serviceClientId)).thenReturn(java.util.Optional.of(entity));

        RegisteredClient mutated = RegisteredClient.from(mappedClient(entity))
                .clientId("different-client")
                .clientSecret("{new}encoded-secret")
                .build();

        assertSaveRejected(mutated);
    }

    @Test
    void rejectsAddingOrChangingScopes() {
        UUID serviceClientId = UUID.randomUUID();
        OAuth2ServiceClientEntity entity = serviceClient(serviceClientId, true, "{old}encoded-secret");
        RegisteredClient existing = mappedClient(entity);
        when(repository.findById(serviceClientId)).thenReturn(java.util.Optional.of(entity));

        assertSaveRejected(RegisteredClient.from(existing)
                .scope("extra.scope")
                .clientSecret("{new}encoded-secret")
                .build());
        assertSaveRejected(client(serviceClientId, "assessment-service", Set.of("test.read")));
    }

    @Test
    void rejectsAddingGrantType() {
        UUID serviceClientId = UUID.randomUUID();
        OAuth2ServiceClientEntity entity = serviceClient(serviceClientId, true, "{old}encoded-secret");
        RegisteredClient existing = mappedClient(entity);
        when(repository.findById(serviceClientId)).thenReturn(java.util.Optional.of(entity));

        RegisteredClient mutated = RegisteredClient.from(existing)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://identity.test/callback")
                .clientSecret("{new}encoded-secret")
                .build();

        assertSaveRejected(mutated);
    }

    @Test
    void rejectsAddingClientAuthenticationMethod() {
        UUID serviceClientId = UUID.randomUUID();
        OAuth2ServiceClientEntity entity = serviceClient(serviceClientId, true, "{old}encoded-secret");
        RegisteredClient existing = mappedClient(entity);
        when(repository.findById(serviceClientId)).thenReturn(java.util.Optional.of(entity));

        RegisteredClient mutated = RegisteredClient.from(existing)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .clientSecret("{new}encoded-secret")
                .build();

        assertSaveRejected(mutated);
    }

    @Test
    void rejectsUnrelatedRegisteredClientMetadata() {
        UUID serviceClientId = UUID.randomUUID();
        OAuth2ServiceClientEntity entity = serviceClient(serviceClientId, true, "{old}encoded-secret");
        RegisteredClient existing = mappedClient(entity);
        when(repository.findById(serviceClientId)).thenReturn(java.util.Optional.of(entity));

        RegisteredClient mutated = RegisteredClient.from(existing)
                .clientName("different-name")
                .clientSecret("{new}encoded-secret")
                .build();

        assertSaveRejected(mutated);
    }

    @Test
    void rejectsNewClientRegistrationThroughSave() {
        UUID serviceClientId = UUID.randomUUID();
        when(repository.findById(serviceClientId)).thenReturn(java.util.Optional.empty());

        assertThrows(
                IllegalArgumentException.class,
                () -> registeredClientRepository.save(client(serviceClientId, "new-client", Set.of("test.read"))));
        verify(repository, never())
                .updateClientSecretHash(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private void assertSaveRejected(RegisteredClient registeredClient) {
        assertThrows(UnsupportedOperationException.class, () -> registeredClientRepository.save(registeredClient));
        verify(repository, never())
                .updateClientSecretHash(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static RegisteredClient mappedClient(OAuth2ServiceClientEntity entity) {
        return RegisteredClient.withId(entity.getId().toString())
                .clientId(entity.getClientId())
                .clientSecret(entity.getClientSecretHash())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("test.read")
                .scope("test.write")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(TOKEN_TTL)
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .build())
                .build();
    }

    private static RegisteredClient client(UUID id, String clientId, Set<String> scopes) {
        RegisteredClient.Builder builder = RegisteredClient.withId(id.toString())
                .clientId(clientId)
                .clientSecret("{new}encoded-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(TOKEN_TTL)
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .build());
        scopes.forEach(builder::scope);
        return builder.build();
    }

    private static OAuth2ServiceClientEntity serviceClient(UUID id, boolean enabled) {
        return serviceClient(id, enabled, "{bcrypt}encoded-secret");
    }

    private static OAuth2ServiceClientEntity serviceClient(UUID id, boolean enabled, String secretHash) {
        OAuth2ServiceClientEntity entity = mock(OAuth2ServiceClientEntity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.getClientId()).thenReturn("assessment-service");
        when(entity.getClientSecretHash()).thenReturn(secretHash);
        when(entity.isEnabled()).thenReturn(enabled);
        when(entity.getScopes()).thenReturn(Set.of("test.read", "test.write"));
        return entity;
    }
}
