package com.quizopia.identity.application.localauthentication;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record LocalAuthenticationResult(LocalAuthenticationStatus status, Optional<UUID> authenticatedUserId) {
    public LocalAuthenticationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId");
        boolean authenticated = status == LocalAuthenticationStatus.AUTHENTICATED;
        if (authenticated != authenticatedUserId.isPresent()) {
            throw new IllegalArgumentException("authenticatedUserId presence does not match authentication status");
        }
    }

    public static LocalAuthenticationResult authenticated(UUID userId) {
        return new LocalAuthenticationResult(
                LocalAuthenticationStatus.AUTHENTICATED, Optional.of(Objects.requireNonNull(userId)));
    }

    public static LocalAuthenticationResult failed() {
        return new LocalAuthenticationResult(LocalAuthenticationStatus.AUTHENTICATION_FAILED, Optional.empty());
    }
}
