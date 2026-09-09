package com.quizopia.identity.application.googlelink;

import java.util.Objects;
import java.util.UUID;

public record GoogleLinkCandidate(UUID userId, boolean emailVerified) {
    public GoogleLinkCandidate {
        Objects.requireNonNull(userId, "userId");
    }
}
