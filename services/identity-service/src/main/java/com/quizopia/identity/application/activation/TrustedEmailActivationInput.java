package com.quizopia.identity.application.activation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TrustedEmailActivationInput(UUID userId, Instant verifiedAt) {
    public TrustedEmailActivationInput {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
    }
}
