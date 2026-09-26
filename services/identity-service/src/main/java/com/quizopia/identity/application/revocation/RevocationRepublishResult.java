package com.quizopia.identity.application.revocation;

import java.util.Objects;
import java.util.Optional;

public record RevocationRepublishResult(
        Optional<AuthoritativeRevocationState> authoritativeState, RedisRevocationPropagationStatus propagationStatus) {
    public RevocationRepublishResult {
        Objects.requireNonNull(authoritativeState, "authoritativeState must not be null");
        Objects.requireNonNull(propagationStatus, "propagationStatus must not be null");
    }
}
