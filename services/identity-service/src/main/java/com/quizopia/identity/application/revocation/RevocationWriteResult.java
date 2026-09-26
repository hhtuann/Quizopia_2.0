package com.quizopia.identity.application.revocation;

import java.util.Objects;

public record RevocationWriteResult(
        AuthoritativeRevocationState authoritativeState, RedisRevocationPropagationStatus propagationStatus) {
    public RevocationWriteResult {
        Objects.requireNonNull(authoritativeState, "authoritativeState must not be null");
        Objects.requireNonNull(propagationStatus, "propagationStatus must not be null");
    }
}
