package com.quizopia.identity.application.revocation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuthoritativeRevocationState(UUID userId, Instant revokedBefore) {
    public AuthoritativeRevocationState {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(revokedBefore, "revokedBefore must not be null");
    }
}
