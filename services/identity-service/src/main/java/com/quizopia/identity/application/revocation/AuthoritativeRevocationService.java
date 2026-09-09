package com.quizopia.identity.application.revocation;

import com.quizopia.identity.infrastructure.revocation.RedisRevocationStateStore;
import com.quizopia.identity.persistence.revocation.AuthoritativeRevocationPersistence;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class AuthoritativeRevocationService {
    private final AuthoritativeRevocationPersistence persistence;
    private final RedisRevocationStateStore redisStateStore;

    public AuthoritativeRevocationService(
            AuthoritativeRevocationPersistence persistence, RedisRevocationStateStore redisStateStore) {
        this.persistence = persistence;
        this.redisStateStore = redisStateStore;
    }

    public RevocationWriteResult recordRevocation(UUID userId, Instant revokedBefore) {
        AuthoritativeRevocationState state = persistence.upsert(userId, revokedBefore);
        RedisRevocationPropagationResult propagation = redisStateStore.publish(state);
        return new RevocationWriteResult(state, propagation.status());
    }

    public Optional<AuthoritativeRevocationState> find(UUID userId) {
        return persistence.find(userId);
    }

    public RevocationRepublishResult republish(UUID userId) {
        Optional<AuthoritativeRevocationState> state = persistence.find(userId);
        if (state.isEmpty()) {
            return new RevocationRepublishResult(Optional.empty(), RedisRevocationPropagationStatus.NOT_APPLICABLE);
        }

        RedisRevocationPropagationResult propagation = redisStateStore.publish(state.orElseThrow());
        return new RevocationRepublishResult(state, propagation.status());
    }
}
