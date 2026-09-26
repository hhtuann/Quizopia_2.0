package com.quizopia.quiz.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable identity and ownership for a quiz across its mutable draft and future versions. */
public record Quiz(UUID id, UUID ownerUserId, Instant createdAt) {
    public Quiz {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerUserId.equals(Objects.requireNonNull(userId, "userId"));
    }
}
