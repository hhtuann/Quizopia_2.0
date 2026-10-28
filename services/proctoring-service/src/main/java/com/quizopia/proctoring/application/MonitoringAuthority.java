package com.quizopia.proctoring.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface MonitoringAuthority {
    record Actor(UUID id, Set<String> roles, String userEvidence, Instant expiresAt) {
        @Override
        public String toString() {
            return "Actor[redacted]";
        }
    }

    record Snapshot(
            UUID actorId,
            String viewerRole,
            UUID attemptId,
            UUID publicationId,
            UUID classroomId,
            UUID studentId,
            boolean active,
            Instant deadlineAt,
            Instant checkedAt,
            UUID quizId) {
        /** Pre-FB015 spelling; a null quiz skips the Proctoring revocation fence probe. */
        public Snapshot(
                UUID actorId,
                String viewerRole,
                UUID attemptId,
                UUID publicationId,
                UUID classroomId,
                UUID studentId,
                boolean active,
                Instant deadlineAt,
                Instant checkedAt) {
            this(
                    actorId,
                    viewerRole,
                    attemptId,
                    publicationId,
                    classroomId,
                    studentId,
                    active,
                    deadlineAt,
                    checkedAt,
                    null);
        }
    }

    Snapshot check(Actor actor, UUID attempt, String role);

    /** Historical snapshots never implicitly authorize live media; adapters must opt in explicitly. */
    default Snapshot checkLive(Actor actor, UUID attempt, String role) {
        throw MonitoringFailure.unavailable();
    }
}
