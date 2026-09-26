package com.quizopia.identity.application.revocation;

import java.time.Instant;
import java.util.Objects;

public record RedisRevocationLookup(RedisRevocationLookupStatus status, Instant revokedBefore) {
    public RedisRevocationLookup {
        Objects.requireNonNull(status, "status must not be null");
        if (status == RedisRevocationLookupStatus.PRESENT) {
            Objects.requireNonNull(revokedBefore, "present lookup must have a cutoff");
        } else if (revokedBefore != null) {
            throw new IllegalArgumentException("non-present lookup must not have a cutoff");
        }
    }

    public static RedisRevocationLookup present(Instant revokedBefore) {
        return new RedisRevocationLookup(RedisRevocationLookupStatus.PRESENT, revokedBefore);
    }

    public static RedisRevocationLookup notPresent() {
        return new RedisRevocationLookup(RedisRevocationLookupStatus.NOT_PRESENT, null);
    }

    public static RedisRevocationLookup unavailable() {
        return new RedisRevocationLookup(RedisRevocationLookupStatus.UNAVAILABLE, null);
    }
}
