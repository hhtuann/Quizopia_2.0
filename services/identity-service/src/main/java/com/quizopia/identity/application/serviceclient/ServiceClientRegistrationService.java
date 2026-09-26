package com.quizopia.identity.application.serviceclient;

import com.quizopia.identity.persistence.entity.OAuth2ServiceClientEntity;
import com.quizopia.identity.persistence.repository.OAuth2ServiceClientRepository;
import com.quizopia.identity.security.client.RawServiceClientSecret;
import com.quizopia.identity.security.client.ServiceClientSecretGenerator;
import com.quizopia.identity.security.client.ServiceClientSecretHasher;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ServiceClientRegistrationService {
    private static final int MAX_CLIENT_ID_LENGTH = 255;
    private static final int MAX_SCOPE_LENGTH = 255;

    private final OAuth2ServiceClientRepository repository;
    private final ServiceClientSecretGenerator secretGenerator;
    private final ServiceClientSecretHasher secretHasher;

    public ServiceClientRegistrationService(
            OAuth2ServiceClientRepository repository,
            ServiceClientSecretGenerator secretGenerator,
            ServiceClientSecretHasher secretHasher) {
        this.repository = repository;
        this.secretGenerator = secretGenerator;
        this.secretHasher = secretHasher;
    }

    @Transactional
    public ServiceClientRegistration register(String clientId, Collection<String> scopes) {
        String validatedClientId = validateClientId(clientId);
        Set<String> validatedScopes = validateScopes(scopes);
        RawServiceClientSecret rawSecret = secretGenerator.generate();
        OAuth2ServiceClientEntity entity =
                new OAuth2ServiceClientEntity(validatedClientId, secretHasher.encode(rawSecret), validatedScopes);
        OAuth2ServiceClientEntity persisted = repository.saveAndFlush(entity);
        return new ServiceClientRegistration(persisted.getId(), persisted.getClientId(), rawSecret);
    }

    @Transactional(readOnly = true)
    public Optional<ServiceClientDescriptor> findByClientId(String clientId) {
        return repository.findByClientId(validateClientId(clientId)).map(ServiceClientDescriptor::from);
    }

    @Transactional
    public ServiceClientDescriptor setEnabled(String clientId, boolean enabled) {
        OAuth2ServiceClientEntity entity = repository
                .findByClientId(validateClientId(clientId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown service client"));
        entity.setEnabled(enabled);
        return ServiceClientDescriptor.from(repository.saveAndFlush(entity));
    }

    private static String validateClientId(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        if (clientId.isBlank()
                || !clientId.equals(clientId.trim())
                || containsWhitespace(clientId)
                || clientId.length() > MAX_CLIENT_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "clientId must be a non-blank, whitespace-free value of at most 255 characters");
        }
        return clientId;
    }

    private static Set<String> validateScopes(Collection<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> validatedScopes = new LinkedHashSet<>();
        for (String scope : scopes) {
            Objects.requireNonNull(scope, "scope");
            if (scope.isBlank()
                    || !scope.equals(scope.trim())
                    || containsWhitespace(scope)
                    || scope.length() > MAX_SCOPE_LENGTH) {
                throw new IllegalArgumentException(
                        "scope must be a non-blank, whitespace-free value of at most 255 characters");
            }
            if (!validatedScopes.add(scope)) {
                throw new IllegalArgumentException("Duplicate scope");
            }
        }
        return validatedScopes;
    }

    private static boolean containsWhitespace(String value) {
        return value.chars().anyMatch(character -> Character.isWhitespace((char) character));
    }
}
