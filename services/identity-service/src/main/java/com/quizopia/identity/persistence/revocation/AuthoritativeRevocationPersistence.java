package com.quizopia.identity.persistence.revocation;

import com.quizopia.identity.application.revocation.AuthoritativeRevocationState;
import com.quizopia.identity.persistence.entity.UserAccessRevocationEntity;
import com.quizopia.identity.persistence.repository.UserAccessRevocationRepository;
import com.quizopia.identity.persistence.repository.UserAccountRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class AuthoritativeRevocationPersistence {
    private final UserAccountRepository userAccountRepository;
    private final UserAccessRevocationRepository revocationRepository;

    public AuthoritativeRevocationPersistence(
            UserAccountRepository userAccountRepository, UserAccessRevocationRepository revocationRepository) {
        this.userAccountRepository = userAccountRepository;
        this.revocationRepository = revocationRepository;
    }

    public AuthoritativeRevocationState upsert(UUID userId, Instant revokedBefore) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(revokedBefore, "revokedBefore must not be null");
        if (!userAccountRepository.existsById(userId)) {
            throw new IllegalArgumentException("Identity user does not exist: " + userId);
        }

        revocationRepository.upsertRevokedBefore(userId, revokedBefore);
        return find(userId)
                .orElseThrow(() ->
                        new IllegalStateException("Revocation state was not readable after its authoritative write"));
    }

    public Optional<AuthoritativeRevocationState> find(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return revocationRepository.findById(userId).map(this::toState);
    }

    private AuthoritativeRevocationState toState(UserAccessRevocationEntity entity) {
        return new AuthoritativeRevocationState(entity.getUserId(), entity.getRevokedBefore());
    }
}
