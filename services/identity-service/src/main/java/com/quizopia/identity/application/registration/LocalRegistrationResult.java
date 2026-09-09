package com.quizopia.identity.application.registration;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record LocalRegistrationResult(LocalRegistrationStatus status, Optional<UUID> userId) {
    public LocalRegistrationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(userId, "userId");
        boolean requiresUser = status == LocalRegistrationStatus.CREATED_PENDING_VERIFICATION;
        if (requiresUser != userId.isPresent()) {
            throw new IllegalArgumentException("userId presence does not match registration status");
        }
    }

    public static LocalRegistrationResult created(UUID userId) {
        return new LocalRegistrationResult(
                LocalRegistrationStatus.CREATED_PENDING_VERIFICATION, Optional.of(Objects.requireNonNull(userId)));
    }

    public static LocalRegistrationResult usernameConflict() {
        return new LocalRegistrationResult(LocalRegistrationStatus.USERNAME_CONFLICT, Optional.empty());
    }
}
