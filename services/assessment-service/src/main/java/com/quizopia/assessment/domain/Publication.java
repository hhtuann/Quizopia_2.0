package com.quizopia.assessment.domain;

import java.time.Instant;
import java.util.UUID;

public record Publication(
        UUID id,
        UUID ownerId,
        PublicationConfiguration configuration,
        State state,
        long revision,
        Long openedFromRevision,
        String privateLocator,
        Instant createdAt,
        Instant openedAt) {
    public enum State {
        DRAFT,
        OPEN,
        CLOSED
    }

    @Override
    public String toString() {
        return "Publication[redacted]";
    }
}
