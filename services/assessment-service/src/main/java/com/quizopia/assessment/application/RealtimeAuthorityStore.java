package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.UUID;

/** Read-only authorization projections; no writes, no cross-service queries. */
public interface RealtimeAuthorityStore {
    AttemptView attempt(UUID attemptId);

    PublicationView publication(UUID publicationId);

    record AttemptView(
            UUID attemptId,
            UUID publicationId,
            UUID classroomId,
            UUID studentId,
            UUID ownerId,
            boolean active,
            Instant checkedAt,
            UUID quizId) {}

    record PublicationView(
            UUID publicationId, UUID classroomId, UUID ownerId, boolean open, Instant checkedAt, UUID quizId) {}
}
