package com.quizopia.identity.application.activation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TrustedEmailActivationResult(
        TrustedEmailActivationStatus status, UUID userId, Optional<Instant> verifiedAt) {
    public TrustedEmailActivationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        boolean requiresTimestamp = status == TrustedEmailActivationStatus.ACTIVATED
                || status == TrustedEmailActivationStatus.ALREADY_ACTIVE;
        if (requiresTimestamp != verifiedAt.isPresent()) {
            throw new IllegalArgumentException("verifiedAt presence does not match activation status");
        }
    }

    public static TrustedEmailActivationResult activated(UUID userId, Instant verifiedAt) {
        return new TrustedEmailActivationResult(
                TrustedEmailActivationStatus.ACTIVATED, userId, Optional.of(verifiedAt));
    }

    public static TrustedEmailActivationResult alreadyActive(UUID userId, Instant verifiedAt) {
        return new TrustedEmailActivationResult(
                TrustedEmailActivationStatus.ALREADY_ACTIVE, userId, Optional.of(verifiedAt));
    }

    public static TrustedEmailActivationResult notFound(UUID userId) {
        return new TrustedEmailActivationResult(TrustedEmailActivationStatus.NOT_FOUND, userId, Optional.empty());
    }

    public static TrustedEmailActivationResult conflict(UUID userId) {
        return new TrustedEmailActivationResult(TrustedEmailActivationStatus.CONFLICT, userId, Optional.empty());
    }
}
